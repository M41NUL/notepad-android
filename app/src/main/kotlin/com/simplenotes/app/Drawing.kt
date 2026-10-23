package com.simplenotes.app

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat

class DrawingView(ctx: Context) : View(ctx) {
    private class Stroke(val path: Path, val paint: Paint)

    private val strokes = ArrayList<Stroke>()
    private var cur: Stroke? = null
    private var lastX = 0f
    private var lastY = 0f

    var inkColor = Color.BLACK
    var inkWidth = 5f * ctx.resources.displayMetrics.density
    var inkAlpha = 255

    val isBlank get() = strokes.isEmpty()

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate()
        }
    }

    fun clear() {
        strokes.clear()
        invalidate()
    }

    private fun newPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor
        alpha = inkAlpha
        strokeWidth = inkWidth
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val s = Stroke(Path().apply { moveTo(x, y) }, newPaint())
                strokes.add(s)
                cur = s
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> cur?.let {
                it.path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                cur?.path?.lineTo(x + 0.1f, y)
                cur = null
            }
            MotionEvent.ACTION_CANCEL -> cur = null
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.WHITE)
        for (s in strokes) c.drawPath(s.path, s.paint)
    }

    fun toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        return bmp
    }
}

object DrawingDialog {
    private val inks = intArrayOf(
        0xFF1C1C1E.toInt(), 0xFFFF3B30.toInt(), 0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt()
    )

    fun show(ctx: Context, onSave: (Bitmap) -> Unit) {
        val dlg = Dialog(ctx, R.style.Theme_App_Draw)
        val surface = ctx.themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = ctx.themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val primary = ctx.themeColor(androidx.appcompat.R.attr.colorPrimary, Color.BLUE)

        val canvas = DrawingView(ctx)
        fun action(label: Int, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
            setText(label)
            textSize = 16f
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(14), 0, ctx.dpi(14), 0)
            Motion.press(this)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dpi(48))
        }

        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(4), 0, ctx.dpi(4), 0)
            addView(action(R.string.cancel, onSurface) { dlg.dismiss() })
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(action(R.string.draw_undo, onSurface) { canvas.undo() })
            addView(action(R.string.draw_clear, onSurface) { canvas.clear() })
            addView(action(R.string.draw_save, primary) {
                if (canvas.isBlank) dlg.dismiss()
                else {
                    onSave(canvas.toBitmap())
                    dlg.dismiss()
                }
            })
        }

        var sizeBtn: TextView? = null
        sizeBtn = action(R.string.draw_size, onSurface) {
            sizeBtn?.let { DrawingSize.show(ctx, it, canvas, onSurface, primary) }
        }

        val dots = ArrayList<View>()
        var custom = 0
        var customDot: View? = null
        val rainbow = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
        )
        fun refreshDots() {
            dots.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setStroke(
                    ctx.dpi(if (inks[i] == canvas.inkColor) 3 else 1),
                    if (inks[i] == canvas.inkColor) primary else 0x33000000
                )
            }
            customDot?.let { v ->
                val on = custom != 0 && canvas.inkColor == custom
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (custom == 0) {
                        gradientType = GradientDrawable.SWEEP_GRADIENT
                        colors = rainbow
                    } else setColor(custom)
                    setStroke(ctx.dpi(if (on) 3 else 1), if (on) primary else 0x33000000)
                }
            }
        }
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ctx.dpi(10), 0, ctx.dpi(14))
            inks.forEach { col ->
                val d = View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(col)
                    }
                    setOnClickListener {
                        canvas.inkColor = col
                        refreshDots()
                    }
                }
                dots.add(d)
                addView(d, LinearLayout.LayoutParams(ctx.dpi(32), ctx.dpi(32)).apply {
                    setMargins(ctx.dpi(5), 0, ctx.dpi(5), 0)
                })
            }
            val cd = View(ctx).apply {
                setOnClickListener {
                    if (custom != 0 && canvas.inkColor != custom) {
                        canvas.inkColor = custom
                        refreshDots()
                    } else {
                        ColorPicker.show(ctx, if (custom != 0) custom else canvas.inkColor) { c ->
                            custom = c
                            canvas.inkColor = c
                            refreshDots()
                        }
                    }
                }
                setOnLongClickListener {
                    ColorPicker.show(ctx, if (custom != 0) custom else canvas.inkColor) { c ->
                        custom = c
                        canvas.inkColor = c
                        refreshDots()
                    }
                    true
                }
            }
            customDot = cd
            addView(cd, LinearLayout.LayoutParams(ctx.dpi(32), ctx.dpi(32)).apply {
                setMargins(ctx.dpi(5), 0, ctx.dpi(5), 0)
            })
            addView(sizeBtn)
        }
        refreshDots()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            fitsSystemWindows = true
            addView(top)
            addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom)
        }
        dlg.setContentView(root)
        dlg.show()
    }
}

