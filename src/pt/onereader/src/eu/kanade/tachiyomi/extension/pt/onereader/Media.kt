package eu.kanade.tachiyomi.extension.pt.onereader

import android.util.Base64
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.buffer
import okio.cipherSource
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val KEY_TRANSPORT = "ecdh-p256-aesgcm-v1"
internal const val PROOF_MODE = "hmac-sha256-v1"
private const val KEY_WRAP_LABEL = "oneReader-keywrap-ecdh-p256-v1"
private const val PROOF_KEY_LABEL = "oneReader-scan-proof-hmac-v1"
private const val PROOF_LABEL = "oneReader-scan-proof-v1"
private const val ORX4_AAD = "oneReader-orx4-v1"

/** Ephemeral P-256 key the server uses to wrap media keys and to check page proofs for the site's own translations. */
class ReaderTransport {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private val params = (keyPair.public as ECPublicKey).params

    private val random = SecureRandom()

    @Volatile
    private var proofKey: Pair<String, SecretKeySpec>? = null

    val publicKey: String = (keyPair.public as ECPublicKey).w
        .let { byteArrayOf(4) + it.affineX.toCoordinate() + it.affineY.toCoordinate() }
        .encodeBase64Url()

    fun unwrap(wrap: KeyWrapDto): ByteArray {
        if (wrap.mode != KEY_TRANSPORT) throw IOException("Canal seguro da OneReader não suportado (${wrap.mode}).")
        val context = wrap.context.orEmpty()
        val wrappingKey = MessageDigest.getInstance("SHA-256").run {
            update("$KEY_WRAP_LABEL\n".toByteArray())
            update(sharedSecret(wrap.serverKey))
            update("\n$context".toByteArray())
            digest()
        }
        return aesGcm(wrappingKey, wrap.iv.decodeBase64Url(), "$KEY_WRAP_LABEL\n$context").doFinal(wrap.payload.decodeBase64Url())
    }

    /** Signs a page request the same way the site's secure reader worker does. */
    fun proofHeaders(serverKey: String, url: HttpUrl): Headers {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = ByteArray(16).also(random::nextBytes).encodeBase64Url()
        val canonical = listOf(PROOF_LABEL, "GET", url.encodedPath, url.queryParameter("g").orEmpty(), timestamp, nonce)
            .joinToString("\n")
        val proof = Mac.getInstance("HmacSHA256").run {
            init(proofKey(serverKey))
            doFinal(canonical.toByteArray())
        }
        return headersOf(
            "X-OneReader-Proof-Ts",
            timestamp,
            "X-OneReader-Proof-Nonce",
            nonce,
            "X-OneReader-Proof",
            proof.encodeBase64Url(),
        )
    }

    private fun proofKey(serverKey: String): SecretKeySpec {
        proofKey?.takeIf { it.first == serverKey }?.let { return it.second }
        val digest = MessageDigest.getInstance("SHA-256").run {
            update("$PROOF_KEY_LABEL\n".toByteArray())
            update(sharedSecret(serverKey))
            digest()
        }
        return SecretKeySpec(digest, "HmacSHA256").also { proofKey = serverKey to it }
    }

    private fun sharedSecret(serverKey: String): ByteArray {
        val raw = serverKey.decodeBase64Url()
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        return KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(publicKey, true)
            generateSecret()
        }
    }
}

/** Turns the ORX3 (XOR over a prefix) or ORX4 (AES-GCM) envelope back into the original image. */
fun Response.decodeMedia(media: MediaDto, transport: ReaderTransport): Response {
    if (!isSuccessful) {
        close()
        throw IOException("Erro HTTP $code ao baixar a página.")
    }
    val key = media.keyWrap?.let(transport::unwrap)
        ?: media.key?.decodeBase64Url()
        ?: throw IOException("Chave da página ausente.")
    val source = body.source()
    val magic = source.readUtf8(4)

    val image: Source = when {
        media.mode == "xor-prefix-v3" && magic == "ORX3" -> source.xorPrefix(key, source.readInt())

        media.mode == "aes-gcm-v4" && magic == "ORX4" -> {
            val nonce = media.url.toHttpUrl().queryParameter("or_n")?.decodeBase64Url()
                ?: throw IOException("Nonce da página ausente.")
            source.cipherSource(aesGcm(key, nonce, ORX4_AAD))
        }

        else -> {
            close()
            throw IOException("Formato de página desconhecido (${media.mode}).")
        }
    }

    val contentType = media.contentType ?: "image/jpeg"
    return newBuilder()
        .header("Content-Type", contentType)
        .removeHeader("Content-Length")
        .body(image.buffer().asResponseBody(contentType.toMediaTypeOrNull()))
        .build()
}

fun Response.toApiError(): IOException = readApiError().toIOException(code)

/** Reads and closes the body of a failed API response. */
fun Response.readApiError(): ApiErrorDto? = runCatching { parseAs<ApiErrorDto>() }.getOrNull()

fun ApiErrorDto?.toIOException(status: Int): IOException {
    val message = when (this?.code) {
        "READER_CHALLENGE_REQUIRED" -> "Abra o capítulo na WebView, conclua a verificação do site e tente de novo."
        else -> this?.message ?: "Erro HTTP $status na OneReader."
    }
    return IOException(message)
}

private fun BufferedSource.xorPrefix(key: ByteArray, count: Int): Source {
    request(count.toLong())
    val prefix = readByteArray(minOf(count.toLong(), buffer.size))
    for (index in prefix.indices) {
        prefix[index] = (prefix[index].toInt() xor key[index % key.size].toInt()).toByte()
    }
    return PrefixedSource(Buffer().write(prefix), this)
}

private class PrefixedSource(
    private val prefix: Buffer,
    private val rest: BufferedSource,
) : Source by rest {
    override fun read(sink: Buffer, byteCount: Long): Long = if (prefix.size > 0) prefix.read(sink, byteCount) else rest.read(sink, byteCount)
}

private fun aesGcm(key: ByteArray, iv: ByteArray, aad: String): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    updateAAD(aad.toByteArray())
}

private fun BigInteger.toCoordinate(): ByteArray {
    val bytes = toByteArray().takeLast(32).toByteArray()
    return ByteArray(32 - bytes.size) + bytes
}

private fun String.decodeBase64Url(): ByteArray = Base64.decode(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

private fun ByteArray.encodeBase64Url(): String = Base64.encodeToString(this, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
