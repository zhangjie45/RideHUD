package zj.cursor.ridehud.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zj.cursor.ridehud.R
import zj.cursor.ridehud.domain.RideTrackPoint
import zj.cursor.ridehud.ui.theme.NeonLime
import zj.cursor.ridehud.ui.theme.NeonPeach
import zj.cursor.ridehud.ui.theme.NeonRose
import zj.cursor.ridehud.ui.theme.SoftTeal
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

private const val MAX_CHART_POINTS = 360

private data class ChartSample(val elapsedMs: Long, val value: Float)

private data class ChartGuide(val value: Float, val color: Color, val label: String)

private data class ChartStyle(
    val accent: Color,
    val lineColors: List<Color>,
    val fillTop: Color,
    val fillMid: Color,
)

@Composable
fun SpeedLineChart(
    points: List<RideTrackPoint>,
    avgSpeedKmh: Float,
    maxSpeedKmh: Float,
    modifier: ComposeModifier = ComposeModifier,
) {
    val samples = remember(points) {
        downsampleTrack(points, MAX_CHART_POINTS).map {
            ChartSample(it.elapsedMs, it.speedKmh)
        }
    }
    MetricLineChart(
        title = stringResource(R.string.speed_chart_title),
        unit = stringResource(R.string.speed_unit),
        emptyText = stringResource(R.string.speed_chart_empty),
        samples = samples,
        yMin = 0f,
        yMaxHint = maxSpeedKmh,
        yStep = 5f,
        guides = listOf(
            ChartGuide(avgSpeedKmh, SoftTeal, stringResource(R.string.avg_speed) + " " + fmt1(avgSpeedKmh)),
            ChartGuide(maxSpeedKmh, NeonRose, stringResource(R.string.max_speed) + " " + fmt1(maxSpeedKmh)),
        ),
        style = SharedMetricChartStyle,
        modifier = modifier,
    )
}

@Composable
fun HeartRateLineChart(
    points: List<RideTrackPoint>,
    avgHeartRateBpm: Float?,
    maxHeartRateBpm: Int?,
    modifier: ComposeModifier = ComposeModifier,
) {
    val samples = remember(points) {
        downsampleTrack(points, MAX_CHART_POINTS).mapNotNull { p ->
            p.heartRateBpm?.let { ChartSample(p.elapsedMs, it.toFloat()) }
        }
    }
    val maxHr = maxHeartRateBpm?.toFloat() ?: samples.maxOfOrNull { it.value } ?: 0f
    val avgHr = avgHeartRateBpm ?: 0f
    MetricLineChart(
        title = stringResource(R.string.hr_chart_title),
        unit = stringResource(R.string.hr_unit),
        emptyText = stringResource(R.string.hr_chart_empty),
        samples = samples,
        yMin = null,
        yMaxHint = maxHr,
        yStep = 10f,
        padMin = true,
        guides = buildList {
            if (avgHr > 0f) {
                add(ChartGuide(avgHr, SoftTeal, stringResource(R.string.avg_hr) + " " + avgHr.toInt()))
            }
            if (maxHr > 0f) {
                add(ChartGuide(maxHr, NeonRose, stringResource(R.string.max_hr_short) + " " + maxHr.toInt()))
            }
        },
        style = SharedMetricChartStyle,
        modifier = modifier,
    )
}

@Composable
fun AltitudeLineChart(
    points: List<RideTrackPoint>,
    minAltitudeM: Float?,
    maxAltitudeM: Float?,
    modifier: ComposeModifier = ComposeModifier,
) {
    val samples = remember(points) {
        downsampleTrack(points, MAX_CHART_POINTS).mapNotNull { p ->
            p.altitudeM?.let { ChartSample(p.elapsedMs, it) }
        }
    }
    val maxAlt = maxAltitudeM ?: samples.maxOfOrNull { it.value } ?: 0f
    val minAlt = minAltitudeM ?: samples.minOfOrNull { it.value } ?: 0f
    MetricLineChart(
        title = stringResource(R.string.altitude_chart_title),
        unit = stringResource(R.string.altitude_unit),
        emptyText = stringResource(R.string.altitude_chart_empty),
        samples = samples,
        yMin = null,
        yMaxHint = maxAlt,
        yStep = 10f,
        padMin = true,
        forceYMinHint = minAlt,
        guides = buildList {
            if (maxAlt > minAlt) {
                add(ChartGuide(maxAlt, NeonRose, stringResource(R.string.altitude_max_short) + " " + maxAlt.toInt()))
                add(ChartGuide(minAlt, SoftTeal, stringResource(R.string.altitude_min_short) + " " + minAlt.toInt()))
            }
        },
        style = SharedMetricChartStyle,
        modifier = modifier,
    )
}

/**
 * Intensity ladder by value (low → high): teal → lime → peach → rose.
 * Line color follows the metric value, not elapsed time.
 */
private val SharedMetricChartStyle = ChartStyle(
    accent = NeonLime,
    // low → high intensity
    lineColors = listOf(SoftTeal, NeonLime, NeonPeach, NeonRose),
    fillTop = NeonRose.copy(alpha = 0.22f),
    fillMid = NeonLime.copy(alpha = 0.12f),
)

