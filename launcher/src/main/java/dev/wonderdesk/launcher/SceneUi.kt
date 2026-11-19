package dev.wonderdesk.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.withContext

/** A wallpaper picture with the home screen's icons drawn on it, so you can see how icons will sit on it. */
fun homePreview(base: Bitmap): Bitmap {
    val b = base.copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(b)
    val w = b.width.toFloat(); val h = b.height.toFloat()
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0x66101828; c.drawRect(0f, 0f, w, h * .035f, p)
    p.color = 0xEEFFFFFF.toInt()
    val colors = intArrayOf(0xFF3B82F6.toInt(), 0xFFEF4444.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(), 0xFF8B5CF6.toInt(), 0xFF06B6D4.toInt())
    for (r in 0 until 4) for (col in 0 until 4) { p.color = colors[(r * 4 + col) % colors.size]; val s = w * .15f; val x = w * (.08f + col * .225f); val y = h * (.08f + r * .12f); c.drawRoundRect(RectF(x, y, x + s, y + s), s * .26f, s * .26f, p) }
    p.color = 0x88FFFFFF.toInt(); c.drawRoundRect(RectF(w * .06f, h * .9f, w * .94f, h * .975f), w * .05f, w * .05f, p)
    for (i in 0 until 4) { p.color = colors[(i + 2) % colors.size]; val s = w * .13f; val x = w * (.1f + i * .21f); c.drawRoundRect(RectF(x, h * .905f, x + s, h * .905f + s), s * .26f, s * .26f, p) }
    return b
}

fun lockPreview(c: Context, base: Bitmap, look: LockLook): Bitmap {
    val b = base.copy(Bitmap.Config.ARGB_8888, true)
    LockDraw.draw(Canvas(b), c, b.width, b.height, look)
    return b
}

@Composable
fun PhoneThumb(bmp: Bitmap?, label: String, modifier: Modifier = Modifier) {
    val p = wonderPalette()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(62.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(9.dp)).background(p.window).border(1.dp, p.panelBorder, RoundedCornerShape(9.dp))) {
            bmp?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
    }
}

