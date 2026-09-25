package eu.kanade.tachiyomi.multisrc.aurora

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.extractNextJsRsc
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Aurora : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3, 1.seconds)
        .addCookie("mnx_adulto" to "1")

    override fun Headers.Builder.configureHeaders() = set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")
        .set("Alt-Used", baseUrl.substringAfterLast("/"))

    override suspend fun getPopularManga(page: Int) = getMangasPage(client.get("$baseUrl/catalogo"))

    override suspend fun getLatestUpdates(page: Int) = getMangasPage(client.get("$baseUrl/novidades"))

    private fun getMangasPage(response: Response): MangasPage {
        val dto = response.extractNextJs<SeriesDto>()
        return MangasPage(dto?.toSMangaList() ?: emptyList(), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val mangas = getPopularManga(page).mangas.filter { it.title.contains(query, ignoreCase = true) }
        return MangasPage(mangas, false)
    }

    override fun getMangaUrl(manga: SManga): String = entryURL(manga.memo)

    override fun getChapterUrl(chapter: SChapter) = "${entryURL(chapter.memo)}/${chapter.chapter_number}"

    private fun entryURL(memo: JsonObject): String = "$baseUrl/${memo["type"]!!.string}/${memo["slug"]!!.string}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val manga = getSMangaDetails(document, manga)
        val chapters = document.extractNextJs<ChapterListDto>()
            ?.toSChapterList(manga)
            ?: emptyList()

        return SMangaUpdate(manga, chapters)
    }

    private val mangaDetailsDescriptionRegex = """description":"([^"]+)""".toRegex()
    private val mangaDetailsGenreRegex = """genre":([^]]+])""".toRegex()
    private val mangaDetailsAuthorRegex = """author[^.]+name":"([^"]+)""".toRegex()
    private val cleanRegex = """\\{2,}""".toRegex()

    private fun getSMangaDetails(
        document: Document,
        manga: SManga,
    ): SManga = document.selectFirst("script[type]:containsData(ComicSeries)")?.data()
        ?.parseAs<MangaDto>()
        ?.toSManga()
        ?: manga.apply {
            document.selectFirst("script:containsData(ComicSeries)")?.data()
                ?.replace(cleanRegex, "")
                ?.let {
                    description = mangaDetailsDescriptionRegex.find(it)?.groupValues?.last()
                    genre = mangaDetailsGenreRegex.find(it)?.groupValues?.last()?.parseAs<List<String>>()?.joinToString()
                    author = mangaDetailsAuthorRegex.find(it)?.groupValues?.last()
                }
        }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pageHeaders = headersBuilder()
            .set("rsc", "1")
            .set("Referer", entryURL(chapter.memo))
            .set("Sec-Fetch-Mode", "cors")
            .set("Sec-Fetch-Dest", "empty")
            .set("Sec-Fetch-Site", "same-origin")
            .set("next-url", entryURL(chapter.memo).toHttpUrl().encodedPath)
            .set("Accept", "*/*")
            .build()
        val payload = client.get(getChapterUrl(chapter), pageHeaders).use { it.body.string() }

        payload.extractNextJsRsc<PagesDto>()?.let { pages ->
            return pages.toPageList { encodedUrl -> decrypt(encodedUrl, getKey(encodedUrl)) }
        }
        // The site now asks every reader to pass its partner step before a chapter unlocks.
        if (payload.extractNextJsRsc<GateDto>() != null) {
            throw IOException("Abra o capítulo na WebView e conclua a etapa \"Um passo para ler\" do site. Depois volte e tente de novo.")
        }
        return emptyList()
    }

    private var key: String? = null
    suspend fun getKey(payload: String): String {
        if (!key.isNullOrBlank()) {
            return key!!
        }
        val (v, e) = getParams(payload)
        return client.get("$baseUrl/api/atfield/key?v=$v&e=$e").parseAs<KeyDto>().k.also {
            key = it
        }
    }
}