private class StrokePreview(ctx: Context, private val target: DrawingView) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init {
        background = GradientDrawable().apply {
            cornerRadius = ctx.dpi(12).toFloat()
            setColor(Color.WHITE)
            setStroke(ctx.dpi(1), 0x1F000000)
        }
    }

    override fun onDraw(c: Canvas) {
        paint.color = target.inkColor
        paint.alpha = target.inkAlpha
        paint.strokeWidth = target.inkWidth
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = target.inkWidth / 2f + context.dpi(14)
        path.reset()
        path.moveTo(pad, h / 2f)
        path.cubicTo(w * 0.28f, h * 0.18f, w * 0.42f, h * 0.82f, w / 2f, h / 2f)
        path.cubicTo(w * 0.58f, h * 0.18f, w * 0.72f, h * 0.82f, w - pad, h / 2f)
        c.drawPath(path, paint)
    }
}

private class SizeDot(ctx: Context, val sizeDp: Int, private val target: DrawingView, private val ring: Int) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dpi(2).toFloat()
        color = ring
    }

    fun selected() = Math.abs(target.inkWidth / resources.displayMetrics.density - sizeDp) < 0.75f

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = context.dpi(sizeDp.coerceAtMost(26)) / 2f
        fill.color = target.inkColor
        c.drawCircle(cx, cy, r, fill)
        if (selected()) c.drawCircle(cx, cy, context.dpi(19).toFloat(), stroke)
    }
}

object DrawingSize {
    private val presets = intArrayOf(2, 5, 10, 18, 30)

    fun show(ctx: Context, anchor: View, canvas: DrawingView, onSurface: Int, primary: Int) {
        val density = ctx.resources.displayMetrics.density
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(18), ctx.dpi(16), ctx.dpi(18), ctx.dpi(18))
            background = GradientDrawable().apply {
                cornerRadius = ctx.resources.getDimension(R.dimen.radius_card)
                setColor(ContextCompat.getColor(ctx, R.color.glass_fill))
                setStroke(ctx.dpi(1), ContextCompat.getColor(ctx, R.color.glass_stroke))
            }
        }

        val value = TextView(ctx).apply {
            textSize = 15f
            setTextColor(primary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        card.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                setText(R.string.draw_brush)
                textSize = 16f
                setTextColor(onSurface)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        })

        val preview = StrokePreview(ctx, canvas)
        card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(84)).apply {
            topMargin = ctx.dpi(12)
        })

        val seek = SeekBar(ctx).apply {
            max = 39
            progressTintList = ColorStateList.valueOf(primary)
            thumbTintList = ColorStateList.valueOf(primary)
        }
        card.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8)
        })

        val dotRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val dots = presets.map { SizeDot(ctx, it, canvas, primary) }
        dots.forEach { d ->
            dotRow.addView(d, LinearLayout.LayoutParams(0, ctx.dpi(46), 1f))
        }
        card.addView(dotRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(2)
        })

        val opValue = TextView(ctx).apply {
            textSize = 15f
            setTextColor(primary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        card.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply {
                setText(R.string.draw_opacity)
                textSize = 16f
                setTextColor(onSurface)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(opValue)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8)
        })

        val opSeek = SeekBar(ctx).apply {
            max = 95
            progressTintList = ColorStateList.valueOf(primary)
            thumbTintList = ColorStateList.valueOf(primary)
        }
        card.addView(opSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(4)
        })

        var syncing = false
        fun sync() {
            syncing = true
            val dp = (canvas.inkWidth / density).toInt().coerceIn(1, 40)
            seek.progress = dp - 1
            value.text = "$dp dp"
            preview.invalidate()
            dots.forEach { it.invalidate() }
            val pct = (canvas.inkAlpha * 100 / 255).coerceIn(5, 100)
            opSeek.progress = pct - 5
            opValue.text = "$pct%"
            syncing = false
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                canvas.inkWidth = (progress + 1) * density
                value.text = "${progress + 1} dp"
                preview.invalidate()
                dots.forEach { it.invalidate() }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        dots.forEach { d ->
            d.setOnClickListener {
                canvas.inkWidth = d.sizeDp * density
                sync()
            }
        }
        opSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                val pct = progress + 5
                canvas.inkAlpha = pct * 255 / 100
                opValue.text = "$pct%"
                preview.invalidate()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        sync()

        val screenW = ctx.resources.displayMetrics.widthPixels
        val pw = PopupWindow(card, minOf(ctx.dpi(340), screenW - ctx.dpi(32)), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.animationStyle = R.style.Animation_App_Dialog
        val bar = anchor.parent as View
        val loc = IntArray(2)
        bar.getLocationInWindow(loc)
        val root = anchor.rootView
        pw.showAtLocation(root, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, root.height - loc[1] + ctx.dpi(8))
    }
}

