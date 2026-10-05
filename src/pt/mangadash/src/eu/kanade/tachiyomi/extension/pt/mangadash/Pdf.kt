package eu.kanade.tachiyomi.extension.pt.mangadash

import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8

private val STREAM = "stream".encodeUtf8()
private val END_STREAM = "endstream".encodeUtf8()
private val LENGTH_REGEX = Regex("""/Length\s+(\d+)(\s+\d+\s+R)?""")

// The site's PDFs hold one full-page JPEG per page, stored in page order, so the byte ranges of
// the DCTDecode image streams are the chapter's pages.
fun BufferedSource.jpegStreamRanges(): List<LongRange> {
    val ranges = mutableListOf<LongRange>()
    var position = 0L

    while (true) {
        val keyword = indexOf(STREAM)
        if (keyword == -1L) break

        val dictionary = readByteString(keyword).utf8()
        skip(STREAM.size.toLong())
        position += keyword + STREAM.size
        if (dictionary.endsWith("end")) continue

        if (request(1) && buffer[0] == '\r'.code.toByte()) {
            skip(1)
            position++
        }
        if (request(1) && buffer[0] == '\n'.code.toByte()) {
            skip(1)
            position++
        }

        val header = dictionary.substringAfterLast("obj")
        val isJpeg = "/DCTDecode" in header && "/Image" in header
        val directLength = LENGTH_REGEX.find(header)?.takeIf { it.groupValues[2].isEmpty() }?.groupValues?.get(1)?.toLong()
        val length = directLength ?: indexOf(END_STREAM).takeIf { it != -1L } ?: break

        if (isJpeg && length > 0) ranges += position until position + length
        skip(length)
        position += length
    }

    return ranges
}
