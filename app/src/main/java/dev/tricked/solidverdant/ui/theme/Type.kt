/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.tricked.solidverdant.R

@OptIn(ExperimentalTextApi::class)
private fun interWeight(weight: FontWeight) = Font(
    resId = R.font.inter_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Inter stands in for the iOS system font; one variable file supplies every weight. */
val InterFontFamily = FontFamily(
    interWeight(FontWeight.Normal),
    interWeight(FontWeight.Medium),
    interWeight(FontWeight.SemiBold),
    interWeight(FontWeight.Bold),
)

private fun style(size: Int, lineHeight: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em,
)

/**
 * Type scale mapped from the iOS text styles (Large Title 34, Title 28/22/20, Headline 17,
 * Body 17, Subhead 15, Footnote 13, Caption 12/11), tightened slightly for Android's denser dp.
 */
val ZenTypography = Typography(
    displayLarge = style(57, 64, FontWeight.Bold, -0.02),
    displayMedium = style(45, 52, FontWeight.Bold, -0.02),
    displaySmall = style(36, 44, FontWeight.Bold, -0.02),
    headlineLarge = style(34, 41, FontWeight.Bold, -0.02),
    headlineMedium = style(28, 34, FontWeight.Bold, -0.015),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.01),
    titleLarge = style(20, 25, FontWeight.SemiBold, -0.01),
    titleMedium = style(17, 22, FontWeight.SemiBold, -0.01),
    titleSmall = style(15, 20, FontWeight.SemiBold, -0.005),
    bodyLarge = style(16, 22, FontWeight.Normal, -0.01),
    bodyMedium = style(15, 20, FontWeight.Normal, -0.005),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.Medium, -0.005),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(11, 13, FontWeight.Medium, 0.005),
)

/** Tabular figures so running timers and duration columns keep a fixed width. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** iOS-like rounding: chips 6, calendar blocks 8, grouped cells 12, cards 14, sheets 22. */
val ZenShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.RadiusXs),
    small = RoundedCornerShape(Dimens.CornerRadius),
    medium = RoundedCornerShape(Dimens.RadiusMd),
    large = RoundedCornerShape(Dimens.RadiusLg),
    extraLarge = RoundedCornerShape(Dimens.RadiusXl),
)
