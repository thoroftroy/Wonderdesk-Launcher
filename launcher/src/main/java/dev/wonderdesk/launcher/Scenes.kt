package dev.wonderdesk.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ======================================================================================================
 * Dynamic scenes: Aqua (water and bubbles), Bliss (a grass field under a moving sky) and Super (space,
 * rainbows and glowing rings). Colours move smoothly with the time of day, things happen at random, and
 * a tap pops a bubble or ripples the grass. A custom scene is one of these with your own rules.
 * ==================================================================================================== */

/** Conditions in a custom scene's rules. */
data class Rule(val whenKind: String, val a: Int, val b: Int, val base: String, val hue: Int) {
    fun toJson() = JSONObject().put("when", whenKind).put("a", a).put("b", b).put("base", base).put("hue", hue)
    fun label(): String = when (whenKind) {
        "hours" -> "Between $a:00 and $b:00"
        "charging" -> "While charging"
        "battery" -> "Battery below $a%"
        else -> whenKind
    } + " → " + (if (base.isNotEmpty()) SceneConfig.name(base) else "same scene") + (if (hue != 0) ", colours shifted $hue°" else "")
    companion object { fun from(o: JSONObject) = Rule(o.optString("when"), o.optInt("a"), o.optInt("b"), o.optString("base"), o.optInt("hue")) }
}

/** Everything that can be changed about a scene. */
data class SceneConfig(
    /** aqua, bliss, super */
    val base: String = "aqua",
    /** -1 follows the clock; otherwise a fixed hour of the day. */
    val fixedHour: Float = -1f,
    /** How fast the day goes: 1 is real time, 60 is a day per 24 minutes. */
    val daySpeed: Int = 1,
    val hueShift: Int = 0,
    val events: Boolean = true,
    /** 1 rare, 2 now and then, 3 often. */
    val eventRate: Int = 2,
    val batteryTint: Boolean = false,
    val chargingGlow: Boolean = false,
    val rules: List<Rule> = emptyList(),
) {
    fun toJson() = JSONObject().put("base", base).put("fixedHour", fixedHour.toDouble()).put("speed", daySpeed).put("hue", hueShift).put("events", events).put("rate", eventRate)
        .put("batteryTint", batteryTint).put("chargingGlow", chargingGlow).put("rules", JSONArray(rules.map { it.toJson() }))

    companion object {
        fun name(base: String) = when (base) { "aqua" -> "Aqua"; "bliss" -> "Bliss"; "super" -> "Super"; else -> base }
        fun from(o: JSONObject) = SceneConfig(o.optString("base", "aqua"), o.optDouble("fixedHour", -1.0).toFloat(), o.optInt("speed", 1), o.optInt("hue"), o.optBoolean("events", true), o.optInt("rate", 2),
            o.optBoolean("batteryTint"), o.optBoolean("chargingGlow"), o.optJSONArray("rules")?.let { a -> (0 until a.length()).map { Rule.from(a.getJSONObject(it)) } } ?: emptyList())

        fun custom(c: Context): SceneConfig = runCatching { from(JSONObject(c.getSharedPreferences("scene", Context.MODE_PRIVATE).getString("custom", "{}")!!)) }.getOrDefault(SceneConfig())
        fun saveCustom(c: Context, s: SceneConfig) { c.getSharedPreferences("scene", Context.MODE_PRIVATE).edit().putString("custom", s.toJson().toString()).apply(); WpConfig.changed(c) }

        /** The scene that matches a Wonderdesk look. */
        fun forLook(look: dev.wonderdesk.shared.WonderLook?) = when (look) {
            dev.wonderdesk.shared.WonderLook.BLISS, dev.wonderdesk.shared.WonderLook.DARK -> "bliss"
            dev.wonderdesk.shared.WonderLook.SUPER -> "super"
            else -> "aqua"
        }

        /** What a wallpaper source of kind "scene" means right now. */
        fun resolve(c: Context, id: String): SceneConfig = when (id) {
            "custom" -> custom(c)
            "theme", "" -> SceneConfig(base = forLook(runCatching { dev.wonderdesk.shared.GlobalAppearance.read(c)?.first }.getOrNull()))
            else -> SceneConfig(base = id)
        }
    }
}

/** What is going on around the scene right now. */
class Env(var hour: Float, var battery: Int, var charging: Boolean)

private fun blend(a: Int, b: Int, f: Float): Int {
    val t = f.coerceIn(0f, 1f)
    return Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
}

/** Colour stops that blend smoothly around the clock: [at] hours with [colors] each. */
private class Keys(val at: FloatArray, val colors: Array<IntArray>) {
    fun at(hour: Float, i: Int): Int {
        val h = ((hour % 24) + 24) % 24
        var k = at.size - 1
        for (j in 0 until at.size - 1) if (h >= at[j] && h < at[j + 1]) { k = j; break }
        val next = (k + 1) % at.size
        val span = if (next == 0) 24 - at[k] + at[0] else at[next] - at[k]
        val into = if (h >= at[k]) h - at[k] else h + 24 - at[k]
        val f = into / span
        val s = f * f * (3 - 2 * f)
        return blend(colors[k][i], colors[next][i], s)
    }
}

private val SKY = Keys(floatArrayOf(0f, 5f, 6.5f, 8.5f, 12f, 16f, 18.3f, 19.6f, 21.5f),
    arrayOf(intArrayOf(0xFF050B24.toInt(), 0xFF0B1A44.toInt()), intArrayOf(0xFF1B2A5E.toInt(), 0xFF5B4B8A.toInt()), intArrayOf(0xFF4A6FB5.toInt(), 0xFFFFA56B.toInt()),
        intArrayOf(0xFF3A8EE0.toInt(), 0xFF9AD4FF.toInt()), intArrayOf(0xFF1E7FE8.toInt(), 0xFF8FD0FF.toInt()), intArrayOf(0xFF2F86E0.toInt(), 0xFFA8DAFF.toInt()),
        intArrayOf(0xFF4A5FA8.toInt(), 0xFFFF8A5B.toInt()), intArrayOf(0xFF2B2A6B.toInt(), 0xFFB5527A.toInt()), intArrayOf(0xFF0A1236.toInt(), 0xFF1E2A5E.toInt())))

/** Water seen from below: surface, middle and deep colours. */
private val WATER = Keys(floatArrayOf(0f, 5f, 6.5f, 8.5f, 12f, 16f, 18.3f, 19.6f, 21.5f),
    arrayOf(intArrayOf(0xFF0E4A94.toInt(), 0xFF082F6B.toInt(), 0xFF010A22.toInt()), intArrayOf(0xFF2A4E96.toInt(), 0xFF1A2E6E.toInt(), 0xFF050C2A.toInt()), intArrayOf(0xFFFFB27A.toInt(), 0xFF3F7FC4.toInt(), 0xFF0B2A66.toInt()),
        intArrayOf(0xFF7FDCFF.toInt(), 0xFF22A8EE.toInt(), 0xFF0A4FA8.toInt()), intArrayOf(0xFF8FE4FF.toInt(), 0xFF22A8EE.toInt(), 0xFF063B8E.toInt()), intArrayOf(0xFF7FDCFF.toInt(), 0xFF1E9CE6.toInt(), 0xFF083E92.toInt()),
        intArrayOf(0xFFFFA26B.toInt(), 0xFF8A5BB0.toInt(), 0xFF140F4E.toInt()), intArrayOf(0xFF8A4A9E.toInt(), 0xFF2B2A6B.toInt(), 0xFF0A0A30.toInt()), intArrayOf(0xFF123A82.toInt(), 0xFF0A2458.toInt(), 0xFF020A26.toInt())))

