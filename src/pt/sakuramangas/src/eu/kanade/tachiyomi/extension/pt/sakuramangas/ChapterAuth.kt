package eu.kanade.tachiyomi.extension.pt.sakuramangas

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.io.IOException

internal object ChapterAuth {
    const val CLIENT_SIGNATURE = "ChapterSig_91bd35f7"
    private const val CHALLENGE_KEY = "ChapterPulse#e62d9"
    private const val PROOF_KEY = "chapter-weave-proof-b83d61e9"

    fun proof(challenge: String, userAgent: String): String {
        val bytes = challenge.decodeHex().toByteArray()
        val encoded = ByteArray(bytes.size) { i ->
            val value = bytes[i].toInt() and 255
            val shift = i % 5 + 1
            val rotated = ((value ushr shift) or (value shl (8 - shift))) and 255
            (rotated - CHALLENGE_KEY[i % CHALLENGE_KEY.length].code - i * 29 - 83).toByte()
        }.toString(Charsets.US_ASCII)
        val parts = encoded.decodeBase64()?.utf8()?.split('|')
            ?: throw IOException("Desafio de acesso inválido.")
        require(parts.size == 4 && parts[0] == "C1") { "Desafio de acesso inválido." }
        return hash("chapter-weave-v1|${parts[1]}|$userAgent|$PROOF_KEY|${parts[3]}")
    }

    private fun hash(value: String): String {
        var seed = 0x9e3779b9.toInt()
        val table = IntArray(64) { i ->
            val key = PROOF_KEY[i % PROOF_KEY.length].code
            seed += (key + 49) * 257 + i * 1663821227
            seed = Integer.rotateLeft(seed xor ((key + i) * 73244475), (i and 7) + 1)
            (seed xor (seed ushr 8) xor (seed ushr 16) xor (seed ushr 24)) and 255
        }
        val state = intArrayOf(
            1831565813,
            461845907,
            2246822507L.toInt(),
            3266489909L.toInt(),
            668265263,
            374761393,
            3550635116L.toInt(),
            4251993797L.toInt(),
        )
        value.toByteArray(Charsets.UTF_8).forEachIndexed { i, byte ->
            val character = byte.toInt() and 255
            val index = i and 7
            val neighbor = (index + 3) and 7
            val factor = table[(i * 7 + character + (state[neighbor] and 63)) and 63]
            var low = state[index] and 65535
            var high = (state[index] ushr 16) and 65535
            low = (low + character + factor + i * 3) and 65535
            high = high xor ((low * 257 + factor * 17 + (state[neighbor] and 65535)) and 65535)
            low = rotateLeft16(low, (factor xor i) % 15 + 1)
            high = rotateRight16(high, (character + index) % 15 + 1)
            state[index] = (high shl 16) or low
            state[neighbor] += Integer.rotateLeft(state[index] xor (factor * 0x01010101), ((character + i) and 31) + 1)
            val target = (index + 5) and 7
            state[target] = state[target] xor Integer.rotateRight(state[index], ((factor + index) and 31) + 1) xor ((i + factor) * 40503)
        }
        repeat(8) { round ->
            for (i in state.indices) {
                val neighbor = (i + 1) and 7
                val factor = table[(round * 8 + i) and 63]
                var low = state[i] and 65535
                var high = (state[i] ushr 16) and 65535
                low = ((low xor ((state[neighbor] ushr (round and 7)) and 65535)) + factor + round + i) and 65535
                high = (high + low * 257 + factor) and 65535
                low = rotateLeft16(low, (factor + round) % 15 + 1)
                high = rotateRight16(high, (low + i) % 15 + 1)
                state[i] = (high shl 16) or low
            }
        }
        return state.indices.step(2).joinToString("") { i ->
            val mixed = (state[i] + Integer.rotateLeft(state[(i + 2) and 7], 5)) xor
                Integer.rotateRight(state[(i + 1) and 7], 11) xor state[(i + 3) and 7]
            Integer.toHexString(mixed).padStart(8, '0')
        }
    }

    private fun rotateLeft16(value: Int, shift: Int) = ((value shl shift) or (value ushr (16 - shift))) and 65535

    private fun rotateRight16(value: Int, shift: Int) = ((value ushr shift) or (value shl (16 - shift))) and 65535
}
