package eu.kanade.tachiyomi.extension.pt.lycantoons

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

@Serializable
class SeriesRequestDto(
    private val page: Int,
    private val limit: Int,
    private val search: String? = null,
    private val seriesType: String? = null,
    private val status: String? = null,
    private val tags: List<String>? = null,
    private val original: Boolean? = null,
    private val orderBy: String? = null,
)

@Serializable
class SeriesListDto(
    val series: List<SeriesDto>,
    private val pagination: PaginationDto,
) {
    val hasNextPage get() = pagination.hasNextPage
}

@Serializable
class PaginationDto(
    val hasNextPage: Boolean,
)

@Serializable
class SeriesDto(
    private val slug: String,
    private val title: String,
    private val coverUrl: String? = null,
    private val seriesType: String? = null,
) {
    val isNovel get() = seriesType == "NOVEL"

    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = coverUrl?.takeIf(String::isNotBlank)
    }
}

@Serializable
class SeriesPageDto(
    val obra: SeriesDetailsDto,
)

@Serializable
class SeriesDetailsDto(
    private val slug: String,
    private val title: String,
    @SerialName("original_title") private val originalTitle: String? = null,
    private val description: String? = null,
    private val coverUrl: String? = null,
    private val status: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val genre: List<String> = emptyList(),
    private val seriesType: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SeriesDetailsDto.title
        thumbnail_url = coverUrl?.takeIf(String::isNotBlank)
        author = this@SeriesDetailsDto.author.known()
        artist = this@SeriesDetailsDto.artist.known()
        description = buildString {
            this@SeriesDetailsDto.description?.takeIf(String::isNotBlank)?.let(::append)
            originalTitle?.takeIf { it.isNotBlank() && it != title }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Título original: $it")
            }
        }.takeIf(String::isNotEmpty)
        genre = buildList {
            seriesType?.let { add(it.lowercase().replaceFirstChar(Char::uppercase)) }
            this@SeriesDetailsDto.genre.mapTo(this) { GENRES[it] ?: it }
        }.distinct().joinToString()
        status = when (this@SeriesDetailsDto.status) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun String?.known() = this?.takeIf { it.isNotBlank() && it != "-" }
}

@Serializable
class ChapterListDto(
    val chapters: List<ChapterDto>,
)

@Serializable
class ChapterDto(
    val id: Long,
    private val numero: Float,
    private val titulo: String? = null,
    private val createdAt: String? = null,
) {
    val label get() = numero.toString().removeSuffix(".0")

    fun toSChapter(slug: String) = SChapter.create().apply {
        url = "/series/$slug/$label"
        name = buildString {
            append("Capítulo $label")
            titulo?.takeIf(String::isNotBlank)?.let { append(" - $it") }
        }
        chapter_number = numero
        date_upload = Instant.tryParse(createdAt)
        memo = buildJsonObject { put("id", id) }
    }
}

@Serializable
class PagesDto(
    val pages: List<String> = emptyList(),
)
