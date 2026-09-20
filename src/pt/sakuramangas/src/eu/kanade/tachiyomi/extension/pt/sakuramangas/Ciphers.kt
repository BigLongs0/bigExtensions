package eu.kanade.tachiyomi.extension.pt.sakuramangas

import android.webkit.JavascriptInterface
import app.cash.quickjs.QuickJs
import keiyoushi.network.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.Locale

internal class Ciphers(private val client: OkHttpClient, private val baseUrl: String) {
    private val scripts = LinkedHashMap<String, String>()
    private val scriptMutex = Mutex()

    suspend fun decrypt(encrypted: String, secret: String, headers: Headers): ByteArray {
        val data = Crypto.decryptEnvelope(encrypted, secret).parseAs<EphemeralKeyDto>()
        val cipherScript = script("ciphers", data.cipherScript, headers)
        val masterScript = data.mode?.let { mode ->
            require(mode in 1..7) { "Modo de criptografia inválido." }
            script("masters", data.masterScript ?: throw IOException("Algoritmo do capítulo não informado."), headers)
        }
        val bytes = data.payload.decodeBase64()?.toByteArray() ?: throw IOException("Chave de capítulo inválida.")
        return QuickJs.create().use { engine ->
            engine.set(
                "sakuraDigest",
                Digest::class.java,
                object : Digest {
                    @JavascriptInterface
                    override fun sha256(value: String): String = value.encodeUtf8().sha256().hex()

                    @JavascriptInterface
                    override fun sha512(value: String): String = value.encodeUtf8().sha512().hex()
                },
            )
            engine.evaluate(implementation)
            engine.evaluate(cipherScript)
            masterScript?.let(engine::evaluate)
            val result = engine.evaluate(
                "decipher(" + data.cipher.uppercase(Locale.ROOT).toJsonString() + "," +
                    bytes.map { it.toInt() and 255 }.toJsonString() + "," + secret.toJsonString() + "," + data.mode + ")",
            ) as? String ?: throw IOException("Não foi possível decifrar a chave do capítulo.")
            result.decodeHex().toByteArray()
        }
    }

    private suspend fun script(directory: String, name: String, headers: Headers): String = scriptMutex.withLock {
        require(SCRIPT_NAME.matches(name)) { "Nome de algoritmo inválido." }
        val url = "$baseUrl/dist/sakura/_yggdrasil/$directory/$name.js"
        scripts[url] ?: client.get(url, headers).use { response ->
            response.body.string().replace(ASYNC_FUNCTION, "function").replace(AWAIT, "")
        }.also { script ->
            if (scripts.size >= 32) scripts.remove(scripts.keys.first())
            scripts[url] = script
        }
    }

    interface Digest {
        @JavascriptInterface
        fun sha256(value: String): String

        @JavascriptInterface
        fun sha512(value: String): String
    }

    companion object {
        private val SCRIPT_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        private val ASYNC_FUNCTION = Regex("\\basync\\s+function\\b")
        private val AWAIT = Regex("\\bawait\\s+")

        private val implementation = """
            globalThis.self = globalThis;
            globalThis.location = { pathname: "" };
            globalThis.Promise = {
                resolve: function(value) { return value; },
                reject: function(error) { throw error; }
            };
            globalThis.__sakuraYggdrasilRuntime_v1 = {
                CryptoUtils: {
                    hexToBytes: function(value) { return value.match(/../g).map(function(byte) { return parseInt(byte, 16); }); },
                    sha256: function(value) { return sakuraDigest.sha256(value); },
                    sha512: function(value) { return sakuraDigest.sha512(value); }
                },
                YggdrasilCipherImplementations: {},
                YggdrasilMasterDecryptors: {}
            };
            function decipher(cipher, input, secret, mode) {
                var runtime = globalThis.__sakuraYggdrasilRuntime_v1;
                var decoder = runtime.YggdrasilCipherImplementations[cipher];
                if (typeof decoder !== 'function') throw Error('Algoritmo do capítulo não encontrado.');
                if (mode != null) {
                    var master = runtime.YggdrasilMasterDecryptors[mode];
                    if (typeof master !== 'function') throw Error('Algoritmo principal não encontrado.');
                    input = master(input, secret);
                }
                var value = decoder(input, secret);
                if (typeof value !== 'string' || !value.length) throw Error('Chave de capítulo inválida.');
                return Array.from(value, function(character) {
                    return (character.charCodeAt(0) & 255).toString(16).padStart(2, '0');
                }).join('');
            }
        """.trimIndent()
    }
}
