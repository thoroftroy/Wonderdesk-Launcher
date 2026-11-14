@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.wonderdesk.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.WallpaperManager
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

const val HOST_ID = 5013

class MainActivity : ComponentActivity() {
    lateinit var host: WdWidgetHost
    private val callback = object : LauncherApps.Callback() {
        private fun refresh() = runOnUiThread { Home.loadApps(this@MainActivity) }
        override fun onPackageRemoved(p: String, u: UserHandle) = refresh()
        override fun onPackageAdded(p: String, u: UserHandle) = refresh()
        override fun onPackageChanged(p: String, u: UserHandle) = refresh()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = refresh()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = refresh()
    }
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        host = WdWidgetHost(this, HOST_ID)
        Sounds.enabled = Home.settings.sounds; Sounds.init(applicationContext)
        Home.load(this)
        getSystemService(LauncherApps::class.java).registerCallback(callback)
        applyRotation()
        setContent { WonderKitTheme { Desktop(this) } }
    }
    fun applyRotation() {
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        requestedOrientation = if (Home.settings.rotate || tablet) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
    override fun onStart() { super.onStart(); runCatching { host.startListening() } }
    override fun onStop() { super.onStop(); runCatching { host.stopListening() } }
    override fun onDestroy() { getSystemService(LauncherApps::class.java).unregisterCallback(callback); super.onDestroy() }
    override fun onResume() {
        super.onResume(); resumed = true
        Home.refreshIfChanged(this)
        applyRotation()
        if (Home.settings.askDefault && !isDefaultHome()) welcome.value = true
    }
    override fun onPause() { super.onPause(); resumed = false }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home while already on the home screen goes to the main page; coming back from an app keeps your page.
        if (resumed) homePressed.intValue++ else returned.intValue++
    }
    val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (!isDefaultHome()) launchSafely(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) {} }
    fun requestDefault() {
        val rm = getSystemService(android.app.role.RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME))
            runCatching { roleRequest.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)) }.onFailure { launchSafely(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) {} }
        else launchSafely(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) {}
    }
    companion object { val homePressed = mutableIntStateOf(0); val returned = mutableIntStateOf(0); val welcome = mutableStateOf(false) }
}

/** Optional: lets the launcher lock the screen with a gesture. */
class LockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
    companion object { var instance: LockService? = null }
}

/* ---------------------------------------------------- helpers ----------------------------------------------- */

fun Context.isDefaultHome(): Boolean = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName

/** Where the last tap on an icon landed (window coordinates), so the app can grow out of it. */
var lastTap: Offset? = null

fun Context.launch(a: App) = runCatching {
    Sounds.play(this, Sounds.S.Open)
    // Apps open by growing out of the icon you tapped, like a stock launcher.
    val opts = (this as? android.app.Activity)?.window?.decorView?.let { v -> lastTap?.let { p ->
        val s = (64 * resources.displayMetrics.density).toInt()
        android.app.ActivityOptions.makeScaleUpAnimation(v, p.x.toInt() - s / 2, p.y.toInt() - s / 2, s, s).toBundle()
    } }
    lastTap = null
    getSystemService(LauncherApps::class.java).startMainActivity(a.component, a.user, null, opts); Home.recordLaunch(this, a.key)
}
fun Context.appInfo(a: App) = runCatching { getSystemService(LauncherApps::class.java).startAppDetailsActivity(a.component, a.user, null, null) }
fun Context.uninstall(a: App) = launchSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")).putExtra(Intent.EXTRA_USER, a.user).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {}

fun Context.expandNotifications() = runCatching {
    val sb = getSystemService("statusbar"); Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
}

private fun Context.openPackageOr(pkg: String, fallback: Intent) {
    val i = packageManager.getLaunchIntentForPackage(pkg) ?: fallback
    launchSafely(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {}
}

private val labelStyle @Composable get() = MaterialTheme.typography.labelMedium.copy(
    color = if (Home.settings.darkLabels) Color(0xFF1B1D21) else Color.White,
    shadow = if (Home.settings.darkLabels) null else Shadow(Color.Black.copy(alpha = .8f), Offset(0f, 2f), 6f), fontWeight = FontWeight.Medium)

/* ---------------------------------------------------- drag & drop ------------------------------------------- */

sealed class Payload {
    data class HomeItem(val item: Item) : Payload()
    data class DockApp(val key: String) : Payload()
    data class FolderApp(val folderId: String, val key: String) : Payload()
    /** From Launchpad or a Launchpad folder. */
    data class GridApp(val key: String) : Payload()

    val appKey: String? get() = when (this) { is HomeItem -> (item as? Item.Shortcut)?.app; is DockApp -> key; is FolderApp -> key; is GridApp -> key }
}

sealed class Hover {
    data class Cell(val page: Int, val x: Int, val y: Int, val merge: Boolean) : Hover()
    data class Dock(val index: Int) : Hover()
    data object Remove : Hover()
    data object Uninstall : Hover()
    data object Info : Hover()
}

/** Shared state of whatever is being dragged, in root coordinates. */
class DragController {
    var payload by mutableStateOf<Payload?>(null)
    var dragging by mutableStateOf(false)
    var pos by mutableStateOf(Offset.Zero)
    var hover by mutableStateOf<Hover?>(null)
    var gridRect = Rect.Zero
    var cellW = 1f; var cellH = 1f
    var dockRect = Rect.Zero
    val dockCenters = mutableMapOf<Int, Float>()
    val targets = mutableMapOf<String, Rect>()
    var mergeCell: Triple<Int, Int, Int>? = null; var mergeSince = 0L
    var edgeDir = 0; var edgeSince = 0L
    val active get() = payload != null
    fun reset() { payload = null; dragging = false; hover = null; mergeCell = null; edgeDir = 0 }
}

private class Coords { var c: LayoutCoordinates? = null }

private val dropRequested = mutableIntStateOf(0)
/** True while a finger is down on an icon, so the empty-desktop long press doesn't also fire. */
private var itemTouch = false

/**
 * Tap, long-press (menu) and long-press-then-drag on one element, the same way everywhere:
 * home icons, folders, the Dock, Launchpad and folder popups.
 */
/** The Dock app you just opened: it bounces like an app launching on a Mac. */
private val dockBouncing = mutableStateOf<Pair<String, Long>?>(null)

private fun Modifier.dockBounce(key: String): Modifier = composed {
    val y = remember { androidx.compose.animation.core.Animatable(0f) }
    val b = dockBouncing.value
    LaunchedEffect(b) {
        if (b?.first != key) return@LaunchedEffect
        repeat(2) {
            y.animateTo(-1f, androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            y.animateTo(0f, androidx.compose.animation.core.tween(190, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    graphicsLayer { translationY = y.value * 22.dp.toPx() }
}

/** The icon under your finger, so it can sink in like a button (and spring back). */
private val pressedIcon = mutableStateOf<Any?>(null)

private fun Modifier.liftable(key: Any, coords: Coords, drag: DragController, payload: () -> Payload, onTap: () -> Unit, onMenu: () -> Unit, onDragStart: () -> Unit = {}, haptic: () -> Unit) = composed {
    val sc by androidx.compose.animation.core.animateFloatAsState(if (pressedIcon.value == key) .86f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = .55f, stiffness = 700f), label = "press")
    // While something is being dragged, the other icons wiggle gently, like rearranging on an iPhone. Only runs during a drag.
    val wiggle = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragging = drag.dragging && drag.payload?.let { (it as? Payload.HomeItem)?.item?.id != key && (it as? Payload.DockApp)?.key != key } != false
    LaunchedEffect(dragging) {
        if (!dragging) { wiggle.animateTo(0f, androidx.compose.animation.core.tween(120)); return@LaunchedEffect }
        val phase = (key.hashCode() and 3) * 40L
        kotlinx.coroutines.delay(phase)
        while (true) { wiggle.animateTo(1f, androidx.compose.animation.core.tween(130)); wiggle.animateTo(-1f, androidx.compose.animation.core.tween(130)) }
    }
    graphicsLayer { scaleX = sc; scaleY = sc; rotationZ = wiggle.value * 2.2f }.pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            itemTouch = true
            pressedIcon.value = key
            lastTap = coords.c?.takeIf { it.isAttached }?.localToWindow(down.position)
            try {
            var moved = false
            val released: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var result = false
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed) { result = true; break }
                    if (ch.isConsumed || (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                }
                result
            }
            if (released == true) { pressedIcon.value = null; onTap(); return@awaitEachGesture }
            if (moved) { pressedIcon.value = null; return@awaitEachGesture }
            haptic()
            val root = { p: Offset -> coords.c?.takeIf { it.isAttached }?.localToRoot(p) ?: p }
            drag.payload = payload(); drag.pos = root(down.position)
            var dragging = false
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) break
                if (!dragging && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop * 1.5f) { dragging = true; drag.dragging = true; onDragStart() }
                if (dragging) drag.pos = root(ch.position)
                ch.consume()
            }
            if (!dragging) { drag.reset(); onMenu() } else dropRequested.intValue++
            } finally { itemTouch = false; pressedIcon.value = null }
        }
    }
}

/** Takes [key] out of a home folder; a folder left with one app becomes that app. */
private fun removeFromFolder(c: Context, folderId: String, key: String) {
    val f = Home.items.find { it.id == folderId } as? Item.Folder ?: return
    val rest = f.apps - key
    Home.items = when (rest.size) {
        0 -> Home.items.filter { it.id != f.id }
        1 -> Home.items.map { if (it.id == f.id) Item.Shortcut(Home.newId(), rest[0], f.page, f.x, f.y) else it }
        else -> Home.items.map { if (it.id == f.id) f.copy(apps = rest) else it }
    }
    Home.save(c)
}

/* ---------------------------------------------------- desktop ----------------------------------------------- */

