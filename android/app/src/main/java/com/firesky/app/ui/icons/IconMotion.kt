package com.firesky.app.ui.icons

import com.firesky.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * How one path of an icon is moved at a moment of its animation. Distances are in the icon's own
 * 24 x 24 units, angles in degrees, and every pivot is in the same units.
 */
class Fx(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotate: Float = 0f,
    val px: Float = 12f,
    val py: Float = 12f,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val alpha: Float = 1f,
    /** The visible part of the stroke, as fractions of its length: how a line is drawn in. */
    val trimStart: Float = 0f,
    val trimEnd: Float = 1f,
) {
    companion object {
        val Rest = Fx()
    }
}

/**
 * The tap animation for one icon: [fx] answers, for progress 0..1 and a path index, how that path is
 * drawn. At progress 1 the icon must look exactly as it does at rest; [rotationSymmetry] is the angle
 * after which a rotated icon looks unchanged (360 for none, 90 for a plus sign, 72 for a star).
 */
class IconMotion(
    val durationMs: Int,
    val rotationSymmetry: Float = 360f,
    val fx: (t: Float, index: Int) -> Fx,
)

private const val PI_F = PI.toFloat()
private const val TAU = 2f * PI_F

/** 0 -> 1 -> 0, a single swell. */
private fun bump(t: Float) = sin(PI_F * t)

