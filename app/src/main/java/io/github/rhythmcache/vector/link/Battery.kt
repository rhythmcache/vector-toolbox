package io.github.rhythmcache.vector.link

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.ui.unit.dp

@Composable
fun BatteryGauge(percent: Int, modifier: Modifier = Modifier) {
    val level by animateFloatAsState(percent.coerceIn(0, 100) / 100f, tween(800), label = "level")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase",
    )
    val fluid = when {
        percent <= 15 -> Color(0xFFE53935)
        percent <= 30 -> Color(0xFFFFB300)
        else -> Color(0xFF2ECC71)
    }
    val outline = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val capH = size.height * 0.05f
            val capW = size.width * 0.38f
            val bodyTop = capH
            val bodyH = size.height - capH
            val corner = CornerRadius(size.width * 0.16f)
            val stroke = 4.dp.toPx()

            // terminal cap
            drawRoundRect(
                color = outline,
                topLeft = Offset((size.width - capW) / 2f, 0f),
                size = Size(capW, capH + stroke),
                cornerRadius = CornerRadius(6f),
            )

            val body = RoundRect(Rect(0f, bodyTop, size.width, size.height), corner)
            clipPath(Path().apply { addRoundRect(body) }) {
                drawRect(track)
                val fillH = bodyH * level
                val top = size.height - fillH
                val amp = if (level in 0.02f..0.98f) 7.dp.toPx() else 0f
                val wave = Path().apply {
                    moveTo(0f, top)
                    var x = 0f
                    while (x <= size.width) {
                        val y = top + sin(x / size.width * 2f * PI.toFloat() * 1.4f + phase) * amp
                        lineTo(x, y)
                        x += 4f
                    }
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(wave, fluid)
            }
            drawRoundRect(
                color = outline,
                topLeft = Offset(0f, bodyTop),
                size = Size(size.width, bodyH),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
        }
        Text(
            "$percent%",
            style = TextStyle(
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
            ),
        )
    }
}