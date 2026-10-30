package zj.cursor.ridehud.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zj.cursor.ridehud.R
import zj.cursor.ridehud.data.prefs.RiderPrefs
import zj.cursor.ridehud.data.ride.RideExporter
import zj.cursor.ridehud.data.ride.RideRepository
import zj.cursor.ridehud.domain.RideRecord
import zj.cursor.ridehud.ui.components.HudBackdrop
import zj.cursor.ridehud.ui.components.AltitudeLineChart
import zj.cursor.ridehud.ui.components.HeartRateLineChart
import zj.cursor.ridehud.ui.components.SpeedLineChart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    initialRideId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { RideRepository(context) }
    val exporter = remember { RideExporter(context) }
    val prefs = remember { RiderPrefs(context) }
    val colors = MaterialTheme.colorScheme

    var rides by remember { mutableStateOf<List<RideRecord>>(emptyList()) }
    var selectedId by remember { mutableStateOf(initialRideId) }
    var detail by remember { mutableStateOf<RideRecord?>(null) }
    var showWeightDialog by remember { mutableStateOf(false) }
    var showWatchDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var weightText by remember { mutableStateOf(prefs.weightKg.toInt().toString()) }
    var watchText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun reloadList() {
        scope.launch {
            rides = withContext(Dispatchers.IO) { repo.listSummaries() }
        }
    }

    fun exportCsv(record: RideRecord) {
        scope.launch {
            val full = withContext(Dispatchers.IO) { repo.load(record.id) ?: record }
            val file = withContext(Dispatchers.IO) { exporter.exportCsv(full) }
            exporter.shareFile(file, "text/csv")
        }
    }

    fun exportJson(record: RideRecord) {
        scope.launch {
            val full = withContext(Dispatchers.IO) { repo.load(record.id) ?: record }
            val file = withContext(Dispatchers.IO) { exporter.exportJson(full, repo) }
            exporter.shareFile(file, "application/json")
        }
    }

    LaunchedEffect(Unit) { reloadList() }
    LaunchedEffect(selectedId) {
        detail = if (selectedId == null) null
        else withContext(Dispatchers.IO) { repo.load(selectedId!!) }
    }

    HudBackdrop {
        Column(modifier = ComposeModifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.history_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.history_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedId != null) selectedId = null else onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (detail != null && selectedId != null) {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.watch_calories_label)) },
                                onClick = {
                                    showMoreMenu = false
                                    watchText = detail?.watchCaloriesKcal?.toInt()?.toString().orEmpty()
                                    showWatchDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_csv)) },
                                onClick = {
                                    showMoreMenu = false
                                    detail?.let { exportCsv(it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_json)) },
                                onClick = {
                                    showMoreMenu = false
                                    detail?.let { exportJson(it) }
                                },
                            )
                        }
                    }
                    IconButton(onClick = {
                        weightText = prefs.weightKg.toInt().toString()
                        showWeightDialog = true
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.weight_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfaceContainerLow.copy(alpha = 0.92f),
                    titleContentColor = colors.onSurface,
                    navigationIconContentColor = colors.onSurface,
                    actionIconContentColor = colors.onSurfaceVariant,
                ),
            )

            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = ComposeModifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            val currentDetail = detail
            when {
                selectedId != null && currentDetail != null -> {
                    DetailContent(
                        record = currentDetail,
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.delete(currentDetail.id) }
                                selectedId = null
                                reloadList()
                            }
                        },
                    )
                }

                rides.isEmpty() -> {
                    Column(
                        modifier = ComposeModifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = ComposeModifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(rides, key = { it.id }) { ride ->
                            RideListCard(ride = ride, onClick = { selectedId = ride.id })
                        }
                    }
                }
            }
        }
    }

    if (showWeightDialog) {
        AlertDialog(
            onDismissRequest = { showWeightDialog = false },
            title = { Text(stringResource(R.string.weight_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.weight_settings_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text(stringResource(R.string.weight_kg_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = ComposeModifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        weightText.toFloatOrNull()?.let {
                            prefs.weightKg = it
                            message = context.getString(R.string.weight_saved)
                        }
                        showWeightDialog = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWeightDialog = false }) {
                    Text(stringResource(R.string.back))
                }
            },
        )
    }

    if (showWatchDialog && detail != null) {
        val current = detail!!
        AlertDialog(
            onDismissRequest = { showWatchDialog = false },
            title = { Text(stringResource(R.string.watch_calories_label)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.watch_calories_dialog_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = watchText,
                        onValueChange = { watchText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text(stringResource(R.string.watch_calories_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = ComposeModifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val updated = withContext(Dispatchers.IO) {
                                repo.updateWatchCalories(current.id, watchText.toFloatOrNull())
                            }
                            detail = updated
                            message = context.getString(R.string.watch_calories_saved)
                            reloadList()
                        }
                        showWatchDialog = false
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showWatchDialog = false }) {
                    Text(stringResource(R.string.back))
                }
            },
        )
    }
}

@Composable
private fun RideListCard(ride: RideRecord, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = ComposeModifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp, pressedElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = formatDateTime(ride.startEpochMs),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            supportingContent = {
                Text(
                    text = stringResource(
                        R.string.history_item_summary,
                        formatDuration(ride.elapsedMs),
                        String.format(Locale.US, "%.2f", ride.distanceM / 1000f),
                        ride.caloriesKcal.toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            trailingContent = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = String.format(Locale.US, "%.1f", ride.avgSpeedKmh),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.speed_unit),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        )
    }
}

@Composable
private fun DetailContent(
    record: RideRecord,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = ComposeModifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = ComposeModifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        ) {
            Column(
                modifier = ComposeModifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = formatDateTime(record.startEpochMs),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Metric(stringResource(R.string.duration), formatDuration(record.elapsedMs))
                Metric(
                    stringResource(R.string.total_duration),
                    formatDuration(
                        if (record.totalElapsedMs > 0L) record.totalElapsedMs else record.elapsedMs,
                    ),
                )
                Metric(stringResource(R.string.distance_label), String.format(Locale.US, "%.2f km", record.distanceM / 1000f))
                Metric(stringResource(R.string.calories_label), String.format(Locale.US, "%.0f kcal", record.caloriesKcal))
                Metric(
                    stringResource(R.string.active_calories_label),
                    String.format(Locale.US, "%.0f kcal", record.activeCaloriesKcal),
                )
                Metric(stringResource(R.string.avg_speed), String.format(Locale.US, "%.1f km/h", record.avgSpeedKmh))
                Metric(stringResource(R.string.max_speed), String.format(Locale.US, "%.1f km/h", record.maxSpeedKmh))
                Metric(
                    stringResource(R.string.elevation_gain),
                    String.format(Locale.US, "%.1f m", record.elevationGainM),
                )
                Metric(
                    stringResource(R.string.elevation_loss),
                    String.format(Locale.US, "%.1f m", record.elevationLossM),
                )
            }
        }

        Card(
            modifier = ComposeModifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        ) {
            SpeedLineChart(
                points = record.trackPoints,
                avgSpeedKmh = record.avgSpeedKmh,
                maxSpeedKmh = record.maxSpeedKmh,
                modifier = ComposeModifier.padding(16.dp),
            )
        }

        Card(
            modifier = ComposeModifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        ) {
            HeartRateLineChart(
                points = record.trackPoints,
                avgHeartRateBpm = record.avgHeartRateBpm,
                maxHeartRateBpm = record.maxHeartRateBpm,
                modifier = ComposeModifier.padding(16.dp),
            )
        }

        Card(
            modifier = ComposeModifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        ) {
            AltitudeLineChart(
                points = record.trackPoints,
                minAltitudeM = record.minAltitudeM,
                maxAltitudeM = record.maxAltitudeM,
                modifier = ComposeModifier.padding(16.dp),
            )
        }

        OutlinedButton(onClick = onDelete, modifier = ComposeModifier.fillMaxWidth()) {
            Text(stringResource(R.string.delete_ride))
        }
        Spacer(ComposeModifier.height(8.dp))
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        modifier = ComposeModifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun formatDuration(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
