/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Blend a user-chosen colour (usually a project colour) toward [towards] until it reaches
 * normal-text contrast on [background], so a dark project colour stays legible on a dark card.
 */
internal fun Color.readableOn(background: Color, towards: Color): Color {
    var candidate = this
    var fraction = 0f
    while (contrastRatio(candidate, background) < MIN_TEXT_CONTRAST && fraction < 1f) {
        fraction += CONTRAST_BLEND_STEP
        candidate = lerp(this, towards, fraction.coerceAtMost(1f))
    }
    return candidate
}

private fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance() + RELATIVE_LUMINANCE_FLARE
    val b = second.luminance() + RELATIVE_LUMINANCE_FLARE
    return maxOf(a, b) / minOf(a, b)
}

private const val MIN_TEXT_CONTRAST = 4.5f
private const val RELATIVE_LUMINANCE_FLARE = 0.05f
private const val CONTRAST_BLEND_STEP = 0.1f
