package dev.wonderdesk.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import org.xmlpull.v1.XmlPullParser

/**
 * Icon packs from the Play Store (the same ones Nova, Lawnchair and others use): each pack lists in its appfilter.xml
 * which drawable replaces which app's icon. Apps the pack doesn't cover keep their own icon.
 */
object IconPack {
    private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME", "com.gau.go.launcherex.theme", "org.adw.ActivityStarter.THEMES")
    private var loadedFor = ""
    private var map: Map<String, String> = emptyMap()
    private var res: Resources? = null

    /** Installed icon packs: package to name. */
    fun installed(c: Context): List<Pair<String, String>> = ACTIONS.flatMap { a ->
        runCatching { c.packageManager.queryIntentActivities(Intent(a), 0) }.getOrDefault(emptyList())
    }.map { it.activityInfo.packageName to it.loadLabel(c.packageManager).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }

    @Synchronized private fun ensure(c: Context, pkg: String) {
        if (loadedFor == pkg) return
        loadedFor = pkg; map = emptyMap(); res = null
        val r = runCatching { c.packageManager.getResourcesForApplication(pkg) }.getOrNull() ?: return
        res = r
        val out = HashMap<String, String>()
        fun read(p: XmlPullParser) {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "item") {
                    val comp = p.getAttributeValue(null, "component") ?: continue
                    val draw = p.getAttributeValue(null, "drawable") ?: continue
                    val key = comp.removePrefix("ComponentInfo{").removeSuffix("}")
                    if (key.contains('/')) out.putIfAbsent(key, draw)
                }
            }
        }
        val id = r.getIdentifier("appfilter", "xml", pkg)
        runCatching {
            if (id != 0) read(r.getXml(id))
            else r.assets.open("appfilter.xml").use { s -> val p = android.util.Xml.newPullParser(); p.setInput(s, null); read(p) }
        }
        map = out
    }

    /** The pack's drawable for this activity, or null when the pack doesn't have one. */
    fun icon(c: Context, pkg: String, cn: ComponentName, density: Int): Drawable? {
        if (pkg.isBlank()) return null
        ensure(c, pkg)
        val r = res ?: return null
        val name = map["${cn.packageName}/${cn.className}"] ?: map["${cn.packageName}/${cn.packageName}${cn.className.removePrefix(cn.packageName)}"] ?: return null
        val id = r.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: r.getIdentifier(name, "mipmap", pkg).takeIf { it != 0 } ?: return null
        return runCatching { r.getDrawableForDensity(id, density, null) }.getOrNull()
    }
}
