/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import dev.tricked.solidverdant.ui.components.GroupedDivider
import dev.tricked.solidverdant.ui.components.GroupedSection
import dev.tricked.solidverdant.ui.theme.Dimens

/** Where a row sits in a grouped section built from separate lazy items; decides its corners. */
internal enum class GroupedPosition {
    Single,
    First,
    Middle,
    Last,
    ;

    val hasDividerBelow: Boolean get() = this == First || this == Middle

    companion object {
        fun of(index: Int, count: Int): GroupedPosition = when {
            count <= 1 -> Single
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

/**
 * One row of a [GroupedSection] for lazy lists: the same inset rounded surface, drawn per item so a
 * section of hundreds of rows composes only the visible ones. The first and last rows carry the
 * section's rounded corners, and every row but the last ends with a [GroupedDivider] at
 * [dividerInset].
 */
@Composable
internal fun LazyGroupedRow(
    position: GroupedPosition,
    modifier: Modifier = Modifier,
    dividerInset: Dp = Dimens.Space16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rounded = MaterialTheme.shapes.medium
    val square = CornerSize(0)
    val shape = when (position) {
        GroupedPosition.Single -> rounded
        GroupedPosition.First -> rounded.copy(bottomStart = square, bottomEnd = square)
        GroupedPosition.Middle -> RectangleShape
        GroupedPosition.Last -> rounded.copy(topStart = square, topEnd = square)
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.Space16),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            content()
            if (position.hasDividerBelow) GroupedDivider(inset = dividerInset)
        }
    }
}
