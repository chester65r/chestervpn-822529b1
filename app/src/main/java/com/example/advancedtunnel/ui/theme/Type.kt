package com.example.advancedtunnel.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.R

val StarlinkMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

val StarlinkSansFontFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold)
)

val StarlinkTypography = Typography(
    // Primary Screen Titles (22sp - 26sp, Tightly tracked -0.5.sp)
    headlineMedium = TextStyle(
        fontFamily = StarlinkSansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        color = TextPrimary
    ),
    headlineSmall = TextStyle(
        fontFamily = StarlinkSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.5).sp,
        color = TextPrimary
    ),
    // Primary Telemetry Readouts (32sp Strict Monospace)
    displaySmall = TextStyle(
        fontFamily = StarlinkMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
        color = TextPrimary
    ),
    // Card & Node Titles
    titleMedium = TextStyle(
        fontFamily = StarlinkSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.2).sp,
        color = TextPrimary
    ),
    // Section Headers & Categories (Monospace, Uppercase, Bold, 11sp-12sp, 1.5sp-2.0sp tracking)
    labelMedium = TextStyle(
        fontFamily = StarlinkMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.8.sp,
        color = TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = StarlinkMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.5.sp,
        color = TextSecondary
    ),
    // Body Copy
    bodyMedium = TextStyle(
        fontFamily = StarlinkSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = TextSecondary
    ),
    bodySmall = TextStyle(
        fontFamily = StarlinkMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = TextSecondary
    )
)
