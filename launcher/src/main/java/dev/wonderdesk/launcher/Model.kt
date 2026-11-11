package dev.wonderdesk.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
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

data class App(val key: String, val component: ComponentName, val label: String, val icon: ImageBitmap, val user: UserHandle, val installed: Long, val category: Int) {
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

/** A folder in Launchpad (the all-apps view). Apps in a group don't also show loose. */
data class Group(val id: String, val name: String, val apps: List<String>)

data class Settings(
    val cols: Int = 4, val rows: Int = 5, val iconScale: Float = 1f, val labels: Boolean = true, val dockMagnify: Boolean = true,
    val dockLabels: Boolean = false, val menuBar: Boolean = true, val pages: Int = 2, val swipeUp: String = "launchpad", val swipeDown: String = "notifications",
    val doubleTap: String = "none", val launchpadSort: String = "name", val darkLabels: Boolean = false,
    /** "natural" keeps every icon's own shape and transparency; "tiles" puts icons on Aqua tiles. */
    val iconStyle: String = "natural", val reflections: Boolean = true, val askDefault: Boolean = true,
    /** Swiping past the last page wraps to the first, and back. */
    val infinite: Boolean = false, val defaultPage: Int = 0, val lpFoldersFirst: Boolean = true,
    /** Launchpad and search open Beacon's Launchpad when Beacon is installed. */
    val beaconLaunchpad: Boolean = true,
    /** Phones keep the home screen upright like Pixel's; tablets always rotate. */
    val rotate: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("cols", cols).put("rows", rows).put("iconScale", iconScale.toDouble()).put("labels", labels).put("magnify", dockMagnify)
        .put("dockLabels", dockLabels).put("menuBar", menuBar).put("pages", pages).put("swipeUp", swipeUp).put("swipeDown", swipeDown).put("doubleTap", doubleTap)
        .put("lpSort", launchpadSort).put("darkLabels", darkLabels).put("iconStyle", iconStyle).put("reflections", reflections).put("askDefault", askDefault)
        .put("infinite", infinite).put("defaultPage", defaultPage).put("lpFoldersFirst", lpFoldersFirst).put("beaconLaunchpad", beaconLaunchpad).put("rotate", rotate)

    companion object {
        fun from(o: JSONObject) = Settings(o.optInt("cols", 4), o.optInt("rows", 5), o.optDouble("iconScale", 1.0).toFloat(), o.optBoolean("labels", true), o.optBoolean("magnify", true),
            o.optBoolean("dockLabels"), o.optBoolean("menuBar", true), o.optInt("pages", 2), o.optString("swipeUp", "launchpad"), o.optString("swipeDown", "notifications"),
            o.optString("doubleTap", "none"), o.optString("lpSort", "name"), o.optBoolean("darkLabels"), o.optString("iconStyle", "natural"), o.optBoolean("reflections", true),
            o.optBoolean("askDefault", true), o.optBoolean("infinite"), o.optInt("defaultPage", 0), o.optBoolean("lpFoldersFirst", true), o.optBoolean("beaconLaunchpad", true), o.optBoolean("rotate", false))
    }
}

val SORTS = listOf("name" to "A to Z", "name_desc" to "Z to A", "used" to "Most used", "recent" to "Recently used", "installed" to "Newest", "category" to "By category")

object Home {
    var apps by mutableStateOf<List<App>>(emptyList())
    var items by mutableStateOf<List<Item>>(emptyList())
    var dock by mutableStateOf<List<String>>(emptyList())
    /** A page the home screen should scroll to (set after something is placed on another page). */
    var jumpTo by mutableStateOf<Int?>(null)
    var hidden by mutableStateOf<Set<String>>(emptySet())
    var names by mutableStateOf<Map<String, String>>(emptyMap())
    var groups by mutableStateOf<List<Group>>(emptyList())
    var settings by mutableStateOf(Settings())
    /** Launch counts and last launch time, for the "Most used" and "Recently used" sorts. */
    var launches by mutableStateOf<Map<String, Pair<Int, Long>>>(emptyMap())
    /** The page you were on, kept while you use other apps. */
    var currentPage = 0
    private var loaded = false

    private var byKey by mutableStateOf<Map<String, App>>(emptyMap())
    fun app(key: String) = byKey[key]
    var ready by mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loading: Job? = null
    private var again = false
    private var lastCheck = 0L

