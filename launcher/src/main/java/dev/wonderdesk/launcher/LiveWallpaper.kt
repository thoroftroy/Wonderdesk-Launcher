package dev.wonderdesk.launcher

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ======================================================================================================
 * What the wallpaper is, for each of the three places it can be set: one wallpaper for all day, or
 * separate day and night ones. Each place holds a Source: a Wonderdesk style (including the animated Aqua
 * bubbles), one photo, a slideshow of photos, or a looping video.
 * ==================================================================================================== */

data class Source(val kind: String, val id: String = "", val files: List<String> = emptyList(), val seconds: Int = 30) {
    val animated get() = (kind == "style" && id.startsWith("bubbles")) || kind == "video" || (kind == "photos" && files.size > 1)
    val signature get() = "$kind|$id|${files.joinToString(",")}|$seconds"

    fun toJson() = JSONObject().put("kind", kind).put("id", id).put("files", JSONArray(files)).put("seconds", seconds)

    fun describe(): String = when (kind) {
        "style" -> (Wallpapers.STYLES.firstOrNull { it.id == id }?.name ?: "Wonderdesk style") + if (animated) " (animated)" else ""
        "photos" -> if (files.size > 1) "Slideshow of ${files.size} photos, changes every ${if (seconds < 60) "$seconds seconds" else if (seconds < 3600) "${seconds / 60} minutes" else "hour"}" else "Your photo"
        "video" -> "Your video, looping"
        else -> "Not set"
    }

    companion object {
        fun fromJson(o: JSONObject) = Source(o.optString("kind", "style"), o.optString("id"), o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optInt("seconds", 30))
        val DAY = Source("style", "bubbles")
        val NIGHT = Source("style", "bubbles_night")
    }
}

object WpConfig {
    const val ACTION_RELOAD = "dev.wonderdesk.launcher.WALLPAPER_RELOAD"
    private fun prefs(c: Context) = c.getSharedPreferences("livewp", Context.MODE_PRIVATE)
    fun dir(c: Context) = File(c.filesDir, "wp").apply { mkdirs() }

    /** Separate day and night wallpapers (on by default) or one for the whole day. */
    fun dayNight(c: Context) = prefs(c).getBoolean("dayNight", true)
    fun setDayNight(c: Context, on: Boolean) { prefs(c).edit().putBoolean("dayNight", on).apply(); changed(c) }

    fun source(c: Context, slot: String): Source {
        prefs(c).getString("src_$slot", null)?.let { raw -> runCatching { return Source.fromJson(JSONObject(raw)) } }
        // Wallpapers saved by older versions as day or night pictures.
        val legacy = File(c.filesDir, "wallpaper-$slot.png")
        if (legacy.isFile) return Source("photos", files = listOf(legacy.absolutePath))
        return when (slot) { "night" -> Source.NIGHT; else -> Source.DAY }
    }

    fun setSource(c: Context, slot: String, s: Source) { prefs(c).edit().putString("src_$slot", s.toJson().toString()).apply(); changed(c) }

    /** The slot showing right now. */
    fun currentSlot(c: Context) = if (!dayNight(c)) "all" else if (DayNight.isNight(c)) "night" else "day"
    fun current(c: Context): Source = source(c, currentSlot(c)).let { if (currentSlot(c) == "all" && prefs(c).getString("src_all", null) == null) source(c, "day") else it }

    /** Anything that needs the animated wallpaper to be switched on. */
    fun needsLive(c: Context): Boolean = (if (dayNight(c)) listOf("day", "night") else listOf("all")).any { s -> source(c, s).let { it.animated } }

    fun liveActive(c: Context): Boolean = runCatching { WallpaperManager.getInstance(c).wallpaperInfo?.packageName == c.packageName }.getOrDefault(false)

    fun changed(c: Context) {
        c.sendBroadcast(Intent(ACTION_RELOAD).setPackage(c.packageName))
        Thread { runCatching { DayNight.applyNow(c) } }.start()
    }

    /** What to paint when the animated wallpaper is not the active one: the current look as one picture. */
    fun staticBitmap(c: Context, s: Source, w: Int, h: Int): Bitmap = when (s.kind) {
        "photos" -> s.files.firstOrNull()?.let { loadCover(it, w, h) } ?: Wallpapers.render("aqua", w, h)
        "video" -> s.files.firstOrNull()?.let { f -> runCatching {
            val r = android.media.MediaMetadataRetriever(); r.setDataSource(f)
            val frame = r.getFrameAtTime(1_000_000) ?: r.getFrameAtTime(0); r.release()
            frame?.let { cover(it, w, h) }
        }.getOrNull() } ?: Wallpapers.render("aqua", w, h)
        else -> if (s.id.startsWith("bubbles")) Wallpapers.renderBubbles(s.id == "bubbles_night", w, h) else Wallpapers.render(s.id.ifBlank { "aqua" }, w, h)
    }

