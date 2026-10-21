package org.fm1.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.*
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Serial native ownership, bounded prepared PCM, and durable private storage. */
class EngineController(context: Context) {
    @JvmField @Volatile var selectedKind = 2
    @JvmField @Volatile var paused = false
    @JvmField @Volatile var muted = false
    @JvmField @Volatile var status = "Starting instrument…"
    @JvmField @Volatile var pixels = IntArray(57600)
    @JvmField @Volatile var leds = FloatArray(41)
    @JvmField @Volatile var producedFrames = 0L
    @JvmField @Volatile var outputFrames = 0L
    @JvmField @Volatile var underflowFrames = 0L
    @JvmField @Volatile var peak = 0.0
    @JvmField @Volatile var diagnostics = false
    @JvmField @Volatile var checkpointCaptureMaxMicros = 0L
    @JvmField @Volatile var checkpointWriteMaxMicros = 0L
    @JvmField @Volatile var snapshotMaxMicros = 0L
    @JvmField @Volatile var checkpointCount = 0L
    @JvmField @Volatile var producerMaxGapMicros = 0L
    @JvmField @Volatile var lastUnderflowProducerAgeMicros = 0L
    @JvmField @Volatile var lastUnderflowOutputIntervalMicros = 0L
    @JvmField @Volatile var pcmWaitCount = 0L
    @JvmField @Volatile var pcmWaitMaxMicros = 0L
    @Volatile private var lastTickNanos = 0L

    interface Listener {
        fun onFrame()

        fun onStatus()
    }

    fun interface ExportCallback {
        fun complete(file: File?, error: Exception?)
    }

    @Volatile private var frameListener: Listener? = null
    private val context = context.applicationContext
    private val directory = File(context.filesDir, "Instruments").apply { mkdirs() }
    private val projects = File(context.filesDir, "Projects").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("fm1", 0)
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("FM1 native owner", Process.THREAD_PRIORITY_AUDIO)
    private val worker: Handler
    private val ringLock = java.lang.Object()
    private val ring = FloatArray(8192 * 2)
    private val renderBuffer = FloatArray(1024)
    private var read = 0
    private var write = 0
    private var available = 0
    @Volatile private var closed = false
    @Volatile private var foreground = true
    @Volatile private var ready = false
    private var machine = 0L
    private var lastSnapshot = 0L
    private var lastSave = 0L
    private val snapshotPixels = IntArray(57600)
    private val snapshotLeds = FloatArray(41)
    private var pendingPixels = IntArray(57600)
    private var pendingLeds = FloatArray(41)
    private val framePending = AtomicBoolean(false)
    private val deliverFrame = Runnable {
        val previousPixels = pixels
        val previousLeds = leds
        pixels = pendingPixels
        leds = pendingLeds
        pendingPixels = previousPixels
        pendingLeds = previousLeds
        try {
            frameListener?.onFrame()
        } finally {
            framePending.set(false)
        }
    }
    private val storage =
        Executors.newSingleThreadExecutor { task ->
            Thread(
                {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                },
                "FM1 durable saves",
            )
        }
    private var pendingStoreWrite: Future<*>? = null
    @Volatile private var exportThread: Thread? = null
    @JvmField val audioBufferFrames: Int
    private var masterValue = prefs.getInt("master", 716)
    private val track: AudioTrack
    private val audioThread: Thread
    private val tick =
        object : Runnable {
            override fun run() {
                if (closed) return
                if (diagnostics) {
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (lastTickNanos != 0L)
                        producerMaxGapMicros =
                            maxOf(producerMaxGapMicros, (now - lastTickNanos) / 1000)
                    lastTickNanos = now
                }
                try {
                    if (running()) {
                        // Refill the same bounded target completely before optional UI/storage
                        // work.
                        var batches = 0
                        while (batches < 2 && synchronized(ringLock) { 1024 - available } >= 512) {
                            batches++
                            if (NativeEngine.render(machine, renderBuffer, 512) != 512)
                                throw IOException(
                                    "The native instrument did not return complete audio."
                                )
                            for (value in renderBuffer) {
                                if (!value.isFinite())
                                    throw IOException("The instrument returned invalid audio.")
                                peak = maxOf(peak, abs(value.toDouble()))
                            }
                            synchronized(ringLock) {
                                for (i in 0 until 1024) ring[(write * 2 + i) % ring.size] =
                                    renderBuffer[i]
                                write = (write + 512) % 8192
                                available += 512
                                ringLock.notifyAll()
                            }
                            producedFrames += 512
                        }
                        val now = SystemClock.uptimeMillis()
                        if (now - lastSnapshot >= 33) {
                            publishFrame()
                            lastSnapshot = now
                        }
                        if (now - lastSave >= 10000) {
                            checkpoint(waitForDisk = false)
                            lastSave = now
                        }
                    }
                } catch (error: Exception) {
                    ready = false
                    clearRing()
                    failure(error)
                }
                worker.postDelayed(this, 2)
            }
        }

