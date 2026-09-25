package eu.kanade.tachiyomi.extension.pt.onereader

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UrlFilter {
    fun addToUrl(builder: HttpUrl.Builder)
}

open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
    state: Int = 0,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), state) {
    val selectedValue get() = options[state].second
}

class SortFilter :
    SelectFilter(
        "Ordenar por",
        listOf(
            "Mais recentes" to "DATE",
            "Mais lidas" to "VIEWS",
            "Melhor avaliadas" to "RATING",
            "A-Z" to "AZ",
        ),
        state = 1,
    )

class FormatFilter :
    SelectFilter(
        "Formato",
        listOf(
            "Todos" to "",
            "Webtoon" to "WEBTOON",
            "Mangá" to "MANGÁ",
            "Manhua" to "MANHUA",
            "Manhwa" to "MANHWA",
            "Shoujo" to "SHOUJO",
        ),
    ),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        selectedValue.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter("format", it) }
    }
}

class StatusFilter :
    SelectFilter(
        "Status",
        listOf(
            "Todos" to "",
            "Em lançamento" to "LANÇANDO",
            "Completo" to "COMPLETO",
            "Hiato" to "HIATO",
        ),
    ),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        selectedValue.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter("status", it) }
    }
}

class Genre(name: String) : Filter.TriState(name)

class GenreFilter(genres: List<String>) :
    Filter.Group<Genre>("Gêneros", genres.map(::Genre)),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        state.forEach {
            when (it.state) {
                Filter.TriState.STATE_INCLUDE -> builder.addQueryParameter("genre", it.name)
                Filter.TriState.STATE_EXCLUDE -> builder.addQueryParameter("excludeGenre", it.name)
            }
        }
    }
}

class GenreModeFilter :
    Filter.CheckBox("Exigir todos os gêneros incluídos"),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        if (state) builder.addQueryParameter("genreMode", "all")
    }
}
