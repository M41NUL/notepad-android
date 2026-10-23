package com.simplenotes.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast

object Links {
    const val URL = 0
    const val EMAIL = 1
    const val PHONE = 2
    const val CODE = 3

    class Match(val start: Int, val end: Int, val kind: Int, val text: String)

    class LinkSpan(val kind: Int, val text: String, val color: Int) : ClickableSpan() {
        override fun onClick(widget: View) {}

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }
    }

    private val urlRx = Regex("""(?i)(?<![\w@./-])(?:(?:https?|ftp)://|www\.)[^\s<>"'`]+""")
    private val emailRx = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val domainRx = Regex(
        """(?i)(?<![\w@./-])(?:[a-z0-9][a-z0-9-]*\.)+(?:com|net|org|io|me|app|dev|co|bd|info|xyz|edu|gov|ai|tv|ly|link|site|online|store|tech)(?![\w@-])(?:[/?#][^\s<>"'`]*)?"""
    )
    private val phoneRx = Regex("""(?<![\w.])\+?\d(?:[\d \-()]{5,18}\d)(?![\w])""")
    private val codeRx = Regex("""(?<![\w.+\-])\d{4,8}(?![\w]|[.,]\d)""")
    private val dateRx = Regex("""\d{4}-\d{1,2}-\d{1,2}|\d{1,2}-\d{1,2}-\d{2,4}""")

    private fun trimUrl(s: String): String {
        var t = s
        while (t.isNotEmpty()) {
            val c = t.last()
            val drop = c in ".,;:!?]}" || (c == ')' && !t.contains('('))
            if (!drop) break
            t = t.dropLast(1)
        }
        return t
    }

    fun find(text: CharSequence): List<Match> {
        val s = text.toString()
        val out = ArrayList<Match>()
        fun free(a: Int, b: Int) = out.none { a < it.end && b > it.start }
        fun add(a: Int, b: Int, kind: Int) {
            if (b > a && free(a, b)) out.add(Match(a, b, kind, s.substring(a, b)))
        }
        for (m in urlRx.findAll(s)) {
            val t = trimUrl(m.value)
            if (t.length > 4) add(m.range.first, m.range.first + t.length, URL)
        }
        for (m in emailRx.findAll(s)) add(m.range.first, m.range.last + 1, EMAIL)
        for (m in domainRx.findAll(s)) {
            val t = trimUrl(m.value)
            add(m.range.first, m.range.first + t.length, URL)
        }
        for (m in phoneRx.findAll(s)) {
            val digits = m.value.count { it.isDigit() }
            if (digits !in 7..15 || dateRx.matches(m.value.trim())) continue
            add(m.range.first, m.range.last + 1, PHONE)
        }
        for (m in codeRx.findAll(s)) {
            val n = m.value.toInt()
            if (m.value.length == 4 && n in 1900..2099) continue
            add(m.range.first, m.range.last + 1, CODE)
        }
        return out.sortedBy { it.start }
    }

    fun apply(ed: Editable, color: Int) {
        val old = ed.getSpans(0, ed.length, LinkSpan::class.java)
        val found = if (ed.isEmpty() || ed.length > 30000) emptyList() else find(ed)
        val same = old.size == found.size && found.all { m ->
            old.any { ed.getSpanStart(it) == m.start && ed.getSpanEnd(it) == m.end && it.kind == m.kind && it.color == color }
        }
        if (same) return
        for (o in old) ed.removeSpan(o)
        for (m in found) ed.setSpan(LinkSpan(m.kind, m.text, color), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun decorate(sp: Spannable, color: Int) {
        if (sp.isEmpty()) return
        for (m in find(sp)) {
            sp.setSpan(ForegroundColorSpan(color), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(UnderlineSpan(), m.start, m.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachTap(et: EditText, onHit: (LinkSpan) -> Unit) {
        val detector = GestureDetector(et.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                hit(et, e)?.let(onHit)
                return false
            }
        })
        et.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            false
        }
    }

    private fun hit(et: EditText, e: MotionEvent): LinkSpan? {
        val layout = et.layout ?: return null
        val text = et.text ?: return null
        val x = e.x - et.totalPaddingLeft + et.scrollX
        val y = e.y - et.totalPaddingTop + et.scrollY
        if (y < 0 || y > layout.height) return null
        val line = layout.getLineForVertical(y.toInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val o = layout.getOffsetForHorizontal(line, x)
        val ci = if (x < layout.getPrimaryHorizontal(o)) o - 1 else o
        return text.getSpans(0, text.length, LinkSpan::class.java)
            .firstOrNull { ci >= text.getSpanStart(it) && ci < text.getSpanEnd(it) }
    }

    fun open(ctx: Context, anchor: View, s: LinkSpan) {
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(anchor.windowToken, 0)
        val rows = ArrayList<SheetRow>()
        val value = s.text
        when (s.kind) {
            URL -> {
                val url = if (Regex("(?i)^[a-z]+://").containsMatchIn(value)) value else "https://$value"
                rows.add(SheetRow(R.drawable.ic_open_in_new, ctx.getString(R.string.link_open)) {
                    launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                })
            }
            EMAIL -> rows.add(SheetRow(R.drawable.ic_mail, ctx.getString(R.string.link_email)) {
                launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$value")))
            })
            PHONE -> {
                val num = (if (value.trim().startsWith("+")) "+" else "") + value.filter { it.isDigit() }
                rows.add(SheetRow(R.drawable.ic_call, ctx.getString(R.string.link_call)) {
                    launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
                })
                rows.add(SheetRow(R.drawable.ic_sms, ctx.getString(R.string.link_message)) {
                    launch(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")))
                })
            }
        }
        rows.add(SheetRow(R.drawable.ic_copy, ctx.getString(R.string.link_copy)) { copy(ctx, value) })
        rows.add(SheetRow(R.drawable.ic_share, ctx.getString(R.string.share)) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }
            launch(ctx, Intent.createChooser(send, ctx.getString(R.string.share)))
        })
        Sheet.show(ctx, value, rows)
    }

    private fun launch(ctx: Context, i: Intent) {
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.link_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(ctx: Context, text: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("note", text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }
}
