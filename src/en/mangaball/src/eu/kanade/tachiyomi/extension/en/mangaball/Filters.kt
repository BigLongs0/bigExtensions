package eu.kanade.tachiyomi.extension.en.mangaball

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl

internal interface UrlFilter {
    fun addToUrl(builder: HttpUrl.Builder)
}

internal class MangaBallFilters(private val taxonomy: TaxonomyDto?) {
    fun getFilterList() = FilterList(
        SortFilter(),
        Filter.Separator(),
        TagModeFilter(),
        TagFilter("Content", taxonomy?.content.orEmpty()),
        TagFilter("Format", taxonomy?.format.orEmpty()),
        TagFilter("Genres", taxonomy?.genre.orEmpty()),
        TagFilter("Themes", taxonomy?.theme.orEmpty()),
        Filter.Separator(),
        DemographicFilter(),
        TypeFilter(),
        StatusFilter(),
        AdultFilter(),
    )
}

private class FilterOption(val name: String, val value: String)

private open class UrlSelectFilter(
    name: String,
    private val parameter: String,
    private val options: List<FilterOption>,
) : Filter.Select<String>(name, options.map(FilterOption::name).toTypedArray()),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        options[state].value.takeIf(String::isNotEmpty)?.let { builder.addQueryParameter(parameter, it) }
    }
}

private class TagModeFilter :
    UrlSelectFilter(
        "Included tags match",
        "tag_mode",
        listOf(
            FilterOption("All", "AND"),
            FilterOption("Any", "OR"),
        ),
    )

internal class TagOption(name: String, val id: String) : Filter.TriState(name)

internal class TagFilter(name: String, tags: List<TaxonomyItemDto>) : Filter.Group<TagOption>(name, tags.map { TagOption(it.name, it.id) }) {
    val included get() = state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map(TagOption::id)
    val excluded get() = state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map(TagOption::id)
}

private class DemographicFilter :
    UrlSelectFilter(
        "Demographic",
        "publicationDemographic",
        listOf(
            FilterOption("Any", ""),
            FilterOption("Shounen", "shounen"),
            FilterOption("Shoujo", "shoujo"),
            FilterOption("Seinen", "seinen"),
            FilterOption("Josei", "josei"),
        ),
    )

private class TypeFilter :
    UrlSelectFilter(
        "Type",
        "type",
        listOf(
            FilterOption("Any", ""),
            FilterOption("Manga", "manga"),
            FilterOption("Manhwa", "manhwa"),
            FilterOption("Manhua", "manhua"),
            FilterOption("Comics", "comics"),
        ),
    )

private class StatusFilter :
    UrlSelectFilter(
        "Publication status",
        "status",
        listOf(
            FilterOption("Any", ""),
            FilterOption("Ongoing", "ongoing"),
            FilterOption("Completed", "completed"),
            FilterOption("Hiatus", "hiatus"),
        ),
    )

private class AdultFilter :
    UrlSelectFilter(
        "Adult content",
        "adult_mode",
        listOf(
            FilterOption("Show", "all"),
            FilterOption("Hide", "no_18"),
            FilterOption("Only", "only_18"),
        ),
    )

internal class SortFilter(selection: Selection = Selection(0, false)) :
    Filter.Sort(
        "Sort by",
        SORT_OPTIONS.map(FilterOption::name).toTypedArray(),
        selection,
    ),
    UrlFilter {
    override fun addToUrl(builder: HttpUrl.Builder) {
        val selection = state ?: Selection(0, false)
        builder.setQueryParameter("sort_by", SORT_OPTIONS[selection.index].value)
        builder.setQueryParameter("sort_order", if (selection.ascending) "asc" else "desc")
    }

    companion object {
        fun popular() = SortFilter(Selection(SORT_OPTIONS.indexOfFirst { it.value == "views" }, false))
        fun latest() = SortFilter(Selection(SORT_OPTIONS.indexOfFirst { it.value == "lastupdate" }, false))
    }
}

private val SORT_OPTIONS = listOf(
    FilterOption("Views", "views"),
    FilterOption("Latest updated", "lastupdate"),
    FilterOption("Rating", "rating"),
    FilterOption("Recently added", "created_at"),
    FilterOption("Title", "name"),
)
