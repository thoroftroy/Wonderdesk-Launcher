package dev.wonderdesk.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Process
import android.os.UserHandle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class App(val key: String, val component: ComponentName, val label: String, val icon: ImageBitmap, val user: UserHandle, val installed: Long) {
    val pkg get() = component.packageName
}

/** Something placed on a home page. [x],[y] are grid cells; widgets span [w] by [h]. */
sealed class Item {
    abstract val id: String; abstract val page: Int; abstract val x: Int; abstract val y: Int; abstract val w: Int; abstract val h: Int
    data class Shortcut(override val id: String, val app: String, override val page: Int, override val x: Int, override val y: Int) : Item() { override val w = 1; override val h = 1 }
    data class Folder(override val id: String, val name: String, val apps: List<String>, override val page: Int, override val x: Int, override val y: Int) : Item() { override val w = 1; override val h = 1 }
    data class Widget(override val id: String, val widgetId: Int, override val page: Int, override val x: Int, override val y: Int, override val w: Int, override val h: Int) : Item()

    fun at(page: Int, x: Int, y: Int): Item = when (this) { is Shortcut -> copy(page = page, x = x, y = y); is Folder -> copy(page = page, x = x, y = y); is Widget -> copy(page = page, x = x, y = y) }
    fun covers(px: Int, py: Int) = px in x until x + w && py in y until y + h

    fun toJson(): JSONObject = when (this) {
        is Shortcut -> JSONObject().put("t", "s").put("app", app)
        is Folder -> JSONObject().put("t", "f").put("name", name).put("apps", JSONArray(apps))
        is Widget -> JSONObject().put("t", "w").put("wid", widgetId).put("w", w).put("h", h)
    }.put("id", id).put("p", page).put("x", x).put("y", y)

    companion object {
        fun from(o: JSONObject): Item? = when (o.optString("t")) {
            "s" -> Shortcut(o.getString("id"), o.getString("app"), o.getInt("p"), o.getInt("x"), o.getInt("y"))
            "f" -> Folder(o.getString("id"), o.optString("name", "Folder"), o.getJSONArray("apps").let { a -> (0 until a.length()).map { a.getString(it) } }, o.getInt("p"), o.getInt("x"), o.getInt("y"))
            "w" -> Widget(o.getString("id"), o.getInt("wid"), o.getInt("p"), o.getInt("x"), o.getInt("y"), o.optInt("w", 2), o.optInt("h", 2))
            else -> null
        }
    }
}

data class Settings(
    val cols: Int = 4, val rows: Int = 5, val iconScale: Float = 1f, val labels: Boolean = true, val dockMagnify: Boolean = true,
    val dockLabels: Boolean = false, val menuBar: Boolean = true, val pages: Int = 2, val swipeUp: String = "launchpad", val swipeDown: String = "notifications",
    val doubleTap: String = "none", val launchpadSort: String = "name", val darkLabels: Boolean = false,
    /** "natural" keeps every icon's own shape and transparency; "tiles" puts icons on Aqua tiles. */
    val iconStyle: String = "natural", val reflections: Boolean = true, val askDefault: Boolean = true,
)

object Home {
    var apps by mutableStateOf<List<App>>(emptyList())
    var items by mutableStateOf<List<Item>>(emptyList())
    var dock by mutableStateOf<List<String>>(emptyList())
    var hidden by mutableStateOf<Set<String>>(emptySet())
    var names by mutableStateOf<Map<String, String>>(emptyMap())
    var settings by mutableStateOf(Settings())
    private var loaded = false

