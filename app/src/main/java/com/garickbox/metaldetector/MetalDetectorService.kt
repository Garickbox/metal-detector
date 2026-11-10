package com.garickbox.metaldetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

class MetalDetectorService : Service(), SensorEventListener {

    companion object {
        private const val NOTIF_ID = 8801
        private const val CHANNEL = "metal"
        private const val TAG = "MetalDetector"

        val reading: MutableStateFlow<Reading?> = MutableStateFlow(null)
        val isRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)
        val peak: MutableStateFlow<Float> = MutableStateFlow(0f)
        val findCount: MutableStateFlow<Int> = MutableStateFlow(0)

        fun start(ctx: Context) {
            val i = Intent(ctx, MetalDetectorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MetalDetectorService::class.java))
        }
    }

    private lateinit var sm: SensorManager
    private var magSensor: Sensor? = null
    private val pipeline = MagPipeline()
    private var lastVibWarm = 0L
    private var lastVibHot = 0L
    private var lastVibAlarm = 0L
    private var lastSound = 0L
    private var lastCountAt = 0L
    private var toneGen: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magSensor == null) Log.e(TAG, "NO MAGNETOMETER")
        startForeground(NOTIF_ID, buildNotif())
        try { toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }
        catch (e: Exception) { Log.e(TAG, "ToneGenerator fail: ${e.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        magSensor?.let {
            val ok = sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "registerListener=$ok sensor=${it.name}")
        }
        pipeline.resetBaseline()
        peak.value = 0f
        findCount.value = 0
        isRunning.value = true
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val r = pipeline.process(x, y, z)

        if (!pipeline.isBaselineReady()) {
            pipeline.captureBaseline(r.smoothed, x, y, z)
            if (pipeline.isBaselineReady()) {
                Log.d(TAG, "baseline=${pipeline.baseline} sigma=${pipeline.sigma} warm=${pipeline.thresholdWarm} hot=${pipeline.thresholdHot} alarm=${pipeline.thresholdAlarm}")
            }
            reading.value = r.copy(delta = 0f)
            return
        }

        reading.value = r
        if (r.delta > peak.value) peak.value = r.delta

        val now = System.currentTimeMillis()

        // warm: 30ms single, раз в 400ms
        if (r.delta > pipeline.thresholdWarm && now - lastVibWarm > 400) {
            vibratePattern(longArrayOf(0, 30), -1)
            lastVibWarm = now
            bumpCount(now)
            Log.d(TAG, "WARM d=${r.delta}")
        }

        // hot: double, раз в 400ms
        if (r.delta > pipeline.thresholdHot && now - lastVibHot > 400) {
            vibratePattern(longArrayOf(0, 30, 60, 30), -1)
            lastVibHot = now
            bumpCount(now)
            Log.d(TAG, "HOT d=${r.delta}")
        }

        // alarm: triple + sound, раз в 500ms
        if (r.delta > pipeline.thresholdAlarm && now - lastVibAlarm > 500) {
            vibratePattern(longArrayOf(0, 50, 40, 50, 40, 50), -1)
            lastVibAlarm = now
            bumpCount(now)
            Log.d(TAG, "ALARM d=${r.delta}")
        }

        // звук отдельно, раз в 700ms
        if (r.delta > pipeline.thresholdAlarm && now - lastSound > 700) {
            try {
                val pitch = if (r.delta > pipeline.thresholdAlarm * 2) ToneGenerator.TONE_PROP_BEEP2
                            else ToneGenerator.TONE_PROP_BEEP
                toneGen?.startTone(pitch, 120)
            } catch (e: Exception) { Log.e(TAG, "tone fail: ${e.message}") }
            lastSound = now
        }
    }

    private fun bumpCount(now: Long) {
        if (now - lastCountAt > 1500) {
            findCount.value = findCount.value + 1
            lastCountAt = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibratePattern(pattern: LongArray, repeat: Int) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!v.hasVibrator()) { Log.e(TAG, "NO VIBRATOR"); return }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, repeat)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate fail: ${e.message}")
        }
    }

    private fun buildNotif(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Metal", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Metal Detector")
            .setContentText("Сканирую…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        reading.value = null
        isRunning.value = false
        try { toneGen?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
