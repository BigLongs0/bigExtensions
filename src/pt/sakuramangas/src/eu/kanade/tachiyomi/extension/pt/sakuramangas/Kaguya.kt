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
            val stream = table[(i + rotateLeft(a, 1) + k * 3 + d) and 255]
            val shift = ((b xor stream xor i) + version * 3) and 7
            val mixed = (rotateLeft(stream, 2) + c + k + i * 5 + version * 55) and 255
            val offset = (c + rotateLeft(stream, 1) + i + version * 75) and 255
            val result = if (decrypt) {
                val unmixed = (((value - offset) * inverse) - (a xor stream) - b - version * 25) and 255
                val rotated = rotateRight(unsubstitute(unmixed, version), shift)
                ((rotated xor rotateLeft(k xor d, 1)) - mixed) and 255
            } else {
                val rotated = rotateLeft(((value + mixed) and 255) xor rotateLeft(k xor d, 1), shift)
                val substituted = (substitute(rotated, version) + (a xor stream) + b + version * 25) and 255
                (substituted * multiplier + offset) and 255
            }
            val plain = if (decrypt) result else value
            val encrypted = if (decrypt) value else result
            val nextA = ((a xor rotateLeft((encrypted + stream + i) and 255, 2)) + d + 29) and 255
            val nextB = (b + rotateLeft(plain xor encrypted xor k, 3) + stream + 71) and 255
            val nextC = ((c xor rotateLeft((encrypted + d) and 255, (i + version) and 7)) + a + stream + version * 13) and 255
            d = ((d + rotateLeft((plain + encrypted + k) and 255, 1)) xor nextA xor rotateLeft(stream, 3)) and 255
            a = nextA
            b = nextB
            c = nextC
            result.toByte()
        }
    }

    private fun seed(key: ByteArray, version: Int): IntArray {
        var a = (167 + version * 53) and 255
        var b = (61 xor (version * 201)) and 255
        var c = (225 + version * 23) and 255
        var d = (89 xor (version * 115)) and 255
        key.forEachIndexed { i, byte ->
            val mixed = ((byte.toInt() and 255) + i * 29 + version * 23) and 255
            a = (rotateLeft(a xor mixed xor d, ((b + i) and 7) + 1) + c + 97) and 255
            b = (b + rotateLeft(mixed xor a xor d, (c xor i) and 7) + i * 11 + 19) and 255
            c = (rotateLeft(c xor b xor mixed, ((d + i) and 7) + 1) + a + 43) and 255
            d = ((d xor rotateLeft((a + c + mixed) and 255, 2)) + b * 3 + 151) and 255
        }
        return intArrayOf(a, b, c, d)
    }

    private fun table(initial: IntArray, version: Int): IntArray {
        var a = initial[0]
        var b = initial[1]
        var c = initial[2]
        var d = initial[3]
        return IntArray(256) { i ->
            a = (a + rotateLeft(d, 1) + i + 47) and 255
            b = rotateLeft(b xor a xor ((i * 17) and 255), (c xor i) and 7)
            c = (c + rotateLeft((b + d) and 255, 3) + (d xor ((i * 157) and 255))) and 255
            d = ((d xor c) + rotateLeft(a, 5) + version * 43 + 115) and 255
            ((a + rotateLeft(b, 2)) xor rotateLeft(c, 3) xor d xor ((i * 167) and 255)) and 255
        }
    }

    private fun substitute(value: Int, version: Int): Int = if (version == 1) {
        (rotateLeft(value xor 199, 5) + 57) and 255
    } else {
        rotateLeft((value + 109) and 255, 3) xor 166
    }

    private fun unsubstitute(value: Int, version: Int): Int = if (version == 1) {
        rotateRight((value - 57) and 255, 5) xor 199
    } else {
        (rotateRight(value xor 166, 3) - 109) and 255
    }

    private fun rotateLeft(value: Int, shift: Int): Int = ((value shl (shift and 7)) or (value ushr (8 - (shift and 7)))) and 255

    private fun rotateRight(value: Int, shift: Int): Int = ((value ushr (shift and 7)) or (value shl (8 - (shift and 7)))) and 255
}
