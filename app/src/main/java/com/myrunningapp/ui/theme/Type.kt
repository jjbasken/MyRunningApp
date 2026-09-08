package com.myrunningapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val default = Typography()

/**
 * Mostly the Material 3 defaults, with oversized, tabular-friendly display
 * styles for the big live numbers on the run screen.
 */
val AppTypography = default.copy(
    displayLarge = default.displayLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 64.sp,
    ),
    displayMedium = default.displayMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
    ),
    labelLarge = default.labelLarge.copy(
        letterSpacing = 0.5.sp,
    ),
)

val StatNumberStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
)
