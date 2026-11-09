package dev.wonderdesk.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.wonderdesk.shared.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class HomeLayout(val favorites: List<String>, val dock: List<String>, val folders: Map<String, List<String>>, val columns: Int = 4, val tint: String = "blue") {
    fun json(): String = JSONObject().put("schema", 1).put("favorites", JSONArray(favorites)).put("dock", JSONArray(dock)).put("folders", JSONObject().apply { folders.forEach { (name, keys) -> put(name, JSONArray(keys)) } }).put("columns", columns).put("tint", tint).toString(2)
    companion object {
        fun parse(source: String): HomeLayout {
            require(source.length <= 128 * 1024) { "Layout exceeds 128 KiB" }
            val value = JSONObject(source)
            require(value.getInt("schema") == 1) { "Unsupported layout schema" }
            fun keys(array: JSONArray): List<String> {
                require(array.length() <= 500) { "Too many layout entries" }
                return (0 until array.length()).map { array.getString(it).also { key -> require(key.length <= 512 && '/' in key) { "Invalid app component" } } }.distinct()
            }
            val folders = value.getJSONObject("folders")
            require(folders.length() <= 50)
            val result = folders.keys().asSequence().associateWith { name -> require(name.isNotBlank() && name.length <= 60); keys(folders.getJSONArray(name)) }
            val columns = value.optInt("columns", 4)
            require(columns in 3..6)
            val tint = value.optString("tint", "blue")
            require(tint in listOf("blue", "violet", "slate"))
            return HomeLayout(keys(value.getJSONArray("favorites")), keys(value.getJSONArray("dock")).take(10), result, columns, tint)
        }
    }
}