private val GRASS = Keys(floatArrayOf(0f, 5f, 6.5f, 8.5f, 12f, 16f, 18.3f, 19.6f, 21.5f),
    arrayOf(intArrayOf(0xFF0E2A24.toInt(), 0xFF153D2E.toInt()), intArrayOf(0xFF1D3C34.toInt(), 0xFF2A5A3E.toInt()), intArrayOf(0xFF6E8F4A.toInt(), 0xFF8FB04A.toInt()), intArrayOf(0xFF5DB04A.toInt(), 0xFF7FD05A.toInt()),
        intArrayOf(0xFF4FA83E.toInt(), 0xFF76D14B.toInt()), intArrayOf(0xFF5AAE40.toInt(), 0xFF7FD052.toInt()), intArrayOf(0xFF9A8A3E.toInt(), 0xFFC8A04A.toInt()), intArrayOf(0xFF3E4A3A.toInt(), 0xFF5A5A44.toInt()), intArrayOf(0xFF15302A.toInt(), 0xFF1E4234.toInt())))

/** 0 in full day, 1 in the dead of night, smooth through twilight. */
private fun nightness(h: Float): Float {
    val x = ((h % 24) + 24) % 24
    return when { x < 5f -> 1f; x < 7f -> 1f - (x - 5f) / 2f; x < 18f -> 0f; x < 20.5f -> (x - 18f) / 2.5f; else -> 1f }.coerceIn(0f, 1f)
}

abstract class Scene(val w: Int, val h: Int, seed: Long) {
    protected val rnd = Random(seed)
    protected val m = max(w, h).toFloat()
    var time = 0f
    abstract fun draw(c: Canvas, env: Env, cfg: SceneConfig)
    open fun step(dt: Float, env: Env, cfg: SceneConfig) { time += dt }
    open fun tap(x: Float, y: Float) {}

    /** A shooting star every so often, when the sky is dark enough. */
    protected class Streak(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float)
    protected val streaks = ArrayList<Streak>()
    private var nextEvent = 6f
    protected fun scheduleEvents(dt: Float, cfg: SceneConfig, canStreak: Boolean, other: () -> Unit = {}) {
        if (!cfg.events) return
        nextEvent -= dt
        if (nextEvent <= 0f) {
            nextEvent = (when (cfg.eventRate) { 1 -> 90f; 3 -> 12f; else -> 35f }) * (.6f + rnd.nextFloat() * .8f)
            if (canStreak && rnd.nextBoolean()) streaks.add(Streak(rnd.nextFloat() * w, rnd.nextFloat() * h * .3f, m * (.5f + rnd.nextFloat() * .4f), m * (.2f + rnd.nextFloat() * .2f), 1f)) else other()
        }
        val it = streaks.iterator()
        while (it.hasNext()) { val s = it.next(); s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt * 1.1f; if (s.life <= 0f) it.remove() }
    }
    protected fun drawStreaks(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = m * .003f }
        for (s in streaks) {
            val len = m * .09f; val d = kotlin.math.sqrt(s.vx * s.vx + s.vy * s.vy)
            p.shader = LinearGradient(s.x, s.y, s.x - s.vx / d * len, s.y - s.vy / d * len, Color.argb((255 * s.life.coerceIn(0f, 1f)).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawLine(s.x, s.y, s.x - s.vx / d * len, s.y - s.vy / d * len, p)
        }
    }
}

/** A soft round glow, painted once and then scaled, tinted and faded for free every frame. */
private fun glowSprite(n: Int, vararg stops: Int): Bitmap {
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(n / 2f, n / 2f, n / 2f, stops, null, Shader.TileMode.CLAMP) }
    Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
    return b
}

/** A soft ring, painted once. */
private fun ringSprite(n: Int): Bitmap {
    val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = n * .05f; color = Color.WHITE; maskFilter = BlurMaskFilter(n * .035f, BlurMaskFilter.Blur.NORMAL) }
    Canvas(b).drawCircle(n / 2f, n / 2f, n * .4f, p)
    return b
}

private val ADD_MODE = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
private fun hsv(a: Int, hue: Float, s: Float, v: Float) = Color.HSVToColor(a, floatArrayOf(((hue % 360f) + 360f) % 360f, s, v))

/**
 * Aqua: looking up through water at the sky. Every part of the day looks like itself: a rose and gold dawn with long low rays from the left,
 * a fresh turquoise morning, a deep azure noon with short bright rays straight down, a warm golden afternoon with the rays leaning the other way,
 * an orange and violet sunset, an indigo dusk, and a real night: almost black water, no sun rays, the moon shimmering through the surface with one
 * pale moonbeam, and plankton that glows teal. Now and then a cloud drifts over and the light dims for a few seconds.
 * Bubbles you can pop, drifting specks and the odd fish, as before.
 */
class AquaScene(w: Int, h: Int) : Scene(w, h, 3) {
    private val field = BubbleField(w, h, count = 30, seed = 11, popping = true)
    private class Fish(var x: Float, val y: Float, val size: Float, val dir: Int)
    private val fish = ArrayList<Fish>()
    private var bg: Bitmap? = null
    private var bgKey = ""

