@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.wonderdesk.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings as AndroidSettings
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import dev.wonderdesk.shared.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

const val HOST_ID = 5013

class MainActivity : ComponentActivity() {
    lateinit var host: AppWidgetHost
    private val callback = object : LauncherApps.Callback() {
        private fun refresh() = runOnUiThread { Home.loadApps(this@MainActivity) }
        override fun onPackageRemoved(p: String, u: UserHandle) = refresh()
        override fun onPackageAdded(p: String, u: UserHandle) = refresh()
        override fun onPackageChanged(p: String, u: UserHandle) = refresh()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = refresh()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, HOST_ID)
        Home.load(this)
        getSystemService(LauncherApps::class.java).registerCallback(callback)
        setContent { WonderKitTheme { Desktop(this) } }
    }
    override fun onStart() { super.onStart(); runCatching { host.startListening() } }
    override fun onStop() { super.onStop(); runCatching { host.stopListening() } }
    override fun onDestroy() { getSystemService(LauncherApps::class.java).unregisterCallback(callback); super.onDestroy() }
    override fun onResume() { super.onResume(); Home.loadApps(this) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); homePressed.value++ }
    companion object { val homePressed = mutableIntStateOf(0) }
}

/** Optional: lets the launcher lock the screen on double-tap. */
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
    companion object { var instance: LockService? = null }
}

/* ---------------------------------------------------- helpers ----------------------------------------------- */

fun Context.launch(a: App) = runCatching { getSystemService(LauncherApps::class.java).startMainActivity(a.component, a.user, null, null) }
fun Context.appInfo(a: App) = runCatching { getSystemService(LauncherApps::class.java).startAppDetailsActivity(a.component, a.user, null, null) }
fun Context.uninstall(a: App) = launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {}

fun Context.expandNotifications() = runCatching {
    val sb = getSystemService("statusbar"); Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
}

private val labelStyle @Composable get() = MaterialTheme.typography.labelMedium.copy(
    color = if (Home.settings.darkLabels) Color(0xFF1B1D21) else Color.White,
    shadow = if (Home.settings.darkLabels) null else Shadow(Color.Black.copy(alpha = .75f), Offset(0f, 2f), 5f), fontWeight = FontWeight.Medium)

/* ---------------------------------------------------- desktop ----------------------------------------------- */

