package dev.wonderdesk.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.wonderdesk.shared.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** What the recorder is doing, as Cinema last told us. */
object AssistState {
    var recording by mutableStateOf(false)
    var clipMode by mutableStateOf(false)
    var writing by mutableStateOf(false)
    /** The button is hidden until you bring it back (menu, tile or shortcut). */
    var hidden by mutableStateOf(false)
}

/** What a shortcut in the floating button's menu is. [pkg] set means it opens that app and shows the app's own art. */
data class AssistAct(val id: String, val label: String, val icon: ImageVector?, val group: String, val pkg: String? = null)

/** Everything about the floating button that can be changed, and what each shortcut does. */
object AssistActions {
    private val APPS = listOf("Beacon" to "dev.wonderdesk.beacon", "Polaris" to "dev.wonderdesk.polaris", "Scribble" to "dev.wonderdesk.scribble", "Chronicle" to "dev.wonderdesk.chronicle",
        "Timepiece" to "dev.wonderdesk.timepiece", "Memories" to "dev.wonderdesk.memories", "Cinema" to "dev.wonderdesk.cinema", "Ripple" to "dev.wonderdesk.ripple", "Vault" to "dev.wonderdesk.vault",
        "Control Room" to "dev.wonderdesk.controlroom", "Wayfinder" to "dev.wonderdesk.wayfinder", "Pages" to "dev.wonderdesk.pages", "Packrat" to "dev.wonderdesk.packrat", "Abacus" to "dev.wonderdesk.abacus",
        "Pulse" to "dev.wonderdesk.pulse", "Safekeeper" to "dev.wonderdesk.safekeeper", "Echo" to "dev.wonderdesk.echo", "Apphub" to "dev.wonderdesk.apphub", "Bliss Player" to "com.blissplayer.music.debug")

    val CATALOG: List<AssistAct> by lazy {
        listOf(
            AssistAct("home", "Home", WonderIcons.Home, "System"), AssistAct("back", "Back", WonderIcons.ArrowBack, "System"), AssistAct("recents", "Recents", WonderIcons.Layers, "System"),
            AssistAct("notifications", "Alerts", WonderIcons.Notifications, "System"), AssistAct("quick", "Quick set", WonderIcons.Tune, "System"), AssistAct("lock", "Lock", WonderIcons.Lock, "System"),
            AssistAct("screenshot", "Screenshot", WonderIcons.PhotoLibrary, "System"), AssistAct("power", "Power", WonderIcons.Bolt, "System"), AssistAct("split", "Split", WonderIcons.Dashboard, "System"),
            AssistAct("volup", "Vol up", WonderIcons.VolumeUp, "System"), AssistAct("voldown", "Vol down", WonderIcons.VolumeOff, "System"),
            AssistAct("rec", "Record", WonderIcons.Videocam, "Recording"), AssistAct("clip", "Clip", WonderIcons.ContentCut, "Recording"),
            AssistAct("newnote", "New note", WonderIcons.NoteAdd, "Quick"), AssistAct("newevent", "New event", WonderIcons.Event, "Quick"), AssistAct("search", "Search", WonderIcons.Search, "Quick"),
            AssistAct("hide", "Hide", WonderIcons.VisibilityOff, "Quick"),
        ) + APPS.map { (n, p) -> AssistAct("app:$p", n, null, "Apps", p) }
    }
    fun byId(id: String) = CATALOG.firstOrNull { it.id == id }
    val DEFAULT = listOf("home", "back", "recents", "notifications", "screenshot", "lock", "rec", "clip", "newnote", "search", "app:dev.wonderdesk.beacon", "hide")
    /** What a tap, double tap or long press on the button itself can do. */
    val GESTURES = listOf("menu" to "Open the menu", "hide" to "Hide the button", "home" to "Home", "back" to "Back", "recents" to "Recents", "screenshot" to "Screenshot", "rec" to "Record or stop", "lock" to "Lock", "none" to "Nothing")
    val ACCENTS = listOf("look" to "Match my look", "blue" to "Blue", "green" to "Green", "purple" to "Purple", "orange" to "Orange", "red" to "Red", "graphite" to "Graphite")
    fun accent(id: String, fallback: Color) = when (id) { "blue" -> Color(0xFF1E6FE8); "green" -> Color(0xFF1F9D55); "purple" -> Color(0xFF7B4BD8); "orange" -> Color(0xFFE8831E); "red" -> Color(0xFFD93B3B); "graphite" -> Color(0xFF3B4250); else -> fallback }

