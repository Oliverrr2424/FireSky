package com.firesky.app.ui.icons

import android.graphics.Path as NativePath
import android.graphics.PathMeasure as NativePathMeasure
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.min

/**
 * A signal a button hands to the icons inside it, so tapping anywhere on the button, not just on the
 * icon itself, plays the icons' animations.
 */
@Stable
class IconPulse {
    var tick by mutableIntStateOf(0)
        private set

    fun fire() {
        tick++
    }
}

@Composable
fun rememberIconPulse(): IconPulse = remember { IconPulse() }

/**
 * Fires [pulse] when a finger lands and lifts without scrolling away. It only watches: the touch still
 * reaches the button underneath, so taps keep doing what they did.
 */
fun Modifier.tapPulse(pulse: IconPulse): Modifier = pointerInput(pulse) { watchTaps { pulse.fire() } }

private suspend fun PointerInputScope.watchTaps(onTap: () -> Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if ((change.position - down.position).getDistance() > slop) moved = true
            if (!change.pressed) {
                if (!moved) onTap()
                break
            }
        }
    }
}

/** An icon that plays its own animation when tapped: see [IconMotions] for what each one does. */
@Composable
fun AnimatedIcon(
    @DrawableRes resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    pulse: IconPulse? = null,
    playOnTap: Boolean = true,
    playKey: Any? = null,
    playOnAppear: Boolean = false,
) {
    AnimatedIcon(
        vector = ImageVector.vectorResource(resId),
        motion = IconMotions.forRes(resId),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
        pulse = pulse,
        playOnTap = playOnTap,
        playKey = playKey,
        playOnAppear = playOnAppear,
    )
}

/**
 * [pulse] plays it from outside (a button around the icon), [playOnTap] from a tap on the icon, and a
 * change of [playKey] plays it too, for icons whose meaning changes, like a tab becoming selected.
 * An icon swapped in by a state change (a cross-fade) sets [playOnAppear] to play as it arrives.
 */
@Composable
fun AnimatedIcon(
    vector: ImageVector,
    motion: IconMotion,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    pulse: IconPulse? = null,
    playOnTap: Boolean = true,
    playKey: Any? = null,
    playOnAppear: Boolean = false,
) {
    val prepared = remember(vector) { PreparedIcon.of(vector) }
    val progress = remember { Animatable(1f) }
    var ownTaps by remember { mutableIntStateOf(0) }
    val trigger = ownTaps + (pulse?.tick ?: 0)
    var composedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(trigger, playKey) {
        // The first run is just the icon appearing; only later changes are taps.
        if (!composedOnce) {
            composedOnce = true
            if (!playOnAppear) return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(motion.durationMs, easing = LinearEasing))
    }

    var semanticsModifier: Modifier = Modifier
    if (contentDescription != null) {
        semanticsModifier = Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
    Canvas(
        modifier = modifier
            .then(semanticsModifier)
            .then(if (playOnTap) Modifier.pointerInput(Unit) { watchTaps { ownTaps++ } } else Modifier),
    ) {
        drawIcon(prepared, motion, progress.value, tint)
    }
}

/** An icon's strokes, parsed once: the path, how to paint it, and what is needed to draw it partway. */
private class PreparedPath(vector: VectorPath) {
    val path: Path = vector.pathData.toPath()
    val strokeWidth: Float = vector.strokeLineWidth
    val hasStroke: Boolean = vector.stroke != null
    val hasFill: Boolean = vector.fill != null
    val cap: StrokeCap = vector.strokeLineCap
    val join: StrokeJoin = vector.strokeLineJoin
    val miter: Float = vector.strokeLineMiter

    private val nativePath: NativePath = path.asAndroidPath()
    private val scratchNative = NativePath()
    private val scratch: Path = scratchNative.asComposePath()
    private val totalLength: Float = run {
        val measure = NativePathMeasure(nativePath, false)
        var total = 0f
        do {
            total += measure.length
        } while (measure.nextContour())
        total
    }

    /** The part of the stroke between two fractions of its length, across all of its sub-paths. */
    fun segment(startFraction: Float, endFraction: Float): Path {
        scratchNative.reset()
        val from = startFraction.coerceIn(0f, 1f) * totalLength
        val to = endFraction.coerceIn(0f, 1f) * totalLength
        if (to <= from) return scratch
        val measure = NativePathMeasure(nativePath, false)
        var offset = 0f
        do {
            val length = measure.length
            val start = (from - offset).coerceIn(0f, length)
            val end = (to - offset).coerceIn(0f, length)
            if (end > start) measure.getSegment(start, end, scratchNative, true)
            offset += length
        } while (measure.nextContour())
        return scratch
    }
}

private class PreparedIcon(val width: Float, val height: Float, val paths: List<PreparedPath>) {
    companion object {
        fun of(vector: ImageVector): PreparedIcon {
            val paths = ArrayList<PreparedPath>()
            fun collect(group: VectorGroup) {
                for (node in group) {
                    when (node) {
                        is VectorPath -> paths.add(PreparedPath(node))
                        is VectorGroup -> collect(node)
                    }
                }
            }
            collect(vector.root)
            return PreparedIcon(vector.viewportWidth, vector.viewportHeight, paths)
        }
    }
}

private fun DrawScope.drawIcon(icon: PreparedIcon, motion: IconMotion, progress: Float, tint: Color) {
    val scale = min(size.width / icon.width, size.height / icon.height)
    val left = (size.width - icon.width * scale) / 2f
    val top = (size.height - icon.height * scale) / 2f
    val playing = progress < 1f
    withTransform({
        translate(left, top)
        scale(scale, scale, Offset.Zero)
    }) {
        icon.paths.forEachIndexed { index, path ->
            val fx = if (playing) motion.fx(progress, index) else Fx.Rest
            val pivot = Offset(fx.px, fx.py)
            withTransform({
                translate(fx.dx, fx.dy)
                if (fx.rotate != 0f) rotate(fx.rotate, pivot)
                if (fx.sx != 1f || fx.sy != 1f) scale(fx.sx, fx.sy, pivot)
            }) {
                val color = if (fx.alpha < 1f) tint.copy(alpha = tint.alpha * fx.alpha) else tint
                val drawn = if (fx.trimStart > 0f || fx.trimEnd < 1f) path.segment(fx.trimStart, fx.trimEnd) else path.path
                if (path.hasFill && fx.trimStart <= 0f && fx.trimEnd >= 1f) drawPath(drawn, color, style = Fill)
                if (path.hasStroke) {
                    drawPath(drawn, color, style = Stroke(width = path.strokeWidth, cap = path.cap, join = path.join, miter = path.miter))
                }
            }
        }
    }
}