    private class Beam(val x: Float, val wd: Float, val sp: Float, val ph: Float, val str: Float)
    private val beams = List(9) { Beam(.02f + it * .115f + rnd.nextFloat() * .05f, .10f + rnd.nextFloat() * .16f, .6f + rnd.nextFloat() * .9f, rnd.nextFloat() * 6.28f, .5f + rnd.nextFloat() * .5f) }
    private val beamSprite: Bitmap = Bitmap.createBitmap(192, 512, Bitmap.Config.ARGB_8888).also { b ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL); shader = LinearGradient(0f, 0f, 0f, 512f, intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0f, .5f, .95f), Shader.TileMode.CLAMP) }
        Canvas(b).drawPath(Path().apply { moveTo(80f, 0f); lineTo(112f, 0f); lineTo(170f, 512f); lineTo(22f, 512f); close() }, p)
    }
    private val causticTile = makeCaustic(256)
    private val causticA = android.graphics.BitmapShader(causticTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val causticB = android.graphics.BitmapShader(causticTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val mat = android.graphics.Matrix()
    private val rayPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = ADD_MODE }
    private val motePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = ADD_MODE }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = ADD_MODE }
    private val causticPaint = Paint().apply { xfermode = ADD_MODE }
    private val motes = List(64) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h, m * (.003f + rnd.nextFloat() * .006f), rnd.nextFloat() * 6.28f, m * (.004f + rnd.nextFloat() * .01f)) }
    private val moteSprite = glowSprite(48, 0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF)
    private val sunSprite = glowSprite(128, 0xFFFFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x22FFFFFF, 0x00FFFFFF)
    private val src = android.graphics.Rect(); private val dst = RectF()
    private val fishDir = HashMap<Fish, Int>()
    private val fishPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    // A passing cloud: [cloudAt] runs from 0 to 1 while it crosses, dimming the rays and ripples in the middle.
    private var nextCloud = 25f + rnd.nextFloat() * 30f
    private var cloudAt = -1f
    private var cloudLen = 12f
    private var lastHour = 12f
    private var filterKey = 0
    private var moteKey = 0

    private fun makeCaustic(n: Int): Bitmap {
        val px = IntArray(n * n); val tw = (2 * PI / n).toFloat()
        for (y in 0 until n) for (x in 0 until n) {
            val f = (sin(tw * (3 * x + 2 * y)) + sin(tw * (-2 * x + 4 * y) + 1.7f) + sin(tw * (5 * x - 3 * y) + 3.1f) + sin(tw * (-4 * x - 5 * y) + .6f)) / 4f
            var v = 1f - abs(f); v *= v; v *= v; v *= v
            px[y * n + x] = (((v * 255f * 1.7f).toInt().coerceIn(0, 255)) shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    override fun step(dt: Float, env: Env, cfg: SceneConfig) {
        super.step(dt, env, cfg)
        lastHour = env.hour
        field.step(dt)
        scheduleEvents(dt, cfg, false) { val left = rnd.nextBoolean(); val f = Fish(if (left) -m * .1f else w + m * .1f, h * (.3f + rnd.nextFloat() * .4f), m * (.012f + rnd.nextFloat() * .01f), if (left) 1 else -1); fish.add(f); fishDir[f] = f.dir }
        val it = fish.iterator()
        while (it.hasNext()) { val f = it.next(); f.x += (fishDir[f] ?: 1) * m * .09f * dt; if (f.x < -m * .2f || f.x > w + m * .2f) { it.remove(); fishDir.remove(f) } }
        val night = nightness(env.hour)
        // Plankton drifts more lazily at night.
        for (s in motes) { s[1] -= s[4] * dt * (1f - night * .5f); s[0] += sin(time * .4f + s[3]) * m * .004f * dt * 8; if (s[1] < -10f) { s[1] = h + 10f; s[0] = rnd.nextFloat() * w } }
        if (cloudAt >= 0f) { cloudAt += dt / cloudLen; if (cloudAt >= 1f) cloudAt = -1f }
        else { nextCloud -= dt; if (nextCloud <= 0f) { cloudAt = 0f; cloudLen = 9f + rnd.nextFloat() * 10f; nextCloud = 40f + rnd.nextFloat() * 70f } }
    }

    override fun tap(x: Float, y: Float) { field.popAt(x, y) }

    private fun water(hour: Float): Bitmap {
        val key = "%.1f".format(hour)
        if (bg == null || key != bgKey) {
            val b = bg ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val night = nightness(hour)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, w * .12f, h.toFloat(), intArrayOf(AQUA_WATER.at(hour, 0), AQUA_WATER.at(hour, 1), AQUA_WATER.at(hour, 2)), floatArrayOf(0f, .42f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            // The deep gets properly dark at night, a little dark by day.
            p.shader = LinearGradient(0f, h * .45f, 0f, h.toFloat(), Color.TRANSPARENT, blend(0x66000A1C, 0xD0000208.toInt(), night), Shader.TileMode.CLAMP); c.drawRect(0f, h * .45f, w.toFloat(), h.toFloat(), p)
            bg = b; bgKey = key
        }
        return bg!!
    }

    /** Where the sun is: -1 low on the left (morning) through 0 overhead to 1 low on the right (evening), and how high (0 to 1). */
    private fun sunSide(hour: Float) = (((hour - 6.5f) / (18.3f - 6.5f)) * 2f - 1f).coerceIn(-1.15f, 1.15f)
    private fun sunHeight(hour: Float) = sin(PI.toFloat() * ((hour - 6.5f) / (18.3f - 6.5f)).coerceIn(0f, 1f))
    /** The moon crosses the night the same way, left to right. */
    private fun moonSide(hour: Float): Float { val t = (((hour - 19.5f) + 24f) % 24f) / 11f; return (t * 2f - 1f).coerceIn(-1f, 1f) }

    private fun tintFilter(color: Int) = android.graphics.LightingColorFilter(color and 0xFFFFFF, 0)

    override fun draw(c: Canvas, env: Env, cfg: SceneConfig) {
        val hour = env.hour
        val night = nightness(hour); val day = 1f - night
        val height = sunHeight(hour); val side = sunSide(hour)
        // A cloud dims the light smoothly in and out as it passes.
        val cloud = if (cloudAt >= 0f) 1f - .62f * sin(PI.toFloat() * cloudAt).let { it * it } * day else 1f
        val rayColor = AQUA_RAYS.at(hour, 0)
        c.drawBitmap(water(hour), 0f, 0f, null)

        // The sun's (or by night the moon's) glow seen through the surface.
        src.set(0, 0, sunSprite.width, sunSprite.height)
        if (day > .02f) {
            val sx = w * (.5f + .44f * side); val r = m * (.38f + .42f * height)
            glowPaint.colorFilter = tintFilter(rayColor); glowPaint.alpha = (day * cloud * (150f + 70f * height)).toInt().coerceIn(0, 255)
            dst.set(sx - r, -r * .9f, sx + r, r * .55f); c.drawBitmap(sunSprite, src, dst, glowPaint)
        }
        if (night > .02f) {
            val mx = w * (.5f + .38f * moonSide(hour)); val r = m * .07f
            // The moon wobbles with the surface ripples, a bright smudge rather than a crisp disc.
            val wob = sin(time * 1.3f) * r * .18f
            glowPaint.colorFilter = tintFilter(0xFFDDE8FF.toInt()); glowPaint.alpha = (night * 235f).toInt()
            dst.set(mx - r * 1.3f + wob, m * .012f, mx + r * 1.3f - wob, m * .012f + r * 1.1f); c.drawBitmap(sunSprite, src, dst, glowPaint)
            glowPaint.alpha = (night * 70f).toInt(); dst.set(mx - r * 5f, -r * 3f, mx + r * 5f, r * 4f); c.drawBitmap(sunSprite, src, dst, glowPaint)
        }

        // Ripples of light: strong at noon, softer at the ends of the day, a faint silver shimmer under the moon.
        val causticStrength = (day * (.45f + .55f * height) * cloud + night * .16f).coerceIn(0f, 1f)
        val cKey = if (night > .5f) 1 else 0
        if (cKey != filterKey) { filterKey = cKey; causticPaint.colorFilter = if (cKey == 1) tintFilter(0xFFB8CCFF.toInt()) else null }
        val reach = h * (.62f + .18f * height)
        for (layer in 0..1) {
            val sc = m * (if (layer == 0) .0016f else .0023f)
            mat.reset(); mat.setScale(sc, sc); mat.postTranslate(time * m * (if (layer == 0) .012f else -.016f), time * m * (if (layer == 0) .008f else .01f))
            val sh = if (layer == 0) causticA else causticB
            sh.setLocalMatrix(mat); causticPaint.shader = sh
            val bands = 16
            for (i in 0 until bands) {
                val f = i / bands.toFloat(); causticPaint.alpha = (92f * causticStrength * (1f - f) * (1f - f) * (if (layer == 0) 1f else .75f)).toInt().coerceIn(0, 255)
                c.drawRect(0f, reach * f, w.toFloat(), reach * (i + 1) / bands + 1f, causticPaint)
            }
        }
        causticPaint.shader = null

        // Light shafts. They lean away from the sun (long and slanted when it is low, short and straight at noon), take its colour,
        // and are gone at night except for one pale moonbeam.
        src.set(0, 0, beamSprite.width, beamSprite.height)
        val lean = side * 30f
        val rays = (day * (.38f + .62f * kotlin.math.sqrt(height)) * cloud).coerceIn(0f, 1f)
        rayPaint.colorFilter = tintFilter(rayColor)
        if (rays > .01f) for (bm in beams) {
            val x = w * bm.x + sin(time * .07f * bm.sp + bm.ph) * w * .06f - side * w * .08f
            val a = (.4f + .6f * (sin(time * .22f * bm.sp + bm.ph) * .5f + .5f)) * rays * bm.str * (.36f + .2f * height)
            val bw = w * bm.wd * (1.7f - .5f * height)
            val len = h * (1.25f - .3f * height)
            c.save(); c.translate(x, -m * .03f); c.rotate(lean + sin(time * .1f * bm.sp + bm.ph * 1.7f) * 5f)
            dst.set(-bw / 2f, 0f, bw / 2f, len); rayPaint.alpha = (a * 255).toInt().coerceIn(0, 255)
            c.drawBitmap(beamSprite, src, dst, rayPaint); c.restore()
        }
        if (night > .5f) {
            val mx = w * (.5f + .38f * moonSide(hour))
            rayPaint.colorFilter = tintFilter(0xFFC8D8FF.toInt())
            val bw = w * .22f
            c.save(); c.translate(mx, 0f); c.rotate(moonSide(hour) * -18f + sin(time * .12f) * 3f)
            dst.set(-bw / 2f, 0f, bw / 2f, h * .9f); rayPaint.alpha = ((night - .5f) * 2f * 70f * (.75f + .25f * sin(time * .3f))).toInt().coerceIn(0, 255)
            c.drawBitmap(beamSprite, src, dst, rayPaint); c.restore()
        }

        // Drifting specks by day; at night the plankton glows teal and pulses.
        val mKey = if (night > .45f) 1 else 0
        if (mKey != moteKey) { moteKey = mKey; motePaint.colorFilter = if (mKey == 1) tintFilter(0xFF4DFFD6.toInt()) else tintFilter(0xFFE6F8FF.toInt()) }
        val sp = android.graphics.Rect(0, 0, moteSprite.width, moteSprite.height)
        for (s in motes) {
            val glow = sin(time * (1.3f - night * .7f) + s[3]) * .5f + .5f
            val r = s[2] * (3f + night * 2.2f * glow)
            motePaint.alpha = (((.3f + .7f * glow) * 140f * (day * .9f + .2f)) + night * 120f * glow * glow).toInt().coerceIn(0, 255)
            dst.set(s[0] - r, s[1] - r, s[0] + r, s[1] + r); c.drawBitmap(moteSprite, sp, dst, motePaint)
        }
        fishPaint.color = Color.argb((60 * (1f - night * .5f)).toInt(), 0, 20, 40)
        for (f in fish) {
            val d = fishDir[f] ?: 1
            c.save(); c.translate(f.x, f.y + sin(time * 2 + f.x * .01f) * f.size * .3f); c.scale(d.toFloat(), 1f)
            c.drawPath(Path().apply { moveTo(-f.size * 2, 0f); quadTo(0f, -f.size * 1.1f, f.size * 2f, 0f); quadTo(0f, f.size * 1.1f, -f.size * 2, 0f); close() }, fishPaint)
            c.drawPath(Path().apply { moveTo(-f.size * 1.8f, 0f); lineTo(-f.size * 3.1f, -f.size); lineTo(-f.size * 3.1f, f.size); close() }, fishPaint); c.restore()
        }
        field.draw(c)
        // A passing cloud also leaves a soft shadow on the water.
        if (cloud < .99f) { shade.color = Color.argb(((1f - cloud) * 70f).toInt(), 0, 10, 30); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade) }
    }
}

/** Water seen from below at each part of the day: surface, middle and deep colours. Every part of the day has its own look. */
private val AQUA_WATER = Keys(floatArrayOf(0f, 4.6f, 5.8f, 6.9f, 9f, 12.4f, 15.3f, 17.3f, 18.3f, 19.3f, 20.6f),
    arrayOf(
        intArrayOf(0xFF0B2552.toInt(), 0xFF06163A.toInt(), 0xFF01040F.toInt()),   // night: ink blue
        intArrayOf(0xFF1E2C66.toInt(), 0xFF111C4A.toInt(), 0xFF02061A.toInt()),   // before dawn
        intArrayOf(0xFFF0A2BE.toInt(), 0xFF6060AE.toInt(), 0xFF111F58.toInt()),   // dawn: rose
        intArrayOf(0xFFFFC98E.toInt(), 0xFF3F8ECB.toInt(), 0xFF0A3272.toInt()),   // sunrise: gold
        intArrayOf(0xFFA2F4EE.toInt(), 0xFF16BAD6.toInt(), 0xFF06528E.toInt()),   // morning: turquoise
        intArrayOf(0xFF86DCFF.toInt(), 0xFF0A8BEB.toInt(), 0xFF032E88.toInt()),   // noon: azure
        intArrayOf(0xFFCFF2C4.toInt(), 0xFF2AA8B4.toInt(), 0xFF0B4076.toInt()),   // afternoon: warm, greener
        intArrayOf(0xFFFFD680.toInt(), 0xFF4E9CB4.toInt(), 0xFF133F72.toInt()),   // golden hour
        intArrayOf(0xFFFF8B5C.toInt(), 0xFFB2507E.toInt(), 0xFF24165C.toInt()),   // sunset: orange and magenta
        intArrayOf(0xFF7C4AAA.toInt(), 0xFF2F2C7A.toInt(), 0xFF0A0A36.toInt()),   // dusk: violet
        intArrayOf(0xFF27367C.toInt(), 0xFF121E52.toInt(), 0xFF03071E.toInt()),   // late dusk: indigo
    ))

/** The colour of the light shafts through the day. */
private val AQUA_RAYS = Keys(floatArrayOf(0f, 5.6f, 6.6f, 8.5f, 12.4f, 15.5f, 17.4f, 18.4f, 19.6f),
    arrayOf(intArrayOf(0xFFA8BCFF.toInt()), intArrayOf(0xFFFF9EB6.toInt()), intArrayOf(0xFFFFCB80.toInt()), intArrayOf(0xFFF0FFFA.toInt()), intArrayOf(0xFFFFFFFF.toInt()),
        intArrayOf(0xFFFFF0C2.toInt()), intArrayOf(0xFFFFB45E.toInt()), intArrayOf(0xFFFF7A52.toInt()), intArrayOf(0xFF9C80FF.toInt())))

/* ----------------------------------------------------------------------------------------------------
 * Bliss: rolling hills fading into haze, thousands of grass blades that sway in a wind you can see move
 * across the field, soft layered clouds, a sun that really crosses the sky (with sunrises, sunsets, stars
 * and a moon), birds by day and the odd rainbow.
 * -------------------------------------------------------------------------------------------------- */

/** A soft cloud, painted once: many faint overlapping puffs with a shaded, flattened underside. */
private fun cloudSprite(seed: Long): Bitmap {
    val bw = 640; val bh = 320
    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val cv = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val r0 = Random(seed)
    class Puff(val x: Float, val y: Float, val r: Float)
    val puffs = ArrayList<Puff>()
    for (i in 0 until 46) {
        val t = r0.nextFloat(); val px = .14f + .72f * t
        val dome = sin(PI.toFloat() * t).coerceAtLeast(0f)
        val r = bh * (.07f + .11f * dome * (.5f + r0.nextFloat() * .7f))
        puffs += Puff(bw * px, bh * (.72f - dome * .26f * r0.nextFloat() - r0.nextFloat() * .05f), r)
    }
    // shadowed underside first, then the lit puffs on top, all with soft edges
    for (q in puffs) { p.shader = RadialGradient(q.x, q.y + q.r * .35f, q.r * 1.25f, intArrayOf(0x70A9B8D6, 0x00A9B8D6), null, Shader.TileMode.CLAMP); cv.drawCircle(q.x, q.y + q.r * .35f, q.r * 1.25f, p) }
    for (q in puffs.sortedBy { it.y }) { p.shader = RadialGradient(q.x - q.r * .15f, q.y - q.r * .22f, q.r, intArrayOf(0xF2FFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP); cv.drawCircle(q.x, q.y, q.r, p) }
    // flatten the bottom into a soft edge
    p.shader = LinearGradient(0f, bh * .62f, 0f, bh * .84f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP); p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    cv.drawRect(0f, bh * .62f, bw.toFloat(), bh.toFloat(), p)
    return b
}

class BlissScene(w: Int, h: Int) : Scene(w, h, 7) {
    private class Cloud(var x: Float, val y: Float, val size: Float, val speed: Float, val sprite: Int, val layer: Int)
    private val sprites = List(3) { cloudSprite(11L + it * 7) }
    private val clouds = List(9) { i -> val far = i < 5
        Cloud(rnd.nextFloat() * w, h * (if (far) .1f + rnd.nextFloat() * .16f else .06f + rnd.nextFloat() * .3f), m * (if (far) .07f + rnd.nextFloat() * .04f else .1f + rnd.nextFloat() * .07f), m * (if (far) .0025f else .0065f) * (.7f + rnd.nextFloat() * .6f), i % 3, if (far) 0 else 1) }
    private val stars = List(170) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h * .5f, .5f + rnd.nextFloat() * 1.3f, rnd.nextFloat() * 6.28f) }
    private val glow = glowSprite(256, 0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF)
    private val seedSprite = glowSprite(32, 0xFFFFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x00FFFFFF)
    private val ring = ringSprite(256)
    private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = ADD_MODE }
    private val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val horizon = h * .60f
    private val src = android.graphics.Rect(); private val dst = RectF()

    // Hills: each is a smooth curve; the nearest carries the grass.
    private class Hill(val base: Float, val a1: Float, val f1: Float, val p1: Float, val a2: Float, val f2: Float, val p2: Float) {
        fun y(x: Float, w: Float, h: Float): Float { val u = x / w; return h * (base + a1 * sin(u * f1 + p1) + a2 * sin(u * f2 + p2)) }
    }
    private val hills = listOf(Hill(.615f, .022f, 5.5f, .4f, .010f, 11f, 1.2f), Hill(.675f, .030f, 4.2f, 2.1f, .012f, 9f, .3f), Hill(.745f, .036f, 3.4f, 4.0f, .014f, 8f, 2.2f), Hill(.835f, .030f, 3.0f, 1.0f, .012f, 7f, 4.4f))
    private var ground: Bitmap? = null
    private var groundKey = ""

    // Grass blades on the near hills: x, depth (0 far..1 near), height, phase, shade bucket.
    private val nBlades = 3400
    private val bladeX = FloatArray(nBlades) { rnd.nextFloat() * w }
    private val bladeT = FloatArray(nBlades) { rnd.nextFloat() }
    private val bladeH = FloatArray(nBlades) { .6f + rnd.nextFloat() * .8f }
    private val bladePh = FloatArray(nBlades) { rnd.nextFloat() * 6.28f }
    private val bladeShade = IntArray(nBlades) { rnd.nextInt(3) }
    private val lines = Array(3) { FloatArray(nBlades * 4) }
    private val bladePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

    private class Wave(val x: Float, val y: Float, var age: Float)
    private val waves = ArrayList<Wave>()
    private val seeds = ArrayList<FloatArray>()           // x, y, vx, vy, life
    private class Bird(var x: Float, val y: Float, val speed: Float, val size: Float, val ph: Float)
    private val birds = ArrayList<Bird>()
    private var rainbow = 0f

    override fun step(dt: Float, env: Env, cfg: SceneConfig) {
        super.step(dt, env, cfg)
        for (c in clouds) { c.x += c.speed * dt; if (c.x - c.size * 1.2f > w) c.x = -c.size * 1.2f }
        val day = nightness(env.hour) < .4f
        scheduleEvents(dt, cfg, nightness(env.hour) > .6f) {
            if (day) { if (rnd.nextInt(3) == 0) rainbow = 1f else { val n = 3 + rnd.nextInt(4); val y0 = h * (.12f + rnd.nextFloat() * .25f); val sp = m * (.05f + rnd.nextFloat() * .03f)
                repeat(n) { i -> birds += Bird(-m * (.04f + i * .035f), y0 + (i % 2 * 2 - 1) * m * .012f * i, sp, m * (.008f + rnd.nextFloat() * .003f), rnd.nextFloat() * 6.28f) } } }
        }
        if (rainbow > 0f) rainbow = (rainbow - dt / 40f).coerceAtLeast(0f)
        val bi = birds.iterator(); while (bi.hasNext()) { val b = bi.next(); b.x += b.speed * dt; if (b.x > w + m * .1f) bi.remove() }
        // a few dandelion seeds always drifting by on a sunny day
        if (day && seeds.size < 7 && rnd.nextFloat() < dt * .8f) seeds.add(floatArrayOf(-m * .02f, h * (.45f + rnd.nextFloat() * .3f), m * (.03f + rnd.nextFloat() * .03f), -m * (.004f + rnd.nextFloat() * .01f), 1f).also { it[4] = 2.2f })
        val wi = waves.iterator(); while (wi.hasNext()) { val x = wi.next(); x.age += dt; if (x.age > 2.4f) wi.remove() }
        val it = seeds.iterator(); while (it.hasNext()) { val s = it.next(); s[0] += s[2] * dt; s[1] += s[3] * dt + sin(time * 2f + s[0] * .01f) * m * .004f * dt * 10; s[4] -= dt / 5f; if (s[4] <= 0f || s[0] > w + 20f) it.remove() }
    }

    override fun tap(x: Float, y: Float) {
        waves.add(Wave(x, y, 0f))
        repeat(9) { seeds.add(floatArrayOf(x, y, m * (.01f + rnd.nextFloat() * .06f), -m * (.02f + rnd.nextFloat() * .07f), 1f)) }
    }

    /** The hills, shaded, with haze between them. Only redrawn when the hour moves on. */
    private fun ground(hr: Float, night: Float): Bitmap {
        val key = "%.1f".format(hr)
        if (ground == null || key != groundKey) {
            val b = ground ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.TRANSPARENT)
            val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val far = GRASS.at(hr, 0); val near = GRASS.at(hr, 1); val haze = blend(SKY.at(hr, 1), 0xFFFFFFFF.toInt(), .25f * (1f - night))
            val warm = (1f - abs(hr - 6.8f).coerceAtMost(1.5f) / 1.5f).coerceAtLeast(1f - abs(hr - 18.5f).coerceAtMost(1.7f) / 1.7f)
            val sunSide = if (hr < 12.3f) .2f else .8f      // which side of the field catches the light
            hills.forEachIndexed { i, hl ->
                val path = Path(); val step = (w / 60f).coerceAtLeast(2f)
                path.moveTo(0f, hl.y(0f, w.toFloat(), h.toFloat())); var x = step
                while (x < w + step) { path.lineTo(x, hl.y(x, w.toFloat(), h.toFloat())); x += step }
                path.lineTo(w.toFloat(), h.toFloat()); path.lineTo(0f, h.toFloat()); path.close()
                val depth = i / (hills.size - 1f)
                val hz = (1f - depth) * .62f                                  // far hills sink into haze
                val topC = blend(blend(far, near, depth * .8f), haze, hz); val botC = blend(blend(near, 0xFF000000.toInt(), .22f * depth), haze, hz * .55f)
                p.shader = LinearGradient(0f, h * (hl.base - .05f), 0f, h * (hl.base + .16f), topC, botC, Shader.TileMode.CLAMP); c.drawPath(path, p)
                // light falling across the slope from the sun's side
                p.shader = LinearGradient(w * sunSide - w * .6f, 0f, w * sunSide + w * .6f, 0f, intArrayOf(Color.argb((70 * (1f - night)).toInt(), 255, 240, 190 - (warm * 80).toInt()), Color.TRANSPARENT, Color.argb(40, 0, 20, 30)), null, Shader.TileMode.CLAMP)
                p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP); c.drawPath(path, p); p.xfermode = null
                // a bright rim along the crest where the sun grazes it
                p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = m * .0022f * (1f + depth)
                p.color = Color.argb(((1f - night) * (45 + 80 * warm) * (.5f + .5f * depth)).toInt().coerceIn(0, 255), 255, 245, 200); c.drawPath(path, p); p.style = Paint.Style.FILL
            }
            // warm bloom in the haze right at the horizon
            p.shader = LinearGradient(0f, horizon - h * .06f, 0f, horizon + h * .05f, Color.TRANSPARENT, Color.argb((warm * 90 + 25 * (1f - night)).toInt(), 255, 200, 140), Shader.TileMode.CLAMP)
            p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_ATOP); c.drawRect(0f, horizon - h * .06f, w.toFloat(), horizon + h * .05f, p)
            p.shader = LinearGradient(0f, h * .88f, 0f, h.toFloat(), Color.TRANSPARENT, 0x66000000, Shader.TileMode.CLAMP); c.drawRect(0f, h * .88f, w.toFloat(), h.toFloat(), p)
            p.xfermode = null; p.shader = null
            ground = b; groundKey = key
        }
        return ground!!
    }

    override fun draw(c: Canvas, env: Env, cfg: SceneConfig) {
        val hr = env.hour; val night = nightness(hr)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // sky, brighter and hazier toward the horizon
        p.shader = LinearGradient(0f, 0f, 0f, horizon + h * .04f, intArrayOf(SKY.at(hr, 0), SKY.at(hr, 1), blend(SKY.at(hr, 1), 0xFFFFFFFF.toInt(), .35f * (1f - night))), floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), horizon + h * .05f, p); p.shader = null
        if (night > .15f) for (s in stars) { p.color = Color.argb((255 * night * (.45f + .55f * sin(time * 1.4f + s[3]))).toInt().coerceIn(0, 255), 255, 255, 255); c.drawCircle(s[0], s[1], s[2], p) }
        drawStreaks(c)
        fun arc(f: Float): Pair<Float, Float> = (w * (.08f + .84f * f)) to (horizon - sin(PI.toFloat() * f) * h * .5f + h * .03f)
        val sunF = (hr - 6f) / 12.7f
        if (sunF in -.05f..1.05f) {
            val (sx, sy) = arc(sunF)
            val low = 1f - sin(PI.toFloat() * sunF.coerceIn(0f, 1f))
            // wide bloom, then a tighter one, then the disc
            for ((k, a) in listOf(.30f to 70, .13f to 130)) {
                val gr = m * (k + .06f * low); dst.set(sx - gr, sy - gr, sx + gr, sy + gr); src.set(0, 0, glow.width, glow.height)
                addPaint.alpha = a; addPaint.colorFilter = android.graphics.PorterDuffColorFilter(blend(0xFFFFF4C8.toInt(), 0xFFFF8A4B.toInt(), low), android.graphics.PorterDuff.Mode.MULTIPLY)
                c.drawBitmap(glow, src, dst, addPaint)
            }
            addPaint.colorFilter = null
            p.color = blend(0xFFFFFBE8.toInt(), 0xFFFFC27A.toInt(), low); c.drawCircle(sx, sy, m * .028f, p)
        }
        val moonF = (((hr + 24f - 18f) % 24f)) / 12f
        if (moonF in 0f..1f) {
            val (mx, my) = arc(moonF)
            src.set(0, 0, glow.width, glow.height); dst.set(mx - m * .2f, my - m * .2f, mx + m * .2f, my + m * .2f); addPaint.alpha = 100; c.drawBitmap(glow, src, dst, addPaint)
            p.color = 0xFFF2F5FF.toInt(); c.drawCircle(mx, my, m * .03f, p)
            p.color = blend(SKY.at(hr, 0), SKY.at(hr, 1), .3f); c.drawCircle(mx + m * .012f, my - m * .006f, m * .026f, p)
        }
        if (rainbow > 0f) {
            val a = (min(1f, rainbow * 3f) * min(1f, (1f - rainbow) * 6f + .3f) * 90).toInt()
            val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = m * .011f }
            for (i in 0 until 7) { rp.color = Color.HSVToColor(a, floatArrayOf(i * 42f, .65f, 1f)); c.drawArc(RectF(w * .02f + i * m * .01f, horizon - w * .8f + i * m * .01f, w * .98f - i * m * .01f, horizon + w * .7f - i * m * .01f), 180f, 180f, false, rp) }
        }
        // clouds in two layers: small slow ones in the distance, larger faster ones nearer, lit by the time of day
        val dusk = (1f - abs(hr - 18.3f).coerceAtMost(1.6f) / 1.6f).coerceAtLeast(1f - abs(hr - 6.6f).coerceAtMost(1.4f) / 1.4f) * .85f
        val cloudCol = blend(blend(0xFFFFFFFF.toInt(), 0xFFFFC9A8.toInt(), dusk), 0xFF3A4670.toInt(), night)
        for (layer in 0..1) {
            cloudPaint.colorFilter = android.graphics.PorterDuffColorFilter(blend(cloudCol, SKY.at(hr, 1), if (layer == 0) .28f else 0f), android.graphics.PorterDuff.Mode.MULTIPLY)
            cloudPaint.alpha = ((if (night > .5f) 120 else 245) * (if (layer == 0) .8f else 1f)).toInt()
            for (cl in clouds) if (cl.layer == layer) {
                src.set(0, 0, sprites[cl.sprite].width, sprites[cl.sprite].height)
                dst.set(cl.x - cl.size * 1.1f, cl.y - cl.size * .55f, cl.x + cl.size * 1.1f, cl.y + cl.size * .55f); c.drawBitmap(sprites[cl.sprite], src, dst, cloudPaint)
            }
        }
        // birds, a flock crossing the sky
        if (birds.isNotEmpty()) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = m * .0018f; strokeCap = Paint.Cap.ROUND; color = Color.argb(150, 40, 50, 70) }
            for (b in birds) {
                val flap = sin(time * 7f + b.ph) * b.size * .55f
                val path = Path().apply { moveTo(b.x - b.size, b.y - flap); quadTo(b.x - b.size * .4f, b.y - b.size * .2f - flap * .3f, b.x, b.y); quadTo(b.x + b.size * .4f, b.y - b.size * .2f - flap * .3f, b.x + b.size, b.y - flap) }
                c.drawPath(path, bp)
            }
        }
        c.drawBitmap(ground(hr, night), 0f, 0f, null)
        // light and shade rolling over the hills, like wind through grass
        src.set(0, 0, glow.width, glow.height)
        val warmth = blend(0xFFFFFFFF.toInt(), 0xFFFFC890.toInt(), dusk)
        addPaint.colorFilter = android.graphics.PorterDuffColorFilter(warmth, android.graphics.PorterDuff.Mode.MULTIPLY)
        for (i in 0 until 4) {
            val f = ((time * (.02f + i * .006f) + i * .27f) % 1f)
            val x = -w * .4f + f * w * 1.8f; val y = h * (.70f + i * .06f) + sin(time * .3f + i) * h * .012f
            dst.set(x - w * .55f, y - h * .05f, x + w * .55f, y + h * .05f)
            addPaint.alpha = ((1f - night * .7f) * 56f * sin(PI.toFloat() * f)).toInt().coerceIn(0, 255); c.drawBitmap(glow, src, dst, addPaint)
        }
        addPaint.colorFilter = null
        drawGrass(c, hr, night)
        for (wv in waves) {
            val f = wv.age / 2.4f; val r = f * m * .45f
            dst.set(wv.x - r, wv.y - r * .45f, wv.x + r, wv.y + r * .45f); src.set(0, 0, ring.width, ring.height)
            addPaint.alpha = (150 * (1f - f)).toInt().coerceIn(0, 255); c.drawBitmap(ring, src, dst, addPaint)
        }
        src.set(0, 0, seedSprite.width, seedSprite.height)
        for (s in seeds) { val r = m * .009f; addPaint.alpha = (s[4].coerceAtMost(1f) * 230).toInt().coerceIn(0, 255); dst.set(s[0] - r, s[1] - r, s[0] + r, s[1] + r); c.drawBitmap(seedSprite, src, dst, addPaint) }
    }

    /** Blades along the two nearest hills, all leaning with a wave of wind that travels across the field. */
    private fun drawGrass(c: Canvas, hr: Float, night: Float) {
        val near = GRASS.at(hr, 1); val far = GRASS.at(hr, 0)
        val colors = intArrayOf(blend(near, 0xFF000000.toInt(), .22f), near, blend(blend(near, far, .35f), 0xFFFFFFE0.toInt(), .22f * (1f - night)))
        val counts = IntArray(3)
        val gustPhase = time * 1.1f
        for (i in 0 until nBlades) {
            val x = bladeX[i]
            // blades cover the two nearest hills right down to the bottom; the nearer, the bigger
            val hillIdx = if (i % 3 == 0) 2 else 3
            val edge = hills[hillIdx].y(x, w.toFloat(), h.toFloat())
            val bottom = if (hillIdx == 2) hills[3].y(x, w.toFloat(), h.toFloat()) else h.toFloat()
            val y = edge + bladeT[i] * (bottom - edge)
            if (y > h) continue
            val near01 = ((y - horizon) / (h - horizon)).coerceIn(0f, 1f)
            val len = m * (.006f + .05f * near01 * near01) * bladeH[i]
            // wind: a slow big wave plus a quicker flutter, stronger on the crest of each gust
            val gust = sin(x * .0065f - gustPhase) * .5f + .5f
            val sway = (sin(time * 1.7f + bladePh[i] + x * .01f) * .35f + gust * 1.1f - .2f) * len * .55f
            val s = bladeShade[i]; val k = counts[s]; counts[s] = k + 4
            val arr = lines[s]
            arr[k] = x; arr[k + 1] = y; arr[k + 2] = x + sway; arr[k + 3] = y - len
        }
        for (s in 0..2) {
            if (counts[s] == 0) continue
            bladePaint.color = colors[s]; bladePaint.strokeWidth = m * (.0013f + .0008f * s)
            c.drawLines(lines[s], 0, counts[s], bladePaint)
        }
    }
}