object ColorPicker {
    private val rainbow = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
        0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
    )

    fun show(ctx: Context, initial: Int, onPick: (Int) -> Unit) {
        val onSurface = ctx.themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var updating = false

        val swatch = View(ctx)
        val swatchBg = GradientDrawable().apply {
            cornerRadius = ctx.dpi(14).toFloat()
            setStroke(ctx.dpi(1), 0x33000000)
        }
        swatch.background = swatchBg

        val hex = EditText(ctx).apply {
            setSingleLine()
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = android.graphics.Typeface.MONOSPACE
            filters = arrayOf(InputFilter.LengthFilter(7))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }

        fun track(colors: IntArray) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = ctx.dpi(7).toFloat()
            setSize(-1, ctx.dpi(14))
        }

        val hueTrack = track(rainbow)
        val satTrack = track(intArrayOf(Color.WHITE, Color.RED))
        val valTrack = track(intArrayOf(Color.BLACK, Color.RED))

        fun slider(max: Int, bg: GradientDrawable, onChange: (Int) -> Unit) = SeekBar(ctx).apply {
            this.max = max
            progressDrawable = bg
            splitTrack = false
            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(ctx.dpi(2), 0x66000000)
                setSize(ctx.dpi(26), ctx.dpi(26))
            }
            setPadding(ctx.dpi(13), 0, ctx.dpi(13), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updating) onChange(progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        lateinit var hueSeek: SeekBar
        lateinit var satSeek: SeekBar
        lateinit var valSeek: SeekBar

        fun refresh(skipHex: Boolean) {
            updating = true
            val c = Color.HSVToColor(hsv)
            swatchBg.setColor(c)
            hueSeek.progress = hsv[0].toInt().coerceIn(0, 360)
            satSeek.progress = (hsv[1] * 100).toInt()
            valSeek.progress = (hsv[2] * 100).toInt()
            satTrack.colors = intArrayOf(
                Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])),
                Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))
            )
            valTrack.colors = intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            if (!skipHex) {
                hex.setText(String.format("#%06X", c and 0xFFFFFF))
                hex.setSelection(hex.text.length)
            }
            updating = false
        }

        hueSeek = slider(360, hueTrack) {
            hsv[0] = it.toFloat()
            refresh(false)
        }
        satSeek = slider(100, satTrack) {
            hsv[1] = it / 100f
            refresh(false)
        }
        valSeek = slider(100, valTrack) {
            hsv[2] = it / 100f
            refresh(false)
        }

        hex.afterChanged {
            if (updating) return@afterChanged
            val t = hex.text.toString().trim().removePrefix("#")
            if (t.length == 6 && t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                Color.colorToHSV(Color.parseColor("#$t"), hsv)
                refresh(true)
            }
        }

        fun label(res: Int) = TextView(ctx).apply {
            setText(res)
            textSize = 13f
            alpha = 0.6f
            setTextColor(onSurface)
        }

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(24), ctx.dpi(8), ctx.dpi(24), 0)
            addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(64)))
            addView(hex, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(8)
            })
            for ((res, sb) in listOf(R.string.color_hue to hueSeek, R.string.color_sat to satSeek, R.string.color_bright to valSeek)) {
                addView(label(res), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dpi(10)
                })
                addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dpi(36)))
            }
        }
        refresh(false)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.color_custom)
            .setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.color_select) { _, _ -> onPick(Color.HSVToColor(hsv)) }
            .show()
    }
}
