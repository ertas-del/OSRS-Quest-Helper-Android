package com.questoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import com.questoverlay.capture.AlertEngine
import com.questoverlay.capture.AlertStore
import com.questoverlay.capture.Alerter
import com.questoverlay.capture.CaptureConsentActivity
import com.questoverlay.capture.CaptureLog
import com.questoverlay.capture.Coach
import com.questoverlay.capture.CoachContext
import com.questoverlay.capture.CoachOutput
import com.questoverlay.capture.HighlightView
import com.questoverlay.capture.OcrLine
import com.questoverlay.capture.ScreenSense
import com.questoverlay.capture.ScreenWatcher
import com.questoverlay.capture.Speaker
import com.questoverlay.capture.StepInfo
import com.questoverlay.capture.Suggestion
import com.questoverlay.capture.Frame
import com.questoverlay.capture.Fuzzy
import com.questoverlay.capture.HudLayout
import com.questoverlay.capture.KillCountStore
import com.questoverlay.capture.KillCountTracker
import com.questoverlay.capture.Pets
import com.questoverlay.capture.ScreenFacts
import com.questoverlay.capture.VitalsEngine
import com.questoverlay.capture.VitalsReader
import com.questoverlay.capture.VitalsStore
import com.questoverlay.farming.Farming
import com.questoverlay.farming.FarmingStore
import com.questoverlay.farming.SecateursAdvisor
import com.questoverlay.location.Fix
import com.questoverlay.location.LocationState
import com.questoverlay.location.LocatorEngine
import com.questoverlay.location.LocatorStatus
import com.questoverlay.location.LocatorStore
import com.questoverlay.location.Pixels
import com.questoverlay.location.WorldMaps
import com.questoverlay.sailing.NavTarget
import com.questoverlay.sailing.SailingData
import com.questoverlay.sailing.SailingStore
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import com.questoverlay.puzzles.PuzzleRepository
import com.questoverlay.puzzles.PuzzleState
import com.questoverlay.puzzles.PuzzleViews
import com.questoverlay.travel.Leg
import com.questoverlay.travel.Packed
import com.questoverlay.travel.RouteFinder
import com.questoverlay.travel.TravelEngine
import com.questoverlay.travel.TravelStore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the floating quest card over other apps.
 *
 * With Auto-check (👁) switched on it also reads the screen, the same way a screen recorder
 * does, to notice conversations, dialogue options and "quest complete". It never touches the
 * game client, its memory or its network traffic, and never taps anything for you.
 */
class OverlayService : Service() {

    private enum class Mode { STEP, ROUTE, PUZZLE, ITEMS, ALL }

    private lateinit var wm: WindowManager
    private lateinit var store: ProgressStore
    private lateinit var params: WindowManager.LayoutParams

    private var quests: List<Quest> = emptyList()
    private var quest: Quest? = null
    private var root: FrameLayout? = null
    private var mode = Mode.STEP
    private var collapsed = false
    private lateinit var travel: TravelStore
    private var pendingRoute: String? = null

    // Puzzle tab: which puzzle is open, what the player has entered, and where the list was scrolled.
    private val puzzleState = PuzzleState()
    private var openPuzzle: String? = null
    private var puzzleScroll: ScrollView? = null
    private var puzzleScrollY = 0

    // Auto-check: reading the game screen, only while switched on with 👁.
    private var watcher: ScreenWatcher? = null
    private val coach = Coach()
    private lateinit var speaker: Speaker
    private var coachStatus: String? = null
    private var suggestion: Suggestion? = null
    private var suggestionKey: String? = null
    private var highlight: HighlightView? = null

    // AFK alerts ("Cargo hold full" etc.), checked on every look at the screen.
    private lateinit var alertStore: AlertStore
    private lateinit var alerter: Alerter
    private val alertEngine = AlertEngine()
    private var alertBanner: String? = null
    private var alertAt = 0L

    // Vitals, kill counts and farming, read from the same looks at the screen.
    private val vitalsEngine = VitalsEngine()
    private lateinit var vitalsStore: VitalsStore
    private lateinit var killStore: KillCountStore
    private lateinit var killTracker: KillCountTracker
    private lateinit var farming: FarmingStore
    private val secateurs = SecateursAdvisor()
    /** Chat messages already handled (time + text), so one message starts one timer. */
    private val seenChat = LinkedHashSet<String>()
    private var chatPrimed = false
    private var infoLine: String? = null
    private var infoAt = 0L

    // Where am I: the minimap matched against the world map, on a background thread.
    private lateinit var locatorStore: LocatorStore
    private lateinit var sailing: SailingStore
    private val locWorker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var locBusy = false
    @Volatile private var locator: LocatorEngine? = null
    private var fix: Fix? = null
    /** Where the current route was planned from; moves on after 15 tiles. */
    private var routeAnchor: Fix? = null
    private var liveCompass: CompassView? = null
    private var liveLabel: TextView? = null
    private var navCompass: CompassView? = null
    private var navLabel: TextView? = null

    private val watching: Boolean get() = watcher?.running == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        speaker = Speaker(this)
        alertStore = AlertStore(this)
        alerter = Alerter(this)
        store = ProgressStore(this)
        travel = TravelStore(this)
        vitalsStore = VitalsStore(this)
        killStore = KillCountStore(this)
        killTracker = KillCountTracker(killStore.counts)
        farming = FarmingStore(this)
        locatorStore = LocatorStore(this)
        sailing = SailingStore(this)
        quests = try {
            QuestRepository.load(this)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOverlay()
            return START_NOT_STICKY
        }

        // Must be called promptly after startForegroundService().
        startAsForeground()

        when (intent?.action) {
            ACTION_CAPTURE_GRANTED -> {
                if (root != null) startWatching(intent) else stopOverlay()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_DENIED -> {
                if (root != null) toast("Auto-check needs screen sharing. Tap 👁 to try again.")
                return START_NOT_STICKY
            }
        }

        if (intent?.action == ACTION_REFRESH) {
            pendingRoute = null
            if (root != null) render() else stopOverlay()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\" for Breadcrumbs first.")
            stopOverlay()
            return START_NOT_STICKY
        }

        val wanted = intent?.getStringExtra(EXTRA_QUEST_ID) ?: store.activeQuestId
        val chosen = quests.firstOrNull { it.id == wanted } ?: quests.firstOrNull()
        if (chosen == null) {
            toast("Could not load quest data.")
            stopOverlay()
            return START_NOT_STICKY
        }