/* ----------------------------------------------------------------------------------------------------
 * Super: a nebula made of real layered noise that drifts and shifts colour, stars at three depths with a few
 * bright ones that sparkle, ribbons of rainbow aurora that flow across the sky, and a ringed planet to look at.
 * -------------------------------------------------------------------------------------------------- */

/** Seamless fractal noise (0..1), so it can be tiled and scrolled without a visible edge. */
private fun noiseTile(n: Int, seed: Long, octaves: Int): FloatArray {
    val out = FloatArray(n * n); var amp = 1f; var total = 0f
    for (o in 0 until octaves) {
        val cells = 4 shl o; val r = Random(seed + o * 101)
        val grid = FloatArray(cells * cells) { r.nextFloat() }
        for (y in 0 until n) for (x in 0 until n) {
            val gx = x * cells / n.toFloat(); val gy = y * cells / n.toFloat()
            val x0 = gx.toInt() % cells; val y0 = gy.toInt() % cells; val x1 = (x0 + 1) % cells; val y1 = (y0 + 1) % cells
            var fx = gx - gx.toInt(); var fy = gy - gy.toInt(); fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
            val v = (grid[y0 * cells + x0] * (1 - fx) + grid[y0 * cells + x1] * fx) * (1 - fy) + (grid[y1 * cells + x0] * (1 - fx) + grid[y1 * cells + x1] * fx) * fy
            out[y * n + x] += v * amp
        }
        total += amp; amp *= .5f
    }
    for (i in out.indices) out[i] /= total
    return out
}

