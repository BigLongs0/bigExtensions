package eu.kanade.tachiyomi.extension.en.mangadot

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.webviewfetch.WebViewFetchInterceptor
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class MangaDot : KeiSource() {

    private val apiUrl get() = "$baseUrl/api"

    // mangadot.net challenges every non-browser client, but not the WebView.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" }
        addInterceptor(WebViewFetchInterceptor())
    }

    override suspend fun getPopularManga(page: Int): MangasPage = getSection("most-tracked", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSection("latest-updates", page)

    private suspend fun getSection(section: String, page: Int): MangasPage {
        val url = "$apiUrl/manga/section/$section".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("adult", "0")
            .build()

        return client.get(url).parseAs<MangaListDto>().toMangasPage(baseUrl)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .apply {
                if (filters.firstInstanceOrNull<ContentRatingFilter>()?.includesAdult != true) {
                    addQueryParameter("strict_adult", "0")
                }
                filters.forEach { filter ->
                    if (filter is UriFilter) filter.addToUri(this)
                }
            }
            .build()

        return client.get(url).parseAs<MangaListDto>().toMangasPage(baseUrl)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null
        if (url.pathSegments.firstOrNull() != "manga") return null

        val mangaId = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null
        val manga = SManga.create().apply { this.url = mangaId.toString() }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaId = getMangaUrl(manga).toHttpUrl().pathSegments.getOrNull(1)
            ?: return@coroutineScope SMangaUpdate(manga, chapters)
        val details = if (fetchDetails) {
            async {
                client.get("$apiUrl/manga/$mangaId")
                    .parseAs<MangaDetailResponseDto>()
                    .toSManga(baseUrl)
            }
        } else {
            null
        }
        val chapterList = if (fetchChapters) {
            async {
                client.get("$apiUrl/manga/$mangaId/chapters/list?lang=en")
                    .parseAs<List<ChapterDto>>()
                    .toSChapterList()
            }
        } else {
            null
        }

        SMangaUpdate(
            manga = details?.await() ?: manga,
            chapters = chapterList?.await() ?: chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val readerUrl = getChapterUrl(chapter).toHttpUrl()
        val chapterId = readerUrl.pathSegments.getOrNull(1) ?: return emptyList()
        val endpoint = if (readerUrl.queryParameter("source") == "user") "uploads" else "chapters"

        return client.get("$apiUrl/$endpoint/$chapterId/images")
            .parseAs<ChapterImagesDto>()
            .toPageList(baseUrl)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val facets = async {
            client.get("$apiUrl/search?facets=1&limit=1")
                .parseAs<FacetResponseDto>()
        }
        val tags = async {
            client.get("$apiUrl/manga/tags?in_use=1")
                .parseAs<TagCatalogDto>()
        }

        facets.await().toFilterData(tags.await()).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList = MangaDotFilters(
        data?.parseAs<FilterDataDto>(),
    ).getFilterList()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    companion object {
        private const val PAGE_LIMIT = 28
    }
}
