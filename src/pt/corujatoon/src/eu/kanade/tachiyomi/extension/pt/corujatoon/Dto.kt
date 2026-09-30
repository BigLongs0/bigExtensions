package eu.kanade.tachiyomi.extension.pt.corujatoon

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class MangaListDto(val series: List<MangaDto>, val pagination: PaginationDto)

@Serializable
class PaginationDto(val totalPages: Int)

@Serializable
class UpdatesDto(val updates: List<MangaDto>)

@Serializable
class MangaDto(
    private val slug: String,
    private val title: String,
    private val cover: String? = null,
    private val description: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val status: String? = null,
    @SerialName("SeriesGenre") private val genres: List<SeriesGenreDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "/series/$slug"
        title = this@MangaDto.title.trim()
        thumbnail_url = cover
        description = this@MangaDto.description
        author = this@MangaDto.author
        artist = this@MangaDto.artist
        genre = genres.joinToString { it.genre.name }
        status = mangaStatus(this@MangaDto.status)
    }
}

@Serializable
class SeriesGenreDto(@SerialName("Genre") val genre: GenreDto)

@Serializable
class GenreDto(val name: String)

@Serializable
class DescriptionDto(val description: String?)

@Serializable
class ChapterListDto(
    private val chapters: List<ChapterDto>,
    private val seriesSlug: String,
    val seriesTitle: String,
    val seriesCover: String? = null,
) {
    fun chapterList() = chapters.map { it.toSChapter(seriesSlug) }.sortedByDescending { it.chapter_number }
}

@Serializable
class ChapterDto(
    private val number: Float,
    private val title: String,
    private val publishedAt: String? = null,
    private val isVip: Boolean = false,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        url = "/series/$slug/capitulo/${number.toString().removeSuffix(".0")}"
        name = if (isVip) "🔒 ${title.trim()}" else title.trim()
        chapter_number = number
        date_upload = Instant.tryParse(publishedAt)
    }
}

@Serializable
class ReaderDto(val chapter: ReaderChapterDto)

@Serializable
class ReaderChapterDto(val content: List<String> = emptyList())

@Serializable
class CsrfDto(val csrfToken: String)

@Serializable
class LoginDto(val url: String)

@Serializable
class GenresDto(val genres: List<GenreOptionDto>)

@Serializable
class GenreOptionDto(val name: String, val slug: String)

fun mangaStatus(status: String?): Int = when (status?.lowercase()) {
    "ongoing", "em andamento" -> SManga.ONGOING
    "completed", "completo", "concluído" -> SManga.COMPLETED
    "hiatus", "em hiato" -> SManga.ON_HIATUS
    "cancelled", "cancelada", "cancelado" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}
