package eu.kanade.tachiyomi.extension.pt.lycantoons

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
            "Mais vistos" to "views",
            "Atualizados" to "updated",
            "Recentes" to null,
            "Melhor avaliados" to "rating",
            "A-Z" to "title",
        ),
    )

class TypeFilter :
    SelectFilter(
        "Tipo",
        listOf(
            "Todos" to null,
            "Mangá" to "MANGA",
            "Manhwa" to "MANHWA",
            "Manhua" to "MANHUA",
            "Webtoon" to "WEBTOON",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        listOf(
            "Todos" to null,
            "Em andamento" to "ONGOING",
            "Completo" to "COMPLETED",
            "Hiato" to "HIATUS",
            "Cancelado" to "CANCELLED",
        ),
    )

class OriginalFilter : Filter.CheckBox("Somente originais da LycanToons")

class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class GenreFilter : Filter.Group<GenreCheckBox>("Gêneros", GENRES.map { (value, name) -> GenreCheckBox(name, value) }) {
    val selectedValues get() = state.filter { it.state }.map { it.value }.takeIf(List<String>::isNotEmpty)
}

val GENRES = linkedMapOf(
    "action" to "Ação",
    "adventure" to "Aventura",
    "comedy" to "Comédia",
    "drama" to "Drama",
    "romance" to "Romance",
    "fantasy" to "Fantasia",
    "sci-fi" to "Ficção Científica",
    "horror" to "Horror",
    "mystery" to "Mistério",
    "slice-of-life" to "Slice of Life",
    "supernatural" to "Sobrenatural",
    "sports" to "Esporte",
    "psychological" to "Psicológico",
    "thriller" to "Thriller",
    "tragedy" to "Tragédia",
    "seinen" to "Seinen",
    "shounen" to "Shounen",
    "shoujo" to "Shoujo",
    "josei" to "Josei",
    "harem" to "Harem",
    "reverse-harem" to "Harem Reverso",
    "ecchi" to "Ecchi",
    "yaoi" to "Yaoi",
    "yuri" to "Yuri",
    "martial-arts" to "Artes Marciais",
    "wuxia" to "Wuxia",
    "xianxia" to "Xianxia",
    "xuanhuan" to "Xuanhuan",
    "murim" to "Murim",
    "cultivation" to "Cultivação",
    "isekai" to "Isekai",
    "system" to "Sistema",
    "game" to "Game",
    "dungeon" to "Dungeon",
    "gate" to "Gate",
    "constellation" to "Constelação",
    "reincarnation" to "Reencarnação",
    "regression" to "Regressão",
    "returned-hero" to "Herói Retornado",
    "overpowered-mc" to "MC Apelão",
    "weak-to-strong" to "Fraco ao Forte",
    "historical" to "Histórico",
    "post-apocalyptic" to "Pós-Apocalíptico",
    "revenge" to "Vingança",
    "survival" to "Sobrevivência",
    "time-travel" to "Viagem no Tempo",
    "academy" to "Academia",
    "school-life" to "Vida Escolar",
    "royal" to "Realeza",
    "villainess" to "Vilã",
    "gore" to "Gore",
    "tragedy-dark" to "Sangue/Violência",
    "vampire" to "Vampiro",
    "zombie" to "Zumbi",
    "demons" to "Demônios",
    "mecha" to "Mecha",
    "military" to "Militar",
    "magic" to "Magia",
    "necromancer" to "Necromante",
    "monster-taming" to "Doma de Monstros",
    "business" to "Negócios",
    "cooking" to "Culinária",
    "medical" to "Médico",
    "music" to "Música",
    "police" to "Polícia",
    "parody" to "Paródia",
)
