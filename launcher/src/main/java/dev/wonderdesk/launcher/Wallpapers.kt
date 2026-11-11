package dev.wonderdesk.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import kotlin.math.max
import kotlin.math.sin

/**
 * Wonderdesk's built-in wallpapers: original Aqua-style artwork drawn on the phone at its exact
 * resolution (no images shipped, nothing downloaded), plus helpers to set a photo as wallpaper.
 */
object Wallpapers {
    data class Style(val id: String, val name: String)

    val STYLES = listOf(
        Style("aqua", "Aqua"), Style("aqua_dark", "Aqua Night"), Style("graphite", "Graphite"), Style("aurora", "Aurora"),
        Style("lagoon", "Lagoon"), Style("pinstripe", "Pinstripe"), Style("dawn", "Dawn"), Style("ember", "Ember"),
    )

    fun render(id: String, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun bg(vararg colors: Int) { p.shader = LinearGradient(0f, 0f, w * .35f, h.toFloat(), colors, null, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null }
        fun glow(x: Float, y: Float, r: Float, color: Int) { p.shader = RadialGradient(x, y, r, intArrayOf(color, Color.TRANSPARENT), null, Shader.TileMode.CLAMP); c.drawCircle(x, y, r, p); p.shader = null }
        /** A soft flowing ribbon across the screen, the signature Aqua swoosh. */
        fun ribbon(y0: Float, amp: Float, thick: Float, phase: Float, color: Int, blur: Float) {
            val path = Path(); val steps = 48
            for (i in 0..steps) { val x = w * i / steps.toFloat(); val y = y0 + amp * sin(i / steps.toFloat() * 3.4f + phase); if (i == 0) path.moveTo(x - w * .1f, y) else path.lineTo(x, y) }
            for (i in steps downTo 0) { val x = w * i / steps.toFloat(); val y = y0 + thick + amp * .8f * sin(i / steps.toFloat() * 3.1f + phase + .6f); path.lineTo(x, y) }
            path.close()
            val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; if (blur > 0) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
            c.drawPath(path, rp)
        }
        val m = max(w, h).toFloat()
        when (id) {
            "aqua" -> {
                bg(0xFF0B2E73.toInt(), 0xFF1560C9.toInt(), 0xFF3B9AF0.toInt())
                glow(w * .75f, h * .2f, m * .55f, 0x553FB6FF)
                ribbon(h * .42f, h * .06f, h * .16f, 0f, 0x667FD4FF, m * .02f)
                ribbon(h * .5f, h * .05f, h * .07f, 1.2f, 0x99D8F1FF.toInt(), m * .008f)
                ribbon(h * .58f, h * .07f, h * .12f, 2.1f, 0x443FA9FF, m * .03f)
                glow(w * .2f, h * .85f, m * .45f, 0x55001A4D)
            }
            "aqua_dark" -> {
                bg(0xFF020B1F.toInt(), 0xFF07224F.toInt(), 0xFF0E3A80.toInt())
                ribbon(h * .46f, h * .06f, h * .14f, .4f, 0x552C8CFF, m * .03f)
                ribbon(h * .52f, h * .05f, h * .05f, 1.7f, 0x8896D2FF.toInt(), m * .008f)
                glow(w * .5f, h * .5f, m * .5f, 0x33246BFF)
            }
            "graphite" -> {
                bg(0xFF2B3038.toInt(), 0xFF4A525E.toInt(), 0xFF7D8794.toInt())
                ribbon(h * .44f, h * .05f, h * .15f, .8f, 0x55C9D3DE, m * .025f)
                ribbon(h * .52f, h * .04f, h * .05f, 2f, 0x88EEF2F6.toInt(), m * .007f)
            }
            "aurora" -> {
                bg(0xFF05030F.toInt(), 0xFF120A2E.toInt(), 0xFF1A0F3D.toInt())
                ribbon(h * .30f, h * .09f, h * .2f, .2f, 0x66B01EFF, m * .05f)
                ribbon(h * .40f, h * .08f, h * .14f, 1.4f, 0x5536A8FF, m * .04f)
                ribbon(h * .48f, h * .07f, h * .06f, 2.3f, 0x8800E5C8.toInt(), m * .015f)
                ribbon(h * .55f, h * .06f, h * .03f, 3f, 0xAAFF7AD9.toInt(), m * .006f)
                val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val rnd = java.util.Random(7)
                repeat(260) { star.alpha = 40 + rnd.nextInt(180); c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, rnd.nextFloat() * m * .0016f + .5f, star) }
            }
            "lagoon" -> {
                bg(0xFF004E64.toInt(), 0xFF00A5A8.toInt(), 0xFF7EE0D2.toInt())
                glow(w * .3f, h * .25f, m * .5f, 0x55FFFFFF)
                ribbon(h * .55f, h * .05f, h * .2f, .5f, 0x44003A55, m * .03f)
                ribbon(h * .6f, h * .04f, h * .04f, 1.9f, 0x88E8FFFB.toInt(), m * .008f)
            }
            "pinstripe" -> {
                bg(0xFFF4F6F8.toInt(), 0xFFE6EAEE.toInt(), 0xFFD8DEE4.toInt())
                val line = Paint().apply { color = 0x10000000; strokeWidth = max(1f, w / 540f) }
                var y = 0f; val step = max(3f, w / 270f)
                while (y < h) { c.drawLine(0f, y, w.toFloat(), y, line); y += step }
                glow(w * .5f, h * .35f, m * .6f, 0x66FFFFFF)
            }
            "dawn" -> {
                bg(0xFF2E3A87.toInt(), 0xFFC86BA8.toInt(), 0xFFFFC38A.toInt())
                glow(w * .7f, h * .78f, m * .45f, 0x88FFE7B0.toInt())
                ribbon(h * .5f, h * .06f, h * .12f, 1f, 0x44FFFFFF, m * .03f)
            }
            else -> { // ember
                bg(0xFF1F0A05.toInt(), 0xFF6B1B0B.toInt(), 0xFFD9531E.toInt())
                ribbon(h * .45f, h * .07f, h * .15f, .6f, 0x55FF9A3D, m * .035f)
                ribbon(h * .53f, h * .05f, h * .04f, 2.2f, 0x99FFD08A.toInt(), m * .008f)
            }
        }
        return b
    }

    fun thumbnail(id: String) = render(id, 180, 320)

    /** [which]: WallpaperManager.FLAG_SYSTEM, FLAG_LOCK, or both. */
    fun apply(c: Context, bmp: Bitmap, which: Int) {
        WallpaperManager.getInstance(c).setBitmap(bmp, null, true, which)
    }

    fun applyStyle(c: Context, id: String, which: Int) {
        val dm = c.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels); val h = maxOf(dm.widthPixels, dm.heightPixels)
        apply(c, render(id, w, h), which)
    }

    /** A picked photo, scaled to cover the screen. */
    fun applyPhoto(c: Context, uri: Uri, which: Int) {
        val dm = c.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels); val h = maxOf(dm.widthPixels, dm.heightPixels)
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1; while (opts.outWidth / (sample * 2) >= w && opts.outHeight / (sample * 2) >= h) sample *= 2
        val raw = c.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Couldn't read that picture")
        val scale = max(w / raw.width.toFloat(), h / raw.height.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.translate((w - raw.width * scale) / 2, (h - raw.height * scale) / 2); cv.scale(scale, scale)
        cv.drawBitmap(raw, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        apply(c, out, which)
    }
}