@Composable
fun Desktop(activity: MainActivity) {
    val context = LocalContext.current
    val s = Home.settings
    val pages = s.pages.coerceAtLeast((Home.items.maxOfOrNull { it.page } ?: 0) + 1)
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    var launchpad by remember { mutableStateOf(false) }
    var launchpadSearch by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<Item.Folder?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var widgetPicker by remember { mutableStateOf(false) }
    var homeMenu by remember { mutableStateOf<Offset?>(null) }
    var dragging by remember { mutableStateOf<Item?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    val home by MainActivity.homePressed
    LaunchedEffect(home) { launchpad = false; folder = null; settingsOpen = false; widgetPicker = false; if (home > 0) pager.animateScrollToPage(0) }
    BackHandler(launchpad || folder != null || settingsOpen) { when { settingsOpen -> settingsOpen = false; folder != null -> folder = null; else -> launchpad = false } }
    fun gesture(action: String) = when (action) {
        "launchpad" -> launchpad = true
        "search" -> { launchpadSearch = true; launchpad = true }
        "notifications" -> { context.expandNotifications(); Unit }
        "lock" -> if (LockService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) != true)
            context.launchSafely(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {} else Unit
        "beacon" -> context.packageManager.getLaunchIntentForPackage("dev.wonderdesk.beacon")?.let { context.launchSafely(it) {} } ?: run { launchpadSearch = true; launchpad = true }
        else -> Unit
    }
    if (settingsOpen) { LauncherSettings(onClose = { settingsOpen = false }); return }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (s.menuBar) MenuBar(onApps = { launchpad = true }, onSearch = { launchpadSearch = true; launchpad = true }, onSettings = { settingsOpen = true }, onWidgets = { widgetPicker = true })
            else Spacer(Modifier.statusBarsPadding())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()
                .pointerInput(s.swipeUp, s.swipeDown) {
                    var dy = 0f
                    detectVerticalDragGestures(onDragEnd = { if (dy < -120) gesture(s.swipeUp) else if (dy > 120) gesture(s.swipeDown); dy = 0f }) { _, d -> dy += d }
                }
                .pointerInput(s.doubleTap) { detectTapGestures(onDoubleTap = { gesture(s.doubleTap) }, onLongPress = { homeMenu = it }) }) {
                val cellW = maxWidth / s.cols
                val cellH = (maxHeight - 24.dp) / s.rows
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = dragging == null) { page ->
                    Box(Modifier.fillMaxSize()) {
                        Home.items.filter { it.page == page }.forEach { item ->
                            val isDragging = dragging?.id == item.id
                            Box(Modifier.offset(cellW * item.x, cellH * item.y).size(cellW * item.w, cellH * item.h)
                                .graphicsLayer { if (isDragging) { translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.12f; scaleY = 1.12f; alpha = .9f } }
                                .then(if (item !is Item.Widget) Modifier.pointerInput(item.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragging = item; dragOffset = Offset.Zero },
                                        onDrag = { ch, d -> ch.consume(); dragOffset += d },
                                        onDragEnd = {
                                            val moved = dragging
                                            if (moved != null) {
                                                val cw = cellW.toPx(); val ch = cellH.toPx()
                                                if (abs(dragOffset.x) < 20 && abs(dragOffset.y) < 20) { /* a still long-press opens the menu */ itemMenu.value = moved }
                                                else Home.drop(context, moved, page, ((moved.x * cw + dragOffset.x) / cw).roundToInt().coerceIn(0, s.cols - 1), ((moved.y * ch + dragOffset.y) / ch).roundToInt().coerceIn(0, s.rows - 1))
                                            }
                                            dragging = null; dragOffset = Offset.Zero
                                        },
                                        onDragCancel = { dragging = null; dragOffset = Offset.Zero })
                                } else Modifier), contentAlignment = Alignment.Center) {
                                when (item) {
                                    is Item.Shortcut -> Home.app(item.app)?.let { a -> AppIcon(a, onClick = { context.launch(a) }) }
                                    is Item.Folder -> FolderIcon(item) { folder = item }
                                    is Item.Widget -> WidgetView(activity, item)
                                }
                            }
                        }
                    }
                }
                // page dots
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(pages) { i -> Box(Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) .95f else .45f))) }
                }
            }
            Dock(onLaunchpad = { launchpad = true })
        }
        itemMenu.value?.let { item -> ItemMenu(item, onDismiss = { itemMenu.value = null }, onOpenFolder = { folder = it }) }
        homeMenu?.let { _ ->
            HomeMenu(onDismiss = { homeMenu = null }, onWidgets = { widgetPicker = true }, onSettings = { settingsOpen = true }, onAddPage = {
                Home.settings = Home.settings.copy(pages = pages + 1); Home.save(context); scope.launch { pager.animateScrollToPage(pages) }
            })
        }
        AnimatedVisibility(launchpad, enter = fadeIn() + scaleIn(initialScale = 1.08f), exit = fadeOut() + scaleOut(targetScale = 1.08f)) {
            Launchpad(focusSearch = launchpadSearch, onClose = { launchpad = false; launchpadSearch = false }, onPage = pager.currentPage)
        }
        folder?.let { f -> Home.items.find { it.id == f.id } as? Item.Folder }?.let { f -> FolderPopup(f) { folder = null } } ?: run { if (folder != null) folder = null }
        if (widgetPicker) WidgetPicker(activity, pager.currentPage) { widgetPicker = false }
    }
}

private val itemMenu = mutableStateOf<Item?>(null)

/* --------------------------------------------------- menu bar ----------------------------------------------- */

