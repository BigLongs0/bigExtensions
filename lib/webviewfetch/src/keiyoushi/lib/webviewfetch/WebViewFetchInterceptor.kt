package keiyoushi.lib.webviewfetch

import android.util.Base64
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import keiyoushi.utils.toJsonString
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Repeats a request that Cloudflare answers with a challenge (403 with `cf-mitigated: challenge`)
 * through `fetch()` inside a WebView whose origin is the request's site.
 *
 * Some sites challenge every non-browser client but let Android's WebView through without a
 * challenge, so no `cf_clearance` cookie is ever issued for OkHttp to reuse and the app's own
 * Cloudflare handling cannot help. The WebView sends the request with its own network stack and
 * cookies; if the WebView is challenged too, the 403 is returned as is so the user can pass the
 * check in "Open in WebView".
 *
 * Only requests to the same site as the page work, since the page has that site as its origin.
 * Remove `CloudflareInterceptor` from the client when adding this interceptor: it handles the
 * same responses first and fails before this one sees them.
 */
class WebViewFetchInterceptor(
    private val timeout: Duration = 60.seconds,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403 || response.header("cf-mitigated") != "challenge") return response

        response.close()
        return fetchInWebView(chain.call(), request)
    }

    private fun fetchInWebView(call: Call, request: Request): Response {
        val url = request.url
        val origin = "${url.scheme}://${url.host}" + if (url.port == defaultPort(url.scheme)) "" else ":${url.port}"
        val script = FETCH_SCRIPT
            .replace("__URL__", url.toString().toJsonString())
            .replace("__INIT__", request.toFetchInit(origin).toJsonString())

        val started = AtomicBoolean(false)
        val result = runWebViewBlocking<FetchResultDto>(call, timeout = timeout) {
            // cf_clearance only holds for the User-Agent that earned it in the app's WebView.
            request.header("User-Agent")?.let { userAgent = it }
            jsBridge(BRIDGE) { message -> resolve(message.parseAs<FetchResultDto>()) }
            onPageFinished {
                if (started.compareAndSet(false, true)) evaluateJs(script)
            }
            loadData("$origin/", "<html><body></body></html>")
        }

        result.error?.let { throw IOException("WebView fetch failed: $it") }
        val contentType = result.type?.toMediaTypeOrNull()
        val bytes = Base64.decode(result.body.orEmpty(), Base64.DEFAULT)

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(result.status)
            .message("")
            .apply { result.type?.let { header("Content-Type", it) } }
            .body(bytes.toResponseBody(contentType))
            .build()
    }

    private fun Request.toFetchInit(origin: String): FetchInitDto {
        val referer = header("Referer")?.takeIf { it.startsWith(origin) }
        val fetchHeaders = headers.names()
            .filterNot { it.lowercase() in FORBIDDEN_HEADERS || it.lowercase().startsWith("sec-") || it.lowercase().startsWith("proxy-") }
            .associateWith { header(it)!! }
        val payload = body?.let { requestBody ->
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            Base64.encodeToString(buffer.readByteArray(), Base64.NO_WRAP)
        }
        val contentType = body?.contentType()?.toString()

        return FetchInitDto(
            method = method,
            headers = if (contentType != null) fetchHeaders + ("Content-Type" to contentType) else fetchHeaders,
            body = payload,
            referrer = referer,
        )
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    private companion object {
        const val BRIDGE = "keiWebViewFetch"

        val FORBIDDEN_HEADERS = setOf(
            "accept-encoding", "connection", "content-length", "cookie", "host", "origin",
            "referer", "user-agent", "te", "trailer", "transfer-encoding", "upgrade", "keep-alive",
        )

        val FETCH_SCRIPT = """
            (async () => {
              try {
                const init = __INIT__;
                const options = { method: init.method, headers: init.headers, credentials: 'include' };
                if (init.referrer) options.referrer = init.referrer;
                if (init.body != null) options.body = Uint8Array.from(atob(init.body), c => c.charCodeAt(0));
                const response = await fetch(__URL__, options);
                const bytes = new Uint8Array(await response.arrayBuffer());
                let binary = '';
                for (let i = 0; i < bytes.length; i += 0x8000) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                }
                window.$BRIDGE.post(JSON.stringify({
                  status: response.status,
                  type: response.headers.get('content-type'),
                  body: btoa(binary),
                }));
              } catch (e) {
                window.$BRIDGE.post(JSON.stringify({ status: 0, error: String(e) }));
              }
            })();
        """.trimIndent()
    }
}

@Serializable
internal class FetchInitDto(
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val referrer: String?,
)

@Serializable
internal class FetchResultDto(
    val status: Int,
    val type: String? = null,
    val body: String? = null,
    val error: String? = null,
)
