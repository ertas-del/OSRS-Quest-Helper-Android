package com.questoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/**
 * "Open bubble": puts the floating card (or just the small bubble) over the game without having to
 * pick a quest first. It reopens on the quest you were last on.
 */
object OverlayLauncher {
    fun show(activity: Activity, smallBubble: Boolean = false) {
        if (!Settings.canDrawOverlays(activity)) {
            Toast.makeText(activity, "Allow \"Display over other apps\" for Breadcrumbs first.", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            } catch (e: Exception) {
            }
            return
        }
        val i = Intent(activity, OverlayService::class.java)
            .setAction(OverlayService.ACTION_SHOW)
            .putExtra(OverlayService.EXTRA_COLLAPSED, smallBubble)
        activity.startForegroundService(i)
        Toast.makeText(
            activity,
            if (smallBubble) "Small bubble is up. Open the game; tap it to open the card."
            else "Bubble is up. Open the game; drag the card by its title.",
            Toast.LENGTH_LONG
        ).show()
    }
}
