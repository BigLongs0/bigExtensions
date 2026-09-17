package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

internal object Kaguya {
    fun encrypt(value: String, secret: String): String = transform(
        value.toByteArray(Charsets.ISO_8859_1),
        secret.toByteArray(Charsets.ISO_8859_1),
        0,
        false,
    ).toByteString().base64()

    fun decrypt(payload: String, secret: ByteArray, version: Int): ByteArray = transform(
        requireNotNull(payload.decodeBase64()) { "Dados do leitor inválidos." }.toByteArray(),
        secret,
        version,
        true,
    )

    private fun transform(input: ByteArray, secret: ByteArray, version: Int, decrypt: Boolean): ByteArray {
        val key = if (secret.isEmpty()) byteArrayOf(0) else secret
        val initial = seed(key, version)
        val table = table(initial, version)
        var a = initial[0]
        var b = initial[1]
        var c = initial[2]
        var d = initial[3]
        val multiplier = if (version == 1) 13 else 5
        val inverse = if (version == 1) 197 else 205
        return ByteArray(input.size) { i ->
            val value = input[i].toInt() and 255
            val k = key[i % key.size].toInt() and 255
            val stream = table[(i + a + (k xor d)) and 255]
            val shift = (b + stream + version) and 7
            val mixed = (stream + rotateLeft(a, 1) + k + i * 3 + version * 41) and 255
            val offset = (c + stream + i + version * 53) and 255
            val result = if (decrypt) {
                val rotated = rotateRight(((value - offset) * inverse) and 255, shift)
                swap((rotated - (b xor k) - d) and 255, version) xor mixed
            } else {
                val rotated = rotateLeft((swap(value xor mixed, version) + (b xor k) + d) and 255, shift)
                (rotated * multiplier + offset) and 255
            }
            val plain = if (decrypt) result else value
            val encrypted = if (decrypt) value else result
            val nextA = (a + encrypted + stream + i + 23) and 255
            val nextB = rotateLeft(b xor plain xor k, 3)
            val nextC = (c + swap(encrypted, 0) + d + version * 11) and 255
            d = d xor rotateLeft((plain + encrypted + stream) and 255, 1) xor nextA
            a = nextA
            b = nextB
            c = nextC
            result.toByte()
        }
    }

    private fun seed(key: ByteArray, version: Int): IntArray {
        var a = (211 + version * 41) and 255
        var b = (113 xor (version * 183)) and 255
        var c = (76 + version * 99) and 255
        var d = (169 xor (version * 93)) and 255
        key.forEachIndexed { i, byte ->
            val mixed = ((byte.toInt() and 255) + i * 37 + version * 19) and 255
            a = rotateLeft((a + mixed + d) and 255, ((b xor mixed) and 7) + 1)
            b = b xor swap((mixed + a) and 255, 1)
            c = (c + rotateLeft(b xor mixed, 3) + i) and 255
            d = (d * 5 + c + (mixed xor a) + 39) and 255
        }
        return intArrayOf(a, b, c, d)
    }

    private fun table(initial: IntArray, version: Int): IntArray {
        var a = initial[0]
        var b = initial[1]
        var c = initial[2]
        var d = initial[3]
        return IntArray(256) { i ->
            a = (a + d + i + 61) and 255
            b = rotateLeft(b xor a xor ((i * 13) and 255), (c + i) and 7)
            c = (c + swap(b, 0) + (d xor i)) and 255
            d = (d * 5 + a + c + version * 29 + 17) and 255
            a xor rotateLeft(b, 1) xor rotateRight(c, 2) xor d
        }
    }

    private fun swap(value: Int, version: Int): Int = if (version == 1) {
        ((value and 51) shl 2) or ((value and 204) ushr 2)
    } else {
        ((value and 85) shl 1) or ((value and 170) ushr 1)
    }

    private fun rotateLeft(value: Int, shift: Int): Int = ((value shl (shift and 7)) or (value ushr (8 - (shift and 7)))) and 255

    private fun rotateRight(value: Int, shift: Int): Int = ((value ushr (shift and 7)) or (value shl (8 - (shift and 7)))) and 255
}
