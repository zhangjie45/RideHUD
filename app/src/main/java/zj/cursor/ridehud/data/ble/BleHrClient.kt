package zj.cursor.ridehud.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import zj.cursor.ridehud.domain.HrConnectionState
import zj.cursor.ridehud.domain.ScannedHrDevice
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects to a BLE Heart Rate Monitor (GATT service 0x180D).
 */
class BleHrClient(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow(HrConnectionState.Disconnected)
    val connectionState: StateFlow<HrConnectionState> = _connectionState.asStateFlow()

    private val _heartRateBpm = MutableStateFlow<Int?>(null)
    val heartRateBpm: StateFlow<Int?> = _heartRateBpm.asStateFlow()

    /** Median + EMA for HUD (HeartRate_Monitor / thud-style display smoothing). */
    private val hrWindow = ArrayDeque<Int>(HR_MEDIAN_WINDOW)
    private var smoothedHr = 0f

    private val _scannedDevices = MutableStateFlow<List<ScannedHrDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedHrDevice>> = _scannedDevices.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val deviceMap = ConcurrentHashMap<String, ScannedHrDevice>()
    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var autoReconnect = false

    @SuppressLint("MissingPermission")
    fun startScan(timeoutMs: Long = 15_000L) {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            scope.launch { _errors.emit("请先打开蓝牙") }
            return
        }
        stopScan()
        deviceMap.clear()
        _scannedDevices.value = emptyList()
        _connectionState.value = HrConnectionState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            // Also scan without filter — some firmware omits service UUID in ads
            scanner.startScan(null, settings, unfilteredScanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "scan failed", e)
            scope.launch { _errors.emit("缺少蓝牙扫描权限") }
            _connectionState.value = HrConnectionState.Disconnected
            return
        }

        scanTimeoutJob = scope.launch {
            delay(timeoutMs)
            stopScan()
            if (_connectionState.value == HrConnectionState.Scanning) {
                _connectionState.value = HrConnectionState.Disconnected
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val scanner = adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
            scanner.stopScan(unfilteredScanCallback)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        autoReconnect = true
        targetAddress = address
        reconnectJob?.cancel()
        reconnectJob = null
        _connectionState.value = HrConnectionState.Connecting
        openConnection(address)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        targetAddress = null
        stopScan()
        closeGatt()
        resetHeartRateFilter()
        _connectionState.value = HrConnectionState.Disconnected
    }

    private fun resetHeartRateFilter() {
        hrWindow.clear()
        smoothedHr = 0f
        _heartRateBpm.value = null
    }

    private fun publishSmoothedHeartRate(rawBpm: Int) {
        if (rawBpm !in HR_MIN..HR_MAX) return
        hrWindow.addLast(rawBpm)
        while (hrWindow.size > HR_MEDIAN_WINDOW) hrWindow.removeFirst()
        val sorted = hrWindow.sorted()
        val median = sorted[sorted.size / 2]
        smoothedHr = if (smoothedHr <= 1f) {
            median.toFloat()
        } else {
            HR_EMA * median + (1f - HR_EMA) * smoothedHr
        }
        _heartRateBpm.value = smoothedHr.toInt().coerceIn(HR_MIN, HR_MAX)
    }

    @SuppressLint("MissingPermission")
    private fun openConnection(address: String) {
        stopScan()
        closeGatt()

        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            scope.launch { _errors.emit("无效的设备地址") }
            _connectionState.value = HrConnectionState.Disconnected
            return
        }

        if (device == null) {
            scope.launch { _errors.emit("蓝牙不可用") }
            _connectionState.value = HrConnectionState.Disconnected
            return
        }

        try {
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            scope.launch { _errors.emit("缺少蓝牙连接权限") }
            _connectionState.value = HrConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
        } catch (_: SecurityException) {
        }
        try {
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
    }

    private fun scheduleReconnect() {
        if (!autoReconnect) return
        val address = targetAddress ?: return
        if (reconnectJob?.isActive == true) return
        _connectionState.value = HrConnectionState.Reconnecting
        reconnectJob = scope.launch {
            var attempt = 0
            while (isActive && autoReconnect) {
                attempt++
                delay(2_000L * attempt.coerceAtMost(5))
                if (!autoReconnect || _connectionState.value == HrConnectionState.Connected) break
                Log.i(TAG, "reconnect attempt $attempt -> $address")
                _connectionState.value = HrConnectionState.Reconnecting
                openConnection(address)
                delay(8_000L)
                if (_connectionState.value == HrConnectionState.Connected) break
            }
        }
    }

    private fun upsertDevice(address: String, name: String?, rssi: Int) {
        val display = name?.takeIf { it.isNotBlank() } ?: "HR $address"
        deviceMap[address] = ScannedHrDevice(address, display, rssi)
        _scannedDevices.value = deviceMap.values.sortedByDescending { it.rssi }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "filtered scan failed: $errorCode")
        }
    }

    private val unfilteredScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            val name = result.device.name ?: result.scanRecord?.deviceName
            val looksLikeHr = uuids.any { it.uuid == HR_SERVICE_UUID } ||
                name?.contains("HR", ignoreCase = true) == true ||
                name?.contains("HEART", ignoreCase = true) == true ||
                name?.contains("WATCH", ignoreCase = true) == true ||
                name?.contains("BAND", ignoreCase = true) == true
            if (looksLikeHr) {
                handleScanResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "unfiltered scan failed: $errorCode")
            scope.launch { _errors.emit("扫描失败 ($errorCode)") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val address = result.device.address ?: return
        val name = try {
            result.device.name ?: result.scanRecord?.deviceName
        } catch (_: SecurityException) {
            result.scanRecord?.deviceName
        }
        upsertDevice(address, name, result.rssi)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services")
                    reconnectJob?.cancel()
                    _connectionState.value = HrConnectionState.Connecting
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected status=$status")
                    resetHeartRateFilter()
                    closeGatt()
                    if (autoReconnect) {
                        scheduleReconnect()
                    } else {
                        _connectionState.value = HrConnectionState.Disconnected
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "service discovery failed: $status")
                scope.launch { _errors.emit("无法发现心率服务") }
                gatt.disconnect()
                return
            }
            val service = gatt.getService(HR_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(HR_MEASUREMENT_UUID)
            if (characteristic == null) {
                scope.launch { _errors.emit("设备未提供心率测量特征") }
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                val writeAccepted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        cccd,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                if (!writeAccepted) {
                    scope.launch { _errors.emit("无法写入心率通知描述符") }
                }
            } else {
                _connectionState.value = HrConnectionState.Connected
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = HrConnectionState.Connected
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "CCCD write failed: $status")
                scope.launch { _errors.emit("订阅心率通知失败") }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_UUID) {
                parseHeartRate(characteristic.value)?.let { bpm ->
                    publishSmoothedHeartRate(bpm)
                    if (_connectionState.value != HrConnectionState.Connected) {
                        _connectionState.value = HrConnectionState.Connected
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == HR_MEASUREMENT_UUID) {
                parseHeartRate(value)?.let { bpm ->
                    publishSmoothedHeartRate(bpm)
                    if (_connectionState.value != HrConnectionState.Connected) {
                        _connectionState.value = HrConnectionState.Connected
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BleHrClient"
        private const val HR_MIN = 35
        private const val HR_MAX = 220
        private const val HR_MEDIAN_WINDOW = 5
        private const val HR_EMA = 0.35f
        val HR_SERVICE_UUID: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun parseHeartRate(value: ByteArray?): Int? {
            if (value == null || value.isEmpty()) return null
            val flags = value[0].toInt() and 0xFF
            val uint16 = flags and 0x01 != 0
            return if (uint16) {
                if (value.size < 3) return null
                (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            } else {
                if (value.size < 2) return null
                value[1].toInt() and 0xFF
            }
        }
    }
}