    fun loadCover(path: String, w: Int, h: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var sample = 1; while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2
        val raw = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return cover(raw, w, h)
    }

    fun cover(raw: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w / raw.width.toFloat(), h / raw.height.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.translate((w - raw.width * scale) / 2, (h - raw.height * scale) / 2); cv.scale(scale, scale)
        cv.drawBitmap(raw, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun switchToLive(c: Context): Intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(c, WonderdeskWallpaper::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/* ====================================================================================================
 * Bubbles
 * ================================================================================================== */

/** Soft bubbles drifting up through the water. Sprites are drawn once and scaled, so a frame costs very little. */
class BubbleField(private val w: Int, private val h: Int, count: Int = 26, seed: Long = 11) {
    private class B(var x: Float, var y: Float, val r: Float, val speed: Float, val sway: Float, val phase: Float, val alpha: Int)
    private val rnd = Random(seed)
    private val m = max(w, h).toFloat()
    private val bubbles = List(count) { spawn(true) }
    private val sprite: Bitmap = makeSprite(192)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect(0, 0, sprite.width, sprite.height)
    private val dst = RectF()
    private var t = 0f

    private fun spawn(anywhere: Boolean): B {
        val r = m * (.006f + rnd.nextFloat() * rnd.nextFloat() * .03f)
        return B(rnd.nextFloat() * w, if (anywhere) rnd.nextFloat() * h else h + r * 2 + rnd.nextFloat() * h * .2f, r,
            m * (.025f + rnd.nextFloat() * .05f) * (.6f + r / (m * .02f) * .5f), w * (.006f + rnd.nextFloat() * .018f), rnd.nextFloat() * 6.28f, 90 + rnd.nextInt(150))
    }

    private fun makeSprite(n: Int): Bitmap {
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = n / 2f
        // body: nearly clear in the middle, brighter toward the rim, like a real bubble
        p.shader = RadialGradient(r, r, r, intArrayOf(0x08FFFFFF, 0x14BFE9FF, 0x55E3F6FF, 0xCCFFFFFF.toInt()), floatArrayOf(0f, .6f, .88f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r - 1f, p)
        // highlight crescent top left and a small reflection bottom right
        p.shader = null; p.color = 0xCCFFFFFF.toInt()
        c.drawArc(RectF(n * .16f, n * .16f, n * .62f, n * .62f), 190f, 70f, false, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = n * .045f; color = 0xDDFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND })
        p.color = 0x66FFFFFF; c.drawCircle(n * .72f, n * .72f, n * .035f, p)
        return b
    }

    fun step(dt: Float) {
        t += dt
        for (b in bubbles) {
            b.y -= b.speed * dt
            if (b.y < -b.r * 3) { val n = spawn(false); b.x = n.x; b.y = n.y }
        }
    }

    fun draw(c: Canvas) {
        for (b in bubbles) {
            val x = b.x + sin(t * .6f + b.phase) * b.sway
            dst.set(x - b.r, b.y - b.r, x + b.r, b.y + b.r)
            paint.alpha = b.alpha
            c.drawBitmap(sprite, src, dst, paint)
        }
    }

    /** One frame with the bubbles spread out, for previews and the still wallpaper. */
    fun drawStill(c: Canvas) { step(.001f); draw(c) }
}

/* ====================================================================================================
 * The live wallpaper
 * ================================================================================================== */

class WonderdeskWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = WpEngine()

    inner class WpEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var w = 0
        private var h = 0
        private var visible = false
        private var src: Source? = null
        private var water: Bitmap? = null
        private var field: BubbleField? = null
        private var photos: List<String> = emptyList()
        private var index = 0
        private var shown: Bitmap? = null
        private var incoming: Bitmap? = null
        private var fade = 1f
        private var nextAt = 0L
        private var player: MediaPlayer? = null
        private var last = 0L
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var receiver: BroadcastReceiver? = null
        // A surface that has been drawn on with a canvas cannot be handed to the video player, and the other way round,
        // so switching between the two asks Android for a fresh surface first.
        private var canvasTouched = false
        private var videoTouched = false
        private var flip = false

        private val frame = object : Runnable { override fun run() { if (!visible) return; drawFrame(); handler.postDelayed(this, if (needsFast()) 33 else 400) } }
        private val clock = object : Runnable { override fun run() { reselect(); handler.postDelayed(this, 60_000) } }

        private fun needsFast(): Boolean { val s = src ?: return false; return s.kind == "style" && s.id.startsWith("bubbles") || incoming != null }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { src = null; reselect() } }
            val f = IntentFilter(WpConfig.ACTION_RELOAD)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
        }

        override fun onDestroy() {
            handler.removeCallbacksAndMessages(null)
            receiver?.let { runCatching { unregisterReceiver(it) } }
            releasePlayer()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width != w || height != h) { w = width; h = height; src = null }
            reselect()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(frame); handler.removeCallbacks(clock)
            if (v) {
                reselect()
                last = System.nanoTime()
                handler.post(frame); handler.postDelayed(clock, 60_000)
                player?.let { runCatching { it.start() } }
            } else player?.let { runCatching { it.pause() } }
        }

