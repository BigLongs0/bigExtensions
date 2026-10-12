package eu.kanade.tachiyomi.extension.pt.mangotoons

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selectedValue get() = options[state].second
}

class SortFilter :
    SelectFilter(
        "Ordenar",
        listOf(
            "Mais populares" to "populares",
            "Mais recentes" to "recentes",
            "Melhor avaliados" to "rating",
            "Mais capítulos" to "capitulos",
            "A-Z" to "alfabetico",
        ),
    )

class FormatFilter :
    SelectFilter(
        "Formato",
        listOf(
            "Todos" to "",
            "Manhwa" to "MANHWA",
            "Mangá" to "MANGA",
            "Manhua" to "MANHUA",
            "Webtoon" to "WEBTOON",
            "Novel" to "NOVEL",
            "Comic" to "COMIC",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        listOf(
            "Todos" to "",
            "Em andamento" to "Em andamento",
            "Completo" to "Completo",
            "Hiato" to "Hiato",
        ),
    )
