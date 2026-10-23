package com.simplenotes.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        applyTheme(prefs.theme)
        applyLanguage(prefs.language)
        Reminders.createChannel(this)
        val db = NoteDb.of(this)
        val days = prefs.trashDays.coerceAtLeast(1)
        db.bg { db.purgeTrash(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000) }
    }

    companion object {
        fun applyTheme(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }

        fun applyLanguage(tag: String) {
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag)
            )
        }
    }
}
