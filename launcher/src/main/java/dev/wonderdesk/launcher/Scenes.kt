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

/** Water, light from above, floating bubbles you can pop, and now and then a fish. */
class AquaScene(w: Int, h: Int) : Scene(w, h, 3) {
    private val field = BubbleField(w, h, count = 28, seed = 11, popping = true)
    private class Fish(var x: Float, val y: Float, val dir: Int, val size: Float)
    private val fish = ArrayList<Fish>()
    private var bg: Bitmap? = null
    private var bgHour = -99f
    private var bgKey = ""

    override fun step(dt: Float, env: Env, cfg: SceneConfig) {
        super.step(dt, env, cfg)
        field.step(dt)
        scheduleEvents(dt, cfg, false) { fish.add(Fish(if (rnd.nextBoolean()) -m * .1f else w + m * .1f, h * (.3f + rnd.nextFloat() * .4f), 0, m * (.012f + rnd.nextFloat() * .01f)).also { f -> if (f.x > 0) fishDir[f] = -1 else fishDir[f] = 1 }) }
        val it = fish.iterator()
        while (it.hasNext()) { val f = it.next(); f.x += (fishDir[f] ?: 1) * m * .09f * dt; if (f.x < -m * .2f || f.x > w + m * .2f) { it.remove(); fishDir.remove(f) } }
    }
    private val fishDir = HashMap<Fish, Int>()

    override fun tap(x: Float, y: Float) { field.popAt(x, y) }

    private fun water(hour: Float): Bitmap {
        val key = "%.1f".format(hour)
        if (bg == null || key != bgKey) {
            val b = bg ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val night = nightness(hour)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, w * .15f, h.toFloat(), intArrayOf(WATER.at(hour, 0), WATER.at(hour, 1), WATER.at(hour, 2)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            val glow = blend(0x99FFFFFF.toInt(), 0x44A9D4FF, night)
            p.shader = RadialGradient(w * (.3f + .4f * ((hour % 24) / 24f)), -h * .04f, m * .75f, intArrayOf(glow, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(w * (.3f + .4f * ((hour % 24) / 24f)), -h * .04f, m * .75f, p); p.shader = null
            val rayAlpha = (1f - night * .65f) * 64
            listOf(.08f to .10f, .27f to .06f, .46f to .13f, .66f to .07f, .84f to .09f).forEach { (x0, wd) ->
                val top = w * x0; val sp = w * wd
                val path = Path().apply { moveTo(top - sp * .4f, 0f); lineTo(top + sp * .4f, 0f); lineTo(top + sp * 2.6f - w * .25f, h * .72f); lineTo(top - sp * 1.6f - w * .25f, h * .72f); close() }
                p.shader = LinearGradient(0f, 0f, 0f, h * .72f, Color.argb(rayAlpha.toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawPath(path, p)
            }
            p.shader = LinearGradient(0f, h * .55f, 0f, h.toFloat(), Color.TRANSPARENT, 0x66000A1C, Shader.TileMode.CLAMP); c.drawRect(0f, h * .55f, w.toFloat(), h.toFloat(), p)
            bg = b; bgKey = key
        }
        return bg!!
    }

    override fun draw(c: Canvas, env: Env, cfg: SceneConfig) {
        c.drawBitmap(water(env.hour), 0f, 0f, null)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 20, 40) }
        for (f in fish) {
            val d = fishDir[f] ?: 1
            c.save(); c.translate(f.x, f.y + sin(time * 2 + f.x * .01f) * f.size * .3f); c.scale(d.toFloat(), 1f)
            val body = Path().apply { moveTo(-f.size * 2, 0f); quadTo(0f, -f.size * 1.1f, f.size * 2f, 0f); quadTo(0f, f.size * 1.1f, -f.size * 2, 0f); close() }
            c.drawPath(body, p)
            val tail = Path().apply { moveTo(-f.size * 1.8f, 0f); lineTo(-f.size * 3.1f, -f.size); lineTo(-f.size * 3.1f, f.size); close() }
            c.drawPath(tail, p); c.restore()
        }
        field.draw(c)
    }
}

/** A grass field under a sky that goes through sunrise, noon, sunset and a night of stars. */
class BlissScene(w: Int, h: Int) : Scene(w, h, 7) {
    private class Cloud(var x: Float, val y: Float, val size: Float, val speed: Float, val seed: Int)
    private val clouds = List(7) { Cloud(rnd.nextFloat() * w, h * (.06f + rnd.nextFloat() * .3f), m * (.026f + rnd.nextFloat() * .03f), m * (.004f + rnd.nextFloat() * .01f), rnd.nextInt()) }
    private val stars = List(160) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h * .6f, .4f + rnd.nextFloat() * 1.4f, rnd.nextFloat() * 6.28f) }
    /** Each cloud is painted once, soft-edged, then tinted and moved every frame. */
    private val cloudBmp = clouds.map { cl ->
        val bw = (cl.size * 6.4f).toInt().coerceAtLeast(8); val bh = (cl.size * 3.2f).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val cv = Canvas(b)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; maskFilter = BlurMaskFilter(cl.size * .22f, BlurMaskFilter.Blur.NORMAL) }
        val r = Random(cl.seed.toLong())
        for (k in 0 until 6) cv.drawCircle(bw / 2f + (k - 2.5f) * cl.size * .55f, bh * .55f - sin(k * 1.1f) * cl.size * .2f + r.nextFloat() * cl.size * .1f, cl.size * (.45f + .25f * r.nextFloat()), pp)
        b
    }
    private val cloudPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val horizon = h * .62f
    private val blades = List(420) { floatArrayOf(rnd.nextFloat() * w, horizon + 8f + rnd.nextFloat().let { it * it } * (h - horizon), rnd.nextFloat() * 6.28f, .5f + rnd.nextFloat()) }
    private var rainbow = 0f
    private val seeds = ArrayList<FloatArray>()

