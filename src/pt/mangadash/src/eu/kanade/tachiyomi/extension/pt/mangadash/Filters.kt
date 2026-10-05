package eu.kanade.tachiyomi.extension.pt.mangadash

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(
    name: String,
    private val options: List<Pair<String, String?>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selectedValue get() = options[state].second
}

class SortFilter :
    SelectFilter(
        "Ordenar por",
        listOf(
            "Mais vistos" to "populares",
            "Mais recentes" to "recentes",
            "Melhor avaliados" to "nota",
            "A-Z" to "alfabetica",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        listOf(
            "Todos" to null,
            "Em lançamento" to "Em Lançamento",
            "Concluído" to "Concluído",
            "Em hiato" to "Em hiato",
        ),
    )

class AdultFilter :
    SelectFilter(
        "Conteúdo +18",
        listOf(
            "Mostrar" to null,
            "Esconder" to "false",
            "Somente +18" to "true",
        ),
    )

class CategoryFilter :
    SelectFilter(
        "Categoria",
        listOf(
            "Todas" to null,
            "Ação" to "acao",
            "Aventura" to "aventura",
            "Comédia" to "comedia",
            "Cotidianos" to "cotidianos",
            "Drama" to "drama",
            "Ecchi" to "ecchi",
            "Esportes" to "esportes",
            "Fantasia" to "fantasia",
            "Ficção Científica" to "ficcao-cientifica",
            "Harem" to "harem",
            "Hentai" to "hentai",
            "Histórico" to "historico",
            "Isekai" to "isekai",
            "Mecha" to "mecha",
            "Mistério" to "misterio",
            "Música" to "musica",
            "Psicológico" to "psicologico",
            "Romance" to "romance",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Sobrenatural" to "sobrenatural",
            "Suspense" to "suspense",
            "Terror" to "terror",
            "Tragédia" to "tragedy",
            "Yaoi" to "yaoi",
        ),
    )
