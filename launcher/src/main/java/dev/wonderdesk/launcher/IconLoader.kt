package dev.wonderdesk.launcher

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import java.io.File
import java.security.MessageDigest

/**
 * Renders app icons for the home screen, once, off the main thread, and keeps them on disk so the
 * launcher starts instantly and never re-renders icons while you use it.
 *
 * Styles:
 *  - "natural": icons keep their own shape and transparency. Adaptive icons whose background
 *    layer is empty show just their artwork, uncropped. Wonderdesk apps show their full illustration.
 *  - "tiles": every icon sits on a rounded Aqua tile (adaptive icons use their own background).
 */
object IconLoader {
    private const val VERSION = 5
    private val memory = HashMap<String, Bitmap>()

    fun clearMemory() = synchronized(memory) { memory.clear() }

    fun clearDisk(c: Context) { File(c.cacheDir, "icons").listFiles()?.forEach { it.delete() } }

    fun load(c: Context, ai: LauncherActivityInfo, px: Int, style: String, updated: Long): Bitmap {
        val key = "${ai.componentName.flattenToShortString()}|${ai.user.hashCode()}|$updated|$style|$px|$VERSION"
        synchronized(memory) { memory[key]?.let { return it } }
        val file = File(File(c.cacheDir, "icons").apply { mkdirs() }, sha1(key) + ".png")
        val cached = if (file.isFile) runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() else null
        val bmp = cached ?: render(c, ai, px, style).also { b ->
            runCatching { file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        synchronized(memory) { memory[key] = bmp }
        return bmp
    }

    /** Removes cached icons that no installed app uses any more. */
    fun prune(c: Context, keep: Set<String>) {
        File(c.cacheDir, "icons").listFiles()?.forEach { if (it.nameWithoutExtension !in keep) it.delete() }
    }

    fun fileKey(ai: LauncherActivityInfo, px: Int, style: String, updated: Long) =
        sha1("${ai.componentName.flattenToShortString()}|${ai.user.hashCode()}|$updated|$style|$px|$VERSION")

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun render(c: Context, ai: LauncherActivityInfo, px: Int, style: String): Bitmap {
        val density = c.resources.displayMetrics.densityDpi
        val pkg = ai.componentName.packageName
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val pad = px * .06f
        val box = RectF(pad, pad * .6f, px - pad, px - pad * 1.4f)   // leave room for the shadow below
        val art: Bitmap = suiteArt(c, pkg, px)?.let { a ->
            if (style == "tiles") tile(px) { cv, r -> drawFitted(cv, BitmapDrawable(c.resources, a), r.insetBy(.14f)) } else fitted(a, px)
        } ?: run {
            val d = runCatching { ai.getIcon(density) }.getOrNull() ?: c.packageManager.defaultActivityIcon
            if (d is AdaptiveIconDrawable) adaptive(d, px, style) else fittedDrawable(d, px)
        }
        // soft Aqua drop shadow
        val shadow = art.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(px * .035f, BlurMaskFilter.Blur.NORMAL) }, null)
        val sp = Paint().apply { alpha = 95 }
        canvas.save()
        canvas.translate(box.left, box.top)
        canvas.scale(box.width() / art.width, box.height() / art.height)
        canvas.drawBitmap(shadow, -px * .035f, px * .02f, sp)
        canvas.drawBitmap(art, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
        shadow.recycle()
        if (ai.user != Process.myUserHandle()) {
            return runCatching { c.packageManager.getUserBadgedIcon(BitmapDrawable(c.resources, out), ai.user).let { d -> toBitmap(d, px) } }.getOrDefault(out)
        }
        return out
    }

    /** The full illustration bundled in every Wonderdesk app. */
    private fun suiteArt(c: Context, pkg: String, px: Int): Bitmap? {
        if (!pkg.startsWith("dev.wonderdesk.")) return null
        return runCatching {
            val res = c.packageManager.getResourcesForApplication(pkg)
            val id = listOf("wonderdesk_${pkg.substringAfterLast('.')}_icon", "wonderdesk_icon").map { res.getIdentifier(it, "drawable", pkg) }.first { it != 0 }
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeResource(res, id, opts)
            opts.inSampleSize = maxOf(1, minOf(opts.outWidth, opts.outHeight) / (px * 2))
            opts.inJustDecodeBounds = false
            android.graphics.BitmapFactory.decodeResource(res, id, opts)?.let { dev.wonderdesk.shared.trimTransparent(it) }
        }.getOrNull()
    }

    private fun adaptive(d: AdaptiveIconDrawable, px: Int, style: String): Bitmap {
        val transparentBg = d.background == null || coverage(d.background) < .35f
        // The visible part of an adaptive icon is the middle 2/3 of each layer.
        fun drawLayers(cv: Canvas, r: RectF, withBg: Boolean) {
            val w = r.width() * 1.5f; val l = r.centerX() - w / 2; val t = r.centerY() - w / 2
            if (withBg) d.background?.let { it.setBounds(l.toInt(), t.toInt(), (l + w).toInt(), (t + w).toInt()); it.draw(cv) }
            d.foreground?.let { it.setBounds(l.toInt(), t.toInt(), (l + w).toInt(), (t + w).toInt()); it.draw(cv) }
        }
        return when {
            style == "natural" && transparentBg -> Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b -> drawLayers(Canvas(b), RectF(0f, 0f, px.toFloat(), px.toFloat()), false) }
            transparentBg -> tile(px) { cv, r -> drawLayers(cv, r, false) }
            else -> rounded(px) { cv, r -> drawLayers(cv, r, true) }
        }
    }

