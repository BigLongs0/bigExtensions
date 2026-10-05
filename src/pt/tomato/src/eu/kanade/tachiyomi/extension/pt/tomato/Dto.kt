package eu.kanade.tachiyomi.extension.pt.tomato

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class LoginRequestDto(
    private val email: String,
    private val password: String,
    private val fingerprint: String,
    private val verification: String,
)

@Serializable
class LoginDto(
    val token: String? = null,
    val message: String? = null,
)

@Serializable
class FeedDto(
    private val data: List<FeedSectionDto> = emptyList(),
) {
    val items get() = data.flatMap(FeedSectionDto::data).distinctBy(FeedItemDto::id)

    val latest get() = data.firstOrNull { "atualizad" in it.title.lowercase() }?.data.orEmpty()
}

@Serializable
class FeedSectionDto(
    val title: String,
    val data: List<FeedItemDto> = emptyList(),
)

@Serializable
class FeedItemDto(
    val id: Long,
    private val name: String,
    private val author: String? = null,
    private val thumbnail: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = name
        author = this@FeedItemDto.author?.takeIf(String::isNotBlank)
        thumbnail_url = thumbnail
    }
}

@Serializable
class SearchRequestDto(
    private val search: String,
    private val page: Int,
    private val token: String,
    @SerialName("content_type") private val contentType: String,
)

@Serializable
class SearchDto(
    val result: List<SearchItemDto> = emptyList(),
)

@Serializable
class SearchItemDto(
    private val id: Long,
    private val type: String? = null,
    private val name: String,
    private val author: String? = null,
    private val image: String? = null,
) {
    val isManga get() = type == "manga"

    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = name
        author = this@SearchItemDto.author
        thumbnail_url = image
    }
}

@Serializable
class DetailsRequestDto(
    private val id: Long,
    private val token: String,
)

@Serializable
class DetailsDto(
    val details: MangaDetailsDto,
)

@Serializable
class MangaDetailsDto(
    private val id: Long,
    private val name: String,
    private val cover: String? = null,
    private val author: String? = null,
    private val description: String? = null,
    private val genre: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = name
        thumbnail_url = cover
        author = this@MangaDetailsDto.author?.takeIf(String::isNotBlank)
        description = this@MangaDetailsDto.description?.takeIf(String::isNotBlank)
        genre = this@MangaDetailsDto.genre?.takeIf(String::isNotBlank)
    }
}

@Serializable
class ChaptersDto(
    val data: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    private val id: Long,
    private val name: String,
    private val number: Float,
    @SerialName("source_url") private val sourceUrl: String? = null,
    private val date: String? = null,
) {
    // Mirrors the app: MangaDex chapters are keyed by their UUID, own uploads by id.
    private val key get() = when {
        sourceUrl.isNullOrEmpty() -> id.toString()
        "mangadex.org" in sourceUrl -> sourceUrl.substringAfter("chapter/")
        else -> sourceUrl
    }

    fun toSChapter() = SChapter.create().apply {
        url = key
        name = this@ChapterDto.name
        chapter_number = number
        date_upload = Instant.tryParse(date)
    }
}

@Serializable
class PagesDto(
    val data: List<PageDto> = emptyList(),
)

@Serializable
class PageDto(
    @SerialName("page_url") val pageUrl: String,
)
