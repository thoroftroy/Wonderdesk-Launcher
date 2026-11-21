package dev.wonderdesk.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*

/** One control another Wonderdesk app has put on the floating button (recording, playback, a transfer, a connection). */
class AssistCard(val key: String, val id: String, val title: String, val text: String?, val progress: Int, val ongoing: Boolean,
                 val open: PendingIntent?, val labels: List<String>, val actions: List<PendingIntent>, val pkg: String, val expires: Long)

/** The cards that apps have posted. Memory only: an app posts again whenever its state changes. */
object AssistCards {
    val list = mutableStateListOf<AssistCard>()
    private fun prefs(c: Context) = c.getSharedPreferences("assist", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("cards", true)
    fun setEnabled(c: Context, v: Boolean) { prefs(c).edit().putBoolean("cards", v).apply() }

    fun put(card0: AssistCard) {
        // Cards that nothing clears still go away by themselves after ten minutes, and the list never grows without bound.
        val card = if (card0.expires == 0L && !card0.ongoing) AssistCard(card0.key, card0.id, card0.title, card0.text, card0.progress, card0.ongoing, card0.open, card0.labels, card0.actions, card0.pkg, System.currentTimeMillis() + 600_000L) else card0
        while (list.size >= 40) list.removeAt(0)
        val i = list.indexOfFirst { it.key == card.key && it.id == card.id }
        if (i >= 0) list[i] = card else list.add(card)
    }
    fun remove(key: String, id: String) { list.removeAll { it.key == key && it.id == id } }
    fun prune() { val now = System.currentTimeMillis(); list.removeAll { it.expires in 1..now } }
}

/** Receives the cards. Only the Wonderdesk apps' own buttons are shown: a card's buttons must belong to the package that sent it. */
class AssistCardReceiver : BroadcastReceiver() {
    private fun trusted(p: String?) = p != null && (p.startsWith("dev.wonderdesk.") || p.startsWith("com.blissplayer."))

    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        val pkg = i.getStringExtra("pkg"); val key = i.getStringExtra("key") ?: return; val id = i.getStringExtra("id") ?: return
        if (!trusted(pkg) || !AssistCards.enabled(c)) return
        if (i.action == dev.wonderdesk.shared.WonderAssist.CLEAR) { AssistCards.remove(key, id); return }
        val actions = (i.getParcelableArrayListExtra<PendingIntent>("actions") ?: arrayListOf()).filter { it.creatorPackage == pkg }
        val labels = i.getStringArrayExtra("labels")?.toList().orEmpty().take(actions.size)
        val open = i.getParcelableExtra<PendingIntent>("open")?.takeIf { it.creatorPackage == pkg }
        val expire = i.getLongExtra("expire", 0L)
        AssistCards.put(AssistCard(key, id, i.getStringExtra("title").orEmpty(), i.getStringExtra("text"), i.getIntExtra("progress", -1), i.getBooleanExtra("ongoing", false), open, labels, actions, pkg!!, if (expire > 0) System.currentTimeMillis() + expire else 0L))
    }
}

private fun appLabel(c: Context, pkg: String): String = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })

/** One card: swipe it sideways or tap × to dismiss it. */
@Composable
private fun CardRow(card: AssistCard, accent: Color, close: () -> Unit) {
    val context = LocalContext.current
    val pal = wonderPalette()
    var dx by remember(card.key, card.id) { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(dx.toInt(), 0) }.graphicsLayer { alpha = (1f - kotlin.math.abs(dx) / 700f).coerceIn(.2f, 1f) }
        .pointerInput(card.key, card.id) { detectHorizontalDragGestures(onDragEnd = { if (kotlin.math.abs(dx) > 140f) AssistCards.remove(card.key, card.id) else dx = 0f }, onDragCancel = { dx = 0f }) { _, d -> dx += d } }
        .clip(RoundedCornerShape(pal.panelRadius + 4.dp)).background(accent.copy(alpha = .10f))
        .clickable(enabled = card.open != null) { runCatching { card.open?.send() }; close() }.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = remember(card.pkg) { context.suiteArt(card.pkg, 72) }
            if (art != null) Image(art, null, Modifier.size(26.dp).clip(CircleShape))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!card.text.isNullOrBlank()) Text(card.text, style = MaterialTheme.typography.bodySmall, color = pal.secondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text("×", Modifier.clip(CircleShape).clickable { AssistCards.remove(card.key, card.id) }.padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.titleLarge, color = pal.secondaryText)
        }
        if (card.progress in 0..100) LinearProgressIndicator(progress = { card.progress / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape), color = accent)
        if (card.actions.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            card.actions.forEachIndexed { n, pi ->
                Text(card.labels.getOrNull(n).orEmpty(), Modifier.weight(1f, fill = false).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .16f))
                    .clickable { runCatching { pi.send() }; if (!card.ongoing) AssistCards.remove(card.key, card.id); close() }.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = accent, maxLines = 1)
            }
        }
    }
}

/**
 * The "Now" section at the top of the floating button's menu. Cards are grouped by app (tap a group to expand it), the
 * section never grows past a fixed height and scrolls, every card can be swiped or crossed away, and there is Clear all.
 */
@Composable
fun AssistCardsSection(accent: Color, close: () -> Unit) {
    val context = LocalContext.current
    val pal = wonderPalette()
    LaunchedEffect(AssistCards.list.size) { AssistCards.prune() }
    if (AssistCards.list.isEmpty()) return
    var open by remember { mutableStateOf(setOf<String>()) }
    val groups = AssistCards.list.toList().asReversed().groupBy { it.pkg }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${AssistCards.list.size} now", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = pal.secondaryText)
            Text("Clear all", Modifier.clip(RoundedCornerShape(10.dp)).clickable { AssistCards.list.clear() }.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = accent)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (pkg, cards) ->
                val expanded = pkg in open
                if (cards.size > 1) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = if (expanded) open - pkg else open + pkg }.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(appLabel(context, pkg), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = pal.text)
                    Text("${cards.size}", Modifier.clip(CircleShape).background(accent.copy(alpha = .2f)).padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelMedium, color = accent)
                    Text(if (expanded) "  Hide" else "  Show all", style = MaterialTheme.typography.labelMedium, color = accent)
                    Text("  Clear", Modifier.clickable { cards.forEach { AssistCards.remove(it.key, it.id) } }, style = MaterialTheme.typography.labelMedium, color = pal.secondaryText)
                }
                (if (cards.size > 1 && !expanded) cards.take(1) else cards).forEach { CardRow(it, accent, close) }
            }
        }
    }
}
