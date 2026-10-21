package com.firesky.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firesky.app.data.clamp
import com.firesky.app.ui.theme.DisplayFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val SCORE_STOPS = listOf(
    0.0 to Color(0xFF26376F),
    24.0 to Color(0xFF4B2F7F),
    42.0 to Color(0xFFC05A78),
    62.0 to Color(0xFFEE7A45),
    78.0 to Color(0xFFF6C85A),
    100.0 to Color(0xFFFF6A3D),
)

fun scoreSpectrumColor(value: Double): Color {
    val score = clamp(value)
    val upperIndex = SCORE_STOPS.indexOfFirst { score <= it.first }
    if (upperIndex <= 0) return SCORE_STOPS[0].second
    val (lowStop, lowColor) = SCORE_STOPS[upperIndex - 1]
    val (highStop, highColor) = SCORE_STOPS[upperIndex]
    val t = ((score - lowStop) / (highStop - lowStop)).toFloat()
    return Color(
        red = lowColor.red + (highColor.red - lowColor.red) * t,
        green = lowColor.green + (highColor.green - lowColor.green) * t,
        blue = lowColor.blue + (highColor.blue - lowColor.blue) * t,
        alpha = 1f,
    )
}

private data class RingParticle(
    val progress: Float,
    val lane: Float,
    val size: Float,
    val opacity: Float,
)

private val PARTICLES = listOf(
    RingParticle(12f, 0.22f, 1.2f, 0.48f),
    RingParticle(18f, 0.78f, 1.6f, 0.62f),
    RingParticle(26f, 0.44f, 1.1f, 0.52f),
    RingParticle(34f, 0.7f, 1.4f, 0.46f),
    RingParticle(43f, 0.3f, 1.7f, 0.58f),
    RingParticle(51f, 0.62f, 1.2f, 0.5f),
    RingParticle(59f, 0.18f, 1.5f, 0.66f),
    RingParticle(67f, 0.84f, 1.1f, 0.5f),
    RingParticle(76f, 0.5f, 1.7f, 0.7f),
    RingParticle(84f, 0.28f, 1.3f, 0.56f),
    RingParticle(91f, 0.72f, 1.6f, 0.64f),
)

@Composable
fun ScoreRing(
    value: Double,
    label: String,
    tone: String,
    modifier: Modifier = Modifier,
) {
    val target = clamp(value).toFloat()
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target) {
        animated.snapTo(0f)
        animated.animateTo(
            target,
            animationSpec = tween(950, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
        )
    }
    val current = animated.value
    val accent = scoreSpectrumColor(current.toDouble())

    val spokenScore = "${target.roundToInt()} percent, $label"
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1.56f)
            // Read the final score once instead of the animating digits and the separate "%" glyph.
            .clearAndSetSemantics { contentDescription = spokenScore },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val ringWidth = maxWidth
        val valueSize = (ringWidth.value * 0.165f).coerceIn(22f, 46f).sp
        val percentSize = (ringWidth.value * 0.068f).coerceIn(10f, 18f).sp
        val labelSize = (ringWidth.value * 0.062f).coerceIn(10.5f, 14.5f).sp

        Canvas(Modifier.fillMaxSize()) {
            val scale = min(size.width / 320f, size.height / 206f)
            val offsetX = (size.width - 320f * scale) / 2f
            val offsetY = (size.height - 206f * scale) / 2f
            fun point(x: Float, y: Float) = Offset(offsetX + x * scale, offsetY + y * scale)

            val cx = 160f
            val cy = 178f
            val radius = 134f
            val strokeWidth = 30f * scale
            val topLeft = point(cx - radius, cy - radius)
            val arcSize = Size(2f * radius * scale, 2f * radius * scale)
            val gradient = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF4C1D95),
                    Color(0xFFE11D48),
                    Color(0xFFF97316),
                    Color(0xFFFACC15),
                ),
                startX = point(cx - radius, 0f).x,
                endX = point(cx + radius, 0f).x,
            )

            drawArc(
                color = Color(0x8C1E293B),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            if (current > 0f) {
                val sweep = 180f * (current / 100f)
                drawArc(
                    brush = gradient,
                    startAngle = 180f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round),
                    alpha = 0.22f,
                )
                drawArc(
                    brush = gradient,
                    startAngle = 180f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }

            PARTICLES.forEach { particle ->
                if (current >= particle.progress) {
                    val angle = (180f - particle.progress * 1.8f) * (PI / 180.0).toFloat()
                    val particleRadius = radius - 30f / 2f + particle.lane * 30f
                    drawCircle(
                        color = Color.White.copy(alpha = particle.opacity),
                        radius = particle.size * scale,
                        center = point(
                            cx + particleRadius * cos(angle),
                            cy - particleRadius * sin(angle),
                        ),
                    )
                }
            }

            val angle = (180f - current * 1.8f) * (PI / 180.0).toFloat()
            val outerRadius = radius + 30f / 2f + 9f
            val innerRadius = radius - 30f / 2f - 5f
            drawLine(
                color = Color.White.copy(alpha = 0.94f),
                start = point(cx + innerRadius * cos(angle), cy - innerRadius * sin(angle)),
                end = point(cx + outerRadius * cos(angle), cy - outerRadius * sin(angle)),
                strokeWidth = 3.5f * scale,
                cap = StrokeCap.Round,
            )
        }

        Column(
            modifier = Modifier.offset(y = (-(ringWidth.value * 0.03f)).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "${current.roundToInt()}",
                    color = Color.White.copy(alpha = 0.97f),
                    fontFamily = DisplayFamily,
                    fontSize = valueSize,
                    fontWeight = FontWeight.W500,
                    lineHeight = valueSize,
                )
                Text(
                    text = "%",
                    color = Color.White.copy(alpha = 0.52f),
                    fontFamily = DisplayFamily,
                    fontSize = percentSize,
                    fontWeight = FontWeight.W500,
                    modifier = Modifier.offset(y = (percentSize.value * 0.15f).dp),
                )
            }
            Text(
                text = label,
                color = lerp(accent, Color.White, 0.52f),
                fontFamily = DisplayFamily,
                fontSize = labelSize,
                fontWeight = FontWeight.W700,
                maxLines = 1,
            )
        }
    }
}

/** Compact, static score gauge for the horizontally scrolling daily outlook. */
@Composable
fun MiniScoreGauge(value: Double, modifier: Modifier = Modifier) {
    val score = clamp(value).toFloat()
    androidx.compose.foundation.layout.Box(
        modifier.clearAndSetSemantics { contentDescription = "${score.roundToInt()} percent" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val diameter = min(size.width - stroke * 2, (size.height - stroke) * 2)
            val topLeft = Offset((size.width - diameter) / 2, stroke)
            val arcSize = Size(diameter, diameter)
            val style = Stroke(stroke, cap = StrokeCap.Round)
            drawArc(Color(0x661E293B), 180f, 180f, false, topLeft, arcSize, style = style)
            if (score > 0f) {
                drawArc(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF4C1D95), Color(0xFFE11D48), Color(0xFFF97316), Color(0xFFFACC15)),
                        startX = topLeft.x, endX = topLeft.x + diameter,
                    ),
                    180f, 180f * score / 100f, false, topLeft, arcSize, style = style,
                )
            }
        }
        Text("${score.roundToInt()}%", color = Color.White, fontFamily = DisplayFamily,
            fontSize = 15.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600,
            modifier = Modifier.offset(y = (-2).dp))
    }
}
