package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.io.IOException

internal object MangaAuth {
    const val CLIENT_SIGNATURE = "MangaSig_8e2d5b47"
    private const val CHALLENGE_KEY = "MangaFlux#9a73d1e4"
    private const val PROOF_KEY = "manga-orbit-proof-9f2a6c81"

    fun proof(challenge: String, userAgent: String): String {
        val bytes = challenge.decodeHex().toByteArray().reversedArray()
        val encoded = ByteArray(bytes.size) { i ->
            ((bytes[i].toInt() - i * 17 - 45) xor CHALLENGE_KEY[i % CHALLENGE_KEY.length].code).toByte()
        }.toString(Charsets.US_ASCII)
        val parts = encoded.decodeBase64()?.utf8()?.split('|')
            ?: throw IOException("Desafio de acesso inválido.")
        require(parts.size == 4 && parts[0] == "M2") { "Desafio de acesso inválido." }
        return hash("manga-prism-v3\u0000" + parts[1] + "\u0000" + userAgent + "\u0000" + PROOF_KEY + "\u0000" + parts[3])
    }

    private fun hash(value: String): String {
        var seed = 2773480762L.toInt()
        val table = IntArray(256) { index ->
            val key = PROOF_KEY[index % PROOF_KEY.length].code
            seed = seed xor ((key + 211) * 0x01010101)
            seed += (index + 1) * 1831565813
            seed = Integer.rotateLeft(seed + (key xor (index + 157)), ((index + key + 7) and 31) + 1)
            (seed xor (seed ushr 7) xor (seed ushr 19) xor (index * 11)) and 255
        }
        val state = intArrayOf(
            3605593784L.toInt(), 2746324277L.toInt(), 3337565984L.toInt(), 461845907,
            3956652317L.toInt(), 2246822507L.toInt(), 668265263, 374761393,
            2654435769L.toInt(), 2135587861, 2496678331L.toInt(), 1542469173,
        )
        value.forEachIndexed { offset, char ->
            val character = char.code and 255
            val index = (offset + 5) % state.size
            val neighbor = (index + 5) % state.size
            val factor = table[(offset + ((state[index] ushr 7) and 255) + ((state[neighbor] ushr 19) and 255)) and 255]
            val mixed = Integer.rotateLeft(
                (state[index] xor ((character + factor) * 257)) + state[(index + 4) % state.size] + state[neighbor],
                ((factor + character + offset * 3) and 31) + 1,
            )
            state[index] = mixed xor (state[neighbor] + (character xor 167) * 0x01010101)
            val target = (index + 7) % state.size
            state[target] += Integer.rotateLeft(mixed xor factor, ((character + offset + index + 5) and 31) + 1)
        }
        for (index in state.indices) {
            val mixed = (state[index] xor Integer.rotateLeft(state[(index + 5) % state.size], (index % 11) + 1)) + state[(index + 2) % state.size]
            state[index] = Integer.rotateLeft(mixed, ((state[index] ushr 23) and 31) + 1)
        }
        return state.indices.step(3).joinToString("") { index ->
            val mixed = ((state[index] + Integer.rotateLeft(state[(index + 1) % state.size], 9)) xor state[(index + 2) % state.size]) +
                state[(index + 4) % state.size]
            Integer.toHexString(mixed).padStart(8, '0')
        }
    }
}
