package eu.kanade.tachiyomi.extension.pt.auratoons

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private const val PAGE_SIZE = 24

@Serializable
class CatalogRequestDto(
    val filters: CatalogFiltersDto,
    val toolbarQuery: String,
    val sortBy: String,
    val sortDir: String,
    val page: Int,
    val blockedGenrePt: List<String>,
)

@Serializable
class CatalogFiltersDto(
    val status: List<String>,
    val types: List<String>,
    val genres: List<String>,
    val themes: List<String>,
    val categoryMode: String,
    val yearFrom: String,
    val yearTo: String,
    val chaptersMin: String,
    val chaptersMax: String,
    val adult: String,
)

@Serializable
class CatalogResponseDto(
    private val items: List<MangaDto>,
    private val total: Int,
) {
    fun toMangasPage(page: Int, baseUrl: String) = MangasPage(
        mangas = items.map { it.toSManga(baseUrl) },
        hasNextPage = items.isNotEmpty() && page * PAGE_SIZE < total,
    )
}

@Serializable
class MangaDto(
    private val mangaDexId: String,
    private val title: String,
    private val image: String? = null,
    private val genre: String? = null,
    private val status: String? = null,
    private val description: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val alternativeTitles: List<String>? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/manga/$mangaDexId"
        title = this@MangaDto.title
        thumbnail_url = image?.toAbsoluteUrl(baseUrl)
        author = this@MangaDto.author
        artist = this@MangaDto.artist
        genre = this@MangaDto.genre
        status = when (this@MangaDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        description = buildString {
            append(this@MangaDto.description.orEmpty())
            alternativeTitles?.takeIf(List<String>::isNotEmpty)?.let {
                if (isNotEmpty()) append("\n\n")
                append("Títulos alternativos: ")
                append(it.joinToString())
            }
        }
    }
}

@Serializable
class MangaDetailDto(
    val manga: MangaDto,
    private val chapters: List<ChapterDto>,
    private val scanGroupName: String? = null,
) {
    fun toSChapterList(mangaUrl: String) = chapters
        .filter(ChapterDto::isReadable)
        .sortedWith(compareByDescending(ChapterDto::number).thenByDescending(ChapterDto::readableAt))
        .map { it.toSChapter(mangaUrl, scanGroupName) }
}

@Serializable
class ChapterDto(
    private val id: String,
    private val chapterNumber: String? = null,
    private val title: String? = null,
    val readableAt: String? = null,
    private val releaseStatus: String? = null,
    private val accessLevel: String? = null,
    private val coinCost: Int = 0,
) {
    // Mirrors the site's badge logic: unpublished chapters are "Agendado" and paid or restricted ones are locked.
    val isReadable get() = (releaseStatus == null || releaseStatus == "published") && accessLevel != "restricted" && coinCost == 0

    val number get() = chapterNumber?.toFloatOrNull() ?: -1f

    fun toSChapter(mangaUrl: String, scanlator: String?) = SChapter.create().apply {
        url = "$mangaUrl/capitulo/$id"
        name = buildString {
            append("Capítulo ").append(chapterNumber ?: title ?: id)
            if (chapterNumber != null) {
                title?.takeIf { it.isNotBlank() && !it.equals("Capitulo", ignoreCase = true) && !it.equals("Capítulo", ignoreCase = true) }
                    ?.let { append(" - ").append(it) }
            }
        }
        chapter_number = number
        date_upload = readableAt?.let { Instant.parseOrNull(it) }?.toEpochMilliseconds() ?: 0L
        this.scanlator = scanlator
    }
}

@Serializable
class ChapterPagesDto(
    private val ok: Boolean = false,
    private val urls: List<String> = emptyList(),
    private val reason: String? = null,
) {
    val gateMessage get() = when {
        ok -> null
        reason == "limit_reached" -> "Limite diário de leitura gratuita da Aura Toons atingido. Tente novamente mais tarde."
        reason == "register_required" -> "A Aura Toons exige uma conta gratuita para continuar lendo hoje."
        reason != null -> "Capítulo indisponível na Aura Toons ($reason)."
        else -> null
    }

    fun toPageList(baseUrl: String) = urls.mapIndexed { index, url ->
        Page(index, imageUrl = url.toAbsoluteUrl(baseUrl))
    }
}

@Serializable
class FilterDataDto(
    val genres: List<FilterOptionDto>,
    val themes: List<FilterOptionDto>,
)

@Serializable
class FilterOptionDto(
    val label: String,
    val value: String,
)

private fun String.toAbsoluteUrl(baseUrl: String) = if (startsWith("/")) baseUrl + this else this
