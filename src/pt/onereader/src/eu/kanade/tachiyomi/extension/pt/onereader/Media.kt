package eu.kanade.tachiyomi.extension.pt.onereader

import android.util.Base64
import keiyoushi.utils.parseAs
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
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val KEY_TRANSPORT = "ecdh-p256-aesgcm-v1"
private const val KEY_WRAP_LABEL = "oneReader-keywrap-ecdh-p256-v1"
private const val ORX4_AAD = "oneReader-orx4-v1"

/** Ephemeral P-256 key the server uses to wrap the media keys of the site's own translations. */
class ReaderTransport {
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private val params = (keyPair.public as ECPublicKey).params

    val publicKey: String = (keyPair.public as ECPublicKey).w
        .let { byteArrayOf(4) + it.affineX.toCoordinate() + it.affineY.toCoordinate() }
        .encodeBase64Url()

    fun unwrap(wrap: KeyWrapDto): ByteArray {
        if (wrap.mode != KEY_TRANSPORT) throw IOException("Canal seguro da OneReader não suportado (${wrap.mode}).")
        val raw = wrap.serverKey.decodeBase64Url()
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        val serverKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(serverKey, true)
            generateSecret()
        }
        val context = wrap.context.orEmpty()
        val wrappingKey = MessageDigest.getInstance("SHA-256").run {
            update("$KEY_WRAP_LABEL\n".toByteArray())
            update(shared)
            update("\n$context".toByteArray())
            digest()
        }
        return aesGcm(wrappingKey, wrap.iv.decodeBase64Url(), "$KEY_WRAP_LABEL\n$context").doFinal(wrap.payload.decodeBase64Url())
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

fun Response.toApiError(): IOException {
    val status = code
    val error = runCatching { parseAs<ApiErrorDto>() }.getOrNull()
    val message = when (error?.code) {
        "READER_CHALLENGE_REQUIRED" -> "Abra o capítulo na WebView, conclua a verificação do site e tente de novo."
        else -> error?.message ?: "Erro HTTP $status na OneReader."
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