@Composable
private fun MenuBar(onApps: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit, onWidgets: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(15_000) } }
    var orb by remember { mutableStateOf(false) }
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF2FAFBFC), Color(0xE6E4E7EB)))).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                val art = remember { context.appArt(64) }
                Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable { orb = true }.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    art?.let { Image(it, "Wonderdesk menu", Modifier.size(20.dp)) }
                    Spacer(Modifier.width(6.dp))
                    Text("Wonderdesk", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1B1D21)))
                }
                DropdownMenu(orb, { orb = false }, Modifier.background(Color(0xF7F5F6F8))) {
                    DropdownMenuItem({ Text("Launchpad") }, { orb = false; onApps() }, leadingIcon = { Icon(WonderIcons.Apps, null) })
                    DropdownMenuItem({ Text("Add a widget…") }, { orb = false; onWidgets() }, leadingIcon = { Icon(WonderIcons.Widgets, null) })
                    DropdownMenuItem({ Text("Change wallpaper…") }, { orb = false; context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) {} }, leadingIcon = { Icon(WonderIcons.Wallpaper, null) })
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Control Room") }, { orb = false; context.packageManager.getLaunchIntentForPackage("dev.wonderdesk.controlroom")?.let { context.launchSafely(it) {} } ?: context.launchSafely(Intent(AndroidSettings.ACTION_SETTINGS)) {} }, leadingIcon = { Icon(WonderIcons.Tune, null) })
                    DropdownMenuItem({ Text("Wonderdesk Apphub") }, { orb = false; context.packageManager.getLaunchIntentForPackage("dev.wonderdesk.apphub")?.let { context.launchSafely(it) {} } }, leadingIcon = { Icon(WonderIcons.SystemUpdate, null) })
                    DropdownMenuItem({ Text("Home screen settings") }, { orb = false; onSettings() }, leadingIcon = { Icon(WonderIcons.Settings, null) })
                }
            }
            Spacer(Modifier.width(14.dp))
            Text("Apps", Modifier.clickable(onClick = onApps), style = TextStyle(fontSize = 14.sp, color = Color(0xFF1B1D21)))
            Spacer(Modifier.weight(1f))
            Icon(WonderIcons.Search, "Search", Modifier.size(20.dp).clickable(onClick = onSearch), tint = Color(0xFF30343A))
            Spacer(Modifier.width(12.dp))
            Text(now.format(DateTimeFormatter.ofPattern(if (is24) "EEE d MMM  HH:mm" else "EEE d MMM  h:mm a")), style = TextStyle(fontSize = 14.sp, color = Color(0xFF1B1D21), fontWeight = FontWeight.Medium))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x55000000)))
    }
}

/* ----------------------------------------------------- icons ------------------------------------------------ */

