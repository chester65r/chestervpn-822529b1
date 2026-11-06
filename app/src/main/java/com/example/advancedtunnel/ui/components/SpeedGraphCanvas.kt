package com.example.advancedtunnel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.SurfaceDark
import com.example.advancedtunnel.ui.theme.TextMuted

@Composable
fun SpeedGraphCanvas(
    downHistory: List<Double>,
    upHistory: List<Double>,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Horizontal grid lines
            val gridLines = 4
            for (i in 1 until gridLines) {
                val y = height * (i.toFloat() / gridLines)
                drawLine(
                    color = BorderDark.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (!active || downHistory.isEmpty()) return@Canvas

            val maxDown = downHistory.maxOrNull() ?: 1.0
            val maxUp = upHistory.maxOrNull() ?: 1.0
            val maxVal = maxOf(maxDown, maxUp, 100_000.0)

            val count = downHistory.size
            if (count < 2) return@Canvas

            val stepX = width / (count - 1).toFloat()

            // Download Path (Cyan)
            val downPath = Path()
            val downFillPath = Path()
            downHistory.forEachIndexed { index, value ->
                val x = index * stepX
                val normalized = (value / maxVal).coerceIn(0.0, 1.0).toFloat()
                val y = height - (normalized * (height - 16f)) - 8f

                if (index == 0) {
                    downPath.moveTo(x, y)
                    downFillPath.moveTo(x, height)
                    downFillPath.lineTo(x, y)
                } else {
                    downPath.lineTo(x, y)
                    downFillPath.lineTo(x, y)
                }
            }
            downFillPath.lineTo(width, height)
            downFillPath.close()

            // Fill gradient under download curve
            drawPath(
                path = downFillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(CyanPrimary.copy(alpha = 0.25f), Color.Transparent),
                    startY = 0f,
                    endY = height
                )
            )

            // Stroke download curve
            drawPath(
                path = downPath,
                color = CyanPrimary,
                style = Stroke(width = 2.5f.dp.toPx())
            )

            // Upload Path (Orange)
            val upPath = Path()
            upHistory.forEachIndexed { index, value ->
                val x = index * stepX
                val normalized = (value / maxVal).coerceIn(0.0, 1.0).toFloat()
                val y = height - (normalized * (height - 16f)) - 8f

                if (index == 0) {
                    upPath.moveTo(x, y)
                } else {
                    upPath.lineTo(x, y)
                }
            }

            drawPath(
                path = upPath,
                color = AccentOrange,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        if (!active) {
            Text(
                text = "IDLE (NO TRAFFIC)",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
