package eu.kanade.tachiyomi.extension.pt.auratoons

import eu.kanade.tachiyomi.network.HttpException
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
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Source
abstract class AuraToons : KeiSource() {

    // Next.js server action id from the library bundle; rotates on every deploy.
    @Volatile
    private var catalogAction: String? = null

    private val rscHeaders by lazy { headersBuilder().set("RSC", "1").build() }

    override suspend fun getPopularManga(page: Int) = getCatalog(page, "", FilterList(), "views", "desc")

    override suspend fun getLatestUpdates(page: Int) = getCatalog(page, "", FilterList(), "updatedAt", "desc")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = getCatalog(page, query, filters, filters.sortBy, filters.sortDir)

    private suspend fun getCatalog(page: Int, query: String, filters: FilterList, sortBy: String, sortDir: String): MangasPage {
        val body = listOf(
            CatalogRequestDto(
                filters = filters.toCatalogFilters(),
                toolbarQuery = query,
                sortBy = sortBy,
                sortDir = sortDir,
                page = page,
                blockedGenrePt = emptyList(),
            ),
        ).toJsonString().toRequestBody(ACTION_MEDIA_TYPE)

        val catalog = postCatalogAction(body, refresh = false)
            ?: postCatalogAction(body, refresh = true)
            ?: throw IOException("Não foi possível carregar o catálogo")
        return catalog.toMangasPage(page, baseUrl)
    }

    private suspend fun postCatalogAction(body: RequestBody, refresh: Boolean): CatalogResponseDto? {
        val headers = headersBuilder()
            .set("Accept", "text/x-component")
            .set("Next-Action", getCatalogAction(refresh))
            .set("Referer", "$baseUrl/biblioteca")
            .build()

        return client.post("$baseUrl/biblioteca", headers, body, ensureSuccess = false).use { response ->
            when {
                response.isSuccessful -> response.extractNextJs<CatalogResponseDto>()

                // Stale action id after a deploy; the caller refreshes it from the bundle and retries.
                response.code == 404 -> null

                else -> throw HttpException(response.code)
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detail = getMangaDetail(manga.url) ?: throw IOException("Obra não encontrada")
        return SMangaUpdate(
            manga = detail.manga.toSManga(baseUrl),
            chapters = detail.toSChapterList(manga.url),
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null
        return getMangaDetail("/manga/$slug")?.manga?.toSManga(baseUrl)
    }

    private suspend fun getMangaDetail(mangaUrl: String): MangaDetailDto? = client.get(baseUrl + mangaUrl, rscHeaders).extractNextJs<MangaDetailDto>()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl/api/nxtoons/chapter-pages".toHttpUrl().newBuilder()
            .addQueryParameter("chapterId", chapter.url.substringAfterLast('/'))
            .addQueryParameter("mangaSlug", chapter.url.substringAfter("/manga/").substringBefore('/'))
            .build()
        val result = client.get(url, ensureSuccess = false).parseAs<ChapterPagesDto>()
        result.gateMessage?.let { throw IOException(it) }
        return result.toPageList(baseUrl)
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = getCatalogBundle(refresh = false).extractFilterData().toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList = buildFilterList(data?.parseAs<FilterDataDto>())

    private suspend fun getCatalogAction(refresh: Boolean): String {
        if (!refresh) catalogAction?.let { return it }
        return getCatalogBundle(refresh).extractCatalogAction().also { catalogAction = it }
    }

    private suspend fun getCatalogBundle(refresh: Boolean): String {
        val cacheControl = if (refresh) CacheControl.FORCE_NETWORK else CacheControl.Builder().build()
        val document = client.get("$baseUrl/biblioteca", cacheControl = cacheControl).asJsoup()
        val bundleUrl = document.selectFirst("script[src*=/app/biblioteca/page-]")
            ?.absUrl("src")
            ?.takeIf(String::isNotEmpty)
            ?: throw IOException("Bundle da biblioteca não encontrado")
        return client.get(bundleUrl, cacheControl = cacheControl).use { it.body.string() }
    }

    private fun String.extractCatalogAction(): String {
        val id = substringBefore("\"fetchBibliotecaCatalogAction\"", "")
            .substringAfterLast("createServerReference)(\"", "")
            .substringBefore('"')
        if (id.length < 40 || !id.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw IOException("Ação do catálogo não encontrada")
        }
        return id
    }

    // Genres and themes are the two largest `{label, value}` arrays hardcoded in the library bundle.
    private fun String.extractFilterData(): FilterDataDto {
        val groups = OPTION_LIST_REGEX.findAll(this)
            .map { list ->
                OPTION_REGEX.findAll(list.value).map { option ->
                    FilterOptionDto(
                        label = option.groupValues[1].decodeJsString(),
                        value = option.groupValues[2].decodeJsString(),
                    )
                }.toList()
            }
            .filter { it.size > 10 }
            .sortedByDescending { it.size }
            .toList()
        return FilterDataDto(
            genres = groups.getOrElse(0) { emptyList() },
            themes = groups.getOrElse(1) { emptyList() },
        )
    }

    private fun String.decodeJsString(): String = "\"${replace(JS_HEX_ESCAPE_REGEX) { "\\u00${it.groupValues[1]}" }}\"".parseAs()

    companion object {
        private val ACTION_MEDIA_TYPE = "text/plain;charset=UTF-8".toMediaType()

        // Android's regex engine (ICU) rejects unescaped braces, so every `{` and `}` below is escaped.
        private val OPTION_LIST_REGEX = Regex("""\[(?:\{label:"(?:\\.|[^"\\])*",value:"(?:\\.|[^"\\])*"\},?)+\]""")
        private val OPTION_REGEX = Regex("""\{label:"((?:\\.|[^"\\])*)",value:"((?:\\.|[^"\\])*)"\}""")
        private val JS_HEX_ESCAPE_REGEX = Regex("""\\x([0-9a-fA-F]{2})""")
    }
}
