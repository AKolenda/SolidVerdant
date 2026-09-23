/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.ui.theme.Dimens

/** Searchable multi-selection for the entry forms. */
@Composable
fun TagsSelector(
    selectedTagIds: List<String>,
    availableTags: List<Tag>,
    onTagsChanged: (List<String>) -> Unit,
    enabled: Boolean,
    onCreateTag: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(enabled) {
        if (!enabled) {
            expanded = false
            query = ""
        }
    }
    val selectedName = remember(selectedTagIds, availableTags) {
        availableTags.firstOrNull { it.id == selectedTagIds.singleOrNull() }?.name?.takeIf(String::isNotBlank)
    }

    SearchableSelectorField(
        value = when (selectedTagIds.size) {
            0 -> stringResource(R.string.no_tags)
            1 -> selectedName ?: pluralStringResource(R.plurals.selected_tags, 1, 1)
            else -> pluralStringResource(R.plurals.selected_tags, selectedTagIds.size, selectedTagIds.size)
        },
        label = stringResource(R.string.tags),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        testTag = EditTimeEntryTestTags.TAGS_SELECTOR,
    )

    if (expanded && enabled) {
        val normalizedQuery = query.trim()
        val filteredTags = remember(availableTags, normalizedQuery) {
            if (normalizedQuery.isBlank()) {
                availableTags
            } else {
                availableTags.filter { it.name.contains(normalizedQuery, ignoreCase = true) }
            }
        }
        PickerDialog(
            title = stringResource(R.string.tags),
            searchPlaceholder = stringResource(R.string.search_tags),
            searchQuery = query,
            onSearchQueryChange = { query = it },
            onClose = {
                expanded = false
                query = ""
            },
            listTestTag = EditTimeEntryTestTags.TAGS_LIST,
            searchTestTag = EditTimeEntryTestTags.TAGS_SEARCH,
            closeTestTag = EditTimeEntryTestTags.TAGS_CLOSE,
        ) {
            if (selectedTagIds.isNotEmpty()) {
                item(key = "clear_tags") {
                    PickerItem(
                        text = stringResource(R.string.clear_tags),
                        selected = false,
                        onClick = { onTagsChanged(emptyList()) },
                        modifier = Modifier.testTag(EditTimeEntryTestTags.TAGS_CLEAR),
                    )
                }
            }
            items(filteredTags, key = { it.id }) { tag ->
                PickerItem(
                    text = tag.name,
                    selected = tag.id in selectedTagIds,
                    onClick = {
                        onTagsChanged(
                            if (tag.id in selectedTagIds) {
                                selectedTagIds - tag.id
                            } else {
                                selectedTagIds + tag.id
                            },
                        )
                    },
                    modifier = Modifier.testTag(EditTimeEntryTestTags.tagChip(tag.id)),
                )
            }
            if (filteredTags.isEmpty()) {
                item(key = "empty_tags") {
                    Text(
                        text = stringResource(
                            if (normalizedQuery.isBlank()) R.string.no_tags_available else R.string.no_results_found,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Dimens.Space24, vertical = Dimens.Space16),
                    )
                }
            }
            onCreateTag?.let { createTag ->
                item(key = "create_tag") {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.create_tag)) },
                        onClick = {
                            createTag(normalizedQuery)
                            expanded = false
                            query = ""
                        },
                        modifier = Modifier.testTag(EditTimeEntryTestTags.CREATE_TAG),
                    )
                }
            }
        }
    }
}
