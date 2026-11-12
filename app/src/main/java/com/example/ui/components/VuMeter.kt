package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Rose500
import com.example.ui.theme.Teal400

@Composable
fun VuMeter(
    level: Float, // 0.0 to 1.0
    threshold: Float, // 0.0 to 1.0 (VOX gate)
    isTransmitting: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 24
) {
    val animatedLevel = remember { Animatable(0f) }

    LaunchedEffect(level) {
        animatedLevel.animateTo(
            targetValue = level.coerceIn(0f, 1f),
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
        )
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val activeColor = if (isTransmitting) Emerald400 else primaryColor
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val gateMarkerColor = Rose500

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
        ) {
            val totalWidth = size.width
            val totalHeight = size.height
            val spacing = 3.dp.toPx()
            val totalSpacing = spacing * (barCount - 1)
            val barWidth = (totalWidth - totalSpacing) / barCount

            val currentLevel = animatedLevel.value

            for (i in 0 until barCount) {
                val barFraction = (i + 1).toFloat() / barCount
                val isActive = barFraction <= currentLevel

                val color = if (isActive) {
                    if (barFraction > 0.85f) Rose500 else activeColor
                } else {
                    inactiveColor
                }

                // Wave height curve
                val heightScale = if (isActive) 1f else 0.45f
                val h = totalHeight * heightScale
                val top = (totalHeight - h) / 2f
                val left = i * (barWidth + spacing)

                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
            }

            // Draw noise gate threshold line
            val thresholdX = threshold.coerceIn(0f, 1f) * totalWidth
            drawLine(
                color = gateMarkerColor,
                start = Offset(thresholdX, 0f),
                end = Offset(thresholdX, totalHeight),
                strokeWidth = 2.dp.toPx()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isTransmitting) "TRANSMITTING" else "MIC INPUT",
                style = MaterialTheme.typography.labelSmall,
                color = if (isTransmitting) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
            Text(
                text = "Gate: ${(threshold * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = Rose500,
                fontSize = 10.sp
            )
        }
    }
}
