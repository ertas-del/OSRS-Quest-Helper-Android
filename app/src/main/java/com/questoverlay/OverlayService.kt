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
 * The overlay never reads the game. You tick steps off yourself, so there is nothing
 * here that touches the game client, its memory or its network traffic.
 */
class OverlayService : Service() {

    private enum class Mode { STEP, ROUTE, ITEMS, ALL }

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        store = ProgressStore(this)
        travel = TravelStore(this)
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

        if (intent?.action == ACTION_REFRESH) {
            pendingRoute = null
            if (root != null) render() else stopOverlay()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\" for Quest Overlay first.")
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
        running = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- service plumbing

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Quest overlay", NotificationManager.IMPORTANCE_LOW)
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
            .setContentTitle("Quest overlay is running")
            .setContentText("Tap to pick a quest")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(stopAction)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopOverlay() {
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
            toast("Open Quest Overlay from your app list to pick another quest.")
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun render() {
        val frame = root ?: return
        val q = quest ?: return
        frame.removeAllViews()
        frame.alpha = store.opacity
        frame.addView(if (collapsed) buildBubble(q) else buildCard(q))
        frame.post { clampToScreen() }
    }

    private fun buildBubble(q: Quest): View {
        val step = store.stepIndex(q).coerceIn(0, q.steps.size)
        val size = Ui.dp(this, 52)
        val bubble = TextView(this)
        bubble.text = if (step >= q.steps.size) "✓" else "${step + 1}/${q.steps.size}"
        bubble.setTextColor(Ui.DARK_TEXT)
        bubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        bubble.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        bubble.gravity = Gravity.CENTER
        bubble.background = Ui.oval(this, Ui.GOLD, Ui.STROKE, 2)
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
        card.layoutParams = FrameLayout.LayoutParams(Ui.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT)

        card.addView(buildHeader(q, step))
        card.addView(buildProgress(q, step))
        when {
            mode == Mode.ITEMS -> card.addView(buildItems(q))
            mode == Mode.ROUTE -> card.addView(buildRoute(q, step))
            mode == Mode.ALL -> card.addView(buildAllSteps(q, step))
            step >= q.steps.size -> card.addView(buildComplete(q))
            else -> card.addView(buildStep(q, step))
        }
        card.addView(buildTabs(q))
        return card
    }

    private fun iconButton(label: String, onClick: () -> Unit): TextView {
        val size = Ui.dp(this, 32)
        val t = Ui.text(this, label, 18f, Ui.TEXT, bold = true)
        t.gravity = Gravity.CENTER
        t.background = Ui.rounded(this, Ui.BTN_BG, 8, Ui.STROKE, 1)
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
            if (step >= q.steps.size) "Quest complete" else "Step ${step + 1} of ${q.steps.size}",
            11f,
            Ui.TAN
        )
        titles.addView(title)
        titles.addView(subtitle)
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

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
        bar.background = Ui.rounded(this, Ui.TRACK, 3)
        val filled = View(this)
        filled.background = Ui.rounded(this, Ui.GREEN, 3)
        bar.addView(filled, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, step.toFloat()))
        bar.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (total - step).toFloat()))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 5))
        lp.topMargin = Ui.dp(this, 8)
        lp.bottomMargin = Ui.dp(this, 8)
        bar.layoutParams = lp
        return bar
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
        travelLine(q, step)?.let { col.addView(it) }
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
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val scroll = MaxHeightScrollView(this, listMaxHeight(230))
        scroll.addView(row)
        container.addView(scroll)

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
        container.addView(Ui.text(this, "Quest complete!", 18f, Ui.GREEN, bold = true))
        val note = Ui.text(this, "Claim your rewards, then pick your next quest.", 13f, Ui.TEXT)
        note.setPadding(0, Ui.dp(this, 4), 0, 0)
        container.addView(note)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val again = Ui.button(this, "Restart", false) {
            store.reset(q.id)
            render()
        }
        val pick = Ui.button(this, "Pick quest", true) { openApp() }
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

    /** Where the player most likely is before doing step [index]: the last step with a map tile. */
    private fun startFor(q: Quest, index: Int): Pair<Int, String> {
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
            list.addView(Ui.text(this, "Quest complete: nowhere left to go.", 13f, Ui.TEXT))
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
            toast("Open Quest Overlay to change travel settings.")
        }
    }

    private fun buildTabs(q: Quest): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(this, 10)
        row.layoutParams = lp

        val checked = store.checkedItems(q).size
        val tabs = listOf(
            Mode.STEP to "Step",
            Mode.ROUTE to "Route",
            Mode.ITEMS to (if (q.items.isEmpty()) "Items" else "Items $checked/${q.items.size}"),
            Mode.ALL to "All steps"
        )
        for ((tabMode, label) in tabs) {
            val active = mode == tabMode
            val tab = Ui.text(this, label, 12f, if (active) Ui.GOLD else Ui.MUTED, bold = active)
            tab.gravity = Gravity.CENTER
            tab.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7))
            if (active) tab.background = Ui.rounded(this, 0x33FFC83D, 8)
            tab.setOnClickListener { setMode(tabMode) }
            row.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    companion object {
        const val ACTION_STOP = "com.questoverlay.STOP"
        const val ACTION_REFRESH = "com.questoverlay.REFRESH"
        const val EXTRA_QUEST_ID = "quest_id"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set
    }
}