@Composable
fun AppIcon(a: App, size: androidx.compose.ui.unit.Dp = (56 * Home.settings.iconScale).dp, label: Boolean = Home.settings.labels, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(a.icon.asImageBitmap(), Home.label(a), Modifier.size(size).shadow(4.dp, RoundedCornerShape(size * .22f), clip = false))
        if (label) Text(Home.label(a), Modifier.padding(top = 4.dp).widthIn(max = size + 24.dp), style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FolderIcon(f: Item.Folder, onClick: () -> Unit) {
    val size = (56 * Home.settings.iconScale).dp
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(RoundedCornerShape(size * .24f)).background(Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x66C8D2DC)))).border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(size * .24f)).padding(size * .1f)) {
            val apps = f.apps.mapNotNull { Home.app(it) }.take(4)
            Column(verticalArrangement = Arrangement.spacedBy(size * .06f)) {
                apps.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(size * .06f)) { row.forEach { Image(it.icon.asImageBitmap(), null, Modifier.size(size * .36f)) } } }
            }
        }
        if (Home.settings.labels) Text(f.name, Modifier.padding(top = 4.dp).widthIn(max = size + 24.dp), style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/* ------------------------------------------------------ dock ------------------------------------------------ */

@Composable
private fun Dock(onLaunchpad: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val apps = Home.dock.mapNotNull { Home.app(it) }
    var touchX by remember { mutableStateOf<Float?>(null) }
    val base = 50.dp
    val centers = remember { mutableStateMapOf<Int, Float>() }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.BottomCenter) {
        // Glass shelf, drawn in perspective like the Leopard dock.
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val inset = size.width * .04f
            val path = Path().apply { moveTo(inset, 0f); lineTo(size.width - inset, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(path, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x33DDE6F0), Color(0x55FFFFFF))))
            drawLine(Color(0xCCFFFFFF), Offset(inset, 1f), Offset(size.width - inset, 1f), 2f)
            drawLine(Color(0x66000000), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
        }
        Row(Modifier.padding(bottom = 10.dp).pointerInput(apps.size, Home.settings.dockMagnify) {
            if (!Home.settings.dockMagnify) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull() ?: continue
                    if (c.pressed) touchX = c.position.x else {
                        // Release over an icon opens it, like the Mac dock.
                        if (touchX != null && abs(c.position.y) < size.height) {
                            val x = c.position.x
                            centers.minByOrNull { abs(it.value - x) }?.takeIf { abs(it.value - x) < with(density) { base.toPx() } }?.key?.let { i ->
                                if (i == apps.size) onLaunchpad() else apps.getOrNull(i)?.let { context.launch(it) }
                            }
                        }
                        touchX = null
                    }
                }
            }
        }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            (apps.map<App, App?> { it } + listOf(null)).forEachIndexed { i, a ->
                val scale = touchX?.let { tx -> centers[i]?.let { cx -> val d = abs(tx - cx) / with(density) { base.toPx() }; 1f + .55f * (1f - (d / 2.2f)).coerceIn(0f, 1f) } } ?: 1f
                val anim by androidx.compose.animation.core.animateFloatAsState(scale, androidx.compose.animation.core.tween(90), label = "dock")
                Column(Modifier.onGloballyPositioned { centers[i] = it.positionInRoot().x - it.parentLayoutCoordinatesOffsetX() + it.size.width / 2f }
                    .graphicsLayer { scaleX = anim; scaleY = anim; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 1f) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (a == null) Box(Modifier.size(base).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF6A7A8C), Color(0xFF2E3742)))).clickable(onClick = onLaunchpad),
                        contentAlignment = Alignment.Center) { Icon(WonderIcons.Apps, "Launchpad", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    else Image(a.icon.asImageBitmap(), Home.label(a), Modifier.size(base).clickable { context.launch(a) })
                    // reflection
                    if (a != null) Image(a.icon.asImageBitmap(), null, Modifier.size(base, 10.dp).graphicsLayer { scaleY = -1f; alpha = .25f }, contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.BottomCenter)
                    else Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

private fun androidx.compose.ui.layout.LayoutCoordinates.parentLayoutCoordinatesOffsetX(): Float = parentLayoutCoordinates?.positionInRoot()?.x ?: 0f

/* --------------------------------------------------- launchpad ---------------------------------------------- */

@Composable
private fun Launchpad(focusSearch: Boolean, onClose: () -> Unit, onPage: Int) {
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<App?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(focusSearch) { if (focusSearch) runCatching { delay(150); focus.requestFocus() } }
    val apps = Home.apps.filter { it.key !in Home.hidden && (q.isBlank() || Home.label(it).contains(q, true) || it.pkg.contains(q, true)) }
        .let { if (Home.settings.launchpadSort == "recent") it.sortedByDescending { a -> a.installed } else it.sortedBy { a -> Home.label(a).lowercase() } }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xE6121820), Color(0xF00A0D12)))).pointerInput(Unit) { detectTapGestures { onClose() } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Box(Modifier.padding(vertical = 14.dp).widthIn(max = 420.dp).align(Alignment.CenterHorizontally).focusRequester(focus)) {
                SearchField(q, { q = it }, "Search apps", onSubmit = { apps.firstOrNull()?.let { context.launch(it); onClose() } })
            }
            LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(apps, key = { it.key }) { a ->
                    Box(Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).pointerInput(a.key) { detectTapGestures(onTap = { context.launch(a); onClose() }, onLongPress = { menuFor = a }) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(a.icon.asImageBitmap(), Home.label(a), Modifier.size(58.dp))
                            Text(Home.label(a), Modifier.padding(top = 4.dp).width(80.dp), style = MaterialTheme.typography.labelMedium.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                        DropdownMenu(menuFor == a, { menuFor = null }) {
                            DropdownMenuItem({ Text("Add to home screen") }, { menuFor = null; Home.addShortcut(context, a.key, onPage); onClose() }, leadingIcon = { Icon(WonderIcons.Home, null) })
                            if (a.key !in Home.dock && Home.dock.size < 7) DropdownMenuItem({ Text("Add to Dock") }, { menuFor = null; Home.dock = Home.dock + a.key; Home.save(context) }, leadingIcon = { Icon(WonderIcons.Dashboard, null) })
                            DropdownMenuItem({ Text("Hide from Launchpad") }, { menuFor = null; Home.hidden = Home.hidden + a.key; Home.save(context) }, leadingIcon = { Icon(WonderIcons.VisibilityOff, null) })
                            DropdownMenuItem({ Text("App info") }, { menuFor = null; context.appInfo(a) }, leadingIcon = { Icon(WonderIcons.Info, null) })
                            DropdownMenuItem({ Text("Uninstall") }, { menuFor = null; context.uninstall(a) }, leadingIcon = { Icon(WonderIcons.Delete, null) })
                        }
                    }
                }
            }
        }
    }
}


