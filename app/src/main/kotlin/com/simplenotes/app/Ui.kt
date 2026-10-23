package com.simplenotes.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.themeColor(attr: Int, fallback: Int = Color.GRAY) = MaterialColors.getColor(this, attr, fallback)

class SheetRow(val icon: Int, val text: String, val destructive: Boolean = false, val action: () -> Unit)

object Sheet {
    fun show(ctx: Context, title: String?, rows: List<SheetRow>) {
        val dlg = BottomSheetDialog(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dpi(8), ctx.dpi(12), ctx.dpi(8), ctx.dpi(24))
        }
        if (title != null) {
            box.addView(TextView(ctx).apply {
                text = title
                textSize = 13f
                alpha = 0.6f
                setPadding(ctx.dpi(16), ctx.dpi(4), ctx.dpi(16), ctx.dpi(8))
            })
        }
        val onSurface = ctx.themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val primary = ctx.themeColor(androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val red = ContextCompat.getColor(ctx, R.color.swipe_bg)
        for (r in rows) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dpi(16), 0, ctx.dpi(16), 0)
                minimumHeight = ctx.dpi(54)
                isClickable = true
                Motion.press(this, 0.98f)
            }
            row.addView(ImageView(ctx).apply {
                setImageResource(r.icon)
                imageTintList = ColorStateList.valueOf(if (r.destructive) red else primary)
                layoutParams = LinearLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24))
            })
            row.addView(TextView(ctx).apply {
                text = r.text
                textSize = 17f
                setTextColor(if (r.destructive) red else onSurface)
                setPadding(ctx.dpi(16), 0, 0, 0)
            })
            row.setOnClickListener {
                dlg.dismiss()
                r.action()
            }
            box.addView(row)
        }
        dlg.setContentView(ScrollView(ctx).apply { addView(box) })
        flat(dlg)
        dlg.show()
    }

    fun flat(dlg: BottomSheetDialog) {
        dlg.setOnShowListener {
            dlg.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.elevation = 0f
        }
    }
}

fun Context.inputFrame(input: View) = FrameLayout(this).apply {
    setPadding(dpi(24), dpi(8), dpi(24), 0)
    addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

fun Context.labelDialog(db: NoteDb, current: String, onOk: (String) -> Unit) {
    val input = AutoCompleteTextView(this).apply {
        hint = getString(R.string.label_hint)
        setSingleLine()
        setText(current)
        threshold = 1
        setAdapter(ArrayAdapter(this@labelDialog, android.R.layout.simple_dropdown_item_1line, db.labels()))
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(if (current.isBlank()) R.string.label_add else R.string.label_edit)
        .setView(inputFrame(input))
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ok) { _, _ -> onOk(input.text.toString().trim().replace('\n', ' ')) }
        .show()
}

fun Context.nameDialog(title: Int, current: String, onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        hint = getString(R.string.folder_name_hint)
        setSingleLine()
        setText(current)
        setSelection(current.length)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(inputFrame(input))
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ok) { _, _ ->
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) onOk(name)
        }
        .show()
}

object Rows {
    fun sectionTitle(ctx: Context, label: String) = TextView(ctx).apply {
        text = label.uppercase()
        textSize = 12.5f
        alpha = 0.6f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
        setPadding(ctx.dpi(20), ctx.dpi(22), ctx.dpi(20), ctx.dpi(8))
    }

    fun group(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_glass_card)
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(ctx.dpi(16), 0, ctx.dpi(16), 0) }
    }

    fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(ContextCompat.getColor(ctx, R.color.glass_stroke))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = ctx.dpi(60)
        }
    }

    private fun tile(ctx: Context, icon: Int, color: Int) = ImageView(ctx).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        val p = ctx.dpi(5)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dpi(8).toFloat()
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(ctx.dpi(30), ctx.dpi(30)).apply { marginEnd = ctx.dpi(14) }
    }

    fun row(
        ctx: Context, icon: Int, tileColor: Int, title: String, subtitle: String? = null,
        value: String? = null, chevron: Boolean = false, trailing: View? = null, onClick: (() -> Unit)? = null
    ): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dpi(16), ctx.dpi(10), ctx.dpi(14), ctx.dpi(10))
            minimumHeight = ctx.dpi(56)
        }
        row.addView(tile(ctx, icon, tileColor))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(ctx).apply {
            text = title
            textSize = 16.5f
            setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        })
        if (subtitle != null) {
            texts.addView(TextView(ctx).apply {
                text = subtitle
                textSize = 12.5f
                alpha = 0.6f
            })
        }
        row.addView(texts)
        if (value != null) {
            row.addView(TextView(ctx).apply {
                text = value
                textSize = 15f
                alpha = 0.6f
                setPadding(ctx.dpi(8), 0, 0, 0)
            })
        }
        if (trailing != null) row.addView(trailing)
        if (chevron) {
            row.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_right)
                layoutParams = LinearLayout.LayoutParams(ctx.dpi(22), ctx.dpi(22)).apply { marginStart = ctx.dpi(2) }
            })
        }
        if (onClick != null) {
            row.isClickable = true
            row.setOnClickListener { onClick() }
            Motion.press(row, 0.985f)
        }
        return row
    }

    fun switch(ctx: Context, checked: Boolean, onChange: (Boolean) -> Unit) = MaterialSwitch(ctx).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }
}
