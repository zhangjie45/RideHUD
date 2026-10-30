package zj.cursor.ridehud.ui.hud

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import zj.cursor.ridehud.R
import zj.cursor.ridehud.domain.RideMetrics
import zj.cursor.ridehud.domain.RideSessionStore
import zj.cursor.ridehud.ui.components.HudBackdrop
import zj.cursor.ridehud.ui.theme.NeonCyan
import zj.cursor.ridehud.ui.theme.NeonLime
import zj.cursor.ridehud.ui.theme.NeonPeach
import zj.cursor.ridehud.ui.theme.NeonRose
import zj.cursor.ridehud.ui.theme.Slate300
import zj.cursor.ridehud.ui.theme.SoftSky
import zj.cursor.ridehud.ui.theme.SoftTeal
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

private const val MAX_SPEED_RING = 45f
private const val METRIC_ANIM_MS = 420
/** Instant speed follows SpeedSource-style direct assign — keep tween short. */
private const val SPEED_ANIM_MS = 160

@Composable
fun HudScreen(
    onStopRide: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val metrics by RideSessionStore.metrics.collectAsStateWithLifecycle()

    HudBackdrop {
        Column(
            modifier = ComposeModifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TopBar(isPaused = metrics.isPaused)

            Box(
                modifier = ComposeModifier
                    .fillMaxWidth()
                    .weight(1.2f),
                contentAlignment = Alignment.Center,
            ) {
                SpeedRing(speedKmh = metrics.speedKmh)
            }

            SecondaryCards(metrics = metrics)

            Spacer(ComposeModifier.height(10.dp))

            TertiaryStrip(metrics = metrics)

            Spacer(ComposeModifier.height(12.dp))

            Row(
                modifier = ComposeModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onTogglePause,
                    modifier = ComposeModifier
                        .weight(1.25f)
                        .height(48.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (metrics.isPaused) R.string.resume_action else R.string.pause_action,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                FilledTonalButton(
                    onClick = onStopRide,
                    modifier = ComposeModifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.reset_action),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(isPaused: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = ComposeModifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = stringResource(R.string.hud_brand_tag),
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary.copy(alpha = 0.8f),
            )
            Text(
                text = stringResource(R.string.hud_brand_name),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                fontWeight = FontWeight.Bold,
            )
        }
        LiveChip(paused = isPaused)
    }
}

@Composable
private fun LiveChip(paused: Boolean) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot",
    )
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Text(
                text = stringResource(if (paused) R.string.pause_badge else R.string.live),
                style = MaterialTheme.typography.labelMedium,
            )
        },
        leadingIcon = {
            Box(
                modifier = ComposeModifier
                    .size(7.dp)
                    .background(
                        (if (paused) Slate300 else NeonLime).copy(alpha = if (paused) 1f else pulse),
                        CircleShape,
                    ),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (paused) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                Color(0xFF064E3B).copy(alpha = 0.55f)
            },
            disabledLabelColor = if (paused) Slate300 else Color(0xFFA7F3D0),
            disabledLeadingIconContentColor = NeonLime,
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = false,
            disabledBorderColor = if (paused) {
                MaterialTheme.colorScheme.outline
            } else {
                NeonLime.copy(alpha = 0.4f)
            },
        ),
    )
}

