package dev.wonderdesk.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen wallpaper preview: pinch and drag to frame the picture, see it behind your real home-screen layout
 * (icons, Dock, menu bar), dim it, then set it for the home screen, lock screen or both, or keep it as the day or
 * night wallpaper.
 */
@Composable
fun WallpaperEditor(source: Bitmap, onClose: () -> Unit, onDone: (String) -> Unit) = WonderKitTheme {
    val context = LocalContext.current
    val c = rememberPlayerChrome()
    val scope = rememberCoroutineScope()
    var box by remember { mutableStateOf(Size(1f, 1f)) }
    var scale by remember { mutableFloatStateOf(1f) }   // on top of "cover the screen"
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dim by remember { mutableFloatStateOf(0f) }
    var icons by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var where by remember { mutableStateOf(false) }
    val img = remember(source) { source.asImageBitmap() }
    BackHandler { onClose() }
    // The picture first covers the screen; scale zooms in from there and the edges never come into view.
    val cover = maxOf(box.width / source.width, box.height / source.height)
    fun clamp(o: Offset, s: Float): Offset {
        val w = source.width * cover * s; val h = source.height * cover * s
        val mx = ((w - box.width) / 2).coerceAtLeast(0f); val my = ((h - box.height) / 2).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    /** The part of [source] the screen shows, with the dimming applied, at screen size. */
    fun render(): Bitmap {
        val s = cover * scale
        val w = box.width / s; val h = box.height / s
        val cx = source.width / 2f - offset.x / s; val cy = source.height / 2f - offset.y / s
        val r = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val dm = context.resources.displayMetrics
        val out = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { if (dim > 0f) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { val k = 1f - dim; setScale(k, k, k, 1f) }) }
        Canvas(out).drawBitmap(source, android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()), android.graphics.Rect(0, 0, out.width, out.height), paint)
        return out
    }
    fun apply(flag: Int?, slot: String?) {
        busy = true; where = false
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching {
                val b = render()
                if (slot != null) { DayNight.save(context, slot, b); DayNight.applyNow(context); "Saved as your $slot wallpaper" }
                else { WallpaperManager.getInstance(context).setBitmap(b, null, true, flag!!); "Wallpaper set" }
            } }
            busy = false
            r.onSuccess { onDone(it) }.onFailure { onDone(it.message ?: "Couldn't set the wallpaper") }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { box = Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(source) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset = clamp(offset + pan, scale) } }) {
        Image(img, null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Crop)
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        // Your home screen on top, so you can see how icons sit on the picture.
        if (icons) ComposeCanvas(Modifier.fillMaxSize()) {
            val cols = Home.settings.cols; val rows = Home.settings.rows
            drawRect(Color.White.copy(alpha = .55f), Offset.Zero, Size(size.width, 32.dp.toPx() + 24.dp.toPx()))
            val top = 80.dp.toPx(); val bottom = size.height - 140.dp.toPx()
            val cw = size.width / cols; val ch = (bottom - top) / rows; val icon = minOf(cw, ch) * .55f
            for (r in 0 until rows) for (col in 0 until cols) drawRoundRect(Color.White.copy(alpha = .45f), Offset(col * cw + (cw - icon) / 2, top + r * ch + (ch - icon) / 2), Size(icon, icon), CornerRadius(icon * .24f))
            drawRoundRect(Color.White.copy(alpha = .35f), Offset(16.dp.toPx(), size.height - 110.dp.toPx()), Size(size.width - 32.dp.toPx(), 76.dp.toPx()), CornerRadius(18.dp.toPx()))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().mediaGlass(c, c.radius + 6.dp, hud = true).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pinch and drag to frame it", color = c.ink, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dim", color = c.ink, modifier = Modifier.width(56.dp))
                Slider(dim, { dim = it }, Modifier.weight(1f), valueRange = 0f..0.7f, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show my icons", color = c.ink, modifier = Modifier.weight(1f)); Switch(icons, { icons = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WButton("Cancel", onClose, Modifier.weight(1f), kind = ButtonKind.Plain)
                WButton(if (busy) "Setting…" else "Set wallpaper", { if (!busy) where = true }, Modifier.weight(1.4f), icon = WonderIcons.Wallpaper, kind = ButtonKind.Primary)
            }
        }
        Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).mediaGlass(c, 24.dp)) { HudButton(WonderIcons.ArrowBack, "Back", c, 48.dp) { onClose() } }
    }
    if (where) WonderDialog(onDismissRequest = { where = false }, title = { Text("Use it for") }, text = {
        Panel {
            ListRow("Home screen", icon = WonderIcons.Home, onClick = { apply(WallpaperManager.FLAG_SYSTEM, null) }); PanelDivider()
            ListRow("Lock screen", icon = WonderIcons.Lock, onClick = { apply(WallpaperManager.FLAG_LOCK, null) }); PanelDivider()
            ListRow("Both", icon = WonderIcons.Layers, onClick = { apply(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK, null) }); PanelDivider()
            ListRow("Daytime wallpaper", subtitle = "Switches in at ${DayNight.dayHour(context)}:00", icon = WonderIcons.WbSunny, tint = Color(0xFFE39A12), onClick = { apply(null, "day") }); PanelDivider()
            ListRow("Night wallpaper", subtitle = "Switches in at ${DayNight.nightHour(context)}:00", icon = WonderIcons.NightsStay, tint = Color(0xFF5B4BC4), onClick = { apply(null, "night") })
        }
    }, confirmButton = { TextButton(onClick = { where = false }) { Text("Cancel") } })
}

