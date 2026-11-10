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
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MetalDetectorService : Service(), SensorEventListener {

    companion object {
        private const val NOTIF_ID = 8801
        private const val CHANNEL = "metal"

        val reading: MutableStateFlow<Reading?> = MutableStateFlow(null)
        val isRunning: MutableStateFlow<Boolean> = MutableStateFlow(false)

        const val VIB_HOT = 20f
        const val VIB_ALARM = 50f
        const val SOUND_ALARM = 100f

        fun start(ctx: Context) {
            val i = Intent(ctx, MetalDetectorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MetalDetectorService::class.java))
        }
    }

    private lateinit var sm: SensorManager
    private var magSensor: Sensor? = null
    private val pipeline = MagPipeline()
    private var lastVib = 0L
    private var lastAlarm = 0L

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        startForeground(NOTIF_ID, buildNotif())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        magSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        pipeline.resetBaseline()
        isRunning.value = true
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val r = pipeline.process(event.values[0], event.values[1], event.values[2])

        if (!pipeline.isBaselineReady()) {
            pipeline.captureBaseline(r.smoothed)
        }

        reading.value = r

        val now = System.currentTimeMillis()
        if (r.delta > VIB_HOT && now - lastVib > 300) {
            vibrate(if (r.delta > VIB_ALARM) 200L else 60L)
            lastVib = now
        }
        if (r.delta > SOUND_ALARM && now - lastAlarm > 800) {
            // без звука в MVP — только сильная вибрация
            vibrate(400L)
            lastAlarm = now
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibrate(ms: Long) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        } catch (_: Exception) {}
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
            .setContentText("Сканирую магнитометр…")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        reading.value = null
        isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
