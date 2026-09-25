package eu.kanade.tachiyomi.extension.pt.onereader

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

@Source
abstract class OneReader : KeiSource() {

    private val siteHost by lazy { baseUrl.toHttpUrl().host }

    private val transport by lazy { ReaderTransport() }

    private val pageInterceptor = Interceptor(::decodeProtectedPage)

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
        .addInterceptor(pageInterceptor)
        .rateLimit(8) { it.host == siteHost }

    // Some apps treat any 403 from a Cloudflare host as a challenge, which would hide an expired page grant.
    private val pageClient by lazy {
        client.newBuilder()
            .apply { interceptors().removeAll { it === pageInterceptor || it.javaClass.simpleName == "CloudflareInterceptor" } }
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getCatalog(page, sort = "VIEWS")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/reader/home/updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("filter", "all")
            .build()

        return client.get(url).parseAs<UpdatesDto>().toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getCatalog(
        page = page,
        sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "VIEWS",
        query = query,
        filters = filters,
    )

    private suspend fun getCatalog(page: Int, sort: String, query: String = "", filters: FilterList = FilterList()): MangasPage {
        val url = "$baseUrl/api/reader/catalog/page".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query.trim())
                filters.forEach { if (it is UrlFilter) it.addToUrl(this) }
            }
            .build()

        return client.get(url).parseAs<CatalogDto>().toMangasPage()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() !in MANGA_PATHS) return null
        val id = url.queryParameter("id")?.takeIf(String::isNotBlank) ?: return null
        return getWork(id).work.toSManga()
    }

    // Details and chapters come from the same response.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = getWork(manga.url)
        return SMangaUpdate(details.work.toSManga(), details.toSChapterList())
    }

    private suspend fun getWork(id: String): WorkDetailsDto {
        val url = "$baseUrl/api/reader/works".toHttpUrl().newBuilder()
            .addPathSegment(id)
            .build()

        return client.get(url).parseAs()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = (baseUrl + chapter.url).toHttpUrl()
        val work = url.queryParameter("id") ?: throw IOException("Capítulo inválido")
        val number = url.queryParameter("cap") ?: throw IOException("Capítulo inválido")

        return client.get(manifestUrl(work, number), manifestHeaders, ensureSuccess = false)
            .parseManifest()
            .chapter.pages
            .mapIndexed { index, path -> Page(index, imageUrl = baseUrl + path) }
    }

    private fun manifestUrl(work: String, number: String) = "$baseUrl/api/reader/works".toHttpUrl().newBuilder()
        .addPathSegment(work)
        .addPathSegment("chapters")
        .addPathSegment(number)
        .build()

    // The site always announces an ephemeral key; its own translations refuse to load without one.
    private val manifestHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set(CLIENT_KEY_HEADER, transport.publicKey)
            .set(KEY_TRANSPORT_HEADER, KEY_TRANSPORT)
            .build()
    }

    private val mediaHeaders by lazy {
        headersBuilder()
            .set("Accept", MEDIA_ACCEPT)
            .set(CLIENT_KEY_HEADER, transport.publicKey)
            .set(KEY_TRANSPORT_HEADER, KEY_TRANSPORT)
            .build()
    }

    // Page links answer with the key and location of an obfuscated image instead of the image itself.
    private fun decodeProtectedPage(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val segments = request.url.pathSegments
        if (request.url.host != siteHost || segments.getOrNull(6) != "pages") return chain.proceed(request)

        val mediaRequest = request.newBuilder().headers(mediaHeaders).build()
        var response = pageClient.newCall(mediaRequest).execute()
        // Page grants last two hours, so a cached page list needs a fresh chapter manifest.
        if (response.code == 403) {
            response.close()
            val pageUrl = renewPageUrl(segments[3], segments[5], request.url.encodedPath)
            response = pageClient.newCall(mediaRequest.newBuilder().url(pageUrl).build()).execute()
        }
        if (!response.isSuccessful) throw response.toApiError()

        val media = response.parseAs<MediaDto>()
        return pageClient.newCall(GET(media.url, headers)).execute()
            .decodeMedia(media, transport)
            .newBuilder()
            .request(request)
            .build()
    }

    private fun renewPageUrl(work: String, number: String, pagePath: String): String {
        val pages = client.newCall(GET(manifestUrl(work, number), manifestHeaders)).execute()
            .parseManifest()
            .chapter.pages
        val path = pages.firstOrNull { it.substringBefore('?') == pagePath }
            ?: throw IOException("Página não encontrada, reabra o capítulo.")
        return baseUrl + path
    }

    private fun Response.parseManifest(): ReaderManifestDto {
        if (!isSuccessful) throw toApiError()
        return parseAs()
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/api/reader/catalog/meta")
        .parseAs<CatalogMetaDto>()
        .toFilterData()
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(SortFilter())
            add(FormatFilter())
            add(StatusFilter())
            data?.parseAs<FilterDataDto>()?.genres?.takeIf(List<String>::isNotEmpty)?.let {
                add(Filter.Separator())
                add(GenreFilter(it))
                add(GenreModeFilter())
            }
        },
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra?id=${manga.url}"

    companion object {
        private const val PAGE_SIZE = 24
        private val MANGA_PATHS = listOf("obra", "leitor")
        private const val MEDIA_ACCEPT = "application/vnd.onereader.media+json"
        private const val CLIENT_KEY_HEADER = "X-OneReader-Client-Key"
        private const val KEY_TRANSPORT_HEADER = "X-OneReader-Key-Transport"
    }
}