@Composable
private fun MetricLineChart(
    title: String,
    unit: String,
    emptyText: String,
    samples: List<ChartSample>,
    yMin: Float?,
    yMaxHint: Float,
    yStep: Float,
    guides: List<ChartGuide>,
    style: ChartStyle,
    modifier: ComposeModifier = ComposeModifier,
    padMin: Boolean = false,
    forceYMinHint: Float? = null,
) {
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = ComposeModifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelMedium,
                color = style.accent,
            )
        }
        Spacer(ComposeModifier.height(10.dp))

        if (samples.size < 2) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = ComposeModifier.padding(vertical = 28.dp),
            )
            return
        }

        val dataMin = samples.minOf { it.value }
        val dataMax = max(yMaxHint, samples.maxOf { it.value })
        val minHint = forceYMinHint ?: dataMin
        val axisMin = if (padMin || yMin == null) {
            floor((minHint - yStep) / yStep) * yStep
        } else {
            yMin
        }
        val axisMax = ceil((dataMax + yStep * 0.2f) / yStep) * yStep
        val span = (axisMax - axisMin).coerceAtLeast(yStep)
        val durationMs = samples.last().elapsedMs.coerceAtLeast(1L)
        val gridColor = colors.outline.copy(alpha = 0.28f)
        val mid = (axisMin + axisMax) / 2f

        Row(
            modifier = ComposeModifier
                .fillMaxWidth()
                .height(168.dp),
        ) {
            Column(
                modifier = ComposeModifier
                    .width(38.dp)
                    .fillMaxHeight()
                    .padding(top = 4.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = formatAxisValue(axisMax),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = formatAxisValue(mid),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = formatAxisValue(axisMin),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(ComposeModifier.width(6.dp))
            Canvas(
                modifier = ComposeModifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                val topPad = 4.dp.toPx()
                val bottomPad = 18.dp.toPx()
                val chartH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
                val chartW = size.width.coerceAtLeast(1f)
                val originY = topPad + chartH

                listOf(0f, 0.5f, 1f).forEach { t ->
                    val y = originY - t * chartH
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(chartW, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                fun xOf(elapsedMs: Long): Float =
                    (elapsedMs.toFloat() / durationMs) * chartW

                fun yOf(value: Float): Float =
                    originY - ((value - axisMin) / span) * chartH

                guides.forEachIndexed { index, guide ->
                    if (guide.value !in axisMin..axisMax) return@forEachIndexed
                    val gy = yOf(guide.value)
                    drawLine(
                        color = guide.color.copy(alpha = if (index == 0) 0.8f else 0.6f),
                        start = Offset(0f, gy),
                        end = Offset(chartW, gy),
                        strokeWidth = if (index == 0) 1.5.dp.toPx() else 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            if (index == 0) floatArrayOf(10f, 8f) else floatArrayOf(6f, 8f),
                        ),
                    )
                }

                val fillPath = Path()
                samples.forEachIndexed { index, sample ->
                    val x = xOf(sample.elapsedMs)
                    val y = yOf(sample.value)
                    if (index == 0) {
                        fillPath.moveTo(x, originY)
                        fillPath.lineTo(x, y)
                    } else {
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(xOf(samples.last().elapsedMs), originY)
                fillPath.close()

                // Fill: high values near top use warmer/more vivid tones
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            style.fillTop,
                            style.fillMid,
                            style.lineColors.first().copy(alpha = 0.04f),
                        ),
                        startY = topPad,
                        endY = originY,
                    ),
                )

                // Stroke: color each segment by metric value (not by time)
                val strokeWidth = 2.5.dp.toPx()
                for (i in 1 until samples.size) {
                    val a = samples[i - 1]
                    val b = samples[i]
                    val intensity = (((a.value + b.value) * 0.5f - axisMin) / span).coerceIn(0f, 1f)
                    drawLine(
                        color = colorAtIntensity(intensity, style.lineColors),
                        start = Offset(xOf(a.elapsedMs), yOf(a.value)),
                        end = Offset(xOf(b.elapsedMs), yOf(b.value)),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        Row(
            modifier = ComposeModifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "0:00",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = formatAxisDuration(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (guides.isNotEmpty()) {
            Spacer(ComposeModifier.height(8.dp))
            Row(
                modifier = ComposeModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                guides.forEach { guide ->
                    LegendItem(color = guide.color, label = guide.label)
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = ComposeModifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(ComposeModifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private fun downsampleTrack(points: List<RideTrackPoint>, maxPoints: Int): List<RideTrackPoint> {
    if (points.size <= maxPoints) return points
    val step = (points.size - 1).toFloat() / (maxPoints - 1)
    return List(maxPoints) { i ->
        points[(i * step).toInt().coerceIn(0, points.lastIndex)]
    }
}


private fun colorAtIntensity(t: Float, stops: List<Color>): Color {
    if (stops.isEmpty()) return Color.White
    if (stops.size == 1) return stops[0]
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceIn(0, stops.size - 2)
    return lerp(stops[i], stops[i + 1], x - i)
}

private fun formatAxisDuration(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

private fun formatAxisValue(v: Float): String =
    if (kotlin.math.abs(v - v.toInt()) < 0.05f) v.toInt().toString()
    else String.format(Locale.US, "%.0f", v)

private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