    override fun step(dt: Float, env: Env, cfg: SceneConfig) {
        super.step(dt, env, cfg)
        for (c in clouds) { c.x += c.speed * dt; if (c.x - c.size * 2.2f > w) c.x = -c.size * 2.6f }
        scheduleEvents(dt, cfg, nightness(env.hour) > .6f) { if (nightness(env.hour) < .4f) rainbow = 1f }
        if (rainbow > 0f) rainbow = (rainbow - dt / 40f).coerceAtLeast(0f)
        val it = seeds.iterator(); while (it.hasNext()) { val s = it.next(); s[0] += s[2] * dt; s[1] += s[3] * dt; s[4] -= dt / 6f; if (s[4] <= 0f) it.remove() }
    }

    override fun tap(x: Float, y: Float) { repeat(7) { seeds.add(floatArrayOf(x, y, m * (.01f + rnd.nextFloat() * .06f), -m * (.02f + rnd.nextFloat() * .06f), 1f)) } }

    override fun draw(c: Canvas, env: Env, cfg: SceneConfig) {
        val hr = env.hour; val night = nightness(hr)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, horizon, SKY.at(hr, 0), SKY.at(hr, 1), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), horizon + 2, p); p.shader = null
        // stars
        if (night > .15f) for (s in stars) { p.color = Color.argb((255 * night * (.5f + .5f * sin(time * 1.5f + s[3]))).toInt().coerceIn(0, 255), 255, 255, 255); c.drawCircle(s[0], s[1], s[2], p) }
        drawStreaks(c)
        // sun and moon follow their own arcs
        fun arc(f: Float): Pair<Float, Float> = (w * (.08f + .84f * f)) to (horizon - sin(PI.toFloat() * f) * h * .5f + h * .02f)
        val sunF = (hr - 6f) / 12.7f
        if (sunF in -.05f..1.05f) {
            val (sx, sy) = arc(sunF.coerceIn(0f, 1f).let { if (sunF < 0 || sunF > 1) sunF else it })
            val low = 1f - sin(PI.toFloat() * sunF.coerceIn(0f, 1f))
            p.shader = RadialGradient(sx, sy, m * (.22f + .2f * low), intArrayOf(blend(0xAAFFF2B0.toInt(), 0xCCFF8A4B.toInt(), low), Color.TRANSPARENT), null, Shader.TileMode.CLAMP); c.drawCircle(sx, sy, m * .42f, p); p.shader = null
            p.color = blend(0xFFFFF6C8.toInt(), 0xFFFFC27A.toInt(), low); c.drawCircle(sx, sy, m * .035f, p)
        }
        val moonF = (((hr + 24f - 18f) % 24f)) / 12f
        if (moonF in 0f..1f) {
            val (mx, my) = arc(moonF)
            p.shader = RadialGradient(mx, my, m * .16f, intArrayOf(0x55CFE3FF, Color.TRANSPARENT), null, Shader.TileMode.CLAMP); c.drawCircle(mx, my, m * .16f, p); p.shader = null
            p.color = 0xFFF2F5FF.toInt(); c.drawCircle(mx, my, m * .03f, p)
            p.color = blend(SKY.at(hr, 0), SKY.at(hr, 1), .3f); c.drawCircle(mx + m * .012f, my - m * .006f, m * .026f, p)
        }
        // rainbow
        if (rainbow > 0f) {
            val a = (min(1f, rainbow * 3f) * min(1f, (1f - rainbow) * 6f + .3f) * 110).toInt()
            val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = m * .012f }
            for (i in 0 until 7) { rp.color = Color.HSVToColor(a, floatArrayOf(i * 42f, .8f, 1f)); c.drawArc(RectF(w * .05f + i * m * .011f, horizon - w * .7f + i * m * .011f, w * .95f - i * m * .011f, horizon + w * .6f - i * m * .011f), 180f, 180f, false, rp) }
        }
        // clouds, tinted by the sky
        val cloudCol = blend(blend(0xFFFFFFFF.toInt(), 0xFFFFC9A8.toInt(), (1f - abs(hr - 18.3f).coerceAtMost(1.6f) / 1.6f) * .8f), 0xFF3A4670.toInt(), night)
        cloudPaint.colorFilter = android.graphics.PorterDuffColorFilter(cloudCol, android.graphics.PorterDuff.Mode.MULTIPLY); cloudPaint.alpha = if (night > .5f) 120 else 235
        clouds.forEachIndexed { i, cl -> val bm = cloudBmp[i]; c.drawBitmap(bm, cl.x - bm.width / 2f, cl.y - bm.height * .55f, cloudPaint) }
        // hills and grass
        val far = GRASS.at(hr, 0); val near = GRASS.at(hr, 1)
        p.color = blend(far, SKY.at(hr, 1), .25f)
        val hill = Path().apply { moveTo(0f, horizon + 4); for (i in 0..24) lineTo(w * i / 24f, horizon - h * .035f * (sin(i * .55f) * .5f + .5f)); lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close() }
        c.drawPath(hill, p)
        p.shader = LinearGradient(0f, horizon, 0f, h.toFloat(), far, near, Shader.TileMode.CLAMP); c.drawRect(0f, horizon + h * .03f, w.toFloat(), h.toFloat(), p); p.shader = null
        val gust = sin(time * .35f) * .5f + .5f
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        for (b in blades) {
            val depth = (b[1] - horizon) / (h - horizon)
            val len = m * (.012f + .05f * depth) * b[3]
            val sway = (sin(time * 1.6f + b[0] * .012f + b[2]) * .5f + gust * 1.2f) * len * .55f
            gp.strokeWidth = m * (.0016f + .003f * depth)
            gp.color = blend(blend(near, far, .5f + .5f * (1f - b[3] / 1.5f)), 0xFFFFFFFF.toInt(), .12f * b[3]).let { blend(it, 0xFF000000.toInt(), (1f - depth) * .08f) }
            c.drawLine(b[0], b[1], b[0] + sway, b[1] - len, gp)
        }
        // tapped dandelion seeds drifting off
        gp.color = 0xCCFFFFFF.toInt()
        for (s in seeds) { gp.alpha = (s[4] * 220).toInt(); gp.strokeWidth = m * .002f; c.drawLine(s[0], s[1], s[0] + m * .008f, s[1] - m * .008f, gp); c.drawCircle(s[0], s[1], m * .003f, p.apply { color = Color.argb((s[4] * 220).toInt(), 255, 255, 255) }) }
        // warm light across the field at sunrise and sunset
        val warm = (1f - abs(hr - 6.8f).coerceAtMost(1.5f) / 1.5f).coerceAtLeast(1f - abs(hr - 18.5f).coerceAtMost(1.7f) / 1.7f)
        if (warm > 0f) { p.shader = LinearGradient(0f, horizon - h * .1f, 0f, h.toFloat(), Color.argb((warm * 70).toInt(), 255, 150, 70), Color.TRANSPARENT, Shader.TileMode.CLAMP); c.drawRect(0f, horizon - h * .1f, w.toFloat(), h.toFloat(), p); p.shader = null }
    }
}