/* ---------------------------------------------------- folders ----------------------------------------------- */

@Composable
private fun FolderPopup(f: Item.Folder, onClose: () -> Unit) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)).pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(22.dp)).background(Brush.verticalGradient(listOf(Color(0xE8E9EDF2), Color(0xE0CDD5DE))))
            .border(1.dp, Color(0xAAFFFFFF), RoundedCornerShape(22.dp)).pointerInput(Unit) { detectTapGestures { } }.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(f.name, Modifier.clickable { renaming = true }.padding(bottom = 12.dp), style = MaterialTheme.typography.titleLarge.copy(color = Color(0xFF1B1D21), fontWeight = FontWeight.SemiBold))
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 420.dp)) {
                items(f.apps, key = { it }) { key ->
                    val a = Home.app(key) ?: return@items
                    Box {
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).pointerInput(key) { detectTapGestures(onTap = { context.launch(a); onClose() }, onLongPress = { menuFor = key }) }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(a.icon.asImageBitmap(), null, Modifier.size(52.dp))
                            Text(Home.label(a), style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF1B1D21)), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                        DropdownMenu(menuFor == key, { menuFor = null }) {
                            DropdownMenuItem({ Text("Remove from folder") }, { menuFor = null
                                val rest = f.apps - key
                                if (rest.size <= 1) { Home.remove(context, f.id); rest.firstOrNull()?.let { Home.items = Home.items + Item.Shortcut(UUID.randomUUID().toString(), it, f.page, f.x, f.y); Home.save(context) }; onClose() }
                                else Home.update(context, f.copy(apps = rest))
                                Home.addShortcut(context, key, f.page)
                            })
                            DropdownMenuItem({ Text("App info") }, { menuFor = null; context.appInfo(a) })
                        }
                    }
                }
            }
        }
    }
    if (renaming) InputDialog("Rename folder", "Name", initial = f.name, onDismiss = { renaming = false }) { Home.update(context, f.copy(name = it)) }
}

/* -------------------------------------------------- item menus ---------------------------------------------- */

