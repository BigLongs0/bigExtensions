package eu.kanade.tachiyomi.extension.pt.mangadash

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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Source
abstract class MangaDash : KeiSource() {

    private val coverBaseUrl by lazy { "$baseUrl/static/covers/".toHttpUrl() }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = this
        .addInterceptor(::fetchPdfPage)
        .rateLimit(2) { it.host == baseUrl.toHttpUrl().host }

    // Chapters are single PDFs; each page is served as its embedded JPEG through a byte range.
    private fun fetchPdfPage(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val range = request.url.fragment?.takeIf { request.url.encodedPath.endsWith(".pdf") }
            ?: return chain.proceed(request)

        val response = chain.proceed(request.newBuilder().header("Range", "bytes=$range").build())
        if (response.code != 206) {
            response.close()
            throw IOException("O servidor do PDF não aceitou leitura parcial (HTTP ${response.code})")
        }

        return response.newBuilder()
            .code(200)
            .message("OK")
            .header("Content-Type", "image/jpeg")
            .removeHeader("Content-Range")
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = fetchList(page, sort = "populares")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/capitulos?page=$page").asJsoup()
        val mangas = document.select("div.card-new h3.card-title a[href^=/manga/]").map { link ->
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text()
                thumbnail_url = link.closest("div.card-new")?.selectFirst(".card-poster img")?.absUrl("src")
            }
        }.distinctBy(SManga::url)

        return MangasPage(mangas, document.selectFirst("a.page-link.next") != null)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = fetchList(
        page = page,
        query = query.trim(),
        sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "populares",
        category = filters.firstInstanceOrNull<CategoryFilter>()?.selectedValue,
        status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue,
        adult = filters.firstInstanceOrNull<AdultFilter>()?.selectedValue,
    )

    private suspend fun fetchList(
        page: Int,
        sort: String,
        query: String = "",
        category: String? = null,
        status: String? = null,
        adult: String? = null,
    ): MangasPage {
        val url = "$baseUrl/api/mangas/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sort)
            .apply {
                if (query.isNotEmpty()) addQueryParameter("q", query)
                category?.let { addQueryParameter("categoria", it) }
                status?.let { addQueryParameter("status", it) }
                adult?.let { addQueryParameter("plus18", it) }
            }
            .build()

        val result = client.get(url).parseAs<MangaListDto>()

        return MangasPage(result.items.map { it.toSManga(coverBaseUrl) }, result.hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null
        val path = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null

        return parseDetails(fetchSeriesPage("/manga/$path"), "/manga/$path")
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchSeriesPage(manga.url)

        return SMangaUpdate(parseDetails(document, manga.url), parseChapters(document))
    }

    private suspend fun fetchSeriesPage(path: String): Document {
        val response = client.get(baseUrl + path)
        if (response.request.url.pathSegments.firstOrNull() == "auth") {
            response.close()
            throw IOException("Obra +18: entre na sua conta pela WebView e ative o conteúdo adulto no perfil.")
        }
        return response.asJsoup()
    }

    private fun parseDetails(document: Document, path: String) = SManga.create().apply {
        url = path
        title = document.selectFirst("h1.neon-title")!!.text()
        thumbnail_url = document.selectFirst(".cover-3d img")?.absUrl("src")
        author = document.selectFirst("a.tag-author")?.text()
        genre = document.select(".manga-tags a.tag:not(.tag-author)").joinToString { it.text() }
        status = when (document.selectFirst("div.stat-item[title=Status] span")?.text()?.lowercase()) {
            "em lançamento" -> SManga.ONGOING
            "concluído" -> SManga.COMPLETED
            "em hiato" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        description = buildString {
            document.selectFirst("p.manga-description")?.text()?.let(::append)
            document.selectFirst("h2.alt-title")?.text()?.takeIf(String::isNotEmpty)?.let {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos: ${it.replace(" | ", ", ")}")
            }
        }.takeIf(String::isNotEmpty)
    }

    private fun parseChapters(document: Document): List<SChapter> = document.select("a.chapter-row").map { row ->
        SChapter.create().apply {
            setUrlWithoutDomain(row.absUrl("href"))
            name = row.selectFirst(".chapter-title-group h4")!!.text()
            chapter_number = row.attr("data-chapter-number").toFloatOrNull() ?: -1f
            date_upload = dateFormat.tryParseDate(row.selectFirst(".chapter-meta-info:has(i.fa-calendar)")?.text(), ZONE)
        }
    }.sortedByDescending(SChapter::chapter_number)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val viewer = client.get(baseUrl + chapter.url).asJsoup()
            .selectFirst("script#chapterViewerData")!!
            .data()
            .parseAs<ChapterViewerDto>()
        val pdfUrl = viewer.pdfUrl
            ?: throw IOException("Capítulo +18: entre na sua conta pela WebView e ative o conteúdo adulto no perfil.")

        val ranges = client.get(pdfUrl, NO_STORE).use { it.body.source().jpegStreamRanges() }
        if (ranges.isEmpty()) throw IOException("O PDF do capítulo não tem páginas em JPEG")

        return ranges.mapIndexed { index, range -> Page(index, imageUrl = "$pdfUrl#${range.first}-${range.last}") }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        CategoryFilter(),
        StatusFilter(),
        AdultFilter(),
    )

    companion object {
        private val ZONE = ZoneId.of("America/Sao_Paulo")
        private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val NO_STORE = CacheControl.Builder().noStore().build()
    }
}
