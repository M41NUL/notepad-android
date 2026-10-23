package com.simplenotes.app

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

object Splash {
    private var shown = false

    fun show(a: Activity) {
        if (shown) return
        shown = true

        val bars = WindowCompat.getInsetsController(a.window, a.window.decorView)
        val lightStatus = bars.isAppearanceLightStatusBars
        val lightNav = bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false

        val root = FrameLayout(a).apply {
            setBackgroundColor(ContextCompat.getColor(a, R.color.splash_bg))
            isClickable = true
        }

        val gradient = View(a).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#FFA52E"), Color.parseColor("#FF8A00"), Color.parseColor("#EF7600"))
            )
            alpha = 0f
        }
        root.addView(gradient, FrameLayout.LayoutParams(-1, -1))

        fun disc(sizeDp: Int, fill: Int, gravity: Int, top: Int, bottom: Int, start: Int, end: Int): View =
            View(a).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(fill)
                }
                alpha = 0f
                root.addView(this, FrameLayout.LayoutParams(a.dpi(sizeDp), a.dpi(sizeDp), gravity).apply {
                    topMargin = a.dpi(top)
                    bottomMargin = a.dpi(bottom)
                    marginStart = a.dpi(start)
                    marginEnd = a.dpi(end)
                })
            }

        val discTop = disc(300, Color.argb(22, 255, 255, 255), Gravity.TOP or Gravity.END, -110, 0, 0, -120)
        val discBottom = disc(260, Color.argb(18, 255, 255, 255), Gravity.BOTTOM or Gravity.START, 0, -90, -110, 0)

        val glow = View(a).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = a.dpi(210).toFloat()
                colors = intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255))
            }
            alpha = 0f
        }
        root.addView(glow, FrameLayout.LayoutParams(a.dpi(420), a.dpi(420), Gravity.CENTER))

        val logo = ImageView(a).apply { setImageResource(R.drawable.ic_launcher_foreground) }
        root.addView(logo, FrameLayout.LayoutParams(a.dpi(288), a.dpi(288), Gravity.CENTER))

        val name = TextView(a).apply {
            text = a.getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 34f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.3f
            setShadowLayer(a.dpi(6).toFloat(), 0f, a.dpi(2).toFloat(), Color.argb(46, 120, 50, 0))
            alpha = 0f
            translationY = a.dpi(146).toFloat()
        }
        val line = View(a).apply {
            background = GradientDrawable().apply {
                cornerRadius = a.dpi(2).toFloat()
                setColor(Color.WHITE)
            }
            alpha = 0f
            scaleX = 0f
            translationY = a.dpi(158).toFloat()
        }
        val tag = TextView(a).apply {
            text = a.getString(R.string.splash_tagline)
            setTextColor(Color.WHITE)
            textSize = 14f
            letterSpacing = 0.04f
            alpha = 0f
            translationY = a.dpi(210).toFloat()
        }
        root.addView(name, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(line, FrameLayout.LayoutParams(a.dpi(32), a.dpi(3), Gravity.CENTER))
        root.addView(tag, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val track = FrameLayout(a).apply {
            background = GradientDrawable().apply {
                cornerRadius = a.dpi(2).toFloat()
                setColor(Color.argb(60, 255, 255, 255))
            }
        }
        val fill = View(a).apply {
            background = GradientDrawable().apply {
                cornerRadius = a.dpi(2).toFloat()
                setColor(Color.WHITE)
            }
            pivotX = 0f
            scaleX = 0f
        }
        track.addView(fill, FrameLayout.LayoutParams(-1, -1))
        root.addView(track, FrameLayout.LayoutParams(a.dpi(56), a.dpi(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = a.dpi(76)
        })

        a.addContentView(root, ViewGroup.LayoutParams(-1, -1))

        gradient.animate().alpha(1f).setDuration(600).start()
        discTop.animate().alpha(1f).setStartDelay(120).setDuration(800).start()
        discBottom.animate().alpha(1f).setStartDelay(200).setDuration(800).start()
        glow.animate().alpha(1f).setStartDelay(100).setDuration(700).start()

        logo.animate().scaleX(1.06f).scaleY(1.06f).setDuration(420).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                logo.animate().scaleX(1f).scaleY(1f).setDuration(420)
                    .setInterpolator(AccelerateDecelerateInterpolator()).start()
            }.start()

        name.animate().alpha(1f).translationY(a.dpi(122).toFloat()).setStartDelay(80).setDuration(560)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        ValueAnimator.ofFloat(0.3f, 0.03f).apply {
            startDelay = 80
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { name.letterSpacing = it.animatedValue as Float }
            start()
        }
        line.animate().alpha(0.6f).scaleX(1f).setStartDelay(220).setDuration(480)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        tag.animate().alpha(0.92f).translationY(a.dpi(190).toFloat()).setStartDelay(320).setDuration(520)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        fill.animate().scaleX(1f).setStartDelay(60).setDuration(1020).setInterpolator(LinearInterpolator()).start()

        root.postDelayed({
            bars.isAppearanceLightStatusBars = lightStatus
            bars.isAppearanceLightNavigationBars = lightNav
            root.animate().alpha(0f).scaleX(1.05f).scaleY(1.05f).setDuration(320)
                .withEndAction { (root.parent as? ViewGroup)?.removeView(root) }.start()
        }, 1150)
    }
}