    private fun prefs(c: Context) = c.getSharedPreferences("assist", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, v: Boolean) { prefs(c).edit().putBoolean("on", v).apply(); if (v) AssistState.hidden = false; LockService.instance?.refreshButton() }
    fun size(c: Context) = prefs(c).getInt("size", 52)
    fun opacity(c: Context) = prefs(c).getInt("opacity", 85)
    fun snap(c: Context) = prefs(c).getBoolean("snap", false)
    fun hideWhileRecording(c: Context) = prefs(c).getBoolean("hideRec", true)
    fun accentId(c: Context) = prefs(c).getString("accent", "look") ?: "look"
    fun gesture(c: Context, k: String) = prefs(c).getString("g_$k", when (k) { "tap" -> "menu"; "long" -> "hide"; else -> "none" }) ?: "none"
    fun set(c: Context, key: String, v: Any) { prefs(c).edit().apply { when (v) { is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); is String -> putString(key, v) } }.apply(); LockService.instance?.refreshButton() }
    fun chosen(c: Context): List<String> = (prefs(c).getString("actions", null)?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT).filter { byId(it) != null }
    fun setChosen(c: Context, l: List<String>) { prefs(c).edit().putString("actions", l.joinToString(",")).apply() }
    fun pos(c: Context): Pair<Int, Int> = prefs(c).getInt("x", -1) to prefs(c).getInt("y", 600)
    fun setPos(c: Context, x: Int, y: Int) { prefs(c).edit().putInt("x", x).putInt("y", y).apply() }

    /** Which shortcuts to show right now: the recording ones change with what the recorder is doing. */
    fun live(c: Context): List<AssistAct> = chosen(c).mapNotNull { id ->
        val a = byId(id) ?: return@mapNotNull null
        when (id) {
            "rec" -> if (AssistState.recording && !AssistState.clipMode) a.copy(label = "Stop", icon = WonderIcons.Stop) else if (AssistState.recording) a.copy(label = "Clips off", icon = WonderIcons.Stop) else a
            "clip" -> if (!AssistState.recording || !AssistState.clipMode) null else if (AssistState.writing) a.copy(label = "Save clip") else a.copy(label = "Start clip")
            else -> a
        }
    }

    private fun toCinema(s: AccessibilityService, action: String) { s.sendBroadcast(Intent(action).setPackage("dev.wonderdesk.cinema"), "dev.wonderdesk.cinema.CONTROL") }

    private fun openApp(s: Context, pkg: String, extra: Intent.() -> Unit = {}) {
        val la = s.getSystemService(android.content.pm.LauncherApps::class.java)
        val comp = la.getActivityList(pkg, android.os.Process.myUserHandle()).firstOrNull()?.componentName ?: return
        runCatching { s.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(comp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED).apply(extra)) }
    }

    /** Runs one shortcut from the accessibility service. */
    fun run(s: AccessibilityService, id: String) {
        fun global(a: Int) = s.performGlobalAction(a)
        when {
            id.startsWith("app:") -> openApp(s, id.removePrefix("app:"))
            id == "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            id == "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            id == "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            id == "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            id == "quick" -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            id == "lock" -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            id == "screenshot" -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            id == "power" -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            id == "split" -> global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            id == "volup" -> s.getSystemService(android.media.AudioManager::class.java).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
            id == "voldown" -> s.getSystemService(android.media.AudioManager::class.java).adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
            id == "rec" -> if (AssistState.recording) toCinema(s, RecorderBridge.STOP) else runCatching { s.startActivity(Intent().setClassName("dev.wonderdesk.cinema", "dev.wonderdesk.cinema.RecordActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            id == "clip" -> if (AssistState.writing) toCinema(s, RecorderBridge.SAVE_CLIP) else toCinema(s, RecorderBridge.CLIP)
            id == "newnote" -> openApp(s, "dev.wonderdesk.scribble") { putExtra("new", true) }
            id == "newevent" -> openApp(s, "dev.wonderdesk.chronicle") { putExtra("new", true) }
            id == "search" -> openApp(s, "dev.wonderdesk.beacon") { putExtra("search", true) }
            id == "hide" -> { AssistState.hidden = true; LockService.instance?.refreshButton() }
        }
    }
}

/** The names of Cinema's remote controls (kept in step with Cinema's RecorderControl). */
object RecorderBridge {
    const val STATE = "dev.wonderdesk.cinema.REC_STATE"
    const val CLIP = "dev.wonderdesk.cinema.CTL_CLIP"
    const val SAVE_CLIP = "dev.wonderdesk.cinema.CTL_SAVE_CLIP"
    const val STOP = "dev.wonderdesk.cinema.CTL_STOP"
}

/** Lifecycle plumbing so Compose can live in a window the accessibility service owns. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    init { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; store.clear() }
}

/**
 * The round button that floats over every app, and its menu. Drag it anywhere (it stays exactly where you leave it unless you turn
 * on snapping), tap for the menu. While the screen is being recorded it draws nothing at all, so it can never appear in a recording,
 * but it still answers a tap in the same spot.
 */
class FloatingButton(private val s: AccessibilityService) {
    private val wm = s.getSystemService(WindowManager::class.java)
    private var buttonView: ComposeView? = null
    private var panelView: ComposeView? = null
    private val owner = OverlayOwner()
    private var buttonParams: WindowManager.LayoutParams? = null
    private var receiver: BroadcastReceiver? = null
    private val dm get() = s.resources.displayMetrics

    private fun lp(w: Int, h: Int, touchable: Boolean = true) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    private fun compose(content: @Composable () -> Unit) = ComposeView(s).also { v ->
        v.setViewTreeLifecycleOwner(owner); v.setViewTreeSavedStateRegistryOwner(owner); v.setViewTreeViewModelStoreOwner(owner)
        v.setContent { WonderKitTheme { content() } }
    }

    fun show() {
        hide()
        if (AssistState.hidden) return
        listenToRecorder()
        val size = (AssistActions.size(s) * dm.density).toInt()
        val (sx, sy) = AssistActions.pos(s)
        val p = lp(size, size)
        p.x = (if (sx < 0) dm.widthPixels - size else sx).coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0)); p.y = sy.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
        buttonParams = p
        val v = compose { ButtonBody(p) }
        runCatching { wm.addView(v, p) }.onSuccess { buttonView = v }
    }

    /** Recording state comes from Cinema; the button hides itself from the recording while it runs. */
    private fun listenToRecorder() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { AssistState.recording = i.getBooleanExtra("running", false); AssistState.clipMode = i.getBooleanExtra("clip", false); AssistState.writing = i.getBooleanExtra("writing", false) }
        }
        val f = IntentFilter(RecorderBridge.STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) s.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") s.registerReceiver(receiver, f)
    }

    @Composable
    private fun ButtonBody(p: WindowManager.LayoutParams) {
        val context = LocalContext.current
        val pal = wonderPalette()
        val view = androidx.compose.ui.platform.LocalView.current
        val accent = AssistActions.accent(AssistActions.accentId(context), pal.accent)
        var pressed by remember { mutableStateOf(false) }
        var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var idle by remember { mutableStateOf(false) }
        LaunchedEffect(lastTouch) { idle = false; delay(2800); idle = true }
        val scale by animateFloatAsState(if (pressed) .88f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
        val base = AssistActions.opacity(context) / 100f
        val recordingHide = AssistState.recording && AssistActions.hideWhileRecording(context)
        // Nothing is drawn while recording, so no recording can ever contain the button; touches still reach it.
        val btnAlpha by animateFloatAsState(if (recordingHide) 0f else if (idle) base * .55f else base, tween(350), label = "alpha")
        val art = remember { context.appArt(96) }
        val squircle = RoundedCornerShape(30)
        fun doGesture(id: String) { when (id) { "menu" -> openPanel(p); "none" -> {}; else -> { if (id == "hide") view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); AssistActions.run(s, id) } } }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = btnAlpha; scaleX = scale; scaleY = scale }
            .shadow(if (recordingHide) 0.dp else 8.dp, CircleShape)
            .clip(CircleShape).background(Brush.verticalGradient(listOf(accent.copy(alpha = .95f), accent.copy(alpha = .78f))))
            .border(1.5.dp, Color.White.copy(alpha = .55f), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onPress = { pressed = true; lastTouch = System.currentTimeMillis(); tryAwaitRelease(); pressed = false; lastTouch = System.currentTimeMillis() },
                onTap = { doGesture(AssistActions.gesture(context, "tap")) }, onDoubleTap = { doGesture(AssistActions.gesture(context, "double")) }, onLongPress = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); doGesture(AssistActions.gesture(context, "long")) }) }
            .pointerInput(Unit) { detectDragGestures(onDragStart = { lastTouch = System.currentTimeMillis(); pressed = false }, onDragEnd = {
                if (AssistActions.snap(context)) { p.x = if (p.x + p.width / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - p.width; runCatching { wm.updateViewLayout(buttonView, p) } }
                AssistActions.setPos(context, p.x, p.y) }, onDrag = { change, d ->
                change.consume()
                p.x = (p.x + d.x.roundToInt()).coerceIn(0, (dm.widthPixels - p.width).coerceAtLeast(0)); p.y = (p.y + d.y.roundToInt()).coerceIn(0, (dm.heightPixels - p.height).coerceAtLeast(0))
                runCatching { wm.updateViewLayout(buttonView, p) } }) },
            contentAlignment = Alignment.Center) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(.62f))
        }
    }

    private fun openPanel(bp: WindowManager.LayoutParams) {
        if (panelView != null) return
        val full = lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        full.flags = full.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val v = compose { PanelBody(bp) }
        runCatching { wm.addView(v, full) }.onSuccess { panelView = v }
    }

    private fun closePanelNow() { panelView?.let { runCatching { wm.removeView(it) } }; panelView = null }

    @Composable
    private fun PanelBody(bp: WindowManager.LayoutParams) {
        val context = LocalContext.current
        val pal = wonderPalette()
        val accent = AssistActions.accent(AssistActions.accentId(context), pal.accent)
        var open by remember { mutableStateOf(false) }
        var closing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { open = true }
        LaunchedEffect(closing) { if (closing) { open = false; delay(220); closePanelNow() } }
        val acts = remember(AssistState.recording, AssistState.clipMode, AssistState.writing) { AssistActions.live(context) }
        val cols = if (acts.size <= 3) acts.size.coerceAtLeast(1) else if (acts.size <= 8) 4 else 4
        val cell = 84.dp
        val panelW = cell * cols + 20.dp
        val density = dm.density
        val rows = (acts.size + cols - 1) / cols
        val panelH = 46.dp + (cell + 4.dp) * rows + 14.dp
        val panelWpx = panelW.value * density; val panelHpx = panelH.value * density
        val cx = bp.x + bp.width / 2f
        val px = (cx - panelWpx / 2f).coerceIn(8 * density, dm.widthPixels - panelWpx - 8 * density)
        val below = bp.y + bp.height + 10 * density + panelHpx < dm.heightPixels - 40 * density
        val py = if (below) bp.y + bp.height + 10 * density else (bp.y - panelHpx - 10 * density).coerceAtLeast(24 * density)
        // The menu grows out of the button and shrinks back into it.
        val origin = TransformOrigin(((cx - px) / panelWpx).coerceIn(0f, 1f), if (below) 0f else 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (open) .22f else 0f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closing = true }) {
            AnimatedVisibility(open, Modifier.offset { IntOffset(px.roundToInt(), py.roundToInt()) }, enter = scaleIn(spring(dampingRatio = .72f, stiffness = 520f), initialScale = .3f, transformOrigin = origin) + fadeIn(tween(120)),
                exit = scaleOut(tween(160), targetScale = .3f, transformOrigin = origin) + fadeOut(tween(140))) {
                Column(Modifier.width(panelW).shadow(14.dp, RoundedCornerShape(pal.panelRadius + 10.dp)).clip(RoundedCornerShape(pal.panelRadius + 10.dp)).background(pal.window).border(1.dp, pal.panelBorder, RoundedCornerShape(pal.panelRadius + 10.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Wonderdesk", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = pal.text)
                        if (AssistState.recording) Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE5483C))); Spacer(Modifier.width(5.dp)); Text(if (AssistState.writing) "Recording" else if (AssistState.clipMode) "Clips on" else "Recording", style = MaterialTheme.typography.labelSmall, color = pal.secondaryText) }
                    }
                    acts.chunked(cols).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEachIndexed { i, a ->
                                var shown by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) { delay(18L * i); shown = true }
                                val k by animateFloatAsState(if (shown) 1f else .6f, spring(dampingRatio = .6f, stiffness = 500f), label = "cell")
                                Column(Modifier.width(cell).height(cell + 4.dp).graphicsLayer { scaleX = k; scaleY = k; alpha = k.coerceIn(0f, 1f) }.clip(RoundedCornerShape(pal.panelRadius + 2.dp))
                                    .clickable { closing = true; val id = a.id; (buttonView ?: panelView)?.postDelayed({ AssistActions.run(s, if (id == "rec" || id == "clip") id else id) }, 260) }.padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    val danger = a.id == "rec" && AssistState.recording
                                    Box(Modifier.size(42.dp).clip(CircleShape).background((if (danger) Color(0xFFE5483C) else accent).copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                        val art = a.pkg?.let { remember(it) { context.suiteArt(it, 96) } }
                                        if (art != null) Image(art, null, Modifier.size(30.dp)) else if (a.icon != null) Icon(a.icon, null, Modifier.size(23.dp), tint = if (danger) Color(0xFFE5483C) else accent)
                                    }
                                    Text(a.label, Modifier.padding(top = 3.dp), style = TextStyle11, color = pal.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun hide() {
        closePanelNow()
        buttonView?.let { runCatching { wm.removeView(it) } }; buttonView = null
    }

    fun destroy() { hide(); receiver?.let { runCatching { s.unregisterReceiver(it) } }; receiver = null; owner.destroy() }

    private val TextStyle11 = androidx.compose.ui.text.TextStyle(fontSize = 10.5.sp, lineHeight = 12.sp)
}
