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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ======================================================================================================
 * What the wallpaper is, for each of the three places it can be set: one wallpaper for all day, or
 * separate day and night ones. Each place holds a Source: a Wonderdesk style (including the animated Aqua
 * bubbles), one photo, a slideshow of photos, or a looping video.
 * ==================================================================================================== */

data class Source(val kind: String, val id: String = "", val files: List<String> = emptyList(), val seconds: Int = 30) {
    val animated get() = (kind == "style" && id.startsWith("bubbles")) || kind == "scene" || kind == "video" || (kind == "photos" && files.size > 1)
    val signature get() = "$kind|$id|${files.joinToString(",")}|$seconds"

    fun toJson() = JSONObject().put("kind", kind).put("id", id).put("files", JSONArray(files)).put("seconds", seconds)

    fun describe(): String = when (kind) {
        "scene" -> when (id) { "theme", "" -> "Dynamic scene for your theme"; "custom" -> "Your custom scene"; else -> SceneConfig.name(id) + " dynamic scene" }
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
    fun dayNight(c: Context) = prefs(c).getBoolean("dayNight", false)
    fun setDayNight(c: Context, on: Boolean) { prefs(c).edit().putBoolean("dayNight", on).apply(); changed(c) }

    fun source(c: Context, slot: String): Source {
        prefs(c).getString("src_$slot", null)?.let { raw -> runCatching { return Source.fromJson(JSONObject(raw)) } }
        // Wallpapers saved by older versions as day or night pictures.
        val legacy = File(c.filesDir, "wallpaper-$slot.png")
        if (legacy.isFile) return Source("photos", files = listOf(legacy.absolutePath))
        return when (slot) { "night" -> Source.NIGHT; "all" -> Source("scene", "theme"); else -> Source.DAY }
    }

    /** The lock screen can show its own look; by default it matches the home screen. */
    fun lockSame(c: Context) = prefs(c).getBoolean("lockSame", true)
    fun setLockSame(c: Context, v: Boolean) { prefs(c).edit().putBoolean("lockSame", v).apply(); changed(c) }
    fun lockSource(c: Context): Source = if (lockSame(c)) current(c) else source(c, "lock").let { if (prefs(c).getString("src_lock", null) == null) Source("scene", "theme") else it }

    fun setSource(c: Context, slot: String, s: Source) { prefs(c).edit().putString("src_$slot", s.toJson().toString()).apply(); changed(c) }

    /** The slot showing right now. */
    fun currentSlot(c: Context) = if (!dayNight(c)) "all" else if (DayNight.isNight(c)) "night" else "day"
    fun current(c: Context): Source = source(c, currentSlot(c))

    /** Anything that needs the animated wallpaper to be switched on. */
    fun needsLive(c: Context): Boolean = (if (dayNight(c)) listOf("day", "night") else listOf("all")).any { s -> source(c, s).let { it.animated } }

    fun liveActive(c: Context): Boolean = runCatching { WallpaperManager.getInstance(c).wallpaperInfo?.packageName == c.packageName }.getOrDefault(false)

    /** True when the lock screen is showing the animated wallpaper (it has no picture of its own). */
    fun lockIsLive(c: Context): Boolean = runCatching {
        val wm = WallpaperManager.getInstance(c)
        liveActive(c) && (android.os.Build.VERSION.SDK_INT < 24 || wm.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0 || (android.os.Build.VERSION.SDK_INT >= 34 && wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.packageName == c.packageName))
    }.getOrDefault(false)

    /** Drops any separate picture on the lock screen so it shows the animated wallpaper too. */
    fun lockUseLive(c: Context): Boolean = runCatching { WallpaperManager.getInstance(c).clear(WallpaperManager.FLAG_LOCK); true }.getOrDefault(false)

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
        "scene" -> SceneRunner.still(SceneConfig.resolve(c, s.id), w, h)
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
class BubbleField(private val w: Int, private val h: Int, count: Int = 26, seed: Long = 11, private val popping: Boolean = false) {
    private class B(var x: Float, var y: Float, val r: Float, val speed: Float, val sway: Float, val phase: Float, val alpha: Int)
    private val rnd = Random(seed)
    private val m = max(w, h).toFloat()
    private val bubbles = List(count) { spawn(true) }
    private val sprite: Bitmap = makeSprite(192)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect(0, 0, sprite.width, sprite.height)
    private val dst = RectF()
    private var t = 0f
    private class Pop(val x: Float, val y: Float, val r: Float) { var age = 0f; val drops = List(7) { floatArrayOf((it * 51.4f + 20f) * 0.01745f, .6f + (it % 3) * .25f) } }
    private val pops = ArrayList<Pop>()

    /** A tap: if it lands on a bubble, the bubble bursts into a ring and little drops, and a new one rises later. */
    fun popAt(px: Float, py: Float): Boolean {
        if (!popping) return false
        var best: B? = null; var bd = Float.MAX_VALUE
        for (b in bubbles) { val x = b.x + sin(t * .6f + b.phase) * b.sway; val d = (x - px) * (x - px) + (b.y - py) * (b.y - py); val reach = b.r + m0 * .03f; if (d < reach * reach && d < bd) { bd = d; best = b } }
        val b = best ?: return false
        pops.add(Pop(b.x + sin(t * .6f + b.phase) * b.sway, b.y, b.r))
        val n = spawn(false); b.x = n.x; b.y = n.y + h * .3f
        return true
    }
    private val m0 get() = max(w, h).toFloat()

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
        val pi = pops.iterator(); while (pi.hasNext()) { val q = pi.next(); q.age += dt; if (q.age > .6f) pi.remove() }
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
        if (pops.isNotEmpty()) {
            val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
            for (q in pops) {
                val f = q.age / .6f; val a = ((1f - f) * 230).toInt()
                pp.strokeWidth = max(2f, q.r * .12f * (1f - f)); pp.color = Color.argb(a, 235, 248, 255)
                c.drawCircle(q.x, q.y, q.r * (1f + f * 1.1f), pp)
                pp.style = Paint.Style.FILL
                for (d in q.drops) { val dist = q.r * (1f + f * 2.4f * d[1]); pp.color = Color.argb(a, 235, 248, 255); c.drawCircle(q.x + cos(d[0]) * dist, q.y + sin(d[0]) * dist + f * f * q.r, max(1.5f, q.r * .14f * (1f - f)), pp) }
                pp.style = Paint.Style.STROKE
            }
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
        private var runner: SceneRunner? = null
        private var sceneCfg = SceneConfig()
        private var lockLook = LockLook()
        private var isLock = false
        private var lockedNow = false
        private var battery = 100
        private var charging = false
        private var batteryAt = 0L
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

        private fun needsFast(): Boolean { val s = src ?: return false; return s.kind == "scene" || s.kind == "style" && s.id.startsWith("bubbles") || incoming != null }

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
            lockedNow = runCatching { getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)
            var want = if (lockedNow) WpConfig.lockSource(this@WonderdeskWallpaper) else WpConfig.current(this@WonderdeskWallpaper)
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
            releasePlayer(); shown = null; incoming = null; water = null; field = null; photos = emptyList(); runner = null
            lockLook = LockLook.load(this@WonderdeskWallpaper)
            isLock = runCatching { android.os.Build.VERSION.SDK_INT >= 31 && (wallpaperFlags and android.app.WallpaperManager.FLAG_LOCK) != 0 && (wallpaperFlags and android.app.WallpaperManager.FLAG_SYSTEM) == 0 }.getOrDefault(false)
            when (want.kind) {
                "scene" -> { sceneCfg = SceneConfig.resolve(this@WonderdeskWallpaper, want.id); runner = SceneRunner(w, h) }
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
            // Locking or unlocking the phone can switch the look.
            val lockedPeek = runCatching { getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true }.getOrDefault(false)
            if (lockedPeek != lockedNow && !WpConfig.lockSame(this@WonderdeskWallpaper)) { src = null; reselect(); return }
            if (s.kind == "video") return   // the player owns the surface
            if (s.kind == "photos" && photos.size > 1) advanceSlideshow(dt)
            field?.step(dt)
            runner?.let { r ->
                if (System.currentTimeMillis() - batteryAt > 30_000) { batteryAt = System.currentTimeMillis(); runCatching { val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!; battery = b.getIntExtra("level", 100) * 100 / b.getIntExtra("scale", 100).coerceAtLeast(1); charging = b.getIntExtra("plugged", 0) != 0 } }
                r.update(dt, sceneCfg, battery, charging)
            }
            val c = runCatching { if (android.os.Build.VERSION.SDK_INT >= 26) holder.surface.lockHardwareCanvas() else holder.lockCanvas() }.getOrNull() ?: runCatching { holder.lockCanvas() }.getOrNull() ?: return
            canvasTouched = true
            try {
                val bg = water
                if (bg != null) c.drawBitmap(bg, 0f, 0f, null) else shown?.let { c.drawBitmap(it, 0f, 0f, null) } ?: c.drawColor(Color.BLACK)
                incoming?.let { paint.alpha = (fade * 255).toInt().coerceIn(0, 255); c.drawBitmap(it, 0f, 0f, paint); paint.alpha = 255 }
                field?.draw(c)
                runner?.draw(c, sceneCfg)
                if (isLock || (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true)) LockDraw.draw(c, this@WonderdeskWallpaper, w, h, lockLook)
            } finally { runCatching { holder.surface.unlockCanvasAndPost(c) } }
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: android.os.Bundle?, resultRequested: Boolean): android.os.Bundle? {
            if (action == android.app.WallpaperManager.COMMAND_TAP) runner?.tap(x.toFloat(), y.toFloat(), sceneCfg)
            return null
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

/* ====================================================================================================
 * Lock screen look: a clock layout drawn on the wallpaper when it is the lock screen's.
 * ================================================================================================== */

data class LockLook(val layout: String = "center", val style: String = "thin", val date: Boolean = true, val battery: Boolean = true, val alarm: Boolean = true, val show: Boolean = false) {
    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("lock", Context.MODE_PRIVATE)
        fun load(c: Context) = LockLook(prefs(c).getString("layout", "center")!!, prefs(c).getString("style", "thin")!!, prefs(c).getBoolean("date", true), prefs(c).getBoolean("battery", true), prefs(c).getBoolean("alarm", true), prefs(c).getBoolean("show", false))
        fun save(c: Context, l: LockLook) { prefs(c).edit().putString("layout", l.layout).putString("style", l.style).putBoolean("date", l.date).putBoolean("battery", l.battery).putBoolean("alarm", l.alarm).putBoolean("show", l.show).apply(); c.sendBroadcast(Intent(WpConfig.ACTION_RELOAD).setPackage(c.packageName)) }
        val LAYOUTS = listOf("center" to "Centered", "left" to "Left stack", "bottom" to "Low and large", "minimal" to "Minimal")
        val STYLES = listOf("thin" to "Thin", "bold" to "Bold", "mono" to "Mono")
    }
}

object LockDraw {
    /** The clock, date and small facts, drawn over whatever the wallpaper shows. Also used for the preview. */
    fun draw(c: Canvas, ctx: Context, w: Int, h: Int, look: LockLook) {
        // Android always draws its own clock on the lock screen and apps cannot hide it, so ours is off unless asked for.
        if (!look.show) return
        val now = java.time.LocalTime.now()
        val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
        val time = if (is24) "%02d:%02d".format(now.hour, now.minute) else "%d:%02d".format(if (now.hour % 12 == 0) 12 else now.hour % 12, now.minute)
        val date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM"))
        val extras = buildList {
            if (look.battery) runCatching { val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)); val l = b!!.getIntExtra("level", -1) * 100 / b.getIntExtra("scale", 100).coerceAtLeast(1); add("$l%" + if (b.getIntExtra("plugged", 0) != 0) " charging" else "") }
            if (look.alarm) runCatching { ctx.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.let { add("Alarm " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it.triggerTime))) } }
        }.joinToString("   ")
        val m = max(w, h).toFloat()
        val tf = when (look.style) { "bold" -> android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD); "mono" -> android.graphics.Typeface.MONOSPACE; else -> android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL) }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = tf; setShadowLayer(m * .014f, 0f, m * .003f, 0xAA000000.toInt()) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 230; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL); setShadowLayer(m * .008f, 0f, m * .002f, 0x66000000) }
        when (look.layout) {
            "left" -> {
                big.textSize = m * .12f; big.textAlign = Paint.Align.LEFT; small.textSize = m * .026f; small.textAlign = Paint.Align.LEFT
                val x = w * .08f; var y = h * .52f
                c.drawText(time, x, y, big); y += m * .045f
                if (look.date) { c.drawText(date, x, y, small); y += m * .04f }
                if (extras.isNotEmpty()) c.drawText(extras, x, y, small)
            }
            "bottom" -> {
                big.textSize = m * .2f; big.textAlign = Paint.Align.CENTER; small.textSize = m * .028f; small.textAlign = Paint.Align.CENTER
                var y = h * .84f
                c.drawText(time, w / 2f, y, big); y += m * .05f
                if (look.date) { c.drawText(date, w / 2f, y, small); y += m * .04f }
                if (extras.isNotEmpty()) c.drawText(extras, w / 2f, y, small)
            }
            "minimal" -> {
                big.textSize = m * .06f; big.textAlign = Paint.Align.CENTER; small.textSize = m * .02f; small.textAlign = Paint.Align.CENTER
                var y = h * .88f
                c.drawText(time, w / 2f, y, big); y += m * .03f
                if (look.date) c.drawText(date, w / 2f, y, small)
            }
            else -> {
                big.textSize = m * .15f; big.textAlign = Paint.Align.CENTER; small.textSize = m * .028f; small.textAlign = Paint.Align.CENTER
                var y = h * .6f
                c.drawText(time, w / 2f, y, big); y += m * .05f
                if (look.date) { c.drawText(date, w / 2f, y, small); y += m * .04f }
                if (extras.isNotEmpty()) c.drawText(extras, w / 2f, y, small)
            }
        }
    }
}
