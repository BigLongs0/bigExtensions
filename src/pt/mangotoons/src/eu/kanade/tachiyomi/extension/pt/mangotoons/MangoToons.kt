package eu.kanade.tachiyomi.extension.pt.mangotoons

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
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

@Source
abstract class MangoToons : KeiSource() {

    private val apiUrl = "https://api.mangotoons.com/api/v1"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3) { it.encodedPath.startsWith("/api/") }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Accept", "application/json")

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList(page, sort = "populares")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangaList(page, sort = "recentes")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getMangaList(
        page = page,
        sort = filters.firstInstanceOrNull<SortFilter>()?.selectedValue ?: "populares",
        query = query,
        format = filters.firstInstanceOrNull<FormatFilter>()?.selectedValue,
        status = filters.firstInstanceOrNull<StatusFilter>()?.selectedValue,
    )

    private suspend fun getMangaList(
        page: Int,
        sort: String,
        query: String? = null,
        format: String? = null,
        status: String? = null,
    ): MangasPage {
        val url = "$apiUrl/obras".toHttpUrl().newBuilder()
            .addQueryParameter("pagina", page.toString())
            .addQueryParameter("ordenar", sort)
            .apply {
                query?.takeIf(String::isNotBlank)?.let { addQueryParameter("busca", it) }
                format?.takeIf(String::isNotBlank)?.let { addQueryParameter("formato", it) }
                status?.takeIf(String::isNotBlank)?.let { addQueryParameter("status", it) }
            }
            .build()

        val result = client.get(url).parseAs<ListDto>()

        return MangasPage(result.mangas, result.hasNextPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "obra") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.all(Char::isDigit) } ?: return null
        val manga = SManga.create().apply { this.url = id }

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
        val work = client.get("$apiUrl/obras/${manga.workId}").parseAs<WorkDto>()

        return SMangaUpdate(manga = work.toSManga(), chapters = work.chapterList)
    }

    // Entries saved before 1.6.3 hold "/obra/{id}".
    private val SManga.workId get() = url.substringAfterLast('/')

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/obra/${manga.workId}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = chapter.url.split('/')
        val url = "$apiUrl/leitor/obras".toHttpUrl().newBuilder()
            .addPathSegment(segments[2])
            .addPathSegment("capitulos")
            .addPathSegment(segments[4])
            .addPathSegment("manifest")
            .build()

        // Chapters saved before 1.6.3 point at ids the reader no longer accepts.
        val response = client.get(url, ensureSuccess = false)
        if (response.code == 404) {
            response.close()
            throw IOException("Capítulo não encontrado. Atualize a lista de capítulos da obra.")
        }
        val manifest = response.parseAs<ManifestDto>()
        val pages = manifest.toPageList()

        if (pages.isEmpty() && manifest.isVip) {
            throw IOException("Capítulo bloqueado por paywall na MangoToons.")
        }

        return pages
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
    )
}
