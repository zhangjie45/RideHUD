package zj.cursor.ridehud.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import zj.cursor.ridehud.ui.theme.NeonCyan
import zj.cursor.ridehud.ui.theme.NeonPink
import zj.cursor.ridehud.ui.theme.Slate900
import zj.cursor.ridehud.ui.theme.Slate950

@Composable
fun HudBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = ComposeModifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF030712), Slate900, Slate950),
                ),
            ),
    ) {
        Canvas(modifier = ComposeModifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonCyan.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * -0.05f),
                    radius = size.minDimension * 0.85f,
                ),
                radius = size.minDimension * 0.85f,
                center = Offset(size.width * 0.5f, size.height * -0.05f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(NeonPink.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(size.width * 1.05f, size.height * 1.05f),
                    radius = size.minDimension * 0.7f,
                ),
                radius = size.minDimension * 0.7f,
                center = Offset(size.width * 1.05f, size.height * 1.05f),
            )
        }
        content()
    }
}
