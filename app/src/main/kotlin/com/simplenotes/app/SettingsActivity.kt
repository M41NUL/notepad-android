package com.simplenotes.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.KeyguardManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity() {
    private lateinit var db: NoteDb
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                db.bgFor({
                    try {
                        Backup.write(this, uri, db.exportable(), db.folders())
                        true
                    } catch (e: Exception) {
                        false
                    }
                }) { ok ->
                    toast(if (ok) R.string.backup_done else R.string.backup_failed)
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                db.bgFor({
                    try {
                        Backup.restore(this, uri, db)
                    } catch (e: Exception) {
                        -1
                    }
                }) { n ->
                    if (n < 0) toast(R.string.backup_failed)
                    else Toast.makeText(this, getString(R.string.restored_n, n), Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val soundPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33)
                r.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            else @Suppress("DEPRECATION") r.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.alarmSound = uri?.toString() ?: "silent"
            Reminders.createChannel(this)
            build()
        }

    private fun pickAlarmSound() {
        val cur = Reminders.soundUri(this)
        soundPicker.launch(
            Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALARM)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM))
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.alarm_sound))
        )
    }

    private fun alarmSoundName(): String {
        val s = prefs.alarmSound
        if (s.isEmpty()) return getString(R.string.alarm_sound_default)
        if (s == "silent") return getString(R.string.alarm_sound_silent)
        return runCatching { android.media.RingtoneManager.getRingtone(this, Uri.parse(s)).getTitle(this) }
            .getOrNull() ?: getString(R.string.alarm_sound_default)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = NoteDb.of(this)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(4))
        }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            background = null
            contentDescription = getString(R.string.back)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { finish() }
            Motion.press(this)
        })
        header.addView(TextView(this).apply {
            setText(R.string.settings)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(header)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(40))
        }
        root.addView(
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(col)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
        installLock()

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        build()
    }

    private fun openAlarmSettings() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !Reminders.canExact(this)) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:$packageName"))
                )
            } else {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
    }

    private fun toast(@StringRes r: Int) = Toast.makeText(this, r, Toast.LENGTH_SHORT).show()

    private fun choose(@StringRes title: Int, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), selected) { dlg, which ->
                dlg.dismiss()
                onPick(which)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
        }
    }

    private fun addGroup(vararg rows: View) {
        val g = Rows.group(this)
        rows.forEachIndexed { i, r ->
            if (i > 0) g.addView(Rows.divider(this))
            g.addView(r)
        }
        col.addView(g)
    }

    private fun section(@StringRes r: Int) = col.addView(Rows.sectionTitle(this, getString(r)))

    private fun build() {
        col.removeAllViews()
        val themeNames = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark).map { getString(it) }
        val sizeNames = listOf(R.string.size_small, R.string.size_medium, R.string.size_large).map { getString(it) }
        val langTags = listOf("", "en", "bn")
        val langNames = listOf(R.string.lang_system, R.string.lang_en, R.string.lang_bn).map { getString(it) }
        val trashOptions = listOf(7, 30, 60, 90)

        section(R.string.sec_appearance)
        addGroup(
            Rows.row(this, R.drawable.ic_palette, 0xFF5856D6.toInt(), getString(R.string.theme),
                value = themeNames[prefs.theme], chevron = true) {
                choose(R.string.theme, themeNames, prefs.theme) { i ->
                    ThemeState.setMode(this, i)
                    build()
                }
            },
            Rows.row(this, R.drawable.ic_palette, Accent.swatch(this, prefs.accent), getString(R.string.accent),
                chevron = true, trailing = swatchDot(Accent.swatch(this, prefs.accent))) { pickAccent() },
            Rows.row(this, R.drawable.ic_text_size, 0xFF8E8E93.toInt(), getString(R.string.text_size),
                value = sizeNames[prefs.textSize], chevron = true) {
                choose(R.string.text_size, sizeNames, prefs.textSize) { i ->
                    prefs.textSize = i
                    build()
                }
            }
        )

        section(R.string.sec_notes)
        val viewNames = listOf(getString(R.string.view_grid_name), getString(R.string.view_list_name))
        val sorts = Sort.entries
        addGroup(
            Rows.row(this, R.drawable.ic_grid_view, 0xFF34C759.toInt(), getString(R.string.default_view),
                value = viewNames[if (prefs.grid) 0 else 1], chevron = true) {
                choose(R.string.default_view, viewNames, if (prefs.grid) 0 else 1) { i ->
                    prefs.grid = i == 0
                    build()
                }
            },
            Rows.row(this, R.drawable.ic_sort, 0xFF007AFF.toInt(), getString(R.string.sort_by),
                value = getString(prefs.sort.res), chevron = true) {
                choose(R.string.sort_by, sorts.map { getString(it.res) }, prefs.sort.ordinal) { i ->
                    prefs.sort = sorts[i]
                    build()
                }
            },
            Rows.row(this, R.drawable.ic_delete, 0xFFFF3B30.toInt(), getString(R.string.trash_days),
                value = getString(R.string.days_n, prefs.trashDays), chevron = true) {
                val names = trashOptions.map { getString(R.string.days_n, it) }
                choose(R.string.trash_days, names, trashOptions.indexOf(prefs.trashDays)) { i ->
                    prefs.trashDays = trashOptions[i]
                    build()
                }
            }
        )

        section(R.string.sec_reminders)
        addGroup(
            Rows.row(this, R.drawable.ic_sound, 0xFFFF9500.toInt(), getString(R.string.alarm_sound),
                value = alarmSoundName(), chevron = true) { pickAlarmSound() },
            Rows.row(this, R.drawable.ic_alarm, 0xFFFF3B30.toInt(), getString(R.string.exact_alarm_title),
                subtitle = getString(R.string.exact_alarm_sub), chevron = true) { openAlarmSettings() }
        )

        section(R.string.sec_library)
        addGroup(
            Rows.row(this, R.drawable.ic_archive, 0xFFFF9500.toInt(), getString(R.string.f_archive), chevron = true) {
                startActivity(Intent(this, LibraryActivity::class.java).putExtra("filter", Filter.ARCHIVE.name))
            },
            Rows.row(this, R.drawable.ic_delete, 0xFFFF3B30.toInt(), getString(R.string.f_trash), chevron = true) {
                startActivity(Intent(this, LibraryActivity::class.java).putExtra("filter", Filter.TRASH.name))
            }
        )

        section(R.string.sec_security)
        addGroup(
            Rows.row(this, R.drawable.ic_lock, 0xFF34C759.toInt(), getString(R.string.app_lock),
                subtitle = getString(R.string.app_lock_sub),
                trailing = Rows.switch(this, prefs.lock) { setLock(it) })
        )

        section(R.string.sec_data)
        addGroup(
            Rows.row(this, R.drawable.ic_backup, 0xFF007AFF.toInt(), getString(R.string.backup), chevron = true) {
                exportLauncher.launch(
                    "notepad-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json"
                )
            },
            Rows.row(this, R.drawable.ic_restore_data, 0xFF5856D6.toInt(), getString(R.string.restore_backup),
                chevron = true) { importLauncher.launch(arrayOf("*/*")) }
        )

        section(R.string.sec_language)
        val curLang = langTags.indexOf(prefs.language).coerceAtLeast(0)
        addGroup(
            Rows.row(this, R.drawable.ic_language, 0xFF007AFF.toInt(), getString(R.string.language),
                value = langNames[curLang], chevron = true) {
                choose(R.string.language, langNames, curLang) { i ->
                    prefs.language = langTags[i]
                    App.applyLanguage(langTags[i])
                    build()
                }
            }
        )

        section(R.string.sec_about)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        addGroup(
            Rows.row(this, R.drawable.ic_info, 0xFF8E8E93.toInt(), getString(R.string.about_version_fmt, version)),
            Rows.row(this, R.drawable.ic_developer, 0xFF5856D6.toInt(), getString(R.string.about_dev), value = "CODEX M41NUL"),
            Rows.row(this, R.drawable.ic_github, 0xFF181717.toInt(), "GitHub", value = "M41NUL", chevron = true) {
                open("https://github.com/M41NUL")
            },
            Rows.row(this, R.drawable.ic_telegram, 0xFF26A5E4.toInt(), "Telegram", value = "@mdmainulislaminfo",
                chevron = true) { open("https://t.me/mdmainulislaminfo") }
        )
    }

    private fun swatchDot(color: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) }
    }

    private fun pickAccent() {
        val dlg = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(32))
        }
        box.addView(TextView(this).apply {
            setText(R.string.accent)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        for (i in Accent.styles.indices) {
            val sel = i == prefs.accent
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(6), 0, dp(6), 0) }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Accent.swatch(this@SettingsActivity, i))
                setStroke(dp(if (sel) 3 else 0), themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
            }
            dot.setOnClickListener {
                Motion.haptic(it, prefs)
                ThemeState.setAccent(this, i)
                dlg.dismiss()
                recreate()
            }
            row.addView(dot)
        }
        box.addView(row)
        dlg.setContentView(box)
        Sheet.flat(dlg)
        dlg.show()
    }

    private fun setLock(on: Boolean) {
        if (!on) {
            prefs.lock = false
            toast(R.string.lock_disabled)
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            Toast.makeText(this, R.string.lock_need_secure, Toast.LENGTH_LONG).show()
            col.post { build() }
        } else {
            prefs.lock = true
            AppLock.unlocked()
            toast(R.string.lock_enabled)
        }
    }
}
