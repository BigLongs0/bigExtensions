package eu.kanade.tachiyomi.extension.en.mangaball

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
class MangaListResponseDto(
    private val data: List<MangaDto>,
    private val pagination: PaginationDto,
) {
    fun toMangasPage() = MangasPage(
        mangas = data.mapNotNull(MangaDto::toSMangaOrNull),
        hasNextPage = pagination.page < pagination.totalPages,
    )
}

@Serializable
class PaginationDto(
    val page: Int,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
class MangaDetailsResponseDto(
    val data: MangaDto,
)

@Serializable
class MangaDto(
    private val id: String,
    private val name: String? = null,
    private val slug: String? = null,
    private val image: ImageDto? = null,
    private val alternateName: List<String> = emptyList(),
    private val description: List<String> = emptyList(),
    private val status: String? = null,
    private val authors: List<NamedDto> = emptyList(),
    private val tags: List<NamedDto> = emptyList(),
    private val is18plus: Boolean = false,
) {
    // A few catalog entries have no name at all; they cannot be listed.
    fun toSMangaOrNull() = name?.let { toSManga() }

    fun toSManga() = SManga.create().apply {
        url = MangaKey(slug?.takeIf(String::isNotEmpty) ?: id, id).serialized
        title = name ?: throw Exception("Manga title not found")
        thumbnail_url = image?.toUrl()
        author = authors.map { it.name }.distinct().joinToString().takeIf(String::isNotEmpty)
        genre = buildList {
            tags.mapTo(this) { it.name }
            if (is18plus) add("Adult")
        }.distinct().joinToString().takeIf(String::isNotEmpty)
        status = this@MangaDto.status.toMangaStatus()
        description = buildString {
            append(this@MangaDto.description.joinToString("\n\n"))
            val alternatives = alternateName
                .filterNot { it.equals(name, ignoreCase = true) }
                .distinctBy(String::lowercase)
            if (alternatives.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative titles:\n")
                append(alternatives.joinToString("\n"))
            }
        }.takeIf(String::isNotEmpty)
    }
}

@Serializable
class ImageDto(
    private val cover: CoverDto? = null,
    @SerialName("cdn_mangadex") private val mangadex: String? = null,
    @SerialName("cdn_mangaupdate") private val mangaupdates: String? = null,
) {
    fun toUrl(): String? = cover?.path?.takeIf(String::isNotEmpty)?.let { COVER_CDN + it }
        ?: mangadex?.takeIf(String::isNotEmpty)
        ?: mangaupdates?.takeIf(String::isNotEmpty)
}

@Serializable
class CoverDto(
    val path: String? = null,
)

@Serializable
class NamedDto(
    val name: String,
)

@Serializable
class ChapterListResponseDto(
    private val data: List<ChapterDto>,
) {
    fun toSChapterList() = data.map(ChapterDto::toSChapter)
}

@Serializable
class ChapterDto(
    private val id: String,
    private val name: String? = null,
    private val number: Double? = null,
    private val volume: Double? = null,
    @SerialName("group_name") private val groupName: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
) {
    fun toSChapter() = SChapter.create().apply {
        url = id
        chapter_number = number?.toFloat() ?: -1f
        scanlator = groupName?.takeIf(String::isNotEmpty)
        date_upload = DATE_FORMAT.tryParseDateTime(createdAt, ZoneOffset.UTC)

        val label = this@ChapterDto.name?.takeIf(String::isNotBlank)
            ?: number?.let { "Chapter ${it.toString().removeSuffix(".0")}" }
            ?: "Chapter"
        val volumeNumber = volume?.takeIf { it > 0 }?.toString()?.removeSuffix(".0")
        name = if (volumeNumber != null && !label.contains("Vol.", ignoreCase = true)) {
            "Vol. $volumeNumber $label"
        } else {
            label
        }
    }
}

@Serializable
class ChapterListRequestDto(
    @SerialName("title_id") val titleId: String,
    val lang: String,
)

@Serializable
class ChapterPagesDto(
    @SerialName("title_id") val titleId: String,
    val pages: List<String> = emptyList(),
)

@Serializable
class TaxonomyResponseDto(
    val data: TaxonomyDto,
)

// Items keep the `_id` key so filter data cached by the previous site version still parses.
@Serializable
class TaxonomyDto(
    val content: List<TaxonomyItemDto> = emptyList(),
    val format: List<TaxonomyItemDto> = emptyList(),
    val genre: List<TaxonomyItemDto> = emptyList(),
    val theme: List<TaxonomyItemDto> = emptyList(),
)

@Serializable
class TaxonomyItemDto(
    @SerialName("_id") val id: String,
    val name: String,
)

internal class MangaKey(
    val slug: String,
    val id: String,
) {
    val serialized get() = "$slug/$id"
}

internal fun String.toMangaKey(): MangaKey {
    val separator = lastIndexOf('/')
    require(separator in 1 until lastIndex) { "Invalid stored manga URL" }
    return MangaKey(substring(0, separator), substring(separator + 1))
}

private fun String?.toMangaStatus(): Int = when (this?.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus", "on_hold", "on-hold" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

private const val COVER_CDN = "https://bulbasaur.poke-black-and-white.net/covers/"

private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME
