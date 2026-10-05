package eu.kanade.tachiyomi.extension.pt.tomato

import android.os.Build
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
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException

@Source
abstract class Tomato :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val loginMutex = Mutex()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2) { it.host == API_HOST }

    // baseUrl is the captcha page, which the app never sends along.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .removeAll("Referer")
        .removeAll("Origin")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val items = apiGet("/v2/manga/feed").parseAs<FeedDto>().items

        return MangasPage(items.map(FeedItemDto::toSManga), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val items = apiGet("/v2/manga/feed").parseAs<FeedDto>().latest

        return MangasPage(items.map(FeedItemDto::toSManga), false)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val results = apiPost("/v2/content/search") { token -> SearchRequestDto(query.trim(), page - 1, token, contentType = "manga").toJsonRequestBody() }
            .parseAs<SearchDto>()
            .result

        return MangasPage(results.filter(SearchItemDto::isManga).map(SearchItemDto::toSManga), results.size >= SEARCH_PAGE_SIZE)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val id = manga.url.substringAfterLast('/')
        val details = if (fetchDetails) {
            async { apiPost("/manga/query/") { token -> DetailsRequestDto(id.toLong(), token).toJsonRequestBody() }.parseAs<DetailsDto>().details.toSManga() }
        } else {
            null
        }
        val chapterList = if (fetchChapters) {
            async { apiGet("/manga/chapters/query/$id").parseAs<ChaptersDto>().data.asReversed().map(ChapterDto::toSChapter) }
        } else {
            null
        }

        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val key = chapter.url.substringAfter("/chapter/")

        return apiGet("/manga/pages/query/$key").parseAs<PagesDto>().data
            .mapIndexed { index, page -> Page(index, imageUrl = page.pageUrl) }
    }

    private suspend fun apiGet(path: String): Response = authorized { token ->
        client.get("$API_URL$path", authHeaders(token), ensureSuccess = false)
    }

    private suspend fun apiPost(path: String, body: (String) -> RequestBody): Response = authorized { token ->
        client.post("$API_URL$path", authHeaders(token), body(token), ensureSuccess = false)
    }

    private suspend fun authorized(call: suspend (String) -> Response): Response {
        val response = call(token())
        if (response.code != 403) return response.successful()

        response.close()
        return call(token(renew = true)).successful()
    }

    private fun Response.successful(): Response {
        if (isSuccessful) return this
        close()
        throw IOException("A Tomato recusou o pedido (HTTP $code)")
    }

    private fun authHeaders(token: String): Headers = headersBuilder()
        .set("Authorization", "Bearer $token")
        .set("request-time", System.currentTimeMillis().toString())
        .build()

    private suspend fun token(renew: Boolean = false): String = loginMutex.withLock {
        if (!renew) preferences.getString(TOKEN_PREF, null)?.takeIf(String::isNotEmpty)?.let { return@withLock it }

        val email = preferences.getString(EMAIL_PREF, "")!!.trim()
        val password = preferences.getString(PASSWORD_PREF, "")!!
        if (email.isEmpty() || password.isEmpty()) {
            throw IOException("Informe o email e a senha da Tomato nas configurações da extensão.")
        }
        val captcha = takeCaptcha()
            ?: throw IOException("Toque em \"Abrir na WebView\", resolva o captcha e tente de novo.")

        val result = client.post("$API_URL/login/", LoginRequestDto(email, password, fingerprint, captcha).toJsonRequestBody(), ensureSuccess = false)
            .parseAs<LoginDto>()
        val token = result.token?.takeIf(String::isNotEmpty)
            ?: throw IOException("Não foi possível entrar na Tomato: ${result.message ?: "resposta inesperada"}")

        preferences.edit().putString(TOKEN_PREF, token).apply()
        token
    }

    // The login requires an hCaptcha answer. The page at baseUrl lets the user solve it in the
    // WebView and keeps the answer in a cookie; each answer can only be used once.
    private fun takeCaptcha(): String? {
        val pageUrl = baseUrl.toHttpUrl()
        val captcha = client.cookieJar.loadForRequest(pageUrl).firstOrNull { it.name == CAPTCHA_COOKIE }?.value
        val expired = Cookie.Builder()
            .name(CAPTCHA_COOKIE)
            .value("")
            .hostOnlyDomain(pageUrl.host)
            .path("/")
            .secure()
            .expiresAt(0)
            .build()
        client.cookieJar.saveFromResponse(pageUrl, listOf(expired))
        return captcha?.takeIf(String::isNotEmpty)
    }

    private val fingerprint by lazy {
        "${Build.VERSION.RELEASE}/${Build.MANUFACTURER}/${Build.MODEL}".replace(WHITESPACE_REGEX, "-")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = EMAIL_PREF
            title = "Email"
            summary = "Email da sua conta na Tomato. Depois de salvar, abra a fonte na WebView e resolva o captcha para entrar."
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
            setOnPreferenceChangeListener { _, _ ->
                preferences.edit().remove(TOKEN_PREF).apply()
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Senha"
            summary = "Senha da sua conta na Tomato"
            setDefaultValue("")
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, _ ->
                preferences.edit().remove(TOKEN_PREF).apply()
                true
            }
        }.let(screen::addPreference)
    }

    companion object {
        private const val API_HOST = "prod-api.tomatoanimes.com"
        private const val API_URL = "https://$API_HOST"
        private const val EMAIL_PREF = "email"
        private const val PASSWORD_PREF = "password"
        private const val TOKEN_PREF = "token"
        private const val CAPTCHA_COOKIE = "tomato_captcha"
        private const val SEARCH_PAGE_SIZE = 25
        private val WHITESPACE_REGEX = Regex("""\s""")
    }
}