@Composable
private fun ItemMenu(item: Item, onDismiss: () -> Unit, onOpenFolder: (Item.Folder) -> Unit) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    val app = (item as? Item.Shortcut)?.let { Home.app(it.app) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(app?.let { Home.label(it) } ?: (item as? Item.Folder)?.name ?: "Widget") }, text = {
        Panel {
            if (app != null) {
                ListRow("Open", icon = WonderIcons.OpenInNew, onClick = { onDismiss(); context.launch(app) }); PanelDivider()
                if (app.key !in Home.dock && Home.dock.size < 7) { ListRow("Add to Dock", icon = WonderIcons.Dashboard, tint = Color(0xFF16A085), onClick = { onDismiss(); Home.dock = Home.dock + app.key; Home.save(context) }); PanelDivider() }
                ListRow("Rename", icon = WonderIcons.DriveFileRenameOutline, tint = Color(0xFF8E44AD), onClick = { renaming = true }); PanelDivider()
                ListRow("App info", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), onClick = { onDismiss(); context.appInfo(app) }); PanelDivider()
                ListRow("Uninstall", icon = WonderIcons.Delete, danger = true, onClick = { onDismiss(); context.uninstall(app) }); PanelDivider()
            }
            if (item is Item.Folder) { ListRow("Open folder", icon = WonderIcons.FolderOpen, onClick = { onDismiss(); onOpenFolder(item) }); PanelDivider() }
            ListRow("Remove from home screen", icon = WonderIcons.Close, tint = Color(0xFF7A7F87), onClick = { onDismiss(); Home.remove(context, item.id) })
        }
        Text("Tip: hold and drag to move. Drop one app on another to make a folder.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (renaming && app != null) InputDialog("Rename ${app.label}", "Name", initial = Home.label(app), onDismiss = { renaming = false }) { n ->
        Home.names = Home.names + (app.key to n); Home.save(context); onDismiss()
    }
}

@Composable
private fun HomeMenu(onDismiss: () -> Unit, onWidgets: () -> Unit, onSettings: () -> Unit, onAddPage: () -> Unit) {
    val context = LocalContext.current
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Home screen") }, text = {
        Panel {
            ListRow("Add a widget", icon = WonderIcons.Widgets, tint = Color(0xFF1E7FE8), onClick = { onDismiss(); onWidgets() }); PanelDivider()
            ListRow("Change wallpaper", icon = WonderIcons.Wallpaper, tint = Color(0xFF8E44AD), onClick = { onDismiss(); context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) {} }); PanelDivider()
            ListRow("Add a page", icon = WonderIcons.Add, tint = Color(0xFF2E9E44), onClick = { onDismiss(); onAddPage() }); PanelDivider()
            ListRow("Home screen settings", icon = WonderIcons.Settings, tint = Color(0xFF7A7F87), onClick = { onDismiss(); onSettings() })
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/* ---------------------------------------------------- widgets ----------------------------------------------- */

@Composable
private fun WidgetView(activity: MainActivity, w: Item.Widget) {
    val context = LocalContext.current
    val mgr = remember { AppWidgetManager.getInstance(context) }
    val info = remember(w.widgetId) { mgr.getAppWidgetInfo(w.widgetId) }
    var menu by remember { mutableStateOf(false) }
    if (info == null) { Text("Widget unavailable", style = labelStyle); return }
    Box(Modifier.fillMaxSize().padding(4.dp)) {
        AndroidView({ activity.host.createView(it, w.widgetId, info).apply { setPadding(0, 0, 0, 0) } }, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.TopEnd).size(28.dp).clip(CircleShape).clickable { menu = true })
        DropdownMenu(menu, { menu = false }) {
            listOf(1 to 1, 2 to 1, 2 to 2, 4 to 1, 4 to 2, 4 to 3).forEach { (cw, ch) ->
                if (Home.fits(w.page, w.x, w.y, cw, ch, w.id)) DropdownMenuItem({ Text("Size ${cw}×$ch") }, { menu = false; Home.update(context, w.copy(w = cw, h = ch)) })
            }
            DropdownMenuItem({ Text("Remove widget") }, { menu = false; activity.host.deleteAppWidgetId(w.widgetId); Home.remove(context, w.id) })
        }
    }
}

