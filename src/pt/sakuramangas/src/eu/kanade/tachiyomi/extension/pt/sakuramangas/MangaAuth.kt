package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.io.IOException

internal object MangaAuth {
    const val CLIENT_SIGNATURE = "MangaSig_4c71e8a2"
    private const val CHALLENGE_KEY = "MangaRelay#c84a17"
    private const val PROOF_KEY = "manga-orbit-proof-7f2c9a41"

    fun proof(challenge: String, userAgent: String): String {
        val bytes = challenge.decodeHex().toByteArray().reversedArray()
        val encoded = ByteArray(bytes.size) { i ->
            ((bytes[i].toInt() - i * 17 - 45) xor CHALLENGE_KEY[i % CHALLENGE_KEY.length].code).toByte()
        }.toString(Charsets.US_ASCII)
        val parts = encoded.decodeBase64()?.utf8()?.split('|')
            ?: throw IOException("Desafio de acesso inválido.")
        require(parts.size == 4 && parts[0] == "M1") { "Desafio de acesso inválido." }
        return hash("manga-orbit-v1\u0000" + parts[1] + "\u0000" + userAgent + "\u0000" + PROOF_KEY + "\u0000" + parts[3])
    }

    private fun hash(value: String): String {
        var seed = 0x243f6a88
        val table = IntArray(256) { i ->
            val key = PROOF_KEY[i % PROOF_KEY.length].code
            seed += (key + 1) * 73244475 + i * 40503
            seed = Integer.rotateLeft(seed xor ((key + 167) * 0x01010101), ((i + key) and 31) + 1)
            (seed xor (seed ushr 8) xor (seed ushr 16) xor i) and 255
        }
        val state = intArrayOf(
            0x13579bdf, 0x2468ace0, 0xfdb97531.toInt(), 0x0eca8642,
            0xa5a5a5a5.toInt(), 0x5a5a5a5a, 0x3c6ef372, 0x1f83d9ab,
            0xcbbb9d5d.toInt(), 0x629a292a, 0x9159015a.toInt(), 0x152fecd8,
        )
        value.forEachIndexed { i, char ->
            val byte = char.code and 255
            val index = i % state.size
            val neighbor = (index + 5) % state.size
            val factor = table[(i + (state[index] and 255) + ((state[neighbor] ushr 8) and 255)) and 255]
            val mixed = Integer.rotateLeft(
                (state[index] + (byte + factor) * 257 + state[(index + 1) % state.size]) xor state[neighbor],
                ((factor xor byte xor i) and 31) + 1,
            )
            state[index] = mixed + (state[neighbor] xor ((byte + 1) * 0x01010101))
            val target = (index + 7) % state.size
            state[target] = state[target] xor Integer.rotateLeft(mixed + factor, ((byte + i) and 31) + 1)
        }
        for (i in state.indices) {
            val mixed = (state[i] + Integer.rotateLeft(state[(i + 1) % state.size], (i % 13) + 1)) xor state[(i + 4) % state.size]
            state[i] = Integer.rotateLeft(mixed, ((state[i] ushr 27) and 31) + 1)
        }
        return state.indices.step(3).joinToString("") { i ->
            val mixed = (state[i] xor Integer.rotateLeft(state[(i + 1) % state.size], 7)) + state[(i + 2) % state.size]
            Integer.toHexString(mixed).padStart(8, '0')
        }
    }
}
