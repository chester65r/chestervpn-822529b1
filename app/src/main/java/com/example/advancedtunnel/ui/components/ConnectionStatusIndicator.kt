package com.example.advancedtunnel.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.advancedtunnel.model.TunnelStatus
import com.example.advancedtunnel.ui.theme.AccentOrange
import com.example.advancedtunnel.ui.theme.BorderDark
import com.example.advancedtunnel.ui.theme.CardBackground
import com.example.advancedtunnel.ui.theme.CyanPrimary
import com.example.advancedtunnel.ui.theme.DestructiveRed
import com.example.advancedtunnel.ui.theme.StarlinkMonoFontFamily

@Composable
fun ConnectionStatusIndicator(
    status: TunnelStatus,
    modifier: Modifier = Modifier
) {
    val indicatorColor by animateColorAsState(
        targetValue = when (status) {
            TunnelStatus.CONNECTED -> CyanPrimary
            TunnelStatus.CONNECTING -> AccentOrange
            TunnelStatus.DISCONNECTED -> DestructiveRed
        },
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "starlinkStatusColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "starlinkCapsulePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CardBackground)
            .border(
                width = 1.dp,
                color = if (status == TunnelStatus.CONNECTED) CyanPrimary.copy(alpha = 0.5f) else BorderDark,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("connection_status_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier.size(12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (status != TunnelStatus.DISCONNECTED) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(indicatorColor.copy(alpha = pulseAlpha))
                )
            }

            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
        }

        Crossfade(
            targetState = status,
            animationSpec = tween(250, easing = FastOutSlowInEasing),
            label = "statusCrossfade"
        ) { currentStatus ->
            val labelText = when (currentStatus) {
                TunnelStatus.CONNECTED -> "ONLINE"
                TunnelStatus.CONNECTING -> "SEARCHING"
                TunnelStatus.DISCONNECTED -> "OFFLINE"
            }
            Text(
                text = labelText,
                fontFamily = StarlinkMonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = indicatorColor,
                letterSpacing = 1.8.sp
            )
        }
    }
}
