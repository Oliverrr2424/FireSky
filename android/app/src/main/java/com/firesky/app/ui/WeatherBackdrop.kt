package com.firesky.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun WeatherBackdrop(theme: String, isNight: Boolean, modifier: Modifier = Modifier) {
    val field = remember(theme, isNight) { WeatherField(theme, isNight) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(theme, isNight, canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            field.resize(canvasSize.width.toFloat(), canvasSize.height.toFloat())
        }
    }

    LaunchedEffect(theme, isNight) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(0.05).toFloat()
                last = now
                field.step(dt, now / 1_000_000.0)
                tick++
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it },
    ) {
        @Suppress("UNUSED_EXPRESSION")
        tick
        field.draw(this)
    }
}

private class WeatherField(private val theme: String, private val isNight: Boolean) {
    private var width = 0f
    private var height = 0f
    private val random = Random(System.nanoTime())
    private val particles = mutableListOf<Particle>()
    private val splashes = mutableListOf<Splash>()
    private val droplets = mutableListOf<Droplet>()
    private val stars = mutableListOf<Star>()
    private val clouds = mutableListOf<Cloud>()
    private var lightningAlpha = 0f
    private var lightningNextAt = 0.0
    private var lightningFlickers = 0

    private class Particle(
        var x: Float,
        var y: Float,
        var len: Float = 0f,
        var speed: Float = 0f,
        var wind: Float = 0f,
        var thickness: Float = 0f,
        var alpha: Float = 0f,
        var r: Float = 0f,
        var drift: Float = 0f,
        var phase: Float = 0f,
        var spin: Float = 0f,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var baseAlpha: Float = 0f,
        var twinkle: Float = 0f,
        var twinkleSpeed: Float = 0f,
    )

    private class Splash(var x: Float, var y: Float, var r: Float, var life: Float, val max: Float)

    private class Droplet(
        var x: Float,
        var y: Float,
        val r: Float,
        val tail: Float,
        val alpha: Float,
        val speed: Float,
    )

    private class Star(
        val x: Float,
        val y: Float,
        val r: Float,
        val baseAlpha: Float,
        var twinkle: Float,
        val speed: Float,
    )

    private class Cloud(
        var x: Float,
        var y: Float,
        val r: Float,
        val speed: Float,
        val alpha: Float,
        val stretch: Float,
    )

    private fun rand(min: Float, max: Float): Float = min + random.nextFloat() * (max - min)

    fun resize(nextWidth: Float, nextHeight: Float) {
        if (nextWidth <= 0f || nextHeight <= 0f) return
        if (abs(nextWidth - width) < 1f && abs(nextHeight - height) < 48f) return
        width = nextWidth
        height = nextHeight
        init()
    }

    private fun init() {
        particles.clear()
        splashes.clear()
        droplets.clear()
        stars.clear()
        clouds.clear()
        val area = width * height
        val cloudCount = when {
            theme == "clear" && !isNight -> 6
            theme == "rain" || theme == "storm" -> 18
            theme == "fog" -> 20
            else -> 14
        }
        repeat(cloudCount) { clouds.add(makeCloud()) }

        when {
            theme == "rain" || theme == "storm" -> {
                val isStorm = theme == "storm"
                val count = minOf(if (isStorm) 520 else 380, maxOf(80, (area / 1600f).toInt()))
                repeat(count) { particles.add(makeRain(isStorm)) }
                val dropletCount = minOf(140, maxOf(40, (area / 10500f).toInt()))
                repeat(dropletCount) { droplets.add(makeDroplet()) }
                lightningNextAt = System.currentTimeMillis() + rand(2500f, 6000f).toDouble()
            }
            theme == "snow" -> {
                val count = minOf(260, maxOf(60, (area / 4500f).toInt()))
                repeat(count) { particles.add(makeSnow()) }
            }
            theme == "cloudy" || theme == "fog" -> Unit
            isNight -> {
                val count = minOf(240, maxOf(70, (area / 6600f).toInt()))
                repeat(count) { stars.add(makeStar()) }
            }
            else -> {
                val count = minOf(60, maxOf(20, (area / 22000f).toInt()))
                repeat(count) { particles.add(makeDust()) }
            }
        }
    }

    private fun makeRain(isStorm: Boolean): Particle {
        val wind = if (isStorm) rand(140f, 240f) else rand(40f, 110f)
        return Particle(
            x = rand(-40f, width + 40f),
            y = rand(-height, height),
            len = rand(10f, if (isStorm) 26f else 20f),
            speed = rand(if (isStorm) 620f else 460f, if (isStorm) 980f else 760f),
            wind = wind,
            thickness = rand(0.7f, if (isStorm) 1.6f else 1.2f),
            alpha = rand(0.18f, if (isStorm) 0.55f else 0.45f),
        )
    }

    private fun makeDroplet() = Droplet(
        x = rand(0f, width),
        y = rand(0f, height),
        r = rand(1.2f, 4.8f),
        tail = rand(4f, 20f),
        alpha = rand(0.08f, 0.22f),
        speed = rand(2f, 14f),
    )

