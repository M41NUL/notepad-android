package com.simplenotes.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("notepad_prefs", Context.MODE_PRIVATE)

    var sort: Sort
        get() = Sort.entries.getOrElse(sp.getInt("sort", 0)) { Sort.NEWEST }
        set(v) = sp.edit().putInt("sort", v.ordinal).apply()

    var grid: Boolean
        get() = sp.getBoolean("grid", false)
        set(v) = sp.edit().putBoolean("grid", v).apply()

    var lock: Boolean
        get() = sp.getBoolean("lock", false)
        set(v) = sp.edit().putBoolean("lock", v).apply()

    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    var accent: Int
        get() = sp.getInt("accent", 3).coerceIn(0, Accent.styles.size - 1)
        set(v) = sp.edit().putInt("accent", v).apply()

    val glass: Int get() = 55

    var textSize: Int
        get() = sp.getInt("text_size", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("text_size", v).apply()

    val fontScale: Float get() = floatArrayOf(0.9f, 1f, 1.15f)[textSize]

    val anim: Boolean get() = true
    val haptics: Boolean get() = true
    val sound: Boolean get() = true

    var trashDays: Int
        get() = sp.getInt("trash_days", 30)
        set(v) = sp.edit().putInt("trash_days", v).apply()

    var alarmSound: String
        get() = sp.getString("alarm_sound", "") ?: ""
        set(v) = sp.edit().putString("alarm_sound", v).apply()

    var language: String
        get() = sp.getString("language", "") ?: ""
        set(v) = sp.edit().putString("language", v).apply()
}
