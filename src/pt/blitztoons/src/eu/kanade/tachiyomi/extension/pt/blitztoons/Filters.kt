package eu.kanade.tachiyomi.extension.pt.blitztoons

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

@Serializable
class GenreDto(
    val name: String,
    val slug: String,
)

interface UrlFilter {
    fun addToUrl(builder: HttpUrl.Builder)
}

open class SelectFilter(
    name: String,
    private val parameter: String,
    private val options: List<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), state),
    UrlFilter {
    val selectedValue get() = options[state].second

    override fun addToUrl(builder: HttpUrl.Builder) {
        selectedValue.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter(parameter, it) }
    }
}

class SortFilter(default: String = "populares") :
    SelectFilter(
        "Ordenar por",
        "ordem",
        SORT_OPTIONS,
        SORT_OPTIONS.indexOfFirst { it.second == default },
    )

private val SORT_OPTIONS = listOf(
    "Recentes" to "recentes",
    "Populares" to "populares",
    "Melhor nota" to "nota",
    "A-Z" to "alfabetica",
)

class TypeFilter :
    SelectFilter(
        "Categoria",
        "tipo",
        listOf(
            "Todos" to "",
            "Comics" to "comic",
            "Shoujo" to "shoujo",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        "status",
        listOf(
            "Todos" to "",
            "Em lançamento" to "ongoing",
            "Completo" to "completed",
            "Hiato" to "hiatus",
        ),
    )

class GenreFilter(genres: List<GenreDto>) :
    SelectFilter(
        "Gênero",
        "genero",
        listOf("Todos" to "") + genres.map { it.name to it.slug },
    )