@Composable
private fun SpeedRing(speedKmh: Float) {
    val colors = MaterialTheme.colorScheme
    val animatedSpeed by animateFloatAsState(
        targetValue = speedKmh,
        animationSpec = tween(durationMillis = SPEED_ANIM_MS, easing = FastOutSlowInEasing),
        label = "speed",
    )
    val animatedProgress by animateFloatAsState(
        targetValue = (speedKmh / MAX_SPEED_RING).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = SPEED_ANIM_MS, easing = FastOutSlowInEasing),
        label = "speedProgress",
    )

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = ComposeModifier
            .fillMaxWidth(0.72f)
            .aspectRatio(1f),
    ) {
        val stroke = 10.dp
        Canvas(modifier = ComposeModifier.fillMaxSize()) {
            val strokePx = stroke.toPx()
            val diameter = min(size.width, size.height) - strokePx
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = colors.primary.copy(alpha = 0.12f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(NeonCyan, SoftSky, SoftTeal, NeonPeach, NeonCyan)),
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.label_speed),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = String.format(Locale.US, "%.1f", animatedSpeed),
                color = colors.onBackground,
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.speed_unit),
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun SecondaryCards(metrics: RideMetrics) {
    val colors = MaterialTheme.colorScheme
    val hrTarget = metrics.heartRateBpm?.toFloat() ?: -1f
    val animatedHr by animateMetricFloat(hrTarget, "hr")
    val hrColorTarget = hrColor(metrics.heartRateBpm)
    val animatedHrColor by animateColorAsState(
        targetValue = hrColorTarget,
        animationSpec = tween(METRIC_ANIM_MS, easing = FastOutSlowInEasing),
        label = "hrColor",
    )

    Row(
        modifier = ComposeModifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            modifier = ComposeModifier.weight(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
        ) {
            Column(modifier = ComposeModifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.label_heart),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(ComposeModifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = if (metrics.heartRateBpm == null) "--" else animatedHr.toInt().coerceAtLeast(0).toString(),
                        color = animatedHrColor,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = stringResource(R.string.hr_unit),
                        style = MaterialTheme.typography.labelMedium,
                        color = animatedHrColor.copy(alpha = 0.8f),
                        modifier = ComposeModifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
        Card(
            modifier = ComposeModifier.weight(1f),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
        ) {
            Column(modifier = ComposeModifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.label_time),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(ComposeModifier.height(6.dp))
                Text(
                    text = formatElapsed(metrics.elapsedMs),
                    color = colors.onSurface,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun TertiaryStrip(metrics: RideMetrics) {
    val colors = MaterialTheme.colorScheme
    val grade = metrics.gradePercent
    val animatedDistanceKm by animateMetricFloat(metrics.distanceM / 1000f, "distance")
    val animatedAvg by animateMetricFloat(metrics.avgSpeedKmh, "avgSpeed")
    val animatedKcal by animateMetricFloat(metrics.caloriesKcal, "kcal")
    val animatedAlt by animateMetricFloat(metrics.altitudeM ?: 0f, "altitude")
    val animatedGrade by animateMetricFloat(grade ?: 0f, "grade")
    val animatedMax by animateMetricFloat(metrics.maxSpeedKmh, "maxSpeed")
    val animatedGain by animateMetricFloat(metrics.elevationGainM, "elevGain")
    val animatedLoss by animateMetricFloat(metrics.elevationLossM, "elevLoss")
    val animatedGradeColor by animateColorAsState(
        targetValue = gradeColor(grade),
        animationSpec = tween(METRIC_ANIM_MS, easing = FastOutSlowInEasing),
        label = "gradeColor",
    )

    Card(
        modifier = ComposeModifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
    ) {
        Column {
            MetricRow(
                items = listOf(
                    MetricCell(
                        stringResource(R.string.distance_label),
                        String.format(Locale.US, "%.2f", animatedDistanceKm),
                        "km",
                    ),
                    MetricCell(
                        stringResource(R.string.avg_speed),
                        String.format(Locale.US, "%.1f", animatedAvg),
                        "km/h",
                    ),
                    MetricCell(
                        stringResource(R.string.calories_label),
                        String.format(Locale.US, "%.0f", animatedKcal),
                        "kcal",
                    ),
                ),
            )
            Box(
                modifier = ComposeModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outline.copy(alpha = 0.35f)),
            )
            MetricRow(
                items = listOf(
                    MetricCell(
                        stringResource(R.string.altitude_label),
                        if (metrics.altitudeM == null) "--" else String.format(Locale.US, "%.0f", animatedAlt),
                        "m",
                    ),
                    MetricCell(
                        stringResource(R.string.grade_label),
                        if (grade == null) "--" else formatGrade(animatedGrade),
                        "%",
                        valueColor = animatedGradeColor,
                    ),
                    MetricCell(
                        stringResource(R.string.max_speed_short),
                        String.format(Locale.US, "%.1f", animatedMax),
                        "km/h",
                    ),
                ),
            )
            Box(
                modifier = ComposeModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.outline.copy(alpha = 0.35f)),
            )
            MetricRow(
                items = listOf(
                    MetricCell(
                        stringResource(R.string.elevation_gain_short),
                        String.format(Locale.US, "%.0f", animatedGain),
                        "m",
                    ),
                    MetricCell(
                        stringResource(R.string.elevation_loss_short),
                        String.format(Locale.US, "%.0f", animatedLoss),
                        "m",
                    ),
                    MetricCell(
                        stringResource(R.string.elevation_net_short),
                        String.format(Locale.US, "%+.0f", animatedGain - animatedLoss),
                        "m",
                    ),
                ),
            )
        }
    }
}

private data class MetricCell(
    val label: String,
    val value: String,
    val unit: String,
    val valueColor: Color? = null,
)

@Composable
private fun MetricRow(items: List<MetricCell>) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = ComposeModifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    modifier = ComposeModifier
                        .width(1.dp)
                        .height(44.dp)
                        .align(Alignment.CenterVertically)
                        .background(colors.outline.copy(alpha = 0.3f)),
                )
            }
            Column(
                modifier = ComposeModifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(ComposeModifier.height(4.dp))
                Text(
                    text = item.value,
                    color = item.valueColor ?: colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    text = item.unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun animateMetricFloat(target: Float, label: String): androidx.compose.runtime.State<Float> {
    return animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = METRIC_ANIM_MS, easing = FastOutSlowInEasing),
        label = label,
    )
}

/**
 * Heart-rate ladder: cooler near primary cyan when easy,
 * warmer complementary accents as intensity rises.
 * <110 soft sky · <135 teal · <155 peach · else rose
 */
private fun hrColor(bpm: Int?): Color {
    if (bpm == null) return Slate300
    return when {
        bpm < 110 -> SoftSky
        bpm < 135 -> SoftTeal
        bpm < 155 -> NeonPeach
        else -> NeonRose
    }
}

private fun formatGrade(grade: Float): String {
    val sign = if (grade >= 0) "+" else ""
    return String.format(Locale.US, "%s%.1f", sign, grade)
}

/** Climb uses cyan's M3 tertiary complement (peach); descend stays in teal family. */
private fun gradeColor(grade: Float?): Color = when {
    grade == null -> Slate300
    grade > 0.5f -> NeonPeach
    grade < -0.5f -> SoftTeal
    else -> SoftSky
}

private fun formatElapsed(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
