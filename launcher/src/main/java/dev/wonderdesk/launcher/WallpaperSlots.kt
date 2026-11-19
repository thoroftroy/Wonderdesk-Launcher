package dev.wonderdesk.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wallpaper settings: one wallpaper, or a separate day and night one, each of which can be a Wonderdesk style
 * (including the animated Aqua bubbles), a photo, a slideshow or a video. The hour a look takes over sits right
 * under its own picture.
 */
@Composable
fun WallpaperSlots(toast: (String) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var dayNight by remember { mutableStateOf(WpConfig.dayNight(context)) }
    var dayH by remember { mutableIntStateOf(DayNight.dayHour(context)) }
    var nightH by remember { mutableIntStateOf(DayNight.nightHour(context)) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var pickSlot by remember { mutableStateOf("all") }
    var working by remember { mutableStateOf<String?>(null) }
    var editingScene by remember { mutableStateOf(false) }
    val resumed = rememberIsResumed()
    val live = remember(resumed, tick) { WpConfig.liveActive(context) }
    val took = remember(resumed, tick) { DayNight.tookOver(context) }

    fun saved(slot: String, s: Source) { WpConfig.setSource(context, slot, s); DayNight.takeOver(context); tick++ }

    fun takePhotos(slot: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            working = "Preparing ${uris.size} photo${if (uris.size == 1) "" else "s"}"
            val files = withContext(Dispatchers.IO) { copyPhotos(context, slot, uris) }
            working = null
            if (files.isEmpty()) toast("Couldn't read those pictures") else saved(slot, Source("photos", files = files, seconds = WpConfig.source(context, slot).seconds.takeIf { files.size > 1 } ?: 30))
        }
    }
    val onePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) takePhotos(pickSlot, listOf(u)) }
    val manyPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { us -> takePhotos(pickSlot, us) }
    val oneVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) scope.launch {
            working = "Copying your video"
            val f = withContext(Dispatchers.IO) { runCatching { copyVideo(context, pickSlot, u) }.getOrNull() }
            working = null
            if (f == null) toast("Couldn't read that video") else saved(pickSlot, Source("video", files = listOf(f)))
        }
    }

    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    LaunchedEffect(Unit) { Wallpapers.STYLES.forEach { st -> thumbs[st.id] = withContext(Dispatchers.Default) { Wallpapers.thumbnail(st.id) } } }

    @Composable fun card(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, slot: String, extra: @Composable () -> Unit) {
        val s = remember(tick, slot, dayNight) { WpConfig.source(context, slot) }
        val thumb by produceState<Bitmap?>(null, s.signature, tick) { value = withContext(Dispatchers.Default) { runCatching { WpConfig.staticBitmap(context, s, 180, 320) }.getOrNull() } }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val lookNow = remember(tick) { LockLook.load(context) }
                val home = remember(thumb) { thumb?.let { homePreview(it) } }
                val lock = remember(thumb, lookNow) { thumb?.let { lockPreview(context, it, lookNow) } }
                PhoneThumb(home, "Home"); PhoneThumb(lock, "Lock")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text(title, style = MaterialTheme.typography.titleMedium) }
                    Text(s.describe(), style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    WButton("Change", { choosing = slot }, icon = WonderIcons.Wallpaper)
                }
            }
            if (s.kind == "photos" && s.files.size > 1) {
                Text("Next photo every", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10 to "10 s", 30 to "30 s", 60 to "1 min", 300 to "5 min", 900 to "15 min", 3600 to "1 hour").forEach { (v, l) -> Chip(l, s.seconds == v, { saved(slot, s.copy(seconds = v)) }) }
                }
            }
            extra()
        }
    }

    QuickSetup(tick, { tick++ }, toast) { editingScene = true }
    Panel(title = "Wallpaper", footer = if (dayNight) "Each look can be a Wonderdesk style, a photo, a slideshow or a video. They swap by themselves at the hours below." else "One wallpaper all day. Turn on day and night to have a different one after dark.") {
        if (!took) {
            ListRow("Use Wonderdesk's Aqua wallpaper", subtitle = "Water with floating bubbles by day, deep water at night", icon = WonderIcons.Wallpaper, tint = Color(0xFF1E7FE8), chevron = true,
                onClick = { Thread { DayNight.takeOver(context, force = true) }.start(); tick++; toast("Wallpaper set") })
            PanelDivider()
        }
        SwitchRow("Different wallpapers for day and night", dayNight, { dayNight = it; WpConfig.setDayNight(context, it); if (it) DayNight.takeOver(context); tick++ },
            subtitle = if (dayNight) "Day from $dayH:00, night from $nightH:00" else "Same wallpaper all day", icon = WonderIcons.NightsStay, tint = Color(0xFF5B4BC4))
        PanelDivider()
        if (dayNight) {
            card("Daytime", WonderIcons.WbSunny, Color(0xFFE39A12), "day") {
                Text("Day starts at $dayH:00", style = MaterialTheme.typography.labelLarge)
                Slider(dayH.toFloat(), { dayH = it.toInt() }, valueRange = 4f..11f, steps = 6, onValueChangeFinished = { DayNight.setHours(context, dayH, nightH) })
            }
            PanelDivider()
            card("Night", WonderIcons.NightsStay, Color(0xFF5B4BC4), "night") {
                Text("Night starts at $nightH:00", style = MaterialTheme.typography.labelLarge)
                Slider(nightH.toFloat(), { nightH = it.toInt() }, valueRange = 16f..23f, steps = 6, onValueChangeFinished = { DayNight.setHours(context, dayH, nightH) })
            }
        } else card("Wallpaper", WonderIcons.Wallpaper, Color(0xFF1E7FE8), "all") {}
        working?.let { PanelDivider(); Column(Modifier.padding(14.dp)) { LoadingStatus(it, compact = true) } }
    }

    // Animated looks (bubbles, slideshow, video) need Wonderdesk's live wallpaper to be the one Android shows.
    val needsLive = remember(tick, dayNight) { WpConfig.needsLive(context) }
    if (needsLive && !live) Panel(footer = "Android asks you to confirm once. Wonderdesk then keeps the wallpaper moving and switches between day and night looks by itself. It stops drawing whenever the home screen is not showing.") {
        ListRow("Turn on the animated wallpaper", subtitle = "Needed for the bubbles, slideshows and videos", icon = WonderIcons.Bolt, tint = Color(0xFFF2A516), chevron = true,
            onClick = { runCatching { context.startActivity(WpConfig.switchToLive(context)) }.onFailure { toast("This phone can't use live wallpapers") } })
    } else if (needsLive && live) Panel { ListRow("Animated wallpaper is on", subtitle = "Moving looks are being drawn by Wonderdesk", icon = WonderIcons.CheckCircle, tint = Color(0xFF2E9E44)) }

    if (editingScene) CustomSceneEditor { editingScene = false; tick++ }
    LockPanel(WpConfig.lockSource(context), tick) { tick++ }
    choosing?.let { slot ->
        val name = when (slot) { "day" -> "daytime"; "night" -> "night"; else -> "your" }
        WonderDialog(onDismissRequest = { choosing = null }, title = { Text("Choose $name wallpaper") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Panel {
                    ListRow("A photo", subtitle = "From Memories or any gallery", icon = WonderIcons.PhotoLibrary, tint = Color(0xFF2E9E44), chevron = true,
                        onClick = { pickSlot = slot; choosing = null; onePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    PanelDivider()
                    ListRow("A slideshow of photos", subtitle = "Pick several; they fade from one to the next", icon = WonderIcons.Layers, tint = Color(0xFF1E7FE8), chevron = true,
                        onClick = { pickSlot = slot; choosing = null; manyPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    PanelDivider()
                    ListRow("A video", subtitle = "Plays silently and loops behind your icons", icon = WonderIcons.Movie, tint = Color(0xFF8E44AD), chevron = true,
                        onClick = { pickSlot = slot; choosing = null; oneVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) })
                }
                SectionLabel("Dynamic scenes")
                Panel {
                    listOf(Triple("For my theme", "Aqua, Bliss or Super to match your Wonderdesk look", "theme"), Triple("Aqua", "Water, light and bubbles you can pop", "aqua"), Triple("Bliss", "A grass field, a moving sun and moon, clouds and stars", "bliss"),
                        Triple("Super", "Space, rainbows and glowing rings", "super")).forEachIndexed { i, (n, d, id) ->
                        if (i > 0) PanelDivider()
                        ListRow(n, subtitle = d, icon = WonderIcons.Wallpaper, tint = Color(0xFF1E7FE8), onClick = { saved(slot, Source("scene", id)); choosing = null })
                    }
                    PanelDivider()
                    ListRow("My own scene…", subtitle = "Choose the world, time, events and rules", icon = WonderIcons.Tune, tint = Color(0xFF8E44AD), chevron = true, onClick = { choosing = null; saved(slot, Source("scene", "custom")); editingScene = true })
                }
                SectionLabel("Wonderdesk styles")
                Wallpapers.STYLES.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { st ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { saved(slot, Source("style", st.id)); choosing = null }.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)).background(p.window).border(1.dp, p.panelBorder, RoundedCornerShape(8.dp))) {
                                    thumbs[st.id]?.let { Image(it.asImageBitmap(), st.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                }
                                Text(st.name, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }, confirmButton = { androidx.compose.material3.TextButton(onClick = { choosing = null }) { Text("Cancel") } })
    }
}

/** Copies chosen photos into the app, sized for the screen, so they keep working after the picker lets go of them. */
private fun copyPhotos(c: Context, slot: String, uris: List<Uri>): List<String> {
    val dir = WpConfig.dir(c)
    dir.listFiles { f -> f.name.startsWith("$slot-") && f.extension == "jpg" }?.forEach { it.delete() }
    val dm = c.resources.displayMetrics
    val longSide = maxOf(dm.widthPixels, dm.heightPixels)
    val out = mutableListOf<String>()
    uris.forEachIndexed { i, u ->
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1; while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= longSide) sample *= 2
            var b = c.contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }!!
            val deg = runCatching { c.contentResolver.openInputStream(u)!!.use { when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 } } }.getOrDefault(0)
            if (deg != 0) b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
            val f = File(dir, "$slot-$i.jpg")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out += f.absolutePath
        }
    }
    return out
}