    /** Rounded-square mask, the Aqua icon shape. */
    private fun rounded(px: Int, draw: (Canvas, RectF) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val r = RectF(0f, 0f, px.toFloat(), px.toFloat())
        cv.clipPath(Path().apply { addRoundRect(r, px * .225f, px * .225f, Path.Direction.CW) })
        draw(cv, r)
        return b
    }

    /** Glossy light tile behind artwork that has no background of its own. */
    private fun tile(px: Int, draw: (Canvas, RectF) -> Unit): Bitmap = rounded(px) { cv, r ->
        cv.drawRect(r, Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, r.height(), 0xFFFDFEFF.toInt(), 0xFFD5DEE8.toInt(), android.graphics.Shader.TileMode.CLAMP) })
        cv.drawOval(RectF(-r.width() * .3f, -r.height() * .75f, r.width() * 1.3f, r.height() * .48f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF })
        draw(cv, r)
    }

    private fun RectF.insetBy(f: Float) = RectF(left + width() * f, top + height() * f, right - width() * f, bottom - height() * f)

    private fun fitted(a: Bitmap, px: Int): Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
        val s = minOf(px.toFloat() / a.width, px.toFloat() / a.height); val w = a.width * s; val h = a.height * s
        Canvas(b).drawBitmap(a, null, RectF((px - w) / 2, (px - h) / 2, (px + w) / 2, (px + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun fittedDrawable(d: Drawable, px: Int): Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
        drawFitted(Canvas(b), d, RectF(0f, 0f, px.toFloat(), px.toFloat()))
    }

    private fun drawFitted(cv: Canvas, d: Drawable, r: RectF) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: r.width().toInt()
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: r.height().toInt()
        val s = minOf(r.width() / iw, r.height() / ih)
        val w = iw * s; val h = ih * s
        d.setBounds((r.centerX() - w / 2).toInt(), (r.centerY() - h / 2).toInt(), (r.centerX() + w / 2).toInt(), (r.centerY() + h / 2).toInt())
        (d as? BitmapDrawable)?.paint?.isFilterBitmap = true
        d.draw(cv)
    }

    private fun toBitmap(d: Drawable, px: Int): Bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, px, px); d.draw(Canvas(b)) }

    /** Fraction of a layer that is opaque (sampled small). */
    private fun coverage(d: Drawable): Float {
        val b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 24, 24); d.draw(Canvas(b))
        var n = 0
        for (y in 0 until 24) for (x in 0 until 24) if ((b.getPixel(x, y) ushr 24) > 128) n++
        b.recycle()
        return n / 576f
    }
}
