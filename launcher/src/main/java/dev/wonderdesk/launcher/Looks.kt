package dev.wonderdesk.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved looks: a named copy of everything about how the home and lock screens look (wallpaper for day, night, home and lock,
 * the lock screen layout, and your own scene). Switching is one tap, so the same phone can be the animated Wonderdesk look
 * one moment and a favourite photo or video the next.
 */
object Looks {
    private val STORES = listOf("livewp", "lock", "scene", "daynight")
    private fun prefs(c: Context) = c.getSharedPreferences("looks", Context.MODE_PRIVATE)

    fun names(c: Context): List<String> = runCatching { val a = JSONArray(prefs(c).getString("list", "[]")); (0 until a.length()).map { a.getJSONObject(it).getString("name") } }.getOrDefault(emptyList())

    private fun snapshot(c: Context): JSONObject = JSONObject().also { o ->
        for (s in STORES) {
            val one = JSONObject()
            for ((k, v) in c.getSharedPreferences(s, Context.MODE_PRIVATE).all) {
                when (v) { is Boolean -> one.put(k, JSONObject().put("t", "b").put("v", v)); is Int -> one.put(k, JSONObject().put("t", "i").put("v", v)); is Long -> one.put(k, JSONObject().put("t", "l").put("v", v))
                    is Float -> one.put(k, JSONObject().put("t", "f").put("v", v.toDouble())); is String -> one.put(k, JSONObject().put("t", "s").put("v", v)) }
            }
            o.put(s, one)
        }
    }

    /** Saves the current look under a name (replacing a look with the same name). */
    fun save(c: Context, name: String) {
        val n = name.trim().ifBlank { return }
        val a = runCatching { JSONArray(prefs(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        val rest = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("name") != n }
        prefs(c).edit().putString("list", JSONArray(rest + JSONObject().put("name", n).put("data", snapshot(c))).toString()).apply()
    }

    fun delete(c: Context, name: String) {
        val a = runCatching { JSONArray(prefs(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        prefs(c).edit().putString("list", JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("name") != name }).toString()).apply()
    }

    /** Puts a saved look back and tells the wallpaper to redraw. */
    fun apply(c: Context, name: String): Boolean {
        val a = runCatching { JSONArray(prefs(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        val data = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("name") == name }?.getJSONObject("data") ?: return false
        for (s in STORES) {
            val one = data.optJSONObject(s) ?: continue
            val e = c.getSharedPreferences(s, Context.MODE_PRIVATE).edit().clear()
            for (k in one.keys()) { val x = one.getJSONObject(k)
                when (x.getString("t")) { "b" -> e.putBoolean(k, x.getBoolean("v")); "i" -> e.putInt(k, x.getInt("v")); "l" -> e.putLong(k, x.getLong("v")); "f" -> e.putFloat(k, x.getDouble("v").toFloat()); else -> e.putString(k, x.getString("v")) } }
            e.apply()
        }
        WpConfig.changed(c)
        if (WpConfig.liveActive(c) && WpConfig.lockSame(c)) WpConfig.lockUseLive(c)
        c.sendBroadcast(android.content.Intent(WpConfig.ACTION_RELOAD).setPackage(c.packageName))
        return true
    }
}
