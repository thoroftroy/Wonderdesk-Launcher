package dev.wonderdesk.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Soft desktop sounds, made on the phone the first time (nothing is downloaded): a pop when an app opens, a whoosh up
 * and down for folders and Beacon, a poof when you remove something, and a chime when a widget lands. They only play
 * while the ringer is on, at the phone's touch-sound volume, and can be switched off in Home screen settings.
 */
object Sounds {
    enum class S { Open, FolderOpen, FolderClose, Poof, Chime }
    private var pool: SoundPool? = null
    private val ids = HashMap<S, Int>()
    @Volatile var enabled = true

    fun init(c: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = p
        Thread {
            val dir = File(c.cacheDir, "sounds").apply { mkdirs() }
            S.entries.forEach { s -> val f = File(dir, "${s.name}-v1.wav"); if (!f.isFile) write(f, synth(s)); ids[s] = p.load(f.path, 1) }
        }.start()
    }

    fun play(c: Context, s: S) {
        if (!enabled) return
        val am = c.getSystemService(AudioManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val id = ids[s] ?: return
        pool?.play(id, .55f, .55f, 1, 0, 1f)
    }

    private const val RATE = 44100

    private fun synth(s: S): ShortArray {
        fun tone(ms: Int, f: (Double) -> Double): ShortArray { val n = RATE * ms / 1000; return ShortArray(n) { i -> (f(i.toDouble() / RATE) * 32767 * .8).toInt().coerceIn(-32767, 32767).toShort() } }
        val rnd = java.util.Random(7)
        return when (s) {
            // Short rounded pop with a quick upward pitch, like a bubble.
            S.Open -> tone(70) { t -> sin(2 * PI * (520 + 900 * t / .07) * t) * exp(-t * 55) }
            // Breathy rising and falling sweeps.
            S.FolderOpen -> { var lp = 0.0; tone(180) { t -> val noise = rnd.nextGaussian(); val a = .35 + .65 * (t / .18); lp += (noise - lp) * (.05 + .25 * t / .18); lp * sin(PI * t / .18) * a * 1.4 } }
            S.FolderClose -> { var lp = 0.0; tone(160) { t -> val noise = rnd.nextGaussian(); lp += (noise - lp) * (.30 - .25 * t / .16); lp * sin(PI * t / .16) * 1.3 } }
            // A soft puff of smoke.
            S.Poof -> { var lp = 0.0; tone(320) { t -> lp += (rnd.nextGaussian() - lp) * .12; lp * exp(-t * 11) * 1.6 } }
            // Two glassy notes.
            S.Chime -> tone(420) { t -> (sin(2 * PI * 1318.5 * t) * exp(-t * 9) + (if (t > .09) sin(2 * PI * 1975.5 * (t - .09)) * exp(-(t - .09) * 8) else .0)) * .55 }
        }
    }

    private fun write(f: File, pcm: ShortArray) {
        val data = pcm.size * 2
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            fun i32(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())) }
            fun i16(v: Int) { r.write(byteArrayOf(v.toByte(), (v shr 8).toByte())) }
            r.writeBytes("RIFF"); i32(36 + data); r.writeBytes("WAVE"); r.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
            r.writeBytes("data"); i32(data)
            val buf = java.nio.ByteBuffer.allocate(data).order(java.nio.ByteOrder.LITTLE_ENDIAN); pcm.forEach { buf.putShort(it) }; r.write(buf.array())
        }
    }
}
