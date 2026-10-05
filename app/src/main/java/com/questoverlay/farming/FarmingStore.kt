package com.questoverlay.farming

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import org.json.JSONArray
import org.json.JSONObject

/** One growing crop (or a batch planted together, like a herb run). */
data class FarmTimer(
    val id: Long,
    val crop: String,
    val count: Int,
    val plantedAt: Long,
    val readyAt: Long,
    /** Where it was planted, when known ("Falador park"). */
    val place: String = ""
) {
    val label: String get() = (if (count > 1) "$count× " else "") + crop + if (place.isNotBlank()) " · $place" else ""
}

/**
 * Farming timers, saved on the phone. Each one has an alarm that posts a "ready" notification;
 * alarms are inexact (Android may deliver them a few minutes late to save battery).
 */
class FarmingStore(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("farming", Context.MODE_PRIVATE)

    var timers: List<FarmTimer>
        get() {
            val raw = prefs.getString("timers", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    FarmTimer(o.getLong("id"), o.getString("crop"), o.optInt("count", 1), o.getLong("planted"), o.getLong("ready"), o.optString("place", ""))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        private set(v) {
            val arr = JSONArray()
            for (t in v) arr.put(JSONObject().put("id", t.id).put("crop", t.crop).put("count", t.count).put("planted", t.plantedAt).put("ready", t.readyAt).put("place", t.place))
            prefs.edit().putString("timers", arr.toString()).apply()
        }

    /** The player always carries magic secateurs: no reminders. */
    var alwaysSecateurs: Boolean
        get() = prefs.getBoolean("always_secateurs", false)
        set(v) = prefs.edit().putBoolean("always_secateurs", v).apply()

    /** Start timers automatically from "You plant…" messages while Auto-check is on. */
    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(v) = prefs.edit().putBoolean("auto_start", v).apply()

    /**
     * Starts a timer, or adds to one for the same crop planted in the last 20 minutes (a herb run
     * counts as one timer). Returns the timer.
     */
    fun plant(crop: Crop, count: Int = 1, now: Long = System.currentTimeMillis(), place: String = ""): FarmTimer {
        val list = timers.toMutableList()
        val recent = list.indexOfLast { it.crop == crop.name && now - it.plantedAt < 20 * 60_000L && it.readyAt > now }
        val timer = if (recent >= 0) {
            val old = list[recent]
            old.copy(count = old.count + count, readyAt = maxOf(old.readyAt, Farming.readyAt(crop, now))).also { list[recent] = it }
        } else {
            FarmTimer(now, crop.name, count, now, Farming.readyAt(crop, now), place).also { list.add(it) }
        }
        timers = list
        schedule(timer)
        return timer
    }

    fun remove(id: Long) {
        timers = timers.filter { it.id != id }
        cancel(id)
    }

    /** Drops timers that finished more than a day ago. */
    fun tidy(now: Long = System.currentTimeMillis()) {
        val keep = timers.filter { now - it.readyAt < 24 * 3600_000L }
        if (keep.size != timers.size) timers = keep
    }

    private fun pending(id: Long, t: FarmTimer?): PendingIntent {
        val i = Intent(ctx, FarmingAlarmReceiver::class.java).setAction("com.questoverlay.FARM_READY.$id")
        if (t != null) i.putExtra("label", t.label).putExtra("crop", t.crop)
        return PendingIntent.getBroadcast(ctx, (id % Int.MAX_VALUE).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun schedule(t: FarmTimer) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        // Inexact but allowed while the phone dozes; no special permission needed.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.readyAt, pending(t.id, t))
    }

    private fun cancel(id: Long) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pending(id, null))
    }

    /** Alarms are lost when the phone restarts; put them back. */
    fun rescheduleAll(now: Long = System.currentTimeMillis()) {
        for (t in timers) if (t.readyAt > now) schedule(t)
    }
}

/** Posts "Your herbs are ready" when a farming timer's alarm goes off. */
class FarmingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            FarmingStore(context).rescheduleAll()
            return
        }
        val label = intent.getStringExtra("label") ?: return
        val crop = Farming.crop(intent.getStringExtra("crop") ?: "")
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Farming timers", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, FarmingActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = when {
            crop?.patch == Patch.HESPORI -> "Hespori is ready to fight at the Farming Guild."
            crop?.secateursHelp == true -> "Ready to harvest. Bring magic secateurs for +10%."
            else -> "Ready to harvest."
        }
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("$label is ready")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(label.hashCode(), n)
    }

    companion object {
        const val CHANNEL = "farming"
    }
}
