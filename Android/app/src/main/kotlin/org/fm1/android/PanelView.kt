package org.fm1.android

import android.content.Context
import android.graphics.*
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Native multitouch instrument face. Engine LEDs never derive from held touches. */
class PanelView(context: Context) : View(context) {
    interface Actions {
        fun key(id: Int, down: Boolean)

        fun encoder(id: Int, steps: Int)

        fun master(value: Int)

        fun enlarge()
    }

    companion object {
        @JvmField
        val FINISH_NAMES =
            arrayOf("Black", "Purple", "Orange", "Black-Green", "Cool Gray", "White-Blue")
        @JvmField
        val PALETTES =
            arrayOf(
                intArrayOf(
                    0x3b3c3c,
                    0x343535,
                    0x555757,
                    0x303231,
                    0x272928,
                    0xe5e6e3,
                    0xf0f1eb,
                    0xbaffad,
                    0x18251c,
                ),
                intArrayOf(
                    0xb88fc9,
                    0xae82bf,
                    0x9a79ae,
                    0x8178b7,
                    0x7b72af,
                    0x211728,
                    0x06060c,
                    0xe9caff,
                    0x473962,
                ),
                intArrayOf(
                    0xbf704c,
                    0xb36746,
                    0xa75f43,
                    0xa06959,
                    0x915c4f,
                    0x0c0806,
                    0xffffff,
                    0xffd59c,
                    0x4c3026,
                ),
                intArrayOf(
                    0x3b3c3c,
                    0x343535,
                    0x555757,
                    0x7ccdc4,
                    0x69bbb2,
                    0xe5e6e3,
                    0x203d3c,
                    0xa6fff0,
                    0x244c48,
                ),
                intArrayOf(
                    0xdfd8d0,
                    0xd5cdc3,
                    0xc6beb5,
                    0x444c4e,
                    0x363e40,
                    0x252a2b,
                    0xeef4f2,
                    0xe2edff,
                    0x293a46,
                ),
                intArrayOf(
                    0xeeece4,
                    0xe3e0d7,
                    0xd2d0c8,
                    0x52758e,
                    0x42637c,
                    0x2a343a,
                    0xfffbf5,
                    0xa7ddff,
                    0x294052,
                ),
            )
        private val LABELS =
            arrayOf(
                "FX",
                "SEL",
                "ENV",
                "LFO",
                "EDIT",
                "GLO",
                "HOME",
                "SAVE",
                "ARP",
                "SEQ",
                "PLAY\nSTOP",
                "REC",
            )
        private val WHITES = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)
        private val BLACKS =
            arrayOf(
                intArrayOf(1, 0),
                intArrayOf(3, 1),
                intArrayOf(5, 2),
                intArrayOf(8, 4),
                intArrayOf(10, 5),
                intArrayOf(13, 7),
                intArrayOf(15, 8),
                intArrayOf(17, 9),
                intArrayOf(20, 11),
                intArrayOf(22, 12),
                intArrayOf(25, 14),
            )
    }

    private class Control(
        val label: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        val type: Int,
        val id: Int,
    ) {
        val r = RectF(x, y, x + w, y + h)
    }

    private class Touch(var control: Int, var y: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val controls = ArrayList<Control>()
    private val touches = SparseArray<Touch>()
    private val held = IntArray(41)
    private val angles = FloatArray(8)
    private val screen = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    private var artwork: Bitmap? = null
    private var scale = 1f
    private var referenceHeight = 0f
    private var keyboardTop = 0f
    private var finish = 0
    private var master = 716
    private var leds = FloatArray(41)
    private var screenReady = false
    private var actions: Actions? = null

    init {
        contentDescription = "FM–1 instrument panel, 27 keys"
        isFocusable = true
    }

    fun setActions(value: Actions) {
        actions = value
    }

    fun setMasterIndicator(value: Int) {
        master = value.coerceIn(0, 1023)
        invalidate()
    }

    fun setFinish(value: Int) {
        finish = value.coerceIn(0, 5)
        rebuild()
    }

    fun setReferenceHeight(pixels: Float) {
        referenceHeight = pixels
        rebuild()
    }

    fun updateFrame(pixels: IntArray?, levels: FloatArray?) {
        if (pixels?.size == 57600) {
            screen.setPixels(pixels, 0, 240, 0, 0, 240, 240)
            screenReady = true
        }
        if (levels != null) leds = levels.clone()
        invalidate()
    }

    private fun add(l: String, x: Float, y: Float, w: Float, h: Float, t: Int, id: Int) {
        controls.add(Control(l, x, y, w, h, t, id))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        releaseTouches()
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        scale = resources.displayMetrics.density
        val w = width / scale
        val h = height / scale
        val kh = min(240f, max(118f, h * .42f))
        val u = h - kh - 8
        if (u < 110) return
        val left = w * .235f
        val slot = (left - 22) / 2
        val ref = if (referenceHeight > 0) min(h, referenceHeight / scale) else h
        var ru = ref - min(240f, max(118f, ref * .42f)) - 8
        if (ru < 110) ru = u
        val rs =
            min(w * .29f, min(max(w * .23f, ru - 4), max(w * .23f, w - left - 24 - (6 * 44 + 25))))
        val rr = w - left - rs - 24
        val k = max(24f, min(90f, min(slot, min((ru - 88) / 2, min((rr - 30) / 4, ru * .36f)))))
        val sw = min(max(w * .23f, u - 4), max(0f, w - left - 24 - max(6 * 40f + 25, 4 * k + 30)))
        val rx = left + sw + 12
        val rw = w - rx - 12
        val gap = max(0f, min(slot + 4 - k, ru - 63 - 2 * k))
        val cluster = 2 * k + gap
        val x = (left - cluster) / 2
        val y = (19 + u - 44 - cluster) / 2
        val ft = max(k + 26, ru * .51f)
        val offset = u - ru
        val ky = (ft - k + 15) / 2 + offset
        controls.clear()
        add("MASTER", x, y, k, k, 2, 7)
        add("SELECT", x + k + gap, y, k, k, 1, 0)
        add("PRESETS", x, y + k + gap, k, k, 1, 6)
        add("ALGORITHM", x + k + gap, y + k + gap, k, k, 1, 1)
        add("OCT−", 9f, u - 39, (left - 22) / 2, 36f, 0, 0)
        add("OCT+", 13 + (left - 22) / 2, u - 39, (left - 22) / 2, 36f, 0, 1)
        val ss = min(sw - 8, u - 16)
        add("Enlarge display", left + 4 + (sw - 8 - ss) / 2, 12f, ss, ss, 3, 0)
        for (i in 0..3) add("KNOB${i+1}", rx + rw / 4 * i + (rw / 4 - k) / 2, ky, k, k, 1, i + 2)
        val bw = (rw - 25) / 6
        val bh = (ru - ft - 8) / 2
        for (i in 0..11) add(
            LABELS[i],
            rx + i % 6 * (bw + 5),
            ft + offset + i / 6 * (bh + 5),
            bw,
            bh,
            0,
            i + 2,
        )
        keyboardTop = u + 5
        val pitch = (w - 16) / 16
        for (i in 0..15) add(
            "",
            8 + i * pitch,
            u + 8 + kh * .48f,
            pitch - 2,
            kh * .52f - 4,
            0,
            WHITES[i] + 14,
        )
        for (b in BLACKS) add(
            "",
            8 + pitch * .5f + b[1] * pitch,
            u + 10,
            pitch - 2,
            kh * .43f,
            0,
            b[0] + 14,
        )
        artwork?.recycle()
        artwork = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(artwork!!)
        c.scale(scale, scale)
        drawBase(c, w, h)
        invalidate()
    }

    private fun color(value: Int) = 0xff000000.toInt() or value

    private fun box(c: Canvas, r: RectF, radius: Float, top: Int, bottom: Int, stroke: Int) {
        paint.shader =
            LinearGradient(
                0f,
                r.top,
                0f,
                r.bottom,
                color(top),
                color(bottom),
                Shader.TileMode.CLAMP,
            )
        paint.style = Paint.Style.FILL
        c.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
        if (stroke != 0) {
            paint.color = color(stroke)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            c.drawRoundRect(r, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, rgb: Int) {
        paint.shader = null
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size
        paint.color = color(rgb)
        val lines = s.split("\n")
        for (i in lines.indices) c.drawText(
            lines[i],
            x,
            y + (i - (lines.size - 1) / 2f) * size * 1.12f,
            paint,
        )
    }

    private fun lamp(a: Control) =
        if (a.id >= 14)
            RectF(
                a.r.centerX() - 2,
                a.r.top + 10,
                a.r.centerX() + 2,
                a.r.top + 10 + max(16f, a.r.height() * .4f),
            )
        else RectF(a.r.centerX() - 7, a.r.top + 3, a.r.centerX() + 7, a.r.top + 5)

    private fun drawBase(c: Canvas, w: Float, h: Float) {
        val p = PALETTES[finish]
        box(c, RectF(.5f, .5f, w - .5f, h - .5f), 17f, p[0], p[1], p[2])
        box(c, RectF(3f, keyboardTop, w - 3, h - 2), 12f, p[3], p[4], 0x262a2b)
        for (a in controls) {
            if (a.type == 3) {
                val b = RectF(a.r)
                b.inset(-4f, -4f)
                box(c, b, 8f, 0x161b1e, 0x090c0f, 0x677374)
                continue
            }
            if (a.type == 1 || a.type == 2) {
                box(c, a.r, a.r.width() / 2, 0x171919, 0x0e1010, 0x575b5a)
                val b = RectF(a.r)
                b.inset(3f, 3f)
                box(c, b, b.width() / 2, 0x303332, 0x171a19, 0x494d4b)
                b.inset(6f, 6f)
                box(c, b, b.width() / 2, 0x292c2b, 0x1b1e1d, 0)
                text(c, a.label, a.r.centerX(), a.r.top - 5, 10f, p[5])
            } else {
                box(c, a.r, if (a.id >= 14) 9f else 7f, p[3], p[4], p[4])
                if (a.id >= 14) {
                    var outline = 0
                    for (shift in intArrayOf(16, 8, 0)) outline =
                        outline or
                            ((((p[4] shr shift) and 255) * .74f + ((p[6] shr shift) and 255) * .26f)
                                .roundToInt() shl shift)
                    val edge = RectF(a.r)
                    edge.inset(.65f, .65f)
                    paint.color = color(outline)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.3f
                    c.drawRoundRect(edge, 8.35f, 8.35f, paint)
                    paint.style = Paint.Style.FILL
                }
                if (a.id < 14)
                    text(
                        c,
                        a.label,
                        a.r.centerX(),
                        a.r.centerY() + 4,
                        if (a.id < 2) 12f else 11f,
                        p[6],
                    )
                else
                    for (j in 0..9) if (BLACKS[j][0] == a.id - 14)
                        text(
                            c,
                            arrayOf(
                                "OP1",
                                "OP2",
                                "OP3",
                                "OP4",
                                "OP5",
                                "OP6",
                                "PIT",
                                "GLO",
                                "MONO",
                                "POLY",
                            )[j],
                            a.r.centerX(),
                            a.r.bottom - 5,
                            9f,
                            p[6],
                        )
                paint.color = color(p[8])
                val l = lamp(a)
                val inset = if (finish == 0) -2f else -.75f
                l.inset(inset, inset)
                c.drawRoundRect(l, 3f, 3f, paint)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val art = artwork ?: return
        canvas.drawBitmap(art, 0f, 0f, null)
        canvas.save()
        canvas.scale(scale, scale)
        val p = PALETTES[finish]
        for (a in controls) {
            if (a.type == 3) {
                if (screenReady) {
                    paint.isFilterBitmap = false
                    canvas.drawBitmap(screen, null, a.r, paint)
                } else text(canvas, "FM–1", a.r.centerX(), a.r.centerY(), 28f, p[7])
                continue
            }
            if (a.type == 1 || a.type == 2) {
                val angle = if (a.type == 2) -130 + master / 1023f * 260 else -30 + angles[a.id]
                val rad = Math.toRadians((angle - 90).toDouble())
                val r = a.r.width() * .32f
                paint.color = 0xffe2e6e3.toInt()
                paint.strokeWidth = 2.7f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(
                    a.r.centerX() + cos(rad).toFloat() * r * .6f,
                    a.r.centerY() + sin(rad).toFloat() * r * .6f,
                    a.r.centerX() + cos(rad).toFloat() * r,
                    a.r.centerY() + sin(rad).toFloat() * r,
                    paint,
                )
                continue
            }
            if (held[a.id] > 0) {
                paint.color = 0x44000000
                canvas.drawRoundRect(a.r, 8f, 8f, paint)
            }
            val duty = if (a.id < leds.size) leds[a.id] else 0f
            val level = (duty * 1100 / 95).coerceIn(0f, 1f)
            val alpha = level * 1.03f / (level + .03f)
            if (alpha > 0) {
                val l = lamp(a)
                paint.color = color(p[7])
                paint.alpha = (alpha * 255).toInt()
                paint.setShadowLayer(if (finish == 0) 7f else 4.5f, 0f, 0f, color(p[7]))
                canvas.drawRoundRect(l, 2f, 2f, paint)
                paint.clearShadowLayer()
                paint.alpha = 255
            }
        }
        canvas.restore()
    }

    private fun hit(x: Float, y: Float): Int {
        for (i in controls.indices.reversed()) if (controls[i].r.contains(x, y)) return i
        if (y >= keyboardTop) {
            var best = Float.MAX_VALUE
            var result = -1
            for (i in controls.indices) {
                val c = controls[i]
                if (c.type == 0 && c.id >= 14) {
                    val dx = max(c.r.left - x, max(0f, x - c.r.right))
                    val dy = max(c.r.top - y, max(0f, y - c.r.bottom))
                    val d = dx * dx + dy * dy
                    if (d < best) {
                        best = d
                        result = i
                    }
                }
            }
            return result
        }
        return -1
    }

    private fun hold(index: Int, down: Boolean) {
        val c = controls[index]
        if (c.type == 0) {
            if (down) {
                if (held[c.id]++ == 0) actions?.key(c.id, true)
            } else if (held[c.id] > 0 && --held[c.id] == 0) actions?.key(c.id, false)
        }
    }

    fun releaseTouches() {
        for (i in 0..40) {
            if (held[i] > 0) actions?.key(i, false)
            held[i] = 0
        }
        touches.clear()
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        val index = e.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val c = hit(e.getX(index) / scale, e.getY(index) / scale)
                if (c >= 0) {
                    touches.put(e.getPointerId(index), Touch(c, e.getY(index) / scale))
                    hold(c, true)
                }
            }
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(i)] ?: continue
                    val c = controls[t.control]
                    val y = e.getY(i) / scale
                    if (c.type == 1 || c.type == 2) {
                        val steps = ((t.y - y) / 4).toInt()
                        if (steps != 0) {
                            t.y -= steps * 4
                            if (c.type == 2) {
                                master = (master + steps * 12).coerceIn(0, 1023)
                                actions?.master(master)
                            } else {
                                angles[c.id] += steps * 5.7f
                                actions?.encoder(c.id, steps)
                            }
                        }
                    } else if (c.type == 0 && c.id >= 14) {
                        val next = hit(e.getX(i) / scale, y)
                        if (
                            next >= 0 &&
                                controls[next].type == 0 &&
                                controls[next].id >= 14 &&
                                next != t.control
                        ) {
                            hold(t.control, false)
                            t.control = next
                            hold(next, true)
                        }
                    }
                }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val t = touches[e.getPointerId(index)]
                if (t != null) {
                    hold(t.control, false)
                    if (controls[t.control].type == 3) actions?.enlarge()
                    touches.remove(e.getPointerId(index))
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> releaseTouches()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        releaseTouches()
        super.onDetachedFromWindow()
    }
}