    private fun makeSplash(x: Float) = Splash(
        x = x,
        y = height - rand(2f, 14f),
        r = 0f,
        life = 1f,
        max = rand(6f, 12f),
    )

    private fun makeSnow() = Particle(
        x = rand(0f, width),
        y = rand(-height, height),
        r = rand(0.9f, 2.8f),
        speed = rand(22f, 70f),
        drift = rand(8f, 38f),
        phase = rand(0f, (PI * 2).toFloat()),
        spin = rand(0.4f, 1.4f),
        alpha = rand(0.55f, 1f),
    )

    private fun makeCloud(): Cloud {
        val isFog = theme == "fog"
        return Cloud(
            x = rand(-220f, width),
            y = if (isFog) rand(height * 0.24f, height * 0.95f) else rand(-height * 0.08f, height * 0.62f),
            r = rand(if (isFog) 180f else 140f, if (isFog) 420f else 340f),
            speed = if (isFog) rand(2f, 6f) else rand(3f, 10f),
            alpha = if (isFog) rand(0.1f, 0.22f) else rand(0.08f, 0.2f),
            stretch = rand(1.35f, 2.7f),
        )
    }

    private fun makeStar() = Star(
        x = rand(0f, width),
        y = rand(0f, height * 0.85f),
        r = rand(0.3f, 1.5f),
        baseAlpha = rand(0.35f, 0.95f),
        twinkle = rand(0f, (PI * 2).toFloat()),
        speed = rand(0.6f, 1.6f),
    )

    private fun makeDust() = Particle(
        x = rand(0f, width),
        y = rand(0f, height),
        r = rand(0.6f, 1.8f),
        vx = rand(-6f, 14f),
        vy = rand(-12f, -2f),
        alpha = rand(0.06f, 0.22f),
    )

    fun step(dt: Float, nowMs: Double) {
        if (width <= 0f || height <= 0f) return
        if (theme == "rain" || theme == "storm") {
            updateClouds(dt)
            particles.forEach { p ->
                p.y += p.speed * dt
                p.x += p.wind * dt
                if (p.y > height + 4f) {
                    if (random.nextFloat() < 0.35f && splashes.size < 80) splashes.add(makeSplash(p.x))
                    p.y = rand(-40f, -4f)
                    p.x = rand(-40f, width + 40f)
                }
                if (p.x > width + 20f) p.x = -20f
                if (p.x < -40f) p.x = width + 20f
            }
            droplets.forEach { d ->
                d.y += d.speed * dt
                if (d.y - d.tail > height) {
                    d.y = rand(-40f, 0f)
                    d.x = rand(0f, width)
                }
            }
            var index = splashes.size - 1
            while (index >= 0) {
                val splash = splashes[index]
                splash.r += dt * 18f
                splash.life -= dt * 1.4f
                if (splash.life <= 0f || splash.r > splash.max) splashes.removeAt(index) else index--
            }
            if (theme == "storm") {
                if (nowMs >= lightningNextAt) {
                    lightningAlpha = 1f
                    lightningFlickers = rand(1f, 3.99f).toInt()
                    lightningNextAt = nowMs + rand(3500f, 9000f)
                }
                if (lightningAlpha > 0f) {
                    lightningAlpha -= dt * 2.6f
                    if (lightningAlpha <= 0f && lightningFlickers > 0) {
                        lightningFlickers -= 1
                        lightningAlpha = rand(0.5f, 1f)
                    }
                }
            }
        } else if (theme == "snow") {
            updateClouds(dt)
            particles.forEach { p ->
                p.phase += dt * p.spin
                p.y += p.speed * dt
                p.x += sin(p.phase) * p.drift * dt
                if (p.y > height + 4f) {
                    p.y = -4f
                    p.x = rand(0f, width)
                }
                if (p.x > width + 6f) p.x = -6f
                if (p.x < -6f) p.x = width + 6f
            }
        } else if (theme == "cloudy" || theme == "fog") {
            updateClouds(dt)
        } else if (isNight) {
            updateClouds(dt)
        } else {
            particles.forEach { p ->
                p.x += p.vx * dt
                p.y += p.vy * dt
                if (p.y < -6f) {
                    p.y = height + 6f
                    p.x = rand(0f, width)
                }
                if (p.x > width + 10f) p.x = -10f
                if (p.x < -10f) p.x = width + 10f
            }
            updateClouds(dt)
        }
        stars.forEach { s -> s.twinkle += 0.012f * s.speed }
    }

    private fun updateClouds(dt: Float) {
        val isFog = theme == "fog"
        clouds.forEach { c ->
            c.x += c.speed * dt
            if (c.x - c.r > width) {
                c.x = -c.r
                c.y = if (isFog) rand(height * 0.35f, height * 0.95f) else rand(-40f, height * 0.65f)
            }
        }
    }