private fun ease(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** A swing that dies away: starts and ends at 0. */
private fun wobble(t: Float, cycles: Float = 3f, decay: Float = 3f) = sin(TAU * cycles * t) * exp(-decay * t) * (1f - t)

/** Two hops that get smaller. */
private fun bounce(t: Float) = abs(sin(TAU * t)) * (1f - t)

/** Progress of item [index] of [count] when they start one after another and each lasts [span] of the whole. */
private fun window(t: Float, index: Int, count: Int, span: Float = 0.6f): Float {
    if (count <= 1) return t.coerceIn(0f, 1f)
    val delay = (1f - span) * index / (count - 1)
    return ((t - delay) / span).coerceIn(0f, 1f)
}

/** Every icon in the app has its own motion; anything else draws itself in stroke by stroke. */
object IconMotions {
    private val thumbUp = IconMotion(650) { t, _ ->
        Fx(rotate = -22f * bump(t), dy = -2.2f * bump(t), px = 4f, py = 22f)
    }
    private val thumbDown = IconMotion(650) { t, _ ->
        Fx(rotate = 22f * bump(t), dy = 2.2f * bump(t), px = 20f, py = 2f)
    }
    private val bell = IconMotion(950) { t, i ->
        // The clapper swings a little wider than the bell, as it does in life.
        Fx(rotate = (if (i == 0) 34f else 25f) * wobble(t, 3f, 2.6f), px = 12f, py = 3f)
    }

    private val table: Map<Int, IconMotion> = mapOf(
        R.drawable.ic_aperture to IconMotion(700, rotationSymmetry = 60f) { t, i ->
            val squeeze = if (i == 0) 1f else 1f - 0.14f * bump(t)
            Fx(rotate = 60f * ease(t), sx = squeeze, sy = squeeze)
        },
        R.drawable.ic_bell to bell,
        R.drawable.ic_bell_off to IconMotion(950) { t, i ->
            if (i == 2) Fx(trimEnd = ease((t * 1.3f).coerceIn(0f, 1f)))
            else Fx(rotate = 18f * wobble(t, 3f, 2.6f), px = 12f, py = 3f)
        },
        R.drawable.ic_camera to IconMotion(550) { t, i ->
            // The lens opens like a shutter; the body gives a small jolt.
            if (i == 1) Fx(sx = 1f + 0.42f * bump(t), sy = 1f + 0.42f * bump(t), py = 13f)
            else Fx(sx = 1f + 0.06f * bump(t), sy = 1f + 0.06f * bump(t), py = 13f)
        },
        R.drawable.ic_check to IconMotion(520) { t, _ ->
            val pop = 1f + 0.18f * bump(t)
            Fx(trimEnd = ease((t * 1.15f).coerceIn(0f, 1f)), sx = pop, sy = pop)
        },
        R.drawable.ic_chevron_down to IconMotion(520) { t, _ -> Fx(dy = 4f * sin(TAU * t) * (1f - t)) },
        R.drawable.ic_chevron_left to IconMotion(520) { t, _ -> Fx(dx = -4f * sin(TAU * t) * (1f - t)) },
        R.drawable.ic_chevron_right to IconMotion(520) { t, _ -> Fx(dx = 4f * sin(TAU * t) * (1f - t)) },
        R.drawable.ic_clock_3 to IconMotion(900) { t, i -> if (i == 1) Fx(rotate = 360f * ease(t)) else Fx.Rest },
        R.drawable.ic_cloud_rain to IconMotion(1000) { t, i ->
            if (i == 0) {
                Fx(dy = -0.9f * bump(t))
            } else {
                // Each drop falls out of sight, then the next one draws back in from the top.
                val u = window(t, i - 1, 3, 0.7f)
                if (u < 0.5f) Fx(trimStart = u * 2f) else Fx(trimEnd = (u - 0.5f) * 2f)
            }
        },
        R.drawable.ic_cloud_sun to IconMotion(800) { t, i ->
            if (i == 5) Fx(dy = -1f * bump(t)) else Fx(rotate = 24f * sin(TAU * t) * (1f - t), py = 10f)
        },
        R.drawable.ic_compass to IconMotion(900) { t, i -> if (i == 0) Fx(rotate = 360f * ease(t)) else Fx.Rest },
        R.drawable.ic_eye to IconMotion(700) { t, i ->
            val blink = bump(((t - 0.45f) / 0.4f).coerceIn(0f, 1f))
            val shut = 1f - 0.92f * blink
            // The pupil glances aside before the lids close.
            Fx(dx = if (i == 1) 2.2f * sin(TAU * t) * (1f - t) else 0f, sy = shut)
        },
        R.drawable.ic_eye_off to IconMotion(700) { t, i ->
            if (i == 3) Fx(trimEnd = ease(t)) else Fx(alpha = 0.35f + 0.65f * ease(t), sy = 1f - 0.4f * bump(t))
        },
        R.drawable.ic_image_plus to IconMotion(700, rotationSymmetry = 90f) { t, i ->
            when (i) {
                0 -> Fx(rotate = 90f * ease(t), px = 19f, py = 5f)
                1 -> Fx(sx = 1f + 0.05f * bump(t), sy = 1f + 0.05f * bump(t))
                2 -> Fx(dy = -0.9f * bump(t))
                else -> Fx(sx = 1f + 0.45f * bump(t), sy = 1f + 0.45f * bump(t), px = 9f, py = 9f)
            }
        },
        R.drawable.ic_loader_circle to IconMotion(800) { t, _ -> Fx(rotate = 360f * t) },
        R.drawable.ic_locate_fixed to IconMotion(700) { t, i ->
            when {
                i <= 3 -> Fx(sx = 1f + 0.28f * bump(t), sy = 1f + 0.28f * bump(t))
                i == 4 -> Fx(sx = 1f - 0.08f * bump(t), sy = 1f - 0.08f * bump(t))
                else -> Fx(sx = 1f - 0.55f * bump(t), sy = 1f - 0.55f * bump(t))
            }
        },
        R.drawable.ic_log_in to IconMotion(600) { t, i -> if (i == 0) Fx.Rest else Fx(dx = 3.2f * bump(t)) },
        R.drawable.ic_log_out to IconMotion(600) { t, i -> if (i == 0) Fx.Rest else Fx(dx = 3.2f * bump(t)) },
        R.drawable.ic_map_pin to IconMotion(800) { t, i ->
            if (i == 1) Fx(dy = -4.2f * bounce(t), sx = 1f + 0.35f * bump(t), sy = 1f + 0.35f * bump(t), py = 10f)
            else Fx(dy = -4.2f * bounce(t))
        },
        R.drawable.ic_minus to IconMotion(500, rotationSymmetry = 180f) { t, _ ->
            Fx(rotate = 180f * ease(t), sx = 1f - 0.3f * bump(t))
        },
        R.drawable.ic_moon_star to IconMotion(900, rotationSymmetry = 90f) { t, i ->
            if (i == 0) Fx(rotate = -18f * sin(TAU * t) * (1f - 0.3f * t))
            else Fx(rotate = 90f * ease(t), sx = 1f + 0.5f * bump(t), sy = 1f + 0.5f * bump(t), px = 20f, py = 5f)
        },
        R.drawable.ic_plus to IconMotion(500, rotationSymmetry = 90f) { t, _ ->
            Fx(rotate = 90f * ease(t), sx = 1f + 0.15f * bump(t), sy = 1f + 0.15f * bump(t))
        },
        R.drawable.ic_refresh_cw to IconMotion(850) { t, _ -> Fx(rotate = 360f * ease(t)) },
        R.drawable.ic_search to IconMotion(700) { t, _ ->
            // The lens circles as if scanning.
            Fx(dx = 1.8f * sin(TAU * t), dy = 1.8f * (cos(TAU * t) - 1f))
        },
        R.drawable.ic_share_2 to IconMotion(900) { t, i ->
            val nodes = listOf(18f to 5f, 6f to 12f, 18f to 19f)
            if (i < 3) {
                val pop = 1f + 0.4f * bump(window(t, i, 3, 0.6f))
                Fx(sx = pop, sy = pop, px = nodes[i].first, py = nodes[i].second)
            } else {
                Fx(trimEnd = ease(window(t, i - 3, 2, 0.7f)))
            }
        },
        R.drawable.ic_sparkles to IconMotion(850, rotationSymmetry = 90f) { t, i ->
            when (i) {
                0 -> Fx(sx = 1f + 0.28f * bump(t), sy = 1f + 0.28f * bump(t), rotate = 20f * sin(TAU * t) * (1f - t), px = 9.5f, py = 11f)
                1, 2 -> {
                    val u = window(t, 0, 2, 0.7f)
                    Fx(rotate = 90f * ease(u), sx = 1f + 0.5f * bump(u), sy = 1f + 0.5f * bump(u), px = 20f, py = 5f)
                }
                else -> {
                    val u = window(t, 1, 2, 0.7f)
                    Fx(rotate = 90f * ease(u), sx = 1f + 0.5f * bump(u), sy = 1f + 0.5f * bump(u), px = 4f, py = 18f)
                }
            }
        },
        R.drawable.ic_star to IconMotion(700, rotationSymmetry = 72f) { t, _ ->
            Fx(rotate = 72f * ease(t), sx = 1f + 0.3f * bump(t), sy = 1f + 0.3f * bump(t), py = 12.3f)
        },
        R.drawable.ic_sun_medium to IconMotion(800, rotationSymmetry = 45f) { t, i ->
            if (i == 0) Fx(sx = 1f - 0.18f * bump(t), sy = 1f - 0.18f * bump(t))
            else Fx(rotate = 45f * ease(t), sx = 1f + 0.3f * bump(t), sy = 1f + 0.3f * bump(t))
        },
        R.drawable.ic_thumbs_up to thumbUp,
        R.drawable.ic_thumbs_up_filled to thumbUp,
        R.drawable.ic_thumbs_down to thumbDown,
        R.drawable.ic_thumbs_down_filled to thumbDown,
        R.drawable.ic_triangle_alert to IconMotion(800) { t, i ->
            val jump = if (i == 0) 0f else -1f * bump(t)
            Fx(rotate = 14f * wobble(t, 3f, 2.4f), px = 12f, py = 20f, dy = jump)
        },
        R.drawable.ic_user_round to IconMotion(650) { t, i ->
            if (i == 0) Fx(dy = -2.6f * bump(t), sx = 1f + 0.18f * bump(t), sy = 1f + 0.18f * bump(t), py = 8f)
            else Fx(sx = 1f + 0.08f * bump(t), sy = 1f - 0.2f * bump(t), py = 21f)
        },
        R.drawable.ic_users to IconMotion(800) { t, i ->
            val group = if (i < 2) 0 else 1
            val hop = bump(window(t, group, 2, 0.65f))
            Fx(dy = -2.4f * hop, sx = 1f + 0.08f * hop, sy = 1f + 0.08f * hop, py = 20f)
        },
        R.drawable.ic_x to IconMotion(500, rotationSymmetry = 90f) { t, _ ->
            Fx(rotate = 90f * ease(t), sx = 1f - 0.15f * bump(t), sy = 1f - 0.15f * bump(t))
        },
    )

    private val drawIn = IconMotion(650) { t, i ->
        // Paths past the sixth join the last one so every stroke is complete when the animation ends.
        val u = window(t, minOf(i, 5), 6, 0.6f)
        Fx(trimEnd = ease(u), sx = 1f + 0.1f * bump(t), sy = 1f + 0.1f * bump(t))
    }

    fun forRes(resId: Int): IconMotion = table[resId] ?: drawIn

    /** The icons that have their own motion, for tests. */
    internal val registered: Map<Int, IconMotion> get() = table
    internal val fallback: IconMotion get() = drawIn
}
