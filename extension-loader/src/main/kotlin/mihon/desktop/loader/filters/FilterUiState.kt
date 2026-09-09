package mihon.desktop.loader.filters

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import mihon.desktop.loader.log.Logger
import java.util.IdentityHashMap

/**
 * UI-facing view of one entry in a source's [FilterList].
 *
 * Mirrors the filter contracts extension jars were compiled against. Unsupported
 * [Filter] subclasses are simply not extracted (see [FilterUiState]).
 */
sealed interface FilterItem {
    val filter: Filter<*>

    data class Header(override val filter: Filter.Header) : FilterItem
    data class Separator(override val filter: Filter.Separator) : FilterItem
    data class Text(override val filter: Filter.Text) : FilterItem
    data class Select(override val filter: Filter.Select<*>) : FilterItem
    data class CheckBox(override val filter: Filter.CheckBox) : FilterItem
    data class TriState(override val filter: Filter.TriState) : FilterItem
    data class Sort(override val filter: Filter.Sort) : FilterItem
    data class Group(override val filter: Filter.Group<*>, val children: List<FilterItem>) : FilterItem
}

/**
 * Mutable, per-visit state for one source's filter list.
 *
 * [filterList] holds the live filter instances; the UI mutates `state` in place
 * and the same list is handed to
 * `CatalogueSource.getSearchManga(page, query, filters)` so extensions observe
 * their own filter objects — exactly what upstream Mihon does.
 *
 * Defaults are snapshotted (by identity) at construction so [activeCount] and
 * [reset] work even for sources whose defaults are non-zero.
 *
 * Caveat: filter instances are captured once, at construction. Sources that
 * return fresh instances on every `getFilterList()` call will not observe
 * edits made through this object — always pass [filterList] to the search
 * call, never a newly built list.
 *
 * Persistence is deliberately NOT implemented: state lives for one screen
 * visit only (back navigation drops it; that matches the desktop app's
 * current scope — Mihon persists some filters per source, out of scope here).
 */
class FilterUiState(val filterList: FilterList) {

    /** Renderable entries; unsupported filter types are skipped and logged. */
    val items: List<FilterItem> = extract(filterList)

    /** Default state per filter instance, keyed by identity (Filter.equals is value-based). */
    private val defaults = IdentityHashMap<Filter<*>, Any?>()

    init {
        captureDefaults(filterList)
    }

    /**
     * Number of filters (headers/separators excluded, groups recursed into)
     * whose current state differs from the snapshotted default.
     */
    fun activeCount(): Int = defaults.count { (filter, default) ->
        filter !is Filter.Header &&
            filter !is Filter.Separator &&
            filter !is Filter.Group<*> &&
            filter.state != default
    }

    fun hasActiveFilters(): Boolean = activeCount() > 0

    /** Restores every filter (groups recursed into) to its snapshotted default state. */
    fun reset() {
        for ((filter, state) in defaults) {
            @Suppress("UNCHECKED_CAST")
            (filter as Filter<Any?>).state = state
        }
    }

    private fun captureDefaults(filters: List<Filter<*>>) {
        for (filter in filters) {
            defaults[filter] = filter.state
            if (filter is Filter.Group<*>) {
                captureDefaults(filter.state.filterIsInstance<Filter<*>>())
            }
        }
    }

    companion object {
        private const val TAG = "FilterUiState"

        private fun extract(filters: List<Filter<*>>): List<FilterItem> = filters.mapNotNull(::toItem)

        private fun toItem(filter: Filter<*>): FilterItem? = when (filter) {
            is Filter.Header -> FilterItem.Header(filter)
            is Filter.Separator -> FilterItem.Separator(filter)
            is Filter.Text -> FilterItem.Text(filter)
            is Filter.Select<*> -> FilterItem.Select(filter)
            is Filter.CheckBox -> FilterItem.CheckBox(filter)
            is Filter.TriState -> FilterItem.TriState(filter)
            is Filter.Sort -> FilterItem.Sort(filter)
            is Filter.Group<*> -> {
                val children = filter.state.filterIsInstance<Filter<*>>()
                if (children.isEmpty() && filter.state.isNotEmpty()) {
                    Logger.w(TAG, "Skipping group '${filter.name}': children are not Filter instances")
                    null
                } else {
                    FilterItem.Group(filter, extract(children))
                }
            }
            else -> {
                Logger.w(TAG, "Skipping unsupported filter '${filter.name}' (${filter::class.qualifiedName})")
                null
            }
        }
    }
}