/** Space, rainbows and glowing rings that do not stop pulsing. */
class SuperScene(w: Int, h: Int) : Scene(w, h, 21) {
    private val stars = List(320) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h, .5f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f) }
    private val blobs = List(6) { floatArrayOf(rnd.nextFloat() * w, rnd.nextFloat() * h, m * (.25f + rnd.nextFloat() * .3f), rnd.nextFloat() * 360f, rnd.nextFloat() * 6.28f) }
    private class Ring(val x: Float, val y: Float, var age: Float, val hue: Float)
    private val rings = ArrayList<Ring>()
    private var flash = 0f

    override fun step(dt: Float, env: Env, cfg: SceneConfig) {
        super.step(dt, env, cfg)
        scheduleEvents(dt, cfg, true) { rings.add(Ring(rnd.nextFloat() * w, rnd.nextFloat() * h, 0f, rnd.nextFloat() * 360f)); flash = .5f }
        val it = rings.iterator(); while (it.hasNext()) { val r = it.next(); r.age += dt; if (r.age > 5f) it.remove() }
        if (flash > 0) flash -= dt
    }

    override fun tap(x: Float, y: Float) { rings.add(Ring(x, y, 0f, (time * 40f) % 360f)) }

    override fun draw(c: Canvas, env: Env, cfg: SceneConfig) {
        val hueBase = (time * 6f + env.hour * 15f) % 360f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w * .6f, h.toFloat(), Color.HSVToColor(floatArrayOf((hueBase + 250f) % 360f, .9f, .22f)), Color.HSVToColor(floatArrayOf((hueBase + 300f) % 360f, 1f, .42f)), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        for (b in blobs) {
            val x = b[0] + sin(time * .12f + b[4]) * m * .08f; val y = b[1] + cos(time * .1f + b[4]) * m * .08f
            p.shader = RadialGradient(x, y, b[2], intArrayOf(Color.HSVToColor(170, floatArrayOf((b[3] + hueBase) % 360f, .9f, 1f)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP); c.drawCircle(x, y, b[2], p)
        }
        p.shader = null
        for (s in stars) { p.color = Color.argb((255 * (.35f + .65f * (sin(time * 2f + s[3]) * .5f + .5f))).toInt(), 255, 255, 255); c.drawCircle(s[0], s[1], s[2], p) }
        // rainbow arcs that breathe
        val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = m * .022f; maskFilter = BlurMaskFilter(m * .009f, BlurMaskFilter.Blur.NORMAL) }
        val pulse = .6f + .4f * sin(time * .8f)
        for (k in 0 until 2) for (i in 0 until 7) {
            rp.color = Color.HSVToColor((200 * pulse).toInt(), floatArrayOf(i * 51f, .85f, 1f))
            val cx = w * (if (k == 0) .15f else .85f); val cy = h * (if (k == 0) .72f else .3f); val r = m * (.35f + i * .016f + .03f * sin(time * .5f + k))
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), (time * 4f + k * 180f) % 360f, 200f, false, rp)
        }
        // glowing rings that spread out from the middle of the screen, and from taps
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; maskFilter = BlurMaskFilter(m * .008f, BlurMaskFilter.Blur.NORMAL) }
        for (i in 0 until 8) {
            val f = ((time * .14f + i / 8f) % 1f); gp.strokeWidth = m * .007f + f * m * .016f
            gp.color = Color.HSVToColor((235 * (1f - f)).toInt(), floatArrayOf((hueBase + i * 40f) % 360f, .85f, 1f)); c.drawCircle(w / 2f, h * .5f, f * m * .6f, gp)
        }
        for (r in rings) { val f = r.age / 5f; gp.strokeWidth = m * .006f; gp.color = Color.HSVToColor((200 * (1f - f)).toInt(), floatArrayOf((r.hue + r.age * 60f) % 360f, .7f, 1f)); c.drawCircle(r.x, r.y, f * m * .5f, gp) }
        drawStreaks(c)
        if (flash > 0f) { p.color = Color.argb((flash * 90).toInt(), 255, 255, 255); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p) }
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
        env.hour = if (cfg.fixedHour >= 0) cfg.fixedHour else if (cfg.daySpeed == 1) real else (real + clock * cfg.daySpeed / 3600f) % 24f
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
