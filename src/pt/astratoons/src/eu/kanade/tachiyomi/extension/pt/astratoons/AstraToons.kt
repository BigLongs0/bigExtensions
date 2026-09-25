package eu.kanade.tachiyomi.extension.pt.astratoons

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

@Source
abstract class AstraToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Accept", "text/html,application/xhtml+xml,*/*")

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse(page, sort = "latest")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = browse(page, query = query)

    private suspend fun browse(page: Int, query: String? = null, sort: String? = null): MangasPage {
        val url = "$baseUrl/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                query?.takeIf(String::isNotBlank)?.let { addQueryParameter("search", it) }
                sort?.let { addQueryParameter("sort", it) }
            }
            .build()

        val document = client.get(url).asJsoup()

        // Both the grid and the list layout are rendered, so the same series appears twice.
        val entries = document.select("a[href*=/comics/]")
            .mapNotNull(::toSMangaOrNull)
            .distinctBy(SManga::url)

        return MangasPage(entries, document.selectFirst("a[rel=next]") != null)
    }

    private fun toSMangaOrNull(element: Element): SManga? {
        val href = element.absUrl("href").toHttpUrl()
        if (href.pathSegments.size != 2 || href.pathSegments[0] != "comics") return null
        val title = element.selectFirst("h2, h3")?.text()?.takeIf(String::isNotBlank) ?: return null

        return SManga.create().apply {
            url = "/comics/${href.pathSegments[1]}"
            this.title = title
            thumbnail_url = element.selectFirst("img[src*=/storage/covers/]")?.absUrl("src")
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "comics") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val manga = SManga.create().apply { this.url = "/comics/$slug" }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()

        return SMangaUpdate(
            manga = document.toSManga(manga.url),
            chapters = fetchChapterList(document, manga.url),
        )
    }

    // The series page carries no status or genre, only the title, cover and synopsis.
    private fun Document.toSManga(mangaUrl: String) = SManga.create().apply {
        url = mangaUrl
        title = selectFirst("h1")?.text()?.takeIf(String::isNotBlank)
            ?: metaContent("og:title").substringBeforeLast(" - AstraToons")
        thumbnail_url = metaContent("og:image").takeIf(String::isNotBlank)
        description = metaContent("og:description").takeIf(String::isNotBlank)
    }

    private fun Document.metaContent(property: String): String = selectFirst("meta[property=$property]")?.attr("content").orEmpty()

    /** The detail page only ships the newest chapters, the rest come from a paginated fragment. */
    private suspend fun fetchChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val comicId = COMIC_ID_REGEX.find(document.html())?.groupValues?.get(1)
            ?: throw IOException("Não achei o identificador da obra")

        val chapters = mutableListOf<SChapter>()
        var page = 1

        while (page <= MAX_CHAPTER_PAGES) {
            val url = "$baseUrl/api/comics/$comicId/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .build()
            val fragment = client.get(url, ajaxHeaders).parseAs<ChapterFragmentDto>()

            val parsed = Jsoup.parseBodyFragment(fragment.html, baseUrl)
                .select("a[href*=/capitulo/]")
                .mapNotNull { it.toSChapterOrNull(mangaUrl) }

            if (parsed.isEmpty()) break
            chapters += parsed
            if (!fragment.hasMore) break
            page++
        }

        return chapters.distinctBy(SChapter::url).sortedByDescending(SChapter::chapter_number)
    }

    private fun Element.toSChapterOrNull(mangaUrl: String): SChapter? {
        val href = absUrl("href").toHttpUrl()
        val number = href.pathSegments.lastOrNull()?.toFloatOrNull() ?: return null

        return SChapter.create().apply {
            url = "$mangaUrl/capitulo/${href.pathSegments.last()}"
            name = selectFirst("span.truncate")?.text()?.takeIf(String::isNotBlank)
                ?: "Capítulo ${number.toString().removeSuffix(".0")}"
            chapter_number = number
        }
    }

    // Pages are served as canvas placeholders that the site swaps for an image after loading.
    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url)
        .asJsoup()
        .select("#reader-container canvas[data-src], #reader-container img[src]")
        .map { if (it.hasAttr("data-src")) it.absUrl("data-src") else it.absUrl("src") }
        .filter { "/storage/chapters/" in it }
        .mapIndexed { index, url -> Page(index, imageUrl = url) }

    private val ajaxHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    companion object {
        private const val MAX_CHAPTER_PAGES = 100
        private val COMIC_ID_REGEX = Regex("""favorites/toggle/(\d+)""")
    }
}

@Serializable
class ChapterFragmentDto(
    val html: String = "",
    val hasMore: Boolean = false,
)
