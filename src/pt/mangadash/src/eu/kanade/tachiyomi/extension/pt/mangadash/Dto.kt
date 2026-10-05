package eu.kanade.tachiyomi.extension.pt.mangadash

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

@Serializable
class MangaListDto(
    val items: List<MangaDto>,
    private val pagination: PaginationDto,
) {
    val hasNextPage get() = pagination.hasNext
}

@Serializable
class PaginationDto(
    @SerialName("has_next") val hasNext: Boolean,
)

@Serializable
class MangaDto(
    private val id: Int,
    private val nome: String,
    private val capa: String? = null,
) {
    // Relative covers are resolved against the site's cover folder, as its own scripts do.
    fun toSManga(coverBaseUrl: HttpUrl) = SManga.create().apply {
        url = id.toString()
        title = nome
        thumbnail_url = capa?.takeIf(String::isNotBlank)?.let { coverBaseUrl.resolve(it)?.toString() }
    }
}

@Serializable
class ChapterViewerDto(
    val pdfUrl: String? = null,
)
