package org.fm1.android

import java.io.IOException
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.*

/** WAV decode/trim and FSMP IMA-ADPCM, preserving the attributed codec format. */
object SampleAudio {
    data class Source(val name: String, val samples: FloatArray) {
        fun duration() = samples.size / 22050.0
    }

    private fun u16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun i32(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 255) or
            ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or
            (bytes[at + 3].toInt() shl 24)

    private fun tag(bytes: ByteArray, at: Int, text: String) =
        (0..3).all { bytes[at + it] == text[it].code.toByte() }

    fun decode(bytes: ByteArray, name: String): Source {
        if (bytes.size !in 44..32 * 1024 * 1024 || !tag(bytes, 0, "RIFF") || !tag(bytes, 8, "WAVE"))
            throw IOException("Choose a valid WAV file no larger than 32 MB.")
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var align = 0
        var data = -1
        var count = 0
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val size = Integer.toUnsignedLong(i32(bytes, cursor + 4))
            val end = cursor.toLong() + 8 + size
            if (end > bytes.size) throw IOException("The WAV file is incomplete.")
            if (tag(bytes, cursor, "fmt ")) {
                if (size < 16) throw IOException("Invalid WAV format.")
                format = u16(bytes, cursor + 8)
                channels = u16(bytes, cursor + 10)
                rate = i32(bytes, cursor + 12)
                align = u16(bytes, cursor + 20)
                bits = u16(bytes, cursor + 22)
            }
            if (tag(bytes, cursor, "data")) {
                data = cursor + 8
                count = size.toInt()
            }
            cursor = (end + (size and 1)).toInt()
        }
        if (
            data < 0 ||
                channels !in 1..2 ||
                rate !in 8000..192000 ||
                count < 1 ||
                !(format == 1 && bits in setOf(8, 16, 24, 32) || format == 3 && bits == 32) ||
                align != channels * bits / 8 ||
                count % align != 0
        )
            throw IOException("Use mono/stereo PCM or float WAV, 8–192 kHz.")
        val frames = count / align
        if (frames.toDouble() / rate > 120) throw IOException("Use a WAV up to two minutes long.")
        val source =
            FloatArray(frames) { frame ->
                var sum = 0.0
                for (channel in 0 until channels) {
                    val at = data + frame * align + channel * (bits / 8)
                    val value =
                        when {
                            format == 3 -> Float.fromBits(i32(bytes, at)).toDouble()
                            bits == 8 -> ((bytes[at].toInt() and 255) - 128) / 128.0
                            bits == 16 -> u16(bytes, at).toShort() / 32768.0
                            bits == 24 ->
                                (((bytes[at].toInt() and 255) shl 8) or
                                    ((bytes[at + 1].toInt() and 255) shl 16) or
                                    (bytes[at + 2].toInt() shl 24)) / 2147483648.0
                            else -> i32(bytes, at) / 2147483648.0
                        }
                    if (!value.isFinite()) throw IOException("The WAV contains invalid audio.")
                    sum += value
                }
                (sum / channels).coerceIn(-1.0, 1.0).toFloat()
            }
        val length = maxOf(1, ceil(frames * 22050.0 / rate).toInt())
        val cutoff = minOf(1.0, 22050.0 / rate) * .94
        // Windowed-sinc filtering keeps downsampled audio free of aliasing.
        val samples =
            FloatArray(length) { i ->
                val x = i.toDouble() * rate / 22050
                val center = x.toInt()
                var sum = 0.0
                var weight = 0.0
                for (offset in -24..24) {
                    val index = (center + offset).coerceIn(0, frames - 1)
                    val t = (center + offset - x) * cutoff
                    val sinc = if (abs(t) < 1e-9) 1.0 else sin(PI * t) / (PI * t)
                    val window = .5 + .5 * cos(PI * (center + offset - x) / 25)
                    val w = sinc * window * cutoff
                    sum += source[index] * w
                    weight += w
                }
                (sum / weight).coerceIn(-1.0, 1.0).toFloat()
            }
        return Source(name, samples)
    }

    private fun region(source: Source, start: Double, end: Double): IntRange {
        if (
            !start.isFinite() ||
                !end.isFinite() ||
                start < 0 ||
                end <= start ||
                end > source.duration() + .00001
        )
            throw IOException("Choose a valid start and end within the sound.")
        return floor(start * 22050).toInt() until
            minOf(source.samples.size, ceil(end * 22050).toInt())
    }

    fun encode(
        source: Source,
        start: Double,
        end: Double,
        normalize: Boolean,
        root: Int,
    ): ByteArray {
        val range = region(source, start, end)
        val n = range.count()
        if (n !in 1..162816 || root !in 0..127)
            throw IOException("Trim the sound to fit the 80 KiB sample slot (7.38 seconds).")
        val peak = range.maxOf { abs(source.samples[it]) }
        val gain = if (normalize && peak > 0) 30000 / (32768.0 * peak) else 1.0
        val encoded = ByteArray((n + 1) / 2)
        var predictor = 0
        var index = 0
        for (i in 0 until n) {
            val sample =
                (source.samples[range.first + i] * gain * 32768).coerceIn(-32768.0, 32767.0).toInt()
            val step = STEPS[index]
            var diff = sample - predictor
            var code = 0
            var delta = step shr 3
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            if (diff >= step) {
                code = code or 4
                diff -= step
                delta += step
            }
            if (diff >= (step shr 1)) {
                code = code or 2
                diff -= step shr 1
                delta += step shr 1
            }
            if (diff >= (step shr 2)) {
                code = code or 1
                delta += step shr 2
            }
            predictor = (predictor + if (code and 8 != 0) -delta else delta).coerceIn(-32768, 32767)
            index = (index + ADJUST[code and 7]).coerceIn(0, 88)
            encoded[i / 2] =
                if (i and 1 == 0) code.toByte()
                else (encoded[i / 2].toInt() or (code shl 4)).toByte()
        }
        return ByteArray(81920) { 0xff.toByte() }
            .also { slot ->
                slot.fill(0, 0, 512)
                put32(slot, 0, 0x504d5346)
                put16(slot, 4, 1)
                slot[6] = 1
                source.name
                    .uppercase(Locale.ROOT)
                    .filter { it.code in 32..126 }
                    .take(8)
                    .forEachIndexed { i, c -> slot[8 + i] = c.code.toByte() }
                put32(slot, 16, encoded.size)
                put32(slot, 20, CRC32().apply { update(encoded) }.value.toInt())
                put32(slot, 36, n)
                put32(slot, 44, n - 1)
                put32(slot, 48, 32768)
                put16(slot, 52, root * 16)
                slot[58] = 127
                encoded.copyInto(slot, 512)
            }
    }

    fun preview(source: Source, start: Double, end: Double): ByteArray {
        val range = region(source, start, end)
        val pcm = ByteArray(range.count() * 2)
        range.forEachIndexed { i, sourceIndex ->
            put16(
                pcm,
                i * 2,
                (source.samples[sourceIndex] * 32768).coerceIn(-32768f, 32767f).toInt(),
            )
        }
        return header(pcm.size / 2, 22050, 1) + pcm
    }

    internal fun header(frames: Int, rate: Int, channels: Int): ByteArray =
        ByteArray(44).also { bytes ->
            for ((at, text) in listOf(0 to "RIFF", 8 to "WAVE", 12 to "fmt ", 36 to "data")) text
                .forEachIndexed { i, c -> bytes[at + i] = c.code.toByte() }
            put32(bytes, 4, frames * channels * 2 + 36)
            put32(bytes, 16, 16)
            put16(bytes, 20, 1)
            put16(bytes, 22, channels)
            put32(bytes, 24, rate)
            put32(bytes, 28, rate * channels * 2)
            put16(bytes, 32, channels * 2)
            put16(bytes, 34, 16)
            put32(bytes, 40, frames * channels * 2)
        }

    internal fun put16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
    }

    internal fun put32(bytes: ByteArray, at: Int, value: Int) {
        for (i in 0..3) bytes[at + i] = (value shr (i * 8)).toByte()
    }

    private val ADJUST = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    private val STEPS =
        intArrayOf(
            7,
            8,
            9,
            10,
            11,
            12,
            13,
            14,
            16,
            17,
            19,
            21,
            23,
            25,
            28,
            31,
            34,
            37,
            41,
            45,
            50,
            55,
            60,
            66,
            73,
            80,
            88,
            97,
            107,
            118,
            130,
            143,
            157,
            173,
            190,
            209,
            230,
            253,
            279,
            307,
            337,
            371,
            408,
            449,
            494,
            544,
            598,
            658,
            724,
            796,
            876,
            963,
            1060,
            1166,
            1282,
            1411,
            1552,
            1707,
            1878,
            2066,
            2272,
            2499,
            2749,
            3024,
            3327,
            3660,
            4026,
            4428,
            4871,
            5358,
            5894,
            6484,
            7132,
            7845,
            8630,
            9493,
            10442,
            11487,
            12635,
            13899,
            15289,
            16818,
            18500,
            20350,
            22385,
            24623,
            27086,
            29794,
            32767,
        )
}
