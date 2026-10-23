package com.simplenotes.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

object Accent {
    val styles = intArrayOf(
        R.style.Accent_Blue, R.style.Accent_Purple, R.style.Accent_Green,
        R.style.Accent_Orange, R.style.Accent_Pink, R.style.Accent_Teal
    )

    private val light = intArrayOf(0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFF2DB34F.toInt(),
        0xFFFF8A00.toInt(), 0xFFFF2D55.toInt(), 0xFF2AA3BC.toInt())
    private val dark = intArrayOf(0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFF30D158.toInt(),
        0xFFFF8A00.toInt(), 0xFFFF375F.toInt(), 0xFF40C8E0.toInt())

    fun swatch(ctx: Context, i: Int) = (if (Glass.isNight(ctx)) dark else light)[i]
}

object Glass {
    fun isNight(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun base(ctx: Context, idx: Int) =
        ContextCompat.getColor(ctx, NoteColors.ids[idx.coerceIn(0, NoteColors.ids.size - 1)])

    @Suppress("UNUSED_PARAMETER")
    fun cardColor(ctx: Context, idx: Int, pct: Int = 0): Int {
        val night = isNight(ctx)
        val card = if (night) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
        if (idx == 0) return card
        return ColorUtils.blendARGB(card, base(ctx, idx), if (night) 0.38f else 0.30f)
    }

    fun editorTint(ctx: Context, idx: Int): Int {
        val a = if (idx == 0) 0 else if (isNight(ctx)) 90 else 78
        return ColorUtils.setAlphaComponent(base(ctx, idx), a)
    }

    fun contrastText(bg: Int): Int =
        if (ColorUtils.calculateLuminance(bg) > 0.5) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()

    fun plainFill(ctx: Context) = if (isNight(ctx)) 0x1AFFFFFF else 0x8CFFFFFF.toInt()

    fun glassFill(ctx: Context, solid: Int, plain: Boolean) =
        if (plain) plainFill(ctx)
        else ColorUtils.setAlphaComponent(solid, if (isNight(ctx)) 0xA0 else 0xB8)

    fun surface(ctx: Context) =
        MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, 0xFFF2F2F7.toInt())

    fun opaque(ctx: Context, c: Int) = ColorUtils.compositeColors(c, surface(ctx))
}

object Motion {
    val spring = Interpolator { t -> (1.0 - Math.exp(-6.5 * t) * Math.cos(11.0 * t)).toFloat() }

    fun pressable(v: View, enabled: () -> Boolean) = press(v, 0.975f, enabled)

    @SuppressLint("ClickableViewAccessibility")
    fun press(v: View, scale: Float = 0.94f, enabled: () -> Boolean = { true }) {
        v.setOnTouchListener { view, e ->
            if (enabled()) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(scale).scaleY(scale).setDuration(70)
                            .setInterpolator(DecelerateInterpolator()).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(130)
                            .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false
        }
    }

    fun haptic(v: View, prefs: Prefs, type: Int = HapticFeedbackConstants.CLOCK_TICK) {
        if (!prefs.haptics) return
        val ms = if (type == HapticFeedbackConstants.LONG_PRESS) 40L else 22L
        buzz(v.context, prefs, ms)
    }

    @Suppress("DEPRECATION")
    fun buzz(ctx: Context, prefs: Prefs, ms: Long = 28L) {
        if (!prefs.haptics) return
        val vib: Vibrator = (if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)) ?: return
        if (!vib.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                val effect = VibrationEffect.createOneShot(ms, 200)
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                vib.vibrate(effect, attrs)
            } else vib.vibrate(ms)
        }
    }

    fun pinSound(prefs: Prefs, pinned: Boolean) {
        if (!prefs.sound) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 70)
            tone.startTone(if (pinned) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_PROMPT, 120)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
        }
    }
}

class GlassDrawable(ctx: Context, private val radius: Float) : Drawable() {
    var fill = 0
        set(v) {
            field = v
            invalidateSelf()
        }

    private val density = ctx.resources.displayMetrics.density
    private val night = Glass.isNight(ctx)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var sheen: Shader? = null
    private var edge: Shader? = null

    override fun onBoundsChange(b: android.graphics.Rect) {
        rect.set(b)
        rect.inset(density / 2, density / 2)
        val sheenTop = if (night) 0x26FFFFFF else 0x66FFFFFF
        val edgeStart = if (night) 0x66FFFFFF else 0xF2FFFFFF.toInt()
        val edgeEnd = if (night) 0x14FFFFFF else 0x40FFFFFF
        sheen = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, sheenTop, 0, Shader.TileMode.CLAMP)
        edge = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, edgeStart, edgeEnd, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = fill
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = sheen
        c.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.shader = edge
        c.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
