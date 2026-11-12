package dev.wonderdesk.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** Widget views that report a long press (so widgets can be moved, resized and removed) without breaking their own taps. */
/**
 * Hosts another app's widget. Holding it lifts it out: let go to get the widget menu, or keep the finger down and
 * drag to move it or drop it on Remove. Works even when the widget itself handles touches (lists, buttons),
 * because the hold is watched in dispatchTouchEvent before the widget sees anything.
 */
class WdHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    /** Window coordinates of the finger. */
    var onLift: ((Float, Float) -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDrop: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var lifted = false; private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val loc = IntArray(2)
    private fun win(ev: MotionEvent): Pair<Float, Float> { getLocationInWindow(loc); return (loc[0] + ev.x) to (loc[1] + ev.y) }
    private val trigger = Runnable {
        lifted = true; dragging = false
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        // The widget must not also see a click or long click.
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { super.dispatchTouchEvent(it); it.recycle() }
        onLift?.invoke(lastX, lastY)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val (wx, wy) = win(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lifted = false; dragging = false; downX = wx; downY = wy; lastX = wx; lastY = wy
                handler.postDelayed(trigger, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = wx; lastY = wy
                if (!lifted) { if (abs(wx - downX) > slop || abs(wy - downY) > slop) handler.removeCallbacks(trigger) }
                else {
                    if (!dragging && (abs(wx - downX) > slop * 1.5f || abs(wy - downY) > slop * 1.5f)) dragging = true
                    if (dragging) onDrag?.invoke(wx, wy)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(trigger)
                if (lifted) {
                    lifted = false
                    if (dragging) onDrop?.invoke() else if (ev.actionMasked == MotionEvent.ACTION_UP) onLongPress?.invoke() else onDrop?.invoke()
                    dragging = false
                    return true
                }
            }
        }
        if (lifted) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() { handler.removeCallbacks(trigger); super.onDetachedFromWindow() }

    /** Tell the widget how big it really is, so it can pick a layout that fits (and nothing is cut off). */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || android.os.Build.VERSION.SDK_INT < 31) return
        val d = resources.displayMetrics.density
        runCatching { updateAppWidgetSize(android.os.Bundle(), listOf(android.util.SizeF(w / d, h / d))) }
    }
}

class WdWidgetHost(context: Context, id: Int) : AppWidgetHost(context, id) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView = WdHostView(context)
}

/** One result from Beacon's search (apps excluded; the launcher ranks apps itself). */
data class BeaconHit(val kind: String, val title: String, val subtitle: String, val intent: Intent?)

/** Asks Beacon for settings, contacts, files, maths and web results. Empty when Beacon isn't installed. */
object BeaconClient {
    private val URI: Uri = Uri.parse("content://dev.wonderdesk.beacon.search/query")
    fun available(c: Context) = c.packageManager.resolveContentProvider("dev.wonderdesk.beacon.search", 0) != null
    fun query(c: Context, q: String): List<BeaconHit> = runCatching {
        c.contentResolver.query(URI, null, q, arrayOf("noapps"), null)?.use { cur ->
            val out = ArrayList<BeaconHit>()
            while (cur.moveToNext()) {
                val intent = cur.getString(cur.getColumnIndexOrThrow("intent"))?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
                out += BeaconHit(cur.getString(cur.getColumnIndexOrThrow("kind")), cur.getString(cur.getColumnIndexOrThrow("title")), cur.getString(cur.getColumnIndexOrThrow("subtitle")) ?: "", intent)
            }
            out
        }
    }.getOrNull().orEmpty()
}
