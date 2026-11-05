package com.example.crackprank

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Restarts drop detection after a reboot, but only if the user already granted the overlay permission once. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(context, Intent(context, DropDetectionService::class.java))
        }
    }
}
