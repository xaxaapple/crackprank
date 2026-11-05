package com.example.crackprank

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * Runs in the background (foreground service, so it survives Doze) and watches
 * the accelerometer for a classic "drop" signature:
 *
 *   1. FREE FALL  — acceleration magnitude drops near 0 m/s^2 (the phone is in
 *      the air, not resting on/against anything) for a short sustained window.
 *   2. IMPACT     — within ~1s after that, a sharp spike in magnitude hits
 *      (the phone lands on something — even something soft like a couch
 *      produces a clear spike, just a lower one than a hard floor).
 *
 * This threshold-based free-fall + impact-spike approach is the standard
 * technique used in both commercial drop-protection systems and published
 * fall-detection research (see e.g. "Evaluation of Threshold-based Fall
 * Detection on Android Smartphones", SciTePress 2015).
 *
 * On a confirmed drop it adds [CrackOverlayView] as a system-alert-window
 * overlay above every other app, at a random screen position, with no further
 * input required.
 */
class DropDetectionService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "DropDetectionService"
        const val ACTION_TEST_TRIGGER = "com.example.crackprank.action.TEST_TRIGGER"

        private const val CHANNEL_ID = "drop_watch"
        private const val NOTIF_ID = 1001

        // m/s^2. Earth gravity ~9.81; during true free fall the accelerometer
        // reads close to 0 on all axes because it measures proper acceleration.
        private const val FREE_FALL_THRESHOLD = 2.5f
        private const val FREE_FALL_MIN_DURATION_MS = 80L

        // A couch/mattress landing still produces several g of deceleration
        // spike; a hard floor is much higher. We aim low enough to catch a
        // soft landing but high enough to ignore normal handling/walking.
        private const val IMPACT_THRESHOLD = 18f

        // How long after free fall ends we still accept an impact spike as
        // belonging to the same drop event.
        private const val IMPACT_WINDOW_MS = 1200L

        // Don't re-trigger again immediately after showing the overlay.
        private const val COOLDOWN_MS = 4000L
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private var inFreeFall = false
    private var freeFallStartedAt = 0L
    private var freeFallQualifiedAt = 0L // set once min duration satisfied
    private var lastTriggerAt = 0L

    private var windowManager: WindowManager? = null
    private var overlayView: CrackOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForegroundCompat()

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(TAG, "No accelerometer on this device — drop detection disabled.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_TRIGGER) {
            showCrackOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        removeCrackOverlay()
        super.onDestroy()
    }

    // --- sensor handling --------------------------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        if (magnitude < FREE_FALL_THRESHOLD) {
            if (!inFreeFall) {
                inFreeFall = true
                freeFallStartedAt = now
                freeFallQualifiedAt = 0L
            } else if (freeFallQualifiedAt == 0L && now - freeFallStartedAt >= FREE_FALL_MIN_DURATION_MS) {
                freeFallQualifiedAt = now
            }
            return
        }

        // Not currently near-zero-g: check whether this is the impact that
        // follows a qualified free fall.
        if (inFreeFall) {
            val fellLongEnough = freeFallQualifiedAt != 0L
            inFreeFall = false
            if (fellLongEnough &&
                magnitude >= IMPACT_THRESHOLD &&
                now - freeFallQualifiedAt <= IMPACT_WINDOW_MS
            ) {
                maybeTrigger(now)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun maybeTrigger(now: Long) {
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now
        Log.i(TAG, "Drop detected -> showing crack overlay")
        showCrackOverlay()
    }

    // --- overlay ------------------------------------------------------------------------------

    private fun showCrackOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission, can't show crack.")
            return
        }
        if (overlayView != null) return // already showing

        vibrate()

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Focusable (so it receives the volume-key dismiss combo) but
            // NOT touchable, so the phone stays fully usable underneath —
            // and NOT_TOUCH_MODAL so touches outside this window still
            // reach whatever's below it instead of being swallowed.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = CrackOverlayView(this) { removeCrackOverlay() }
        overlayView = view
        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay", e)
            overlayView = null
        }
    }

    private fun removeCrackOverlay() {
        val view = overlayView ?: return
        view.onRemoved()
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay", e)
        }
        overlayView = null
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(250)
        }
    }

    // --- foreground service plumbing -----------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Отслеживание падения",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Фоновая служба следит за падением телефона" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CrackPrank активен")
            .setContentText("Следим за падением телефона")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
