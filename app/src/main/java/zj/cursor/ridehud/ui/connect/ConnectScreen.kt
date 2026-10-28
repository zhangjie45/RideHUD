package zj.cursor.ridehud.ui.connect

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import zj.cursor.ridehud.R
import zj.cursor.ridehud.data.ble.BleHrClient
import zj.cursor.ridehud.domain.HrConnectionState
import zj.cursor.ridehud.domain.RideSessionStore
import zj.cursor.ridehud.domain.ScannedHrDevice
import zj.cursor.ridehud.ui.components.HudBackdrop
import zj.cursor.ridehud.util.RidePermissions

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(
    onStartRide: (deviceAddress: String?) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bleClient = remember { BleHrClient(context, scope) }
    val colors = MaterialTheme.colorScheme

    val scanned by bleClient.scannedDevices.collectAsStateWithLifecycle()
    val hrState by bleClient.connectionState.collectAsStateWithLifecycle()
    val selected by RideSessionStore.selectedDevice.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(RidePermissions.allGranted(context)) }
    var status by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission = result.values.all { it } || RidePermissions.allGranted(context)
        if (!hasPermission) status = context.getString(R.string.permission_needed)
    }

    LaunchedEffect(Unit) { bleClient.errors.collectLatest { status = it } }
    DisposableEffect(Unit) {
        onDispose {
            bleClient.stopScan()
            if (hrState != HrConnectionState.Connected) bleClient.disconnect()
        }
    }

    HudBackdrop {
        Column(
            modifier = ComposeModifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Row(
                modifier = ComposeModifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.hud_brand_tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary.copy(alpha = 0.85f),
                    )
                    Text(
                        text = stringResource(R.string.hud_brand_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onBackground,
                    )
                }
                TextButton(onClick = onOpenHistory) {
                    Text(stringResource(R.string.records))
                }
            }

            Spacer(ComposeModifier.height(12.dp))

            Card(
                modifier = ComposeModifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
            ) {
                Column(modifier = ComposeModifier.padding(16.dp)) {
                    Text(
                        text = "CONNECT",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(ComposeModifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.watch_tip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                }
            }

            Spacer(ComposeModifier.height(12.dp))

            if (!hasPermission) {
                Button(
                    onClick = { permissionLauncher.launch(RidePermissions.required()) },
                    modifier = ComposeModifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.grant_permissions))
                }
            } else {
                Row(
                    modifier = ComposeModifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            status = null
                            bleClient.startScan()
                        },
                        enabled = hrState != HrConnectionState.Scanning,
                        modifier = ComposeModifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.primaryContainer,
                            contentColor = colors.onPrimaryContainer,
                            disabledContainerColor = colors.primaryContainer.copy(alpha = 0.35f),
                            disabledContentColor = colors.onPrimaryContainer.copy(alpha = 0.55f),
                        ),
                    ) {
                        Text(
                            if (hrState == HrConnectionState.Scanning) {
                                stringResource(R.string.scanning)
                            } else {
                                stringResource(R.string.scan_devices)
                            },
                        )
                    }
                    selected?.let {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(it.name, maxLines = 1) },
                        )
                    }
                }

                Spacer(ComposeModifier.height(12.dp))

                if (scanned.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_devices),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(ComposeModifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = ComposeModifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(scanned, key = { it.address }) { device ->
                            DeviceCard(
                                device = device,
                                selected = selected?.address == device.address,
                                onClick = {
                                    RideSessionStore.selectDevice(device)
                                    status = null
                                },
                            )
                        }
                    }
                }
            }

            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = ComposeModifier.padding(vertical = 6.dp),
                )
            }

            Button(
                onClick = {
                    if (!RidePermissions.allGranted(context)) {
                        permissionLauncher.launch(RidePermissions.required())
                        return@Button
                    }
                    bleClient.stopScan()
                    onStartRide(selected?.address)
                },
                enabled = hasPermission,
                modifier = ComposeModifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = stringResource(R.string.start_ride),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = stringResource(R.string.start_without_hr),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = ComposeModifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: ScannedHrDevice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = ComposeModifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 8.dp else 3.dp,
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surface,
        ),
    ) {
        Row(
            modifier = ComposeModifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.75f) else colors.onSurfaceVariant,
                )
            }
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}