@Composable
fun Desktop(activity: MainActivity) {
    val context = LocalContext.current
    val s = Home.settings
    val pages = Home.pageCount
    val infinite = s.infinite && pages > 1
    val virtual = if (infinite) pages * 1000 else pages
    fun real(v: Int) = ((v % pages) + pages) % pages
    val pager = rememberPagerState(initialPage = if (infinite) pages * 500 + Home.currentPage.coerceIn(0, pages - 1) else Home.currentPage.coerceIn(0, pages - 1)) { virtual }
    val currentPage = real(pager.currentPage)
    LaunchedEffect(Home.jumpTo) { Home.jumpTo?.let { t -> delay(250); runCatching { pager.animateScrollToPage(pager.currentPage + (t - real(pager.currentPage))) }; Home.jumpTo = null } }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val drag = remember { DragController() }
    val toast = remember { mutableStateOf<(String) -> Unit>({}) }
    var launchpad by remember { mutableStateOf(false) }
    var launchpadSearch by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<Pair<String, Rect>?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var hadFolder by remember { mutableStateOf(false) }
    LaunchedEffect(folder != null) { if (folder != null) hadFolder = true else if (hadFolder) { hadFolder = false; Sounds.play(context, Sounds.S.FolderClose) } }
    var widgetPicker by remember { mutableStateOf(false) }
    var wallpaper by remember { mutableStateOf(false) }
    var homeMenu by remember { mutableStateOf(false) }
    var pagesDialog by remember { mutableStateOf(false) }
    var addApps by remember { mutableStateOf(false) }
    var itemMenu by remember { mutableStateOf<Item?>(null) }
    var dockMenu by remember { mutableStateOf<String?>(null) }
    var widgetMenu by remember { mutableStateOf<Item.Widget?>(null) }
    var movingWidget by remember { mutableStateOf<Item.Widget?>(null) }
    var lockGuide by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    LaunchedEffect(pager.currentPage) { Home.savePage(context, currentPage) }
    val home by MainActivity.homePressed
    LaunchedEffect(home) {
        if (home == 0) return@LaunchedEffect
        if (!launchpad && folder == null && !settingsOpen && !widgetPicker && !wallpaper) {
            val target = s.defaultPage.coerceIn(0, pages - 1)
            pager.animateScrollToPage(pager.currentPage + (target - currentPage))
        }
        launchpad = false; folder = null; settingsOpen = false; widgetPicker = false; wallpaper = false; homeMenu = false; movingWidget = null
    }
    val back by MainActivity.returned
    // Home from anywhere (another app, the system picture picker, Android settings) closes every panel and returns to the desktop.
    LaunchedEffect(back) { if (back > 0) { launchpad = false; folder = null; settingsOpen = false; widgetPicker = false; wallpaper = false; homeMenu = false; movingWidget = null } }

    BackHandler(launchpad || folder != null || settingsOpen || widgetPicker || wallpaper || movingWidget != null) {
        when { movingWidget != null -> movingWidget = null; wallpaper -> wallpaper = false; widgetPicker -> widgetPicker = false; settingsOpen -> settingsOpen = false; folder != null -> folder = null; else -> launchpad = false }
    }
    /** All your apps live in Beacon. (Only if Beacon isn't installed is there a simple built-in list, so apps can still be found.) */
    fun openLaunchpad(search: Boolean) {
        Sounds.play(context, Sounds.S.FolderOpen)
        val beacon = Intent("dev.wonderdesk.beacon.LAUNCHPAD").setPackage("dev.wonderdesk.beacon").putExtra("search", search).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.packageManager.resolveActivity(beacon, 0) != null) {
            val opts = android.app.ActivityOptions.makeCustomAnimation(context, android.R.anim.fade_in, 0).toBundle()
            if (runCatching { context.startActivity(beacon, opts) }.isSuccess) return
        }
        launchpadSearch = search; launchpad = true
    }
    fun gesture(action: String) = when (action) {
        "launchpad" -> openLaunchpad(false)
        "search" -> openLaunchpad(true)
        "notifications" -> { context.expandNotifications(); Unit }
        "lock" -> if (LockService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) != true) lockGuide = true else Unit
        "beacon" -> openLaunchpad(false)
        else -> Unit
    }

    fun computeHover(p: Offset): Hover? {
        drag.targets["remove"]?.takeIf { it.contains(p) }?.let { return Hover.Remove }
        drag.targets["info"]?.takeIf { it.contains(p) }?.let { return Hover.Info }
        drag.targets["uninstall"]?.takeIf { it.contains(p) }?.let { return Hover.Uninstall }
        val pl = drag.payload ?: return null
        if (drag.dockRect.contains(p) && pl.appKey != null) return Hover.Dock(drag.dockCenters.entries.count { it.value < p.x })
        if (!drag.gridRect.contains(p)) return null
        val cx = ((p.x - drag.gridRect.left) / drag.cellW).toInt().coerceIn(0, s.cols - 1)
        val cy = ((p.y - drag.gridRect.top) / drag.cellH).toInt().coerceIn(0, s.rows - 1)
        val except = (pl as? Payload.HomeItem)?.item?.id
        val target = Home.items.firstOrNull { it.page == currentPage && it.id != except && it.covers(cx, cy) }
        val center = Offset(drag.gridRect.left + (cx + .5f) * drag.cellW, drag.gridRect.top + (cy + .5f) * drag.cellH)
        val canMerge = (target is Item.Shortcut || target is Item.Folder) && pl.appKey != null && (pl !is Payload.HomeItem || pl.item is Item.Shortcut)
        val near = (p - center).getDistance() < min(drag.cellW, drag.cellH) * .34f
        val now = System.currentTimeMillis()
        val merge = if (canMerge && near) {
            if (drag.mergeCell != Triple(currentPage, cx, cy)) { drag.mergeCell = Triple(currentPage, cx, cy); drag.mergeSince = now }
            now - drag.mergeSince > 380
        } else { drag.mergeCell = null; false }
        return Hover.Cell(currentPage, cx, cy, merge)
    }
    LaunchedEffect(drag.dragging) {
        while (drag.dragging) {
            drag.hover = computeHover(drag.pos)
            val edge = with(density) { 26.dp.toPx() }
            val inRows = drag.pos.y in drag.gridRect.top..drag.gridRect.bottom
            val dir = when { inRows && drag.pos.x < drag.gridRect.left + edge -> -1; inRows && drag.pos.x > drag.gridRect.right - edge -> 1; else -> 0 }
            if (dir != drag.edgeDir) { drag.edgeDir = dir; drag.edgeSince = System.currentTimeMillis() }
            if (dir != 0 && System.currentTimeMillis() - drag.edgeSince > 600) {
                val next = pager.currentPage + dir
                if (infinite || next in 0 until pages) scope.launch { pager.animateScrollToPage(next) }
                else if (dir == 1 && Home.items.any { it.page == pages - 1 }) { Home.addPage(context); scope.launch { delay(60); pager.animateScrollToPage(pages) } }
                drag.edgeSince = System.currentTimeMillis() + 300
            }
            delay(50)
        }
    }
    fun drop() {
        val pl = drag.payload ?: run { drag.reset(); return }
        val h = computeHover(drag.pos)
        val key = pl.appKey
        fun removeSource() { when (pl) {
            is Payload.HomeItem -> Home.remove(context, pl.item.id)
            is Payload.DockApp -> { Home.dock = Home.dock - pl.key; Home.save(context) }
            is Payload.FolderApp -> removeFromFolder(context, pl.folderId, pl.key)
            is Payload.GridApp -> Unit
        } }
        when (h) {
            Hover.Remove -> when (pl) {
                is Payload.HomeItem -> { Sounds.play(context, Sounds.S.Poof); (pl.item as? Item.Widget)?.let { activity.host.deleteAppWidgetId(it.widgetId) }; Home.remove(context, pl.item.id); toast.value("Removed from home screen") }
                is Payload.GridApp -> toast.value("To hide an app, hold it in Beacon and choose Hide")
                else -> removeSource()
            }
            Hover.Uninstall -> key?.let { Home.app(it) }?.let { context.uninstall(it) }
            Hover.Info -> key?.let { Home.app(it) }?.let { context.appInfo(it) }
            is Hover.Dock -> if (key != null) {
                val without = Home.dock - key
                if (without.size >= 8 && key !in Home.dock) toast.value("The Dock holds 8 apps") else {
                    if (pl !is Payload.DockApp) removeSource()
                    Home.dock = without.toMutableList().apply { add(h.index.coerceIn(0, size), key) }; Home.save(context)
                }
            }
            is Hover.Cell -> {
                val w = (pl as? Payload.HomeItem)?.item?.w ?: 1; val hh = (pl as? Payload.HomeItem)?.item?.h ?: 1
                val except = (pl as? Payload.HomeItem)?.item?.id
                val target = Home.items.firstOrNull { it.page == h.page && it.id != except && it.covers(h.x, h.y) }
                if (h.merge && key != null && target != null) {
                    removeSource()
                    when (target) {
                        is Item.Folder -> Home.update(context, target.copy(apps = (target.apps + key).distinct()))
                        is Item.Shortcut -> if (target.app != key) {
                            Home.items = Home.items.filter { it.id != target.id } + Item.Folder(Home.newId(), Home.suggestFolderName(listOf(target.app, key)), listOf(target.app, key), h.page, target.x, target.y)
                            Home.save(context)
                        }
                        else -> {}
                    }
                } else {
                    val spot = if (Home.fits(h.page, h.x, h.y, w, hh, except)) h.x to h.y else Home.nearestFree(h.page, h.x, h.y, w, hh, except)
                    when {
                        spot == null -> toast.value("No room on this page. Make space or add a page")
                        pl is Payload.HomeItem -> Home.update(context, pl.item.at(h.page, spot.first, spot.second))
                        key != null -> { removeSource(); Home.items = Home.items + Item.Shortcut(Home.newId(), key, h.page, spot.first, spot.second); Home.save(context) }
                    }
                }
            }
            null -> {}
        }
        if (pl is Payload.GridApp) launchpad = false
        if (pl is Payload.FolderApp) folder = null
        drag.reset()
    }
    val dropTick by dropRequested
    LaunchedEffect(dropTick) { if (dropTick > 0) drop() }
    val hapticLong = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    if (settingsOpen) { LauncherSettings(onClose = { settingsOpen = false }, onWallpaper = { settingsOpen = false; wallpaper = true }); return }
    if (widgetPicker) { WidgetPicker(activity, currentPage) { widgetPicker = false }; return }
    if (wallpaper) { WallpaperPicker { wallpaper = false }; return }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (s.menuBar) MenuBar(onApps = { openLaunchpad(false) }, onSearch = { openLaunchpad(true) }, onSettings = { settingsOpen = true },
                onWidgets = { widgetPicker = true }, onWallpaper = { wallpaper = true })
            else Spacer(Modifier.statusBarsPadding())
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()
                .pointerInput(s.swipeUp, s.swipeDown) {
                    var dy = 0f
                    detectVerticalDragGestures(onDragEnd = { if (dy < -120) gesture(s.swipeUp) else if (dy > 120) gesture(s.swipeDown); dy = 0f }) { _, d -> dy += d }
                }
                .pointerInput(s.doubleTap) { detectTapGestures(onDoubleTap = { if (!itemTouch) gesture(s.doubleTap) }, onLongPress = { if (!itemTouch && !drag.active) { hapticLong(); homeMenu = true } }) }) {
                val cellW = maxWidth / s.cols
                val cellH = (maxHeight - 24.dp) / s.rows
                Box(Modifier.fillMaxWidth().height(maxHeight - 24.dp).onGloballyPositioned { c ->
                    drag.gridRect = c.boundsInRoot(); drag.cellW = c.size.width.toFloat() / s.cols; drag.cellH = c.size.height.toFloat() / s.rows
                })
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !drag.active && movingWidget == null, beyondBoundsPageCount = 1) { v ->
                    val page = real(v)
                    // Pages ease back and fade slightly as you swipe, like turning a card, instead of sliding flat.
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        val off = ((pager.currentPage - v) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                        val a = kotlin.math.abs(off)
                        scaleX = 1f - .07f * a; scaleY = 1f - .07f * a; alpha = 1f - .4f * a
                        rotationY = off * 10f; cameraDistance = 14f * this.density
                    }) {
                        if (drag.dragging || movingWidget != null) GridGuide(page, drag, movingWidget, cellW, cellH)
                        Home.items.filter { it.page == page }.forEach { item ->
                            key(item.id) {
                                val lifted = drag.dragging && (drag.payload as? Payload.HomeItem)?.item?.id == item.id
                                val coords = remember { Coords() }
                                Box(Modifier.offset(cellW * item.x, cellH * item.y).size(cellW * item.w, cellH * item.h)
                                    .graphicsLayer { alpha = if (lifted || movingWidget?.id == item.id) .25f else 1f }
                                    .onGloballyPositioned { coords.c = it }
                                    .then(if (item !is Item.Widget) Modifier.liftable(item, coords, drag, { Payload.HomeItem(item) },
                                        onTap = { when (item) { is Item.Shortcut -> Home.app(item.app)?.let { context.launch(it) }; is Item.Folder -> { Sounds.play(context, Sounds.S.FolderOpen); folder = item.id to (coords.c?.boundsInRoot() ?: Rect.Zero) }; else -> {} } },
                                        onMenu = { itemMenu = item }, haptic = hapticLong) else Modifier), contentAlignment = Alignment.Center) {
                                    when (item) {
                                        is Item.Shortcut -> Home.app(item.app)?.let { AppIcon(it) }
                                        is Item.Folder -> FolderIcon(item, open = folder?.first == item.id)
                                        is Item.Widget -> WidgetView(activity, item, drag) { widgetMenu = item }
                                    }
                                }
                            }
                        }
                        if (movingWidget != null) Box(Modifier.fillMaxSize().pointerInput(movingWidget) {
                            detectTapGestures { o ->
                                val w = movingWidget ?: return@detectTapGestures
                                val cx = (o.x / (size.width / s.cols)).toInt(); val cy = (o.y / ((size.height - 24.dp.toPx()) / s.rows)).toInt()
                                val spot = if (Home.fits(page, cx, cy, w.w, w.h, w.id)) cx to cy else Home.nearestFree(page, cx, cy, w.w, w.h, w.id)
                                if (spot == null) toast.value("Not enough room here") else { Home.update(context, w.at(page, spot.first, spot.second)); movingWidget = null }
                            }
                        })
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(pages) { i ->
                        val on = i == currentPage
                        Box(Modifier.size(if (on) 8.dp else 6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (on) .95f else .45f))
                            .clickable { scope.launch { pager.animateScrollToPage(pager.currentPage + (i - currentPage)) } })
                    }
                }
            }
            Dock(drag, onMenu = { dockMenu = it }, haptic = hapticLong)
        }
        if (drag.dragging) DropTargets(drag)
        if (movingWidget != null) Text("Tap where the widget should go", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 40.dp).clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = .6f)).clickable { movingWidget = null }.padding(14.dp, 8.dp), color = Color.White)
        AnimatedVisibility(launchpad, enter = fadeIn(tween(180)) + scaleIn(initialScale = 1.06f, animationSpec = tween(220)), exit = fadeOut(tween(160)) + scaleOut(targetScale = 1.06f, animationSpec = tween(180))) {
            Launchpad(drag, focusSearch = launchpadSearch, onClose = { launchpad = false; launchpadSearch = false }, page = currentPage, haptic = hapticLong, toast = { toast.value(it) })
        }
        folder?.let { (fid, origin) ->
            val f = Home.items.find { it.id == fid } as? Item.Folder
            if (f == null) folder = null else FolderPopup(f, origin, drag, haptic = hapticLong) { folder = null }
        }
        val pl = drag.payload
        if (pl != null && drag.dragging) DragGhost(pl, drag)
        ToastBar(toast)
        itemMenu?.let { item -> ItemMenu(item, onDismiss = { itemMenu = null }, onOpenFolder = { folder = it.id to Rect.Zero }) }
        dockMenu?.let { key -> DockMenu(key) { dockMenu = null } }
        widgetMenu?.let { w -> WidgetMenu(activity, w, onMove = { movingWidget = w; widgetMenu = null }) { widgetMenu = null } }
        if (homeMenu) HomeMenu(page = currentPage, onDismiss = { homeMenu = false }, onWidgets = { widgetPicker = true }, onWallpaper = { wallpaper = true }, onSettings = { settingsOpen = true },
            onAddApps = { addApps = true }, onPages = { pagesDialog = true }, onAddPage = { val n = Home.addPage(context); scope.launch { pager.animateScrollToPage(pager.currentPage + (n - currentPage)) } },
            onRemovePage = { Home.removePage(context, currentPage).forEach { activity.host.deleteAppWidgetId(it.widgetId) } })
        if (pagesDialog) PagesDialog(current = currentPage, onGo = { i -> scope.launch { pager.scrollToPage(pager.currentPage + (i - currentPage)) } },
            onDelete = { i -> Home.removePage(context, i).forEach { activity.host.deleteAppWidgetId(it.widgetId) } }) { pagesDialog = false }
        if (addApps) AppChooser("Add apps to this page", already = Home.items.filter { it.page == currentPage }.mapNotNull { (it as? Item.Shortcut)?.app }.toSet(), onDismiss = { addApps = false }) { keys ->
            var placed = 0; keys.forEach { if (Home.addShortcut(context, it, currentPage)) placed++ }
            if (placed < keys.size) toast.value("Some apps didn't fit")
        }
        if (lockGuide) AccessGuide(SpecialAccess.Kind.ACCESSIBILITY, "To lock the screen with a gesture, turn on Wonderdesk's “double-tap to lock” accessibility switch. It only locks the screen and reads nothing.",
            onDismiss = { lockGuide = false }, component = LockService::class.java)
        if (MainActivity.welcome.value) Welcome(activity, onMore = { MainActivity.welcome.value = false; settingsOpen = true }, onWallpaper = { MainActivity.welcome.value = false; wallpaper = true }) { MainActivity.welcome.value = false }
        if (!Home.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
    }
}

