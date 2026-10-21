package org.fm1.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.RectF
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.io.*
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.*
import org.json.*

/** SDK-only device checks; every native handle remains on this one worker. */
class EngineInstrumentation : Instrumentation() {
    private val results = JSONArray()
    private var failures = 0
    private var uiOnly = false
    private var exchangeTests = false
    private var captureBaseline = false
    private var migrationTests = false
    private val expectedUpdateBackups = linkedMapOf<Int, ByteArray>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        uiOnly = arguments?.getString("uiOnly") == "true"
        exchangeTests = arguments?.getString("exchange") == "true"
        captureBaseline = arguments?.getString("captureBaseline") == "true"
        migrationTests = arguments?.getString("migration") == "true"
        Thread(::runChecks, "fm1-instrumentation").start()
    }

    private fun check(name: String, test: () -> JSONObject) {
        val r = JSONObject()
        try {
            r.put("name", name).put("metrics", test()).put("passed", true)
        } catch (error: Throwable) {
            failures++
            r.put("name", name).put("passed", false).put("error", error.toString())
        }
        results.put(r)
        val status = Bundle()
        status.putString("stream", r.toString() + "\n")
        sendStatus(if (failures == 0) 0 else -1, status)
    }

    private fun require(value: Boolean, message: String) {
        if (!value) throw AssertionError(message)
    }

    private fun asset(kind: Int) =
        targetContext.assets.open("engines/$kind.fwsc").use { it.readBytes() }

    private fun advance(m: Long, frames: Int): Double {
        var remaining = frames
        val pcm = FloatArray(1024)
        var peak = 0.0
        while (remaining > 0) {
            val n = min(512, remaining)
            require(NativeEngine.render(m, pcm, n) == n, "incomplete native render")
            for (i in 0 until n * 2) {
                require(pcm[i].isFinite(), "nonfinite PCM")
                peak = max(peak, abs(pcm[i]).toDouble())
            }
            remaining -= n
        }
        return peak
    }

    private fun tap(m: Long, key: Int) {
        NativeEngine.key(m, key, true)
        advance(m, 6615)
        NativeEngine.key(m, key, false)
        advance(m, 1024)
    }

    private fun engine(kind: Int): JSONObject {
        val bytes = asset(kind)
        val m = NativeEngine.create(bytes, kind)
        try {
            require(m != 0L && NativeEngine.kind(m) == kind, "wrong compiled native kind")
            NativeEngine.volume(m, 716)
            advance(m, 23040)
            val key = if (kind == 4) 15 else 21
            NativeEngine.key(m, key, true)
            val peak = advance(m, 44544)
            require(peak > 0.00001, "silent note PCM")
            NativeEngine.key(m, key, false)
            NativeEngine.release(m)
            advance(m, 4096)
            val pixels = IntArray(57600)
            val leds = FloatArray(41)
            require(NativeEngine.snapshot(m, pixels, leds) != 0, "snapshot failed")
            val colors = pixels.count { it != pixels[0] }
            require(colors > 20, "blank LCD")
            for (led in leds) require(led.isFinite(), "nonfinite LED")
            val flash = NativeEngine.flash(m)
            if (captureBaseline) baselineFile("kind-$kind.flash", flash)
            require(flash.size == if (kind == 6) 536576 else 1048576, "wrong flash size")
            flash[flash.lastIndex] = (flash.last().toInt() xor 0x25).toByte()
            val candidate = NativeEngine.create(bytes, kind)
            try {
                require(
                    !NativeEngine.restore(candidate, flash.copyOf(flash.size - 1)),
                    "accepted truncated flash",
                )
                require(NativeEngine.restore(candidate, flash), "flash restore failed")
                require(
                    flash.contentEquals(NativeEngine.flash(candidate)),
                    "edited flash roundtrip differs",
                )
                NativeEngine.key(candidate, key, true)
                advance(candidate, 512)
                NativeEngine.release(candidate)
            } finally {
                NativeEngine.destroy(candidate)
            }
            return JSONObject()
                .put("kind", kind)
                .put("packageSHA256", ProjectSnapshot.digest(bytes))
                .put("peak", peak)
                .put("nonbackgroundPixels", colors)
                .put("flashBytes", flash.size)
        } finally {
            NativeEngine.destroy(m)
        }
    }

    private fun fingerprint(s: ProjectSnapshot) = buildString {
        append(s.master)
        for ((name, bytes) in s.parts) {
            append(name)
            append(ProjectSnapshot.digest(bytes))
        }
    }

    private fun workspace(): JSONObject {
        val bytes = asset(2)
        val m = NativeEngine.create(bytes, 2)
        try {
            advance(m, 22050)
            repeat(3) {
                NativeEngine.encoder(m, 1, 1)
                advance(m, 2205)
            }
            tap(m, 13)
            for (key in intArrayOf(14, 16, 18, 20, 21)) {
                tap(m, key)
                advance(m, 8820)
            }
            tap(m, 13)
            advance(m, 132300)
            tap(m, 12)
            NativeEngine.release(m)
            NativeEngine.transport(m, false)
            advance(m, 512)
            val original = ProjectSnapshot.capture(m, 716)
            val originalHash = fingerprint(original)
            val backup = original.envelope("Device test backup", true)
            val imported = ProjectSnapshot.fromEnvelope(backup, "test.fm1backup")
            require(fingerprint(imported) == originalHash, "backup envelope changed parts")
            val corrupt = JSONObject(String(backup, Charsets.UTF_8))
            corrupt
                .getJSONObject("snapshot")
                .getJSONArray("parts")
                .getJSONObject(0)
                .put("sha256", "bad")
            var rejected = false
            try {
                ProjectSnapshot.fromEnvelope(
                    corrupt.toString().toByteArray(Charsets.UTF_8),
                    "bad.fm1backup",
                )
            } catch (expected: Exception) {
                rejected = true
            }
            require(rejected, "bad workspace checksum accepted")
            val customized = original.copy()
            val settings = customized.parts.getValue("object1")
            require(settings.size >= 16, "missing settings fixture")
            settings[8] = (if (settings[8].toInt() == 0) 1 else 0).toByte()
            customized.master = 271
            var candidate = customized.create(bytes)
            try {
                val captured = ProjectSnapshot.capture(candidate, 271)
                require(
                    settings.contentEquals(captured.parts["object1"]),
                    "edited global settings lost",
                )
                require(
                    customized.parts["object0"].contentEquals(captured.parts["object0"]),
                    "candidate project changed",
                )
            } finally {
                NativeEngine.destroy(candidate)
            }
            val project =
                ProjectSnapshot.fromEnvelope(
                    original.envelope("Device project", false),
                    "test.fm1project",
                )
            val overlay = project.overlay(customized)
            require(
                overlay.master == 271 && overlay.parts["object1"].contentEquals(settings),
                "project overlay lost current globals",
            )
            val tone = ByteArray(44 + 22050 * 2)
            SampleAudio.header(22050, 22050, 1).copyInto(tone, 0, 0, 44)
            for (i in 0 until 22050) SampleAudio.put16(
                tone,
                44 + i * 2,
                (12000 * sin(2 * PI * 440 * i / 22050)).toInt(),
            )
            val decoded = SampleAudio.decode(tone, "TEST440")
            val sample = SampleAudio.encode(decoded, 0.0, 0.5, true, 60)
            require(
                sample.size == 81920 &&
                    sample[0] == 'F'.code.toByte() &&
                    sample[1] == 'S'.code.toByte(),
                "invalid FSMP encoding",
            )
            val preview = SampleAudio.decode(SampleAudio.preview(decoded, 0.0, 0.5), "preview")
            require(abs(preview.duration() - 0.5) < 0.001, "sample trim duration")
            val sampled = customized.copy()
            sampled.parts["sample0"] = sample
            candidate = sampled.create(bytes)
            try {
                require(
                    sample.contentEquals(NativeEngine.readPart(candidate, 2, 0)),
                    "native sample write differs",
                )
            } finally {
                NativeEngine.destroy(candidate)
            }
            val song = original.copy()
            song.parts["object2"] = original.parts.getValue("object0").clone()
            song.parts["object3"] = original.parts.getValue("object0").clone()
            song.parts["arrangement"] = byteArrayOf(0, 0, 3, 0, 0, 0, 0, 0, 0, 1, 2, 0, 1, 1, 2)
            if (captureBaseline) {
                song.parts["sample0"] = sample
                val normalizedMachine = song.create(bytes)
                try {
                    val normalized = ProjectSnapshot.capture(normalizedMachine, song.master)
                    baselineFile(
                        "sloop-2.7.1.fm1backup",
                        normalized.envelope("Recorded 2.7.1 migration fixture", true),
                    )
                    baselineFile(
                        "sloop-2.7.1.fm1project",
                        normalized.envelope("Recorded 2.7.1 migration project", false),
                    )
                } finally {
                    NativeEngine.destroy(normalizedMachine)
                }
            }
            val before = fingerprint(song)
            val loopBytes = AudioExport.render(targetContext, song, bytes, false, 1, 0).readBytes()
            require(loopBytes.size == 44 + 44100 * 4, "loop frame count differs")
            require(wavPeak(loopBytes) > 0.001, "silent recorded loop export")
            val songBytes = AudioExport.render(targetContext, song, bytes, true, 1, 0).readBytes()
            val longDuration = (songBytes.size - 44) / 176400.0
            require(
                longDuration > 2 && wavPeak(songBytes) > 0.001,
                "whole song did not export audible arrangement",
            )
            val shortSong = song.copy()
            shortSong.parts.getValue("arrangement")[14] = 1
            val shortBytes =
                AudioExport.render(targetContext, shortSong, bytes, true, 1, 0).readBytes()
            val shortDuration = (shortBytes.size - 44) / 176400.0
            require(
                abs(longDuration / shortDuration - 1.5) < 0.015,
                "song A1+B2 / A1+B1 duration ratio differs",
            )
            require(fingerprint(song) == before, "export modified source snapshot")
            require(
                fingerprint(ProjectSnapshot.capture(m, 716)) == originalHash,
                "candidate/export modified original machine",
            )
            val invalid = song.copy()
            invalid.parts.getValue("arrangement")[2] = 1
            rejected = false
            try {
                AudioExport.render(targetContext, invalid, bytes, true, 1, 0)
            } catch (expected: Exception) {
                rejected = true
            }
            require(rejected, "missing song section accepted")
            return JSONObject()
                .put("backupBytes", backup.size)
                .put("sampleBytes", sample.size)
                .put("loopFrames", 44100)
                .put("songSeconds", longDuration)
                .put("shortSongSeconds", shortDuration)
                .put("sourceUnchanged", true)
                .put("checksumRejected", true)
        } finally {
            NativeEngine.destroy(m)
        }
    }

    private fun wavPeak(b: ByteArray): Double {
        require(
            b.size > 44 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte(),
            "invalid WAV",
        )
        var peak = 0.0
        for (i in 44 until b.size - 1 step 2) peak =
            max(
                peak,
                abs(((b[i].toInt() and 255) or (b[i + 1].toInt() shl 8)).toShort().toInt()) /
                    32768.0,
            )
        return peak
    }

    private fun exchangeAsset(name: String) =
        context.assets.open("exchange/$name").use { it.readBytes() }

    private fun overlayFresh(project: ProjectSnapshot, bytes: ByteArray): ProjectSnapshot {
        val baseline = NativeEngine.create(bytes, 2)
        try {
            advance(baseline, 22050)
            return project.overlay(ProjectSnapshot.capture(baseline, 716))
        } finally {
            NativeEngine.destroy(baseline)
        }
    }

    private fun crossPlatform(): JSONObject {
        val bytes = asset(2)
        val metrics = JSONObject()
        var backup: ProjectSnapshot? = null
        for (extension in arrayOf("fm1project", "fm1backup")) {
            val filename = "swift-generated.$extension"
            val incoming = ProjectSnapshot.fromEnvelope(exchangeAsset(filename), filename)
            val expected = if (extension == "fm1backup") 517 else 716
            require(incoming.master == expected, "Swift envelope master differs for $extension")
            require(incoming.parts.size == 18, "Swift component count differs")
            val restored =
                if (extension == "fm1project") overlayFresh(incoming, bytes) else incoming
            val candidate = restored.create(bytes)
            try {
                advance(candidate, 512)
                require(
                    NativeEngine.kind(candidate) == 2,
                    "Swift imported candidate is not native SLOOP",
                )
                val captured = ProjectSnapshot.capture(candidate, expected)
                require(
                    incoming.parts["object0"].contentEquals(captured.parts["object0"]),
                    "Swift project object0 changed during native import",
                )
                for (i in 0..3) require(
                    incoming.parts["sample$i"].contentEquals(captured.parts["sample$i"]),
                    "Swift sample changed during native import",
                )
                metrics.put(
                    extension,
                    JSONObject()
                        .put("master", incoming.master)
                        .put("components", incoming.parts.size)
                        .put("object0Bytes", incoming.parts.getValue("object0").size)
                        .put("candidateNativeKind", NativeEngine.kind(candidate)),
                )
                if (extension == "fm1backup") backup = captured
            } finally {
                NativeEngine.destroy(candidate)
            }
        }
        val iosExport =
            ProjectSnapshot.fromEnvelope(
                exchangeAsset("ios-simulator.fm1project"),
                "ios-simulator.fm1project",
            )
        require(iosExport.parts.size == 18, "actual iOS export component count differs")
        val iosCandidate = overlayFresh(iosExport, bytes).create(bytes)
        try {
            advance(iosCandidate, 512)
            require(NativeEngine.kind(iosCandidate) == 2, "actual iOS export native kind differs")
            metrics.put(
                "iosSimulatorUIExport",
                JSONObject()
                    .put("master", iosExport.master)
                    .put("components", iosExport.parts.size)
                    .put("object0Bytes", iosExport.parts.getValue("object0").size)
                    .put("candidateNativeKind", NativeEngine.kind(iosCandidate)),
            )
        } finally {
            NativeEngine.destroy(iosCandidate)
        }
        require(backup != null, "Swift backup fixture absent")
        val exported = backup!!.envelope("Android cross-platform workspace", true)
        val directory = targetContext.getExternalFilesDir(null)
        require(directory != null, "external private exchange directory unavailable")
        val output = File(directory, "android-generated.fm1backup")
        FileOutputStream(output).use {
            it.write(exported)
            it.fd.sync()
        }
        metrics
            .put("androidEnvelopeBytes", exported.size)
            .put("androidEnvelopeSHA256", ProjectSnapshot.digest(exported))
            .put("androidEnvelopePath", output.absolutePath)
        return metrics
    }

    private fun field(instance: Any, name: String): Any {
        val f = instance.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f.get(instance)
    }

    private fun barrier(controller: EngineController) {
        val done = CountDownLatch(1)
        (field(controller, "worker") as Handler).post { done.countDown() }
        require(done.await(10, TimeUnit.SECONDS), "engine worker did not settle")
    }

    private fun delay(ms: Long) = SystemClock.sleep(ms)

    private fun held(panel: PanelView, id: Int): Int {
        var n = -1
        runOnMainSync { n = (field(panel, "held") as IntArray)[id] }
        return n
    }

    private fun point(panel: PanelView, id: Int): FloatArray {
        var result: FloatArray? = null
        runOnMainSync {
            for (control in field(panel, "controls") as ArrayList<*>) {
                val a = control!!
                if (field(a, "type") as Int == 0 && field(a, "id") as Int == id) {
                    val r = field(a, "r") as RectF
                    val xy = IntArray(2)
                    panel.getLocationOnScreen(xy)
                    val scale = field(panel, "scale") as Float
                    result =
                        floatArrayOf(
                            xy[0] + r.centerX() * scale,
                            xy[1] + (r.bottom - r.height() * .1f) * scale,
                        )
                    break
                }
            }
        }
        require(result != null, "missing panel key $id")
        return result!!
    }

    private fun pointer(down: Long, action: Int, vararg points: FloatArray) {
        val properties =
            Array(points.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coordinates =
            Array(points.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = points[i][0]
                    y = points[i][1]
                    pressure = 1f
                    size = .05f
                }
            }
        val event =
            MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                action,
                points.size,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        sendPointerSync(event)
        event.recycle()
        waitForIdleSync()
        delay(35)
    }

    private fun activity(): JSONObject {
        val activity =
            startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
                as MainActivity
        try {
            waitForIdleSync()
            delay(900)
            val controller = field(activity, "engine") as EngineController
            val panel = field(activity, "panel") as PanelView
            controller.diagnostics = true
            runOnMainSync {
                controller.selectEngine(2)
                controller.setPaused(false)
                controller.setForeground(true)
            }
            barrier(controller)
            delay(600)
            require(
                controller.selectedKind == 2 && controller.status.isEmpty(),
                "activity native engine not ready: ${controller.status}",
            )
            if (migrationTests) {
                for ((kind, expected) in expectedUpdateBackups) {
                    runOnMainSync { controller.selectEngine(kind) }
                    barrier(controller)
                    require(
                        controller.selectedKind == kind && controller.status.isEmpty(),
                        "updated engine did not load preserved save: $kind ${controller.status}",
                    )
                    val suffix = if (kind == 6) ".preview.flash" else ".flash"
                    val backup =
                        File(targetContext.filesDir, "Instruments/Before-0.3.0/$kind$suffix")
                    require(
                        backup.isFile && expected.contentEquals(backup.readBytes()),
                        "pre-update backup changed for kind $kind",
                    )
                }
                runOnMainSync { controller.selectEngine(2) }
                barrier(controller)
                delay(300)
            }
            val a = point(panel, 21)
            val b = point(panel, 23)
            val c = point(panel, 25)
            var down = SystemClock.uptimeMillis()
            pointer(down, MotionEvent.ACTION_DOWN, a)
            delay(180)
            require(held(panel, 21) == 1, "bottom note down missed")
            pointer(
                down,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                a,
                a,
            )
            require(held(panel, 21) == 2, "same-key touch reference count lost")
            pointer(
                down,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                a,
                a,
            )
            require(held(panel, 21) == 1, "one pointer released another pointer's note")
            pointer(down, MotionEvent.ACTION_UP, a)
            require(held(panel, 21) == 0, "same-key final release stuck")
            down = SystemClock.uptimeMillis()
            pointer(down, MotionEvent.ACTION_DOWN, a)
            pointer(
                down,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                a,
                b,
            )
            pointer(down, MotionEvent.ACTION_MOVE, c, b)
            require(
                held(panel, 21) == 0 && held(panel, 25) == 1 && held(panel, 23) == 1,
                "multitouch glissando routing failed",
            )
            pointer(
                down,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                c,
                b,
            )
            pointer(down, MotionEvent.ACTION_UP, c)
            for (key in intArrayOf(21, 23, 25)) require(
                held(panel, key) == 0,
                "glissando note stuck",
            )
            repeat(8) {
                down = SystemClock.uptimeMillis()
                pointer(down, MotionEvent.ACTION_DOWN, a)
                pointer(down, MotionEvent.ACTION_UP, a)
            }
            barrier(controller)
            delay(300)
            require(controller.peak > 0.00001, "actual activity note generated no PCM")
            require(controller.outputFrames > 0, "AudioTrack received no generated PCM")
            // When explicit old-source fixtures are supplied, stress the actual output worker too.
            if (migrationTests) {
                val done = CountDownLatch(1)
                var failure: Throwable? = null
                (field(controller, "worker") as Handler).post {
                    try {
                        val machine = field(controller, "machine") as Long
                        NativeEngine.release(machine)
                        require(
                            NativeEngine.restore(machine, migrationAsset("dense-2.7.1.flash")),
                            "controller dense restore failed",
                        )
                        require(
                            NativeEngine.transport(machine, true),
                            "controller dense transport failed",
                        )
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        done.countDown()
                    }
                }
                require(done.await(10, TimeUnit.SECONDS), "controller dense setup timed out")
                failure?.let { throw it }
            }
            // Measure warmed continuous playback separately from loading and lifecycle gaps.
            down = SystemClock.uptimeMillis()
            pointer(down, MotionEvent.ACTION_DOWN, a)
            delay(1000)
            val systemTrack = field(controller, "track") as AudioTrack
            val osUnderrunsStart = systemTrack.underrunCount
            val osHeadStart = Integer.toUnsignedLong(systemTrack.playbackHeadPosition)
            controller.checkpointCaptureMaxMicros = 0
            controller.checkpointWriteMaxMicros = 0
            controller.snapshotMaxMicros = 0
            controller.producerMaxGapMicros = 0
            controller.lastUnderflowProducerAgeMicros = 0
            controller.lastUnderflowOutputIntervalMicros = 0
            controller.pcmWaitCount = 0
            controller.pcmWaitMaxMicros = 0
            val checkpointCount = controller.checkpointCount
            val warmStart = SystemClock.elapsedRealtime()
            val warmProduced = controller.producedFrames
            val warmOutput = controller.outputFrames
            val warmUnderflow = controller.underflowFrames
            delay(if (migrationTests) 15000 else 5000)
            val warmMillis = SystemClock.elapsedRealtime() - warmStart
            val warmFrames = controller.producedFrames - warmProduced
            val warmOutputFrames = controller.outputFrames - warmOutput
            val warmUnderflowFrames = controller.underflowFrames - warmUnderflow
            val osUnderruns = systemTrack.underrunCount - osUnderrunsStart
            val osHeadFrames =
                (Integer.toUnsignedLong(systemTrack.playbackHeadPosition) - osHeadStart) and
                    0xffffffffL
            pointer(down, MotionEvent.ACTION_UP, a)
            val warmDiagnostic =
                "warmed audio did not keep generating/outputting PCM: elapsedMs=$warmMillis, produced=$warmFrames, output=$warmOutputFrames, underflow=$warmUnderflowFrames, status=${controller.status}, ready=${field(controller,"ready")}, foreground=${field(controller,"foreground")}, paused=${controller.paused}, audioBufferFrames=${field(controller,"audioBufferFrames")}, osUnderruns=$osUnderruns, osPlaybackHeadFrames=$osHeadFrames"
            require(
                warmUnderflowFrames == 0L,
                "steady playback inserted $warmUnderflowFrames silent frames; $warmDiagnostic",
            )
            require(osUnderruns == 0, "steady AudioTrack OS underruns: $osUnderruns")
            val warmed =
                JSONObject()
                    .put(
                        "workload",
                        if (migrationTests) "dense eight-voice/drum/granular/FX source fixture"
                        else "held note",
                    )
                    .put("checkpointCaptureMaxMicros", controller.checkpointCaptureMaxMicros)
                    .put("checkpointDiskMaxMicros", controller.checkpointWriteMaxMicros)
                    .put("snapshotMaxMicros", controller.snapshotMaxMicros)
                    .put("checkpointCount", controller.checkpointCount - checkpointCount)
                    .put("producerMaxGapMicros", controller.producerMaxGapMicros)
                    .put(
                        "lastUnderflowProducerAgeMicros",
                        controller.lastUnderflowProducerAgeMicros,
                    )
                    .put(
                        "lastUnderflowOutputIntervalMicros",
                        controller.lastUnderflowOutputIntervalMicros,
                    )
                    .put("pcmWaitCount", controller.pcmWaitCount)
                    .put("pcmWaitMaxMicros", controller.pcmWaitMaxMicros)
                    .put("osUnderrunDelta", osUnderruns)
                    .put("osPlaybackHeadFrames", osHeadFrames)
                    .put("osSampleRate", systemTrack.sampleRate)
                    .put("osBufferCapacityFrames", systemTrack.bufferCapacityInFrames)
                    .put("elapsedMs", warmMillis)
                    .put("producedFrames", warmFrames)
                    .put("outputFrames", warmOutputFrames)
                    .put("underflowFrames", warmUnderflowFrames)
                    .put("audioTrackBufferFrames", field(controller, "audioBufferFrames") as Int)
            // A held note is cancelled by the real Activity lifecycle callbacks.
            down = SystemClock.uptimeMillis()
            pointer(down, MotionEvent.ACTION_DOWN, a)
            runOnMainSync { callActivityOnPause(activity) }
            barrier(controller)
            require(held(panel, 21) == 0, "Activity pause retained held key")
            var stopped = controller.producedFrames
            delay(160)
            require(controller.producedFrames == stopped, "background engine kept rendering")
            runOnMainSync { callActivityOnResume(activity) }
            delay(160)
            require(controller.producedFrames > stopped, "foreground did not resume playback")
            pointer(down, MotionEvent.ACTION_CANCEL, a)
            runOnMainSync { controller.setPaused(true) }
            barrier(controller)
            stopped = controller.producedFrames
            runOnMainSync {
                callActivityOnPause(activity)
                callActivityOnResume(activity)
            }
            barrier(controller)
            delay(160)
            require(
                controller.paused && controller.producedFrames == stopped,
                "explicit pause lost across foreground transition",
            )
            runOnMainSync { controller.setPaused(false) }
            delay(160)
            require(controller.producedFrames > stopped, "explicit resume failed")
            val activityPeak = controller.peak
            // Real controller save/open/reload writes a durable candidate before replacement.
            runOnMainSync { controller.setPaused(true) }
            barrier(controller)
            runOnMainSync { controller.setMaster(271) }
            barrier(controller)
            val name = "Instrumentation durable ${System.currentTimeMillis()}"
            runOnMainSync { controller.saveProject(name, true) }
            barrier(controller)
            require(
                controller.status.lowercase(Locale.ROOT).contains("saved"),
                "controller backup failed: ${controller.status}",
            )
            val savedName =
                controller.listProjects().lastOrNull {
                    it.startsWith(EngineController.safeName(name))
                }
            require(savedName != null, "saved controller backup missing")
            runOnMainSync { controller.setMaster(812) }
            barrier(controller)
            // A slow previous autosave must finish before a replacement store is committed.
            val store = File(targetContext.filesDir, "Instruments/2.flash")
            val previousStore = store.readBytes()
            val queue = field(controller, "storage") as ExecutorService
            queue.submit { delay(180) }
            val staged = CountDownLatch(1)
            var stageFailure: Throwable? = null
            (field(controller, "worker") as Handler).post {
                try {
                    val machine = field(controller, "machine") as Long
                    NativeEngine.transport(machine, false)
                    advance(machine, 512)
                    NativeEngine.encoder(machine, 1, 1)
                    advance(machine, 512)
                    require(
                        NativeEngine.commit(machine) == 0,
                        "staged old checkpoint commit failed",
                    )
                    val checkpoint =
                        controller.javaClass.getDeclaredMethod("checkpoint", java.lang.Boolean.TYPE)
                    checkpoint.isAccessible = true
                    checkpoint.invoke(controller, false)
                } catch (error: Throwable) {
                    stageFailure = error
                } finally {
                    staged.countDown()
                }
            }
            require(staged.await(10, TimeUnit.SECONDS), "staged old checkpoint timed out")
            stageFailure?.let { throw it }
            runOnMainSync { controller.openProject(savedName!!) }
            barrier(controller)
            require(
                controller.status.contains("Opened"),
                "controller saved-copy open failed: ${controller.status}",
            )
            require(controller.getMaster() == 271, "backup did not restore edited master")
            val committed = store.readBytes()
            val checked = CountDownLatch(1)
            var committedMatches = false
            (field(controller, "worker") as Handler).post {
                committedMatches =
                    committed.contentEquals(
                        NativeEngine.flash(field(controller, "machine") as Long)
                    )
                checked.countDown()
            }
            require(
                checked.await(10, TimeUnit.SECONDS) && committedMatches,
                "older queued checkpoint overwrote restored candidate",
            )
            require(
                !previousStore.contentEquals(committed),
                "queued-store fixture did not exercise a different committed snapshot",
            )
            // Inject a real filesystem write error: opening a candidate must retain the live
            // handle/store.
            val originalHandle = field(controller, "machine") as Long
            val obstruction = File(store.path + ".tmp")
            require(obstruction.mkdir(), "could not stage atomic-write failure")
            try {
                runOnMainSync { controller.openProject(savedName!!) }
                barrier(controller)
                require(
                    field(controller, "machine") as Long == originalHandle,
                    "failed save replaced the active machine",
                )
                require(
                    committed.contentEquals(store.readBytes()),
                    "failed save changed the durable store",
                )
                require(
                    controller.status.contains("could not be saved"),
                    "filesystem error was not reported",
                )
            } finally {
                require(obstruction.delete(), "could not remove test write obstruction")
            }
            runOnMainSync { controller.openProject(savedName!!) }
            barrier(controller)
            require(
                controller.status.contains("Opened"),
                "restore retry after write failure did not recover",
            )
            runOnMainSync { controller.selectEngine(2) }
            barrier(controller)
            require(
                controller.selectedKind == 2 && controller.status.isEmpty(),
                "controller reload failed: ${controller.status}",
            )
            runOnMainSync { activity.finish() }
            delay(350)
            val reopened =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            try {
                waitForIdleSync()
                delay(400)
                val restored = field(reopened, "engine") as EngineController
                barrier(restored)
                require(
                    restored.getMaster() == 271,
                    "recreated Activity lost restored backup master",
                )
                require(
                    restored.selectedKind == 2 && restored.status.isEmpty(),
                    "recreated Activity did not restore native instrument",
                )
            } finally {
                runOnMainSync { reopened.finish() }
            }
            require(
                warmFrames > 44100 * (if (migrationTests) 12 else 3) &&
                    warmOutputFrames > 44100 * (if (migrationTests) 12 else 3),
                "$warmDiagnostic, lifecycleAndEditedMasterRecreationPassed=true",
            )
            return JSONObject()
                .put("warmedPlayback", warmed)
                .put("preUpdateBackupKinds", JSONArray(expectedUpdateBackups.keys.toList()))
                .put("bottomTaps", 8)
                .put("sameKeyReferenceCount", true)
                .put("multitouchGlissando", true)
                .put("activityPauseReleasedTouches", true)
                .put("explicitPauseRetained", true)
                .put("automaticResume", true)
                .put("savedCopyReload", true)
                .put("orderedCheckpointBeforeRestore", true)
                .put("writeFailureKeptActiveAndDurableStore", true)
                .put("writeFailureRetryRecovered", true)
                .put("restoredMasterAfterActivityRecreation", 271)
                .put("producedFrames", controller.producedFrames)
                .put("outputFrames", controller.outputFrames)
                .put("underflowFrames", controller.underflowFrames)
                .put("peak", activityPeak)
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun migrationAsset(name: String) =
        context.assets.open("migration/$name").use { it.readBytes() }

    /** Actual 0.2.0 engine output, supplied explicitly; never generated by the new engine. */
    private fun migration(): JSONObject {
        val metrics = JSONObject()
        for (kind in 2..9) {
            val oldFlash = migrationAsset("kind-$kind.flash")
            val machine = NativeEngine.create(asset(kind), kind)
            try {
                require(
                    NativeEngine.restore(machine, oldFlash),
                    "old store rejected for kind $kind",
                )
                require(
                    oldFlash.contentEquals(NativeEngine.flash(machine)),
                    "old store changed on first restore for kind $kind",
                )
                // Skiff deliberately suppresses keys during its 430 ms firmware boot splash.
                // Match the same boot advance used by the fresh-engine note check.
                advance(machine, 23040)
                NativeEngine.volume(machine, 716)
                NativeEngine.key(machine, if (kind == 4) 15 else 21, true)
                val peak = advance(machine, 44100)
                require(peak > 0.00001, "old store produced silent note for kind $kind")
                NativeEngine.release(machine)
                metrics.put(
                    "kind-$kind",
                    JSONObject().put("rawStoreIdentical", true).put("peak", peak),
                )
            } finally {
                NativeEngine.destroy(machine)
            }
        }
        for (extension in arrayOf("fm1backup", "fm1project")) {
            val name = "sloop-2.7.1.$extension"
            val encoded = migrationAsset(name)
            val envelope = JSONObject(String(encoded, Charsets.UTF_8))
            require(
                envelope.getJSONObject("snapshot").getString("packageSHA256") ==
                    ProjectSnapshot.LEGACY_PACKAGE,
                "fixture is not an old 2.7.1 export",
            )
            val incoming = ProjectSnapshot.fromEnvelope(encoded, name)
            val original = fingerprint(incoming)
            val restored =
                if (extension == "fm1project") overlayFresh(incoming, asset(2)) else incoming
            var machine = restored.create(asset(2))
            try {
                val captured = ProjectSnapshot.capture(machine, restored.master)
                // A project overlays the current settings, then its separate song order replaces
                // the duplicated arrangement field in persist_t. The old native engine does this
                // too.
                val expected = restored.copy()
                if (extension == "fm1project") {
                    val legacySettings =
                        ProjectSnapshot.fromEnvelope(
                                migrationAsset("sloop-2.7.1.fm1backup"),
                                "old.fm1backup",
                            )
                            .parts
                            .getValue("object1")
                    val settings = expected.parts.getValue("object1")
                    require(
                        settings.size == 88 && legacySettings.size == 88,
                        "unexpected legacy settings layout",
                    )
                    legacySettings.copyInto(settings, 48, 48, 84)
                }
                for ((part, data) in expected.parts) if (part != "flash")
                    require(
                        data.contentEquals(captured.parts[part]),
                        "old typed $part changed in $extension",
                    )
                val currentEnvelope = captured.envelope("2.8 migrated roundtrip", true)
                require(
                    JSONObject(String(currentEnvelope, Charsets.UTF_8))
                        .getJSONObject("snapshot")
                        .getString("packageSHA256") == ProjectSnapshot.PACKAGE,
                    "new export retained the old package identity",
                )
                val current = ProjectSnapshot.fromEnvelope(currentEnvelope, "current.fm1backup")
                NativeEngine.destroy(machine)
                machine = current.create(asset(2))
                require(NativeEngine.transport(machine, true), "migrated loop did not start")
                val peak = advance(machine, 88200)
                require(peak > 0.001, "migrated recorded notes are silent")
                NativeEngine.transport(machine, false)
                val loop =
                    AudioExport.render(targetContext, current, asset(2), false, 1, 0).readBytes()
                val song =
                    AudioExport.render(targetContext, current, asset(2), true, 1, 0).readBytes()
                require(
                    wavPeak(loop) > 0.001 && wavPeak(song) > 0.001,
                    "migrated loop/song export is silent",
                )
                require(fingerprint(incoming) == original, "migration modified its source envelope")
                val output =
                    File(
                        targetContext.getExternalFilesDir(null),
                        "migrated-2.8-$extension.fm1backup",
                    )
                output.writeBytes(currentEnvelope)
                metrics.put(
                    extension,
                    JSONObject()
                        .put("musicalPartsAndGlobalSettingsRetained", true)
                        .put("loopPeak", wavPeak(loop))
                        .put("songPeak", wavPeak(song))
                        .put("newEnvelope", output.absolutePath),
                )
            } finally {
                NativeEngine.destroy(machine)
            }
        }
        val rejected = JSONObject(String(migrationAsset("sloop-2.7.1.fm1backup"), Charsets.UTF_8))
        rejected.getJSONObject("snapshot").put("packageSHA256", "0".repeat(64))
        var invalidRejected = false
        try {
            ProjectSnapshot.fromEnvelope(rejected.toString().toByteArray(), "unknown.fm1backup")
        } catch (expected: Exception) {
            invalidRejected = true
        }
        require(invalidRejected, "unaudited workspace package identity accepted")
        return metrics.put("unknownPackageRejected", true)
    }

    private fun densePlayback(): JSONObject {
        val machine = NativeEngine.create(asset(2), 2)
        try {
            require(
                NativeEngine.restore(machine, migrationAsset("dense-2.7.1.flash")),
                "dense fixture rejected",
            )
            NativeEngine.volume(machine, 716)
            require(NativeEngine.transport(machine, true), "dense fixture did not start")
            val start = SystemClock.elapsedRealtime()
            val peak = advance(machine, 60 * 44100)
            require(peak > 0.001, "dense playback is silent")
            return JSONObject()
                .put("frames", 60 * 44100)
                .put("secondsRendered", 60)
                .put("peak", peak)
                .put("offlineRenderWallMillis", SystemClock.elapsedRealtime() - start)
                .put("physicalLatencyMeasured", false)
        } finally {
            NativeEngine.destroy(machine)
        }
    }

    private fun baselineFile(name: String, bytes: ByteArray) {
        val directory = File(targetContext.getExternalFilesDir(null), "firmware-baseline")
        require(directory.isDirectory || directory.mkdirs(), "baseline directory unavailable")
        File(directory, name).writeBytes(bytes)
    }

    private fun runChecks() {
        if (captureBaseline && ProjectSnapshot.digest(asset(2)) != ProjectSnapshot.LEGACY_PACKAGE) {
            val result = Bundle()
            result.putString(
                "stream",
                "Baseline capture requires the installed 0.2.0 APK; current firmware is not a legacy fixture.",
            )
            finish(0, result)
            return
        }
        if (migrationTests) {
            // Dedicated emulator only: fill missing test stores, retain any existing synthetic
            // stores.
            val prefs = targetContext.getSharedPreferences("fm1", 0)
            val directory = File(targetContext.filesDir, "Instruments").apply { mkdirs() }
            for (kind in intArrayOf(2, 5, 6, 7)) {
                val suffix = if (kind == 6) ".preview.flash" else ".flash"
                val save = File(directory, "$kind$suffix")
                if (!save.exists()) {
                    save.writeBytes(migrationAsset("kind-$kind.flash"))
                    prefs.edit().remove("storageRevision-$kind").commit()
                }
                if (prefs.getString("storageRevision-$kind", null) != "0.3.0")
                    expectedUpdateBackups[kind] = save.readBytes()
            }
        }
        if (!uiOnly) {
            for (kind in 2..9) check("engine-$kind") { engine(kind) }
            check("sloop-workspace-sample-loop-song", ::workspace)
            if (exchangeTests) check("cross-platform-workspace", ::crossPlatform)
            if (migrationTests) {
                check("old-firmware-store-and-typed-workspace-migration", ::migration)
                check("sloop-dense-60-seconds", ::densePlayback)
            }
        }
        if (!captureBaseline) check("activity-multitouch-audio-lifecycle-persistence", ::activity)
        val result = Bundle()
        try {
            val receipt =
                JSONObject()
                    .put("platform", "Android emulator")
                    .put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("abi", android.os.Build.SUPPORTED_ABIS[0])
                    .put("failures", failures)
                    .put("exchangeTestsRequested", exchangeTests)
                    .put("migrationTestsRequested", migrationTests)
                    .put(
                        "exchangeTestStatus",
                        if (exchangeTests) "included"
                        else "not requested; external fixtures not supplied",
                    )
                    .put("results", results)
            File(targetContext.filesDir, "instrumentation.json").outputStream().use {
                it.write(receipt.toString(2).toByteArray(Charsets.UTF_8))
            }
            result.putString("stream", receipt.toString(2) + "\n")
        } catch (error: Exception) {
            failures++
            result.putString("stream", error.toString())
        }
        finish(if (failures == 0) -1 else 0, result)
    }
}
