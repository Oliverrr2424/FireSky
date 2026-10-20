package com.firesky.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.firesky.app.R

val UiFamily = FontFamily(
    Font(R.font.inter_400, FontWeight.W400),
    Font(R.font.inter_500, FontWeight.W500),
    Font(R.font.inter_600, FontWeight.W600),
    Font(R.font.inter_700, FontWeight.W700),
    Font(R.font.inter_800, FontWeight.W800),
)

val DisplayFamily = FontFamily(
    Font(R.font.space_grotesk_400, FontWeight.W400),
    Font(R.font.space_grotesk_500, FontWeight.W500),
    Font(R.font.space_grotesk_600, FontWeight.W600),
    Font(R.font.space_grotesk_700, FontWeight.W700),
)

val TextPrimary = Color.White.copy(alpha = 0.95f)
val TextSecondary = Color.White.copy(alpha = 0.78f)
val TextMuted = Color.White.copy(alpha = 0.62f)
val SurfacePanel = Color(0x4D1F466D)
val SurfaceInset = Color(0x1BFFFFFF)
val SurfaceControl = Color(0x3D123659)
val SurfaceHairline = Color(0x2EFFFFFF)
val SurfaceShadow = Color(0x240B2645)
val AccountInk = Color(0xFF18334D)
val AccountMuted = Color(0xAD18334D)
val AccountPanel = Color(0xEBD5E5F1)

val WeatherClearTop = Color(0xFF79BDFA)
val WeatherClearMid = Color(0xFF5FA3E6)
val WeatherClearBottom = Color(0xFF3D7FC4)

private val FireSkyColors = darkColorScheme(
    primary = Color(0xFFFFD76A),
    onPrimary = Color(0xFF15233B),
    secondary = Color(0xFF9CC8FF),
    background = Color(0xFF5E83B2),
    surface = SurfacePanel,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

private val FireSkyTypography = Typography(
    bodyLarge = TextStyle(fontFamily = UiFamily, fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontFamily = UiFamily, fontSize = 13.sp, color = TextPrimary),
    bodySmall = TextStyle(fontFamily = UiFamily, fontSize = 11.sp, color = TextMuted),
    titleLarge = TextStyle(fontFamily = DisplayFamily, fontSize = 22.sp, fontWeight = FontWeight.W700, color = TextPrimary),
    labelLarge = TextStyle(fontFamily = UiFamily, fontSize = 13.sp, fontWeight = FontWeight.W600, color = TextPrimary),
)

@Composable
fun FireSkyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FireSkyColors,
        typography = FireSkyTypography,
        content = content,
    )
}
