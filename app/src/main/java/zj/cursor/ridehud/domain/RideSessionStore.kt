package zj.cursor.ridehud.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared ride state between the foreground service and Compose UI.
 */
object RideSessionStore {
    private val _metrics = MutableStateFlow(RideMetrics())
    val metrics: StateFlow<RideMetrics> = _metrics.asStateFlow()

    private val _hrState = MutableStateFlow(HrConnectionState.Disconnected)
    val hrState: StateFlow<HrConnectionState> = _hrState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedHrDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedHrDevice>> = _scannedDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<ScannedHrDevice?>(null)
    val selectedDevice: StateFlow<ScannedHrDevice?> = _selectedDevice.asStateFlow()

    private val _isRiding = MutableStateFlow(false)
    val isRiding: StateFlow<Boolean> = _isRiding.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _lastSavedRideId = MutableStateFlow<String?>(null)
    val lastSavedRideId: StateFlow<String?> = _lastSavedRideId.asStateFlow()

    fun updateMetrics(transform: (RideMetrics) -> RideMetrics) {
        _metrics.update(transform)
    }

    fun setMetrics(metrics: RideMetrics) {
        _metrics.value = metrics
    }

    fun setHrState(state: HrConnectionState) {
        _hrState.value = state
    }

    fun setScannedDevices(devices: List<ScannedHrDevice>) {
        _scannedDevices.value = devices
    }

    fun selectDevice(device: ScannedHrDevice?) {
        _selectedDevice.value = device
    }

    fun setRiding(riding: Boolean) {
        _isRiding.value = riding
    }

    fun setStatusMessage(message: String?) {
        _statusMessage.value = message
    }

    fun setLastSavedRideId(id: String?) {
        _lastSavedRideId.value = id
    }

    fun resetMetrics() {
        _metrics.value = RideMetrics()
        _statusMessage.value = null
    }

    fun reset() {
        resetMetrics()
        _hrState.value = HrConnectionState.Disconnected
        _isRiding.value = false
    }
}