/** Day and night wallpapers: two saved pictures that swap at the hours you choose. */
object DayNight {
    private fun prefs(c: Context) = c.getSharedPreferences("daynight", Context.MODE_PRIVATE)
    private fun file(c: Context, slot: String) = File(c.filesDir, "wallpaper-$slot.png")
    fun dayHour(c: Context) = prefs(c).getInt("day", 7)
    fun nightHour(c: Context) = prefs(c).getInt("night", 19)
    fun has(c: Context, slot: String) = file(c, slot).isFile
    fun enabled(c: Context) = has(c, "day") || has(c, "night")
    fun setHours(c: Context, day: Int, night: Int) { prefs(c).edit().putInt("day", day).putInt("night", night).apply(); applyNow(c) }
    fun save(c: Context, slot: String, b: Bitmap) { file(c, slot).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    fun clear(c: Context) { file(c, "day").delete(); file(c, "night").delete(); c.getSystemService(AlarmManager::class.java).cancel(pending(c)) }

    private fun isNight(c: Context): Boolean {
        val h = java.time.LocalTime.now().hour; val d = dayHour(c); val n = nightHour(c)
        return if (d < n) h !in d until n else h in n until d
    }

    /** Puts up the right picture for the time now and books the next switch. */
    fun applyNow(c: Context) {
        val slot = if (isNight(c)) "night" else "day"
        val f = file(c, slot).takeIf { it.isFile } ?: file(c, if (slot == "night") "day" else "night").takeIf { it.isFile }
        if (f != null && prefs(c).getString("showing", "") != f.name + f.lastModified()) runCatching {
            android.graphics.BitmapFactory.decodeFile(f.path)?.let { WallpaperManager.getInstance(c).setBitmap(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
            prefs(c).edit().putString("showing", f.name + f.lastModified()).apply()
        }
        if (!enabled(c)) return
        val now = java.time.LocalDateTime.now()
        val next = listOf(dayHour(c), nightHour(c)).map { h -> now.toLocalDate().atTime(h, 0).let { if (it.isAfter(now)) it else it.plusDays(1) } }.minOrNull()!!
        c.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), pending(c))
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 0x0DA1, Intent(c, DayNightReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class DayNightReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { val r = goAsync(); Thread { runCatching { DayNight.applyNow(c) }; r.finish() }.start() }
}