    private var byKey by mutableStateOf<Map<String, App>>(emptyMap())
    fun app(key: String) = byKey[key]
    /** True once the first icon load finished (the UI shows the layout as soon as this flips). */
    var ready by mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loading: Job? = null
    private var again = false
    fun label(a: App) = names[a.key] ?: a.label

    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)

    /**
     * Reloads the app list in the background. Icons come from [IconLoader]'s disk cache, so this is
     * cheap; it runs at start and when apps are installed, removed or updated, never on every resume.
     */
    fun loadApps(c: Context) {
        val ctx = c.applicationContext
        if (loading?.isActive == true) { again = true; return }
        loading = scope.launch {
            do {
                again = false
                val style = settings.iconStyle
                val list = withContext(Dispatchers.Default) { readApps(ctx, style) }
                apps = list; byKey = list.associateBy { it.key }
                val keys = list.map { it.key }.toSet()
                if (list.isNotEmpty()) {
                    // Drop shortcuts to apps that were uninstalled.
                    val newItems = items.mapNotNull { when (it) { is Item.Shortcut -> it.takeIf { s -> s.app in keys }; is Item.Folder -> it.copy(apps = it.apps.filter { a -> a in keys }).takeIf { f -> f.apps.isNotEmpty() }; else -> it } }
                    val newDock = dock.filter { it in keys }
                    if (newItems != items || newDock != dock) { items = newItems; dock = newDock; save(ctx) }
                    if (!prefs(ctx).getBoolean("seeded", false)) seed(ctx)
                }
                ready = true
            } while (again)
        }
    }

    private fun readApps(c: Context, style: String): List<App> {
        val la = c.getSystemService(LauncherApps::class.java)
        val px = (c.resources.displayMetrics.density * 64).toInt().coerceIn(96, 256)
        val keep = HashSet<String>()
        val list = la.profiles.flatMap { user ->
            la.getActivityList(null, user).mapNotNull { ai ->
                if (ai.componentName.packageName == c.packageName && !ai.componentName.className.endsWith("SettingsActivity")) return@mapNotNull null
                val key = ai.componentName.flattenToShortString() + if (user != Process.myUserHandle()) "#" + user.hashCode() else ""
                val info = runCatching { c.packageManager.getPackageInfo(ai.componentName.packageName, 0) }.getOrNull()
                val updated = info?.lastUpdateTime ?: 0L
                keep += IconLoader.fileKey(ai, px, style, updated)
                val icon = runCatching { IconLoader.load(c, ai, px, style, updated) }.getOrElse { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
                App(key, ai.componentName, ai.label.toString(), icon.asImageBitmap(), user, info?.firstInstallTime ?: 0L)
            }
        }.sortedBy { it.label.lowercase() }
        IconLoader.prune(c, keep)
        return list
    }

    /** After changing the icon style. */
    fun reloadIcons(c: Context) { IconLoader.clearMemory(); loadApps(c) }

    fun load(c: Context) {
        if (loaded) return
        val p = prefs(c)
        settings = runCatching { JSONObject(p.getString("settings", "{}")).let { o ->
            Settings(o.optInt("cols", 4), o.optInt("rows", 5), o.optDouble("iconScale", 1.0).toFloat(), o.optBoolean("labels", true), o.optBoolean("magnify", true),
                o.optBoolean("dockLabels"), o.optBoolean("menuBar", true), o.optInt("pages", 2), o.optString("swipeUp", "launchpad"), o.optString("swipeDown", "notifications"),
                o.optString("doubleTap", "none"), o.optString("lpSort", "name"), o.optBoolean("darkLabels"),
                o.optString("iconStyle", "natural"), o.optBoolean("reflections", true), o.optBoolean("askDefault", true))
        } }.getOrDefault(Settings())
        items = runCatching { JSONArray(p.getString("items", "[]")).let { a -> (0 until a.length()).mapNotNull { Item.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        dock = runCatching { JSONArray(p.getString("dock", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        hidden = p.getStringSet("hidden", emptySet())!!.toSet()
        names = runCatching { JSONObject(p.getString("names", "{}")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrDefault(emptyMap())
        loaded = true
        loadApps(c)
    }

    /** First run: a tidy dock and a first page of the essentials, Wonderdesk apps on page two. */
    private fun seed(c: Context) {
        fun find(vararg pkgs: String) = pkgs.firstNotNullOfOrNull { pk -> apps.firstOrNull { it.pkg == pk }?.key }
        dock = listOfNotNull(find("com.google.android.dialer", "com.android.dialer"), find("com.google.android.apps.messaging", "com.android.messaging"),
            find("org.mozilla.firefox", "com.android.chrome"), find("dev.wonderdesk.wayfinder"), find("dev.wonderdesk.memories", "com.google.android.apps.photos"),
            find("com.android.camera2", "com.google.android.GoogleCamera")).take(6)
        val suite = apps.filter { it.pkg.startsWith("dev.wonderdesk.") || it.pkg.startsWith("com.blissplayer") }.map { it.key }.filter { it !in dock }
        val first = listOfNotNull(find("dev.wonderdesk.chronicle", "com.google.android.calendar"), find("dev.wonderdesk.timepiece", "com.google.android.deskclock"),
            find("dev.wonderdesk.scribble"), find("dev.wonderdesk.controlroom", "com.android.settings"), find("com.android.vending"), find("com.google.android.gm"),
            find("com.google.android.apps.maps"), find("com.google.android.youtube")).filter { it !in dock }.distinct()
        val out = mutableListOf<Item>()
        first.take(settings.cols * 2).forEachIndexed { i, k -> out += Item.Shortcut(UUID.randomUUID().toString(), k, 0, i % settings.cols, settings.rows - 2 + i / settings.cols) }
        if (suite.isNotEmpty()) {
            out += Item.Folder(UUID.randomUUID().toString(), "Wonderdesk", suite, 1, 0, 0)
            suite.filter { it !in first }.take(settings.cols * (settings.rows - 1)).forEachIndexed { i, k -> out += Item.Shortcut(UUID.randomUUID().toString(), k, 1, (i + 1) % settings.cols, (i + 1) / settings.cols) }
        }
        items = out
        prefs(c).edit().putBoolean("seeded", true).apply()
        save(c)
    }

    fun save(c: Context) {
        val s = settings
        prefs(c).edit()
            .putString("items", JSONArray(items.map { it.toJson() }).toString())
            .putString("dock", JSONArray(dock).toString())
            .putStringSet("hidden", hidden)
            .putString("names", JSONObject(names).toString())
            .putString("settings", JSONObject().put("cols", s.cols).put("rows", s.rows).put("iconScale", s.iconScale.toDouble()).put("labels", s.labels).put("magnify", s.dockMagnify)
                .put("dockLabels", s.dockLabels).put("menuBar", s.menuBar).put("pages", s.pages).put("swipeUp", s.swipeUp).put("swipeDown", s.swipeDown).put("doubleTap", s.doubleTap)
                .put("lpSort", s.launchpadSort).put("darkLabels", s.darkLabels).put("iconStyle", s.iconStyle).put("reflections", s.reflections).put("askDefault", s.askDefault).toString())
            .apply()
    }

    fun occupied(page: Int, x: Int, y: Int, except: String? = null) = items.any { it.page == page && it.id != except && it.covers(x, y) }

    fun fits(page: Int, x: Int, y: Int, w: Int, h: Int, except: String? = null): Boolean {
        if (x < 0 || y < 0 || x + w > settings.cols || y + h > settings.rows) return false
        for (i in x until x + w) for (j in y until y + h) if (occupied(page, i, j, except)) return false
        return true
    }

    fun freeCell(startPage: Int = 0, w: Int = 1, h: Int = 1): Triple<Int, Int, Int>? {
        for (p in startPage until settings.pages + 1) for (y in 0 until settings.rows) for (x in 0 until settings.cols) if (fits(p, x, y, w, h)) {
            if (p >= settings.pages) settings = settings.copy(pages = p + 1)
            return Triple(p, x, y)
        }
        return null
    }

    fun addShortcut(c: Context, key: String, page: Int = 0) {
        val cell = freeCell(page) ?: return
        items = items + Item.Shortcut(UUID.randomUUID().toString(), key, cell.first, cell.second, cell.third); save(c)
    }

    /** Drop [moving] onto a cell: move there, merge into a folder, or make a new folder. */
    fun drop(c: Context, moving: Item, page: Int, x: Int, y: Int) {
        val target = items.firstOrNull { it.page == page && it.id != moving.id && it.covers(x, y) }
        items = when {
            target == null && fits(page, x, y, moving.w, moving.h, moving.id) -> items.map { if (it.id == moving.id) it.at(page, x, y) else it }
            target is Item.Folder && moving is Item.Shortcut -> items.filter { it.id != moving.id }.map { if (it.id == target.id) target.copy(apps = target.apps + moving.app) else it }
            target is Item.Shortcut && moving is Item.Shortcut -> items.filter { it.id != moving.id && it.id != target.id } +
                Item.Folder(UUID.randomUUID().toString(), suggestFolderName(listOf(target.app, moving.app)), listOf(target.app, moving.app), page, target.x, target.y)
            else -> items
        }
        save(c)
    }

    private fun suggestFolderName(keys: List<String>): String {
        val pk = keys.mapNotNull { app(it)?.pkg }
        return when {
            pk.all { it.startsWith("dev.wonderdesk.") } -> "Wonderdesk"
            pk.any { "game" in it } -> "Games"
            pk.any { it.contains("google") } -> "Google"
            else -> "Folder"
        }
    }

    fun remove(c: Context, id: String) { items = items.filter { it.id != id }; save(c) }
    fun update(c: Context, item: Item) { items = items.map { if (it.id == item.id) item else it }; save(c) }

    fun exportLayout(): String = JSONObject().put("items", JSONArray(items.map { it.toJson() })).put("dock", JSONArray(dock)).put("hidden", JSONArray(hidden.toList()))
        .put("names", JSONObject(names)).toString(2)

    fun importLayout(c: Context, json: String) {
        val o = JSONObject(json)
        items = o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { Item.from(a.getJSONObject(it)) }.filter { it !is Item.Widget } } ?: items
        dock = o.optJSONArray("dock")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: dock
        hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: hidden
        names = o.optJSONObject("names")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) } } ?: names
        save(c); loadApps(c)
    }
}
