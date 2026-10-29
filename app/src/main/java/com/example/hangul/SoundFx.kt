package com.example.hangul

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object FxTiming {
    /** 폭죽이 터지는 시각(초). 소리와 화면 효과를 맞추려고 같이 씁니다. */
    val POPS = floatArrayOf(0.05f, 0.5f, 0.9f, 1.4f, 1.95f)
}

/** 효과음 파일 없이 코드로 팡파레/폭죽/띵동 소리를 만들어 재생합니다. */
class SoundFx {

    private val sampleRate = 22050
    private var fanfare: AudioTrack? = null
    private var chime: AudioTrack? = null

    init {
        try {
            fanfare = makeTrack(buildFanfare())
        } catch (e: Throwable) {
        }
        try {
            chime = makeTrack(buildChime())
        } catch (e: Throwable) {
        }
    }

    fun playFanfare() = play(fanfare)

    fun playChime() = play(chime)

    fun release() {
        try {
            fanfare?.release()
            chime?.release()
        } catch (e: Throwable) {
        }
    }

    private fun play(track: AudioTrack?) {
        if (track == null) return
        try {
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (e: Throwable) {
        }
    }

    private fun makeTrack(data: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(data, 0, data.size)
        return track
    }

    private fun buildFanfare(): ShortArray {
        val buf = FloatArray((3.0f * sampleRate).toInt())
        // 빠밤~ 빠바바밤! (도-미-솔-높은 도)
        tone(buf, 0.00f, 523f, 0.14f, 0.55f)
        tone(buf, 0.15f, 659f, 0.14f, 0.55f)
        tone(buf, 0.30f, 784f, 0.14f, 0.55f)
        tone(buf, 0.45f, 1046f, 0.60f, 0.65f)
        chord(buf, 0.45f, 0.90f, 0.35f, 523f, 659f, 784f)
        tone(buf, 1.45f, 784f, 0.15f, 0.55f)
        tone(buf, 1.62f, 1046f, 0.15f, 0.55f)
        tone(buf, 1.80f, 1318f, 0.15f, 0.55f)
        chord(buf, 2.00f, 0.95f, 0.40f, 523f, 659f, 784f, 1046f)
        for (p in FxTiming.POPS) pop(buf, p)
        return toPcm(buf)
    }

    private fun buildChime(): ShortArray {
        val buf = FloatArray((0.5f * sampleRate).toInt())
        tone(buf, 0.00f, 880f, 0.12f, 0.5f)
        tone(buf, 0.11f, 1318f, 0.30f, 0.5f)
        return toPcm(buf)
    }

    private fun chord(buf: FloatArray, start: Float, dur: Float, vol: Float, vararg freqs: Float) {
        for (f in freqs) tone(buf, start, f, dur, vol)
    }

    private fun tone(buf: FloatArray, start: Float, freq: Float, dur: Float, vol: Float) {
        val s0 = (start * sampleRate).toInt()
        val n = (dur * sampleRate).toInt()
        val release = min(0.12f, dur * 0.5f)
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= buf.size) break
            val t = i.toFloat() / sampleRate
            val attack = min(1f, t / 0.01f)
            val rel = if (t > dur - release) (dur - t) / release else 1f
            val w = sin(2.0 * PI * freq * t) +
                0.5 * sin(4.0 * PI * freq * t) +
                0.25 * sin(6.0 * PI * freq * t)
            buf[idx] += (w / 1.75).toFloat() * vol * attack * rel
        }
    }

    private fun pop(buf: FloatArray, start: Float) {
        val s0 = (start * sampleRate).toInt()
        val n = (0.3f * sampleRate).toInt()
        val rnd = Random(42 + s0)
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= buf.size) break
            val t = i.toFloat() / sampleRate
            val env = exp(-t * 16f)
            val noise = rnd.nextFloat() * 2f - 1f
            val thump = sin(2.0 * PI * 90.0 * t).toFloat()
            buf[idx] += (noise * 0.5f + thump * 0.5f) * env * 0.8f
        }
    }

    private fun toPcm(buf: FloatArray): ShortArray {
        var peak = 0f
        for (v in buf) peak = max(peak, abs(v))
        val scale = if (peak > 0.9f) 0.9f / peak else 1f
        return ShortArray(buf.size) {
            (buf[it] * scale * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
