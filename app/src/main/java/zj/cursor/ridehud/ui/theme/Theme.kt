package zj.cursor.ridehud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark neon HUD scheme mapped to Material 3 color roles.
 * Tone-based surfaces follow M3 guidance instead of elevation-only surfaces.
 * https://m3.material.io/styles/color/system/overview
 */
private val NeonHudColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF003640),
    primaryContainer = Color(0xFF004E5C),
    onPrimaryContainer = Color(0xFFB6F0FA),
    secondary = NeonViolet,
    onSecondary = Color(0xFF2A1A5E),
    secondaryContainer = Color(0xFF3B2F66),
    onSecondaryContainer = Color(0xFFE9DEFF),
    tertiary = NeonLime,
    onTertiary = Color(0xFF003825),
    tertiaryContainer = Color(0xFF0A4F38),
    onTertiaryContainer = Color(0xFFB6F5D5),
    background = Slate950,
    onBackground = Ink,
    surface = SoftSurface,
    onSurface = Ink,
    surfaceVariant = SoftSurfaceHigh,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceTint = NeonCyan,
    surfaceBright = SoftSurfaceHighest,
    surfaceDim = Slate950,
    surfaceContainerLowest = Color(0xFF080E13),
    surfaceContainerLow = Slate900,
    surfaceContainer = SoftSurface,
    surfaceContainerHigh = SoftSurfaceHigh,
    surfaceContainerHighest = SoftSurfaceHighest,
    outline = OutlineMuted,
    outlineVariant = Color(0xFF2A3844),
    error = NeonRose,
    onError = Color(0xFF450A12),
    errorContainer = Color(0xFF7F1D2C),
    onErrorContainer = Color(0xFFFFD9DE),
    inverseSurface = Ink,
    inverseOnSurface = Slate950,
    inversePrimary = Color(0xFF00687A),
)

@Composable
fun RideHUDTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeonHudColorScheme,
        typography = Typography,
        content = content,
    )
}
