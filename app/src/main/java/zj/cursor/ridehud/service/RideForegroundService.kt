package zj.cursor.ridehud.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zj.cursor.ridehud.MainActivity
import zj.cursor.ridehud.R
import zj.cursor.ridehud.data.ble.BleHrClient
import zj.cursor.ridehud.data.gps.ElevationTracker
import zj.cursor.ridehud.data.gps.GpsTracker
import zj.cursor.ridehud.data.prefs.RiderPrefs
import zj.cursor.ridehud.data.ride.RideRecorder
import zj.cursor.ridehud.data.ride.RideRepository
import zj.cursor.ridehud.domain.BikeAutoPauseResumeStrategy
import zj.cursor.ridehud.domain.RideHudDefaults
import zj.cursor.ridehud.domain.RideMetrics
import zj.cursor.ridehud.domain.RideSessionStore

class RideForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var bleHrClient: BleHrClient
    private lateinit var gpsTracker: GpsTracker
    private lateinit var elevationTracker: ElevationTracker
    private lateinit var riderPrefs: RiderPrefs
    private lateinit var rideRepository: RideRepository
    private val rideRecorder = RideRecorder()
    private val bikeAutoPause = BikeAutoPauseResumeStrategy()

    private var elapsedJob: Job? = null
    private var sampleJob: Job? = null
    private var rideStartElapsedRealtime = 0L
    private var pausedAccumulatedMs = 0L
    private var pauseStartedElapsed = 0L
    private var isPaused = false
    /** Manual pause must not be cleared by RideHUD auto-resume. */
    private var pauseByUser = false
    private var pauseByAuto = false
    private var rideActive = false
    /** still-timer: continuous still below pause speed → auto-pause after AR_STILL_PAUSE_MS. */
    private var stillBelowSinceElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        bleHrClient = BleHrClient(this, scope)
        gpsTracker = GpsTracker(this)
        elevationTracker = ElevationTracker(this)
        riderPrefs = RiderPrefs(this)
        rideRepository = RideRepository(this)
        observeSources()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRideInternal()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_PAUSE -> {
                togglePause()
                return START_STICKY
            }

            else -> {
                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
                    ?: RideSessionStore.selectedDevice.value?.address
                startAsForeground()
                startRide(address)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRideInternal()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRide(deviceAddress: String?) {
        if (rideActive) return
        RideSessionStore.resetMetrics()
        RideSessionStore.setRiding(true)
        isPaused = false
        pauseByUser = false
        pauseByAuto = false
        rideActive = true
        pausedAccumulatedMs = 0L
        bikeAutoPause.reset()
        stillBelowSinceElapsedMs = 0L
        rideStartElapsedRealtime = SystemClock.elapsedRealtime()
        rideRecorder.start(weightKg = riderPrefs.weightKg)

        elevationTracker.start()
        gpsTracker.start()
        gpsTracker.setCountingDistance(true)
        if (!deviceAddress.isNullOrBlank()) {
            bleHrClient.connect(deviceAddress)
        }

        elapsedJob?.cancel()
        elapsedJob = scope.launch {
            var lastTickElapsed = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastTickElapsed).coerceIn(0L, 1_000L)
                lastTickElapsed = now
                val totalElapsed = now - rideStartElapsedRealtime
                val pausedSoFar = pausedAccumulatedMs +
                    if (isPaused) (now - pauseStartedElapsed) else 0L
                val sportElapsed = (totalElapsed - pausedSoFar).coerceAtLeast(0L)
                RideSessionStore.updateMetrics {
                    it.copy(
                        // 运动时间 — sport duration (excludes pause)
                        elapsedMs = sportElapsed,
                        totalElapsedMs = totalElapsed,
                        // Avg uses 运动时间 exclusively
                        movingElapsedMs = sportElapsed,
                        avgSpeedKmh = RideMetrics.averageSpeedKmh(it.distanceM, sportElapsed),
                        isPaused = isPaused,
                    )
                }
                delay(200L)
            }
        }

        sampleJob?.cancel()
        sampleJob = scope.launch {
            while (isActive && rideActive) {
                if (!isPaused) {
                    rideRecorder.onTick(RideSessionStore.metrics.value)
                    RideSessionStore.updateMetrics {
                        it.copy(
                            caloriesKcal = rideRecorder.currentCaloriesKcal(),
                            activeCaloriesKcal = rideRecorder.currentActiveCaloriesKcal(),
                            maxSpeedKmh = rideRecorder.currentMaxSpeedKmh(),
                        )
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun togglePause() {
        if (!RideSessionStore.isRiding.value) return
        if (isPaused) {
            // User explicitly resumes (clears both auto and manual)
            applyPause(paused = false, byUser = false, byAuto = false)
        } else {
            applyPause(paused = true, byUser = true, byAuto = false)
        }
        updateNotification()
    }

    private fun applyPause(paused: Boolean, byUser: Boolean, byAuto: Boolean) {
        if (paused == isPaused && pauseByUser == byUser && pauseByAuto == byAuto) return
        if (paused && !isPaused) {
            pauseStartedElapsed = SystemClock.elapsedRealtime()
            isPaused = true
            pauseByUser = byUser
            pauseByAuto = byAuto
            gpsTracker.setCountingDistance(false)
            elevationTracker.setMovingForGainLoss(false)
        } else if (!paused && isPaused) {
            pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartedElapsed
            isPaused = false
            pauseByUser = false
            pauseByAuto = false
            gpsTracker.setCountingDistance(true)
            elevationTracker.setMovingForGainLoss(true)
        } else {
            pauseByUser = byUser
            pauseByAuto = byAuto
        }
        RideSessionStore.updateMetrics { it.copy(isPaused = isPaused) }
    }

    private fun stopRideInternal() {
        if (!rideActive) {
            elapsedJob?.cancel()
            sampleJob?.cancel()
            elapsedJob = null
            sampleJob = null
            bleHrClient.disconnect()
            gpsTracker.stop()
            elevationTracker.stop()
            RideSessionStore.setRiding(false)
            RideSessionStore.setHrState(zj.cursor.ridehud.domain.HrConnectionState.Disconnected)
            return
        }
        rideActive = false
        elapsedJob?.cancel()
        sampleJob?.cancel()
        elapsedJob = null
        sampleJob = null

        val finalMetrics = RideSessionStore.metrics.value
        val record = rideRecorder.finish(finalMetrics)
        bleHrClient.disconnect()
        gpsTracker.stop()
        elevationTracker.stop()
        RideSessionStore.setRiding(false)
        RideSessionStore.setHrState(zj.cursor.ridehud.domain.HrConnectionState.Disconnected)

        if (record != null && (record.elapsedMs >= 10_000L || record.distanceM >= 50f)) {
            scope.launch(Dispatchers.IO) {
                rideRepository.save(record)
                withContext(Dispatchers.Main) {
                    RideSessionStore.setLastSavedRideId(record.id)
                    RideSessionStore.setStatusMessage(
                        getString(R.string.ride_saved_toast, record.caloriesKcal.toInt()),
                    )
                }
            }
        }
    }

    private fun observeSources() {
        scope.launch {
            bleHrClient.connectionState.collectLatest { state ->
                RideSessionStore.setHrState(state)
                updateNotification()
            }
        }
        scope.launch {
            bleHrClient.heartRateBpm.collectLatest { bpm ->
                RideSessionStore.updateMetrics { it.copy(heartRateBpm = bpm) }
            }
        }
        scope.launch {
            bleHrClient.scannedDevices.collectLatest { devices ->
                RideSessionStore.setScannedDevices(devices)
            }
        }
        scope.launch {
            bleHrClient.errors.collectLatest { message ->
                RideSessionStore.setStatusMessage(message)
            }
        }
        scope.launch {
            gpsTracker.sample.collectLatest { sample ->
                // Always evaluate bike auto-pause/resume (even while paused)
                when (bikeAutoPause.onSpeedKmh(sample.speedKmh)) {
                    BikeAutoPauseResumeStrategy.Action.Pause -> {
                        if (!isPaused) {
                            applyPause(paused = true, byUser = false, byAuto = true)
                            updateNotification()
                        }
                    }
                    BikeAutoPauseResumeStrategy.Action.Resume -> {
                        if (isPaused && pauseByAuto && !pauseByUser) {
                            applyPause(paused = false, byUser = false, byAuto = false)
                            updateNotification()
                        }
                    }
                    BikeAutoPauseResumeStrategy.Action.None -> Unit
                }
                // jdh AR still: < pause speed for 10s → auto-pause (covers sparse GPS)
                val nowElapsed = SystemClock.elapsedRealtime()
                if (sample.speedKmh < RideHudDefaults.bikePauseEnterKmh) {
                    if (stillBelowSinceElapsedMs == 0L) {
                        stillBelowSinceElapsedMs = nowElapsed
                    } else if (!isPaused &&
                        nowElapsed - stillBelowSinceElapsedMs >= RideHudDefaults.AR_STILL_PAUSE_MS
                    ) {
                        applyPause(paused = true, byUser = false, byAuto = true)
                        updateNotification()
                    }
                } else {
                    stillBelowSinceElapsedMs = 0L
                }

                RideSessionStore.updateMetrics {
                    it.copy(
                        speedKmh = sample.speedKmh,
                        distanceM = sample.distanceM,
                        avgSpeedKmh = RideMetrics.averageSpeedKmh(
                            sample.distanceM,
                            it.elapsedMs,
                        ),
                        // maxSpeed updated on 1s tick via RideRecorder (5s max-speed sample)
                    )
                }

                if (!isPaused) {
                    sample.location?.let { location ->
                        elevationTracker.onLocation(
                            location,
                            sample.distanceM,
                            sample.speedKmh,
                        )
                    }
                }
            }
        }
        elevationTracker.onSample = { sample ->
            if (!isPaused) {
                RideSessionStore.updateMetrics {
                    it.copy(
                        altitudeM = sample.altitudeM,
                        gradePercent = sample.gradePercent,
                        elevationGainM = sample.elevationGainM,
                        elevationLossM = sample.elevationLossM,
                        hasBarometer = sample.hasBarometer,
                    )
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ride_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ride_notification_title))
            .setContentText(getString(R.string.ride_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_ride), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_START = "zj.cursor.ridehud.action.START"
        const val ACTION_STOP = "zj.cursor.ridehud.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "zj.cursor.ridehud.action.TOGGLE_PAUSE"
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        private const val CHANNEL_ID = "ride_hud"
        private const val NOTIFICATION_ID = 42

        fun startIntent(context: Context, deviceAddress: String?): Intent =
            Intent(context, RideForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, RideForegroundService::class.java).apply {
                action = ACTION_STOP
            }

        fun togglePauseIntent(context: Context): Intent =
            Intent(context, RideForegroundService::class.java).apply {
                action = ACTION_TOGGLE_PAUSE
            }
    }
}