        /** Chooses what to show for the time of day and sets it up, only when it changed. */
        private fun reselect() {
            if (w == 0 || h == 0) return
            var want = WpConfig.current(this@WonderdeskWallpaper)
            // A video that has gone missing falls back to the bubbles instead of a blank screen.
            if (want.kind == "video" && want.files.firstOrNull()?.let { File(it).isFile } != true) want = Source.DAY
            if (src?.signature == want.signature) return
            val video = want.kind == "video"
            if ((video && canvasTouched) || (!video && videoTouched)) {
                releasePlayer(); src = null; canvasTouched = false; videoTouched = false
                flip = !flip
                surfaceHolder.setFormat(if (flip) android.graphics.PixelFormat.RGBA_8888 else android.graphics.PixelFormat.RGBX_8888)
                return   // onSurfaceChanged calls this again once the new surface is there
            }
            src = want
            releasePlayer(); shown = null; incoming = null; water = null; field = null; photos = emptyList()
            when (want.kind) {
                "style" -> if (want.id.startsWith("bubbles")) {
                    val night = want.id == "bubbles_night"
                    water = Wallpapers.renderWater(night, w, h); field = BubbleField(w, h, seed = if (night) 5 else 11)
                } else shown = Wallpapers.render(want.id.ifBlank { "aqua" }, w, h)
                "photos" -> {
                    photos = want.files.filter { File(it).isFile }
                    index = 0
                    shown = photos.firstOrNull()?.let { WpConfig.loadCover(it, w, h) } ?: Wallpapers.render("aqua", w, h)
                    nextAt = System.currentTimeMillis() + want.seconds * 1000L
                }
                "video" -> startVideo(want.files.firstOrNull())
                else -> shown = Wallpapers.render("aqua", w, h)
            }
            drawFrame()
        }

        private fun startVideo(path: String?) {
            if (path == null) return
            // Nothing is drawn with a canvas while a video plays: the surface belongs to the player.
            runCatching {
                val mp = MediaPlayer()
                mp.setDataSource(path)
                mp.setSurface(surfaceHolder.surface); videoTouched = true
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                mp.setOnPreparedListener { p -> if (visible) p.start() }
                mp.prepareAsync()
                player = mp
            }.onFailure { releasePlayer() }
        }

        private fun releasePlayer() { player?.let { runCatching { it.stop() }; runCatching { it.release() } }; player = null }

        private fun drawFrame() {
            val holder = surfaceHolder
            val s = src ?: return
            val now = System.nanoTime(); val dt = ((now - last) / 1e9f).coerceIn(0f, .1f); last = now
            if (s.kind == "video") return   // the player owns the surface
            if (s.kind == "photos" && photos.size > 1) advanceSlideshow(dt)
            field?.step(dt)
            val c = runCatching { if (android.os.Build.VERSION.SDK_INT >= 26) holder.surface.lockHardwareCanvas() else holder.lockCanvas() }.getOrNull() ?: runCatching { holder.lockCanvas() }.getOrNull() ?: return
            canvasTouched = true
            try {
                val bg = water
                if (bg != null) c.drawBitmap(bg, 0f, 0f, null) else shown?.let { c.drawBitmap(it, 0f, 0f, null) } ?: c.drawColor(Color.BLACK)
                incoming?.let { paint.alpha = (fade * 255).toInt().coerceIn(0, 255); c.drawBitmap(it, 0f, 0f, paint); paint.alpha = 255 }
                field?.draw(c)
            } finally { runCatching { holder.surface.unlockCanvasAndPost(c) } }
        }

        private fun advanceSlideshow(dt: Float) {
            val inc = incoming
            if (inc != null) {
                fade += dt / 1.2f
                if (fade >= 1f) { shown = inc; incoming = null; fade = 1f }
                return
            }
            if (System.currentTimeMillis() >= nextAt) {
                index = (index + 1) % photos.size
                val next = WpConfig.loadCover(photos[index], w, h)
                nextAt = System.currentTimeMillis() + (src?.seconds ?: 30) * 1000L
                if (next != null) { incoming = next; fade = 0f }
            }
        }
    }
}
