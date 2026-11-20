package dev.wonderdesk.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

    fun put(card: AssistCard) {
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

/** The "Now" section at the top of the floating button's menu: one row per card with its buttons. */
@Composable
fun AssistCardsSection(accent: Color, close: () -> Unit) {
    val context = LocalContext.current
    val pal = wonderPalette()
    LaunchedEffect(AssistCards.list.size) { AssistCards.prune() }
    if (AssistCards.list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistCards.list.toList().forEach { card ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(pal.panelRadius + 4.dp)).background(accent.copy(alpha = .10f))
                .clickable(enabled = card.open != null) { runCatching { card.open?.send() }; close() }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val art = remember(card.pkg) { context.suiteArt(card.pkg, 72) }
                    if (art != null) Image(art, null, Modifier.size(26.dp).clip(CircleShape))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(card.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!card.text.isNullOrBlank()) Text(card.text, style = MaterialTheme.typography.bodySmall, color = pal.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
    }
}