/** The noise turned into glowing gas: dark gaps, then deep colour, then bright cores. [hue] is the main colour. */
private fun nebulaTile(n: Int, seed: Long, hue: Float): Bitmap {
    val f = noiseTile(n, seed, 5); val px = IntArray(n * n)
    for (i in px.indices) {
        val v = ((f[i] - .38f) * 2.4f).coerceIn(0f, 1f)
        val a = (v * v * 255f).toInt().coerceIn(0, 255)
        val core = (v * v * v)
        px[i] = Color.HSVToColor(a, floatArrayOf((hue + core * 50f) % 360f, (.85f - core * .55f).coerceIn(0f, 1f), (.55f + v * .5f).coerceIn(0f, 1f)))
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
}

class SuperScene(w: Int, h: Int) : Scene(w, h, 21) {
    private class Star(val x: Float, val y: Float, val r: Float, val ph: Float, val depth: Float, val warm: Float)
    private val stars = List(260) { Star(rnd.nextFloat() * w, rnd.nextFloat() * h, .5f + rnd.nextFloat() * 1.4f, rnd.nextFloat() * 6.28f, .25f + rnd.nextFloat() * .75f, rnd.nextFloat()) }
    private val bright = List(9) { Star(rnd.nextFloat() * w, rnd.nextFloat() * h * .85f, 2.2f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f, 1f, rnd.nextFloat()) }
    private val gasA = nebulaTile(160, 3, 275f)
    private val gasB = nebulaTile(160, 9, 175f)
    private val gasC = nebulaTile(160, 14, 320f)
    private val glow = glowSprite(256, 0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF)
    private val star = glowSprite(48, 0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF)
    private val ring = ringSprite(512)
    private var base: Bitmap? = null
    private val addPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = ADD_MODE }
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private class Ring(val x: Float, val y: Float, var age: Float, val hue: Float)
    private val rings = ArrayList<Ring>()
    private var flash = 0f
    private val src = android.graphics.Rect(); private val dst = RectF()
    private val aurora = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; xfermode = ADD_MODE }

    override fun step(dt: Float, env: Env, cfg: SceneConfig) {
        super.step(dt, env, cfg)
        scheduleEvents(dt, cfg, true) { rings.add(Ring(rnd.nextFloat() * w, rnd.nextFloat() * h, 0f, rnd.nextFloat() * 360f)); flash = .25f }
        val it = rings.iterator(); while (it.hasNext()) { val r = it.next(); r.age += dt; if (r.age > 5f) it.remove() }
        if (flash > 0) flash -= dt
    }

    override fun tap(x: Float, y: Float) { rings.add(Ring(x, y, 0f, (time * 40f) % 360f)) }

    private fun backdrop(): Bitmap {
        if (base == null) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint()
            p.shader = LinearGradient(0f, 0f, w * .4f, h.toFloat(), intArrayOf(0xFF04031A.toInt(), 0xFF120838.toInt(), 0xFF2A0C4E.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            // a soft vignette keeps the eye in the middle
            p.shader = RadialGradient(w / 2f, h * .5f, m * .75f, Color.TRANSPARENT, 0x88000008.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            base = b
        }
        return base!!
    }

    /** One layer of gas, much bigger than the screen, turning slowly about the middle and tinted by the shifting colour. */
    private fun gas(c: Canvas, tile: Bitmap, scale: Float, turn: Float, tint: Int, alpha: Int, ox: Float, oy: Float) {
        val half = m * scale / 2f
        c.save(); c.translate(w / 2f + ox, h / 2f + oy); c.rotate(turn)
        src.set(0, 0, tile.width, tile.height); dst.set(-half, -half, half, half)
        addPaint.colorFilter = android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.MULTIPLY); addPaint.alpha = alpha
        c.drawBitmap(tile, src, dst, addPaint); c.restore(); addPaint.colorFilter = null
    }

    override fun draw(c: Canvas, env: Env, cfg: SceneConfig) {
        val hueBase = (time * 4f + env.hour * 12f) % 360f
        c.drawBitmap(backdrop(), 0f, 0f, null)
        gas(c, gasA, 2.3f, time * .45f, hsv(255, hueBase, .35f, 1f), (185 + 30 * sin(time * .21f)).toInt(), sin(time * .05f) * m * .06f, cos(time * .04f) * m * .05f)
        gas(c, gasB, 2.0f, -time * .6f + 40f, hsv(255, hueBase + 120f, .3f, 1f), (150 + 30 * sin(time * .17f + 2f)).toInt(), cos(time * .06f) * m * .08f, sin(time * .05f) * m * .06f)
        gas(c, gasC, 1.6f, time * .8f + 90f, hsv(255, hueBase + 240f, .4f, 1f), (105 + 30 * sin(time * .27f + 4f)).toInt(), -sin(time * .07f) * m * .1f, cos(time * .06f) * m * .08f)
        // stars at three depths drifting slowly, cooler and warmer ones
        src.set(0, 0, star.width, star.height)
        for (s in stars) {
            val y = (s.y + time * m * .004f * s.depth) % h
            val tw = .4f + .6f * (sin(time * 1.8f * s.depth + s.ph) * .5f + .5f)
            val r = s.r * 2.4f
            addPaint.colorFilter = android.graphics.PorterDuffColorFilter(blend(0xFFBFD4FF.toInt(), 0xFFFFE2B0.toInt(), s.warm), android.graphics.PorterDuff.Mode.MULTIPLY)
            addPaint.alpha = (255 * tw * s.depth).toInt().coerceIn(0, 255); dst.set(s.x - r, y - r, s.x + r, y + r); c.drawBitmap(star, src, dst, addPaint)
        }
        addPaint.colorFilter = null
        // aurora: ribbons of rainbow light, each a wave that keeps flowing, drawn as three passes (wide and faint, then tight and bright)
        for (i in 0 until 6) {
            val path = Path(); val yBase = h * (.2f + i * .035f); val steps = 14
            for (k in 0..steps) {
                val x = -w * .05f + (w * 1.1f) * k / steps
                val y = yBase + sin(x * .0042f + time * .5f + i * .55f) * h * .055f + sin(x * .0021f - time * .31f + i) * h * .045f
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val col = hsv(255, hueBase + i * 48f, .7f, 1f)
            for ((wd, a) in listOf(.045f to 22, .022f to 40, .007f to 110)) {
                aurora.strokeWidth = m * wd; aurora.color = (col and 0x00FFFFFF) or (a shl 24); c.drawPath(path, aurora)
            }
        }
        // a ringed planet low in the corner: glow, sphere, ring passing in front and behind
        val pr = m * .115f; val px = w * .74f; val py = h * .8f + sin(time * .15f) * m * .006f
        src.set(0, 0, glow.width, glow.height); dst.set(px - pr * 2.6f, py - pr * 2.6f, px + pr * 2.6f, py + pr * 2.6f)
        addPaint.colorFilter = android.graphics.PorterDuffColorFilter(hsv(255, hueBase + 30f, .5f, 1f), android.graphics.PorterDuff.Mode.MULTIPLY); addPaint.alpha = 120; c.drawBitmap(glow, src, dst, addPaint); addPaint.colorFilter = null
        val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        fun planetRing(front: Boolean) {
            c.save(); c.translate(px, py); c.rotate(-18f)
            for ((k, rw) in listOf(1.55f to .035f, 1.75f to .05f, 1.98f to .02f)) {
                ringP.strokeWidth = pr * rw * 2.2f; ringP.color = Color.HSVToColor(if (k == 1.75f) 150 else 105, floatArrayOf((hueBase + 60f + k * 40f) % 360f, .35f, 1f))
                c.drawArc(RectF(-pr * k, -pr * k * .28f, pr * k, pr * k * .28f), if (front) 0f else 180f, 180f, false, ringP)
            }
            c.restore()
        }
        planetRing(false)
        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        body.shader = RadialGradient(px - pr * .38f, py - pr * .4f, pr * 1.5f, intArrayOf(hsv(255, hueBase + 20f, .25f, 1f), hsv(255, hueBase + 30f, .7f, .85f), hsv(255, hueBase + 50f, .9f, .22f)), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(px, py, pr, body)
        // soft bands across the planet, and a bright rim of atmosphere on the lit edge
        body.shader = null; body.style = Paint.Style.STROKE
        for (b in 0 until 5) { body.strokeWidth = pr * .09f; body.color = Color.argb(36, 255, 255, 255); c.drawArc(RectF(px - pr * .98f, py - pr * (.55f - b * .26f) - pr * .1f, px + pr * .98f, py - pr * (.55f - b * .26f) + pr * .1f), 0f, 180f, false, body) }
        body.strokeWidth = pr * .05f; body.color = Color.argb(110, 255, 255, 255); c.drawArc(RectF(px - pr, py - pr, px + pr, py + pr), 180f, 110f, false, body)
        planetRing(true)
        // sparkling bright stars with a cross of light
        for (s in bright) {
            val tw = .55f + .45f * sin(time * 1.2f + s.ph); val r = s.r * 3.2f
            src.set(0, 0, star.width, star.height); dst.set(s.x - r, s.y - r, s.x + r, s.y + r); addPaint.alpha = (255 * tw).toInt().coerceIn(0, 255); c.drawBitmap(star, src, dst, addPaint)
            val len = m * .02f * (.6f + tw); val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = m * .0012f; color = Color.argb((150 * tw).toInt(), 255, 255, 255) }
            c.drawLine(s.x - len, s.y, s.x + len, s.y, sp); c.drawLine(s.x, s.y - len, s.x, s.y + len, sp)
        }
        // taps send rings of light outward
        src.set(0, 0, ring.width, ring.height)
        for (rg in rings) {
            val f = rg.age / 5f; val r = f * m * .5f
            addPaint.colorFilter = android.graphics.PorterDuffColorFilter(hsv(255, rg.hue + rg.age * 60f, .6f, 1f), android.graphics.PorterDuff.Mode.MULTIPLY)
            addPaint.alpha = (200 * (1f - f)).toInt().coerceIn(0, 255); dst.set(rg.x - r, rg.y - r, rg.x + r, rg.y + r); c.drawBitmap(ring, src, dst, addPaint)
        }
        addPaint.colorFilter = null
        drawStreaks(c)
        if (flash > 0f) { plain.color = Color.argb((flash * 70).toInt(), 255, 255, 255); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), plain) }
    }
}

