package dev.wonderdesk.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.wonderdesk.shared.launchSafely

@Composable
fun HomeWidgets(extra: @Composable RowScope.() -> Unit = {}) {
    HomeWidgetsContent(inline = false, extra = extra)
}

@Composable
fun HomeWidgetsInline() {
    HomeWidgetsContent(inline = true)
}

@Composable
fun HomeWidgetsCompactWithFolders(
    folders: Map<String, List<String>>,
    byKeyCount: (List<String>) -> Int,
    onOpenFolder: (String) -> Unit,
) {
    HomeWidgetsContent(inline = false, compactFolders = folders, byKeyCount = byKeyCount, onOpenFolder = onOpenFolder)
}

@Composable
private fun HomeWidgetsContent(
    inline: Boolean,
    extra: @Composable RowScope.() -> Unit = {},
    compactFolders: Map<String, List<String>>? = null,
    byKeyCount: ((List<String>) -> Int)? = null,
    onOpenFolder: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val prefs = remember { context.getSharedPreferences("widgets", Context.MODE_PRIVATE) }
    val manager = remember { AppWidgetManager.getInstance(context) }
    val host = remember { AppWidgetHost(context, 5012) }
    var ids by remember { mutableStateOf(prefs.getStringSet("ids", emptySet())!!.mapNotNull { it.toIntOrNull() }) }
    var pending by remember { mutableIntStateOf(-1) }
    var choose by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun persist(values: List<Int>) { ids = values; prefs.edit().putStringSet("ids", values.map { it.toString() }.toSet()).apply() }
    fun abandon() { if (pending >= 0) host.deleteAppWidgetId(pending); pending = -1 }
    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pending >= 0) { persist(ids + pending); pending = -1; message = "Widget added to the home screen." } else { abandon(); message = "Widget setup canceled" }
    }
    fun finishBinding(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info == null) { abandon(); message = "Widget provider unavailable"; return }
        val component = info.configure
        if (component == null) { persist(ids + id); pending = -1; message = "Widget added to the home screen." }
        else runCatching { configure.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(component).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)) }.onFailure { abandon(); message = "Widget configuration unavailable: ${it.message}" }
    }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && pending >= 0) finishBinding(pending) else { abandon(); message = "Widget permission not granted" }
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) runCatching { host.startListening() }.onFailure { message = "Cannot listen to widget updates: ${it.message}" }
            if (event == Lifecycle.Event.ON_STOP) host.stopListening()
        }
        activity.lifecycle.addObserver(observer)
        runCatching { host.startListening() }.onFailure { message = "Widget host unavailable: ${it.message}" }
        onDispose { activity.lifecycle.removeObserver(observer); host.stopListening() }
    }
    if (!inline) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { choose = true }) { Text("Add widget…") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (compactFolders != null && byKeyCount != null && onOpenFolder != null) {
                    compactFolders.forEach { (name, keys) ->
                        OutlinedButton(onClick = { onOpenFolder(name) }) { Text("Folder · $name (${byKeyCount(keys)})") }
                    }
                } else {
                    extra()
                }
            }
        }
        // Compact mode keeps the home grid tall: only show widget views when at least one exists.
        if (compactFolders != null && ids.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ids.forEach { id ->
                    val info = manager.getAppWidgetInfo(id)
                    if (info != null) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(info.loadLabel(context.packageManager), style = MaterialTheme.typography.labelMedium)
                            AndroidView(factory = { host.createView(it, id, info).apply { setAppWidget(id, info); updateAppWidgetSize(Bundle(), 250, 120, 500, 240) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp))
                            TextButton(onClick = { host.deleteAppWidgetId(id); persist(ids - id) }) { Text("Remove widget") }
                        }
                    } else Text("Widget provider missing")
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Widgets", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { choose = true }) { Text("Add widget…") }
        }
    }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (inline) {
        if (ids.isEmpty()) {
            Text("No widgets yet. Add one to keep it on the home screen.", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ids.forEach { id ->
                    val info = manager.getAppWidgetInfo(id)
                    if (info != null) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(info.loadLabel(context.packageManager), style = MaterialTheme.typography.labelMedium)
                            AndroidView(factory = { host.createView(it, id, info).apply { setAppWidget(id, info); updateAppWidgetSize(Bundle(), 250, 120, 500, 240) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp))
                            TextButton(onClick = { host.deleteAppWidgetId(id); persist(ids - id) }) { Text("Remove widget") }
                        }
                    } else Text("Widget provider missing")
                }
            }
        }
    }
    if (choose) AlertDialog(onDismissRequest = { choose = false }, title = { Text("Choose an installed widget") }, text = {
        val providers = remember { manager.installedProviders.sortedBy { it.loadLabel(context.packageManager) } }
        if (providers.isEmpty()) Text("No widget providers installed. Install an app with widgets first.")
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(providers) { provider -> TextButton(onClick = {
                choose = false; abandon(); pending = host.allocateAppWidgetId()
                try {
                    if (manager.bindAppWidgetIdIfAllowed(pending, provider.provider)) finishBinding(pending)
                    else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider))
                } catch (e: Exception) { abandon(); message = "Cannot add this widget: ${e.message}" }
            }) { Text(provider.loadLabel(context.packageManager)) } }
        }
    }, confirmButton = { TextButton(onClick = { choose = false }) { Text("Cancel") } })
}
