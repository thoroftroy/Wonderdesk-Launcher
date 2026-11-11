package dev.wonderdesk.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONArray

/**
 * Shares Launchpad folders and launch history with Beacon, so both show the same folders.
 * Only Wonderdesk apps (and adb) may read or change them.
 */
class AppsProvider : ContentProvider() {
    override fun onCreate() = true

    private fun allowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SHELL_UID) return true
        return context?.packageManager?.getPackagesForUid(uid).orEmpty().any { it.startsWith("dev.wonderdesk.") }
    }

    /** Home's state drives the screen, so it's only ever read and changed on the main thread. */
    private fun <T> onMain(block: () -> T): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return runCatching(block).getOrNull()
        var out: T? = null
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post { out = runCatching(block).getOrNull(); done.countDown() }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        if (!allowed()) return null
        val c = context ?: return null
        return onMain { Home.load(c); queryNow(uri) }
    }

    private fun queryNow(uri: Uri): Cursor? {
        return when (uri.lastPathSegment) {
            "groups" -> MatrixCursor(arrayOf("id", "name", "apps")).apply { Home.groups.forEach { g -> addRow(arrayOf(g.id, g.name, JSONArray(g.apps).toString())) } }
            "launches" -> MatrixCursor(arrayOf("app", "count", "last")).apply { Home.launches.forEach { (k, v) -> addRow(arrayOf(k, v.first, v.second)) } }
            "settings" -> MatrixCursor(arrayOf("sort", "foldersFirst")).apply { addRow(arrayOf(Home.settings.launchpadSort, if (Home.settings.lpFoldersFirst) 1 else 0)) }
            "hidden" -> MatrixCursor(arrayOf("app")).apply { Home.hidden.forEach { addRow(arrayOf(it)) } }
            "dock" -> MatrixCursor(arrayOf("app")).apply { Home.dock.forEach { addRow(arrayOf(it)) } }
            else -> null
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!allowed()) return null
        val c = context ?: return null
        return onMain { Home.load(c); callNow(c, method, arg, extras) }
    }

    private fun callNow(c: android.content.Context, method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            "addToGroup" -> { val g = extras?.getString("group") ?: return null; arg?.let { Home.addToGroup(c, g, it) } }
            "newGroup" -> { val name = extras?.getString("name") ?: "Folder"; arg?.let { Home.newGroup(c, name, listOf(it)) } }
            "removeFromGroups" -> arg?.let { Home.removeFromGroups(c, it) }
            "renameGroup" -> { val g = extras?.getString("group") ?: return null; arg?.let { Home.renameGroup(c, g, it) } }
            "deleteGroup" -> arg?.let { Home.deleteGroup(c, it) }
            "recordLaunch" -> arg?.let { Home.recordLaunch(c, it) }
            "addToHome" -> return Bundle().apply { putBoolean("ok", arg != null && Home.addShortcut(c, arg, Home.currentPage)) }
            "addToDock" -> { val a = arg ?: return null; if (a in Home.dock) return Bundle().apply { putBoolean("ok", true) }
                if (Home.dock.size >= 8) return Bundle().apply { putBoolean("ok", false) }; Home.dock = Home.dock + a; Home.save(c) }
            "hide" -> arg?.let { Home.hidden = Home.hidden + it; Home.save(c) }
            "unhide" -> arg?.let { Home.hidden = Home.hidden - it; Home.save(c) }
            "setSort" -> arg?.let { Home.settings = Home.settings.copy(launchpadSort = it); Home.save(c) }
            else -> return null
        }
        return Bundle()
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
