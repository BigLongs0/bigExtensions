package eu.kanade.tachiyomi.extension.pt.onereader

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

@Serializable
class CatalogDto(
    private val works: List<WorkDto>,
    private val pagination: CatalogPaginationDto,
) {
    fun toMangasPage() = MangasPage(
        works.filterNot(WorkDto::locked).map(WorkDto::toSManga),
        pagination.page < pagination.totalPages,
    )
}

@Serializable
class CatalogPaginationDto(
    val page: Int,
    val totalPages: Int,
)

@Serializable
class UpdatesDto(
    private val items: List<WorkDto>,
    private val pagination: UpdatesPaginationDto,
) {
    fun toMangasPage() = MangasPage(
        items.filterNot(WorkDto::locked).map(WorkDto::toSManga),
        pagination.page < pagination.pages,
    )
}

@Serializable
class UpdatesPaginationDto(
    val page: Int,
    val pages: Int,
)

// Locked entries are premium previews whose real title and cover are withheld.
@Serializable
class WorkDto(
    private val id: String,
    private val title: String,
    private val coverUrl: String? = null,
    val locked: Boolean = false,
) {
    fun toSManga() = SManga.create().apply {
        url = id
        title = this@WorkDto.title
        thumbnail_url = coverUrl
    }
}

@Serializable
class WorkDetailsDto(
    val work: WorkInfoDto,
    private val chapters: List<ChapterDto>,
) {
    fun toSChapterList() = chapters.sortedByDescending(ChapterDto::number).map { it.toSChapter(work.id) }
}

@Serializable
class WorkInfoDto(
    val id: String,
    private val title: String,
    private val originalName: String? = null,
    private val nativeTitle: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val synopsis: String? = null,
    private val status: String? = null,
    private val contentType: String? = null,
    private val genres: List<String> = emptyList(),
    private val coverUrl: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = id
        title = this@WorkInfoDto.title
        thumbnail_url = coverUrl
        author = this@WorkInfoDto.author?.trim()?.takeIf(String::isNotEmpty)
        artist = this@WorkInfoDto.artist?.trim()?.takeIf(String::isNotEmpty)
        genre = (listOfNotNull(contentType) + genres).joinToString()
        status = when (this@WorkInfoDto.status?.lowercase()) {
            "em lançamento" -> SManga.ONGOING
            "completo" -> SManga.COMPLETED
            "hiatus", "hiato" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            synopsis?.trim()?.takeIf(String::isNotEmpty)?.let(::append)
            val alternatives = listOfNotNull(originalName, nativeTitle)
                .map(String::trim)
                .filter { it.isNotEmpty() && it != this@WorkInfoDto.title }
                .distinct()
            if (alternatives.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos: ").append(alternatives.joinToString())
            }
        }.takeIf(String::isNotEmpty)
    }
}

@Serializable
class ChapterDto(
    val number: Float,
    private val title: String? = null,
    private val isPremium: Boolean = false,
    private val postedAt: String? = null,
) {
    fun toSChapter(workId: String) = SChapter.create().apply {
        val label = number.toString().removeSuffix(".0")
        val cleanTitle = title?.trim()?.takeIf(String::isNotEmpty)
        url = "/leitor?id=$workId&cap=$label"
        name = buildString {
            if (isPremium) append("🔒 ")
            append(
                when {
                    cleanTitle == null -> "Capítulo $label"
                    label in cleanTitle -> cleanTitle
                    else -> "Capítulo $label - $cleanTitle"
                },
            )
        }
        chapter_number = number
        // The API mixes ISO instants with plain UTC timestamps.
        date_upload = Instant.tryParse(postedAt).takeIf { it != 0L } ?: DATE_FORMAT.tryParseDateTime(postedAt, ZoneOffset.UTC)
    }
}

@Serializable
class ReaderManifestDto(
    val chapter: ReaderChapterDto,
)

@Serializable
class ReaderChapterDto(
    val pages: List<String>,
)

@Serializable
class MediaDto(
    val mode: String,
    val url: String,
    val key: String? = null,
    val keyWrap: KeyWrapDto? = null,
    val contentType: String? = null,
)

@Serializable
class KeyWrapDto(
    val mode: String,
    val serverKey: String,
    val context: String? = null,
    val iv: String,
    val payload: String,
)

@Serializable
class ApiErrorDto(
    val code: String? = null,
    val message: String? = null,
)

@Serializable
class CatalogMetaDto(
    private val genres: MetaGenresDto,
) {
    fun toFilterData() = FilterDataDto(genres.all.map(MetaGenreDto::name))
}

@Serializable
class MetaGenresDto(
    @SerialName("ALL") val all: List<MetaGenreDto>,
)

@Serializable
class MetaGenreDto(
    val name: String,
)

@Serializable
class FilterDataDto(
    val genres: List<String>,
)
