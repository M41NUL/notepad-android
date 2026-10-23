package com.simplenotes.app

import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray

object RichText {
    private const val FLAG = Spanned.SPAN_EXCLUSIVE_INCLUSIVE

    class BSpan : StyleSpan(Typeface.BOLD)
    class ISpan : StyleSpan(Typeface.ITALIC)
    class USpan : UnderlineSpan()
    class HSpan(val level: Int) : MetricAffectingSpan() {
        private val scale = if (level == 1) 1.6f else 1.3f
        override fun updateDrawState(tp: TextPaint) {
            tp.textSize = tp.textSize * scale
            tp.isFakeBoldText = true
        }
        override fun updateMeasureState(tp: TextPaint) = updateDrawState(tp)
    }

    fun kindOf(o: Any): String? = when (o) {
        is BSpan -> "b"
        is ISpan -> "i"
        is USpan -> "u"
        is HSpan -> "h${o.level}"
        else -> null
    }

    private fun make(kind: String): Any? = when (kind) {
        "b" -> BSpan()
        "i" -> ISpan()
        "u" -> USpan()
        "h1" -> HSpan(1)
        "h2" -> HSpan(2)
        else -> null
    }

    private fun mine(ed: Spanned, s: Int, e: Int): List<Any> =
        ed.getSpans(s, e, Any::class.java).filter { kindOf(it) != null }

    fun toJson(ed: Spanned, lead: Int = 0, len: Int = ed.length - lead): String {
        val arr = JSONArray()
        for (sp in mine(ed, 0, ed.length)) {
            val a = (ed.getSpanStart(sp) - lead).coerceIn(0, len)
            val b = (ed.getSpanEnd(sp) - lead).coerceIn(0, len)
            if (b > a) arr.put(JSONArray().put(kindOf(sp)).put(a).put(b))
        }
        return if (arr.length() == 0) "" else arr.toString()
    }

    fun apply(target: Spannable, json: String) {
        if (json.isBlank()) return
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONArray(i)
                val span = make(o.getString(0)) ?: continue
                val a = o.getInt(1).coerceIn(0, target.length)
                val b = o.getInt(2).coerceIn(0, target.length)
                if (b > a) target.setSpan(span, a, b, FLAG)
            }
        }
    }

    private fun covered(ed: Spanned, kind: String, s: Int, e: Int): Boolean {
        val seen = BooleanArray(e - s)
        for (sp in mine(ed, s, e)) {
            if (kindOf(sp) != kind) continue
            val a = maxOf(ed.getSpanStart(sp), s)
            val b = minOf(ed.getSpanEnd(sp), e)
            for (i in a until b) seen[i - s] = true
        }
        return seen.all { it }
    }

    private fun strip(ed: Editable, s: Int, e: Int, pred: (Any) -> Boolean) {
        for (sp in mine(ed, s, e).filter(pred)) {
            val a = ed.getSpanStart(sp)
            val b = ed.getSpanEnd(sp)
            val k = kindOf(sp)!!
            ed.removeSpan(sp)
            if (a < s) ed.setSpan(make(k)!!, a, s, FLAG)
            if (b > e) ed.setSpan(make(k)!!, e, b, FLAG)
        }
    }

    fun toggle(ed: Editable, kind: String, s: Int, e: Int) {
        if (e <= s) return
        if (covered(ed, kind, s, e)) {
            strip(ed, s, e) { kindOf(it) == kind }
        } else {
            if (kind.startsWith("h")) strip(ed, s, e) { it is HSpan } else strip(ed, s, e) { kindOf(it) == kind }
            ed.setSpan(make(kind)!!, s, e, FLAG)
        }
    }

    fun has(ed: Spanned, kind: String, s: Int, e: Int): Boolean = e > s && covered(ed, kind, s, e)

    fun clear(ed: Editable, s: Int, e: Int) {
        if (e > s) strip(ed, s, e) { true }
    }
}