/** Cell outlines while dragging, with the landing spot (or the folder it will make) highlighted. */
@Composable
private fun GridGuide(page: Int, drag: DragController, moving: Item.Widget?, cellW: androidx.compose.ui.unit.Dp, cellH: androidx.compose.ui.unit.Dp) {
    val s = Home.settings
    val h = drag.hover as? Hover.Cell
    val pl = drag.payload
    val w = (pl as? Payload.HomeItem)?.item?.w ?: moving?.w ?: 1; val hh = (pl as? Payload.HomeItem)?.item?.h ?: moving?.h ?: 1
    val except = (pl as? Payload.HomeItem)?.item?.id ?: moving?.id
    val spot = h?.takeIf { it.page == page && !it.merge }?.let { if (Home.fits(page, it.x, it.y, w, hh, except)) it.x to it.y else Home.nearestFree(page, it.x, it.y, w, hh, except) }
    Canvas(Modifier.size(cellW * s.cols, cellH * s.rows)) {
        val cw = size.width / s.cols; val ch = size.height / s.rows
        val pad = 5.dp.toPx(); val r = CornerRadius(12.dp.toPx())
        for (y in 0 until s.rows) for (x in 0 until s.cols) {
            val busy = Home.occupied(page, x, y, except)
            drawRoundRect(Color.White.copy(alpha = if (busy) .05f else .12f), Offset(x * cw + pad, y * ch + pad), Size(cw - 2 * pad, ch - 2 * pad), r)
            drawRoundRect(Color.White.copy(alpha = .28f), Offset(x * cw + pad, y * ch + pad), Size(cw - 2 * pad, ch - 2 * pad), r, style = Stroke(1.dp.toPx()))
        }
        spot?.let { (x, y) ->
            drawRoundRect(Color(0xFF5AB0FF).copy(alpha = .35f), Offset(x * cw + pad, y * ch + pad), Size(cw * w - 2 * pad, ch * hh - 2 * pad), r)
            drawRoundRect(Color(0xFFBFE3FF), Offset(x * cw + pad, y * ch + pad), Size(cw * w - 2 * pad, ch * hh - 2 * pad), r, style = Stroke(2.5.dp.toPx()))
        }
        if (h != null && h.merge && h.page == page) drawCircle(Color(0xFFBFE3FF), min(cw, ch) * .42f, Offset((h.x + .5f) * cw, (h.y + .5f) * ch), style = Stroke(4.dp.toPx()))
    }
}

@Composable
private fun DragGhost(pl: Payload, drag: DragController) {
    val size = (60 * Home.settings.iconScale).dp
    val px = with(LocalDensity.current) { size.toPx() }
    val scale by animateFloatAsState(if (drag.hover is Hover.Remove || drag.hover is Hover.Uninstall) .8f else 1.18f, spring(stiffness = 600f), label = "ghost")
    Box(Modifier.offset { IntOffset((drag.pos.x - px / 2).roundToInt(), (drag.pos.y - px * .75f).roundToInt()) }.size(size).graphicsLayer { scaleX = scale; scaleY = scale; alpha = .92f }) {
        when (pl) {
            is Payload.HomeItem -> when (val i = pl.item) {
                is Item.Folder -> FolderTile(i.apps, size)
                is Item.Widget -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .4f)), contentAlignment = Alignment.Center) { Icon(WonderIcons.Widgets, null, tint = Color.White) }
                is Item.Shortcut -> Home.app(i.app)?.let { Image(it.icon, null, Modifier.fillMaxSize()) }
            }
            else -> pl.appKey?.let { Home.app(it) }?.let { Image(it.icon, null, Modifier.fillMaxSize()) }
        }
    }
}

/** Remove / App info / Uninstall targets along the top while dragging. */
@Composable
private fun DropTargets(drag: DragController) {
    val pl = drag.payload
    val isApp = pl?.appKey != null
    DisposableEffect(Unit) { onDispose { drag.targets.clear() } }
    Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF0101418), Color(0xD0101418), Color.Transparent))).statusBarsPadding().padding(8.dp, 4.dp, 8.dp, 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable fun RowScope.target(id: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, hot: Boolean, danger: Boolean) {
            Row(Modifier.weight(1f).height(44.dp).onGloballyPositioned { drag.targets[id] = it.boundsInRoot() }.clip(RoundedCornerShape(22.dp))
                .background(if (hot) (if (danger) Color(0xFFD7372F) else Color(0xFF1E7FE8)) else Color.White.copy(alpha = .18f)).border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(22.dp)),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (pl !is Payload.GridApp) target("remove", if (pl is Payload.DockApp) "Remove from Dock" else "Remove", WonderIcons.Close, drag.hover is Hover.Remove, false)
        if (isApp) target("info", "App info", WonderIcons.Info, drag.hover is Hover.Info, false)
        if (isApp) target("uninstall", "Uninstall", WonderIcons.Delete, drag.hover is Hover.Uninstall, true)
    }
}

