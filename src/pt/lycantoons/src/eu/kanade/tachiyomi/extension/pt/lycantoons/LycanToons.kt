package eu.kanade.tachiyomi.extension.pt.lycantoons

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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.long
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

@Source
abstract class LycanToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    // Cloudflare blocks non-browser clients that send the site as Referer, and challenges any
    // request without Accept-Language.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .removeAll("Referer")
        .removeAll("Origin")
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries(SeriesRequestDto(page, PAGE_SIZE, orderBy = "views"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries(SeriesRequestDto(page, PAGE_SIZE, orderBy = "updated"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchSeries(
        SeriesRequestDto(
            page = page,
            limit = PAGE_SIZE,
            search = query.trim().takeIf(String::isNotEmpty),
            seriesType = filters.firstInstanceOrNull<TypeFilter>()?.selectedValue,
            status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue,
            tags = filters.firstInstanceOrNull<GenreFilter>()?.selectedValues,
            original = filters.firstInstanceOrNull<OriginalFilter>()?.state?.takeIf { it },
            orderBy = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "views",
        ),
    )

    private suspend fun fetchSeries(body: SeriesRequestDto): MangasPage {
        val result = client.post("$baseUrl/api/series", body.toJsonRequestBody()).parseAs<SeriesListDto>()

        return MangasPage(result.series.filterNot(SeriesDto::isNovel).map(SeriesDto::toSManga), result.hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null

        return fetchDetails(slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.url.substringAfterLast('/')
        val details = if (fetchDetails) async { fetchDetails(slug) } else null
        val chapterList = if (fetchChapters) async { fetchChapters(slug).map { it.toSChapter(slug) } } else null

        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    private suspend fun fetchDetails(slug: String): SManga = client.get("$baseUrl/series/$slug").asJsoup()
        .extractNextJs<SeriesPageDto> { it is JsonObject && "obra" in it && "chapters" in it }
        ?.obra
        ?.toSManga()
        ?: throw IOException("Obra não encontrada")

    // Entries saved before 1.6.2 keep the "/series/" prefix.
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url.substringAfterLast('/')}"

    private suspend fun fetchChapters(slug: String): List<ChapterDto> = client.get("$baseUrl/api/series/$slug/chapters").parseAs<ChapterListDto>().chapters

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.memo["id"]?.long ?: findChapterId(chapter)

        return client.get("$baseUrl/api/chapters/$id/view-pages").parseAs<PagesDto>().pages
            .mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    private suspend fun findChapterId(chapter: SChapter): Long {
        val (slug, number) = chapter.url.split('/').takeLast(2)

        return fetchChapters(slug).firstOrNull { it.label == number }?.id ?: throw IOException("Capítulo não encontrado")
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        OriginalFilter(),
        GenreFilter(),
    )

    companion object {
        private const val PAGE_SIZE = 24
    }
}
