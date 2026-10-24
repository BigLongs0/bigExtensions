package eu.kanade.tachiyomi.extension.pt.blitztoons

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
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

@Source
abstract class BlitzToons : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("populares")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("recentes")))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/obras".toHttpUrl().newBuilder()
            .apply {
                if (query.isNotBlank()) {
                    addQueryParameter("q", query)
                }
                filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
            }
            .addQueryParameter("p", page.toString())
            .build()

        return client.get(url).asJsoup().toMangasPage()
    }

    private fun Document.toMangasPage(): MangasPage {
        // The site also hosts text novels, which the reader cannot show.
        val mangas = select("article.card")
            .filter { it.selectFirst(".card-type-novel") == null }
            .map { card ->
                val link = card.selectFirst("h3.card-title a")!!

                SManga.create().apply {
                    url = link.absUrl("href").toHttpUrl().pathSegments[1]
                    title = link.text()
                    thumbnail_url = card.selectFirst("img")?.absUrl("src")
                }
            }

        return MangasPage(mangas, selectFirst("nav.pager a:contains(Próxima)") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() !in MANGA_PATH_SEGMENTS) return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null
        val manga = SManga.create().apply { this.url = slug }

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
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(
            manga = document.toSManga(manga.url),
            // Early access chapters are only readable with a VIP account.
            chapters = document.select("ul.chaplist li.chap:not(.chap-locked)").map(::toSChapter),
        )
    }

    private fun Document.toSManga(slug: String) = SManga.create().apply {
        url = slug
        title = selectFirst(".sbanner-txt h1")!!.text()
        thumbnail_url = selectFirst("img.sbanner-cover")?.absUrl("src")
        genre = select(".sbanner-txt .pill, .sgenres span").joinToString { it.text() }
        status = parseStatus(selectFirst("dl.smeta div:has(dt:contains(Status)) dd")?.text())
        description = buildString {
            selectFirst(".syn")?.text()?.let(::append)
            selectFirst("p.salt")?.text()?.takeIf(String::isNotEmpty)?.let {
                append("\n\nTítulos alternativos: ", it)
            }
        }.trim()
    }

    private fun parseStatus(status: String?): Int {
        val value = status?.lowercase() ?: return SManga.UNKNOWN

        return when {
            "lançamento" in value -> SManga.ONGOING
            "complet" in value -> SManga.COMPLETED
            "hiato" in value -> SManga.ON_HIATUS
            "cancel" in value -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun toSChapter(element: Element) = SChapter.create().apply {
        // The reader lives at /ler/<manga slug>/<chapter number>.
        val segments = element.selectFirst("a.chap-alvo")!!.absUrl("href").toHttpUrl().pathSegments
        url = segments.drop(1).joinToString("/")
        name = element.selectFirst(".chap-nome")!!.text()
        chapter_number = segments.last().toFloatOrNull() ?: -1f
        date_upload = parseDate(element.selectFirst("time")?.attr("datetime"))
    }

    // Older chapters carry JavaScript's Date.toString() output instead of an ISO instant.
    private fun parseDate(date: String?): Long = Instant.tryParse(date).takeIf { it != 0L }
        ?: JS_DATE_FORMAT.tryParseZonedDateTime(date?.substringBefore(" ("))

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(getChapterUrl(chapter))
        .asJsoup()
        .select("div.pages img.page")
        .mapIndexed { index, element -> Page(index, imageUrl = element.absUrl("src")) }

    override val supportsFilterFetching: Boolean get() = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/obras")
        .asJsoup()
        .select("details.filter-gen a[href*=genero=]")
        .mapNotNull { element ->
            val slug = element.absUrl("href").toHttpUrl().queryParameter("genero") ?: return@mapNotNull null
            GenreDto(element.text(), slug)
        }
        .toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreDto>>()

        return FilterList(listOfNotNull(SortFilter(), TypeFilter(), StatusFilter(), genres?.let(::GenreFilter)))
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/ler/${chapter.url}"

    companion object {
        private val MANGA_PATH_SEGMENTS = listOf("obra", "ler")
        private val JS_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'xx", Locale.ENGLISH)
    }
}
