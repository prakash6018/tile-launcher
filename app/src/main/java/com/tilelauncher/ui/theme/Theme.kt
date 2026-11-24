package com.tilelauncher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tilelauncher.R
import com.tilelauncher.data.ThemeMode

/** The 20 Windows Phone 8 / 8.1 accent colors. */
object Accents {
    val all: List<Pair<String, Color>> = listOf(
        "lime" to Color(0xFFA4C400),
        "green" to Color(0xFF60A917),
        "emerald" to Color(0xFF008A00),
        "teal" to Color(0xFF00ABA9),
        "cyan" to Color(0xFF1BA1E2),
        "cobalt" to Color(0xFF0050EF),
        "indigo" to Color(0xFF6A00FF),
        "violet" to Color(0xFFAA00FF),
        "pink" to Color(0xFFF472D0),
        "magenta" to Color(0xFFD80073),
        "crimson" to Color(0xFFA20025),
        "red" to Color(0xFFE51400),
        "orange" to Color(0xFFFA6800),
        "amber" to Color(0xFFF0A30A),
        "yellow" to Color(0xFFE3C800),
        "brown" to Color(0xFF825A2C),
        "olive" to Color(0xFF6D8764),
        "steel" to Color(0xFF647687),
        "mauve" to Color(0xFF76608A),
        "taupe" to Color(0xFF87794E),
    )
    val Cobalt = Color(0xFF0050EF)
}

val Selawik = FontFamily(
    Font(R.font.selawik_light, FontWeight.Light),
    Font(R.font.selawik_semilight, FontWeight(350)),
    Font(R.font.selawik_regular, FontWeight.Normal),
    Font(R.font.selawik_semibold, FontWeight.SemiBold),
)

val SemiLight = FontWeight(350)

/** Text and glyphs on tiles are white in both themes. */
val OnTile = Color.White

@Immutable
data class MetroColors(
    val accent: Color,
    val background: Color,
    val foreground: Color,
    /** Secondary text. */
    val subtle: Color,
    /** Inactive surfaces, e.g. jump-grid letters with no apps, menus. */
    val chrome: Color,
    val isLight: Boolean,
)

fun metroColors(accent: Color, light: Boolean) = if (light) {
    MetroColors(accent, Color.White, Color.Black, Color(0xFF666666), Color(0xFFE5E5E5), isLight = true)
} else {
    MetroColors(accent, Color.Black, Color.White, Color(0xFF999999), Color(0xFF1F1F1F), isLight = false)
}

val LocalMetro = staticCompositionLocalOf { metroColors(Accents.Cobalt, light = false) }

@Composable
fun resolveLight(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> false
    ThemeMode.LIGHT -> true
    ThemeMode.SYSTEM -> !isSystemInDarkTheme()
}

@Composable
fun MetroTheme(colors: MetroColors, content: @Composable () -> Unit) {
    val base = Typography()
    val typography = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = Selawik),
        bodyMedium = base.bodyMedium.copy(fontFamily = Selawik),
        labelLarge = base.labelLarge.copy(fontFamily = Selawik),
        titleMedium = base.titleMedium.copy(fontFamily = Selawik),
        headlineSmall = base.headlineSmall.copy(fontFamily = Selawik, fontWeight = FontWeight.Light),
    )
    val scheme = if (colors.isLight) {
        lightColorScheme(
            primary = colors.accent,
            background = colors.background,
            surface = colors.background,
            surfaceContainer = colors.chrome,
            surfaceContainerHigh = colors.background,
            onSurface = colors.foreground,
        )
    } else {
        darkColorScheme(
            primary = colors.accent,
            background = colors.background,
            surface = colors.chrome,
            surfaceContainer = colors.chrome,
            surfaceContainerHigh = colors.chrome,
            onSurface = colors.foreground,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(
            LocalMetro provides colors,
            LocalTextStyle provides TextStyle(fontFamily = Selawik, color = colors.foreground),
            content = content,
        )
    }
}