    fun draw(scope: DrawScope) {
        if (width <= 0f || height <= 0f) return
        val cloudy = theme in listOf("cloudy", "rain", "storm", "fog")
        val top = if (isNight) {
            if (cloudy) Color(0xFF121A3A) else Color(0xFF090F27)
        } else {
            if (cloudy) Color(0xFF77899D) else Color(0xFF74BDF7)
        }
        val mid = if (isNight) {
            if (cloudy) Color(0xFF283452) else Color(0xFF17234A)
        } else {
            if (cloudy) Color(0xFF8FA3B6) else Color(0xFF87C9FF)
        }
        val bottom = if (isNight) {
            if (cloudy) Color(0xFF334461) else Color(0xFF243C68)
        } else {
            if (cloudy) Color(0xFF718297) else Color(0xFF4C91D7)
        }
        scope.drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(top, mid, bottom),
                startY = 0f,
                endY = height,
            ),
            size = Size(width, height),
        )
        if (theme == "storm" || theme == "rain" || theme == "fog") {
            scope.drawRect(
                color = if (theme == "storm") Color(0x52080E20) else Color(0x385F6E80),
                size = Size(width, height),
            )
        }

        if (isNight && theme != "rain" && theme != "storm") {
            stars.forEach { s ->
                val alpha = s.baseAlpha * (0.52f + 0.48f * abs(sin(s.twinkle.toDouble())).toFloat())
                scope.drawCircle(Color.White.copy(alpha = alpha.coerceIn(0f, 1f)), s.r, Offset(s.x, s.y))
            }
        }

        when {
            theme == "rain" || theme == "storm" -> {
                drawClouds(scope, heavy = true)
                particles.forEach { p ->
                    scope.drawLine(
                        color = Color(0xFFDCE4F0).copy(alpha = p.alpha),
                        start = Offset(p.x, p.y),
                        end = Offset(p.x - p.wind * 0.04f, p.y - p.len),
                        strokeWidth = p.thickness,
                        cap = StrokeCap.Round,
                    )
                }
                drawDroplets(scope)
                splashes.forEach { s ->
                    scope.drawOval(
                        color = Color(0xFFDCE6F5).copy(alpha = (0.22f * s.life).coerceIn(0f, 1f)),
                        topLeft = Offset(s.x - s.r, s.y - s.r * 0.32f),
                        size = Size(s.r * 2f, s.r * 0.64f),
                        style = Stroke(width = 1f),
                    )
                }
                if (lightningAlpha > 0f) {
                    scope.drawRect(
                        color = Color(0xFFE1EBFF).copy(alpha = (lightningAlpha * 0.42f).coerceIn(0f, 1f)),
                        size = Size(width, height),
                    )
                }
            }
            theme == "snow" -> {
                drawClouds(scope, heavy = true)
                particles.forEach { p ->
                    scope.drawCircle(Color.White.copy(alpha = p.alpha), p.r, Offset(p.x, p.y))
                }
            }
            theme == "cloudy" || theme == "fog" -> drawClouds(scope)
            isNight -> drawClouds(scope)
            else -> {
                particles.forEach { p ->
                    scope.drawCircle(Color(0xFFFFF0D2).copy(alpha = p.alpha), p.r, Offset(p.x, p.y))
                }
                drawClouds(scope)
            }
        }
    }

    private fun drawDroplets(scope: DrawScope) {
        droplets.forEach { d ->
            val center = Offset(d.x, d.y)
            val brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = (d.alpha * 1.2f).coerceIn(0f, 1f)),
                    Color(0xFFDCE8F5).copy(alpha = (d.alpha * 0.42f).coerceIn(0f, 1f)),
                    Color.Transparent,
                ),
                center = Offset(d.x - d.r * 0.35f, d.y - d.r * 0.45f),
                radius = d.r * 2.2f,
            )
            scope.drawOval(
                brush = brush,
                topLeft = Offset(center.x - d.r * 0.75f, center.y - d.r * 1.12f),
                size = Size(d.r * 1.5f, d.r * 2.24f),
            )
            scope.drawLine(
                color = Color.White.copy(alpha = (d.alpha * 0.5f).coerceIn(0f, 1f)),
                start = Offset(d.x + d.r * 0.2f, d.y + d.r),
                end = Offset(d.x + d.r * 0.6f, d.y + d.tail),
                strokeWidth = 0.8f,
            )
        }
    }

    private fun drawClouds(scope: DrawScope, heavy: Boolean = false) {
        val isFog = theme == "fog"
        val tint = when {
            isFog -> Triple(225, 234, 242)
            isNight -> Triple(150, 160, 200)
            else -> Triple(232, 240, 248)
        }
        clouds.forEach { c ->
            scope.withTransform({
                translate(c.x, c.y)
                scale(c.stretch, 0.72f, pivot = Offset.Zero)
            }) {
                val alphaBase = c.alpha * (if (heavy) 1.7f else 1f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(tint.first, tint.second, tint.third).copy(alpha = alphaBase.coerceIn(0f, 1f)),
                            Color(tint.first, tint.second, tint.third).copy(alpha = (c.alpha * (if (heavy) 1f else 0.62f)).coerceIn(0f, 1f)),
                            Color(tint.first, tint.second, tint.third).copy(alpha = (c.alpha * 0.24f).coerceIn(0f, 1f)),
                            Color.Transparent,
                        ),
                        center = Offset.Zero,
                        radius = c.r,
                    ),
                    radius = c.r,
                    center = Offset.Zero,
                )
            }
        }
    }
}