    fun label(a: App) = names[a.key] ?: a.label
    private fun prefs(c: Context) = c.getSharedPreferences("home", Context.MODE_PRIVATE)
    val pageCount get() = settings.pages.coerceAtLeast((items.maxOfOrNull { it.page } ?: 0) + 1).coerceAtLeast(1)

    /* ------------------------------------------------- loading ------------------------------------------------ */

    fun loadApps(c: Context) {
        val ctx = c.applicationContext
        if (loading?.isActive == true) { again = true; return }
        loading = scope.launch {
            do {
                again = false
                val list = withContext(Dispatchers.Default) { readApps(ctx, settings.iconStyle) }
                apps = list; byKey = list.associateBy { it.key }
                lastCheck = System.currentTimeMillis()
                val keys = list.map { it.key }.toSet()
                if (list.isNotEmpty()) {
                    val newItems = items.mapNotNull { when (it) { is Item.Shortcut -> it.takeIf { s -> s.app in keys }; is Item.Folder -> it.copy(apps = it.apps.filter { a -> a in keys }).takeIf { f -> f.apps.isNotEmpty() }; else -> it } }
                    val newDock = dock.filter { it in keys }
                    val newGroups = groups.map { g -> g.copy(apps = g.apps.filter { it in keys }) }.filter { it.apps.isNotEmpty() }
                    if (newItems != items || newDock != dock || newGroups != groups) { items = newItems; dock = newDock; groups = newGroups; save(ctx) }
                    if (!prefs(ctx).getBoolean("seeded", false)) seed(ctx)
                    else if (!prefs(ctx).getBoolean("groups1", false)) {
                        // Layouts from before Launchpad folders get one for the Wonderdesk suite.
                        val suite = list.filter { it.pkg.startsWith("dev.wonderdesk.") || it.pkg.startsWith("com.blissplayer") }.map { it.key }
                        if (groups.isEmpty() && suite.size > 1) { groups = listOf(Group(newId(), "Wonderdesk", suite)); save(ctx) }
                        prefs(ctx).edit().putBoolean("groups1", true).apply()
                    }
                }
                ready = true
            } while (again)
        }
    }

    /** Cheap check on resume: reload if any app was installed, removed or updated (icons refresh themselves). */
    fun refreshIfChanged(c: Context) {
        if (System.currentTimeMillis() - lastCheck < 15_000) return
        lastCheck = System.currentTimeMillis()
        scope.launch {
            val changed = withContext(Dispatchers.Default) {
                val la = c.getSystemService(LauncherApps::class.java)
                val now = la.profiles.flatMap { u -> la.getActivityList(null, u) }.map { it.componentName.flattenToShortString() to runCatching { c.packageManager.getPackageInfo(it.componentName.packageName, 0).lastUpdateTime }.getOrDefault(0L) }.toSet()
                (now != knownStamps).also { knownStamps = now }
            }
            if (changed) loadApps(c)
        }
    }
    private var knownStamps: Set<Pair<String, Long>> = emptySet()

