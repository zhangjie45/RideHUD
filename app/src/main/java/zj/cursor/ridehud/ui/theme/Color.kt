package zj.cursor.ridehud.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Seeded from Material Theme Builder style (cyan key for neon HUD).
 * See: https://m3.material.io/styles/color/system/overview
 */
val NeonCyan = Color(0xFF4FD8EB)
val NeonViolet = Color(0xFFC4B5FD)
val NeonPink = Color(0xFFF9A8D4)
val NeonLime = Color(0xFF6EE7B7)
val NeonAmber = Color(0xFFFCD34D)
/** M3 tertiary-style complement to cyan (~peach/coral). */
val NeonPeach = Color(0xFFFDBA74)
val SoftSky = Color(0xFF7DD3FC)
val SoftTeal = Color(0xFF5EEAD4)
val NeonRose = Color(0xFFFB7185)

val Ink = Color(0xFFE8F8FC)
val Slate950 = Color(0xFF0B1218)
val Slate900 = Color(0xFF111A22)
val SoftSurface = Color(0xFF162029)
val SoftSurfaceHigh = Color(0xFF1C2833)
val SoftSurfaceHighest = Color(0xFF23303C)
val OutlineMuted = Color(0xFF3D4E5C)
val OnSurfaceMuted = Color(0xFFA7B6C2)

// Compatibility aliases
val HudBackground = Slate950
val HudSurface = SoftSurface
val HudPrimary = Ink
val HudSpeed = Ink
val HudHeart = NeonLime
val HudMuted = OnSurfaceMuted.copy(alpha = 0.85f)
val HudAccent = NeonCyan
val HudWarning = NeonAmber
val HudViolet = NeonViolet
val HudPink = NeonPink
val HudLime = NeonLime
val HudCardBorder = NeonCyan.copy(alpha = 0.22f)
val HudTertiaryBorder = OutlineMuted.copy(alpha = 0.45f)
val Slate850 = SoftSurface
val Slate800 = SoftSurfaceHigh
val Slate700 = OutlineMuted
val Slate400 = OnSurfaceMuted
val Slate300 = Color(0xFFCBD5E1)
