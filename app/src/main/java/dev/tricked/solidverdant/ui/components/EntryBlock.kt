/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.SolidVerdantTheme
import dev.tricked.solidverdant.ui.theme.readableOn

/**
 * A single calendar entry card: a grey card
 * (surfaceContainerHighest) with [MaterialTheme.shapes.small] corners, the
 * title in onSurface and the project line in the entry [color], blended until
 * it stays legible on the card. Use this everywhere an entry is rendered so the
 * per-view block renderers stay consistent.
 *
 * @param color    entry accent colour (e.g. its project colour), used for the subtitle.
 * @param title    entry title; null/blank falls back to the shared
 *                 "Untitled entry" string.
 * @param subtitle optional secondary line (e.g. "Project - Task") in [color].
 * @param time     optional trailing text (e.g. formatted duration or time range).
 * @param minHeight minimum block height; defaults to [Dimens.EntryMinHeight].
 */
@Composable
fun EntryBlock(
    color: Color,
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    time: String? = null,
    minHeight: Dp = Dimens.EntryMinHeight,
    syncStatus: EntrySyncStatus? = null,
) {
    val resolvedTitle = title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.uikit_untitled_entry)
    val cardColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val subtitleColor = remember(color, cardColor, onSurface) { color.readableOn(background = cardColor, towards = onSurface) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(MaterialTheme.shapes.small)
            .background(cardColor)
            .padding(
                horizontal = Dimens.EntryPaddingHorizontal,
                vertical = Dimens.EntryPaddingVertical,
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = resolvedTitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!time.isNullOrBlank()) {
                    Spacer(Modifier.width(Dimens.EntryBarGap))
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                syncStatus?.let { status ->
                    Spacer(Modifier.width(Dimens.EntryBarGap))
                    SyncChip(status = status, showLabel = false)
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                    // The calendar supplies a finite card height. Let metadata use every line
                    // that fits inside that height, then rely on the card clip for short slots.
                    // A fixed line cap wastes most of tall week-view entries.
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Preview
@Composable
fun EntryBlockPreview() {
    SolidVerdantTheme {
        EntryBlock(
            color = MaterialTheme.colorScheme.primary,
            title = "Design review",
            subtitle = "Acme - UI kit",
            time = "1h 05m",
        )
    }
}
