package org.fm1.android

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CancellationException

/** Offline rendering owns a disposable native engine on its own worker. */
internal object AudioExport {
    fun render(
        context: Context,
        snapshot: ProjectSnapshot,
        packageData: ByteArray,
        song: Boolean,
        seconds: Int,
        tail: Int,
    ): File {
        if (seconds !in 1..120 || tail !in 0..10)
            throw IOException("Choose 1–120 seconds and 0–10 seconds of tail.")
        val copy = snapshot.copy()
        val arrangement = copy.parts.getValue("arrangement")
        if (song) {
            val entries = arrangement[10].toInt() and 255
            if (entries !in 1..16 || arrangement.size != 11 + entries * 2)
                throw IOException("Invalid song arrangement.")
            for (i in 0 until entries) {
                val section = arrangement[11 + i * 2].toInt() and 255
                val bars = arrangement[12 + i * 2].toInt() and 255
                if (
                    section > 3 || bars !in 1..64 || arrangement[2].toInt() and (1 shl section) == 0
                )
                    throw IOException("Save every song section A–D in SLOOP before exporting.")
            }
        }
        arrangement[3] = if (song) 1 else 0
        arrangement[9] = 0
        val folder = File(context.cacheDir, "FM1Export-${UUID.randomUUID()}")
        if (!folder.mkdir()) throw IOException("Could not create an audio export folder.")
        val file = File(folder, if (song) "SLOOP-song.wav" else "SLOOP-loop.wav")
        val machine = copy.create(packageData)
        var success = false
        try {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(SampleAudio.header(0, 44100, 2))
                val pcm = FloatArray(1024)
                val bytes = ByteArray(2048)
                NativeEngine.transport(machine, false)
                if (NativeEngine.render(machine, pcm, 512) != 512)
                    throw IOException("Incomplete preparatory audio.")
                if (!NativeEngine.transport(machine, true))
                    throw IOException("Song transport unavailable.")
                var written = 0
                val goal = seconds * 44100
                var started = false
                while (true) {
                    checkCancellation()
                    val frames = if (song) 128 else minOf(512, goal - written)
                    write(out, machine, pcm, bytes, frames)
                    written += frames
                    if (song) {
                        val state = NativeEngine.readPart(machine, 1, 0)
                        if (state.size < 13) throw IOException("Could not read song position.")
                        if (state[5].toInt() != 0) started = true
                        else if (started) break
                        else if (written >= 44100)
                            throw IOException(
                                "The song did not start. Check saved sections and clock settings."
                            )
                        if (written >= 30 * 60 * 44100)
                            throw IOException("Song export exceeded 30 minutes.")
                    } else if (written >= goal) break
                }
                NativeEngine.transport(machine, false)
                var left = tail * 44100
                while (left > 0) {
                    checkCancellation()
                    val n = minOf(512, left)
                    write(out, machine, pcm, bytes, n)
                    written += n
                    left -= n
                }
                out.seek(0)
                out.write(SampleAudio.header(written, 44100, 2))
                out.fd.sync()
                success = true
                return file
            }
        } finally {
            NativeEngine.destroy(machine)
            if (!success) file.delete()
        }
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted)
            throw CancellationException("Audio export canceled.")
    }

    private fun write(
        out: RandomAccessFile,
        machine: Long,
        pcm: FloatArray,
        bytes: ByteArray,
        frames: Int,
    ) {
        if (NativeEngine.render(machine, pcm, frames) != frames)
            throw IOException("The instrument could not render complete audio.")
        for (i in 0 until frames * 2) {
            if (!pcm[i].isFinite()) throw IOException("The instrument returned invalid audio.")
            SampleAudio.put16(bytes, i * 2, (pcm[i] * 32768).coerceIn(-32768f, 32767f).toInt())
        }
        out.write(bytes, 0, frames * 4)
    }
}