@Composable
private fun WidgetPicker(activity: MainActivity, page: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mgr = remember { AppWidgetManager.getInstance(context) }
    val providers = remember { mgr.installedProviders.sortedBy { it.loadLabel(context.packageManager).lowercase() } }
    var pending by remember { mutableIntStateOf(-1) }
    var pendingInfo by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }
    fun place(id: Int, info: AppWidgetProviderInfo) {
        val dm = context.resources.displayMetrics
        val cellPx = dm.widthPixels / Home.settings.cols
        val cw = ((info.minWidth + cellPx / 2) / cellPx).coerceIn(1, Home.settings.cols)
        val ch = ((info.minHeight + cellPx / 2) / cellPx).coerceIn(1, Home.settings.rows)
        val cell = Home.freeCell(page, cw, ch) ?: Home.freeCell(0, cw, ch)
        if (cell == null) { activity.host.deleteAppWidgetId(id); return }
        Home.items = Home.items + Item.Widget(UUID.randomUUID().toString(), id, cell.first, cell.second, cell.third, cw, ch); Home.save(context)
        onDismiss()
    }
    fun bound(id: Int, info: AppWidgetProviderInfo) {
        // Widgets with a setup screen get it now; the widget is placed right away and fills in once set up.
        if (info.configure != null) runCatching { activity.host.startAppWidgetConfigureActivityForResult(activity, id, 0, 4242, null) }
        place(id, info)
    }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val info = pendingInfo ?: return@rememberLauncherForActivityResult
        if (r.resultCode == Activity.RESULT_OK) bound(pending, info) else activity.host.deleteAppWidgetId(pending)
    }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Add a widget") }, text = {
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(providers) { pr ->
                val preview = remember(pr) { runCatching { pr.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)?.toBitmap(240, 160)?.asImageBitmap() }.getOrNull()
                    ?: runCatching { pr.loadIcon(context, context.resources.displayMetrics.densityDpi).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                ListRow(pr.loadLabel(context.packageManager), subtitle = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pr.provider.packageName, 0)).toString() }.getOrNull(),
                    leading = { preview?.let { Image(it, null, Modifier.size(48.dp)) } }, onClick = {
                        val id = activity.host.allocateAppWidgetId()
                        pending = id; pendingInfo = pr
                        if (mgr.bindAppWidgetIdIfAllowed(id, pr.provider)) bound(id, pr)
                        else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, pr.provider))
                    })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/* ---------------------------------------------------- settings ---------------------------------------------- */