@Composable
private fun ToastBar(holder: MutableState<(String) -> Unit>) {
    var msg by remember { mutableStateOf<String?>(null) }
    var serial by remember { mutableIntStateOf(0) }
    holder.value = { msg = it; serial++ }
    LaunchedEffect(serial) { if (msg != null) { delay(2600); msg = null } }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg != null, enter = fadeIn(), exit = fadeOut()) {
            val c = rememberPlayerChrome()
            Text(msg ?: "", Modifier.mediaGlass(c, 20.dp).padding(16.dp, 10.dp), color = c.ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/* --------------------------------------------------- menu bar ----------------------------------------------- */

@Composable
private fun MenuBar(onApps: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit, onWidgets: () -> Unit, onWallpaper: () -> Unit) {
    val context = LocalContext.current
    val nowMs = rememberNow(60_000)
    val now = remember(nowMs) { LocalDateTime.now() }
    var orb by remember { mutableStateOf(false) }
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val pal = wonderPalette()
    val ink = pal.toolbarText
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(pal.toolbar.take(2).map { it.copy(alpha = .93f) })).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                val art = remember { context.appArt(64) }
                Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable { orb = true }.padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    art?.let { Image(it, "Wonderdesk menu", Modifier.size(20.dp)) }
                    Spacer(Modifier.width(6.dp))
                    Text("Wonderdesk", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ink), maxLines = 1)
                }
                DropdownMenu(orb, { orb = false }, Modifier.background(pal.panel)) {
                    DropdownMenuItem({ Text("All apps (Beacon)") }, { orb = false; onApps() }, leadingIcon = { Icon(WonderIcons.Apps, null) })
                    DropdownMenuItem({ Text("Add a widget…") }, { orb = false; onWidgets() }, leadingIcon = { Icon(WonderIcons.Widgets, null) })
                    DropdownMenuItem({ Text("Desktop & wallpaper…") }, { orb = false; onWallpaper() }, leadingIcon = { Icon(WonderIcons.Wallpaper, null) })
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Control Room") }, { orb = false; context.openPackageOr("dev.wonderdesk.controlroom", Intent(AndroidSettings.ACTION_SETTINGS)) }, leadingIcon = { Icon(WonderIcons.Tune, null) })
                    DropdownMenuItem({ Text("Wonderdesk Apphub") }, { orb = false; context.openPackageOr("dev.wonderdesk.apphub", Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thoroftroy/App-Hub/releases"))) }, leadingIcon = { Icon(WonderIcons.SystemUpdate, null) })
                    DropdownMenuItem({ Text("Home screen settings") }, { orb = false; onSettings() }, leadingIcon = { Icon(WonderIcons.Settings, null) })
                }
            }
            Text("Apps", Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onApps).padding(8.dp, 4.dp), style = TextStyle(fontSize = 14.sp, color = ink))
            Spacer(Modifier.weight(1f))
            Icon(WonderIcons.Search, "Search", Modifier.clip(CircleShape).clickable(onClick = onSearch).padding(6.dp).size(20.dp), tint = pal.toolbarIcon)
            // Date opens Chronicle, time opens Timepiece.
            Text(now.format(DateTimeFormatter.ofPattern("EEE d MMM")), Modifier.clip(RoundedCornerShape(4.dp)).clickable {
                context.openPackageOr("dev.wonderdesk.chronicle", Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            }.padding(6.dp, 4.dp), style = TextStyle(fontSize = 14.sp, color = ink, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(now.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a")), Modifier.clip(RoundedCornerShape(4.dp)).clickable {
                context.openPackageOr("dev.wonderdesk.timepiece", Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
            }.padding(6.dp, 4.dp), style = TextStyle(fontSize = 14.sp, color = ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.toolbarLine.copy(alpha = .7f)))
    }
}

/* ----------------------------------------------------- icons ------------------------------------------------ */

@Composable
fun AppIcon(a: App, size: androidx.compose.ui.unit.Dp = (56 * Home.settings.iconScale).dp, label: Boolean = Home.settings.labels) {
    Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(a.icon, Home.label(a), Modifier.size(size))
        if (label) Text(Home.label(a), Modifier.padding(top = 4.dp).widthIn(max = size + 30.dp), style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FolderTile(apps: List<String>, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .24f)).background(Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x66C8D2DC)))).border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(size * .24f)).padding(size * .1f)) {
        val list = apps.mapNotNull { Home.app(it) }.take(4)
        Column(verticalArrangement = Arrangement.spacedBy(size * .06f)) {
            list.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(size * .06f)) { row.forEach { Image(it.icon, null, Modifier.size(size * .36f)) } } }
        }
    }
}

@Composable
private fun FolderIcon(f: Item.Folder, open: Boolean) {
    val size = (56 * Home.settings.iconScale).dp
    val scale by animateFloatAsState(if (open) 1.12f else 1f, tween(200), label = "folder")
    Column(Modifier.padding(4.dp).graphicsLayer { scaleX = scale; scaleY = scale }, horizontalAlignment = Alignment.CenterHorizontally) {
        FolderTile(f.apps, size)
        if (Home.settings.labels) Text(f.name, Modifier.padding(top = 4.dp).widthIn(max = size + 30.dp), style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/* ------------------------------------------------------ dock ------------------------------------------------ */

@Composable
private fun Dock(drag: DragController, onMenu: (String) -> Unit, haptic: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val apps = Home.dock.mapNotNull { Home.app(it) }
    var touchX by remember { mutableStateOf<Float?>(null) }
    val insertAt = (drag.hover as? Hover.Dock)?.index
    BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).onGloballyPositioned { drag.dockRect = it.boundsInRoot() }, contentAlignment = Alignment.BottomCenter) {
    // Icons shrink to fit when the Dock is full (room is left for the magnified icon and a drop gap).
    val slots = apps.size.coerceAtLeast(1) + if (insertAt != null) 1 else 0
    val base = ((maxWidth - 24.dp) / slots - 6.dp).coerceIn(34.dp, 50.dp)
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val inset = size.width * .04f
            val path = Path().apply { moveTo(inset, 0f); lineTo(size.width - inset, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(path, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x33DDE6F0), Color(0x55FFFFFF))))
            drawLine(Color(0xCCFFFFFF), Offset(inset, 1f), Offset(size.width - inset, 1f), 2f)
            drawLine(Color(0x66000000), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
        }
        Row(Modifier.padding(bottom = 10.dp).pointerInput(Home.settings.dockMagnify) {
            if (!Home.settings.dockMagnify) return@pointerInput
            // Watch the finger without taking the touch, so taps and drags still reach each icon.
            awaitPointerEventScope { while (true) { val e = awaitPointerEvent(PointerEventPass.Initial); val c = e.changes.firstOrNull(); touchX = if (c != null && c.pressed && !drag.dragging) c.position.x else null } }
        }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            (apps.map<App, App?> { it } + if (insertAt == apps.size) listOf(null) else emptyList()).forEachIndexed { i, a ->
                if (insertAt == i) Spacer(Modifier.width(base))
                val coords = remember(a?.key) { Coords() }
                var center by remember { mutableFloatStateOf(0f) }
                val scale = touchX?.let { tx -> val d = abs(tx - center) / with(density) { base.toPx() }; 1f + .55f * (1f - (d / 2.2f)).coerceIn(0f, 1f) } ?: 1f
                val anim by animateFloatAsState(scale, tween(90), label = "dock")
                val lifted = drag.dragging && a != null && (drag.payload as? Payload.DockApp)?.key == a.key
                Column(Modifier.onGloballyPositioned { coords.c = it; center = it.positionInParentX() + it.size.width / 2f; if (a != null) drag.dockCenters[i] = it.boundsInRoot().center.x else drag.dockCenters.remove(i) }
                    .graphicsLayer { scaleX = anim; scaleY = anim; transformOrigin = TransformOrigin(.5f, 1f); alpha = if (lifted) .25f else 1f }, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (a == null) Spacer(Modifier.size(0.dp, base))
                    else Image(a.icon, Home.label(a), Modifier.size(base).dockBounce(a.key).liftable(a.key, coords, drag, { Payload.DockApp(a.key) }, onTap = { dockBouncing.value = a.key to System.nanoTime(); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ context.launch(a) }, 180) }, onMenu = { onMenu(a.key) }, haptic = haptic))
                    if (a != null && Home.settings.reflections) Image(a.icon, null, Modifier.size(base, 10.dp).graphicsLayer { scaleY = -1f; alpha = .25f }, contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.BottomCenter)
                    else Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
    LaunchedEffect(apps.size) { drag.dockCenters.keys.filter { it >= apps.size }.forEach { drag.dockCenters.remove(it) } }
}

private fun LayoutCoordinates.positionInParentX(): Float = parentLayoutCoordinates?.localPositionOf(this, Offset.Zero)?.x ?: 0f

/* --------------------------------------------------- launchpad ---------------------------------------------- */

@Composable
private fun Launchpad(drag: DragController, focusSearch: Boolean, onClose: () -> Unit, page: Int, haptic: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<App?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var newGroupFor by remember { mutableStateOf<App?>(null) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusSearch) { if (focusSearch) { delay(200); runCatching { focus.requestFocus(); keyboard?.show() } } else { focusManager.clearFocus(); keyboard?.hide() } }
    val sort = Home.settings.launchpadSort
    val visible = Home.apps.filter { it.key !in Home.hidden }
    val grouped = Home.groups.flatMap { it.apps }.toSet()
    val loose = remember(visible, sort, grouped, Home.launches) { Home.sorted(visible.filter { it.key !in grouped }, sort) }
    var hits by remember { mutableStateOf<List<BeaconHit>>(emptyList()) }
    LaunchedEffect(q) { hits = emptyList(); if (q.isBlank()) return@LaunchedEffect; delay(160); hits = withContext(Dispatchers.IO) { BeaconClient.query(context, q) } }
    val appHits = remember(q, visible) { Home.search(q) }
    val hidden = drag.dragging && drag.payload is Payload.GridApp
    var pull by remember { mutableFloatStateOf(0f) }
    val gridState = rememberLazyGridState()
    val closeOnPull = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.Drag) { pull += available.y; if (pull > 260) { pull = 0f; onClose() } } else if (consumed.y != 0f) pull = 0f
                return Offset.Zero
            }
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden) 0f else 1f }.background(Brush.verticalGradient(rememberPlayerChrome().backdrop.map { it.copy(alpha = .88f) }))
        .pointerInput(Unit) { detectTapGestures { onClose() } }
        .pointerInput(Unit) { var dy = 0f; detectVerticalDragGestures(onDragEnd = { if (dy > 220) onClose(); dy = 0f }) { _, d -> dy += d } }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
            Row(Modifier.padding(vertical = 12.dp).widthIn(max = 520.dp).align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).focusRequester(focus)) {
                    SearchField(q, { q = it }, if (BeaconClient.available(context)) "Search apps, settings, files, maths…" else "Search apps", onSubmit = {
                        appHits.firstOrNull()?.let { context.launch(it); onClose() } ?: hits.firstOrNull()?.intent?.let { context.launchSafely(it) {}; onClose() }
                    })
                }
                Box {
                    Icon(WonderIcons.Sort, "Sort", Modifier.padding(start = 8.dp).clip(CircleShape).clickable { sortMenu = true }.padding(8.dp), tint = Color.White)
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SORTS.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { sortMenu = false; Home.settings = Home.settings.copy(launchpadSort = id); Home.save(context) },
                            trailingIcon = if (id == sort) ({ Icon(WonderIcons.Check, null) }) else null) }
                    }
                }
            }
            if (q.isNotBlank()) SearchResults(appHits, hits, onHit = { h -> h.intent?.let { context.launchSafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) {} }; onClose() },
                appItem = { a -> LaunchpadApp(a, drag, haptic, onLaunch = { context.launch(a); onClose() }, onMenu = { menuFor = a }) })
            else LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxSize().nestedScroll(closeOnPull), state = gridState, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (Home.groups.isNotEmpty() && Home.settings.lpFoldersFirst) items(Home.groups, key = { "g" + it.id }) { g -> GroupTile(g) { openGroup = g.id } }
                items(loose, key = { it.key }) { a -> LaunchpadApp(a, drag, haptic, onLaunch = { context.launch(a); onClose() }, onMenu = { menuFor = a }) }
                if (Home.groups.isNotEmpty() && !Home.settings.lpFoldersFirst) items(Home.groups, key = { "g" + it.id }) { g -> GroupTile(g) { openGroup = g.id } }
            }
        }
        openGroup?.let { id -> Home.groups.find { it.id == id }?.let { g -> GroupPanel(g, drag, haptic, onLaunch = { a -> context.launch(a); onClose() }, onMenu = { menuFor = it }) { openGroup = null } } ?: run { openGroup = null } }
    }
    menuFor?.let { a -> LaunchpadMenu(a, page, onDismiss = { menuFor = null }, onClose = onClose, onNewGroup = { newGroupFor = a }, toast = toast) }
    newGroupFor?.let { a -> InputDialog("New folder", "Folder name", initial = Home.suggestFolderName(listOf(a.key)).takeIf { it != "Folder" } ?: "", confirm = "Create", onDismiss = { newGroupFor = null }) { n -> Home.newGroup(context, n, listOf(a.key)) } }
}

