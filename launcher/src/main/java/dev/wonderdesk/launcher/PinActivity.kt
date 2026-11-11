package dev.wonderdesk.launcher

import android.appwidget.AppWidgetManager
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.WonderActivity
import dev.wonderdesk.shared.WonderDialog
import dev.wonderdesk.shared.WonderKitTheme

/**
 * Apps asking to put a widget on the home screen (like Chronicle's "Count down to this on your home screen").
 * Asks once, then places it on the current page, or the next one with room.
 */
class PinActivity : WonderActivity() {
    private var request: LauncherApps.PinItemRequest? = null
    private var pendingId = -1
    private val bindAsk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val req = request; val info = req?.let { runCatching { it.getAppWidgetProviderInfo(this) }.getOrNull() }
        if (r.resultCode == RESULT_OK && req != null && info != null) finishAdd(req, info, pendingId) else WdWidgetHost(applicationContext, HOST_ID).deleteAppWidgetId(pendingId)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        request = runCatching { getSystemService(LauncherApps::class.java).getPinItemRequest(intent) }.getOrNull()
        super.onCreate(savedInstanceState)
        if (request?.isValid != true || request?.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET) { finish() }
    }

    @Composable override fun Content() {
        val req = request ?: return
        val c = LocalContext.current
        val info = runCatching { req.getAppWidgetProviderInfo(c) }.getOrNull() ?: run { finish(); return }
        val label = runCatching { info.loadLabel(packageManager) }.getOrNull() ?: "widget"
        WonderKitTheme {
            WonderDialog(onDismissRequest = { finish() }, title = { Text("Add to home screen?") }, text = { Text("“$label” will go on your home screen. Hold it there later to move, resize or remove it.") },
                confirmButton = { TextButton(onClick = { if (add(req, info)) finish() }) { Text("Add") } },
                dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } })
        }
    }

    /** True when done; false while Android asks the user to allow the widget. */
    private fun add(req: LauncherApps.PinItemRequest, info: android.appwidget.AppWidgetProviderInfo): Boolean {
        val host = WdWidgetHost(applicationContext, HOST_ID)
        val id = host.allocateAppWidgetId()
        if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) { finishAdd(req, info, id); return true }
        pendingId = id
        bindAsk.launch(android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile))
        return false
    }

    private fun finishAdd(req: LauncherApps.PinItemRequest, info: android.appwidget.AppWidgetProviderInfo, id: Int) {
        Home.load(this)
        val host = WdWidgetHost(applicationContext, HOST_ID)
        val ok = runCatching { req.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }) }.getOrDefault(false)
        if (!ok) { host.deleteAppWidgetId(id); return }
        val dens = resources.displayMetrics.density
        val tw = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellWidth else 0
        val th = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellHeight else 0
        val w = (if (tw > 0) tw else kotlin.math.ceil((info.minWidth / dens + 30) / 70.0).toInt()).coerceIn(1, Home.settings.cols)
        val h = (if (th > 0) th else kotlin.math.ceil((info.minHeight / dens + 30) / 70.0).toInt()).coerceIn(1, Home.settings.rows)
        val cell = Home.freeCell(Home.currentPage, w, h) ?: Home.freeCell(0, w, h)
        if (cell == null) { host.deleteAppWidgetId(id); Toast.makeText(this, "No room on the home screen. Add a page first", Toast.LENGTH_LONG).show(); return }
        Home.items = Home.items + Item.Widget(Home.newId(), id, cell.first, cell.second, cell.third, w, h); Home.save(this)
        if (cell.first != Home.currentPage) Home.jumpTo = cell.first
        Toast.makeText(this, "Added to your home screen", Toast.LENGTH_SHORT).show()
    }
}
