package eu.kanade.tachiyomi.extension.en.mangaball

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Source
abstract class MangaBall : KeiSource() {

    private val apiUrl get() = "$baseUrl/api/v1"

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, "", FilterList(SortFilter.popular()))

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, "", FilterList(SortFilter.latest()))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = search(page, query, filters)

    private suspend fun search(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/title/search-advanced".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("keyword", query.trim())
            addQueryParameter("language", LANGUAGE)
            filters.forEach { if (it is UrlFilter) it.addToUrl(this) }

            val tags = filters.filterIsInstance<TagFilter>()
            tags.flatMap(TagFilter::included).takeIf(List<String>::isNotEmpty)
                ?.let { addQueryParameter("included_tags", it.joinToString(",")) }
            tags.flatMap(TagFilter::excluded).takeIf(List<String>::isNotEmpty)
                ?.let { addQueryParameter("excluded_tags", it.joinToString(",")) }

            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_SIZE.toString())
        }.build()

        return client.get(url).parseAs<MangaListResponseDto>().toMangasPage()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val id = manga.url.toMangaKey().id
        val details = if (fetchDetails) async { fetchDetails(id) } else null
        val chapterList = if (fetchChapters) async { fetchChapterList(id) } else null

        SMangaUpdate(
            manga = details?.await() ?: manga,
            chapters = chapterList?.await() ?: chapters,
        )
    }

    private suspend fun fetchDetails(id: String): SManga = client.get("$apiUrl/title/detail/$id")
        .parseAs<MangaDetailsResponseDto>()
        .data
        .toSManga()

    // The API rejects the charset that String request bodies append to the JSON media type.
    private suspend fun fetchChapterList(titleId: String): List<SChapter> = client.post(
        "$apiUrl/chapter/chapter-listing-by-title-id",
        ChapterListRequestDto(titleId, LANGUAGE).toJsonString().toByteArray().toRequestBody(JSON_MEDIA_TYPE),
    ).parseAs<ChapterListResponseDto>().toSChapterList()

    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchChapterPages(getChapterUrl(chapter).toHttpUrl())
        ?.pages
        .orEmpty()
        .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }

    private suspend fun fetchChapterPages(url: HttpUrl): ChapterPagesDto? = client.get(url)
        .asJsoup()
        .extractNextJs<ChapterPagesDto> { it is JsonObject && "pages" in it && "title_id" in it }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/tag/get-grouped")
        .parseAs<TaxonomyResponseDto>()
        .data
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = MangaBallFilters(
        data?.parseAs<TaxonomyDto>(),
    ).getFilterList()

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/title-detail/${manga.url.toMangaKey().id}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter-detail/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host.removePrefix("www.") != baseUrl.toHttpUrl().host.removePrefix("www.")) return null

        val segment = url.pathSegments.getOrNull(1) ?: return null
        val titleId = when (url.pathSegments.first()) {
            // Older links carry the slug before the id.
            "title-detail" -> TITLE_ID_REGEX.find(segment)?.value

            "chapter-detail" -> fetchChapterPages(url)?.titleId

            else -> null
        } ?: return null

        return fetchDetails(titleId)
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val LANGUAGE = "en"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val TITLE_ID_REGEX = Regex("[0-9a-fA-F]{24}$")
    }
}
