package eu.kanade.tachiyomi.extension.pt.mangotoons

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

private const val CDN_URL = "https://cdn.mangotoons.com"

private fun String.absoluteCdnUrl() = if (startsWith("/")) CDN_URL + this else this

@Serializable
class ListDto(
    private val obras: List<WorkDto> = emptyList(),
    private val pagina: Int,
    @SerialName("total_paginas") private val totalPaginas: Int,
) {
    val mangas get() = obras.map(WorkDto::toSManga)

    val hasNextPage get() = pagina < totalPaginas
}

@Serializable
class WorkDto(
    private val id: Int,
    private val nome: String,
    private val imagem: String? = null,
    private val descricao: String? = null,
    private val status: String? = null,
    private val formato: String? = null,
    private val tags: List<String> = emptyList(),
    private val capitulos: List<ChapterDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = id.toString()
        title = nome
        thumbnail_url = imagem?.takeIf(String::isNotBlank)?.absoluteCdnUrl()
        description = descricao?.takeIf(String::isNotBlank)
        genre = (listOfNotNull(formato?.takeIf(String::isNotBlank)) + tags)
            .distinct()
            .joinToString()
        status = when (this@WorkDto.status?.lowercase()) {
            "em andamento", "lançando", "lancando", "ativo" -> SManga.ONGOING
            "completo", "concluído", "concluido", "finalizado" -> SManga.COMPLETED
            "hiato", "pausado" -> SManga.ON_HIATUS
            "cancelado" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    val chapterList get() = capitulos.map { it.toSChapter(id) }.sortedByDescending(SChapter::chapter_number)
}

@Serializable
class ChapterDto(
    private val numero: String,
    private val titulo: String? = null,
    @SerialName("is_vip") private val isVip: Boolean = false,
    @SerialName("published_at") private val publishedAt: String? = null,
) {
    // The reader looks chapters up by their number exactly as the API spells it.
    fun toSChapter(workId: Int) = SChapter.create().apply {
        url = "/obra/$workId/capitulo/$numero"
        name = buildString {
            append(titulo?.takeIf(String::isNotBlank) ?: "Capítulo $numero")
            if (isVip) append(" 🔒")
        }
        chapter_number = numero.toFloatOrNull() ?: -1f
        date_upload = Instant.tryParse(publishedAt)
    }
}

@Serializable
class ManifestDto(
    private val pages: List<ManifestPageDto> = emptyList(),
    val isVip: Boolean = false,
) {
    fun toPageList() = pages.sortedBy(ManifestPageDto::index).map { Page(it.index, imageUrl = it.url.absoluteCdnUrl()) }
}

@Serializable
class ManifestPageDto(
    val index: Int,
    val url: String,
)
