package com.example.advancedtunnel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BackgroundDark
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.StarlinkMonoFontFamily
import com.example.advancedtunnel.ui.theme.TextMuted

@Composable
fun SpeedGraphCanvas(
    downHistory: List<Double>,
    upHistory: List<Double>,
    active: Boolean,
    modifier: Modifier = Modifier,
    heightDp: Dp = 88.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .clip(RoundedCornerShape(8.dp))
            .background(BackgroundDark)
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Ultra-minimalist single horizon baseline (zero heavy gridline clutter)
            val baselineY = height - 6.dp.toPx()
            drawLine(
                color = BorderDark.copy(alpha = 0.65f),
                start = Offset(0f, baselineY),
                end = Offset(width, baselineY),
                strokeWidth = 1.dp.toPx()
            )

            val maxDown = downHistory.maxOrNull() ?: 1.0
            val maxUp = upHistory.maxOrNull() ?: 1.0
            val maxVal = maxOf(maxDown, maxUp, 64_000.0)

            val count = downHistory.size
            if (count < 2) return@Canvas

            val stepX = width / (count - 1).toFloat()

            // Downlink Waveform (Starlink Cyan #38BDF8)
            val downPath = Path()
            val downFillPath = Path()
            downHistory.forEachIndexed { index, value ->
                val x = index * stepX
                val normalized = if (active) (value / maxVal).coerceIn(0.0, 1.0).toFloat() else 0.02f
                val y = baselineY - (normalized * (height - 18.dp.toPx()))

                if (index == 0) {
                    downPath.moveTo(x, y)
                    downFillPath.moveTo(x, baselineY)
                    downFillPath.lineTo(x, y)
                } else {
                    downPath.lineTo(x, y)
                    downFillPath.lineTo(x, y)
                }
            }
            downFillPath.lineTo(width, baselineY)
            downFillPath.close()

            if (active) {
                drawPath(
                    path = downFillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(CyanPrimary.copy(alpha = 0.22f), Color.Transparent),
                        startY = 0f,
                        endY = baselineY
                    )
                )
            }

            drawPath(
                path = downPath,
                color = if (active) CyanPrimary else BorderDark,
                style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            )

            // Uplink Waveform (Aerospace Amber #F59E0B)
            if (active && upHistory.size >= 2) {
                val upPath = Path()
                upHistory.forEachIndexed { index, value ->
                    val x = index * stepX
                    val normalized = (value / maxVal).coerceIn(0.0, 1.0).toFloat()
                    val y = baselineY - (normalized * (height - 18.dp.toPx()))
                    if (index == 0) upPath.moveTo(x, y) else upPath.lineTo(x, y)
                }
                drawPath(
                    path = upPath,
                    color = AccentOrange.copy(alpha = 0.85f),
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        if (!active) {
            Text(
                text = "TELEMETRY STANDBY • 0.00 MBPS",
                color = TextMuted,
                fontSize = 10.sp,
                fontFamily = StarlinkMonoFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