    private fun readApps(c: Context, style: String): List<App> {
        val la = c.getSystemService(LauncherApps::class.java)
        val px = (c.resources.displayMetrics.density * 64).toInt().coerceIn(96, 256)
        val keep = HashSet<String>(); val stamps = HashSet<Pair<String, Long>>()
        val list = la.profiles.flatMap { user ->
            la.getActivityList(null, user).mapNotNull { ai ->
                if (ai.componentName.packageName == c.packageName && !ai.componentName.className.endsWith("SettingsActivity")) return@mapNotNull null
                val key = ai.componentName.flattenToShortString() + if (user != Process.myUserHandle()) "#" + user.hashCode() else ""
                val info = runCatching { c.packageManager.getPackageInfo(ai.componentName.packageName, 0) }.getOrNull()
                val updated = info?.lastUpdateTime ?: 0L
                stamps += ai.componentName.flattenToShortString() to updated
                keep += IconLoader.fileKey(ai, px, style, updated)
                val icon = runCatching { IconLoader.load(c, ai, px, style, updated) }.getOrElse { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
                App(key, ai.componentName, ai.label.toString(), icon.asImageBitmap(), user, info?.firstInstallTime ?: 0L, ai.applicationInfo.category)
            }
        }.sortedBy { it.label.lowercase() }
        knownStamps = stamps
        IconLoader.prune(c, keep)
        return list
    }

    fun reloadIcons(c: Context) { IconLoader.clearMemory(); loadApps(c) }

    fun load(c: Context) {
        if (loaded) return
        val p = prefs(c)
        settings = runCatching { Settings.from(JSONObject(p.getString("settings", "{}")!!)) }.getOrDefault(Settings())
        items = runCatching { JSONArray(p.getString("items", "[]")).let { a -> (0 until a.length()).mapNotNull { Item.from(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        dock = runCatching { JSONArray(p.getString("dock", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        hidden = p.getStringSet("hidden", emptySet())!!.toSet()
        names = runCatching { JSONObject(p.getString("names", "{}")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrDefault(emptyMap())
        groups = runCatching { JSONArray(p.getString("groups", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Group(o.getString("id"), o.getString("name"), o.getJSONArray("apps").let { x -> (0 until x.length()).map { x.getString(it) } }) } } }.getOrDefault(emptyList())
        launches = runCatching { JSONObject(p.getString("launches", "{}")).let { o -> o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { it.getInt(0) to it.getLong(1) } } } }.getOrDefault(emptyMap())
        currentPage = p.getInt("page", settings.defaultPage)
        // Older versions put the Wonderdesk apps on page two twice: in a folder and loose beside it.
        if (!p.getBoolean("dedupe1", false)) {
            val folders = items.filterIsInstance<Item.Folder>()
            items = items.filter { it !is Item.Shortcut || folders.none { f -> f.page == it.page && it.app in f.apps } }
            p.edit().putBoolean("dedupe1", true).apply()
            if (p.getBoolean("seeded", false)) save(c)
        }
        loaded = true
        loadApps(c)
    }

    /** First run: a tidy Dock, a page of everyday apps, and the Wonderdesk apps in one folder on page two. */
    private fun seed(c: Context) {
        fun find(vararg pkgs: String) = pkgs.firstNotNullOfOrNull { pk -> apps.firstOrNull { it.pkg == pk }?.key }
        dock = listOfNotNull(find("com.google.android.dialer", "com.android.dialer"), find("com.google.android.apps.messaging", "com.android.messaging"),
            find("org.mozilla.firefox", "com.android.chrome"), find("dev.wonderdesk.wayfinder"), find("dev.wonderdesk.memories", "com.google.android.apps.photos"),
            find("com.android.camera2", "com.google.android.GoogleCamera")).take(6)
        val first = listOfNotNull(find("dev.wonderdesk.chronicle", "com.google.android.calendar"), find("dev.wonderdesk.timepiece", "com.google.android.deskclock"),
            find("dev.wonderdesk.scribble"), find("dev.wonderdesk.controlroom", "com.android.settings"), find("com.android.vending"), find("com.google.android.gm"),
            find("com.google.android.apps.maps"), find("com.google.android.youtube")).filter { it !in dock }.distinct()
        val suite = apps.filter { it.pkg.startsWith("dev.wonderdesk.") || it.pkg.startsWith("com.blissplayer") }.map { it.key }.filter { it !in dock && it !in first }
        val out = mutableListOf<Item>()
        first.take(settings.cols * 2).forEachIndexed { i, k -> out += Item.Shortcut(newId(), k, 0, i % settings.cols, settings.rows - 2 + i / settings.cols) }
        if (suite.isNotEmpty()) out += Item.Folder(newId(), "Wonderdesk", suite, 1, 0, 0)
        items = out
        groups = if (suite.isNotEmpty()) listOf(Group(newId(), "Wonderdesk", apps.filter { it.pkg.startsWith("dev.wonderdesk.") || it.pkg.startsWith("com.blissplayer") }.map { it.key })) else emptyList()
        prefs(c).edit().putBoolean("seeded", true).putBoolean("dedupe1", true).apply()
        save(c)
    }

    fun newId() = UUID.randomUUID().toString()

    fun save(c: Context) {
        prefs(c).edit()
            .putString("items", JSONArray(items.map { it.toJson() }).toString())
            .putString("dock", JSONArray(dock).toString())
            .putStringSet("hidden", hidden)
            .putString("names", JSONObject(names).toString())
            .putString("groups", JSONArray(groups.map { g -> JSONObject().put("id", g.id).put("name", g.name).put("apps", JSONArray(g.apps)) }).toString())
            .putString("settings", settings.toJson().toString())
            .apply()
    }

    fun savePage(c: Context, page: Int) { currentPage = page; prefs(c).edit().putInt("page", page).apply() }

    fun recordLaunch(c: Context, key: String) {
        val (n, _) = launches[key] ?: (0 to 0L)
        launches = launches + (key to (n + 1 to System.currentTimeMillis()))
        prefs(c).edit().putString("launches", JSONObject().apply { launches.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } }.toString()).apply()
    }

    /* -------------------------------------------------- grid -------------------------------------------------- */

    fun occupied(page: Int, x: Int, y: Int, except: String? = null) = items.any { it.page == page && it.id != except && it.covers(x, y) }

    fun fits(page: Int, x: Int, y: Int, w: Int, h: Int, except: String? = null): Boolean {
        if (x < 0 || y < 0 || x + w > settings.cols || y + h > settings.rows) return false
        for (i in x until x + w) for (j in y until y + h) if (occupied(page, i, j, except)) return false
        return true
    }

    /** The free spot nearest to ([x],[y]) on [page], or null when the page is full. */
    fun nearestFree(page: Int, x: Int, y: Int, w: Int = 1, h: Int = 1, except: String? = null): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null; var bestD = Int.MAX_VALUE
        for (j in 0..settings.rows - h) for (i in 0..settings.cols - w) if (fits(page, i, j, w, h, except)) {
            val d = (i - x) * (i - x) + (j - y) * (j - y)
            if (d < bestD) { bestD = d; best = i to j }
        }
        return best
    }

    fun freeCell(startPage: Int = 0, w: Int = 1, h: Int = 1): Triple<Int, Int, Int>? {
        for (p in startPage until pageCount + 1) for (y in 0 until settings.rows) for (x in 0 until settings.cols) if (fits(p, x, y, w, h)) {
            if (p >= settings.pages) settings = settings.copy(pages = p + 1)
            return Triple(p, x, y)
        }
        return null
    }

    fun addShortcut(c: Context, key: String, page: Int = 0, at: Pair<Int, Int>? = null): Boolean {
        val spot = at?.let { (x, y) -> nearestFree(page, x, y)?.let { Triple(page, it.first, it.second) } } ?: freeCell(page) ?: return false
        items = items + Item.Shortcut(newId(), key, spot.first, spot.second, spot.third); save(c); return true
    }

    fun remove(c: Context, id: String) { items = items.filter { it.id != id }; save(c) }
    fun update(c: Context, item: Item) { items = items.map { if (it.id == item.id) item else it }; save(c) }

    fun addPage(c: Context): Int { settings = settings.copy(pages = pageCount + 1); save(c); return pageCount - 1 }

    /** Removes a page; items on it go away (widgets are released by the caller), later pages move up. */
    fun removePage(c: Context, page: Int): List<Item.Widget> {
        val gone = items.filter { it.page == page }
        val count = pageCount
        items = items.filter { it.page != page }.map { if (it.page > page) it.at(it.page - 1, it.x, it.y) else it }
        settings = settings.copy(pages = (count - 1).coerceAtLeast(1), defaultPage = settings.defaultPage.let { if (it >= page && it > 0) it - 1 else it })
        save(c)
        return gone.filterIsInstance<Item.Widget>()
    }

    fun movePage(c: Context, from: Int, to: Int) {
        if (from == to) return
        items = items.map { i ->
            val p = when { i.page == from -> to; from < to && i.page in from + 1..to -> i.page - 1; from > to && i.page in to until from -> i.page + 1; else -> i.page }
            i.at(p, i.x, i.y)
        }
        save(c)
    }

    fun suggestFolderName(keys: List<String>): String {
        val list = keys.mapNotNull { app(it) }
        return when {
            list.all { it.pkg.startsWith("dev.wonderdesk.") || it.pkg.startsWith("com.blissplayer") } -> "Wonderdesk"
            list.isNotEmpty() && list.all { it.category == ApplicationInfo.CATEGORY_GAME } -> "Games"
            list.isNotEmpty() && list.map { it.category }.distinct().size == 1 && list.first().category >= 0 -> categoryName(list.first().category)
            list.all { it.pkg.contains("google") } -> "Google"
            else -> "Folder"
        }
    }

    fun categoryName(cat: Int) = when (cat) {
        ApplicationInfo.CATEGORY_GAME -> "Games"; ApplicationInfo.CATEGORY_AUDIO -> "Music & audio"; ApplicationInfo.CATEGORY_VIDEO -> "Video"
        ApplicationInfo.CATEGORY_IMAGE -> "Photos"; ApplicationInfo.CATEGORY_SOCIAL -> "Social"; ApplicationInfo.CATEGORY_NEWS -> "News"
        ApplicationInfo.CATEGORY_MAPS -> "Maps & travel"; ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"; 8 -> "Accessibility"
        else -> "Other"
    }

    /* ----------------------------------------------- launchpad ------------------------------------------------ */

    fun sorted(list: List<App>, sort: String): List<App> = when (sort) {
        "name_desc" -> list.sortedByDescending { label(it).lowercase() }
        "used" -> list.sortedWith(compareByDescending<App> { launches[it.key]?.first ?: 0 }.thenBy { label(it).lowercase() })
        "recent" -> list.sortedWith(compareByDescending<App> { launches[it.key]?.second ?: 0L }.thenBy { label(it).lowercase() })
        "installed" -> list.sortedByDescending { it.installed }
        "category" -> list.sortedWith(compareBy<App> { categoryName(it.category) }.thenBy { label(it).lowercase() })
        else -> list.sortedBy { label(it).lowercase() }
    }

    fun addToGroup(c: Context, groupId: String, key: String) {
        groups = groups.map { g -> if (g.id == groupId) g.copy(apps = (g.apps + key).distinct()) else g.copy(apps = g.apps - key) }.filter { it.apps.isNotEmpty() }; save(c)
    }
    fun removeFromGroups(c: Context, key: String) { groups = groups.map { it.copy(apps = it.apps - key) }.filter { it.apps.isNotEmpty() }; save(c) }
    fun renameGroup(c: Context, id: String, name: String) { groups = groups.map { if (it.id == id) it.copy(name = name) else it }; save(c) }
    fun deleteGroup(c: Context, id: String) { groups = groups.filter { it.id != id }; save(c) }
    fun newGroup(c: Context, name: String, keys: List<String>): Group {
        val g = Group(newId(), name, keys.distinct())
        groups = groups.map { it.copy(apps = it.apps - keys.toSet()) }.filter { it.apps.isNotEmpty() } + g; save(c); return g
    }

    /** Fuzzy app search: whole-name, word-start and in-order letter matches, best first. */
    fun search(q: String): List<App> {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return emptyList()
        fun score(a: App): Int {
            val n = label(a).lowercase()
            val base = when {
                n == query -> 1000
                n.startsWith(query) -> 800 - n.length
                n.split(' ', '-', '.').any { it.startsWith(query) } -> 600 - n.length
                query in n -> 400 - n.indexOf(query)
                query.length >= 3 && a.pkg.lowercase().contains(query) -> 200
                else -> { var i = 0; for (ch in n) if (i < query.length && ch == query[i]) i++; if (i == query.length && query.length >= 3) 100 - n.length else 0 }
            }
            return if (base <= 0) 0 else base + (launches[a.key]?.first ?: 0).coerceAtMost(50)
        }
        return apps.filter { it.key !in hidden }.map { it to score(it) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    fun exportLayout(): String = JSONObject().put("items", JSONArray(items.map { it.toJson() })).put("dock", JSONArray(dock)).put("hidden", JSONArray(hidden.toList()))
        .put("names", JSONObject(names)).put("groups", JSONArray(groups.map { g -> JSONObject().put("id", g.id).put("name", g.name).put("apps", JSONArray(g.apps)) })).toString(2)

    fun importLayout(c: Context, json: String) {
        val o = JSONObject(json)
        items = o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { Item.from(a.getJSONObject(it)) }.filter { it !is Item.Widget } } ?: items
        dock = o.optJSONArray("dock")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: dock
        hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: hidden
        names = o.optJSONObject("names")?.let { n -> n.keys().asSequence().associateWith { n.getString(it) } } ?: names
        groups = o.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { g -> Group(g.getString("id"), g.getString("name"), g.getJSONArray("apps").let { x -> (0 until x.length()).map { x.getString(it) } }) } } ?: groups
        save(c); loadApps(c)
    }

    fun resetLayout(c: Context) { prefs(c).edit().putBoolean("seeded", false).apply(); items = emptyList(); dock = emptyList(); groups = emptyList(); settings = settings.copy(pages = 2); loadApps(c) }
}