/** Runs a scene for a configuration: follows the clock, applies the rules and the colour shift. */
class SceneRunner(private val w: Int, private val h: Int) {
    private val scenes = HashMap<String, Scene>()
    private val env = Env(12f, 100, false)
    private var clock = 0f
    private val hue = Paint()

    private fun scene(id: String) = scenes.getOrPut(id) { when (id) { "bliss" -> BlissScene(w, h); "super" -> SuperScene(w, h); else -> AquaScene(w, h) } }

    /** The configuration after the rules and the battery have had their say. */
    fun effective(cfg: SceneConfig): Pair<String, Int> {
        var base = cfg.base; var shift = cfg.hueShift
        for (r in cfg.rules) {
            val on = when (r.whenKind) {
                "hours" -> env.hour.toInt().let { hh -> if (r.a <= r.b) hh in r.a until r.b else hh >= r.a || hh < r.b }
                "charging" -> env.charging
                "battery" -> env.battery < r.a && !env.charging
                else -> false
            }
            if (on) { if (r.base.isNotEmpty()) base = r.base; shift += r.hue }
        }
        if (cfg.batteryTint && env.battery < 20 && !env.charging) shift += 150
        return base to shift
    }

    fun update(dt: Float, cfg: SceneConfig, battery: Int, charging: Boolean) {
        clock += dt
        env.battery = battery; env.charging = charging
        val now = java.time.LocalTime.now()
        val real = now.hour + now.minute / 60f + now.second / 3600f
        env.hour = if (cfg.fixedHour >= 0) cfg.fixedHour else if (cfg.daySpeed == 1) Sun.sceneHour(real) else (real + clock * cfg.daySpeed / 3600f) % 24f
        scene(effective(cfg).first).step(dt, env, cfg)
    }

