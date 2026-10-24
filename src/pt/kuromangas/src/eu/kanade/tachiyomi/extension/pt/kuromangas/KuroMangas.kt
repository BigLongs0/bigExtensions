package eu.kanade.tachiyomi.extension.pt.kuromangas

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Source
abstract class KuroMangas :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val apiUrl get() = "$baseUrl/api"

    private val cdnUrl = "https://cdn.kuromangas.com"

    private val apiHost get() = baseUrl.toHttpUrl().host

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != apiHost) return@addInterceptor chain.proceed(request)
            if (!checkLogin()) throw IOException(LOGIN_REQUIRED_MESSAGE)
            chain.proceed(request)
        }

        addInterceptor(KuroMangasDecryptor(baseUrl, network.client, ::relogin).vSecureInterceptor())

        // Unscoped, this also throttles the CDN, and a chapter is 120 images.
        rateLimit(2) { it.host == apiHost }
    }

    // The site's own API calls are same-origin, so they carry no Origin header.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .removeAll("Origin")
        .set("Accept", "application/json, text/plain, */*")
        .set("Accept-Language", "pt-BR,pt;q=0.8,en-US;q=0.5,en;q=0.3")
        .set("Referer", "$baseUrl/catalogo")
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("sort", "view_count")
            .addQueryParameter("order", "DESC")
            .build()

        return client.get(url).parseAs<MangaListResponse>().toMangasPage()
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/chapters/recent".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())
            .addQueryParameter("days", "30")
            .build()

        val result = client.get(url).parseAs<LatestResponse>()
        return MangasPage(result.data.map { it.toSManga(cdnUrl) }, result.pagination.hasNextPage())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_LIMIT.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.firstInstanceOrNull<SortFilter>()?.let { filter ->
            url.addQueryParameter("sort", filter.selectedSort)
            url.addQueryParameter("order", filter.selectedOrder)
        } ?: run {
            url.addQueryParameter("sort", "created_at")
            url.addQueryParameter("order", "DESC")
        }

        return client.get(url.build()).parseAs<MangaListResponse>().toMangasPage()
    }

    private fun MangaListResponse.toMangasPage() = MangasPage(data.map { it.toSManga(cdnUrl) }, pagination.hasNextPage())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() !in MANGA_PATH_SEGMENTS) return null
        val mangaId = url.pathSegments.getOrNull(1)?.toIntOrNull() ?: return null
        val manga = SManga.create().apply { this.url = "/manga/$mangaId" }

        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val mangaId = manga.url.substringAfterLast("/").toInt()

        val details = async {
            if (fetchDetails) {
                client.get("$apiUrl/mangas/$mangaId").parseAs<MangaDetailsResponse>().manga.toSManga(cdnUrl)
            } else {
                manga
            }
        }

        // The details endpoint only carries the newest 60 chapters, so the whole list is requested here.
        val chapterList = async {
            if (fetchChapters) {
                val url = "$apiUrl/mangas/$mangaId/chapters".toHttpUrl().newBuilder()
                    .addQueryParameter("all", "true")
                    .build()

                client.get(url).parseAs<ChapterListResponse>().chapters
                    .map { it.toSChapter(mangaId) }
                    .sortedByDescending { it.chapter_number }
            } else {
                chapters
            }
        }

        SMangaUpdate(manga = details.await(), chapters = chapterList.await())
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/")

        return client.get("$apiUrl/chapters/$chapterId").parseAs<ChapterPagesResponse>().pages.mapIndexed { index, pageUrl ->
            val fixedUrl = pageUrl.replaceFirst(UPLOADS_PREFIX_REGEX, "/")
            val imageUrl = if (fixedUrl.startsWith("http")) fixedUrl else "$cdnUrl$fixedUrl"
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request = Request.Builder()
        .url(page.imageUrl!!)
        .headers(headersBuilder().set("Referer", "$baseUrl/").build())
        .build()

    override fun getFilterList(data: JsonElement?): FilterList = getFilters()

    override fun getMangaUrl(manga: SManga): String {
        val mangaId = manga.url.substringAfterLast("/")
        return "$baseUrl/manga/$mangaId"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        // chapter.url format: /chapter/{mangaId}/{chapterId}
        val parts = chapter.url.removePrefix("/chapter/").split("/")
        val mangaId = parts.getOrNull(0) ?: ""
        val chapterId = parts.getOrNull(1) ?: ""
        return "$baseUrl/reader/$mangaId/$chapterId"
    }

    // ============================= Auth ===================================

    private fun checkLogin(): Boolean {
        if (hasSession()) return true
        return relogin()
    }

    private fun relogin(): Boolean {
        val email = preferences.getString(PREF_EMAIL, "") ?: ""
        val password = preferences.getString(PREF_PASSWORD, "") ?: ""
        if (email.isEmpty() || password.isEmpty()) {
            return false
        }
        login(email, password)

        return hasSession()
    }

    // The API rejects a request whose X-Request-Ctx does not match the token cookie, so a
    // session without it is useless even though the JWT is still valid.
    private fun hasSession(): Boolean = client.getCookie(baseUrl, SESSION_COOKIE) != null && client.getCookie(baseUrl, TOKEN_COOKIE) != null

    // Implicit set-cookie: kuro_session and _vx
    private fun login(email: String, password: String) {
        // Without rememberMe both cookies come back for the session only, forcing a login per app start.
        val request = Request.Builder()
            .url("$apiUrl/auth/login")
            .headers(headers)
            .post(LoginDto(email, password, rememberMe = true).toJsonRequestBody())
            .build()
        network.client.newCall(request).execute().close()
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val warning = "⚠️ Os dados inseridos nesta seção serão usados somente para realizar o login na fonte"
        val message = "Insira %s para prosseguir com o acesso aos recursos disponíveis na fonte"

        EditTextPreference(screen.context).apply {
            key = PREF_EMAIL
            title = "📧 Email"
            summary = "Email de acesso"
            dialogMessage = buildString {
                appendLine(message.format("seu email"))
                append("\n$warning")
            }
            setDefaultValue("")
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "🔑 Senha"
            summary = "Senha de acesso"
            dialogMessage = buildString {
                appendLine(message.format("sua senha"))
                append("\n$warning")
            }
            setDefaultValue("")
        }.let(screen::addPreference)
    }

    companion object {
        private const val PAGE_LIMIT = 24
        private const val PREF_EMAIL = "kuromangas_email"
        private const val PREF_PASSWORD = "kuromangas_password"
        private const val SESSION_COOKIE = "kuro_session"
        private const val LOGIN_REQUIRED_MESSAGE = "Faça login no WebView ou insira email e senha nas configurações e tente novamente."
        private val MANGA_PATH_SEGMENTS = listOf("manga", "reader")
        private val UPLOADS_PREFIX_REGEX = Regex("^/uploads/")
    }
}

fun OkHttpClient.getCookies(baseUrl: String) = cookieJar.loadForRequest(baseUrl.toHttpUrl())

fun OkHttpClient.getCookie(baseUrl: String, cookie: String): String? = getCookies(baseUrl).firstOrNull { it.name == cookie }?.value?.takeUnless { it.isEmpty() }