/** The lock screen look: layout and style chips and a few switches, with a preview that follows. */
@Composable
fun LockPanel(source: Source, tick: Int, onChange: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var look by remember { mutableStateOf(LockLook.load(context)) }
    fun update(n: LockLook) { look = n; LockLook.save(context, n); onChange() }
    val base by produceState<Bitmap?>(null, source.signature, tick) { value = withContext(Dispatchers.Default) { runCatching { WpConfig.staticBitmap(context, source, 360, 640) }.getOrNull() } }
    val preview = remember(base, look) { base?.let { lockPreview(context, it, look) } }
    Panel(title = "Lock screen", footer = "Android keeps its own small clock at the top of the lock screen, so this layout sits below it. Shows on the lock screen when Wonderdesk's animated wallpaper is chosen for it (pick “Home screen and lock screen” when Android asks).") {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(104.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).background(p.window).border(1.dp, p.panelBorder, RoundedCornerShape(12.dp))) {
                preview?.let { Image(it.asImageBitmap(), "Lock screen preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Layout", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { LockLook.LAYOUTS.forEach { (k, l) -> Chip(l, look.layout == k, { update(look.copy(layout = k)) }) } }
                Text("Clock style", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { LockLook.STYLES.forEach { (k, l) -> Chip(l, look.style == k, { update(look.copy(style = k)) }) } }
            }
        }
        PanelDivider()
        SwitchRow("Date", look.date, { update(look.copy(date = it)) }, icon = WonderIcons.Event, tint = Color(0xFF1E7FE8))
        PanelDivider()
        SwitchRow("Battery level", look.battery, { update(look.copy(battery = it)) }, icon = WonderIcons.Bolt, tint = Color(0xFF2E9E44))
        PanelDivider()
        SwitchRow("Next alarm", look.alarm, { update(look.copy(alarm = it)) }, icon = WonderIcons.Notifications, tint = Color(0xFFE39A12))
    }
}

/** Build your own dynamic scene: pick the world, how time passes, random events, and rules that change things. */
@Composable
fun CustomSceneEditor(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cfg by remember { mutableStateOf(SceneConfig.custom(context)) }
    var addingRule by remember { mutableStateOf(false) }
    val p = wonderPalette()
    val preview by produceState<Bitmap?>(null, cfg) { value = withContext(Dispatchers.Default) { runCatching { SceneRunner.still(cfg, 180, 320) }.getOrNull() } }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Custom scene") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneThumb(preview, "Preview")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("World", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("aqua", "bliss", "super").forEach { b -> Chip(SceneConfig.name(b), cfg.base == b, { cfg = cfg.copy(base = b) }) } }
                    Text("Colour shift ${cfg.hueShift}°", style = MaterialTheme.typography.labelLarge)
                    Slider(cfg.hueShift.toFloat(), { cfg = cfg.copy(hueShift = it.toInt()) }, valueRange = 0f..350f)
                }
            }
            Text("Time of day", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Follow the clock", cfg.fixedHour < 0 && cfg.daySpeed == 1, { cfg = cfg.copy(fixedHour = -1f, daySpeed = 1) })
                Chip("Fast day", cfg.fixedHour < 0 && cfg.daySpeed == 60, { cfg = cfg.copy(fixedHour = -1f, daySpeed = 60) })
                Chip("Very fast", cfg.fixedHour < 0 && cfg.daySpeed == 360, { cfg = cfg.copy(fixedHour = -1f, daySpeed = 360) })
                Chip("Fixed hour", cfg.fixedHour >= 0, { cfg = cfg.copy(fixedHour = if (cfg.fixedHour >= 0) cfg.fixedHour else 18.3f) })
            }
            if (cfg.fixedHour >= 0) { Text("Always ${"%d:%02d".format(cfg.fixedHour.toInt(), ((cfg.fixedHour % 1) * 60).toInt())}", style = MaterialTheme.typography.labelMedium); Slider(cfg.fixedHour, { cfg = cfg.copy(fixedHour = it) }, valueRange = 0f..23.9f) }
            SwitchRow("Random events", cfg.events, { cfg = cfg.copy(events = it) }, subtitle = "Shooting stars, fish, rainbows, comets", icon = WonderIcons.Bolt, tint = Color(0xFFF2A516))
            if (cfg.events) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1 to "Rare", 2 to "Now and then", 3 to "Often").forEach { (v, l) -> Chip(l, cfg.eventRate == v, { cfg = cfg.copy(eventRate = v) }) } }
            SwitchRow("Turn red when the battery is low", cfg.batteryTint, { cfg = cfg.copy(batteryTint = it) }, icon = WonderIcons.Warning, tint = Color(0xFFE5483C))
            SwitchRow("Glow while charging", cfg.chargingGlow, { cfg = cfg.copy(chargingGlow = it) }, icon = WonderIcons.Bolt, tint = Color(0xFF2E9E44))
            Text("Rules", style = MaterialTheme.typography.labelLarge)
            if (cfg.rules.isEmpty()) Text("A rule changes the world or the colours when something is true, like “between 22:00 and 6:00 use Super”.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            cfg.rules.forEachIndexed { i, r -> ListRow(r.label(), icon = WonderIcons.Tune, tint = Color(0xFF8E44AD), trailing = { TextButton(onClick = { cfg = cfg.copy(rules = cfg.rules - r) }) { Text("Remove") } }) }
            WButton("Add a rule", { addingRule = true }, icon = WonderIcons.Add)
        }
    }, confirmButton = { TextButton(onClick = { SceneConfig.saveCustom(context, cfg); onDismiss() }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (addingRule) RuleDialog(onDismiss = { addingRule = false }) { cfg = cfg.copy(rules = cfg.rules + it); addingRule = false }
}

@Composable
private fun RuleDialog(onDismiss: () -> Unit, onAdd: (Rule) -> Unit) {
    var kind by remember { mutableStateOf("hours") }
    var a by remember { mutableIntStateOf(22) }
    var b by remember { mutableIntStateOf(6) }
    var base by remember { mutableStateOf("") }
    var hue by remember { mutableIntStateOf(0) }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("New rule") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("When", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Time of day", kind == "hours", { kind = "hours"; a = 22; b = 6 }); Chip("Charging", kind == "charging", { kind = "charging" }); Chip("Battery low", kind == "battery", { kind = "battery"; a = 20 })
            }
            if (kind == "hours") { Text("From $a:00", style = MaterialTheme.typography.labelMedium); Slider(a.toFloat(), { a = it.toInt() }, valueRange = 0f..23f, steps = 22); Text("Until $b:00", style = MaterialTheme.typography.labelMedium); Slider(b.toFloat(), { b = it.toInt() }, valueRange = 0f..23f, steps = 22) }
            if (kind == "battery") { Text("Below $a%", style = MaterialTheme.typography.labelMedium); Slider(a.toFloat(), { a = it.toInt() }, valueRange = 5f..60f) }
            Text("Then use", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Same world", base.isEmpty(), { base = "" }); listOf("aqua", "bliss", "super").forEach { x -> Chip(SceneConfig.name(x), base == x, { base = x }) }
            }
            Text("Shift the colours by $hue°", style = MaterialTheme.typography.labelMedium); Slider(hue.toFloat(), { hue = it.toInt() }, valueRange = 0f..350f)
        }
    }, confirmButton = { TextButton(onClick = { onAdd(Rule(kind, a, b, base, hue)) }) { Text("Add") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
