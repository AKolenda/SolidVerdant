/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.tricked.solidverdant.ui.theme.Dimens
import dev.tricked.solidverdant.ui.theme.segmentedThumb
import dev.tricked.solidverdant.ui.theme.segmentedTrack

/**
 * iOS-style segmented control: equal-width segments on a rounded track with a raised thumb that
 * slides to [selected]. Each segment is a [Role.Tab] in a selectable group, and its whole
 * [Dimens.MinTouchTarget]-tall column is tappable even though the visible track is shorter.
 * Tapping the selected segment calls [onSelect] again so callers can reopen a picker behind it.
 * [selected] may be absent from [options]; then no thumb is drawn.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    optionTestTag: (T) -> String? = { null },
) {
    val selectedIndex = options.indexOf(selected)
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget)) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(segmentWidth * selectedIndex.coerceAtLeast(0), label = "segmentThumb")
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(Dimens.SegmentedTrackHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.segmentedTrack),
        )
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffset)
                    .width(segmentWidth)
                    .height(Dimens.SegmentedTrackHeight)
                    .padding(Dimens.SegmentedThumbInset)
                    .shadow(Dimens.SegmentedThumbShadow, CircleShape)
                    .background(MaterialTheme.colorScheme.segmentedThumb, CircleShape),
            )
        }
        Row(Modifier.matchParentSize().selectableGroup()) {
            options.forEach { option ->
                val isSelected = option == selected
                val tag = optionTestTag(option)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(option) },
                        )
                        .then(if (tag != null) Modifier.testTag(tag) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = Dimens.Space4),
                    )
                }
            }
        }
    }
}
