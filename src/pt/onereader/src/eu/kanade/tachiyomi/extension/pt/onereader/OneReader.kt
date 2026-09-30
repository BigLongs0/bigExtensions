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

        val manifest = client.get(manifestUrl(work, number), manifestHeaders, ensureSuccess = false).parseManifest()
        rememberGrants(work, number, manifest)
        return manifest.chapter.pages.mapIndexed { index, path -> Page(index, imageUrl = baseUrl + path) }
    }

    private fun manifestUrl(work: String, number: String) = "$baseUrl/api/reader/works".toHttpUrl().newBuilder()
        .addPathSegment(work)
        .addPathSegment("chapters")
        .addPathSegment(number)
        .build()

    // The latest page grants of the last few chapters. The site releases its own translations a few pages
    // at a time, so every page after a release has to use the newest grant instead of the one in its Page.
    private val chapterGrants = object : LinkedHashMap<String, ChapterGrants>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChapterGrants>) = size > MAX_CACHED_CHAPTERS
    }

    private fun rememberGrants(work: String, number: String, manifest: ReaderManifestDto): ChapterGrants {
        val grants = ChapterGrants(manifest.chapter.pages, manifest.proofServerKey)
        synchronized(chapterGrants) { chapterGrants["$work/$number"] = grants }
        return grants
    }

    private fun cachedGrants(work: String, number: String) = synchronized(chapterGrants) { chapterGrants["$work/$number"] }

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

        val work = segments[3]
        val number = segments[5]
        val pageNumber = segments[7].toInt()
        var grants = cachedGrants(work, number)
        var pageUrl = grants?.pageUrl(pageNumber) ?: request.url
        val deadline = System.currentTimeMillis() + PAGE_RELEASE_TIMEOUT

        while (true) {
            val pageHeaders = grants?.proofServerKey
                ?.let { mediaHeaders.newBuilder().addAll(transport.proofHeaders(it, pageUrl)).build() }
                ?: mediaHeaders
            val mediaRequest = request.newBuilder().url(pageUrl).headers(pageHeaders).build()
            val response = pageClient.newCall(mediaRequest).execute()

            if (response.isSuccessful) {
                val media = response.parseAs<MediaDto>()
                return pageClient.newCall(GET(media.url, headers)).execute()
                    .decodeMedia(media, transport)
                    .newBuilder()
                    .request(request)
                    .build()
            }

            val status = response.code
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            val error = response.readApiError()
            // Too many pages in a row: the site's reader waits and asks again for the same page.
            if (status == 429 && retryAfter != null) {
                waitForRelease(retryAfter, deadline)
                continue
            }
            // Expired grants (they last two hours), missing proofs and pages past the released window
            // all need a newer chapter manifest.
            if (status != 403 && error?.code !in RENEWABLE_ERRORS) throw error.toIOException(status)
            if (System.currentTimeMillis() > deadline) throw IOException(RELEASE_TIMEOUT_MESSAGE)
            grants = renewGrants(work, number, pageNumber, pageUrl, deadline)
            pageUrl = grants.pageUrl(pageNumber) ?: throw IOException("Página não encontrada, reabra o capítulo.")
        }
    }

    private fun ChapterGrants.pageUrl(pageNumber: Int) = pages.getOrNull(pageNumber - 1)?.let { (baseUrl + it).toHttpUrl() }

    // A page past the released window is only unlocked after the pages before it, so reaching it can take a while.
    private fun renewGrants(work: String, number: String, pageNumber: Int, failedUrl: HttpUrl, deadline: Long): ChapterGrants {
        synchronized(renewLock) {
            // Pages of the same chapter fail together, so another thread may have renewed the grants already.
            cachedGrants(work, number)?.takeIf { it.pageUrl(pageNumber) != failedUrl }?.let { return it }

            val url = manifestUrl(work, number).newBuilder()
                .apply { failedUrl.queryParameter("g")?.let { addQueryParameter("_or_pg", it) } }
                .addQueryParameter("_or_page", pageNumber.toString())
                .build()

            while (true) {
                val response = client.newCall(GET(url, manifestHeaders)).execute()
                if (response.isSuccessful) return rememberGrants(work, number, response.parseAs())

                val status = response.code
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                val error = response.readApiError()
                if (status != 429 || retryAfter == null) throw error.toIOException(status)
                waitForRelease(retryAfter, deadline)
            }
        }
    }

    private val renewLock = Any()

    // The site paces how fast pages are released and says how long to wait. This runs inside an interceptor,
    // so there is no coroutine to suspend.
    private fun waitForRelease(retryAfterSeconds: Long, deadline: Long) {
        val wait = (retryAfterSeconds * 1000).coerceIn(MIN_RELEASE_WAIT, MAX_RELEASE_WAIT)
        if (System.currentTimeMillis() + wait > deadline) throw IOException(RELEASE_TIMEOUT_MESSAGE)
        Thread.sleep(wait)
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

    private class ChapterGrants(val pages: List<String>, val proofServerKey: String?)

    companion object {
        private const val PAGE_SIZE = 24
        private const val MAX_CACHED_CHAPTERS = 4
        private const val PAGE_RELEASE_TIMEOUT = 60_000L
        private const val RELEASE_TIMEOUT_MESSAGE = "A OneReader ainda não liberou esta página, tente de novo em instantes."
        private const val MIN_RELEASE_WAIT = 750L
        private const val MAX_RELEASE_WAIT = 10_000L
        private val RENEWABLE_ERRORS = setOf("READER_GRANT_INVALID", "READER_PROOF_EXPIRED", "READER_PAGE_WINDOW_ADVANCE")
        private val MANGA_PATHS = listOf("obra", "leitor")
        private const val MEDIA_ACCEPT = "application/vnd.onereader.media+json"
        private const val CLIENT_KEY_HEADER = "X-OneReader-Client-Key"
        private const val KEY_TRANSPORT_HEADER = "X-OneReader-Key-Transport"
    }
}
