package mihon.desktop.loader.filters

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FilterUiState]: extraction per filter type, unknown-subclass skipping,
 * active-count semantics, reset-to-defaults, group edge cases.
 */
class FilterUiStateTest {

    // Fake extension-style subclasses (the contracts in source-api are abstract
    // for everything except Header/Separator).
    private class FakeText(name: String, state: String = "") : Filter.Text(name, state)
    private class FakeSelect(name: String, values: Array<String>, state: Int = 0) :
        Filter.Select<String>(name, values, state)
    private class FakeCheckBox(name: String, state: Boolean = false) : Filter.CheckBox(name, state)
    private class FakeTriState(name: String, state: Int = Filter.TriState.STATE_IGNORE) :
        Filter.TriState(name, state)
    private class FakeSort(name: String, values: Array<String>, state: Filter.Sort.Selection? = null) :
        Filter.Sort(name, values, state)
    private class FakeGroup(name: String, children: List<Filter<*>>) : Filter.Group<Filter<*>>(name, children)
    private class FakeStringGroup(name: String, children: List<String>) : Filter.Group<String>(name, children)
    private class FakeMixedGroup(name: String, children: List<Any>) : Filter.Group<Any>(name, children)

    // ── extraction ───────────────────────────────────────────────────────

    @Test
    fun `extracts every supported filter type`() {
        val state = FilterUiState(
            FilterList(
                Filter.Header("Header"),
                Filter.Separator(),
                FakeText("Text"),
                FakeSelect("Select", arrayOf("A", "B")),
                FakeCheckBox("CheckBox"),
                FakeTriState("TriState"),
                FakeSort("Sort", arrayOf("X", "Y")),
                FakeGroup("Group", listOf(FakeTriState("Nested"))),
            ),
        )

        assertEquals(
            listOf(
                FilterItem.Header::class,
                FilterItem.Separator::class,
                FilterItem.Text::class,
                FilterItem.Select::class,
                FilterItem.CheckBox::class,
                FilterItem.TriState::class,
                FilterItem.Sort::class,
                FilterItem.Group::class,
            ),
            state.items.map { it::class },
        )

        val group = state.items.last() as FilterItem.Group
        assertEquals(1, group.children.size)
        assertTrue(group.children.single() is FilterItem.TriState)
    }

