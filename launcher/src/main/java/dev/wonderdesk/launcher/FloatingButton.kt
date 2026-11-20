package dev.wonderdesk.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.wonderdesk.shared.appArt
import kotlin.math.abs

/** What the floating button can do. Each is a short word shown under its icon. */
object AssistActions {
    val ALL = listOf(
        "home" to "Home", "back" to "Back", "recents" to "Recents", "notifications" to "Notifications", "quick" to "Quick settings",
        "lock" to "Lock", "screenshot" to "Screenshot", "power" to "Power menu", "record" to "Record screen",
        "ripple" to "Ripple", "controlroom" to "Control Room", "apps" to "All apps"
    )
    val DEFAULT = listOf("home", "back", "recents", "notifications", "screenshot", "lock", "record", "apps")

    private fun prefs(c: Context) = c.getSharedPreferences("assist", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, v: Boolean) { prefs(c).edit().putBoolean("on", v).apply(); LockService.instance?.refreshButton() }
    fun size(c: Context) = prefs(c).getInt("size", 52)
    fun opacity(c: Context) = prefs(c).getInt("opacity", 70)
    fun set(c: Context, size: Int = size(c), opacity: Int = opacity(c)) { prefs(c).edit().putInt("size", size).putInt("opacity", opacity).apply(); LockService.instance?.refreshButton() }
    fun chosen(c: Context): List<String> = (prefs(c).getString("actions", null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT).filter { id -> ALL.any { it.first == id } }
    fun setChosen(c: Context, l: List<String>) { prefs(c).edit().putString("actions", l.joinToString(",")).apply() }
    fun pos(c: Context): Pair<Int, Int> = prefs(c).getInt("x", -1) to prefs(c).getInt("y", 600)
    fun setPos(c: Context, x: Int, y: Int) { prefs(c).edit().putInt("x", x).putInt("y", y).apply() }

    /** Runs one action from the service. */
    fun run(s: AccessibilityService, id: String) {
        fun global(a: Int) = s.performGlobalAction(a)
        fun open(pkg: String, extra: Intent.() -> Unit = {}) { s.packageManager.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); extra() }?.let { runCatching { s.startActivity(it) } } }
        when (id) {
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot" -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            "power" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            // Cinema's record screen (starts, or stops if it is already recording).
            "record" -> runCatching { s.startActivity(Intent().setClassName("dev.wonderdesk.cinema", "dev.wonderdesk.cinema.RecordActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "ripple" -> open("dev.wonderdesk.ripple")
            "controlroom" -> open("dev.wonderdesk.controlroom")
            "apps" -> open("dev.wonderdesk.beacon")
        }
    }
}

/** The little round button that floats over every app. Drag it anywhere; tap it for the shortcuts. Drawn by the accessibility service, so it needs no other permission. */
class FloatingButton(private val s: AccessibilityService) {
    private val wm = s.getSystemService(WindowManager::class.java)
    private var button: View? = null
    private var panel: View? = null
    private val dp = s.resources.displayMetrics.density
    private var fade: Runnable? = null

    private fun lp(w: Int, h: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    fun show() {
        hide()
        val size = (AssistActions.size(s) * dp).toInt()
        val v = ImageView(s).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE61E3A8A.toInt()); setStroke((2 * dp).toInt(), 0x99FFFFFF.toInt()) }
            val art = runCatching { s.appArt(96)?.asAndroidBitmap() }.getOrNull()
            if (art != null) setImageBitmap(art)
            val pad = (size * .2f).toInt(); setPadding(pad, pad, pad, pad)
            alpha = AssistActions.opacity(s) / 100f
            elevation = 8 * dp
        }
        val p = lp(size, size)
        val (sx, sy) = AssistActions.pos(s)
        val screenW = s.resources.displayMetrics.widthPixels
        p.x = if (sx < 0) screenW - size else sx; p.y = sy
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; v.alpha = 1f; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > 8 * dp || abs(e.rawY - downY) > 8 * dp) moved = true
                    if (moved) { p.x = (startX + e.rawX - downX).toInt(); p.y = (startY + e.rawY - downY).toInt(); runCatching { wm.updateViewLayout(v, p) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // Settle against the nearer side edge.
                        val w = s.resources.displayMetrics.widthPixels
                        p.x = if (p.x + size / 2 < w / 2) 0 else w - size
                        runCatching { wm.updateViewLayout(v, p) }; AssistActions.setPos(s, p.x, p.y)
                    } else togglePanel(p, size)
                    dim(v); true
                }
                else -> false
            }
        }
        runCatching { wm.addView(v, p) }.onSuccess { button = v; dim(v) }
    }

    /** Fades out a little after a touch so it never gets in the way. */
    private fun dim(v: View) {
        fade?.let { v.removeCallbacks(it) }
        val r = Runnable { v.animate().alpha(AssistActions.opacity(s) / 100f * .6f).setDuration(400).start() }
        fade = r; v.postDelayed(r, 2500)
    }

    private fun togglePanel(bp: WindowManager.LayoutParams, size: Int) {
        if (panel != null) { closePanel(); return }
        val actions = AssistActions.chosen(s).mapNotNull { id -> AssistActions.ALL.firstOrNull { it.first == id } }
        if (actions.isEmpty()) return
        val cols = if (actions.size <= 4) actions.size else 4
        val grid = GridLayout(s).apply { columnCount = cols; setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(0xF2FFFFFF.toInt()); setStroke((1 * dp).toInt(), 0x33000000) }; elevation = 12 * dp }
        actions.forEach { (id, label) ->
            val cell = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt()) }
            cell.addView(TextView(s).apply { text = glyph(id); textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFF1E3A8A.toInt()) })
            cell.addView(TextView(s).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xFF222222.toInt()); maxLines = 1 })
            cell.minimumWidth = (72 * dp).toInt()
            cell.setOnClickListener { closePanel(); button?.postDelayed({ AssistActions.run(s, id) }, 120) }
            grid.addView(cell)
        }
        grid.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pw = grid.measuredWidth; val ph = grid.measuredHeight
        val sw = s.resources.displayMetrics.widthPixels; val sh = s.resources.displayMetrics.heightPixels
        val p = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        p.x = (bp.x + size / 2 - pw / 2).coerceIn((8 * dp).toInt(), sw - pw - (8 * dp).toInt())
        p.y = (bp.y + size + (8 * dp).toInt()).let { if (it + ph > sh - (24 * dp).toInt()) (bp.y - ph - (8 * dp).toInt()).coerceAtLeast(0) else it }
        // Touching outside the panel closes it.
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        grid.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { closePanel(); true } else false }
        runCatching { wm.addView(grid, p) }.onSuccess { panel = grid }
    }

    private fun glyph(id: String) = when (id) {
        "home" -> "⌂"; "back" -> "←"; "recents" -> "▣"; "notifications" -> "☰"; "quick" -> "⚙"; "lock" -> "⚿"
        "screenshot" -> "□"; "power" -> "⏻"; "record" -> "●"; "ripple" -> "≈"; "controlroom" -> "≡"; else -> "⁘"
    }

    private fun closePanel() { panel?.let { runCatching { wm.removeView(it) } }; panel = null }
    fun hide() { closePanel(); button?.let { runCatching { wm.removeView(it) } }; button = null }
}
