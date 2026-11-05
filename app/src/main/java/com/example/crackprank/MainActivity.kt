package com.example.crackprank

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Minimal control panel:
 *  - grant "draw over other apps" permission (required for the overlay)
 *  - ask to be exempted from battery optimization (so the drop sensor keeps running)
 *  - start/stop the background drop-detection service
 *  - fire a test crack so you can see it without actually dropping the phone
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        val title = TextView(this).apply {
            text = "CrackPrank"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }

        val grantOverlayBtn = Button(this).apply {
            text = "1. Разрешить наложение поверх экрана"
            setOnClickListener { requestOverlayPermission() }
        }

        val batteryBtn = Button(this).apply {
            text = "2. Отключить оптимизацию батареи"
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }

        val startBtn = Button(this).apply {
            text = "3. Запустить отслеживание падения"
            setOnClickListener {
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, DropDetectionService::class.java)
                )
                refreshStatus()
            }
        }

        val stopBtn = Button(this).apply {
            text = "Остановить"
            setOnClickListener {
                stopService(Intent(this@MainActivity, DropDetectionService::class.java))
                refreshStatus()
            }
        }

        val testBtn = Button(this).apply {
            text = "Тест: показать трещину сейчас"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    requestOverlayPermission()
                } else {
                    startService(
                        Intent(this@MainActivity, DropDetectionService::class.java)
                            .setAction(DropDetectionService.ACTION_TEST_TRIGGER)
                    )
                }
            }
        }

        val hint = TextView(this).apply {
            setPadding(0, 48, 0, 0)
            text = "Чтобы убрать трещину — зажми обе кнопки громкости одновременно " +
                "на 3+ секунды."
        }

        for (v in listOf(title, statusText, grantOverlayBtn, batteryBtn, startBtn, stopBtn, testBtn, hint)) {
            root.addView(v, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        statusText.text = buildString {
            append(if (overlayOk) "✅ Overlay permission: OK\n" else "❌ Overlay permission: нужно выдать\n")
            append(if (batteryOk) "✅ Battery optimization: OK" else "⚠️ Battery optimization: рекомендуется отключить")
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }
}
