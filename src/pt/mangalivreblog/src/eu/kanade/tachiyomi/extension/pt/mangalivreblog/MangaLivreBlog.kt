package eu.kanade.tachiyomi.extension.pt.mangalivreblog

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class MangaLivreBlog : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Accept", "text/html,application/xhtml+xml,*/*")

    override suspend fun getPopularManga(page: Int): MangasPage = archive(page, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = archive(page, "recentes")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = paged("/", page).newBuilder()
                .addQueryParameter("s", query)
                .build()

            return client.get(url).asJsoup().toMangasPage()
        }

        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedValue
        if (!genre.isNullOrBlank()) {
            return client.get(paged("/genre/$genre/", page)).asJsoup().toMangasPage()
        }

        return archive(page, filters.firstInstanceOrNull<OrderFilter>()?.selectedValue ?: "recentes")
    }

    private suspend fun archive(page: Int, order: String): MangasPage {
        val url = paged("/manga/", page).newBuilder()
            .addQueryParameter("ordem", order)
            .build()

        return client.get(url).asJsoup().toMangasPage()
    }

    /** WordPress paginates archives on a path segment, not a query parameter. */
    private fun paged(path: String, page: Int): HttpUrl {
        val suffix = if (page > 1) "page/$page/" else ""

        return "$baseUrl$path$suffix".toHttpUrl()
    }

    // The archive and the search/genre grids use different card markup for the same data.
    private fun Document.toMangasPage(): MangasPage {
        val entries = select("article.home-manga-card, div.manga-card")
            .mapNotNull(::toSMangaOrNull)
            .distinctBy(SManga::url)

        return MangasPage(entries, selectFirst("a.next.page-numbers") != null)
    }

    private fun toSMangaOrNull(card: Element): SManga? {
        val href = card.selectFirst("a[href*=/manga/]")?.absUrl("href")?.toHttpUrl() ?: return null
        val slug = href.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val title = card.selectFirst("h3")?.text()?.takeIf(String::isNotBlank) ?: return null

        return SManga.create().apply {
            url = "/manga/$slug/"
            this.title = title
            thumbnail_url = card.selectFirst("img")?.absUrl("src")
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "manga") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val manga = SManga.create().apply { this.url = "/manga/$slug/" }

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
            chapters = document.select("ul.chapters-list li.chapter-item a.chapter-link")
                .mapNotNull(::toSChapterOrNull)
                .disambiguated()
                .sortedByDescending(SChapter::chapter_number),
        )
    }

    /**
     * The site publishes reuploads under the same label, so several entries read "Capítulo 303".
     * Number them so they stay distinguishable in the chapter list.
     */
    private fun List<SChapter>.disambiguated(): List<SChapter> {
        val counts = groupingBy(SChapter::name).eachCount()
        val seen = mutableMapOf<String, Int>()

        return map { chapter ->
            if (counts[chapter.name] == 1) return@map chapter
            val index = seen.merge(chapter.name, 1, Int::plus)!!
            chapter.apply { name = "$name (v$index)" }
        }
    }

    private fun Document.toSManga(mangaUrl: String) = SManga.create().apply {
        url = mangaUrl
        // The heading also holds a language flag, so only its own text is the title.
        title = selectFirst("h1.manga-title")?.ownText()?.takeIf(String::isNotEmpty)
            ?: selectFirst("title")!!.text()
        thumbnail_url = selectFirst("img.manga-cover-image, img.wp-post-image")?.absUrl("src")
        description = selectFirst("meta[name=description]")?.attr("content")?.takeIf(String::isNotBlank)
        status = when (selectFirst("div.manga-status, span.manga-status")?.text()?.trim()) {
            "Em Andamento", "Em Lançamento" -> SManga.ONGOING
            "Completo", "Concluído" -> SManga.COMPLETED
            "Hiato" -> SManga.ON_HIATUS
            "Cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun toSChapterOrNull(element: Element): SChapter? {
        val href = element.absUrl("href").toHttpUrl()
        val slug = href.pathSegments.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        // Untitled chapters come labeled "Capítulo 13: Capítulo 13".
        val label = element.selectFirst("span.chapter-number")?.text()
            ?.replace(REPEATED_LABEL_REGEX, "$1")

        return SChapter.create().apply {
            url = "/capitulo/$slug/"
            name = label?.takeIf(String::isNotEmpty) ?: "Capítulo"
            chapter_number = CHAPTER_NUMBER_REGEX.find(label.orEmpty())?.value?.toFloatOrNull() ?: -1f
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url)
        .asJsoup()
        .select("div.chapter-images img.chapter-image")
        .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("src")) }

    override val supportsFilterFetching: Boolean get() = true

    // The genre taxonomy is not exposed over the REST API, only as archive links on the home page.
    override suspend fun fetchFilterData(): JsonElement {
        val genres = client.get(baseUrl).asJsoup()
            .select("a[href*=/genre/]")
            .mapNotNull { element ->
                val slug = element.absUrl("href").toHttpUrl().pathSegments.getOrNull(1)
                    ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val name = element.text().takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
                    ?: return@mapNotNull null

                GenreEntry(name, slug)
            }
            .distinctBy(GenreEntry::slug)
            .sortedBy(GenreEntry::name)

        return FilterData(genres).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<FilterData>()?.genres

        return FilterList(listOfNotNull(OrderFilter(), genres?.let(::GenreFilter)))
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
        private val REPEATED_LABEL_REGEX = Regex("""^(.+?):\s*\1$""")
    }
}
