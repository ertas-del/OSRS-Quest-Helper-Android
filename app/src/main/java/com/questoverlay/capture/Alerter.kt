package com.questoverlay.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.questoverlay.MainActivity

/**
 * Gets your attention for an AFK alert: a buzz, and a pop-up notification you'll see even if
 * you've wandered off. (The spoken part goes through [Speaker], which respects the mute button.)
 */
class Alerter(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var nextId = 100

    init {
        val channel = NotificationChannel(CHANNEL, "AFK alerts", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Cargo hold full, inventory full, shipwreck finished and your own alerts."
        channel.enableVibration(true)
        channel.vibrationPattern = PATTERN
        nm.createNotificationChannel(channel)
    }

    fun fire(title: String, detail: String) {
        buzz()
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(detail)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            // Keep the last few on screen; each alert gets its own slot.
            nm.notify(100 + (nextId++ % 5), n)
        } catch (e: SecurityException) {
            // Notifications are switched off for the app; the buzz and voice still happen.
        }
    }

    private fun buzz() {
        try {
            val vm = context.getSystemService(VibratorManager::class.java)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            @Suppress("DEPRECATION")
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(PATTERN, -1), attrs)
        } catch (e: Exception) {
            // No vibrator, or not allowed.
        }
    }

    companion object {
        const val CHANNEL = "afk_alerts"
        private val PATTERN = longArrayOf(0, 400, 150, 400, 150, 600)
    }
}
