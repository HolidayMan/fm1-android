package org.fm1.android

import android.app.*
import android.content.*
import android.content.res.ColorStateList
import android.graphics.*
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import java.io.*
import kotlin.math.*

/** Landscape native shell; storage access is explicitly initiated through Android's picker. */
class MainActivity : Activity(), EngineController.Listener {
    private lateinit var engine: EngineController
    private lateinit var panel: PanelView
    private lateinit var column: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var firmware: TextView
    private lateinit var transport: TextView
    private lateinit var mute: TextView
    private lateinit var folder: Button
    private lateinit var collapse: Button
    private lateinit var preferences: SharedPreferences
    private var finish = 0
    private var collapsed = false
    private var foreground = false
    private var exportBytes: ByteArray? = null
    private var exportFile: File? = null
    private var audition: MediaPlayer? = null
    private var finishDialog: AlertDialog? = null
    private var zoom: View? = null

    companion object {
        private const val SAMPLE = 103
        private const val IMPORT = 101
        private const val EXPORT = 102
    }

    private fun accent() = 0xff000000.toInt() or PanelView.PALETTES[finish][7]

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        preferences = getSharedPreferences("panel", MODE_PRIVATE)
        finish = preferences.getInt("finish", 0).coerceIn(0, 5)
        collapsed = preferences.getBoolean("collapsed", false)
        engine = EngineController(this)
        engine.setListener(this)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.setPadding(dp(8f), dp(4f), dp(6f), dp(2f))
        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val cp = FrameLayout.LayoutParams(-1, -1)
        cp.rightMargin = dp(44f)
        root.addView(column, cp)
        toolbar = LinearLayout(this)
        toolbar.gravity = Gravity.CENTER_VERTICAL
        toolbar.setPadding(dp(8f), 0, dp(8f), 0)
        column.addView(toolbar, LinearLayout.LayoutParams(-1, dp(32f)))
        firmware = chrome("SLOOP 2.8 ⌄") { library() }
        firmware.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        toolbar.addView(firmware, LinearLayout.LayoutParams(0, -1, 1f))
        transport =
            chrome("Ⅱ") {
                engine.setPaused(!engine.paused)
                refreshChrome()
            }
        toolbar.addView(transport, LinearLayout.LayoutParams(dp(48f), -1))
        mute =
            chrome("♪") {
                engine.setMuted(!engine.muted)
                refreshChrome()
            }
        toolbar.addView(mute, LinearLayout.LayoutParams(dp(48f), -1))
        mute.contentDescription = "Mute or unmute output"
        val colors = chrome("●") { chooseFinish() }
        colors.contentDescription = "Panel color"
        toolbar.addView(colors, LinearLayout.LayoutParams(dp(48f), -1))
        val info = chrome("ⓘ") { about() }
        info.contentDescription = "About FM–1"
        toolbar.addView(info, LinearLayout.LayoutParams(dp(44f), -1))
        panel = PanelView(this)
        panel.setMasterIndicator(engine.getMaster())
        column.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.setActions(
            object : PanelView.Actions {
                override fun key(id: Int, down: Boolean) = engine.key(id, down)

                override fun encoder(id: Int, steps: Int) = engine.encoder(id, steps)

                override fun master(value: Int) = engine.setMaster(value)

                override fun enlarge() = enlargeDisplay()
            }
        )
        val side = LinearLayout(this)
        side.orientation = LinearLayout.VERTICAL
        val sp = FrameLayout.LayoutParams(dp(44f), -2, Gravity.RIGHT or Gravity.TOP)
        sp.topMargin = dp(12f)
        root.addView(side, sp)
        collapse =
            sideButton("⌃", "Hide top controls") {
                collapsed = !collapsed
                preferences.edit().putBoolean("collapsed", collapsed).apply()
                applyFinish()
            }
        side.addView(collapse, LinearLayout.LayoutParams(-1, dp(44f)))
        folder = sideButton("▱", "Projects and files") { files() }
        side.addView(folder, LinearLayout.LayoutParams(-1, dp(44f)))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            root.setPadding(
                max(dp(8f), insets.systemWindowInsetLeft),
                dp(4f),
                max(dp(6f), insets.systemWindowInsetRight),
                dp(2f),
            )
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            panel.setReferenceHeight(
                (root.height - root.paddingTop - root.paddingBottom - dp(32f)).toFloat()
            )
        }
        applyFinish()
    }

    private fun dp(n: Float) = (n * resources.displayMetrics.density).roundToInt()

    private fun chrome(value: String, click: View.OnClickListener): TextView {
        val b =
            object : TextView(this) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)

                override fun onDraw(c: Canvas) {
                    val symbol = text.toString()
                    if (symbol !in arrayOf("Ⅱ", "▶", "♪", "♩ ×", "●", "ⓘ")) {
                        super.onDraw(c)
                        return
                    }
                    val save = c.save()
                    c.translate(width / 2f, height / 2f)
                    c.scale(resources.displayMetrics.density, resources.displayMetrics.density)
                    p.color = accent()
                    p.strokeWidth = 1.6f
                    p.style = Paint.Style.STROKE
                    p.strokeJoin = Paint.Join.ROUND
                    p.strokeCap = Paint.Cap.ROUND
                    when (symbol) {
                        "Ⅱ" -> {
                            c.drawLine(-3f, -6f, -3f, 6f, p)
                            c.drawLine(3f, -6f, 3f, 6f, p)
                        }
                        "▶" -> {
                            p.style = Paint.Style.FILL
                            val path = Path()
                            path.moveTo(-5f, -7f)
                            path.lineTo(7f, 0f)
                            path.lineTo(-5f, 7f)
                            path.close()
                            c.drawPath(path, p)
                        }
                        "♪",
                        "♩ ×" -> {
                            val path = Path()
                            path.moveTo(-9f, -3f)
                            path.lineTo(-5f, -3f)
                            path.lineTo(0f, -8f)
                            path.lineTo(0f, 8f)
                            path.lineTo(-5f, 3f)
                            path.lineTo(-9f, 3f)
                            path.close()
                            c.drawPath(path, p)
                            if (symbol == "♪") {
                                c.drawArc(RectF(-3f, -6f, 7f, 6f), -60f, 120f, false, p)
                                c.drawArc(RectF(-6f, -10f, 11f, 10f), -60f, 120f, false, p)
                            } else {
                                c.drawLine(4f, -3f, 9f, 3f, p)
                                c.drawLine(4f, 3f, 9f, -3f, p)
                            }
                        }
                        "●" -> {
                            p.style = Paint.Style.FILL
                            p.color = 0xff000000.toInt() or PanelView.PALETTES[finish][0]
                            c.drawCircle(-3f, 0f, 7f, p)
                            p.color = 0xff000000.toInt() or PanelView.PALETTES[finish][3]
                            c.drawCircle(4f, 0f, 7f, p)
                        }
                        else -> {
                            c.drawCircle(0f, 0f, 8f, p)
                            c.drawLine(0f, -1f, 0f, 5f, p)
                            c.drawLine(-2f, 5f, 2f, 5f, p)
                            p.style = Paint.Style.FILL
                            c.drawCircle(0f, -4f, 1f, p)
                        }
                    }
                    c.restoreToCount(save)
                }
            }
        b.text = value
        b.textSize = 19f
        b.gravity = Gravity.CENTER
        b.setTextColor(accent())
        if (value.startsWith("SLOOP")) b.setSingleLine(true)
        b.setOnClickListener(click)
        return b
    }

    private fun sideButton(text: String, label: String, click: View.OnClickListener): Button {
        val b =
            object : Button(this) {
                val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

                override fun onDraw(c: Canvas) {
                    iconPaint.color = accent()
                    iconPaint.style = Paint.Style.STROKE
                    iconPaint.strokeWidth = dp(1.6f).toFloat()
                    iconPaint.strokeJoin = Paint.Join.ROUND
                    iconPaint.strokeCap = Paint.Cap.ROUND
                    val cx = width / 2f
                    val cy = height / 2f
                    if (contentDescription == "Projects and files") {
                        val path = Path()
                        path.moveTo(cx - dp(9f), cy + dp(7f))
                        path.lineTo(cx - dp(9f), cy - dp(7f))
                        path.lineTo(cx - dp(3f), cy - dp(7f))
                        path.lineTo(cx, cy - dp(4f))
                        path.lineTo(cx + dp(9f), cy - dp(4f))
                        path.lineTo(cx + dp(9f), cy + dp(7f))
                        path.close()
                        c.drawPath(path, iconPaint)
                        c.drawLine(cx - dp(8f), cy - dp(2f), cx + dp(8f), cy - dp(2f), iconPaint)
                    } else {
                        val direction = if (collapsed) 1 else -1
                        c.drawLine(
                            cx - dp(5f),
                            cy - direction * dp(2.5f),
                            cx,
                            cy + direction * dp(2.5f),
                            iconPaint,
                        )
                        c.drawLine(
                            cx,
                            cy + direction * dp(2.5f),
                            cx + dp(5f),
                            cy - direction * dp(2.5f),
                            iconPaint,
                        )
                    }
                }
            }
        b.text = text
        b.textSize = 24f
        b.contentDescription = label
        b.setBackgroundColor(Color.TRANSPARENT)
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener(click)
        return b
    }

    private fun applyFinish() {
        panel.setFinish(finish)
        toolbar.visibility = if (collapsed) View.GONE else View.VISIBLE
        collapse.text = if (collapsed) "⌄" else "⌃"
        collapse.contentDescription = if (collapsed) "Show top controls" else "Hide top controls"
        collapse.setTextColor(accent())
        folder.setTextColor(accent())
        for (i in 0 until toolbar.childCount) (toolbar.getChildAt(i) as TextView).setTextColor(
            accent()
        )
        refreshChrome()
    }

    private fun refreshChrome() {
        if (!::firmware.isInitialized) return
        var index = 0
        for (i in EngineController.ENGINE_KINDS.indices) if (
            EngineController.ENGINE_KINDS[i] == engine.selectedKind
        )
            index = i
        firmware.text = EngineController.ENGINE_NAMES[index] + " ⌄"
        transport.text = if (engine.paused) "▶" else "Ⅱ"
        transport.contentDescription =
            if (engine.paused) "Resume instrument" else "Pause instrument"
        mute.text = if (engine.muted) "♩ ×" else "♪"
        folder.visibility = if (engine.selectedKind == 2) View.VISIBLE else View.GONE
    }

    override fun onFrame() {
        if (::panel.isInitialized) panel.updateFrame(engine.pixels, engine.leds)
        zoom?.invalidate()
    }

    override fun onStatus() {
        if (::panel.isInitialized) panel.setMasterIndicator(engine.getMaster())
        refreshChrome()
        if (engine.status.isNotEmpty())
            Toast.makeText(this, engine.status, Toast.LENGTH_LONG).show()
    }

    private fun library() {
        AlertDialog.Builder(this)
            .setTitle("Instruments")
            .setItems(EngineController.ENGINE_NAMES) { _, i ->
                panel.releaseTouches()
                engine.selectEngine(EngineController.ENGINE_KINDS[i])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun chooseFinish() {
        val grid = LinearLayout(this)
        grid.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        grid.orientation = LinearLayout.VERTICAL
        for (row in 0..2) {
            val line = LinearLayout(this)
            grid.addView(line)
            for (col in 0..1) {
                val index = row * 2 + col
                val button = Button(this)
                button.text = (if (finish == index) "✓  " else "") + PanelView.FINISH_NAMES[index]
                button.setTextColor(0xff000000.toInt() or PanelView.PALETTES[index][5])
                button.backgroundTintList =
                    ColorStateList.valueOf(0xff000000.toInt() or PanelView.PALETTES[index][0])
                line.addView(button, LinearLayout.LayoutParams(0, dp(58f), 1f))
                button.setOnClickListener {
                    finish = index
                    preferences.edit().putInt("finish", finish).apply()
                    applyFinish()
                    finishDialog?.dismiss()
                }
            }
        }
        finishDialog =
            AlertDialog.Builder(this)
                .setTitle("Panel color")
                .setView(grid)
                .setNegativeButton("Close", null)
                .create()
        finishDialog!!.show()
    }

    private fun enlargeDisplay() {
        zoom =
            object : View(this) {
                val p = Paint()
                val bmp = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)

                override fun onDraw(c: Canvas) {
                    c.drawColor(Color.BLACK)
                    val pixels = engine.pixels
                    if (pixels.size == 57600) {
                        bmp.setPixels(pixels, 0, 240, 0, 0, 240, 240)
                        val s = min(width, height).toFloat()
                        c.drawBitmap(
                            bmp,
                            null,
                            RectF(
                                (width - s) / 2,
                                (height - s) / 2,
                                (width + s) / 2,
                                (height + s) / 2,
                            ),
                            p,
                        )
                    }
                }
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Instrument display")
                .setView(zoom)
                .setPositiveButton("Done", null)
                .create()
        dialog.setOnDismissListener { zoom = null }
        dialog.show()
        dialog.window!!.setLayout(
            resources.displayMetrics.widthPixels * 4 / 5,
            resources.displayMetrics.heightPixels * 9 / 10,
        )
    }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("FM–1")
            .setMessage(
                "Unofficial native FM–1 instrument. Eight source-compiled engines.\n\nPlay the 27 keys; drag knobs vertically. Hold function buttons for firmware combinations. Tap the display to enlarge it. MASTER controls the instrument; the speaker control mutes the output.\n\nProjects and samples stay on this device unless you explicitly export them. No account or telemetry. Backgrounding pauses audio.\n\nGPL-3.0-only. Includes fm1-emu (MIT) and source-native engines by their respective authors. Not affiliated with M-VAVE. Portable FM1 Open preview may differ from hardware and does not include the original factory sound bank."
            )
            .setNeutralButton("Credits and licenses") { _, _ -> licenses() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun licenses() {
        try {
            val names =
                assets.list("notices") ?: throw IOException("License notices are unavailable.")
            names.sort()
            AlertDialog.Builder(this)
                .setTitle("Credits and licenses")
                .setItems(names) { _, i ->
                    try {
                        val notice =
                            assets.open("notices/" + names[i]).use {
                                String(
                                    EngineController.readBounded(it, 1024 * 1024),
                                    Charsets.UTF_8,
                                )
                            }
                        val text = label(notice)
                        text.setTextIsSelectable(true)
                        text.setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
                        val scroll = ScrollView(this)
                        scroll.addView(text)
                        AlertDialog.Builder(this)
                            .setTitle(names[i])
                            .setView(scroll)
                            .setPositiveButton("Done", null)
                            .show()
                    } catch (e: Exception) {
                        error(e)
                    }
                }
                .setNegativeButton("Done", null)
                .show()
        } catch (e: Exception) {
            error(e)
        }
    }

    private fun files() {
        val options =
            arrayOf(
                "Save project copy…",
                "Back up workspace…",
                "Saved projects and backups…",
                "Import project or backup…",
                "Samples · import WAV…",
                "Audio · export WAV…",
            )
        AlertDialog.Builder(this)
            .setTitle("Projects and files · SLOOP")
            .setItems(options) { _, i ->
                when (i) {
                    0,
                    1 -> nameCopy(i == 1)
                    2 -> saved()
                    4 ->
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .setType("audio/*")
                                .addCategory(Intent.CATEGORY_OPENABLE),
                            SAMPLE,
                        )
                    5 -> audioExport()
                    else ->
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .setType("*/*")
                                .addCategory(Intent.CATEGORY_OPENABLE),
                            IMPORT,
                        )
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun nameCopy(backup: Boolean) {
        val name = EditText(this)
        name.setSingleLine()
        name.hint = if (backup) "Workspace backup" else "Project name"
        AlertDialog.Builder(this)
            .setTitle(if (backup) "Back up workspace" else "Save project copy")
            .setView(name)
            .setMessage(
                if (backup) "Saves sounds, user samples and instrument settings."
                else
                    "Saves the loop, sections, song and shared sounds. Stop the song and finish SONG REC first."
            )
            .setPositiveButton("Save") { _, _ ->
                val value = name.text.toString().trim()
                if (value.isNotEmpty()) engine.saveProject(value, backup)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saved() {
        val names = engine.listProjects()
        if (names.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Saved work")
                .setMessage("No saved copies yet. Use Save project copy or Back up workspace.")
                .setPositiveButton("Done", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Saved work")
            .setItems(names) { _, i ->
                AlertDialog.Builder(this)
                    .setTitle(names[i])
                    .setItems(arrayOf("Open…", "Export…")) { _, choice ->
                        if (choice == 0)
                            AlertDialog.Builder(this)
                                .setTitle("Open this saved copy?")
                                .setMessage(
                                    "Replaces current work. A recovery backup is saved first."
                                )
                                .setPositiveButton("Back up and open") { _, _ ->
                                    engine.openProject(names[i])
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        else
                            try {
                                exportBytes = engine.exportProject(names[i])
                                startActivityForResult(
                                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                                        .setType("application/octet-stream")
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .putExtra(Intent.EXTRA_TITLE, names[i]),
                                    EXPORT,
                                )
                            } catch (e: Exception) {
                                error(e)
                            }
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        val uri = data?.data
        if (result != RESULT_OK || uri == null) {
            exportBytes = null
            exportFile?.delete()
            exportFile = null
            return
        }
        try {
            when (request) {
                EXPORT -> {
                    contentResolver.openOutputStream(uri).use { out ->
                        if (out == null) throw IOException("Cannot open the selected destination.")
                        val file = exportFile
                        if (file != null) file.inputStream().use { it.copyTo(out, 16384) }
                        else out.write(exportBytes!!)
                    }
                    exportBytes = null
                    exportFile?.delete()
                    exportFile = null
                    Toast.makeText(this, "Export saved", Toast.LENGTH_SHORT).show()
                }
                SAMPLE -> loadSample(uri)
                IMPORT -> {
                    val bytes =
                        contentResolver.openInputStream(uri).use { input ->
                            if (input == null) throw IOException("Cannot open the selected file.")
                            ByteArrayOutputStream().use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (output.size() + count > 4 * 1024 * 1024) {
                                        throw IOException("Project file exceeds 4 MiB.")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                    val filename = displayName(uri)
                    ProjectSnapshot.fromEnvelope(bytes, filename)
                    AlertDialog.Builder(this)
                        .setTitle("Import saved work?")
                        .setMessage(
                            "The file is checked before it is saved to your library. Your current instrument keeps playing."
                        )
                        .setPositiveButton("Import") { _, _ ->
                            engine.importProject(bytes, filename)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        } catch (e: Exception) {
            exportBytes = null
            error(e)
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use {
            cursor ->
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "import"
    }

    private fun loadSample(uri: Uri) {
        val loading = ProgressDialog.show(this, "Sample", "Reading WAV…", true, false)
        Thread(
                {
                    try {
                        val name = displayName(uri)
                        val bytes =
                            contentResolver.openInputStream(uri).use { input ->
                                if (input == null)
                                    throw IOException("Cannot open the selected audio.")
                                EngineController.readBounded(input, 32 * 1024 * 1024)
                            }
                        val source = SampleAudio.decode(bytes, name)
                        runOnUiThread {
                            loading.dismiss()
                            sampleEditor(source, name)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            loading.dismiss()
                            error(e)
                        }
                    }
                },
                "FM1 sample reader",
            )
            .start()
    }

    private fun stopAudition() {
        audition?.release()
        audition = null
    }

    private fun label(value: String) =
        TextView(this).apply {
            text = value
            textSize = 15f
            setPadding(dp(4f), dp(5f), dp(4f), dp(3f))
        }

    private fun sampleEditor(source: SampleAudio.Source, name: String) {
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(16f), dp(4f), dp(16f), dp(8f))
        val scroll = ScrollView(this)
        scroll.addView(content)
        val duration = source.duration()
        val trim = doubleArrayOf(0.0, min(duration, 7.38))
        val position = label("")
        content.addView(position)
        val waveform =
            object : View(this) {
                val p = Paint()

                override fun onDraw(c: Canvas) {
                    c.drawColor(0xff151b1d.toInt())
                    val length = source.samples.size
                    p.color = accent()
                    p.strokeWidth = 1f
                    for (x in 0 until width) {
                        val begin = (x.toLong() * length / width).toInt()
                        val end = max(begin + 1, ((x + 1).toLong() * length / width).toInt())
                        var peak = 0f
                        for (j in begin until min(length, end)) peak =
                            max(peak, abs(source.samples[j]))
                        val height = peak * height * .45f
                        c.drawLine(
                            x.toFloat(),
                            this.height / 2f - height,
                            x.toFloat(),
                            this.height / 2f + height,
                            p,
                        )
                    }
                    p.color = 0x553bcdbe
                    c.drawRect(
                        (trim[0] / duration * width).toFloat(),
                        0f,
                        (trim[1] / duration * width).toFloat(),
                        height.toFloat(),
                        p,
                    )
                }
            }
        content.addView(waveform, LinearLayout.LayoutParams(-1, dp(64f)))
        content.addView(label("Trim start"))
        val start = SeekBar(this)
        start.max = 10000
        content.addView(start)
        content.addView(label("Trim end · maximum 7.38 seconds"))
        val end = SeekBar(this)
        end.max = 10000
        end.progress = (trim[1] / duration * 10000).toInt()
        content.addView(end)
        val update = {
            position.text =
                String.format(
                    java.util.Locale.US,
                    "%s · %.2f s source · %.3f–%.3f s selected",
                    name,
                    duration,
                    trim[0],
                    trim[1],
                )
            waveform.invalidate()
        }
        start.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) {}

                override fun onStopTrackingTouch(b: SeekBar) {}

                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) {
                    if (!user) return
                    trim[0] = min(duration - .001, value / 10000.0 * duration)
                    trim[1] = max(trim[0] + .001, min(trim[1], trim[0] + 7.38))
                    end.progress = (trim[1] / duration * 10000).toInt()
                    update()
                }
            }
        )
        end.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(b: SeekBar) {}

                override fun onStopTrackingTouch(b: SeekBar) {}

                override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) {
                    if (!user) return
                    trim[1] =
                        max(
                            trim[0] + .001,
                            min(value / 10000.0 * duration, min(duration, trim[0] + 7.38)),
                        )
                    b.progress = (trim[1] / duration * 10000).toInt()
                    update()
                }
            }
        )
        val selectors = LinearLayout(this)
        content.addView(selectors)
        val slot = Spinner(this)
        slot.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("USR1", "USR2", "USR3", "USR4"),
            )
        selectors.addView(slot, LinearLayout.LayoutParams(0, dp(48f), 1f))
        val root = EditText(this)
        root.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        root.setText("60")
        root.hint = "Root MIDI 0–127"
        selectors.addView(root, LinearLayout.LayoutParams(0, dp(48f), 1f))
        content.addView(label("Root note · MIDI 60 = C4"))
        val normalize = CheckBox(this)
        normalize.text = "Normalize sample"
        content.addView(normalize)
        val preview = Button(this)
        preview.text = "Audition selection"
        content.addView(preview)
        preview.setOnClickListener {
            try {
                stopAudition()
                val wav = SampleAudio.preview(source, trim[0], trim[1])
                val temporary = File(cacheDir, "sample-audition.wav")
                temporary.outputStream().use { it.write(wav) }
                audition =
                    MediaPlayer().apply {
                        setDataSource(temporary.path)
                        prepare()
                        setOnCompletionListener { stopAudition() }
                        start()
                    }
            } catch (e: Exception) {
                error(e)
            }
        }
        update()
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Import WAV sample")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Review import", null)
                .create()
        dialog.setOnDismissListener { stopAudition() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val note = root.text.toString().toInt()
                    if (note !in 0..127) throw IOException("Choose a root note from 0 to 127.")
                    AlertDialog.Builder(this)
                        .setTitle("Replace USR${slot.selectedItemPosition+1}?")
                        .setMessage(
                            "A recovery backup is saved first. The selected trim is converted to mono 22.05 kHz for SLOOP."
                        )
                        .setPositiveButton("Back up and import") { _, _ ->
                            stopAudition()
                            engine.importSample(
                                source,
                                trim[0],
                                trim[1],
                                normalize.isChecked,
                                note,
                                slot.selectedItemPosition,
                            )
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } catch (e: Exception) {
                    error(e)
                }
            }
        }
        dialog.show()
        dialog.window!!.setLayout(
            resources.displayMetrics.widthPixels * 4 / 5,
            resources.displayMetrics.heightPixels * 9 / 10,
        )
    }

    private fun audioExport() {
        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(16f), dp(4f), dp(16f), dp(8f))
        val mode = Spinner(this)
        mode.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Loop", "Whole song"),
            )
        form.addView(mode)
        form.addView(label("Loop music · 1–120 seconds"))
        val seconds = EditText(this)
        seconds.setText("15")
        seconds.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        form.addView(seconds)
        form.addView(label("Effect tail · 0–10 seconds"))
        val tail = EditText(this)
        tail.setText("2")
        tail.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        form.addView(tail)
        form.addView(
            label(
                "Stereo WAV · 44.1 kHz · 16-bit PCM. Renders a separate copy; live work keeps playing. Whole song uses saved sections A–D, with a 30-minute limit."
            )
        )
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Export audio")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Prepare WAV", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val duration = seconds.text.toString().toInt()
                    val effect = tail.text.toString().toInt()
                    if (duration !in 1..120 || effect !in 0..10)
                        throw IOException("Choose 1–120 music seconds and 0–10 tail seconds.")
                    val song = mode.selectedItemPosition == 1
                    dialog.dismiss()
                    val busy = ProgressDialog(this)
                    busy.setTitle("Export audio")
                    busy.setMessage("Rendering a separate copy…")
                    busy.isIndeterminate = true
                    busy.setCancelable(true)
                    busy.setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel") { _, _ ->
                        engine.cancelExport()
                    }
                    busy.setOnCancelListener { engine.cancelExport() }
                    busy.show()
                    engine.exportAudio(song, duration, effect) callback@{ file, failure ->
                        busy.dismiss()
                        if (isFinishing || isDestroyed) {
                            file?.delete()
                            return@callback
                        }
                        if (failure != null) {
                            if (failure !is java.util.concurrent.CancellationException)
                                error(failure)
                            return@callback
                        }
                        exportFile = file
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .setType("audio/wav")
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .putExtra(
                                    Intent.EXTRA_TITLE,
                                    if (song) "SLOOP-song.wav" else "SLOOP-loop.wav",
                                ),
                            EXPORT,
                        )
                    }
                } catch (e: Exception) {
                    error(e)
                }
            }
        }
        dialog.show()
    }

    private fun error(e: Exception) {
        AlertDialog.Builder(this)
            .setTitle("File action")
            .setMessage(e.message ?: "The file action could not be completed.")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::engine.isInitialized) engine.setForeground(true)
    }

    override fun onPause() {
        foreground = false
        stopAudition()
        if (::panel.isInitialized) panel.releaseTouches()
        if (::engine.isInitialized) engine.setForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.close()
        super.onDestroy()
    }
}