@Composable
private fun LaunchpadApp(a: App, drag: DragController, haptic: () -> Unit, onLaunch: () -> Unit, onMenu: () -> Unit) {
    val coords = remember { Coords() }
    Box(Modifier.padding(vertical = 8.dp).onGloballyPositioned { coords.c = it }.liftable(a.key, coords, drag, { Payload.GridApp(a.key) }, onTap = onLaunch, onMenu = onMenu, haptic = haptic), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(a.icon, Home.label(a), Modifier.size(58.dp))
            Text(Home.label(a), Modifier.padding(top = 4.dp).width(84.dp), style = MaterialTheme.typography.labelMedium.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GroupTile(g: Group, onOpen: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FolderTile(g.apps, 58.dp)
        Text(g.name, Modifier.padding(top = 4.dp).width(84.dp), style = MaterialTheme.typography.labelMedium.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GroupPanel(g: Group, drag: DragController, haptic: () -> Unit, onLaunch: (App) -> Unit, onMenu: (App) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var rename by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(220)) }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f * p.value)).pointerInput(Unit) { detectTapGestures { onClose() } }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp).widthIn(max = 460.dp).graphicsLayer { scaleX = .85f + .15f * p.value; scaleY = .85f + .15f * p.value; alpha = p.value }
            .mediaGlass(rememberPlayerChrome(), 22.dp, hud = true)
            .pointerInput(Unit) { detectTapGestures { } }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, Modifier.weight(1f).clickable { rename = true }, style = MaterialTheme.typography.titleLarge.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(WonderIcons.Add, "Add apps", Modifier.clip(CircleShape).clickable { add = true }.padding(8.dp), tint = Color.White)
                Icon(WonderIcons.Delete, "Delete folder", Modifier.clip(CircleShape).clickable { confirmDelete = true }.padding(8.dp), tint = Color.White)
            }
            LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.heightIn(max = 460.dp)) {
                items(Home.sorted(g.apps.distinct().mapNotNull { Home.app(it) }, Home.settings.launchpadSort), key = { it.key }) { a -> LaunchpadApp(a, drag, haptic, onLaunch = { onLaunch(a) }, onMenu = { onMenu(a) }) }
            }
        }
    }
    if (rename) InputDialog("Rename folder", "Name", initial = g.name, onDismiss = { rename = false }) { Home.renameGroup(context, g.id, it) }
    if (add) AppChooser("Add to ${g.name}", already = g.apps.toSet(), onDismiss = { add = false }) { keys -> keys.forEach { Home.addToGroup(context, g.id, it) } }
    if (confirmDelete) ConfirmDialog("Delete “${g.name}”?", "The apps stay installed and go back into the list of all apps.", "Delete folder", onConfirm = { Home.deleteGroup(context, g.id); onClose() }, onDismiss = { confirmDelete = false })
}

@Composable
private fun SearchResults(apps: List<App>, hits: List<BeaconHit>, onHit: (BeaconHit) -> Unit, appItem: @Composable (App) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(bottom = 24.dp)) {
        hits.filter { it.kind == "math" || it.kind == "convert" }.forEach { h ->
            item { Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .12f)).clickable { onHit(h) }.padding(16.dp)) {
                Text(h.subtitle, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
                Text(h.title, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
            } }
        }
        if (apps.isNotEmpty()) item { LazyRow(Modifier.fillMaxWidth()) { items(apps.take(16), key = { it.key }) { a -> appItem(a) } } }
        hits.filter { it.kind != "math" && it.kind != "convert" }.groupBy { it.kind }.forEach { (kind, list) ->
            item { Text(when (kind) { "setting" -> "Settings"; "contact" -> "Contacts"; "file" -> "Files"; "content" -> "Inside files"; "web" -> "Web"; else -> kind.replaceFirstChar { it.uppercase() } },
                Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp), color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelLarge) }
            items(list.take(8)) { h ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onHit(h) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (kind) { "setting" -> WonderIcons.Settings; "contact" -> WonderIcons.Person; "file", "content" -> WonderIcons.InsertDriveFile; "web" -> WonderIcons.Language; else -> WonderIcons.Search }, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, color = Color.White, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (h.subtitle.isNotBlank()) Text(h.subtitle, color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (apps.isEmpty() && hits.isEmpty()) item { Text("No matches", Modifier.fillMaxWidth().padding(32.dp), color = Color.White.copy(alpha = .7f), textAlign = TextAlign.Center) }
    }
}

@Composable
private fun LaunchpadMenu(a: App, page: Int, onDismiss: () -> Unit, onClose: () -> Unit, onNewGroup: () -> Unit, toast: (String) -> Unit) {
    val context = LocalContext.current
    val inGroup = Home.groups.find { a.key in it.apps }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(Home.label(a)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Panel {
                ListRow("Add to home screen", icon = WonderIcons.Home, tint = Color(0xFF2E9E44), onClick = { onDismiss(); if (Home.addShortcut(context, a.key, page)) { toast("Added to the home screen"); onClose() } else toast("No room left") }); PanelDivider()
                if (a.key !in Home.dock) { ListRow("Add to Dock", icon = WonderIcons.Dashboard, tint = Color(0xFF16A085), onClick = { onDismiss(); if (Home.dock.size >= 8) toast("The Dock holds 8 apps") else { Home.dock = Home.dock + a.key; Home.save(context) } }); PanelDivider() }
                Home.groups.filter { it.id != inGroup?.id }.forEach { g -> ListRow("Move to “${g.name}”", icon = WonderIcons.Folder, tint = Color(0xFF4A90E2), onClick = { onDismiss(); Home.addToGroup(context, g.id, a.key) }); PanelDivider() }
                ListRow("New folder with this app", icon = WonderIcons.CreateNewFolder, tint = Color(0xFF4A90E2), onClick = { onDismiss(); onNewGroup() }); PanelDivider()
                if (inGroup != null) { ListRow("Take out of “${inGroup.name}”", icon = WonderIcons.FolderOpen, tint = Color(0xFF7A7F87), onClick = { onDismiss(); Home.removeFromGroups(context, a.key) }); PanelDivider() }
                ListRow("Hide from all apps", icon = WonderIcons.VisibilityOff, tint = Color(0xFF7A7F87), onClick = { onDismiss(); Home.hidden = Home.hidden + a.key; Home.save(context) }); PanelDivider()
                ListRow("App info", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), onClick = { onDismiss(); context.appInfo(a) }); PanelDivider()
                ListRow("Uninstall", icon = WonderIcons.Delete, danger = true, onClick = { onDismiss(); context.uninstall(a) })
            }
            Text("Tip: hold and drag an app to put it on the home screen.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** Pick several apps (for a page or a folder). */
@Composable
private fun AppChooser(title: String, already: Set<String>, onDismiss: () -> Unit, onPick: (List<String>) -> Unit) {
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<List<String>>(emptyList()) }
    val list = remember(q) { (if (q.isBlank()) Home.sorted(Home.apps, "name") else Home.search(q)).filter { it.key !in already } }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            SearchField(q, { q = it }, "Search apps")
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.key }) { a ->
                    ListRow(Home.label(a), leading = { Image(a.icon, null, Modifier.size(32.dp)) }, trailing = { Checkbox(a.key in chosen, { on -> chosen = if (on) chosen + a.key else chosen - a.key }) },
                        onClick = { chosen = if (a.key in chosen) chosen - a.key else chosen + a.key })
                }
            }
        }
    }, confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onDismiss(); onPick(chosen) }) { Text("Add ${chosen.size}") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/* ---------------------------------------------------- folders ----------------------------------------------- */

@Composable
private fun FolderPopup(f: Item.Folder, origin: Rect, drag: DragController, haptic: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val motion = LocalMotion.current
    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val prog = remember { Animatable(if (motion == WonderMotion.OFF) 1f else 0f) }
    var screen by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(Unit) { prog.animateTo(1f, spring(dampingRatio = .82f, stiffness = 420f)) }
    fun close() { if (motion == WonderMotion.OFF) onClose() else scope.launch { prog.animateTo(0f, tween(160)); onClose() } }
    BackHandler { close() }
    val hidden = drag.dragging && drag.payload is Payload.FolderApp
    Box(Modifier.fillMaxSize().onGloballyPositioned { screen = Size(it.size.width.toFloat(), it.size.height.toFloat()) }.graphicsLayer { alpha = if (hidden) 0f else 1f }
        .background(Color.Black.copy(alpha = .45f * prog.value)).pointerInput(Unit) { detectTapGestures { close() } }, contentAlignment = Alignment.Center) {
        // Grows out of the folder icon you tapped.
        val ox = if (origin == Rect.Zero || screen.width == 0f) .5f else (origin.center.x / screen.width).coerceIn(0f, 1f)
        val oy = if (origin == Rect.Zero || screen.height == 0f) .5f else (origin.center.y / screen.height).coerceIn(0f, 1f)
        Column(Modifier.padding(24.dp).widthIn(max = 420.dp)
            .graphicsLayer { val sc = .2f + .8f * prog.value; scaleX = sc; scaleY = sc; alpha = prog.value.coerceIn(0f, 1f); transformOrigin = TransformOrigin(ox, oy) }
            .clip(RoundedCornerShape(22.dp)).background(Brush.verticalGradient(listOf(wonderPalette().panel.copy(alpha = .95f), wonderPalette().window.copy(alpha = .92f))))
            .border(1.dp, wonderPalette().panelBorder, RoundedCornerShape(22.dp)).pointerInput(Unit) { detectTapGestures { } }.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, Modifier.weight(1f).clickable { renaming = true }.padding(bottom = 10.dp), style = MaterialTheme.typography.titleLarge.copy(color = wonderPalette().text, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(WonderIcons.Add, "Add apps", Modifier.clip(CircleShape).clickable { adding = true }.padding(8.dp), tint = wonderPalette().accent)
            }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 440.dp)) {
                items(f.apps.distinct(), key = { it }) { key ->
                    val a = Home.app(key) ?: return@items
                    val coords = remember { Coords() }
                    Box {
                        Column(Modifier.padding(4.dp).onGloballyPositioned { coords.c = it }.liftable(key, coords, drag, { Payload.FolderApp(f.id, key) }, onTap = { context.launch(a); close() }, onMenu = { menuFor = key }, haptic = haptic),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(a.icon, null, Modifier.size(52.dp))
                            Text(Home.label(a), style = MaterialTheme.typography.labelSmall.copy(color = wonderPalette().text), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                        DropdownMenu(menuFor == key, { menuFor = null }) {
                            DropdownMenuItem({ Text("Take out of folder") }, { menuFor = null; removeFromFolder(context, f.id, key); Home.addShortcut(context, key, f.page, f.x to f.y) })
                            DropdownMenuItem({ Text("App info") }, { menuFor = null; context.appInfo(a) })
                            DropdownMenuItem({ Text("Uninstall") }, { menuFor = null; context.uninstall(a) })
                        }
                    }
                }
            }
            Text("Hold an app and drag it out to move it", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = wonderPalette().secondaryText)
        }
    }
    if (renaming) InputDialog("Rename folder", "Name", initial = f.name, onDismiss = { renaming = false }) { Home.update(context, f.copy(name = it)) }
    if (adding) AppChooser("Add to ${f.name}", already = f.apps.toSet(), onDismiss = { adding = false }) { keys -> Home.update(context, f.copy(apps = (f.apps + keys).distinct())) }
}

/* -------------------------------------------------- item menus ---------------------------------------------- */

@Composable
private fun ItemMenu(item: Item, onDismiss: () -> Unit, onOpenFolder: (Item.Folder) -> Unit) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    val app = (item as? Item.Shortcut)?.let { Home.app(it.app) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(app?.let { Home.label(it) } ?: (item as? Item.Folder)?.name ?: "Widget") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Panel {
                if (app != null) {
                    ListRow("Open", icon = WonderIcons.OpenInNew, onClick = { onDismiss(); context.launch(app) }); PanelDivider()
                    if (app.key !in Home.dock && Home.dock.size < 8) { ListRow("Move to Dock", icon = WonderIcons.Dashboard, tint = Color(0xFF16A085), onClick = { onDismiss(); Home.dock = Home.dock + app.key; Home.remove(context, item.id) }); PanelDivider() }
                    ListRow("Rename", icon = WonderIcons.DriveFileRenameOutline, tint = Color(0xFF8E44AD), onClick = { renaming = true }); PanelDivider()
                    ListRow("App info", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), onClick = { onDismiss(); context.appInfo(app) }); PanelDivider()
                    ListRow("Uninstall", icon = WonderIcons.Delete, danger = true, onClick = { onDismiss(); context.uninstall(app) }); PanelDivider()
                }
                if (item is Item.Folder) {
                    ListRow("Open folder", icon = WonderIcons.FolderOpen, onClick = { onDismiss(); onOpenFolder(item) }); PanelDivider()
                    ListRow("Rename", icon = WonderIcons.DriveFileRenameOutline, tint = Color(0xFF8E44AD), onClick = { renaming = true }); PanelDivider()
                    ListRow("Put the apps on this page", subtitle = "Removes the folder, keeps the apps", icon = WonderIcons.Unarchive, tint = Color(0xFF16A085), onClick = {
                        onDismiss(); Home.remove(context, item.id); item.apps.forEach { Home.addShortcut(context, it, item.page, item.x to item.y) }
                    }); PanelDivider()
                }
                ListRow("Remove from home screen", icon = WonderIcons.Close, tint = Color(0xFF7A7F87), onClick = { onDismiss(); Home.remove(context, item.id) })
            }
            Text("Tip: hold and drag to move. Hold an app over another app to make a folder.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (renaming) {
        if (app != null) InputDialog("Rename ${app.label}", "Name", initial = Home.label(app), onDismiss = { renaming = false }) { n -> Home.names = Home.names + (app.key to n); Home.save(context); onDismiss() }
        else if (item is Item.Folder) InputDialog("Rename folder", "Name", initial = item.name, onDismiss = { renaming = false }) { n -> Home.update(context, item.copy(name = n)); onDismiss() }
    }
}

