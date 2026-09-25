package eu.kanade.tachiyomi.extension.pt.corujatoon

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

@Source
abstract class Corujatoon :
    KeiSource(),
    ConfigurableSource {
    private val preferences by getPreferencesLazy()
    private val loginMutex = Mutex()

    @Volatile
    private var credentialsChanged = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2) { it.host == baseUrl.toHttpUrl().host }

    override suspend fun getPopularManga(page: Int): MangasPage = getList(page, "")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val result = client.get("$baseUrl/api/series/latest").parseAs<UpdatesDto>()
        return MangasPage(result.updates.map { it.toSManga() }, false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = getList(page, query, filters)

    private suspend fun getList(page: Int, query: String, filters: FilterList = FilterList()): MangasPage {
        val url = "$baseUrl/api/series/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", "popular")
            .apply {
                if (query.isNotBlank()) addQueryParameter("search", query)
                filters.forEach { if (it is SelectFilter) it.addToUrl(this) }
            }
            .build()
        val result = client.get(url).parseAs<MangaListDto>()
        return MangasPage(result.series.map { it.toSManga() }, page < result.pagination.totalPages)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = "/series/$slug" },
            emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = authenticatedGet(getMangaUrl(manga)).asJsoup()
        val result = document.extractNextJs<ChapterListDto> {
            it is JsonObject && "chapters" in it && "seriesSlug" in it
        } ?: throw IOException("Não foi possível carregar os capítulos da obra.")
        val details = document.selectFirst("h1")!!.parent()!!.parent()!!
        val description = document.extractNextJs<DescriptionDto> {
            it is JsonObject && it.size == 1 && "description" in it
        }?.description
        val updated = SManga.create().apply {
            url = manga.url
            title = result.seriesTitle
            thumbnail_url = result.seriesCover ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            this.description = description
            author = details.selectFirst(":matchesOwn(^Autor$)")?.nextElementSibling()?.text()
            artist = details.selectFirst(":matchesOwn(^Artista$)")?.nextElementSibling()?.text()
            genre = details.select("a[href^=/generos/]").joinToString { it.text() }
            status = details.select("[data-slot=badge]").map { mangaStatus(it.text()) }
                .firstOrNull { it != SManga.UNKNOWN } ?: SManga.UNKNOWN
        }
        return SMangaUpdate(updated, result.chapterList())
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = authenticatedGet(getChapterUrl(chapter)).asJsoup()
        val reader = document.extractNextJs<ReaderDto> {
            it is JsonObject && "chapter" in it && "seriesSlug" in it
        } ?: throw IOException("Faça login na WebView ou nas configurações da fonte e confira o acesso ao capítulo.")
        return reader.chapter.content.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/api/genres/list").parseAs()

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        buildList {
            add(SortFilter())
            add(StatusFilter())
            add(TypeFilter())
            data?.parseAs<GenresDto>()?.let { add(GenreFilter(it.genres)) }
        },
    )

    private suspend fun authenticatedGet(url: String): Response {
        login()
        var response = client.get(url, ensureSuccess = false)
        if (response.code == 401) {
            response.close()
            login(force = true)
            response = client.get(url, ensureSuccess = false)
        }
        if (response.request.url.pathSegments.firstOrNull() == "login") {
            response.close()
            throw IOException("A CorujaToon exige login para abrir obras. Entre pela WebView ou informe email e senha nas configurações da extensão.")
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Acesso recusado ($code). Confira seu login e a disponibilidade do capítulo no site.")
        }
        return response
    }

    private suspend fun login(force: Boolean = false) = loginMutex.withLock {
        val email = preferences.getString("email", "")!!.trim()
        val password = preferences.getString("password", "")!!
        if (email.isEmpty() || password.isEmpty()) return@withLock
        val hasSession = client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
            .any { it.name.startsWith("__Secure-next-auth.session-token") }
        if (hasSession && !force && !credentialsChanged) return@withLock

        val csrf = client.get("$baseUrl/api/auth/csrf", cacheControl = CacheControl.FORCE_NETWORK)
            .parseAs<CsrfDto>().csrfToken
        val body = FormBody.Builder()
            .add("csrfToken", csrf)
            .add("email", email)
            .add("password", password)
            .add("callbackUrl", "$baseUrl/home")
            .add("json", "true")
            .build()
        client.post("$baseUrl/api/auth/callback/credentials", headers, body, ensureSuccess = false).use { response ->
            if (!response.isSuccessful || response.parseAs<LoginDto>().url.toHttpUrl().queryParameter("error") != null) {
                throw IOException("Não foi possível entrar na CorujaToon. Confira o email e a senha nas configurações.")
            }
        }
        credentialsChanged = false
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "email"
            title = "Email"
            summary = "Login da CorujaToon. Também é possível entrar pela WebView."
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
            setOnPreferenceChangeListener { _, _ ->
                credentialsChanged = true
                true
            }
        }.let(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = "password"
            title = "Senha"
            summary = "Senha da sua conta na CorujaToon"
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, _ ->
                credentialsChanged = true
                true
            }
        }.let(screen::addPreference)
    }
}
