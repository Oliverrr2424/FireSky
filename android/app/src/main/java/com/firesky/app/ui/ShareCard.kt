package com.firesky.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.firesky.app.R
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/** Everything the picture needs; the text is already localised by the caller. */
data class ShareCardSpec(
    val mode: String,
    val probability: Int,
    val scoreLabel: String,
    val eventLabel: String,
    val placeName: String,
    val region: String,
    val dateLine: String,
    val windowLine: String?,
    val tagline: String,
    val linkText: String,
)

/**
 * The image people receive when they share a forecast: a sky in the event's colours, the score
 * gauge from the app, the place and time, and a footer that tells them where to get FireSky.
 */
object ShareCard {
    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    fun render(context: Context, spec: ShareCardSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val display = ResourcesCompat.getFont(context, R.font.space_grotesk_700) ?: Typeface.DEFAULT_BOLD
        val body = ResourcesCompat.getFont(context, R.font.inter_500) ?: Typeface.DEFAULT
        val bodyBold = ResourcesCompat.getFont(context, R.font.inter_700) ?: Typeface.DEFAULT_BOLD

        drawSky(canvas, spec)
        // A dark fade under the place, date and footer text keeps it readable over the bright horizon.
        canvas.drawRect(
            0f, 820f, WIDTH.toFloat(), HEIGHT.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 820f, 0f, HEIGHT.toFloat(),
                    intArrayOf(0x00140B2E, 0x8C140B2E.toInt(), 0xE0140B2E.toInt()),
                    floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP,
                )
            },
        )
        drawGauge(canvas, spec, display, body)

        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        // Header
        text.typeface = display
        text.color = 0xFFFFFFFF.toInt()
        text.textSize = 58f
        canvas.drawText("FireSky", 72f, 128f, text)
        text.typeface = bodyBold
        text.textSize = 34f
        text.color = 0xD9FFFFFF.toInt()
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(spec.eventLabel, WIDTH - 72f, 126f, text)

        // Place
        text.textAlign = Paint.Align.CENTER
        text.typeface = display
        text.color = 0xFFFFFFFF.toInt()
        fit(text, spec.placeName, WIDTH - 144f, 96f)
        canvas.drawText(spec.placeName, WIDTH / 2f, 960f, text)
        if (spec.region.isNotBlank()) {
            text.typeface = body
            text.color = 0xCCFFFFFF.toInt()
            fit(text, spec.region, WIDTH - 144f, 44f)
            canvas.drawText(spec.region, WIDTH / 2f, 1018f, text)
        }
        text.typeface = body
        text.color = 0xD9FFFFFF.toInt()
        text.textSize = 38f
        canvas.drawText(spec.dateLine, WIDTH / 2f, 1082f, text)
        spec.windowLine?.let {
            text.typeface = bodyBold
            text.color = 0xFFFFFFFF.toInt()
            fit(text, it, WIDTH - 144f, 46f)
            canvas.drawText(it, WIDTH / 2f, 1146f, text)
        }

        // Footer: where to get the app.
        val pill = RectF(72f, 1196f, WIDTH - 72f, 1296f)
        canvas.drawRoundRect(pill, 50f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF })
        canvas.drawRoundRect(
            pill, 50f, 50f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x55FFFFFF },
        )
        text.typeface = bodyBold
        text.color = 0xFFFFFFFF.toInt()
        fit(text, spec.tagline, pill.width() - 80f, 38f)
        canvas.drawText(spec.tagline, WIDTH / 2f, 1240f, text)
        text.typeface = body
        text.color = 0xCCFFFFFF.toInt()
        fit(text, spec.linkText, pill.width() - 80f, 30f)
        canvas.drawText(spec.linkText, WIDTH / 2f, 1278f, text)
        return bitmap
    }

    /** Writes the PNG where the app's FileProvider can hand it to the share sheet. */
    fun write(context: Context, bitmap: Bitmap): File {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(directory, "firesky-forecast.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun fit(paint: Paint, text: String, maxWidth: Float, startSize: Float) {
        paint.textSize = startSize
        while (paint.measureText(text) > maxWidth && paint.textSize > 20f) paint.textSize -= 2f
    }

    private fun drawSky(canvas: Canvas, spec: ShareCardSpec) {
        val sunrise = spec.mode == "sunrise"
        val colors = if (sunrise) {
            intArrayOf(0xFF182552.toInt(), 0xFF4A4A9A.toInt(), 0xFFC5668C.toInt(), 0xFFF59A6B.toInt(), 0xFFFFD98A.toInt())
        } else {
            intArrayOf(0xFF14123A.toInt(), 0xFF3B1E6B.toInt(), 0xFF9B2F7C.toInt(), 0xFFE8604C.toInt(), 0xFFFFB45C.toInt())
        }
        val horizon = HEIGHT * 0.8f
        val sky = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, horizon + 40f, colors, floatArrayOf(0f, 0.34f, 0.58f, 0.8f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), sky)

        // Stars, steady for the same place so a re-share looks identical.
        val random = Random(spec.placeName.hashCode())
        val star = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(70) {
            val y = random.nextFloat() * HEIGHT * 0.42f
            val fade = 1f - y / (HEIGHT * 0.42f)
            star.color = ((random.nextFloat() * 0.7f + 0.2f) * fade * 255).toInt().coerceIn(0, 255) shl 24 or 0xFFFFFF
            canvas.drawCircle(random.nextFloat() * WIDTH, y, random.nextFloat() * 2.4f + 0.8f, star)
        }

        // The sun behind the hills.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                WIDTH / 2f, horizon, 620f,
                intArrayOf(0xCCFFE2A0.toInt(), 0x55FFB45C, 0x00FFB45C),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(WIDTH / 2f, horizon, 620f, glow)
        canvas.drawCircle(WIDTH / 2f, horizon + 20f, 96f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF1C4.toInt() })

        hills(canvas, horizon + 24f, 70f, 0.0045f, 0f, 0xD92A1745.toInt())
        hills(canvas, horizon + 96f, 54f, 0.0062f, 2.1f, 0xFF140D2A.toInt())
    }

    private fun hills(canvas: Canvas, baseline: Float, amplitude: Float, frequency: Float, phase: Float, color: Int) {
        val path = Path()
        path.moveTo(0f, HEIGHT.toFloat())
        var x = 0f
        while (x <= WIDTH) {
            val y = baseline - amplitude * (0.5f + 0.5f * sin(x * frequency * 6.28f + phase)) - amplitude * 0.35f * sin(x * frequency * 15f + phase)
            path.lineTo(x, y)
            x += 12f
        }
        path.lineTo(WIDTH.toFloat(), HEIGHT.toFloat())
        path.close()
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun drawGauge(canvas: Canvas, spec: ShareCardSpec, display: Typeface, body: Typeface) {
        val cx = WIDTH / 2f
        val cy = 700f
        val radius = 300f
        val stroke = 66f
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = 0x33FFFFFF
        }
        canvas.drawArc(box, 180f, 180f, false, track)

        val sweep = 180f * spec.probability.coerceIn(0, 100) / 100f
        if (sweep > 0f) {
            val gradient = LinearGradient(
                cx - radius, 0f, cx + radius, 0f,
                intArrayOf(0xFF4C1D95.toInt(), 0xFFE11D48.toInt(), 0xFFF97316.toInt(), 0xFFFACC15.toInt()),
                null, Shader.TileMode.CLAMP,
            )
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke + 30f; strokeCap = Paint.Cap.ROUND; shader = gradient; alpha = 70
            }
            canvas.drawArc(box, 180f, sweep, false, glow)
            val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; shader = gradient
            }
            canvas.drawArc(box, 180f, sweep, false, arc)
        }

        val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = display; color = 0xFFFFFFFF.toInt(); textSize = 210f; textAlign = Paint.Align.CENTER
        }
        val percent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = display; color = 0x99FFFFFF.toInt(); textSize = 84f; textAlign = Paint.Align.LEFT
        }
        val digits = spec.probability.toString()
        val numberWidth = number.measureText(digits)
        val total = numberWidth + 10f + percent.measureText("%")
        val left = cx - total / 2f
        number.textAlign = Paint.Align.LEFT
        canvas.drawText(digits, left, cy - 40f, number)
        canvas.drawText("%", left + numberWidth + 10f, cy - 40f - 118f, percent)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = body; color = 0xFFFFE3C2.toInt(); textSize = 52f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(spec.scoreLabel, cx, cy + 86f, label)
    }
}
