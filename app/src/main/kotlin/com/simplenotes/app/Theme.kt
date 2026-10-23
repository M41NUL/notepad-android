package com.simplenotes.app

import android.content.Context

object ThemeState {
    @Volatile
    var stamp = 0
        private set

    fun setMode(ctx: Context, mode: Int) {
        val prefs = Prefs(ctx)
        if (prefs.theme == mode) return
        prefs.theme = mode
        stamp++
        App.applyTheme(mode)
    }

    fun setAccent(ctx: Context, accent: Int) {
        val prefs = Prefs(ctx)
        if (prefs.accent == accent) return
        prefs.accent = accent
        stamp++
    }
}