@Composable
private fun DockMenu(key: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val a = Home.app(key)
    if (a == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val i = Home.dock.indexOf(key)
    WonderDialog(onDismissRequest = onDismiss, title = { Text(Home.label(a)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Panel {
                ListRow("Open", icon = WonderIcons.OpenInNew, onClick = { onDismiss(); context.launch(a) }); PanelDivider()
                if (i > 0) { ListRow("Move left", icon = WonderIcons.KeyboardArrowLeft, tint = Color(0xFF1E7FE8), onClick = { Home.dock = Home.dock.toMutableList().apply { add(i - 1, removeAt(i)) }; Home.save(context); onDismiss() }); PanelDivider() }
                if (i in 0 until Home.dock.lastIndex) { ListRow("Move right", icon = WonderIcons.KeyboardArrowRight, tint = Color(0xFF1E7FE8), onClick = { Home.dock = Home.dock.toMutableList().apply { add(i + 1, removeAt(i)) }; Home.save(context); onDismiss() }); PanelDivider() }
                ListRow("Remove from Dock", icon = WonderIcons.Close, tint = Color(0xFF7A7F87), onClick = { Home.dock = Home.dock - key; Home.save(context); onDismiss() }); PanelDivider()
                ListRow("App info", icon = WonderIcons.Info, tint = Color(0xFF7A7F87), onClick = { onDismiss(); context.appInfo(a) }); PanelDivider()
                ListRow("Uninstall", icon = WonderIcons.Delete, danger = true, onClick = { onDismiss(); context.uninstall(a) })
            }
            Text("Tip: hold and drag to reorder the Dock, or drag an app onto it from the home screen.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun HomeMenu(page: Int, onDismiss: () -> Unit, onWidgets: () -> Unit, onWallpaper: () -> Unit, onSettings: () -> Unit, onAddApps: () -> Unit, onPages: () -> Unit, onAddPage: () -> Unit, onRemovePage: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    val count = Home.items.count { it.page == page }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Home screen") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Panel {
                ListRow("Add apps to this page", icon = WonderIcons.AddCircle, tint = Color(0xFF2E9E44), onClick = { onDismiss(); onAddApps() }); PanelDivider()
                ListRow("Add a widget", icon = WonderIcons.Widgets, tint = Color(0xFF1E7FE8), onClick = { onDismiss(); onWidgets() }); PanelDivider()
                ListRow("Desktop & wallpaper", icon = WonderIcons.Wallpaper, tint = Color(0xFF8E44AD), onClick = { onDismiss(); onWallpaper() })
            }
            Spacer(Modifier.height(12.dp))
            Panel(title = "Pages") {
                ListRow("Add a page", icon = WonderIcons.Add, tint = Color(0xFF2E9E44), onClick = { onDismiss(); onAddPage() }); PanelDivider()
                if (Home.pageCount > 1) { ListRow("Remove this page", subtitle = if (count == 0) "It's empty" else "$count item${if (count == 1) "" else "s"} on it", icon = WonderIcons.Remove, danger = true, onClick = { if (count == 0) { onDismiss(); onRemovePage() } else confirmRemove = true }); PanelDivider() }
                ListRow("Arrange pages", icon = WonderIcons.ViewList, tint = Color(0xFF7A7F87), chevron = true, onClick = { onDismiss(); onPages() })
            }
            Spacer(Modifier.height(12.dp))
            Panel { ListRow("Home screen settings", icon = WonderIcons.Settings, tint = Color(0xFF7A7F87), chevron = true, onClick = { onDismiss(); onSettings() }) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (confirmRemove) ConfirmDialog("Remove this page?", "Its $count items are removed from the home screen. The apps stay installed.", "Remove page", onConfirm = { onDismiss(); onRemovePage() }, onDismiss = { confirmRemove = false }, danger = true)
}

@Composable
private fun PagesDialog(current: Int, onGo: (Int) -> Unit, onDelete: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Int?>(null) }
    val p = wonderPalette()
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Pages") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Panel {
                (0 until Home.pageCount).forEach { i ->
                    if (i > 0) PanelDivider(0.dp)
                    val n = Home.items.count { it.page == i }
                    Row(Modifier.fillMaxWidth().clickable { onGo(i); onDismiss() }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Page ${i + 1}" + if (i == current) " (showing)" else "", style = MaterialTheme.typography.bodyLarge)
                            Text("$n item${if (n == 1) "" else "s"}" + if (i == Home.settings.defaultPage) " · main page" else "", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                        }
                        IconButton(onClick = { Home.settings = Home.settings.copy(defaultPage = i); Home.save(context) }) { Icon(WonderIcons.Home, "Make main page", tint = if (i == Home.settings.defaultPage) p.accent else p.secondaryText) }
                        IconButton(enabled = i > 0, onClick = { Home.movePage(context, i, i - 1) }) { Icon(WonderIcons.KeyboardArrowUp, "Move earlier") }
                        IconButton(enabled = i < Home.pageCount - 1, onClick = { Home.movePage(context, i, i + 1) }) { Icon(WonderIcons.KeyboardArrowDown, "Move later") }
                        IconButton(enabled = Home.pageCount > 1, onClick = { if (n == 0) onDelete(i) else confirm = i }) { Icon(WonderIcons.Delete, "Remove page", tint = p.danger) }
                    }
                }
                PanelDivider(0.dp)
                ListRow("Add a page", icon = WonderIcons.Add, tint = Color(0xFF2E9E44), onClick = { Home.addPage(context) })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    confirm?.let { i -> ConfirmDialog("Remove page ${i + 1}?", "Everything on it is removed from the home screen. The apps stay installed.", "Remove", onConfirm = { onDelete(i) }, onDismiss = { confirm = null }, danger = true) }
}

private fun installedBeacon(c: Context) = runCatching { c.packageManager.getPackageInfo("dev.wonderdesk.beacon", 0) }.isSuccess

/* ---------------------------------------------------- widgets ----------------------------------------------- */

@Composable
private fun WidgetView(activity: MainActivity, w: Item.Widget, drag: DragController? = null, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val mgr = remember { AppWidgetManager.getInstance(context) }
    var infoState by remember(w.widgetId) { mutableStateOf(mgr.getAppWidgetInfo(w.widgetId)) }
    // A widget added by another app (or just bound) can take a moment to become available.
    LaunchedEffect(w.widgetId) { var tries = 0; while (infoState == null && tries++ < 20) { delay(500); infoState = mgr.getAppWidgetInfo(w.widgetId) } }
    val info = infoState
    val latest = rememberUpdatedState(onLongPress)
    if (info == null) {
        Box(Modifier.fillMaxSize().padding(6.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = .3f)).pointerInput(Unit) { detectTapGestures(onLongPress = { latest.value() }) }, contentAlignment = Alignment.Center) {
            Text("Widget unavailable. Hold to remove.", style = labelStyle, textAlign = TextAlign.Center)
        }
        return
    }
    Box(Modifier.fillMaxSize().padding(4.dp).pointerInput(Unit) {
        // Touches on a widget belong to the widget, never to the empty-desktop long press.
        awaitPointerEventScope { while (true) { val e = awaitPointerEvent(PointerEventPass.Initial); itemTouch = e.changes.any { it.pressed } } }
    }) {
        val item = rememberUpdatedState(w)
        fun wire(hv: WdHostView) {
            hv.onLongPress = { drag?.reset(); latest.value() }
            hv.onLift = { x, y -> drag?.let { d -> d.payload = Payload.HomeItem(item.value); d.pos = Offset(x, y) } }
            hv.onDrag = { x, y -> drag?.let { d -> if (!d.dragging) d.dragging = true; d.pos = Offset(x, y) } }
            hv.onDrop = { drag?.let { d -> if (d.dragging) dropRequested.intValue++ else d.reset() } }
        }
        AndroidView({ ctx -> (activity.host.createView(ctx, w.widgetId, info) as? WdHostView ?: WdHostView(ctx).also { it.setAppWidget(w.widgetId, info) }).apply { setPadding(0, 0, 0, 0); wire(this) } }, Modifier.fillMaxSize(),
            update = { v -> (v as? WdHostView)?.let { wire(it) } })
    }
}

@Composable
private fun WidgetMenu(activity: MainActivity, w: Item.Widget, onMove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember(w.widgetId) { AppWidgetManager.getInstance(context).getAppWidgetInfo(w.widgetId) }
    val name = info?.loadLabel(context.packageManager) ?: "Widget"
    val s = Home.settings
    val current = Home.items.find { it.id == w.id } as? Item.Widget ?: w
    WonderDialog(onDismissRequest = onDismiss, title = { Text(name) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Size", style = MaterialTheme.typography.titleSmall)
            val sizes = (1..s.cols).flatMap { cw -> (1..s.rows).map { ch -> cw to ch } }.filter { (cw, ch) -> Home.fits(current.page, current.x, current.y, cw, ch, current.id) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sizes.forEach { (cw, ch) -> Chip("$cw×$ch", cw == current.w && ch == current.h, { Home.update(context, current.copy(w = cw, h = ch)) }) }
            }
            Panel {
                ListRow("Move", subtitle = "Then tap where it should go", icon = WonderIcons.DriveFileMove, tint = Color(0xFF1E7FE8), onClick = onMove); PanelDivider()
                if (info?.configure != null) { ListRow("Edit widget", subtitle = "Change what it shows", icon = WonderIcons.Tune, tint = Color(0xFF8E44AD), onClick = {
                    onDismiss(); runCatching { activity.host.startAppWidgetConfigureActivityForResult(activity, w.widgetId, 0, 4243, null) }
                }); PanelDivider() }
                ListRow("Remove widget", icon = WonderIcons.Delete, danger = true, onClick = { onDismiss(); activity.host.deleteAppWidgetId(w.widgetId); Home.remove(context, w.id) })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

/** Full-screen widget gallery: search, grouped by app, with previews and sizes. */
@Composable
private fun WidgetPicker(activity: MainActivity, page: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val mgr = remember { AppWidgetManager.getInstance(context) }
    var q by remember { mutableStateOf("") }
    val providers = remember { mgr.installedProviders }
    val byApp = remember(q) {
        providers.filter { pr -> q.isBlank() || pr.loadLabel(context.packageManager).contains(q, true) || appName(context, pr.provider.packageName).contains(q, true) }
            .groupBy { it.provider.packageName }.toList().sortedBy { (pkg, _) -> (if (pkg.startsWith("dev.wonderdesk.")) "0" else "1") + appName(context, pkg).lowercase() }
    }
    var pending by remember { mutableIntStateOf(-1) }
    var pendingInfo by remember { mutableStateOf<AppWidgetProviderInfo?>(null) }
    val dens = context.resources.displayMetrics.density
    // The widget's own preferred size, else Android's standard rule (n cells ≈ 70n − 30 dp).
    fun cells(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val tw = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellWidth else 0
        val th = if (android.os.Build.VERSION.SDK_INT >= 31) info.targetCellHeight else 0
        val w = if (tw > 0) tw else kotlin.math.ceil((info.minWidth / dens + 30) / 70.0).toInt()
        val h = if (th > 0) th else kotlin.math.ceil((info.minHeight / dens + 30) / 70.0).toInt()
        return w.coerceIn(1, Home.settings.cols) to h.coerceIn(1, Home.settings.rows)
    }
    fun place(id: Int, info: AppWidgetProviderInfo) {
        val (cw, ch) = cells(info)
        val cell = Home.freeCell(page, cw, ch) ?: Home.freeCell(0, cw, ch)
        if (cell == null) { activity.host.deleteAppWidgetId(id); return }
        Home.items = Home.items + Item.Widget(Home.newId(), id, cell.first, cell.second, cell.third, cw, ch); Home.save(context)
        if (cell.first != page) { android.widget.Toast.makeText(context, "No room on this page, so it went on page ${cell.first + 1}", android.widget.Toast.LENGTH_SHORT).show(); Home.jumpTo = cell.first }
        onDismiss()
    }
    fun bound(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) runCatching { activity.host.startAppWidgetConfigureActivityForResult(activity, id, 0, 4242, null) }
        place(id, info)
    }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val info = pendingInfo ?: return@rememberLauncherForActivityResult
        if (r.resultCode == Activity.RESULT_OK) bound(pending, info) else activity.host.deleteAppWidgetId(pending)
    }
    WonderApp(appName = "Wonderdesk", about = "Widgets", title = "Add a widget", onBack = onDismiss) {
        Column(Modifier.fillMaxSize()) {
            SearchField(q, { q = it }, "Search widgets", Modifier.padding(16.dp, 12.dp))
            if (byApp.isEmpty()) EmptyState("No widgets found", if (q.isBlank()) "Apps you install can add widgets here." else "Nothing matches “$q”.", icon = WonderIcons.Widgets)
            LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(byApp, key = { it.first }) { (pkg, list) ->
                    Panel(title = appName(context, pkg)) {
                        list.forEachIndexed { i, pr ->
                            if (i > 0) PanelDivider(0.dp)
                            val preview = remember(pr) { runCatching { pr.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)?.toBitmap(360, (360f * pr.minHeight / pr.minWidth.coerceAtLeast(1)).toInt().coerceIn(80, 360))?.asImageBitmap() }.getOrNull()
                                ?: runCatching { pr.loadIcon(context, context.resources.displayMetrics.densityDpi).toBitmap(120, 120).asImageBitmap() }.getOrNull() }
                            val (cw, ch) = cells(pr)
                            Row(Modifier.fillMaxWidth().clickable {
                                val id = activity.host.allocateAppWidgetId()
                                pending = id; pendingInfo = pr
                                if (mgr.bindAppWidgetIdIfAllowed(id, pr.provider)) bound(id, pr)
                                else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, pr.provider))
                            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(width = 120.dp, height = 84.dp).clip(RoundedCornerShape(10.dp)).background(Brush.verticalGradient(listOf(Color(0xFF3B6EA8), Color(0xFF1C3F6B)))), contentAlignment = Alignment.Center) {
                                    // Android 12+: show the widget itself (its live preview layout), shrunk to fit; otherwise its preview picture.
                                    val live = android.os.Build.VERSION.SDK_INT >= 31 && pr.previewLayout != 0 && pr.loadPreviewImage(context, 0) == null
                                    if (live) androidx.compose.ui.viewinterop.AndroidView({ ctx ->
                                        android.widget.FrameLayout(ctx).apply {
                                            runCatching {
                                                val v = android.widget.RemoteViews(pr.provider.packageName, pr.previewLayout).apply(ctx, this)
                                                val wPx = (pr.minWidth.coerceAtLeast(110)); val hPx = (pr.minHeight.coerceAtLeast(60))
                                                addView(v, android.widget.FrameLayout.LayoutParams(wPx, hPx, android.view.Gravity.CENTER))
                                                post { val sc = minOf(width / wPx.toFloat(), height / hPx.toFloat(), 1f) * .92f; v.scaleX = sc; v.scaleY = sc }
                                            }
                                        }
                                    }, Modifier.fillMaxSize())
                                    else preview?.let { Image(it, null, Modifier.fillMaxSize().padding(6.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pr.loadLabel(context.packageManager), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("$cw × $ch" + if (pr.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) " · resizable" else "", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                                }
                                Icon(WonderIcons.AddCircle, "Add", tint = p.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun appName(c: Context, pkg: String) = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

/* --------------------------------------------------- wallpaper ---------------------------------------------- */

@Composable
private fun WallpaperPicker(onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<Pair<String?, Uri?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val thumbs = remember { mutableStateMapOf<String, ImageBitmap>() }
    LaunchedEffect(Unit) { Wallpapers.STYLES.forEach { st -> thumbs[st.id] = withContext(Dispatchers.Default) { Wallpapers.thumbnail(st.id).asImageBitmap() } } }
    // Android's photo picker works with any gallery, including Memories, and needs no permission.
    val toast = remember { mutableStateOf<(String) -> Unit>({}) }
    var editing by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val dm = context.resources.displayMetrics
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) scope.launch {
        busy = true
        editing = withContext(Dispatchers.IO) { runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxOf(dm.widthPixels, dm.heightPixels) * 2) sample *= 2
            val b = context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }!!
            val deg = runCatching { context.contentResolver.openInputStream(uri)!!.use { when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 } } }.getOrDefault(0)
            if (deg == 0) b else android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
        }.getOrNull() }
        busy = false
        if (editing == null) toast.value("Couldn't open that picture")
    } }
    editing?.let { b -> WallpaperEditor(b, onClose = { editing = null }, onDone = { m -> editing = null; toast.value(m) }); return }
    WonderApp(appName = "Wonderdesk", about = "Wallpaper", title = "Desktop & wallpaper", onBack = onClose) {
        toast.value = LocalToast.current
        Page {
            Panel(title = "Wonderdesk wallpapers") {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Wallpapers.STYLES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { st ->
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { scope.launch { editing = withContext(Dispatchers.Default) { Wallpapers.render(st.id, dm.widthPixels, dm.heightPixels) } } }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(p.window).border(1.dp, p.panelBorder, RoundedCornerShape(10.dp))) {
                                        thumbs[st.id]?.let { Image(it, st.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                                    }
                                    Text(st.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Panel {
                ListRow("Choose a photo", subtitle = "From Memories or any of your pictures", icon = WonderIcons.PhotoLibrary, tint = Color(0xFF2E9E44), chevron = true,
                    onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                PanelDivider()
                ListRow("Other wallpaper apps", subtitle = "Live wallpapers and your phone's own styles", icon = WonderIcons.Wallpaper, tint = Color(0xFF8E44AD), chevron = true,
                    onClick = { context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) {} })
            }
            var dayNight by remember { mutableStateOf(DayNight.enabled(context)) }
            var dayH by remember { mutableIntStateOf(DayNight.dayHour(context)) }
            var nightH by remember { mutableIntStateOf(DayNight.nightHour(context)) }
            Panel(title = "Day and night", footer = if (dayNight) "Pick any wallpaper and choose Daytime or Night wallpaper when setting it." else "Pick a wallpaper above and choose “Daytime wallpaper” or “Night wallpaper” when setting it. They swap by themselves at the times below.") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Day starts at $dayH:00", style = MaterialTheme.typography.labelLarge)
                    Slider(dayH.toFloat(), { dayH = it.toInt() }, valueRange = 4f..11f, steps = 6, onValueChangeFinished = { DayNight.setHours(context, dayH, nightH) })
                    Text("Night starts at $nightH:00", style = MaterialTheme.typography.labelLarge)
                    Slider(nightH.toFloat(), { nightH = it.toInt() }, valueRange = 16f..23f, steps = 6, onValueChangeFinished = { DayNight.setHours(context, dayH, nightH) })
                }
                if (dayNight) { PanelDivider(0.dp); ListRow("Stop switching", subtitle = "Keeps whatever wallpaper is up now", icon = WonderIcons.Close, danger = true, onClick = { DayNight.clear(context); dayNight = false }) }
            }
            if (busy) AquaProgress(null, Modifier.fillMaxWidth())
        }
    }
    target?.let { (style, uri) ->
        WonderDialog(onDismissRequest = { target = null }, title = { Text("Set wallpaper") }, text = {
            Panel {
                listOf("Home screen" to WallpaperManager.FLAG_SYSTEM, "Lock screen" to WallpaperManager.FLAG_LOCK, "Both" to (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)).forEachIndexed { i, (label, flag) ->
                    if (i > 0) PanelDivider()
                    ListRow(label, onClick = {
                        target = null; busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { runCatching { if (style != null) Wallpapers.applyStyle(context, style, flag) else Wallpapers.applyPhoto(context, uri!!, flag) } }
                            busy = false
                            r.onSuccess { toast.value("Wallpaper set") }.onFailure { toast.value(it.message ?: "Couldn't set the wallpaper") }
                        }
                    })
                }
            }
        }, confirmButton = { TextButton(onClick = { target = null }) { Text("Cancel") } })
    }
}

/* ---------------------------------------------------- welcome ----------------------------------------------- */

@Composable
private fun Welcome(activity: MainActivity, onMore: () -> Unit, onWallpaper: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(Home.settings) }
    fun set(n: Settings) { val restyle = n.iconStyle != s.iconStyle || n.iconPack != s.iconPack; s = n; Home.settings = n; Home.save(context); if (restyle) Home.reloadIcons(context) }
    val isDefault = context.isDefaultHome()
    WonderDialog(onDismissRequest = onClose, title = { Text("Welcome to Wonderdesk") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("An Aqua desktop for your phone: a magnifying Dock, folders, widgets, and Beacon for all your apps.", style = MaterialTheme.typography.bodyMedium)
            if (!isDefault) WButton("Make Wonderdesk my home screen", { activity.requestDefault() }, Modifier.fillMaxWidth(), icon = WonderIcons.Home, kind = ButtonKind.Primary)
            else Row(verticalAlignment = Alignment.CenterVertically) { Icon(WonderIcons.CheckCircle, null, tint = wonderPalette().success); Spacer(Modifier.width(8.dp)); Text("Wonderdesk is your home screen") }
            Text("Icons", style = MaterialTheme.typography.titleSmall)
            Segmented(listOf("Natural", "Aqua tiles"), if (s.iconStyle == "tiles") 1 else 0) { set(s.copy(iconStyle = if (it == 1) "tiles" else "natural")) }
            Text("Home screen grid", style = MaterialTheme.typography.titleSmall)
            Segmented(listOf("3×4", "4×5", "4×6", "5×6"), listOf(3 to 4, 4 to 5, 4 to 6, 5 to 6).indexOf(s.cols to s.rows).coerceAtLeast(0)) { i ->
                val (c, r) = listOf(3 to 4, 4 to 5, 4 to 6, 5 to 6)[i]; set(s.copy(cols = c, rows = r))
            }
            Panel {
                SwitchRow("Magnifying Dock", s.dockMagnify, { set(s.copy(dockMagnify = it)) }); PanelDivider()
                SwitchRow("Menu bar with clock", s.menuBar, { set(s.copy(menuBar = it)) }); PanelDivider()
                SwitchRow("Allow home screen rotation", s.rotate, { set(s.copy(rotate = it)); (context as? MainActivity)?.applyRotation() }, subtitle = "Off keeps the home screen upright when you turn the phone; apps still rotate"); PanelDivider()
                SwitchRow("Endless page scrolling", s.infinite, { set(s.copy(infinite = it)) }); PanelDivider()
                ListRow("Choose a wallpaper", subtitle = "Wonderdesk's Aqua wallpapers or a photo", icon = WonderIcons.Wallpaper, tint = Color(0xFF8E44AD), chevron = true, onClick = onWallpaper)
            }
            SwitchRow("Ask again when Wonderdesk isn't the home app", s.askDefault, { set(s.copy(askDefault = it)) })
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Done") } }, dismissButton = { TextButton(onClick = onMore) { Text("More settings") } })
}

/* ---------------------------------------------------- settings ---------------------------------------------- */

/** What Android must allow for the launcher to work fully. */
private val APP_NEEDS by lazy { listOf(
    WonderNeed("Default home app", "Makes the Home button and gesture open Wonderdesk", check = { c -> c.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) ?: true },
        fix = { c -> c.launchSafely(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) {} }),
    WonderNeed("Double-tap to lock", "Locks the screen when you double-tap the home screen", required = false, access = SpecialAccess.Kind.ACCESSIBILITY, component = LockService::class.java),
) }

@Composable
private fun LauncherSettings(onClose: () -> Unit, onWallpaper: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(Home.settings) }
    fun set(n: Settings) { s = n; Home.settings = n; Home.save(context) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> u?.let { runCatching { context.contentResolver.openOutputStream(it, "wt")!!.use { o -> o.write(Home.exportLayout().toByteArray()) } } } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { runCatching { Home.importLayout(context, context.contentResolver.openInputStream(it)!!.bufferedReader().readText()) } } }
    var hiddenOpen by remember { mutableStateOf(false) }
    var lockGuide by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    if (lockGuide) AccessGuide(SpecialAccess.Kind.ACCESSIBILITY, "To lock the screen with a gesture, Android needs Wonderdesk's “double-tap to lock” accessibility switch on. It only locks the screen and reads nothing.",
        onDismiss = { lockGuide = false }, component = LockService::class.java)
    WonderApp(needs = APP_NEEDS, appName = "Wonderdesk Launcher", about = "The Wonderdesk home screen: an Aqua desktop with a magnifying Dock, folders, widgets and gestures. All your apps are in Beacon.", title = "Home screen", onBack = onClose) {
        Page {
            Panel(title = "Default home") {
                val isDefault = context.isDefaultHome()
                ListRow(if (isDefault) "Wonderdesk is your home screen" else "Make Wonderdesk your home screen", subtitle = if (isDefault) "Change it in Android's home app setting" else "Android will ask you to confirm",
                    icon = WonderIcons.Home, tint = Color(0xFF2E9E44), chevron = true, trailing = if (isDefault) ({ Icon(WonderIcons.CheckCircle, null, tint = wonderPalette().success) }) else null,
                    onClick = { if (isDefault) context.launchSafely(Intent(AndroidSettings.ACTION_HOME_SETTINGS)) {} else (context as? MainActivity)?.requestDefault() })
                PanelDivider()
                SwitchRow("Remind me when it isn't", s.askDefault, { set(s.copy(askDefault = it)) })
            }
            Panel { ListRow("Desktop & wallpaper", subtitle = "Wonderdesk's Aqua wallpapers or your own photo", icon = WonderIcons.Wallpaper, tint = Color(0xFF8E44AD), chevron = true, onClick = onWallpaper) }
            Panel(title = "Icons", footer = "Natural keeps each icon's own shape and see-through edges. Aqua tiles puts every icon on a rounded glossy tile.") {
                Column(Modifier.padding(12.dp)) { Segmented(listOf("Natural", "Aqua tiles"), if (s.iconStyle == "tiles") 1 else 0) { set(s.copy(iconStyle = if (it == 1) "tiles" else "natural")); Home.reloadIcons(context) } }
                PanelDivider(0.dp)
                PanelDivider()
                SwitchRow("Interface sounds", s.sounds, { set(s.copy(sounds = it)); Sounds.enabled = it; if (it) Sounds.play(context, Sounds.S.Chime) },
                    subtitle = "Soft desktop sounds when apps and folders open and things are removed. Silent when your ringer is off", icon = WonderIcons.VolumeUp, tint = Color(0xFFE5483C))
                PanelDivider()
                var packs by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
                LaunchedEffect(Unit) { packs = withContext(Dispatchers.IO) { IconPack.installed(context) } }
                var choosePack by remember { mutableStateOf(false) }
                ListRow("Icon pack", subtitle = packs?.find { it.first == s.iconPack }?.second ?: if (s.iconPack.isBlank()) "Apps' own icons" else "Pack not installed",
                    icon = WonderIcons.Palette, tint = Color(0xFF8E44AD), chevron = true, onClick = { choosePack = true })
                if (choosePack) WonderDialog(onDismissRequest = { choosePack = false }, title = { Text("Icon pack") }, text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Panel {
                            ListRow("Apps' own icons", selected = s.iconPack.isBlank(), onClick = { set(s.copy(iconPack = "")); choosePack = false })
                            packs.orEmpty().forEach { (pk, name) -> PanelDivider(16.dp); ListRow(name, selected = s.iconPack == pk, onClick = { set(s.copy(iconPack = pk)); choosePack = false }) }
                        }
                        Text(if (packs.isNullOrEmpty()) "No icon packs installed. Any pack made for Nova, Lawnchair or other launchers works: install one from your app store and it appears here."
                            else "Apps the pack doesn't cover keep their own icon. Wonderdesk apps keep their artwork.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
                    }
                }, confirmButton = { TextButton(onClick = { choosePack = false }) { Text("Close") } })
                PanelDivider()
                ListRow("Refresh icons", subtitle = "Redraw every icon now", icon = WonderIcons.Refresh, tint = Color(0xFF1E7FE8), onClick = { IconLoader.clearDisk(context); Home.reloadIcons(context) })
            }
            Panel(title = "Layout") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Columns: ${s.cols}", style = MaterialTheme.typography.titleSmall); Segmented(listOf("3", "4", "5", "6"), s.cols - 3) { set(s.copy(cols = it + 3)) }
                    Text("Rows: ${s.rows}", style = MaterialTheme.typography.titleSmall); Segmented(listOf("4", "5", "6", "7"), s.rows - 4) { set(s.copy(rows = it + 4)) }
                    Text("Icon size", style = MaterialTheme.typography.titleSmall)
                    Slider(s.iconScale, { set(s.copy(iconScale = it)) }, valueRange = .75f..1.3f)
                }
                PanelDivider(0.dp)
                SwitchRow("Allow home screen rotation", s.rotate, { set(s.copy(rotate = it)); (context as? MainActivity)?.applyRotation() }, subtitle = "Off keeps the home screen upright when you turn the phone; apps still rotate"); PanelDivider()
                SwitchRow("Endless page scrolling", s.infinite, { set(s.copy(infinite = it)) }, subtitle = "Swiping past the last page goes back to the first"); PanelDivider()
                SwitchRow("Show app names", s.labels, { set(s.copy(labels = it)) }); PanelDivider()
                SwitchRow("Dark names (for light wallpapers)", s.darkLabels, { set(s.copy(darkLabels = it)) }); PanelDivider()
                SwitchRow("Menu bar", s.menuBar, { set(s.copy(menuBar = it)) }, subtitle = "Tap the date for Chronicle and the time for Timepiece")
            }
            Panel(title = "Dock") {
                SwitchRow("Magnify icons", s.dockMagnify, { set(s.copy(dockMagnify = it)) }, subtitle = "Icons grow as your finger moves across the Dock"); PanelDivider()
                SwitchRow("Reflections", s.reflections, { set(s.copy(reflections = it)) }, subtitle = "Mirror the icons on the glass shelf"); PanelDivider()
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
                            listOf("launchpad", "search", "notifications", "lock", "none").forEach { g -> DropdownMenuItem({ Text(gestureName(g)) }, { open = false; setter(g); if (g == "lock" && LockService.instance == null) lockGuide = true }) }
                        }
                    }
                }
            }
            Panel(title = "All apps (Beacon)", footer = if (installedBeacon(LocalContext.current)) "All your apps live in Beacon: swipe up, tap Apps in the menu bar, or tap Beacon in the Dock. Hold an app there to add it to the home screen or Dock." else "Install Beacon from Wonderdesk Apphub to see all your apps with search and folders.") {
                var sortOpen by remember { mutableStateOf(false) }
                Box {
                    ListRow("Sort apps", trailing = { Text(SORTS.first { it.first == s.launchpadSort }.second, color = wonderPalette().secondaryText) }, chevron = true, onClick = { sortOpen = true })
                    DropdownMenu(sortOpen, { sortOpen = false }) { SORTS.forEach { (id, n) -> DropdownMenuItem({ Text(n) }, { sortOpen = false; set(s.copy(launchpadSort = id)) }) } }
                }
                PanelDivider()
                SwitchRow("Folders first", s.lpFoldersFirst, { set(s.copy(lpFoldersFirst = it)) }); PanelDivider()
                ListRow("Hidden apps", subtitle = "${Home.hidden.size} hidden", icon = WonderIcons.VisibilityOff, tint = Color(0xFF7A7F87), chevron = true, onClick = { hiddenOpen = true })
            }
            Panel(title = "Backup") {
                ListRow("Export layout", icon = WonderIcons.Download, tint = Color(0xFF16A085), onClick = { export.launch("wonderdesk-home.json") }); PanelDivider()
                ListRow("Import layout", icon = WonderIcons.UploadFile, tint = Color(0xFF1E7FE8), onClick = { import.launch(arrayOf("application/json", "*/*")) }); PanelDivider()
                ListRow("Reset home screen", subtitle = "Start again with the default layout", icon = WonderIcons.Restore, danger = true, onClick = { confirmReset = true })
            }
        }
    }
    if (confirmReset) ConfirmDialog("Reset the home screen?", "Your pages, folders, Dock and Beacon folders go back to the default layout. Widgets are removed.", "Reset", onConfirm = { Home.resetLayout(context) }, onDismiss = { confirmReset = false }, danger = true)
    if (hiddenOpen) WonderDialog(onDismissRequest = { hiddenOpen = false }, title = { Text("Hidden apps") }, text = {
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(Home.apps.sortedBy { Home.label(it).lowercase() }, key = { it.key }) { a ->
                ListRow(Home.label(a), leading = { Image(a.icon, null, Modifier.size(32.dp)) }, trailing = {
                    Checkbox(a.key in Home.hidden, { on -> Home.hidden = if (on) Home.hidden + a.key else Home.hidden - a.key; Home.save(context) })
                })
            }
        }
    }, confirmButton = { TextButton(onClick = { hiddenOpen = false }) { Text("Done") } })
}

private fun gestureName(g: String) = when (g) { "launchpad", "beacon" -> "Open Beacon (all apps)"; "search" -> "Search with Beacon"; "notifications" -> "Show notifications"; "lock" -> "Lock screen"; else -> "Nothing" }
