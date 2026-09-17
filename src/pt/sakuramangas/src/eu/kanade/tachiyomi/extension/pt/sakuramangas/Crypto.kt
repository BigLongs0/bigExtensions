package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object Crypto {
    private const val CATALOG_KEY = "S4kur4_Fl0w3r_K3y_S3cr3t_2026"
    private const val META_KEY = "SakuraKey"
    private const val CHAPTER_KEY = "SakuraCSS"

    fun decodeCatalog(payload: String): String = decodeBase64(payload).mapIndexed { i, byte ->
        val key = CATALOG_KEY[i % CATALOG_KEY.length].code
        ((byte.toInt() xor key) - key - i).toByte()
    }.toByteArray().toString(Charsets.UTF_8)

    fun decodeChapters(payload: String): String {
        val inner = decodeBase64(payload).mapIndexed { i, byte ->
            (byte.toInt() xor CHAPTER_KEY[i % CHAPTER_KEY.length].code).toByte()
        }.toByteArray().toString(Charsets.US_ASCII)
        return decodeBase64(inner).toString(Charsets.UTF_8)
    }

    fun decodeMeta(payload: String): String {
        val bytes = payload.decodeHex().toByteArray()
        val middle = (bytes.size + 1) / 2
        return ByteArray(bytes.size) { i ->
            val position = if (i % 2 == 0) i / 2 else middle + i / 2
            (bytes[position].toInt() xor META_KEY[i % META_KEY.length].code).toByte()
        }.toString(Charsets.US_ASCII)
    }

    fun decrypt(payload: String, secret: ByteArray, version: Int): ByteArray {
        val packet = decodeBase64(payload)
        if (packet.size < 3 || packet[0] != 75.toByte() || packet[1] != 49.toByte() || packet[2] != 51.toByte()) {
            return Kaguya.decrypt(payload, secret, version)
        }
        require(packet.size >= 32 && packet[3] == version.toByte()) { "Dados do leitor inválidos. Atualize a extensão." }
        val header = packet.copyOfRange(0, 4)
        val key = ("Kaguya13:key\u0000".toByteArray(Charsets.US_ASCII) + version.toByte() + secret)
            .toByteString().sha256().toByteArray()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet, 4, 12))
            updateAAD(header)
            doFinal(packet, 16, packet.size - 16)
        }
    }

    fun decryptEnvelope(payload: String, secret: String): String {
        require(payload.startsWith("Y1.")) { "Formato de chave inválido. Atualize a extensão." }
        val packet = decodeBase64(payload.substring(3).replace('-', '+').replace('_', '/'))
        require(packet.size >= 29) { "Chave de capítulo incompleta." }
        val prefix = "sakura-yggdrasil-envelope-v1\u0000".toByteArray(Charsets.UTF_8)
        val key = (prefix + secret.toByteArray(Charsets.UTF_8)).toByteString().sha256().toByteArray()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, packet, 0, 12))
            updateAAD(prefix)
            doFinal(packet, 12, packet.size - 12).toString(Charsets.UTF_8)
        }
    }

    fun encrypt(value: String, secret: String): String = Kaguya.encrypt(value, secret)

    fun encryptSignal(value: String, subtoken: String, chapterId: String, token: String): String = encrypt(value, "kaguya13-signal-v2:$subtoken:$chapterId:$token")

    private fun decodeBase64(value: String): ByteArray = value.decodeBase64()?.toByteArray()
        ?: throw IOException("Resposta codificada inválida.")
}
