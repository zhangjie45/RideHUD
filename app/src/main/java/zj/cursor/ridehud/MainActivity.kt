package zj.cursor.ridehud

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier as ComposeModifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import zj.cursor.ridehud.domain.RideSessionStore
import zj.cursor.ridehud.service.RideForegroundService
import zj.cursor.ridehud.ui.connect.ConnectScreen
import zj.cursor.ridehud.ui.history.HistoryScreen
import zj.cursor.ridehud.ui.hud.HudScreen
import zj.cursor.ridehud.ui.theme.RideHUDTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RideHUDTheme {
                RideHudApp(
                    modifier = ComposeModifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    onStartRide = { address -> startRideService(address) },
                    onStopRide = { stopRideService() },
                    onTogglePause = { togglePauseService() },
                )
            }
        }
    }

    private fun startRideService(deviceAddress: String?) {
        val intent = RideForegroundService.startIntent(this, deviceAddress)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRideService() {
        startService(RideForegroundService.stopIntent(this))
    }

    private fun togglePauseService() {
        startService(RideForegroundService.togglePauseIntent(this))
    }
}

private enum class AppScreen {
    Connect,
    History,
}

@Composable
private fun RideHudApp(
    modifier: ComposeModifier,
    onStartRide: (String?) -> Unit,
    onStopRide: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val isRiding by RideSessionStore.isRiding.collectAsStateWithLifecycle()
    val lastSavedRideId by RideSessionStore.lastSavedRideId.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(AppScreen.Connect) }
    var historyFocusId by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier) {
        when {
            isRiding -> {
                HudScreen(
                    onStopRide = onStopRide,
                    onTogglePause = onTogglePause,
                )
            }

            screen == AppScreen.History -> {
                HistoryScreen(
                    initialRideId = historyFocusId,
                    onBack = {
                        historyFocusId = null
                        screen = AppScreen.Connect
                    },
                )
            }

            else -> {
                ConnectScreen(
                    onStartRide = onStartRide,
                    onOpenHistory = {
                        historyFocusId = lastSavedRideId
                        screen = AppScreen.History
                    },
                )
            }
        }
    }
}
