package com.firesky.app.ui.icons

import com.firesky.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IconMotionsTest {
    /** Drawable names by resource id for every icon, from the generated R class. */
    private val names: Map<Int, String> = R.drawable::class.java.fields
        .filter { it.name.startsWith("ic_") }
        .associate { it.getInt(null) to it.name }

    /** Asset art that is never drawn by the animated icon engine. */
    private val notShownInTheApp = setOf("ic_launcher_background", "ic_launcher_foreground", "ic_notification")

    /** The paths an icon really has, counted in its drawable file, so a motion is only judged on those. */
    private fun pathCount(name: String): Int =
        File("src/main/res/drawable/$name.xml").readText().split("<path").size - 1

    private fun registeredIndexes(id: Int): IntRange = 0 until pathCount(names.getValue(id))

    /** Each motion with the path indexes it will be asked about. */
    private val cases: List<Triple<String, IconMotion, IntRange>> =
        IconMotions.registered.map { (id, motion) -> Triple(names.getValue(id), motion, registeredIndexes(id)) } +
            // The fallback has to cope with any icon, however many paths it has.
            Triple("fallback", IconMotions.fallback, 0..12)

    @Test
    fun everyIconInTheAppHasItsOwnMotion() {
        // New icons draw themselves in by default; this makes adding one a deliberate choice.
        val missing = names.filter { it.value !in notShownInTheApp && IconMotions.registered[it.key] == null }.values
        assertTrue("icons without a motion: $missing", missing.isEmpty())
    }

    @Test
    fun motionsStayFiniteAllTheWayThrough() {
        for ((name, motion, indexes) in cases) {
            for (index in indexes) {
                var t = 0f
                while (t <= 1f) {
                    val fx = motion.fx(t, index)
                    val values = listOf(fx.dx, fx.dy, fx.rotate, fx.px, fx.py, fx.sx, fx.sy, fx.alpha, fx.trimStart, fx.trimEnd)
                    assertTrue("$name path $index at $t is not finite", values.all { it.isFinite() })
                    assertTrue("$name path $index at $t has a negative scale", fx.sx > 0f && fx.sy > 0f)
                    assertTrue("$name path $index at $t trims backwards", fx.trimStart <= fx.trimEnd)
                    t += 0.05f
                }
            }
        }
    }

    @Test
    fun everyMotionEndsLookingLikeTheIconAtRest() {
        // The engine stops applying a motion when it finishes, so its last frame must match the resting icon
        // or the icon would visibly jump.
        for ((name, motion, indexes) in cases) {
            for (index in indexes) {
                val end = motion.fx(1f, index)
                val where = "$name path $index"
                assertEquals("$where dx", 0f, end.dx, EPSILON)
                assertEquals("$where dy", 0f, end.dy, EPSILON)
                assertEquals("$where sx", 1f, end.sx, EPSILON)
                assertEquals("$where sy", 1f, end.sy, EPSILON)
                assertEquals("$where alpha", 1f, end.alpha, EPSILON)
                assertEquals("$where trimStart", 0f, end.trimStart, EPSILON)
                assertEquals("$where trimEnd", 1f, end.trimEnd, EPSILON)
                val turn = ((end.rotate % motion.rotationSymmetry) + motion.rotationSymmetry) % motion.rotationSymmetry
                val off = minOf(turn, motion.rotationSymmetry - turn)
                assertTrue("$where ends rotated by ${end.rotate} degrees, which is not a symmetry of the icon", off < 0.01f)
            }
        }
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
