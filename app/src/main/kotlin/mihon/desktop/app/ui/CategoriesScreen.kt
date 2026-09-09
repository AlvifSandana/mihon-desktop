package mihon.desktop.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mihon.desktop.loader.library.CategoryRepository
import mihon.desktop.loader.library.SelectAllWithCounts
import mihon.desktop.app.i18n.Strings
import mihon.desktop.app.i18n.t

/**
 * Manages categories: create, rename, delete, reorder. Manga are assigned to
 * categories from [MangaDetailScreen]; deleting a category here only removes
 * the assignments, never the manga.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val repository = remember { CategoryRepository() }
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf(listOf<SelectAllWithCounts>()) }
    var loaded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SelectAllWithCounts?>(null) }
    var deleteTarget by remember { mutableStateOf<SelectAllWithCounts?>(null) }

    fun reload() {
        scope.launch {
            categories = repository.allWithCounts()
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    /** Swaps [index] with its neighbor and persists the new full ordering. */
    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target < 0 || target >= categories.size) return
        val reordered = categories.toMutableList().apply {
            val item = removeAt(index)
            add(target, item)
        }
        categories = reordered
        scope.launch { repository.reorder(reordered.map { it.id }) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("categories_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common_back"))
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = t("action_add_category"))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                categories.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        t("categories_empty"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(categories, key = { _, c -> c.id }) { index, category ->
                        ListItem(
                            headlineContent = { Text(category.name) },
                            supportingContent = {
                                Text(
                                    if (category.mangaCount == 1L) t("category_count_1")
                                    else t("category_count_n", category.mangaCount),
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        onClick = { move(index, -1) },
                                        enabled = index > 0,
                                    ) {
                                        Icon(Icons.Filled.ArrowUpward, contentDescription = t("action_move_up"))
                                    }
                                    IconButton(
                                        onClick = { move(index, +1) },
                                        enabled = index < categories.size - 1,
                                    ) {
                                        Icon(Icons.Filled.ArrowDownward, contentDescription = t("action_move_down"))
                                    }
                                    IconButton(onClick = { renameTarget = category }) {
                                        Icon(Icons.Filled.Edit, contentDescription = t("action_rename"))
                                    }
                                    IconButton(onClick = { deleteTarget = category }) {
                                        Icon(Icons.Filled.Delete, contentDescription = t("common_delete"))
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CategoryNameDialog(
            title = t("categories_new_title"),
            initialName = "",
            confirmLabel = t("action_create"),
            errorHint = { name -> if (name.isBlank()) Strings.get("error_name_empty") else null },
            onDismiss = { showAddDialog = false },
            onConfirm = { name ->
                val created = repository.create(name)
                if (created != null) {
                    showAddDialog = false
                    reload()
                }
                // null (= blank or duplicate) keeps the dialog open; the
                // dialog shows its own duplicate-name error.
                created != null
            },
        )
    }

    renameTarget?.let { target ->
        CategoryNameDialog(
            title = t("categories_rename_title"),
            initialName = target.name,
            confirmLabel = t("action_rename"),
            errorHint = { name -> if (name.isBlank()) Strings.get("error_name_empty") else null },
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                val renamed = repository.rename(target.id, name)
                if (renamed) {
                    renameTarget = null
                    reload()
                }
                renamed
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("categories_delete_title")) },
            text = {
                Text(t("categories_delete_text", target.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.delete(target.id)
                        deleteTarget = null
                        reload()
                    }
                }) {
                    Text(t("common_delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(t("common_cancel"))
                }
            },
        )
    }
}

/**
 * Text-field dialog for category names. [onConfirm] runs in a coroutine and
 * returns whether the change succeeded -- false keeps the dialog open (blank or
 * duplicate name) so the user can fix the input.
 */
@Composable
private fun CategoryNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    errorHint: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Boolean,
) {
    var name by remember { mutableStateOf(initialName) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 200.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        error = null
                    },
                    label = { Text(t("field_name")) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { err ->
                        { Text(err, color = MaterialTheme.colorScheme.error) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    val hint = errorHint(name)
                    if (hint != null) {
                        error = hint
                        return@TextButton
                    }
                    scope.launch {
                        submitting = true
                        if (onConfirm(name)) {
                            // Dialog state is cleared by the caller.
                        } else {
                            error = Strings.get("error_category_exists")
                        }
                        submitting = false
                    }
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(enabled = !submitting, onClick = onDismiss) {
                Text(t("common_cancel"))
            }
        },
    )
}
