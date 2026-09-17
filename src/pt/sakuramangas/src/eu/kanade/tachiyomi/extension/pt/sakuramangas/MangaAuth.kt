package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.io.IOException

internal object MangaAuth {
    const val CLIENT_SIGNATURE = "MangaSig_8f29c6d1"
    private const val CHALLENGE_KEY = "MangaFlux#91d52a3"
    private const val PROOF_KEY = "manga-prism-proof-9d4e1b73"

    fun proof(challenge: String, userAgent: String): String {
        val bytes = challenge.decodeHex().toByteArray().reversedArray()
        val encoded = ByteArray(bytes.size) { i ->
            ((bytes[i].toInt() - i * 17 - 45) xor CHALLENGE_KEY[i % CHALLENGE_KEY.length].code).toByte()
        }.toString(Charsets.US_ASCII)
        val parts = encoded.decodeBase64()?.utf8()?.split('|')
            ?: throw IOException("Desafio de acesso inválido.")
        require(parts.size == 4 && parts[0] == "M1") { "Desafio de acesso inválido." }
        return hash("manga-prism-v2\u0000" + parts[1] + "\u0000" + userAgent + "\u0000" + PROOF_KEY + "\u0000" + parts[3])
    }

    private fun hash(value: String): String {
        var seed = 1367130551
        val table = IntArray(256) { i ->
            val key = PROOF_KEY[i % PROOF_KEY.length].code
            seed = seed xor ((key + 91) * 0x01010101)
            seed += (i + 1) * 648061
            seed = Integer.rotateLeft(seed, ((i xor key) and 31) + 1)
            (seed xor (seed ushr 7) xor (seed ushr 19) xor (i * 3)) and 255
        }
        val state = intArrayOf(
            1779033703, 3144134277L.toInt(), 1013904242, 2773480762L.toInt(),
            1359893119, 2600822924L.toInt(), 528734635, 1541459225,
            3418070365L.toInt(), 1654270250, 2438529370L.toInt(), 355462360,
        )
        value.forEachIndexed { i, char ->
            val byte = char.code and 255
            val index = (i + 3) % state.size
            val neighbor = (index + 7) % state.size
            val factor = table[(i + ((state[index] ushr 16) and 255) + (state[neighbor] and 255)) and 255]
            val mixed = Integer.rotateLeft(
                (state[index] xor ((byte + factor) * 257)) + state[(index + 3) % state.size] + state[neighbor],
                ((factor + byte + i) and 31) + 1,
            )
            state[index] = mixed xor (state[neighbor] + (byte + 3) * 0x01010101)
            val target = (index + 5) % state.size
            state[target] += Integer.rotateLeft(mixed xor factor, ((byte xor i xor index) and 31) + 1)
        }
        for (i in state.indices) {
            val mixed = (state[i] xor Integer.rotateLeft(state[(i + 1) % state.size], (i % 11) + 2)) + state[(i + 5) % state.size]
            state[i] = Integer.rotateLeft(mixed, ((state[i] ushr 29) and 31) + 1)
        }
        return state.indices.step(3).joinToString("") { i ->
            val mixed = (state[i] + Integer.rotateLeft(state[(i + 2) % state.size], 9)) xor
                state[(i + 1) % state.size] xor state[(i + 3) % state.size]
            Integer.toHexString(mixed).padStart(8, '0')
        }
    }
}