private fun copyVideo(c: Context, slot: String, u: Uri): String {
    val dir = WpConfig.dir(c)
    dir.listFiles { f -> f.name.startsWith("$slot.") && f.extension == "mp4" }?.forEach { it.delete() }
    val f = File(dir, "$slot.mp4")
    c.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
    return f.absolutePath
}


/** One tap to put a dynamic scene on the home screen and on the lock screen, each on its own. */
@Composable
private fun QuickSetup(tick: Int, changed: () -> Unit, toast: (String) -> Unit, editCustom: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val presets = listOf("theme" to "My theme", "aqua" to "Aqua", "bliss" to "Bliss", "super" to "Super", "custom" to "My own")
    val dn = remember(tick) { WpConfig.dayNight(context) }
    val homeSrc = remember(tick) { WpConfig.source(context, "all") }
    val homeSel = if (dn) "daynight" else if (homeSrc.kind == "scene") homeSrc.id else "other"
    val same = remember(tick) { WpConfig.lockSame(context) }
    val lockSrc = remember(tick) { WpConfig.source(context, "lock") }
    val lockSel = if (same) "same" else if (lockSrc.kind == "scene") lockSrc.id else "other"
    val live = remember(tick) { WpConfig.liveActive(context) }
    fun askLive() { if (!WpConfig.liveActive(context)) runCatching { context.startActivity(WpConfig.switchToLive(context)) }.onFailure { toast("This phone can't use live wallpapers") } }
    Panel(title = "Quick setup", footer = "Dynamic scenes move with the time of day. “My theme” follows your Wonderdesk look: Aqua for Aqua, Bliss for Bliss and Dark Bliss, Super for Super. When Android asks, choose “Home screen and lock screen” so the lock screen can use it too.") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Home screen", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (id, label) -> Chip(label, homeSel == id, {
                    WpConfig.setDayNight(context, false); WpConfig.setSource(context, "all", Source("scene", id)); DayNight.takeOver(context); askLive(); changed(); if (id == "custom") editCustom()
                }) }
                Chip("Day and night", homeSel == "daynight", { WpConfig.setDayNight(context, true); DayNight.takeOver(context); changed() })
            }
            Text("Lock screen", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Same as home", lockSel == "same", { WpConfig.setLockSame(context, true); askLive(); changed() })
                presets.forEach { (id, label) -> Chip(label, lockSel == id, {
                    WpConfig.setLockSame(context, false); WpConfig.setSource(context, "lock", Source("scene", id)); DayNight.takeOver(context); askLive(); changed(); if (id == "custom") editCustom()
                }) }
            }
            Text(if (live) "Animated wallpaper is on." else "Turn the animated wallpaper on when Android asks, then pick “Home screen and lock screen”.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
        }
    }
}