    init {
        selectedKind = prefs.getInt("instrument", 2).takeIf { it in 2..9 } ?: 2
        muted = prefs.getBoolean("muted", false)
        workerThread.start()
        worker = Handler(workerThread.looper)
        val minimum =
            AudioTrack.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
        track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minimum, 1024 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        track.setBufferSizeInFrames(maxOf(512, minimum / 8))
        audioBufferFrames = track.bufferSizeInFrames
        audioThread = Thread(::audioLoop, "FM1 PCM output").also { it.start() }
        worker.post {
            load(selectedKind)
            worker.post(tick)
        }
    }

    fun setListener(listener: Listener?) {
        frameListener = listener
    }

    private fun notifyStatus() {
        main.post { frameListener?.onStatus() }
    }

    private fun message(value: String) {
        status = value
        notifyStatus()
    }

    private fun failure(error: Exception) {
        Log.e("FM1", "Operation failed", error)
        message(error.message ?: "The operation could not be completed.")
    }

    private fun clearRing() =
        synchronized(ringLock) {
            read = 0
            write = 0
            available = 0
            ringLock.notifyAll()
        }

    private fun running() = ready && !paused && foreground && !closed

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val output = FloatArray(1024)
        track.play()
        var previousOutputNanos = 0L
        while (!closed) {
            val iterationNanos = if (diagnostics) SystemClock.elapsedRealtimeNanos() else 0L
            var count = 0
            var play = running()
            synchronized(ringLock) {
                if (play && available < 512) {
                    // AudioTrack may accept a burst of writes before its next mixer cycle.
                    // Give the independent producer at most half a render quantum; inserting
                    // silence immediately here creates avoidable gaps while OS PCM is queued.
                    val started = SystemClock.elapsedRealtimeNanos()
                    val deadline = started + 6_000_000L
                    while (running() && available < 512) {
                        val remaining = deadline - SystemClock.elapsedRealtimeNanos()
                        if (remaining <= 0) break
                        ringLock.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                    }
                    if (diagnostics) {
                        pcmWaitCount++
                        pcmWaitMaxMicros =
                            maxOf(
                                pcmWaitMaxMicros,
                                (SystemClock.elapsedRealtimeNanos() - started) / 1000,
                            )
                    }
                }
                play = running()
                if (play) {
                    count = minOf(512, available)
                    for (i in 0 until count * 2) output[i] = ring[(read * 2 + i) % ring.size]
                    read = (read + count) % 8192
                    available -= count
                }
            }
            output.fill(0f, count * 2, output.size)
            if (muted) output.fill(0f)
            if (play) {
                if (diagnostics && count < 512) {
                    lastUnderflowProducerAgeMicros =
                        maxOf(0L, (SystemClock.elapsedRealtimeNanos() - lastTickNanos) / 1000)
                    if (previousOutputNanos != 0L)
                        lastUnderflowOutputIntervalMicros =
                            (iterationNanos - previousOutputNanos) / 1000
                }
                underflowFrames += 512 - count
                outputFrames += count
            }
            previousOutputNanos = iterationNanos
            val sent = track.write(output, 0, output.size, AudioTrack.WRITE_BLOCKING)
            if (sent < 0) {
                Log.e("FM1", "AudioTrack write $sent")
                break
            }
        }
        track.stop()
        track.release()
    }

    private fun packageBytes(kind: Int) =
        context.assets.open("engines/$kind.fwsc").use { readBounded(it, 4 * 1024 * 1024) }

    private fun saveFile(kind: Int) =
        File(directory, "$kind${if (kind == 6) ".preview.flash" else ".flash"}")

    /** Preserve the previous firmware's exact raw store before the first new-engine restore. */
    private fun preservePreUpdateSave(kind: Int, save: File) {
        if (
            kind !in intArrayOf(2, 5, 6, 7) ||
                prefs.getString("storageRevision-$kind", null) == STORAGE_REVISION ||
                !save.isFile
        )
            return
        val backups = File(directory, "Before-0.3.0")
        if (!backups.isDirectory && !backups.mkdirs())
            throw IOException("Could not preserve the previous instrument save. It has been kept.")
        val backup = File(backups, save.name)
        if (!backup.exists()) atomicWrite(backup, Files.readAllBytes(save.toPath()))
    }

    private fun load(kind: Int) {
        var candidate = 0L
        val wasReady = ready
        ready = false
        clearRing()
        try {
            awaitStoreWrites()
            if (machine != 0L) {
                NativeEngine.release(machine)
                checkpoint()
            }
            val save = saveFile(kind)
            preservePreUpdateSave(kind, save)
            candidate = NativeEngine.create(packageBytes(kind), kind)
            if (save.isFile && !NativeEngine.restore(candidate, Files.readAllBytes(save.toPath())))
                throw IOException("Saved instrument data could not be restored. It has been kept.")
            NativeEngine.volume(candidate, masterValue)
            if (machine != 0L) NativeEngine.destroy(machine)
            machine = candidate
            candidate = 0
            selectedKind = kind
            prefs
                .edit()
                .putInt("instrument", kind)
                .putString("storageRevision-$kind", STORAGE_REVISION)
                .apply()
            clearRing()
            ready = true
            peak = 0.0
            publishFrame()
            message("")
        } catch (error: Exception) {
            if (candidate != 0L) NativeEngine.destroy(candidate)
            ready = wasReady
            failure(error)
        }
    }

    private fun publishFrame() {
        val started = if (diagnostics) SystemClock.elapsedRealtimeNanos() else 0L
        NativeEngine.snapshot(machine, snapshotPixels, snapshotLeds)
        val changed = !pixels.contentEquals(snapshotPixels) || !leds.contentEquals(snapshotLeds)
        if (changed && framePending.compareAndSet(false, true)) {
            // The pending arrays are reused only after the main thread finishes its synchronous
            // copy.
            snapshotPixels.copyInto(pendingPixels)
            snapshotLeds.copyInto(pendingLeds)
            main.post(deliverFrame)
        }
        if (diagnostics)
            snapshotMaxMicros =
                maxOf(snapshotMaxMicros, (SystemClock.elapsedRealtimeNanos() - started) / 1000)
    }

    private fun awaitStoreWrites() {
        val pending = pendingStoreWrite ?: return
        try {
            pending.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while saving instrument data.", error)
        } catch (error: ExecutionException) {
            throw IOException("Instrument data could not be saved.", error.cause)
        } finally {
            if (pendingStoreWrite === pending) pendingStoreWrite = null
        }
    }

    /**
     * Every raw-store write shares one FIFO, including transactional project/sample replacement.
     */
    private fun writeStore(file: File, bytes: ByteArray, waitForDisk: Boolean) {
        pendingStoreWrite =
            storage.submit(
                Runnable {
                    val started = if (diagnostics) SystemClock.elapsedRealtimeNanos() else 0L
                    try {
                        atomicWrite(file, bytes)
                    } catch (error: Exception) {
                        if (!waitForDisk) worker.post { failure(error) }
                        throw error
                    } finally {
                        if (diagnostics)
                            checkpointWriteMaxMicros =
                                maxOf(
                                    checkpointWriteMaxMicros,
                                    (SystemClock.elapsedRealtimeNanos() - started) / 1000,
                                )
                    }
                }
            )
        if (waitForDisk) awaitStoreWrites()
    }

    private fun checkpoint(waitForDisk: Boolean = true) {
        if (machine == 0L) return
        // Never accumulate an unbounded queue if storage is slow; the next interval captures fresh
        // state.
        if (!waitForDisk && pendingStoreWrite?.isDone == false) return
        val started = if (diagnostics) SystemClock.elapsedRealtimeNanos() else 0L
        val bytes = NativeEngine.flash(machine)
        if (diagnostics) {
            checkpointCaptureMaxMicros =
                maxOf(
                    checkpointCaptureMaxMicros,
                    (SystemClock.elapsedRealtimeNanos() - started) / 1000,
                )
            checkpointCount++
        }
        writeStore(saveFile(selectedKind), bytes, waitForDisk)
    }

    fun selectEngine(kind: Int) {
        if (kind !in 2..9) return
        message("Loading instrument…")
        worker.post { load(kind) }
    }

    fun key(id: Int, down: Boolean) {
        worker.post { if (machine != 0L && running()) NativeEngine.key(machine, id, down) }
    }

    fun encoder(id: Int, delta: Int) {
        worker.post {
            if (machine != 0L) NativeEngine.encoder(machine, id, delta.coerceIn(-32, 32))
        }
    }

    fun setMaster(value: Int) {
        masterValue = value.coerceIn(0, 1023)
        prefs.edit().putInt("master", masterValue).apply()
        worker.post { if (machine != 0L) NativeEngine.volume(machine, masterValue) }
    }

    fun getMaster() = masterValue

    fun releaseAll() {
        worker.post { if (machine != 0L) NativeEngine.release(machine) }
    }

    fun setPaused(value: Boolean) {
        paused = value
        worker.post {
            clearRing()
            if (machine != 0L) {
                NativeEngine.release(machine)
                try {
                    checkpoint()
                } catch (error: Exception) {
                    failure(error)
                }
            }
        }
        notifyStatus()
    }

    fun setMuted(value: Boolean) {
        muted = value
        prefs.edit().putBoolean("muted", value).apply()
        notifyStatus()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        worker.post {
            clearRing()
            if (machine != 0L) {
                NativeEngine.release(machine)
                try {
                    checkpoint()
                } catch (error: Exception) {
                    failure(error)
                }
            }
        }
    }

    fun listProjects(): Array<String> =
        projects
            .listFiles { _, name -> name.endsWith(".fm1project") || name.endsWith(".fm1backup") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?.toTypedArray() ?: emptyArray()

    fun saveProject(name: String, backup: Boolean) {
        worker.post {
            try {
                requireSloop()
                val snapshot = ProjectSnapshot.capture(machine, masterValue)
                val file =
                    File(
                        projects,
                        "${safeName(name)}-${System.currentTimeMillis()}${if (backup) ".fm1backup" else ".fm1project"}",
                    )
                atomicWrite(file, snapshot.envelope(name, backup))
                message(if (backup) "Workspace backup saved." else "Project copy saved.")
            } catch (error: Exception) {
                failure(error)
            }
        }
    }

    fun openProject(filename: String) {
        worker.post {
            try {
                requireSloop()
                applyProject(Files.readAllBytes(projectFile(filename).toPath()), filename)
            } catch (error: Exception) {
                failure(error)
            }
        }
    }

    fun exportProject(filename: String): ByteArray =
        Files.readAllBytes(projectFile(filename).toPath())

    fun importProject(bytes: ByteArray, filename: String) {
        worker.post {
            try {
                requireSloop()
                ProjectSnapshot.fromEnvelope(bytes, filename)
                val target =
                    File(
                        projects,
                        "${UUID.randomUUID()}${if (filename.lowercase(Locale.ROOT).endsWith(".fm1backup")) ".fm1backup" else ".fm1project"}",
                    )
                atomicWrite(target, bytes)
                message("File imported. Open the saved copy when ready.")
            } catch (error: Exception) {
                failure(error)
            }
        }
    }

    private fun applyProject(bytes: ByteArray, filename: String) {
        var next = ProjectSnapshot.fromEnvelope(bytes, filename)
        val current = ProjectSnapshot.capture(machine, masterValue)
        if (!filename.lowercase(Locale.ROOT).endsWith(".fm1backup")) next = next.overlay(current)
        var candidate = next.create(packageBytes(2))
        try {
            atomicWrite(
                File(projects, "Recovery-${System.currentTimeMillis()}.fm1backup"),
                current.envelope("Recovery before opening", true),
            )
            writeStore(saveFile(2), NativeEngine.flash(candidate), waitForDisk = true)
            NativeEngine.release(machine)
            NativeEngine.destroy(machine)
            machine = candidate
            candidate = 0
            masterValue = next.master
            prefs.edit().putInt("master", masterValue).apply()
            clearRing()
            publishFrame()
            message("Opened as a copy. Previous work is in a recovery backup.")
        } finally {
            if (candidate != 0L) NativeEngine.destroy(candidate)
        }
    }

    fun importSample(
        source: SampleAudio.Source,
        start: Double,
        end: Double,
        normalize: Boolean,
        root: Int,
        slot: Int,
    ) {
        worker.post {
            try {
                requireSloop()
                val current = ProjectSnapshot.capture(machine, masterValue)
                val next = current.copy()
                next.parts["sample$slot"] = SampleAudio.encode(source, start, end, normalize, root)
                var candidate = next.create(packageBytes(2))
                try {
                    atomicWrite(
                        File(projects, "Recovery-${System.currentTimeMillis()}.fm1backup"),
                        current.envelope("Before sample import", true),
                    )
                    writeStore(saveFile(2), NativeEngine.flash(candidate), waitForDisk = true)
                    NativeEngine.destroy(machine)
                    machine = candidate
                    candidate = 0
                    clearRing()
                    publishFrame()
                    message("Sample imported to USR${slot + 1}. Recovery backup saved.")
                } finally {
                    if (candidate != 0L) NativeEngine.destroy(candidate)
                }
            } catch (error: Exception) {
                failure(error)
            }
        }
    }

    fun cancelExport() {
        exportThread?.interrupt()
    }

    fun exportAudio(wholeSong: Boolean, seconds: Int, tail: Int, callback: ExportCallback) {
        worker.post {
            try {
                requireSloop()
                if (exportThread?.isAlive == true)
                    throw IOException("Another audio export is still running.")
                val snapshot = ProjectSnapshot.capture(machine, masterValue)
                val data = packageBytes(2)
                exportThread =
                    Thread(
                            {
                                try {
                                    val file =
                                        AudioExport.render(
                                            context,
                                            snapshot,
                                            data,
                                            wholeSong,
                                            seconds,
                                            tail,
                                        )
                                    main.post { callback.complete(file, null) }
                                } catch (error: Exception) {
                                    main.post { callback.complete(null, error) }
                                }
                            },
                            "FM1 offline render",
                        )
                        .also { it.start() }
            } catch (error: Exception) {
                main.post { callback.complete(null, error) }
            }
        }
    }

    private fun requireSloop() {
        if (machine == 0L || selectedKind != 2)
            throw IOException("Project files require SLOOP 2.8.")
    }

    private fun projectFile(name: String): File =
        File(projects, name).also {
            if (it.canonicalFile.parentFile != projects.canonicalFile)
                throw IOException("Invalid project filename.")
        }

    fun close() {
        foreground = false
        cancelExport()
        worker.post {
            try {
                checkpoint()
            } catch (error: Exception) {
                failure(error)
            }
            if (machine != 0L) {
                NativeEngine.destroy(machine)
                machine = 0
            }
            closed = true
            storage.shutdown()
            workerThread.quitSafely()
        }
    }

    companion object {
        private const val STORAGE_REVISION = "0.3.0"
        @JvmField
        val ENGINE_NAMES =
            arrayOf(
                "SLOOP 2.8",
                "Felucca 1.6.1",
                "X0X 1.1.0",
                "Melodee 1.4.5",
                "FM1 Open V14 Preview",
                "Skiff 1.4",
                "FoMni 0.4",
                "Jangada 1.0.1",
            )
        @JvmField val ENGINE_KINDS = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9)

        fun safeName(value: String) =
            value.trim().replace(Regex("[^a-zA-Z0-9 _.-]"), "_").ifEmpty { "Project" }.take(80)

        fun readBounded(input: InputStream, maximum: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (out.size() + count > maximum) throw IOException("The file is too large.")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }

        fun atomicWrite(file: File, bytes: ByteArray) {
            val temporary = File(file.path + ".tmp")
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Could not finish saving the file.")
        }
    }
}