    @Test
    fun `skips unknown filter subclasses`() {
        // A truly unknown top-level Filter subclass cannot be declared in this
        // module: Kotlin sealed classes emit a JVM permits clause + private
        // constructor, closing the hierarchy to source-api's module. Real
        // unknowns only arrive in extension jars compiled against a NEWER
        // source-api (e.g. a hypothetical Filter.Slider), which is exactly
        // what the else-branch in FilterUiState guards against. The closest
        // constructible case is a group carrying non-Filter children, which
        // exercises the same skip-and-log path.
        val state = FilterUiState(FilterList(FakeStringGroup("BadGroup", listOf("a", "b"))))

        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `skips group whose children are not filters`() {
        val state = FilterUiState(FilterList(FakeText("Text"), FakeStringGroup("BadGroup", listOf("a", "b"))))

        assertEquals(1, state.items.size)
        assertTrue(state.items.single() is FilterItem.Text)
    }

    @Test
    fun `group with mixed children keeps filters and drops junk`() {
        val checkBox = FakeCheckBox("Keep")
        val text = FakeText("AlsoKeep")
        val state = FilterUiState(
            FilterList(FakeMixedGroup("Mixed", listOf(checkBox, "junk", text))),
        )

        val group = state.items.single() as FilterItem.Group
        assertEquals(2, group.children.size)
        assertTrue(group.children[0] is FilterItem.CheckBox)
        assertTrue(group.children[1] is FilterItem.Text)

        // Extracted children keep full semantics: defaults tracked, counted.
        checkBox.state = true
        assertEquals(1, state.activeCount())
    }

    @Test
    fun `extracts nested groups recursively`() {
        val inner = FakeGroup("Inner", listOf(FakeCheckBox("Leaf")))
        val outer = FakeGroup("Outer", listOf(inner, FakeText("Sibling")))

        val state = FilterUiState(FilterList(outer))

        val outerItem = state.items.single() as FilterItem.Group
        assertEquals(2, outerItem.children.size)
        val innerItem = outerItem.children.first() as FilterItem.Group
        assertTrue(innerItem.children.single() is FilterItem.CheckBox)
        assertTrue(outerItem.children[1] is FilterItem.Text)
    }

    // ── active count ─────────────────────────────────────────────────────

    @Test
    fun `active count is zero while states match defaults`() {
        // Defaults are whatever the source shipped — including non-zero ones.
        val state = FilterUiState(
            FilterList(
                FakeText("Text", "preset"),
                FakeSelect("Select", arrayOf("A", "B"), state = 1),
                FakeCheckBox("CheckBox", state = true),
                FakeTriState("TriState", Filter.TriState.STATE_INCLUDE),
                FakeSort("Sort", arrayOf("X"), Filter.Sort.Selection(0, ascending = false)),
                FakeGroup("Group", listOf(FakeTriState("Nested", Filter.TriState.STATE_EXCLUDE))),
            ),
        )

        assertEquals(0, state.activeCount())
        assertFalse(state.hasActiveFilters())
    }

    @Test
    fun `active count counts each changed filter type`() {
        val text = FakeText("Text")
        val select = FakeSelect("Select", arrayOf("A", "B"))
        val checkBox = FakeCheckBox("CheckBox")
        val triState = FakeTriState("TriState")
        val sort = FakeSort("Sort", arrayOf("X", "Y"))
        val nested = FakeTriState("Nested")
        val state = FilterUiState(
            FilterList(text, select, checkBox, triState, sort, FakeGroup("Group", listOf(nested))),
        )

        text.state = "query"
        select.state = 1
        checkBox.state = true
        triState.state = Filter.TriState.STATE_INCLUDE
        sort.state = Filter.Sort.Selection(1, ascending = true)
        nested.state = Filter.TriState.STATE_EXCLUDE

        assertEquals(6, state.activeCount())
        assertTrue(state.hasActiveFilters())
    }

    @Test
    fun `headers and separators are never counted`() {
        val header = Filter.Header("Header")
        val separator = Filter.Separator()
        val state = FilterUiState(FilterList(header, separator))

        assertEquals(0, state.activeCount())
    }

    @Test
    fun `duplicate filter instance is counted once`() {
        val triState = FakeTriState("TriState")
        val state = FilterUiState(FilterList(triState, triState))

        triState.state = Filter.TriState.STATE_INCLUDE

        assertEquals(1, state.activeCount())
    }

    @Test
    fun `distinct instances with same name are counted separately`() {
        // Filter.equals is value-based (name + state): a plain map would
        // collapse these two entries — the IdentityHashMap is load-bearing.
        val a = FakeCheckBox("Same")
        val b = FakeCheckBox("Same")
        val state = FilterUiState(FilterList(a, b))

        a.state = true
        b.state = true

        assertEquals(2, state.activeCount())
    }

    @Test
    fun `reselecting default select index toggles back to inactive`() {
        val select = FakeSelect("Select", arrayOf("A", "B"), state = 0)
        val state = FilterUiState(FilterList(select))

        select.state = 1
        assertEquals(1, state.activeCount())

        // Toggle back to the default without reset(): value equals the
        // snapshotted default again.
        select.state = 0
        assertEquals(0, state.activeCount())
    }

    @Test
    fun `sort ascending toggled twice returns to inactive`() {
        val sort = FakeSort("Sort", arrayOf("X", "Y"), Filter.Sort.Selection(0, ascending = true))
        val state = FilterUiState(FilterList(sort))

        sort.state = sort.state!!.copy(ascending = false)
        assertEquals(1, state.activeCount())

        sort.state = sort.state!!.copy(ascending = true)
        assertEquals(0, state.activeCount())
    }

    // ── reset ────────────────────────────────────────────────────────────

    @Test
    fun `reset restores source defaults including non-zero ones`() {
        val text = FakeText("Text", "preset")
        val select = FakeSelect("Select", arrayOf("A", "B"), state = 1)
        val triState = FakeTriState("TriState", Filter.TriState.STATE_EXCLUDE)
        val sort = FakeSort("Sort", arrayOf("X", "Y"), Filter.Sort.Selection(1, ascending = false))
        val nested = FakeTriState("Nested")
        val state = FilterUiState(FilterList(text, select, triState, sort, FakeGroup("Group", listOf(nested))))

        text.state = "changed"
        select.state = 0
        triState.state = Filter.TriState.STATE_IGNORE
        sort.state = null
        nested.state = Filter.TriState.STATE_INCLUDE
        assertEquals(5, state.activeCount())

        state.reset()

        assertEquals("preset", text.state)
        assertEquals(1, select.state)
        assertEquals(Filter.TriState.STATE_EXCLUDE, triState.state)
        assertEquals(Filter.Sort.Selection(1, ascending = false), sort.state)
        assertEquals(Filter.TriState.STATE_IGNORE, nested.state)
        assertEquals(0, state.activeCount())
    }

    // ── live list semantics ──────────────────────────────────────────────

    @Test
    fun `filter list holds the live instances handed to getSearchManga`() {
        val text = FakeText("Text")
        val state = FilterUiState(FilterList(text))

        text.state = "query"

        assertSame(text, state.filterList.single())
        assertEquals("query", state.filterList.single().state)
    }
}
