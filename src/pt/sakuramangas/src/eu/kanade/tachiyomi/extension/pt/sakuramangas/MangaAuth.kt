package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.io.IOException

internal object MangaAuth {
    const val CLIENT_SIGNATURE = "MangaSig_4c71a9e2"
    private const val CHALLENGE_KEY = "MangaFlux#c4e7b91d"
    private const val PROOF_KEY = "manga-lattice-proof-7c41e2b9"

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
        var seed = 528734635
        val table = IntArray(256) { i ->
            val key = PROOF_KEY[i % PROOF_KEY.length].code
            seed += (key + 167) * 0x01010101
            seed = seed xor ((i + 1) * 2135587861)
            seed = Integer.rotateLeft(seed xor (key + i + 1), ((i + key + 3) and 31) + 1)
            (seed xor (seed ushr 11) xor (seed ushr 23) xor (i * 5)) and 255
        }
        val state = intArrayOf(
            608135816, 2242054355L.toInt(), 320440878, 57701188,
            2752067618L.toInt(), 698298832, 137296536, 3964562569L.toInt(),
            1160258022, 953160567, 3193202383L.toInt(), 887688300,
        )
        value.forEachIndexed { i, char ->
            val byte = char.code and 255
            val index = (i + 5) % state.size
            val neighbor = (index + 5) % state.size
            val factor = table[(i + ((state[index] ushr 11) and 255) + ((state[neighbor] ushr 23) and 255)) and 255]
            val mixed = Integer.rotateLeft(
                (state[index] + (byte xor factor) * 257) xor state[(index + 4) % state.size] xor state[neighbor],
                ((factor xor byte xor i) and 31) + 1,
            )
            state[index] = mixed + (state[neighbor] xor ((byte + 81) * 0x01010101))
            val target = (index + 7) % state.size
            state[target] = state[target] xor Integer.rotateLeft(mixed + factor, ((byte + i + index) and 31) + 1)
        }
        for (i in state.indices) {
            val mixed = (state[i] + Integer.rotateLeft(state[(i + 5) % state.size], (i % 13) + 1)) xor state[(i + 2) % state.size]
            state[i] = Integer.rotateLeft(mixed, ((state[i] ushr 27) and 31) + 1)
        }
        return state.indices.step(3).joinToString("") { i ->
            val mixed = (
                (state[i] xor Integer.rotateLeft(state[(i + 1) % state.size], 13)) +
                    state[(i + 4) % state.size]
                ) xor state[(i + 2) % state.size]
            Integer.toHexString(mixed).padStart(8, '0')
        }
    }
}
