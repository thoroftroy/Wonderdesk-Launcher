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
class WdHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f; private var downY = 0f
    private var fired = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val trigger = Runnable { fired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onLongPress?.invoke() }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { fired = false; downX = ev.x; downY = ev.y; handler.postDelayed(trigger, ViewConfiguration.getLongPressTimeout().toLong()) }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(trigger)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(trigger)
        }
        return fired   // after a long press, swallow the rest so the widget doesn't also get a tap
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean { if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) fired = false; return fired || super.onTouchEvent(ev) }
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