/** Defaults only; existing layouts are never rewritten when applications change. */
fun defaultHomeLayout(values: List<LaunchableApp>, launcherPackage: String): HomeLayout {
    val preferredPackages = listOf(
        listOf("dev.wonderdesk.wayfinder"),
        listOf("com.blissplayer.music", "com.blissplayer.music.debug", "dev.wonderdesk.melody"),
        listOf("dev.wonderdesk.scribble"),
        listOf("dev.wonderdesk.abacus")
    )
    val dock = preferredPackages.mapNotNull { choices ->
        choices.firstNotNullOfOrNull { id -> values.firstOrNull { it.component.packageName == id }?.key }
    }
    val favorites = values.filter {
        (it.component.packageName.startsWith("dev.wonderdesk.") || it.component.packageName in listOf("com.blissplayer.music", "com.blissplayer.music.debug")) && it.component.packageName != launcherPackage
    }.map { it.key }
    return HomeLayout(favorites, dock, emptyMap())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val prefs = remember { context.getSharedPreferences("home", Context.MODE_PRIVATE) }
    val op = rememberOperation()
    var apps by remember { mutableStateOf(listOf<LaunchableApp>()) }
    var layout by remember { mutableStateOf(runCatching { HomeLayout.parse(prefs.getString("layout", "")!!) }.getOrDefault(HomeLayout(emptyList(), emptyList(), emptyMap()))) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    var settings by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var folderTarget by remember { mutableStateOf<LaunchableApp?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<HomeLayout?>(null) }
    var time by remember { mutableStateOf(LocalDateTime.now()) }
    val byKey = apps.associateBy { it.key }
    fun save(next: HomeLayout) { layout = next; prefs.edit().putString("layout", next.json()).apply() }
    fun defaults(values: List<LaunchableApp>): HomeLayout = defaultHomeLayout(values, context.packageName)
    fun refresh() { op.run("Reading installed apps", { loadLaunchableApps(context) }) {
        apps = it
        // Reload the saved layout on every resume so direct preference edits
        // (tests, restore) take effect even if the activity was not recreated.
        // UI organization always goes through save(), so this does not lose edits.
        if (prefs.contains("layout")) {
            runCatching { HomeLayout.parse(prefs.getString("layout", "")!!) }.onSuccess { reloaded ->
                if (reloaded != layout) layout = reloaded
            }
        } else save(defaults(it))
        op.notice("${it.size} installed launchable apps. Long-press an app to organize it.")
    } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) { val snapshot = layout.json(); op.run("Exporting launcher layout", { context.contentResolver.openOutputStream(uri, "w")!!.use { it.write(snapshot.toByteArray()) } }) { op.notice("Layout exported. Widgets and wallpaper images are not included.") } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) op.run("Validating layout JSON", {
            context.contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
                val buffer = CharArray(128 * 1024 + 1); var count = 0
                while (count < buffer.size) { val n = reader.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                require(count <= 128 * 1024) { "Layout exceeds 128 KiB" }
                HomeLayout.parse(String(buffer, 0, count))
            }
        }) { confirmRestore = it; op.notice("Layout validated; review before replacing the current layout.") }
    }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        activity.lifecycle.addObserver(observer); refresh()
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { time = LocalDateTime.now(); delay(1000) } }
    BackHandler(drawer || query.isNotBlank() || openFolder != null) { drawer = false; query = ""; openFolder = null }
    val allApps = drawer || query.isNotBlank()
    val homeKeys = layout.favorites.filter { it in byKey }
    val dark = LocalIsDark.current
    val homeText = if (dark) Color(0xFFEAF4FF) else Color(0xFF163B60)
    val gradient = if (dark) when (layout.tint) {
        "violet" -> listOf(Color(0xFF24213C), Color(0xFF30445E))
        "slate" -> listOf(Color(0xFF1C2834), Color(0xFF34485A))
        else -> listOf(Color(0xFF102236), Color(0xFF274866))
    } else when (layout.tint) { "violet" -> listOf(Color(0xFFE9E9FF), Color(0xFFCDDFF0)); "slate" -> listOf(Color(0xFFE8EEF3), Color(0xFFD1DCE6)); else -> listOf(Color(0xFFE1F2FF), Color(0xFFCDE2F1)) }
    val panelShape = RoundedCornerShape(12.dp)
    val panelBrush = Brush.verticalGradient(gradient.map { it.copy(alpha = .97f) })

    BoxWithConstraints(Modifier.fillMaxSize()) {
      // Preserve the saved preference but cap columns to keep 48dp targets
      // inside the actual window, including split-screen and narrow phones.
      // Wide windows (tablet or landscape) use a two-pane home so widgets and
      // folders do not squeeze the app grid.
      val isWide = maxWidth >= 840.dp || (maxWidth > 600.dp && maxWidth > maxHeight)
      val columns = minOf(layout.columns, ((maxWidth.value - 16f + 6f) / 62f).toInt().coerceAtLeast(1))
      val pageSize = columns * 3
      val pageCount = ((homeKeys.size + pageSize - 1) / pageSize).coerceAtLeast(1)
      val effectivePage = page.coerceIn(0, pageCount - 1)
      val shown = if (allApps) apps.filter { it.matches(query) } else homeKeys.drop(effectivePage * pageSize).take(pageSize).mapNotNull(byKey::get)
      @Composable
      fun AppGrid(modifier: Modifier) {
        // Text monograms apply only where an installed app doesn't supply an icon. No Apple assets.
        LazyVerticalGrid(GridCells.Fixed(columns), modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.key }) { app ->
                Surface(Modifier.fillMaxWidth().heightIn(min = 72.dp), shape = MaterialTheme.shapes.medium, color = if (dark) Color(0xF9293D52) else Color.White.copy(alpha = 0.97f), tonalElevation = 2.dp) {
                    Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .semantics { contentDescription = "Open ${app.title}" }
                            .combinedClickable(onClickLabel = "Open ${app.title}", onLongClickLabel = "Organize ${app.title}",
                                onClick = { context.launchApp(app, op::notice) }, onLongClick = { selected = app }),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            app.icon?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp)) } ?: Text("TEMP", style = MaterialTheme.typography.labelSmall)
                            Text(app.title, maxLines = 2, style = MaterialTheme.typography.labelMedium, color = homeText)
                        }
                    }
                }
            }
        }
      }
      @Composable
      fun WidgetsCard() {
        Column(Modifier.fillMaxWidth().background(panelBrush, panelShape).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          HomeWidgetsInline()
        }
      }
      @Composable
      fun FoldersCard() {
        Column(Modifier.fillMaxWidth().background(panelBrush, panelShape).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Folders", style = MaterialTheme.typography.titleSmall, color = homeText)
          if (layout.folders.isEmpty()) Text("No folders yet. Long-press an app to create one.", style = MaterialTheme.typography.bodySmall, color = homeText)
          layout.folders.forEach { (name, keys) -> OutlinedButton(onClick = { openFolder = name }) { Text("Folder · $name (${keys.count { it in byKey }})") } }
        }
      }
      @Composable
      fun PagerCard() {
        Row(Modifier.fillMaxWidth().background(panelBrush, panelShape), horizontalArrangement = Arrangement.SpaceBetween) {
          TextButton(onClick = { page = effectivePage - 1 }, enabled = effectivePage > 0) { Text("Previous") }
          Text("Page ${effectivePage + 1}/$pageCount", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelSmall, color = homeText)
          TextButton(onClick = { page = effectivePage + 1 }, enabled = effectivePage + 1 < pageCount) { Text("Next") }
        }
      }
      @Composable
      fun DockCard() {
        val dockShape = RoundedCornerShape(18.dp)
        // Glass shelf shared by narrow phones and wide tablets: the same
        // component, materials and icon treatment on both. Icons carry a
        // faded mirror below the shelf floor line and no text labels — the
        // desktop dock reads the same way. Horizontally scrollable past a
        // phone-width handful so the cap is about shelf room, not an
        // arbitrary number; this is still paint, not compositor blur.
        val dockColors = if (dark) listOf(Color(0xF04A6B8A), Color(0xF02C4A61), Color(0xF01E3547), Color(0xF0314E68))
                         else listOf(Color(0xF0FFFFFF), Color(0xF0EAF7FF), Color(0xF0C9DDF0), Color(0xF0BDD6E8), Color(0xF0D9EAF7))
        val stops = if (dark) listOf(0f, .18f, .52f, 1f) else listOf(0f, .18f, .47f, .52f, 1f)
        Box(Modifier.fillMaxWidth().shadow(8.dp, dockShape, clip = false)
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.35f else 0.75f), dockShape)
            .background(Brush.verticalGradient(*stops.zip(dockColors).map { it.first to it.second }.toTypedArray()), dockShape)) {
          Column {
            // Bright front lip along the top of the shelf.
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = if (dark) 0.28f else 0.85f)))
            val dockApps = layout.dock.mapNotNull(byKey::get)
            if (dockApps.isEmpty()) {
              Text("Long-press an app to pin it to the dock.", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = homeText)
            } else {
              LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(dockApps, key = { it.key }) { app ->
                  val interaction = remember(app.key) { MutableInteractionSource() }
                  val pressed by interaction.collectIsPressedAsState()
                  val scale by animateFloatAsState(if (pressed && LocalMotion.current == WonderMotion.FULL && android.animation.ValueAnimator.areAnimatorsEnabled()) 0.92f else 1f, label = "dock press")
                  Column(Modifier.widthIn(min = 60.dp).semantics { contentDescription = "Dock ${app.title}" }.combinedClickable(
                    interactionSource = interaction, indication = LocalIndication.current,
                    onClickLabel = "Open ${app.title}", onLongClickLabel = "Organize ${app.title}",
                    onClick = { context.launchApp(app, op::notice) }, onLongClick = { selected = app }
                  ), horizontalAlignment = Alignment.CenterHorizontally) {
                    app.icon?.let { icon ->
                      val image = remember(icon) { icon.asImageBitmap() }
                      Image(image, null, Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                      // Shelf floor line, then the faded mirror.
                      Box(Modifier.width(40.dp).height(1.dp).background(Color.White.copy(alpha = if (dark) 0.30f else 0.70f)))
                      Image(image, null, Modifier.size(width = 40.dp, height = 10.dp).graphicsLayer { scaleY = -1f; alpha = 0.22f }, contentScale = ContentScale.FillBounds)
                    } ?: Text("TEMP", style = MaterialTheme.typography.labelSmall, color = homeText)
                  }
                }
              }
            }
          }
        }
      }
      Column(Modifier.fillMaxSize().background(Brush.verticalGradient(gradient.map { it.copy(alpha = .10f) })).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!allApps) Row(Modifier.fillMaxWidth().background(panelBrush, panelShape).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.headlineLarge, color = homeText); Text(time.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), color = homeText, style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { settings = true }) { Text("Home settings") }
        }
        OutlinedTextField(query, { query = it.take(512) }, Modifier.fillMaxWidth().background(panelBrush, panelShape), label = { Text("Search installed apps or calculate") }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        val calculation = remember(query) { runCatching { Expression.format(Expression.evaluate(query)) }.getOrNull() }
        if (calculation != null) Card(Modifier.fillMaxWidth()) { TextButton(onClick = { context.copyText("Calculation result", calculation); op.notice("Result copied") }) { Text("$query = $calculation") } }
        if (allApps) {
          if (query.isNotBlank() && shown.isEmpty()) Text("No matching apps", color = homeText, style = MaterialTheme.typography.bodySmall)
          AppGrid(Modifier.weight(1f))
        } else if (isWide) {
          Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              AppGrid(Modifier.weight(1f))
              PagerCard()
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              WidgetsCard()
              FoldersCard()
            }
          }
          DockCard()
        } else {
          AppGrid(Modifier.weight(1f))
          // Compact narrow home keeps the grid tall: one card for Add + folders,
          // with widget views only when at least one widget exists. No extra
          // padding here to preserve the 64dp card height the tests require.
          Column(Modifier.fillMaxWidth().background(panelBrush, panelShape)) {
            HomeWidgetsCompactWithFolders(
              folders = layout.folders,
              byKeyCount = { keys -> keys.count { it in byKey } },
              onOpenFolder = { openFolder = it },
            )
          }
          PagerCard()
          DockCard()
        }
        Row(Modifier.fillMaxWidth().background(panelBrush, panelShape), horizontalArrangement = Arrangement.SpaceBetween) {
            AquaButton(onClick = { drawer = !drawer; query = "" }) { Text(if (drawer) "Home" else "All apps") }
            TextButton(onClick = { context.launchSafely(defaultHomeIntent(context), op::notice) }) { Text("Default home…") }
        }
        Box(Modifier.fillMaxWidth().background(panelBrush, panelShape)) { OperationStatus(op) }
      }
    }
    selected?.let { app -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.title) }, text = {
        Column {
            Text(app.component.packageName, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { save(layout.copy(favorites = if (app.key in layout.favorites) layout.favorites - app.key else layout.favorites + app.key)); selected = null }) { Text(if (app.key in layout.favorites) "Remove from home" else "Add to home") }
            TextButton(onClick = {
                if (app.key in layout.dock) save(layout.copy(dock = layout.dock - app.key))
                else if (layout.dock.size < 10) save(layout.copy(dock = layout.dock + app.key))
                else op.notice("The dock is full at ten apps. Remove one before adding another.")
                selected = null
            }) { Text(if (app.key in layout.dock) "Remove from dock" else "Add to dock (max 10)") }
            TextButton(onClick = { selected = null; folderName = ""; folderTarget = app }) { Text("Add to folder…") }
            TextButton(onClick = { context.launchSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.component.packageName}")), op::notice); selected = null }) { Text("Android app details") }
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Done") } }) }
    folderTarget?.let { app -> AlertDialog(onDismissRequest = { folderTarget = null }, title = { Text("Folder for ${app.title}") }, text = {
        Column { OutlinedTextField(folderName, { folderName = it.take(60) }, modifier = Modifier.semantics { contentDescription = "Folder name" }, label = { Text("New or existing folder name") }); layout.folders.keys.forEach { name -> TextButton(onClick = { folderName = name }) { Text(name) } } }
    }, confirmButton = { TextButton(onClick = { val name = folderName.trim(); if (name.isNotEmpty()) { save(layout.copy(folders = layout.folders + (name to ((layout.folders[name] ?: emptyList()) + app.key).distinct()))); folderTarget = null } }) { Text("Add") } }, dismissButton = { TextButton(onClick = { folderTarget = null }) { Text("Cancel") } }) }
    openFolder?.let { name -> AlertDialog(onDismissRequest = { openFolder = null }, title = { Text(name) }, text = {
        LazyColumn { items(layout.folders[name].orEmpty().mapNotNull(byKey::get), key = { it.key }) { app -> Row { TextButton(onClick = { context.launchApp(app, op::notice) }, Modifier.weight(1f)) { Text(app.title) }; TextButton(onClick = { save(layout.copy(folders = layout.folders + (name to layout.folders[name].orEmpty().filterNot { it == app.key }))) }) { Text("Remove") } } } }
    }, confirmButton = { TextButton(onClick = { openFolder = null }) { Text("Close") } }, dismissButton = { TextButton(onClick = { save(layout.copy(folders = layout.folders - name)); openFolder = null }) { Text("Remove folder only") } }) }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("Home settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Grid: ${layout.columns} columns")
            Slider(layout.columns.toFloat(), { save(layout.copy(columns = it.toInt())); page = 0 }, valueRange = 3f..6f, steps = 2)
            Row { listOf("blue", "violet", "slate").forEach { tint -> TextButton(onClick = { save(layout.copy(tint = tint)) }) { Text(tint) } } }
            Text("Your Android system wallpaper is shown behind the tinted home surface.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.launchSafely(Intent(Intent.ACTION_SET_WALLPAPER), op::notice) }) { Text("System wallpaper picker…") }
            TextButton(onClick = { export.launch("Wonderdesk-home.json") }) { Text("Export layout JSON") }
            TextButton(onClick = { restore.launch(arrayOf("application/json", "text/plain")) }) { Text("Restore layout JSON…") }
            TextButton(onClick = { confirmReset = true; settings = false }) { Text("Restore default layout…") }
            Text("Long-press to organize. Page controls avoid undiscoverable gestures. No icon packs, drag/drop or cross-device sync yet.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Done") } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset launcher layout?") }, text = { Text("This replaces home favorites, dock, folders and grid only. It does not uninstall apps or remove widget data.") }, confirmButton = { TextButton(onClick = { save(defaults(apps)); page = 0; confirmReset = false; op.notice("Default layout restored") }) { Text("Reset layout") } }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
    confirmRestore?.let { restored -> AlertDialog(onDismissRequest = { confirmRestore = null }, title = { Text("Replace current layout?") }, text = { Text("${restored.favorites.size} favorites, ${restored.dock.size} dock apps, ${restored.folders.size} folders. Uninstalled app references are retained but hidden. Widgets are not included. This does not install anything.") }, confirmButton = { TextButton(onClick = { save(restored); page = 0; confirmRestore = null; op.notice("Layout restored") }) { Text("Replace layout") } }, dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } }) }
}
