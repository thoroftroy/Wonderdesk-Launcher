package dev.wonderdesk.launcher

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/** Floating button settings: what it looks like, how it behaves, and every shortcut it can hold. */
@Composable
fun AssistSettingsPanel(needAccess: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var on by remember { mutableStateOf(AssistActions.enabled(context)) }
    var size by remember { mutableIntStateOf(AssistActions.size(context)) }
    var opacity by remember { mutableIntStateOf(AssistActions.opacity(context)) }
    var snap by remember { mutableStateOf(AssistActions.snap(context)) }
    var hideRec by remember { mutableStateOf(AssistActions.hideWhileRecording(context)) }
    var accent by remember { mutableStateOf(AssistActions.accentId(context)) }
    var chosen by remember { mutableStateOf(AssistActions.chosen(context)) }
    val hidden = AssistState.hidden
    Panel(title = "Floating button", footer = "A button that floats over every app, like Assistive Touch. Drag it anywhere, tap it for shortcuts. It uses the accessibility switch, and only presses buttons you choose; it reads nothing on screen. While the screen is being recorded it draws nothing, so it never appears in a recording.") {
        SwitchRow("Floating button", on, { v -> on = v; AssistActions.setEnabled(context, v); if (v && LockService.instance == null) needAccess() }, icon = WonderIcons.TouchApp, tint = Color(0xFF1E7FE8), help = "A small button that floats over every app. Tap it for shortcuts like screenshots, recording and notes. It needs the Wonderdesk accessibility switch.")
        if (on) {
            PanelDivider()
            SwitchRow("Visible right now", !hidden, { v -> AssistState.hidden = !v; LockService.instance?.refreshButton() }, subtitle = "Bring it back any time from the Floating button tile in Quick Settings, or the Wonderdesk icon's long-press shortcut", icon = WonderIcons.Visibility, tint = Color(0xFF16A085))
            PanelDivider()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Menu colour", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistActions.ACCENTS.forEach { (id, label) ->
                        val col = AssistActions.accent(id, p.accent)
                        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (accent == id) col.copy(alpha = .18f) else Color.Transparent).clickable { accent = id; AssistActions.set(context, "accent", id) }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (accent == id) androidx.compose.ui.text.font.FontWeight.Bold else null)
                        }
                    }
                }
                Text("Size: $size dp", style = MaterialTheme.typography.titleSmall)
                Slider(size.toFloat(), { size = it.toInt() }, valueRange = 36f..84f, onValueChangeFinished = { AssistActions.set(context, "size", size) })
                Text("Opacity: $opacity% (it fades a little when you are not using it)", style = MaterialTheme.typography.titleSmall)
                Slider(opacity.toFloat(), { opacity = it.toInt() }, valueRange = 30f..100f, onValueChangeFinished = { AssistActions.set(context, "opacity", opacity) })
            }
            PanelDivider()
            SwitchRow("Snap to the screen edge", snap, { snap = it; AssistActions.set(context, "snap", it) }, subtitle = "Off: it stays exactly where you drop it", icon = WonderIcons.Gesture, tint = Color(0xFF8E44AD))
            PanelDivider()
            var noteTarget by remember { mutableStateOf(AssistActions.noteTarget(context)) }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("New note makes", style = MaterialTheme.typography.titleSmall)
                Text("A Ripple note is saved in a Ripple notepad and kept in sync with your computer, so it is waiting there when you get to it. You can also add Ripple note, Notepads, Files and Wake PC as their own shortcuts below.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("A Scribble note", noteTarget == "scribble", { noteTarget = "scribble"; AssistActions.set(context, "noteTarget", "scribble") })
                    Chip("A Ripple note", noteTarget == "ripple", { noteTarget = "ripple"; AssistActions.set(context, "noteTarget", "ripple") })
                }
            }
            PanelDivider()
            var recClip by remember { mutableStateOf(AssistActions.recClip(context)) }
            SwitchRow("Record starts in clip mode", recClip, { recClip = it; AssistActions.set(context, "recClip", it) }, subtitle = "Tapping Record starts Cinema straight away with no prompts, silently keeping the last few seconds. Start a clip, save it or stop from the button's menu. Off: a normal full recording", icon = WonderIcons.Videocam, tint = Color(0xFFE5483C))
            PanelDivider()
            SwitchRow("Hide while recording", hideRec, { hideRec = it; AssistActions.set(context, "hideRec2", it) }, subtitle = "Off: the button stays on screen and usable while recording, so you can start and save clips, but it will be in the video. On: it draws nothing while recording (a tap in the same spot still opens the menu)", icon = WonderIcons.Videocam, tint = Color(0xFFE5483C), help = "Off keeps the button visible while you record, so you can still tap it. Turn on to hide it during recordings.")
            PanelDivider()
            var cards by remember { mutableStateOf(AssistCards.enabled(context)) }
            SwitchRow("Show app controls", cards, { cards = it; AssistCards.setEnabled(context, it); if (!it) AssistCards.list.clear() }, subtitle = "Apps that have a control notification (Bliss Player's playback, Wayfinder's sharing and file jobs, Ripple's connection, Cinema's recording) also put it at the top of this menu. Their notifications stay as they are, so nothing changes for anyone not using the button. Choose per app in Control Room", icon = WonderIcons.Notifications, tint = Color(0xFFE5483C))
            PanelDivider()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("On the button itself", style = MaterialTheme.typography.titleSmall)
                listOf("tap" to "Tap", "double" to "Double tap", "long" to "Long press").forEach { (k, label) -> GesturePicker(label, k) }
            }
            PanelDivider()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shortcuts in its menu (${chosen.size})", style = MaterialTheme.typography.titleSmall)
                Text("Restore the default shortcuts", Modifier.clip(RoundedCornerShape(16.dp)).background(p.accent.copy(alpha = .14f)).clickable { AssistActions.resetChosen(context); chosen = AssistActions.chosen(context) }.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = p.accent)
                Text("Pick what the menu holds. Recording shortcuts change with what the recorder is doing: Record becomes Stop, Clip appears in clip mode.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                listOf("System", "Recording", "Quick", "Ripple", "Apps").forEach { group ->
                    Text(group, style = MaterialTheme.typography.labelLarge, color = p.accent)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistActions.CATALOG.filter { it.group == group }.forEach { a -> Chip(a.label, a.id in chosen, { chosen = if (a.id in chosen) chosen - a.id else chosen + a.id; AssistActions.setChosen(context, chosen) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GesturePicker(label: String, key: String) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var cur by remember { mutableStateOf(AssistActions.gesture(context, key)) }
    Box {
        ListRow(label, subtitle = AssistActions.GESTURES.firstOrNull { it.first == cur }?.second ?: "Nothing", chevron = true, onClick = { open = true })
        DropdownMenu(open, { open = false }) {
            AssistActions.GESTURES.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { open = false; cur = id; AssistActions.set(context, "g_$key", id) }) }
        }
    }
}

/** Quick Settings tile: hide or bring back the floating button from anywhere. */
class AssistTile : TileService() {
    override fun onStartListening() = refresh()
    private fun refresh() {
        val t = qsTile ?: return
        val on = AssistActions.enabled(this)
        t.state = if (on && !AssistState.hidden) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Floating button"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (!on) "Off in settings" else if (AssistState.hidden) "Hidden" else "Showing"
        t.icon = Icon.createWithResource(this, R.drawable.ic_tile_assist)
        t.updateTile()
    }
    override fun onClick() {
        if (!AssistActions.enabled(this)) { AssistActions.setEnabled(this, true) } else { AssistState.hidden = !AssistState.hidden; LockService.instance?.refreshButton() }
        refresh()
    }
}

/** The Wonderdesk icon's long-press shortcut: toggles the floating button and goes away. */
class AssistToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AssistActions.enabled(this)) AssistActions.setEnabled(this, true) else { AssistState.hidden = !AssistState.hidden; LockService.instance?.refreshButton() }
        finish()
    }
}