@Composable
private fun LauncherSettings(onClose: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(Home.settings) }
    fun set(n: Settings) { s = n; Home.settings = n; Home.save(context) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { runCatching { context.contentResolver.openOutputStream(it, "wt")!!.use { o -> o.write(Home.exportLayout().toByteArray()) } } } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { Home.importLayout(context, context.contentResolver.openInputStream(it)!!.bufferedReader().readText()) } } }
    var hiddenOpen by remember { mutableStateOf(false) }
    WonderApp(appName = "Wonderdesk Launcher", about = "The Wonderdesk home screen: an Aqua desktop with a magnifying Dock, Launchpad, folders, widgets and gestures.", title = "Home screen", onBack = onClose) {
        Page {
            Panel(title = "Default home") {
                ListRow("Make Wonderdesk your home screen", subtitle = "Choose it in Android's home app setting", icon = WonderIcons.Home, tint = Color(0xFF2E9E44), chevron = true,
                    onClick = { context.launchSafely(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) {} })
            }
            Panel(title = "Layout") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Columns: ${s.cols}", style = MaterialTheme.typography.titleSmall); Segmented(listOf("3", "4", "5", "6"), s.cols - 3) { set(s.copy(cols = it + 3)) }
                    Text("Rows: ${s.rows}", style = MaterialTheme.typography.titleSmall); Segmented(listOf("4", "5", "6", "7"), s.rows - 4) { set(s.copy(rows = it + 4)) }
                    Text("Icon size", style = MaterialTheme.typography.titleSmall)
                    Slider(s.iconScale, { set(s.copy(iconScale = it)) }, valueRange = .75f..1.3f)
                }
                PanelDivider(0.dp)
                SwitchRow("Show app names", s.labels, { set(s.copy(labels = it)) })
                PanelDivider()
                SwitchRow("Dark names (for light wallpapers)", s.darkLabels, { set(s.copy(darkLabels = it)) })
                PanelDivider()
                SwitchRow("Menu bar", s.menuBar, { set(s.copy(menuBar = it)) }, subtitle = "The Aqua bar with the Wonderdesk menu and clock")
            }
            Panel(title = "Dock") {
                SwitchRow("Magnify icons", s.dockMagnify, { set(s.copy(dockMagnify = it)) }, subtitle = "Icons grow as your finger moves across the Dock")
                PanelDivider()
                ListRow("Clear the Dock", icon = WonderIcons.DeleteSweep, tint = Color(0xFF7A7F87), onClick = { Home.dock = emptyList(); Home.save(context) })
            }
            Panel(title = "Gestures") {
                listOf(Triple("Swipe up", s.swipeUp) { v: String -> set(s.copy(swipeUp = v)) }, Triple("Swipe down", s.swipeDown) { v: String -> set(s.copy(swipeDown = v)) },
                    Triple("Double-tap", s.doubleTap) { v: String -> set(s.copy(doubleTap = v)) }).forEachIndexed { i, (label, cur, setter) ->
                    if (i > 0) PanelDivider(16.dp)
                    var open by remember { mutableStateOf(false) }
                    Box {
                        ListRow(label, trailing = { Text(gestureName(cur), color = wonderPalette().secondaryText) }, chevron = true, onClick = { open = true })
                        DropdownMenu(open, { open = false }) {
                            listOf("launchpad", "search", "beacon", "notifications", "lock", "none").forEach { g -> DropdownMenuItem({ Text(gestureName(g)) }, { open = false; setter(g) }) }
                        }
                    }
                }
            }
            Panel(title = "Launchpad") {
                ListRow("Sort", trailing = { Segmented(listOf("A–Z", "Newest"), if (s.launchpadSort == "recent") 1 else 0, Modifier.width(170.dp)) { set(s.copy(launchpadSort = if (it == 1) "recent" else "name")) } })
                PanelDivider()
                ListRow("Hidden apps", subtitle = "${Home.hidden.size} hidden", icon = WonderIcons.VisibilityOff, tint = Color(0xFF7A7F87), chevron = true, onClick = { hiddenOpen = true })
            }
            Panel(title = "Backup") {
                ListRow("Export layout", icon = WonderIcons.Download, tint = Color(0xFF16A085), onClick = { export.launch("wonderdesk-home.json") }); PanelDivider()
                ListRow("Import layout", icon = WonderIcons.UploadFile, tint = Color(0xFF1E7FE8), onClick = { import.launch(arrayOf("application/json", "*/*")) })
            }
        }
    }
    if (hiddenOpen) WonderDialog(onDismissRequest = { hiddenOpen = false }, title = { Text("Hidden apps") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(Home.apps.sortedBy { Home.label(it).lowercase() }, key = { it.key }) { a ->
                ListRow(Home.label(a), leading = { Image(a.icon.asImageBitmap(), null, Modifier.size(32.dp)) }, trailing = {
                    Checkbox(a.key in Home.hidden, { on -> Home.hidden = if (on) Home.hidden + a.key else Home.hidden - a.key; Home.save(context) })
                })
            }
        }
    }, confirmButton = { TextButton(onClick = { hiddenOpen = false }) { Text("Done") } })
}

private fun gestureName(g: String) = when (g) { "launchpad" -> "Open Launchpad"; "search" -> "Search apps"; "beacon" -> "Open Beacon"; "notifications" -> "Show notifications"; "lock" -> "Lock screen"; else -> "Nothing" }