    fun draw(c: Canvas, cfg: SceneConfig) {
        val (base, shift) = effective(cfg)
        val s = scene(base)
        if (shift % 360 != 0) {
            val cm = ColorMatrix(); val a = Math.toRadians(shift.toDouble()); val cs = cos(a).toFloat(); val sn = sin(a).toFloat()
            cm.set(floatArrayOf(.213f + cs * .787f - sn * .213f, .715f - cs * .715f - sn * .715f, .072f - cs * .072f + sn * .928f, 0f, 0f,
                .213f - cs * .213f + sn * .143f, .715f + cs * .285f + sn * .140f, .072f - cs * .072f - sn * .283f, 0f, 0f,
                .213f - cs * .213f - sn * .787f, .715f - cs * .715f + sn * .715f, .072f + cs * .928f + sn * .072f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            hue.colorFilter = ColorMatrixColorFilter(cm)
            c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), hue); s.draw(c, env, cfg); c.restore()
        } else s.draw(c, env, cfg)
        if (cfg.chargingGlow && env.charging) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, h.toFloat(), 0f, h * .8f, Color.argb(90 + (40 * sin(clock * 2f)).toInt(), 80, 255, 160), Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(0f, h * .8f, w.toFloat(), h.toFloat(), p)
        }
    }

    fun tap(x: Float, y: Float, cfg: SceneConfig) { scene(effective(cfg).first).tap(x, y) }
    fun hour() = env.hour

    companion object {
        /** A still picture of a scene at some hour, for previews and when the live wallpaper is off. */
        fun still(cfg: SceneConfig, w: Int, h: Int, hour: Float? = null): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val r = SceneRunner(w, h)
            val c = cfg.copy(fixedHour = hour ?: if (cfg.fixedHour >= 0) cfg.fixedHour else java.time.LocalTime.now().let { it.hour + it.minute / 60f })
            repeat(6) { r.update(.5f, c, 100, false) }
            r.draw(Canvas(b), c)
            return b
        }
    }
}