        quest = chosen
        store.activeQuestId = chosen.id
        mode = Mode.STEP
        collapsed = false
        openPuzzle = null

        ensureOverlay()
        render()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root?.post { clampToScreen() }
    }

    override fun onDestroy() {
        removeOverlay()
        watcher?.shutdown()
        watcher = null
        speaker.shutdown()
        locWorker.shutdownNow()
        WorldMaps.release()
        running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- service plumbing

    private fun startAsForeground(withCapture: Boolean = watching) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Quest card", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop",
            stop
        ).build()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Breadcrumbs is running")
            .setContentText("Tap to pick a quest")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(stopAction)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            // Screen capture needs its own foreground type, added only while Auto-check is on.
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withCapture) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopOverlay() {
        watcher?.shutdown()
        watcher = null
        hideHighlight()
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlay() {
        val view = root
        if (view != null) {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        root = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- window handling

    private fun ensureOverlay() {
        if (root != null) return
        val frame = FrameLayout(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = store.overlayX ?: Ui.dp(this, 8)
        lp.y = store.overlayY ?: Ui.dp(this, 48)
        params = lp
        try {
            wm.addView(frame, lp)
            root = frame
        } catch (e: Exception) {
            toast("Could not show the overlay. Check the \"Display over other apps\" permission.")
            stopOverlay()
        }
    }

    private fun clampToScreen() {
        val frame = root ?: return
        val bounds = wm.currentWindowMetrics.bounds
        val maxX = max(0, bounds.width() - frame.width)
        val maxY = max(0, bounds.height() - frame.height)
        val nx = params.x.coerceIn(0, maxX)
        val ny = params.y.coerceIn(0, maxY)
        if (nx != params.x || ny != params.y) {
            params.x = nx
            params.y = ny
            try {
                wm.updateViewLayout(frame, params)
            } catch (e: Exception) {
                // View was removed while we were measuring.
            }
        }
    }

    /** Drags the overlay by whatever view it is attached to. A tap without movement calls [onTap]. */
    private inner class DragListener(private val onTap: (() -> Unit)?) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        val frame = root
                        if (frame != null) {
                            clampToScreen()
                            try {
                                wm.updateViewLayout(frame, params)
                            } catch (ex: Exception) {
                                // Overlay was closed mid-drag.
                            }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        store.savePosition(params.x, params.y)
                    } else {
                        onTap?.invoke()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) store.savePosition(params.x, params.y)
                    return true
                }
            }
            return false
        }
    }

    // ---------------------------------------------------------------- state changes

    private fun goTo(q: Quest, index: Int) {
        suggestion = null
        coachStatus = null
        hideHighlight()
        store.setStepIndex(q, index)
        // Finishing a quest unlocks its transport for the travel guide.
        if (index >= q.steps.size) travel.setQuestDone(q.name, true)
        render()
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        render()
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        render()
    }

    private fun openApp() {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        } catch (e: Exception) {
            toast("Open Breadcrumbs from your app list to pick another quest.")
        }
    }

    // ---------------------------------------------------------------- Auto-check

    /** 👁: start (asks Android for screen sharing) or stop reading the screen. */
    private fun toggleWatch() {
        if (watching) {
            stopWatching()
            return
        }
        try {
            startActivity(Intent(this, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Couldn't ask for screen sharing.")
        }
    }

    private fun startWatching(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CAPTURE_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_CAPTURE_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CAPTURE_DATA)
        }
        if (data == null) {
            toast("Screen sharing didn't start. Tap \uD83D\uDC41 to try again.")
            return
        }
        // Android 14+: the service must be a screen-capture service before the capture starts.
        startAsForeground(withCapture = true)
        val w = watcher ?: ScreenWatcher(this, { onScreen(it) }, { onWatchStopped() }).also { watcher = it }
        try {
            w.start(code, data)
            alertEngine.reset()
            vitalsEngine.reset()
            chatPrimed = false
            locator?.reset()
            setSecure(true)
            CaptureLog.note("Auto-check on")
            render()
        } catch (e: Exception) {
            startAsForeground(withCapture = false)
            toast("Couldn't start Auto-check: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun stopWatching() {
        watcher?.stop()
        onWatchStopped()
    }

    /** Capture ended: switched off, phone locked, or sharing stopped from the notification. */
    private fun onWatchStopped() {
        setSecure(false)
        fix = null
        hideHighlight()
        coachStatus = null
        suggestion = null
        if (root != null) startAsForeground(withCapture = false)
        CaptureLog.note("Auto-check off")
        render()
    }

    /** While watching, our own card shows up black in captures, so it never reads its own text. */
    private fun setSecure(on: Boolean) {
        val frame = root ?: return
        params.flags = if (on) params.flags or WindowManager.LayoutParams.FLAG_SECURE
        else params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        try {
            wm.updateViewLayout(frame, params)
        } catch (e: Exception) {
            // Overlay is closing.
        }
    }

    private fun coachContext(q: Quest): CoachContext {
        val index = store.stepIndex(q).coerceIn(0, q.steps.size)
        fun info(i: Int): StepInfo? = q.steps.getOrNull(i)?.let { StepInfo(i, it.text, it.npc, it.chat) }
        return CoachContext(
            questId = q.id,
            questName = q.name,
            stepCount = q.steps.size,
            step = info(index),
            upcoming = listOfNotNull(info(index + 1), info(index + 2)),
            diaryRegion = if (q.isDiary) q.region else "",
            diaryTier = q.tier,
            nextTaskIndex = if (q.isDiary) q.nextSectionStart(index) else null,
            // The reward step is the last task ("Finishing off").
            lastTaskIndex = if (q.isDiary) q.steps.indexOfLast { it.section.isNotBlank() }.takeIf { it > index } else null
        )
    }

    /** One look at the screen: work out what's happening and react. */
    private fun onScreen(frame: Frame) {
        if (root == null) return
        val lines = frame.lines
        checkAlerts(lines)
        checkVitals(frame)
        locate(frame)
        val q = quest ?: return
        val ctx = coachContext(q)
        val facts = ScreenSense.read(lines, ctx.names, q.name)
        checkChatEvents(facts)
        val out = coach.onFrame(facts, ctx)
        CaptureLog.frame(lines, facts, describe(out))
        out.speak?.let { speaker.say(it) }
        if (out.completeQuest) {
            goTo(q, q.steps.size)
            return
        }
        var changed = false
        if (out.suggestion != null) {
            suggestion = out.suggestion
            suggestionKey = ctx.key
            changed = true
        }
        if (out.status != coachStatus) {
            coachStatus = out.status
            changed = true
        }
        showHighlight(out.highlight)
        if (changed) render()
    }

    /** AFK alerts: buzz, notify and say it when a watched-for message appears. */
    private fun checkAlerts(lines: List<OcrLine>) {
        val fired = alertEngine.check(lines.map { it.text }, alertStore.active())
        if (fired.isEmpty()) return
        for ((rule, line) in fired) {
            CaptureLog.note("ALERT ${rule.label}: $line")
            speaker.say(rule.label)
            alerter.fire(rule.label, line)
        }
        showBanner("\uD83D\uDD14 " + fired.joinToString(" \u00B7 ") { it.first.label })
    }

    /** The red strip at the top of the card (alerts, vitals, secateurs). */
    private fun showBanner(text: String) {
        alertBanner = text
        alertAt = System.currentTimeMillis()
        render()
    }

    /** A passing note under the step (kill count, timer started), shown for 90 seconds. */
    private fun info(text: String) {
        infoLine = text
        infoAt = System.currentTimeMillis()
        render()
    }

    // ---------------------------------------------------------------- vitals, kill counts, farming

    private fun checkVitals(frame: Frame) {
        val cal = locator?.calibration
        val hud = if (cal != null) HudLayout(cal.minimapX, cal.minimapY, cal.minimapRadius, frame.width, frame.height)
            else HudLayout.default(frame.width, frame.height)
        val reading = VitalsReader.read(frame.lines, hud)
        if (reading.isEmpty) return
        for (w in vitalsEngine.check(reading, vitalsStore.settings(travel.levels))) {
            CaptureLog.note("VITALS ${w.text}")
            speaker.say(w.text.substringBefore(":").substringBefore("!"))
            showBanner((if (w.id == "hp") "\u2764 " else "\u2728 ") + w.text)
        }
    }

    private fun fairytaleDone(): Boolean? =
        if (travel.completedQuests.isEmpty()) null else travel.isQuestDone("Fairytale I - Growing Pains")

    private fun checkChatEvents(facts: ScreenFacts) {
        for ((boss, kc) in killTracker.update(facts.killCounts)) {
            killStore.merge(mapOf(boss to kc))
            val pet = Pets.forBoss(boss)
            CaptureLog.note("KC $boss $kc")
            info(if (pet != null) "$boss KC $kc \u00B7 ${Pets.percent(pet.chanceBy(kc))} chance of ${pet.pet} by now" else "$boss KC $kc")
        }
        // Farming reacts only to messages that appear while watching (not old ones already in chat).
        val fresh = facts.timedMessages.filter { (t, m) -> seenChat.add("${t ?: ""}|${Fuzzy.norm(m)}") }
        while (seenChat.size > 400) seenChat.remove(seenChat.first())
        if (!chatPrimed) {
            chatPrimed = true
            return
        }
        for ((_, m) in fresh) {
            Farming.planting(m)?.let { p ->
                if (farming.autoStart) {
                    val t = farming.plant(p.crop, p.count, place = nearestPlaceName())
                    CaptureLog.note("FARM planted ${p.crop.name}")
                    info("\u23F1 ${t.label}: ready ${java.text.SimpleDateFormat(if (t.readyAt - System.currentTimeMillis() < 12 * 3600_000L) "h:mm a" else "EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t.readyAt))}")
                }
                secateurs.onPlant(p.crop, farming.alwaysSecateurs, fairytaleDone())?.let { warnSecateurs(it) }
            }
            Farming.harvesting(m)?.let { c ->
                secateurs.onHarvest(c, farming.alwaysSecateurs, fairytaleDone())?.let { warnSecateurs(it) }
            }
        }
    }

    /** Text and voice: the red strip always shows; 🔇 silences only the voice. */
    private fun warnSecateurs(w: SecateursAdvisor.Warning) {
        CaptureLog.note("SECATEURS ${w.text}")
        speaker.say(w.speak)
        showBanner("\u2702 " + w.text)
    }

    private fun nearestPlaceName(): String {
        val f = fix?.takeIf { System.currentTimeMillis() - it.at < 60_000 } ?: return ""
        val places = try { TravelEngine.places(this) } catch (e: Exception) { return "" }
        val p = places.filter { Packed.z(it.tile) == 0 }.minByOrNull { f.distanceTo(Packed.x(it.tile), Packed.y(it.tile)) } ?: return ""
        return if (f.distanceTo(Packed.x(p.tile), Packed.y(p.tile)) <= 40) "near ${p.name}" else ""
    }

    // ---------------------------------------------------------------- where am I

    private fun locate(frame: Frame) {
        if (!locatorStore.enabled || frame.corner == null || locBusy) return
        locBusy = true
        val app = applicationContext
        try {
            locWorker.execute {
                val result = try {
                    val regions = WorldMaps.load(app)
                    val eng = locator ?: LocatorEngine(
                        regions,
                        zoom = locatorStore.zoom.takeIf { it > 0f } ?: 0.95f,
                        zoomKnown = locatorStore.zoom > 0f,
                        onZoomLearned = { z -> locatorStore.zoom = z }
                    ).also { locator = it }
                    eng.onFrame(Pixels { x, y -> frame.pixel(x, y) }, frame.width, frame.height) to eng
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    null
                }
                mainHandler.post {
                    locBusy = false
                    if (result != null) onLocation(result.first, result.second)
                }
            }
        } catch (e: Exception) {
            locBusy = false // the worker was shut down
        }
    }

    private fun onLocation(st: LocatorStatus, eng: LocatorEngine) {
        LocationState.status = st
        LocationState.updatedAt = System.currentTimeMillis()
        LocationState.calibration = eng.calibration
        LocationState.zoom = eng.zoom
        if (root == null) return
        val had = fix != null
        val f = st.fix
        fix = f
        var redraw = had != (f != null)
        if (f != null) {
            val a = routeAnchor
            if (a == null || a.region != f.region || a.distanceTo(f.tileX, f.tileY) >= 15) {
                routeAnchor = f
                redraw = true
            }
            val q = quest
            if (q != null && locatorStore.arrivalCheck && store.stepIndex(q) < q.steps.size) {
                val tile = q.steps[store.stepIndex(q)].tile
                val at = tile != null && tile.plane == 0 && f.distanceTo(tile.x, tile.y) <= 3
                val ctx = coachContext(q)
                val out = coach.onArrival(ctx, at)
                out.speak?.let { speaker.say(it) }
                if (out.suggestion != null) {
                    suggestion = out.suggestion
                    suggestionKey = ctx.key
                    redraw = true
                }
            }
            if (advanceNav(f)) redraw = true
        }
        if (redraw) render() else updateLive()
    }

    /** Sailing routes: on reaching a waypoint, aim at the next one. Returns true if the target changed. */
    private fun advanceNav(f: Fix): Boolean {
        val t = sailing.target ?: return false
        if (t.routeKey.isEmpty() || f.distanceTo(t.x, t.y) > 6) return false
        val (from, to) = t.routeKey.split('>').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val route = try { SailingData.load(this).routes.firstOrNull { it.from == from && it.to == to } } catch (e: Exception) { null } ?: return false
        val next = sailing.routeIndex + 1
        sailing.routeIndex = next
        sailing.target = if (next < route.points.size) {
            val p = route.points[next]
            NavTarget("$to (waypoint $next of ${route.points.size - 1})", p.first, p.second, routeKey = t.routeKey)
        } else {
            NavTarget("$to (arrived)", route.points.last().first, route.points.last().second)
        }
        speaker.say(if (next < route.points.size) "Next waypoint" else "Arrived")
        return true
    }

    /** The minimap fix only makes sense for targets on the same map (surface vs Prifddinas). */
    private fun pointable(f: Fix, x: Int, y: Int): Boolean = abs(y - f.tileY) < 2000 && abs(x - f.tileX) < 2000

    private fun compassWord(deg: Float): String =
        listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((deg + 22.5f) % 360f) / 45f).toInt()]

    /** Turns the arrows on the card to match where you are and which way the camera faces. */
    private fun updateLive() {
        val f = fix?.takeIf { System.currentTimeMillis() - it.at < 20_000 }
        val q = quest
        val tile = q?.let { it.steps.getOrNull(store.stepIndex(it))?.tile }
        liveCompass?.let { c ->
            if (f != null && tile != null && tile.plane == 0 && pointable(f, tile.x, tile.y)) {
                c.northAngle = f.cameraAngle
                c.screenAngle = f.screenAngleTo(tile.x, tile.y)
                val d = f.straightLineTiles(tile.x, tile.y)
                liveLabel?.text = if (d <= 3) "\uD83D\uDCCD You're there" else "\uD83D\uDCCD $d tiles ${compassWord(f.bearingTo(tile.x, tile.y))} of you"
                liveLabel?.visibility = View.VISIBLE
            } else {
                c.screenAngle = null
                liveLabel?.visibility = View.GONE
            }
        }
        val t = sailing.target
        navCompass?.let { c ->
            if (f != null && t != null && pointable(f, t.x, t.y)) {
                c.northAngle = f.cameraAngle
                c.screenAngle = f.screenAngleTo(t.x, t.y)
                val d = f.straightLineTiles(t.x, t.y)
                navLabel?.text = if (d <= 4) "You're there" else "$d tiles ${compassWord(f.bearingTo(t.x, t.y))}"
            } else {
                c.screenAngle = null
                navLabel?.text = if (watching) "Finding you on the minimap\u2026" else "Switch on \uD83D\uDC41 to point the way"
            }
        }
    }

    /** The Sailing (or any) target strip at the top of the card: arrow, name, distance, ✕. */
    private fun buildNavStrip(): View? {
        val t = sailing.target ?: return null
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val p = Ui.dp(this, 6)
        row.setPadding(p, p / 2, p / 2, p / 2)
        row.background = Ui.rounded(this, Ui.STONE_DARK, 4, Ui.STROKE, 1)
        val c = CompassView(this)
        navCompass = c
        row.addView(c, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 8), 0, 0, 0)
        val name = Ui.text(this, "\u26F5 ${t.name}", 13f, Ui.GOLD, bold = true)
        name.maxLines = 2
        name.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        val label = Ui.text(this, "", 12f, Ui.TAN)
        navLabel = label
        col.addView(label)
        val f = fix
        if (t.chartId >= 0 && f != null && f.distanceTo(t.x, t.y) <= 4) {
            val done = Ui.text(this, "Mark charted \u2713", 12f, Ui.GREEN, bold = true)
            done.setOnClickListener {
                sailing.charted = sailing.charted + t.chartId
                sailing.target = null
                render()
            }
            col.addView(done)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton("\u2715") {
            sailing.target = null
            render()
        })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 6)
        row.layoutParams = lp
        return row
    }

    /** The red alert strip at the top of the card; it goes away after two minutes or with ✕. */
    private fun buildAlertBanner(): View? {
        val text = alertBanner ?: return null
        if (System.currentTimeMillis() - alertAt > 120_000) {
            alertBanner = null
            return null
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val p = Ui.dp(this, 8)
        row.setPadding(p, p / 2, p / 2, p / 2)
        row.background = Ui.rounded(this, 0xFF5A0F0F.toInt(), 4, Ui.RED, 2)
        val t = Ui.text(this, text, 14f, Ui.TEXT, bold = true)
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(iconButton("\u2715") {
            alertBanner = null
            render()
        })
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 6)
        row.layoutParams = lp
        return row
    }

    private fun describe(out: CoachOutput): String? = when {
        out.completeQuest -> "quest complete: ticking it"
        out.suggestion != null -> "asking: ${out.suggestion.text}"
        out.pick != null -> "pick \"${out.pick}\""
        else -> out.status
    }

    private fun showHighlight(line: OcrLine?) {
        if (line == null) {
            hideHighlight()
            return
        }
        val existing = highlight
        if (existing != null) {
            existing.box = line
            return
        }
        val view = HighlightView(this)
        view.box = line
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // Android only lets taps pass through another app's window at 80% opacity or less.
        lp.alpha = 0.8f
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            wm.addView(view, lp)
            highlight = view
        } catch (e: Exception) {
            // Not essential.
        }
    }

    private fun hideHighlight() {
        val view = highlight ?: return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            // Already gone.
        }
        highlight = null
    }

    /** "Looks done?" / "Skip ahead?" banner, shown above the step's buttons. */
    private fun buildSuggestion(q: Quest, s: Suggestion): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 8)
        box.setPadding(p, p, p, p)
        box.background = Ui.rounded(this, Ui.STONE_DARK, 4, Ui.GOLD, 2)
        box.addView(Ui.text(this, s.text, 13f, Ui.TAN, bold = true))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val yes = Ui.button(this, if (s.kind == Suggestion.Kind.SKIP) "Skip ahead \u203A" else "Tick \u2713", true) {
            suggestion = null
            goTo(q, s.targetIndex.coerceIn(0, q.steps.size))
        }
        val no = Ui.button(this, "\u2715", false) {
            coach.dismiss(coachContext(q), s)
            suggestion = null
            render()
        }
        row.addView(yes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.leftMargin = Ui.dp(this, 8)
        row.addView(no, nlp)
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = Ui.dp(this, 6)
        box.addView(row, rlp)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 8)
        box.layoutParams = lp
        return box
    }

    // ---------------------------------------------------------------- drawing

    private fun render() {
        val frame = root ?: return
        val q = quest ?: return
        puzzleScrollY = puzzleScroll?.scrollY ?: 0
        puzzleScroll = null
        liveCompass = null
        liveLabel = null
        navCompass = null
        navLabel = null
        frame.removeAllViews()
        frame.alpha = store.opacity
        frame.addView(if (collapsed) buildBubble(q) else buildCard(q))
        updateLive()
        frame.post { clampToScreen() }
    }

    private fun buildBubble(q: Quest): View {
        val step = store.stepIndex(q).coerceIn(0, q.steps.size)
        val size = Ui.dp(this, 52)
        val bubble = TextView(this)
        bubble.text = if (step >= q.steps.size) "✓" else "${step + 1}/${q.steps.size}"
        bubble.setTextColor(if (step >= q.steps.size) Ui.GREEN else Ui.GOLD)
        bubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        bubble.typeface = Ui.typeface(this, bold = true)
        bubble.setShadowLayer(0.01f, Ui.dpf(this, 1f), Ui.dpf(this, 1f), Ui.DARK_TEXT)
        bubble.gravity = Gravity.CENTER
        bubble.background = Ui.oval(this, Ui.CARD_BG, Ui.STROKE, 2)
        bubble.layoutParams = FrameLayout.LayoutParams(size, size)
        bubble.setOnTouchListener(DragListener { setCollapsed(false) })
        return bubble
    }

    private fun buildCard(q: Quest): View {
        val step = store.stepIndex(q).coerceIn(0, q.steps.size)
        val pad = Ui.dp(this, 10)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.rounded(this, Ui.PANEL_BG, 14, Ui.STROKE, 2)
        card.setPadding(pad, pad, pad, pad)
        card.layoutParams = FrameLayout.LayoutParams(Ui.dp(this, 320), ViewGroup.LayoutParams.WRAP_CONTENT)

        card.addView(buildHeader(q, step))
        buildAlertBanner()?.let { card.addView(it) }
        buildNavStrip()?.let { card.addView(it) }
        card.addView(buildProgress(q, step))
        when {
            mode == Mode.ITEMS -> card.addView(buildItems(q))
            mode == Mode.ROUTE -> card.addView(buildRoute(q, step))
            mode == Mode.PUZZLE -> card.addView(buildPuzzle(q))
            mode == Mode.ALL -> card.addView(buildAllSteps(q, step))
            step >= q.steps.size -> card.addView(buildComplete(q))
            else -> card.addView(buildStep(q, step))
        }
        card.addView(buildTabs(q))
        return card
    }

    private fun iconButton(label: String, onClick: () -> Unit): TextView {
        val size = Ui.dp(this, 32)
        val t = Ui.text(this, label, 18f, Ui.GOLD, bold = true)
        t.gravity = Gravity.CENTER
        t.background = Ui.stoneButton(this)
        val lp = LinearLayout.LayoutParams(size, size)
        lp.leftMargin = Ui.dp(this, 6)
        t.layoutParams = lp
        t.setOnClickListener { onClick() }
        return t
    }

    private fun buildHeader(q: Quest, step: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val title = Ui.text(this, q.name, 16f, Ui.GOLD, bold = true)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val subtitle = Ui.text(
            this,
            (if (step >= q.steps.size) "${q.kindLabel} complete" else "Step ${step + 1} of ${q.steps.size}") +
                if (watching) " \u00B7 watching" else "",
            11f,
            Ui.TAN
        )
        titles.addView(title)
        titles.addView(subtitle)
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val eye = iconButton("\uD83D\uDC41") { toggleWatch() }
        eye.alpha = if (watching) 1f else 0.45f
        eye.contentDescription = if (watching) "Auto-check on" else "Auto-check off"
        row.addView(eye)
        val voice = iconButton(if (speaker.muted) "\uD83D\uDD07" else "\uD83D\uDD0A") {
            speaker.muted = !speaker.muted
            render()
        }
        voice.contentDescription = if (speaker.muted) "Voice muted" else "Voice on"
        row.addView(voice)
        row.addView(iconButton("−") { setCollapsed(true) })
        row.addView(iconButton("×") { stopOverlay() })

        // Grab the title area to move the card around.
        row.setOnTouchListener(DragListener(null))
        return row
    }

    private fun buildProgress(q: Quest, step: Int): View {
        val total = max(1, q.steps.size)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = Ui.rounded(this, 0xFF8C0000.toInt(), 0)
        bar.setPadding(0, 0, 0, 0)
        val filled = View(this)
        filled.background = Ui.rounded(this, 0xFF00A800.toInt(), 0)
        bar.addView(filled, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, step.toFloat()))
        bar.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (total - step).toFloat()))
        val frame = FrameLayout(this)
        frame.background = Ui.rounded(this, Ui.STROKE, 0)
        val p1 = Ui.dp(this, 1)
        frame.setPadding(p1, p1, p1, p1)
        frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8))
        lp.topMargin = Ui.dp(this, 8)
        lp.bottomMargin = Ui.dp(this, 8)
        frame.layoutParams = lp
        return frame
    }

    /** Keeps the whole card on screen, even in landscape: header, tabs and buttons need ~170dp. */
    private fun listMaxHeight(preferredDp: Int): Int {
        val screen = wm.currentWindowMetrics.bounds.height()
        val room = screen - Ui.dp(this, 190)
        return min(Ui.dp(this, preferredDp), max(Ui.dp(this, 90), room))
    }

    private fun openUrl(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            // Shrink the card so the map isn't covered.
            setCollapsed(true)
        } catch (e: Exception) {
            toast("No browser found to open the map.")
        }
    }

    private fun buildStep(q: Quest, step: Int): View {
        val s = q.steps[step]
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val compass = CompassView(this)
        compass.dir = s.dir
        liveCompass = compass
        row.addView(compass, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 10), 0, 0, 0)

        // The section this step belongs to (the nearest section title at or before it).
        val section = (step downTo 0).map { q.steps[it].section }.firstOrNull { it.isNotBlank() } ?: ""
        if (section.isNotBlank()) {
            val sec = Ui.text(this, section.uppercase(), 10f, Ui.TAN, bold = true)
            sec.maxLines = 1
            sec.ellipsize = TextUtils.TruncateAt.END
            col.addView(sec)
        }
        val body = Ui.text(this, s.text, 15f, Ui.TEXT)
        body.setPadding(0, Ui.dp(this, 3), 0, 0)
        col.addView(body)

        val dirText = s.directionLabel
        if (dirText.isNotEmpty()) {
            val dirLabel = Ui.text(this, dirText, 12f, Ui.GOLD, bold = true)
            dirLabel.setPadding(0, Ui.dp(this, 6), 0, 0)
            col.addView(dirLabel)
        }
        // From the minimap: how far, and which way, from where you actually are.
        val live = Ui.text(this, "", 12f, Ui.GREEN, bold = true)
        live.setPadding(0, Ui.dp(this, 4), 0, 0)
        live.visibility = View.GONE
        liveLabel = live
        col.addView(live)
        travelLine(q, step)?.let { col.addView(it) }
        // Diary tasks can be done in any order: let the player skip one they can't do yet.
        if (q.isDiary) {
            q.nextSectionStart(step)?.let { next ->
                val skip = Ui.text(this, "Skip this task: ${q.steps[next].section}  \u203A", 12f, Ui.TAN, bold = true)
                skip.setPadding(0, Ui.dp(this, 6), 0, 0)
                skip.setOnClickListener { goTo(q, next) }
                col.addView(skip)
            }
        }
        puzzleLine(q, s)?.let { col.addView(it) }
        if (s.chat.isNotEmpty()) {
            val options = s.chat.joinToString("  ›  ")
            val chat = Ui.text(this, "Chat: $options", 12f, Ui.MUTED, italic = true)
            chat.setPadding(0, Ui.dp(this, 4), 0, 0)
            col.addView(chat)
        }
        if (s.bring.isNotEmpty()) {
            val bring = Ui.text(this, "Bring: " + s.bring.joinToString(", "), 12f, Ui.TAN)
            bring.setPadding(0, Ui.dp(this, 4), 0, 0)
            col.addView(bring)
        }
        if (watching) {
            val line = Ui.text(this, "\uD83D\uDC41 " + (coachStatus ?: "Watching the screen"), 12f, Ui.GREEN, bold = true)
            line.setPadding(0, Ui.dp(this, 6), 0, 0)
            col.addView(line)
        }
        val note = infoLine
        if (note != null && System.currentTimeMillis() - infoAt < 90_000) {
            val t = Ui.text(this, note, 12f, Ui.TAN)
            t.setPadding(0, Ui.dp(this, 4), 0, 0)
            col.addView(t)
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val scroll = MaxHeightScrollView(this, listMaxHeight(230))
        scroll.addView(row)
        container.addView(scroll)

        val sug = suggestion
        if (sug != null && suggestionKey == coachContext(q).key) container.addView(buildSuggestion(q, sug))

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val back = Ui.button(this, "‹", false) { goTo(q, step - 1) }
        if (step == 0) back.alpha = 0.4f
        buttons.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))

        val tile = s.tile
        if (tile != null) {
            val map = Ui.button(this, "Map", false) { openUrl(tile.mapUrl) }
            val mapLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
            mapLp.leftMargin = Ui.dp(this, 8)
            buttons.addView(map, mapLp)
        }

        val last = step == q.steps.size - 1
        val next = Ui.button(this, if (last) "Finish ✓" else "Done ✓", true) { goTo(q, step + 1) }
        val nextLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        nextLp.leftMargin = Ui.dp(this, 8)
        buttons.addView(next, nextLp)
        val buttonsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttonsLp.topMargin = Ui.dp(this, 10)
        container.addView(buttons, buttonsLp)
        return container
    }

    private fun buildComplete(q: Quest): View {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.addView(Ui.text(this, "${q.kindLabel} complete!", 18f, Ui.GREEN, bold = true))
        val note = Ui.text(
            this,
            if (q.isDiary) "Claim your reward from the diary's reward NPC, then pick what's next."
            else "Claim your rewards, then pick your next quest.",
            13f,
            Ui.TEXT
        )
        note.setPadding(0, Ui.dp(this, 4), 0, 0)
        container.addView(note)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val again = Ui.button(this, "Restart", false) {
            store.reset(q.id)
            render()
        }
        val pick = Ui.button(this, if (q.isDiary) "Pick next" else "Pick quest", true) { openApp() }
        buttons.addView(again, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pickLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        pickLp.leftMargin = Ui.dp(this, 8)
        buttons.addView(pick, pickLp)
        val buttonsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        buttonsLp.topMargin = Ui.dp(this, 10)
        container.addView(buttons, buttonsLp)
        return container
    }

    private fun heading(list: LinearLayout, label: String) {
        val h = Ui.text(this, label.uppercase(), 10f, Ui.TAN, bold = true)
        h.setPadding(0, Ui.dp(this, if (list.childCount == 0) 0 else 10), 0, Ui.dp(this, 2))
        list.addView(h)
    }

    private fun buildItems(q: Quest): View {
        val checked = store.checkedItems(q)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL

        fun addItem(index: Int, item: Item) {
            val isChecked = checked.contains(index)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5))

            val mark = MarkView(this)
            mark.state = if (isChecked) MarkView.State.DONE else MarkView.State.TODO
            val markLp = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
            markLp.topMargin = Ui.dp(this, 1)
            row.addView(mark, markLp)

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(Ui.dp(this, 10), 0, 0, 0)
            val name = Ui.text(this, item.label, 14f, if (isChecked) Ui.MUTED else Ui.TEXT, bold = !item.recommended)
            if (isChecked) name.paintFlags = name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            col.addView(name)
            if (item.note.isNotBlank()) col.addView(Ui.text(this, item.note, 11f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnClickListener {
                store.setItemChecked(q, index, !isChecked)
                render()
            }
            list.addView(row)
        }

        // Indexes refer to positions in q.items so ticks survive the split into two lists.
        val required = q.items.withIndex().filter { !it.value.recommended }
        val recommended = q.items.withIndex().filter { it.value.recommended }

        heading(list, "Required items")
        if (required.isEmpty()) {
            list.addView(Ui.text(this, "Nothing required.", 13f, Ui.TEXT))
        }
        for ((i, item) in required) addItem(i, item)

        if (recommended.isNotEmpty()) {
            heading(list, "Recommended")
            for ((i, item) in recommended) addItem(i, item)
        }

        if (q.requirements.isNotEmpty()) {
            heading(list, "Requirements")
            for (r in q.requirements) {
                val t = Ui.text(this, "• $r", 12f, Ui.TEXT)
                t.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2))
                list.addView(t)
            }
        }

        val scroll = MaxHeightScrollView(this, listMaxHeight(280))
        scroll.addView(list)
        return scroll
    }

    private fun buildAllSteps(q: Quest, step: Int): View {
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        var currentRow: View? = null

        q.steps.forEachIndexed { index, s ->
            if (s.section.isNotBlank()) heading(list, s.section)

            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5))

            val mark = MarkView(this)
            mark.state = when {
                index < step -> MarkView.State.DONE
                index == step -> MarkView.State.CURRENT
                else -> MarkView.State.TODO
            }
            val markLp = LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20))
            markLp.topMargin = Ui.dp(this, 1)
            row.addView(mark, markLp)

            val color = when {
                index < step -> Ui.MUTED
                index == step -> Ui.GOLD
                else -> Ui.TEXT
            }
            val label = Ui.text(this, s.text, 13f, color, bold = index == step)
            if (index < step) label.paintFlags = label.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            label.setPadding(Ui.dp(this, 10), 0, 0, 0)
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnClickListener {
                mode = Mode.STEP
                goTo(q, index)
            }
            if (index == step) currentRow = row
            list.addView(row)
        }

        val hint = Ui.text(this, "Tap a step to jump to it.", 11f, Ui.MUTED, italic = true)
        hint.setPadding(0, Ui.dp(this, 6), 0, 0)
        list.addView(hint)

        val scroll = MaxHeightScrollView(this, listMaxHeight(280))
        scroll.addView(list)
        // Start scrolled to the step you are on.
        scroll.post {
            val target = currentRow
            if (target != null) {
                scroll.scrollTo(0, max(0, target.top - Ui.dp(this, 40)))
            } else if (step >= q.steps.size) {
                scroll.fullScroll(View.FOCUS_DOWN)
            }
        }
        return scroll
    }

    // ---------------------------------------------------------------- travel guide

    /**
     * Where the player is before doing step [index]: where the minimap says you are, if known;
     * otherwise the last step with a map tile, or the start place from the travel settings.
     */
    private fun startFor(q: Quest, index: Int): Pair<Int, String> {
        val f = routeAnchor?.takeIf { fix != null && System.currentTimeMillis() - (fix?.at ?: 0L) < 60_000 }
        if (f != null) return Packed.pack(f.tileX, f.tileY, 0) to "where you are"
        for (i in (index - 1) downTo 0) {
            val t = q.steps.getOrNull(i)?.tile ?: continue
            return Packed.pack(t.x, t.y, t.plane) to "where the last step was"
        }
        return travel.startTile(this) to travel.startPlace
    }

    private fun targetFor(q: Quest, index: Int): Int? {
        val t = q.steps.getOrNull(index)?.tile ?: return null
        return Packed.pack(t.x, t.y, t.plane)
    }

    /** Planned route for a step, or null while it's being worked out (a re-render follows). */
    private fun routeFor(q: Quest, index: Int): TravelEngine.Result? {
        val target = targetFor(q, index) ?: return null
        val start = startFor(q, index).first
        val profile = travel.profile()
        TravelEngine.cached(start, target, profile)?.let { return it }
        val key = "${q.id}#$index#$start#$target#$profile"
        if (pendingRoute != key) {
            pendingRoute = key
            TravelEngine.route(this, start, target, profile) {
                if (pendingRoute == key) pendingRoute = null
                // Only redraw if we're still looking at the same step.
                val cur = quest
                if (root != null && cur?.id == q.id && store.stepIndex(cur) == index) render()
            }
        }
        return null
    }

    private fun travelLine(q: Quest, index: Int): View? {
        if (targetFor(q, index) == null) return null
        val result = routeFor(q, index)
        val text = when (result) {
            null -> "Planning the route…"
            is TravelEngine.Result.Found -> {
                val legs = result.route.legs
                if (legs.size == 1 && legs[0].kind == Leg.Kind.WALK) return null // the compass covers plain walks
                "Travel: " + legs.take(2).joinToString(" → ") { it.title }
            }
            // Usually an instance or quest-only area: the step text and compass are the better guide.
            is TravelEngine.Result.NotFound -> return null
            is TravelEngine.Result.TooFar -> return null
            is TravelEngine.Result.Failed -> return null
        }
        val t = Ui.text(this, "$text  ›", 12f, Ui.GREEN, bold = true)
        t.setPadding(0, Ui.dp(this, 6), 0, 0)
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        t.setOnClickListener { setMode(Mode.ROUTE) }
        return t
    }

    private fun legIcon(kind: Leg.Kind): String = when (kind) {
        Leg.Kind.WALK -> "🚶"      // person walking
        Leg.Kind.TELEPORT -> "✨"        // sparkles
        Leg.Kind.TRANSPORT -> "⛵"       // sailboat
    }

    private fun buildRoute(q: Quest, step: Int): View {
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL

        if (step >= q.steps.size) {
            list.addView(Ui.text(this, "${q.kindLabel} complete: nowhere left to go.", 13f, Ui.TEXT))
            return list
        }
        val target = targetFor(q, step)
        if (target == null) {
            list.addView(Ui.text(this, "This step has no map location, so there's no route to plan. Follow the step text.", 13f, Ui.TEXT))
            return list
        }
        val (_, fromLabel) = startFor(q, step)
        heading(list, "From $fromLabel")

        when (val result = routeFor(q, step)) {
            null -> list.addView(Ui.text(this, "Planning the fastest route…", 13f, Ui.MUTED, italic = true))
            is TravelEngine.Result.NotFound -> {
                list.addView(
                    Ui.text(
                        this,
                        "No route found. This spot is probably inside a quest area or instance that the walking map " +
                            "doesn't cover, so follow the step text. If it's out in the open world, try switching on " +
                            "more ways to travel.",
                        13f,
                        Ui.TEXT
                    )
                )
            }
            is TravelEngine.Result.TooFar -> list.addView(
                Ui.text(this, "This trip is too long to plan on the phone. Teleport closer first, or use the Map button.", 13f, Ui.TEXT)
            )
            is TravelEngine.Result.Failed -> list.addView(Ui.text(this, result.message, 13f, Ui.RED))
            is TravelEngine.Result.Found -> {
                var walked = 0
                result.route.legs.forEachIndexed { i, leg ->
                    val row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5))
                    val icon = Ui.text(this, legIcon(leg.kind), 15f, Ui.TEXT)
                    row.addView(icon, LinearLayout.LayoutParams(Ui.dp(this, 26), ViewGroup.LayoutParams.WRAP_CONTENT))
                    val col = LinearLayout(this)
                    col.orientation = LinearLayout.VERTICAL
                    col.addView(Ui.text(this, "${i + 1}. ${leg.title}", 13f, if (leg.kind == Leg.Kind.WALK) Ui.TEXT else Ui.GOLD, bold = leg.kind != Leg.Kind.WALK))
                    if (leg.detail.isNotBlank()) col.addView(Ui.text(this, leg.detail, 11f, Ui.MUTED))
                    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    list.addView(row)
                    walked += leg.tiles
                }
                if (walked > 0) {
                    val secs = RouteFinder.walkSeconds(walked)
                    val time = if (secs >= 60) "${secs / 60} min ${secs % 60} s" else "$secs s"
                    val foot = Ui.text(this, "About $walked tiles on foot (~$time running).", 11f, Ui.TAN)
                    foot.setPadding(0, Ui.dp(this, 6), 0, 0)
                    list.addView(foot)
                }
            }
        }

        val settings = Ui.button(this, "Travel settings", false) { openTravelSettings() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 10)
        list.addView(settings, lp)

        val scroll = MaxHeightScrollView(this, listMaxHeight(280))
        scroll.addView(list)
        return scroll
    }

    private fun openTravelSettings() {
        try {
            val i = Intent(this, TravelSettingsActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            setCollapsed(true)
        } catch (e: Exception) {
            toast("Open Breadcrumbs to change travel settings.")
        }
    }

    // ---------------------------------------------------------------- puzzles

    /** "Puzzle help" link on steps that have a puzzle. */
    private fun puzzleLine(q: Quest, s: Step): View? {
        val p = PuzzleRepository.forQuest(this, q.id).firstOrNull { it.matches(s.text) || it.matches(s.section) } ?: return null
        val t = Ui.text(this, "\uD83E\uDDE9 Puzzle help: ${p.title}  \u203A", 12f, Ui.GOLD, bold = true)
        t.setPadding(0, Ui.dp(this, 6), 0, 0)
        t.setOnClickListener {
            openPuzzle = p.id
            puzzleScroll = null
            puzzleScrollY = 0
            setMode(Mode.PUZZLE)
        }
        return t
    }

    private fun buildPuzzle(q: Quest): View {
        val puzzles = PuzzleRepository.forQuest(this, q.id)
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        if (puzzles.size == 1) openPuzzle = puzzles[0].id
        val p = puzzles.firstOrNull { it.id == openPuzzle }

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        if (p == null) {
            heading(list, "Puzzles in this quest")
            for (each in puzzles) {
                val tag = if (each.kind == "solver") "solver" else "answer"
                val t = Ui.chip(this, "${each.title}  \u00B7 $tag", false)
                t.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                t.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9))
                t.setOnClickListener {
                    openPuzzle = each.id
                    puzzleScroll = null
                    puzzleScrollY = 0
                    render()
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = Ui.dp(this, 4)
                list.addView(t, lp)
            }
        } else {
            val title = Ui.text(this, p.title, 14f, Ui.GOLD, bold = true)
            list.addView(title)
            list.addView(PuzzleViews(this, puzzleState) { render() }.build(p))
        }

        val scroll = MaxHeightScrollView(this, listMaxHeight(300))
        scroll.addView(list)
        val restore = puzzleScrollY
        scroll.post { scroll.scrollTo(0, restore) }
        puzzleScroll = scroll
        container.addView(scroll)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        if (p != null && puzzles.size > 1) {
            buttons.addView(Ui.button(this, "\u2039 Puzzles", false) {
                openPuzzle = null
                puzzleScroll = null
                puzzleScrollY = 0
                render()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (q.wikiUrl.isNotBlank()) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (buttons.childCount > 0) lp.leftMargin = Ui.dp(this, 8)
            buttons.addView(Ui.button(this, "Wiki", false) { openUrl(q.wikiUrl) }, lp)
        }
        if (buttons.childCount > 0) {
            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            blp.topMargin = Ui.dp(this, 8)
            container.addView(buttons, blp)
        }
        return container
    }

    private fun buildTabs(q: Quest): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 10)
        row.layoutParams = lp

        val checked = store.checkedItems(q).size
        val hasPuzzles = PuzzleRepository.forQuest(this, q.id).isNotEmpty()
        val tabs = listOfNotNull(
            Mode.STEP to "Step",
            Mode.ROUTE to "Route",
            if (hasPuzzles) Mode.PUZZLE to "Puzzle" else null,
            Mode.ITEMS to (if (q.items.isEmpty() || hasPuzzles) "Items" else "Items $checked/${q.items.size}"),
            Mode.ALL to (if (hasPuzzles) "All" else "All steps")
        )
        for ((tabMode, label) in tabs) {
            val active = mode == tabMode
            val tab = Ui.chip(this, label, active, 12f)
            tab.setPadding(Ui.dp(this, 2), Ui.dp(this, 7), Ui.dp(this, 2), Ui.dp(this, 7))
            tab.maxLines = 1
            // Shrink the label rather than wrap or clip it when the font is set large.
            tab.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            tab.setOnClickListener { setMode(tabMode) }
            val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (row.childCount > 0) tlp.leftMargin = Ui.dp(this, 3)
            row.addView(tab, tlp)
        }
        return row
    }

    companion object {
        const val ACTION_STOP = "com.questoverlay.STOP"
        const val ACTION_REFRESH = "com.questoverlay.REFRESH"
        const val EXTRA_QUEST_ID = "quest_id"
        const val ACTION_CAPTURE_GRANTED = "com.questoverlay.CAPTURE_GRANTED"
        const val ACTION_CAPTURE_DENIED = "com.questoverlay.CAPTURE_DENIED"
        const val EXTRA_CAPTURE_CODE = "capture_code"
        const val EXTRA_CAPTURE_DATA = "capture_data"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set
    }
}
