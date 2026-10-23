package com.simplenotes.app

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import java.io.File
import java.util.Calendar
import java.util.TimeZone

class EditorActivity : BaseActivity() {
    private lateinit var db: NoteDb
    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var titleEt: EditText
    private lateinit var contentEt: EditText
    private lateinit var checkList: LinearLayout
    private lateinit var addItem: View
    private lateinit var chipRow: View
    private lateinit var labelChip: Chip
    private lateinit var reminderChip: Chip
    private lateinit var attachList: LinearLayout
    private lateinit var kbBar: View
    private lateinit var mainBar: View
    private lateinit var fmtBar: View
    private lateinit var btnSave: View

    private var noteId = 0L
    private var color = 0
    private var pinned = false
    private var archived = false
    private var label = ""
    private var checklist = false
    private var reminder = 0L
    private var repeatMin = 0
    private var locked = false
    private var folderId = 0L
    private var fontFamily = 0
    private var fontSize = 0
    private val files = ArrayList<Attachment>()
    private val removedFiles = ArrayList<String>()
    private var original: Note? = null
    private var discarded = false
    private var bgColor = 0
    private var textColor = 0
    private var curBg = 0
    private var curText = 0
    private var fmtOpen = false
    private var imeVisible = false
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { save() }

    private class Snap(val text: String, val spans: String)

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var committed = Snap("", "")
    private var restoring = false
    private val commitRun = Runnable { commitSnapshot() }

    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        attach(it, Attachment.AUDIO)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        attach(it, Attachment.IMAGE)
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        attach(it, Attachment.FILE)
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (!ok) Toast.makeText(this, R.string.notif_blocked, Toast.LENGTH_LONG).show()
        }

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var recDialog: android.app.Dialog? = null
    private var recTitle: TextView? = null
    private var recTimer: TextView? = null
    private var recPauseBtn: TextView? = null
    private var recPaused = false
    private var recPausedAt = 0L
    private var recPausedTotal = 0L

    private fun updateRecTimer() {
        val end = if (recPaused) recPausedAt else System.currentTimeMillis()
        val sec = ((end - recStart - recPausedTotal) / 1000).toInt().coerceAtLeast(0)
        recTimer?.text = String.format("%02d:%02d", sec / 60, sec % 60)
    }

    private val recTick = object : Runnable {
        override fun run() {
            updateRecTimer()
            handler.postDelayed(this, 500)
        }
    }
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) showRecorder() else Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        installLock()

        db = NoteDb.of(this)
        prefs = Prefs(this)
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        titleEt = findViewById(R.id.titleEt)
        contentEt = findViewById(R.id.contentEt)
        checkList = findViewById(R.id.checkList)
        addItem = findViewById(R.id.addItem)
        chipRow = findViewById(R.id.chipRow)
        labelChip = findViewById(R.id.labelChip)
        reminderChip = findViewById(R.id.reminderChip)
        attachList = findViewById(R.id.attachList)
        kbBar = findViewById(R.id.kbBar)
        mainBar = findViewById(R.id.mainBar)
        fmtBar = findViewById(R.id.fmtBar)
        btnSave = findViewById(R.id.btnSave)

        val startBg = intent.getIntExtra("bg", 0)
        val startColor = if (startBg != 0) startBg else Glass.opaque(
            this, Glass.cardColor(this, intent.getIntExtra("color", 0), prefs.glass)
        )
        findViewById<View>(android.R.id.content).transitionName = "shared_element_container"
        setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementEnterTransition = transform(300L, startColor)
        window.sharedElementReturnTransition = transform(250L, startColor)

        val fs = prefs.fontScale
        titleEt.textSize = 28f * fs

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            btnSave.visibility = if (imeVisible) View.VISIBLE else View.GONE
            updateBars()
            insets
        }

        noteId = intent.getLongExtra("id", 0L)
        val loaded = if (noteId != 0L) db.get(noteId) else null
        if (loaded != null) {
            original = loaded
            titleEt.setText(loaded.title)
            color = loaded.color.coerceIn(0, NoteColors.ids.size - 1)
            pinned = loaded.pinned
            archived = loaded.archived
            label = loaded.label
            checklist = loaded.checklist
            reminder = if (loaded.reminder > System.currentTimeMillis()) loaded.reminder else 0L
            repeatMin = if (reminder != 0L) loaded.repeatMin else 0
            locked = loaded.locked
            folderId = loaded.folder
            fontFamily = loaded.fontFamily
            fontSize = loaded.fontSize
            bgColor = loaded.bgColor
            textColor = loaded.textColor
            files.addAll(loaded.files())
            if (checklist) {
                val rows = Checklist.parse(loaded.content)
                rows.forEach { addRow(it.first, it.second) }
                if (rows.isEmpty()) addRow("", false)
            } else {
                contentEt.setText(loaded.content)
                RichText.apply(contentEt.text, loaded.spans)
            }
        } else {
            noteId = 0L
            folderId = intent.getLongExtra("folder", 0L)
            if ((intent.getStringExtra("mode") ?: "text") == "text") {
                contentEt.requestFocus()
                contentEt.post { WindowCompat.getInsetsController(window, contentEt).show(WindowInsetsCompat.Type.ime()) }
            }
        }
        if (locked && !intent.getBooleanExtra("authed", false) && !AppLock.needsUnlock(prefs)) {
            root.visibility = View.INVISIBLE
            authenticate({ root.visibility = View.VISIBLE }, { finish() })
        }
        committed = snap()

        titleEt.afterChanged { scheduleSave() }
        contentEt.afterChanged { handleContentChanged() }
        contentEt.afterChanged { Links.apply(contentEt.text, linkColor()) }
        Links.attachTap(contentEt) { Links.open(this, contentEt, it) }
        Links.apply(contentEt.text, linkColor())
        addItem.setOnClickListener { addRow("", false, checkList.childCount, true) }

        toolbar.setNavigationOnClickListener { finishAfterTransition() }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            Motion.haptic(it, prefs)
            handler.removeCallbacks(autosave)
            save()
            finishAfterTransition()
        }
        toolbar.inflateMenu(R.menu.editor)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_undo -> undo()
                R.id.action_redo -> redo()
                R.id.action_pin -> {
                    pinned = !pinned
                    Motion.haptic(toolbar, prefs)
                    Motion.pinSound(prefs, pinned)
                    updateMenu()
                    scheduleSave()
                }
                R.id.action_checklist -> setChecklist(!checklist)
                R.id.action_remind -> pickReminder()
                R.id.action_label -> editLabel()
                R.id.action_archive -> toggleArchive()
                R.id.action_lock -> toggleLock()
                R.id.action_move -> moveNote()
                R.id.action_details -> showDetails()
                R.id.action_share -> share()
                R.id.action_delete -> confirmTrash()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        labelChip.setOnClickListener { editLabel() }
        labelChip.setOnCloseIconClickListener { label = ""; updateChips(); scheduleSave() }
        reminderChip.setOnClickListener { pickReminder() }
        reminderChip.setOnCloseIconClickListener {
            reminder = 0L
            repeatMin = 0
            if (noteId != 0L) Reminders.cancel(this, noteId)
            updateChips()
            scheduleSave()
        }

        setupBars()
        showMode()
        updateChips()
        updateMenu()
        renderFiles()
        applyTheme()
        applyFont()

        if (loaded == null) when (intent.getStringExtra("mode")) {
            "checklist" -> {
                setChecklist(true)
                val first = checkList.getChildAt(0)?.findViewById<EditText>(R.id.et)
                first?.requestFocus()
                first?.post { WindowCompat.getInsetsController(window, first).show(WindowInsetsCompat.Type.ime()) }
            }
            "voice" -> root.post { startRecording() }
            "drawing" -> root.post { startDrawing() }
        }
    }

    private fun startDrawing() {
        DrawingDialog.show(this) { bmp ->
            val dir = File(filesDir, "attachments").apply { mkdirs() }
            val f = File(dir, "${System.nanoTime()}_drawing.png")
            val ok = runCatching { f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                .getOrDefault(false)
            if (!ok) {
                f.delete()
                Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show()
                return@show
            }
            val stamp = DateUtils.formatDateTime(
                this, System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
            )
            files.add(Attachment(Attachment.IMAGE, f.absolutePath, getString(R.string.draw_name, stamp) + ".png"))
            renderFiles()
            scheduleSave()
        }
    }

    private fun transform(ms: Long, containerColor: Int) = MaterialContainerTransform().apply {
        addTarget(android.R.id.content)
        duration = if (prefs.anim) ms + 90 else 0L
        interpolator = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
        setAllContainerColors(containerColor)
        isElevationShadowEnabled = false
        scrimColor = Color.TRANSPARENT
    }

    private fun themeBg(): Int = when {
        bgColor != 0 -> bgColor
        color != 0 -> Glass.opaque(this, Glass.editorTint(this, color))
        else -> Glass.surface(this)
    }

    private fun themeText(bg: Int): Int = when {
        textColor != 0 -> textColor
        bgColor != 0 || color != 0 -> Glass.contrastText(bg)
        else -> MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    }

    private fun themeRow(row: View) {
        if (curText == 0) return
        row.findViewById<EditText>(R.id.et).apply {
            setTextColor(curText)
            setHintTextColor(ColorUtils.setAlphaComponent(curText, 110))
        }
        row.findViewById<CheckBox>(R.id.cb).buttonTintList = ColorStateList.valueOf(curText)
    }

    private fun linkColor() =
        if (bgColor != 0 || textColor != 0) curText else themeColor(androidx.appcompat.R.attr.colorPrimary)

    private fun refreshLinks() {
        val c = linkColor()
        Links.apply(contentEt.text, c)
        for (i in 0 until checkList.childCount) {
            checkList.getChildAt(i).findViewById<EditText>(R.id.et)?.let { Links.apply(it.text, c) }
        }
    }

    private fun applyTheme() {
        val bg = themeBg()
        val tc = themeText(bg)
        curBg = bg
        curText = tc
        root.setBackgroundColor(bg)
        kbBar.setBackgroundColor(bg)
        titleEt.setTextColor(tc)
        contentEt.setTextColor(tc)
        val hint = ColorUtils.setAlphaComponent(tc, 110)
        titleEt.setHintTextColor(hint)
        contentEt.setHintTextColor(hint)
        for (i in 0 until checkList.childCount) themeRow(checkList.getChildAt(i))
        refreshLinks()

        toolbar.navigationIcon?.mutate()?.setTint(tc)
        toolbar.overflowIcon?.mutate()?.setTint(tc)
        for (i in 0 until toolbar.menu.size()) toolbar.menu.getItem(i).icon?.mutate()?.setTint(tc)

        for (id in intArrayOf(R.id.tbAttach, R.id.tbTheme, R.id.tbClear, R.id.tbClose)) {
            findViewById<ImageView>(id).imageTintList = ColorStateList.valueOf(tc)
        }
        findViewById<View>(R.id.tbDivider).setBackgroundColor(ColorUtils.setAlphaComponent(tc, 70))
        val soft = ColorUtils.setAlphaComponent(tc, 30)
        for (id in intArrayOf(R.id.tbAttach, R.id.tbTheme, R.id.tbFormat, R.id.tbFont)) {
            findViewById<View>(id).background?.mutate()?.setTint(soft)
        }
        for (id in intArrayOf(R.id.tbFormat, R.id.tbH1, R.id.tbH2, R.id.tbFont)) {
            findViewById<TextView>(id).setTextColor(tc)
        }

        val light = ColorUtils.calculateLuminance(bg) > 0.5
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        window.sharedElementReturnTransition = transform(250L, bg)
        updateFmtState()
    }

    private val bgChoices = longArrayOf(
        0, 0xFFFFFFFF, 0xFFFFF4C2, 0xFFD8F3DC, 0xFFD6EBFF, 0xFFFFE0EA, 0xFFE8DEFF, 0xFFFFE3CC,
        0xFF1C1C1E, 0xFF14213D, 0xFF1B3A2B, 0xFF3B1F3F
    )
    private val textChoices = longArrayOf(
        0, 0xFF000000, 0xFFFFFFFF, 0xFFD32F2F, 0xFF1565C0, 0xFF2E7D32, 0xFFEF6C00, 0xFF6A1B9A, 0xFF616161
    )

    private fun showThemeSheet() {
        val dlg = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(32))
        }
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, Color.GRAY)
        val surface = Glass.surface(this)

        fun title(r: Int) = TextView(this).apply {
            setText(r)
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(10))
        }

        fun row(choices: LongArray, current: Int, isText: Boolean, pick: (Int) -> Unit): View {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (l in choices) {
                val c = l.toInt()
                val sel = c == current
                line.addView(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (c != 0) c else if (isText) onSurface else surface)
                        setStroke(dp(if (sel) 3 else 1), if (sel) primary else outline)
                    }
                    if (c == 0) {
                        text = "A"
                        textSize = 16f
                        setTextColor(if (isText) surface else onSurface)
                    }
                    setOnClickListener {
                        Motion.haptic(it, prefs)
                        pick(c)
                    }
                })
            }
            return HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(line)
            }
        }

        fun build() {
            box.removeAllViews()
            box.addView(title(R.string.bg_color))
            box.addView(row(bgChoices, bgColor, false) {
                bgColor = it
                color = 0
                applyTheme()
                scheduleSave()
                build()
            })
            box.addView(title(R.string.text_color))
            box.addView(row(textChoices, textColor, true) {
                textColor = it
                applyTheme()
                scheduleSave()
                build()
            })
        }
        build()
        dlg.setContentView(box)
        Sheet.flat(dlg)
        dlg.show()
    }

    private fun updateMenu() {
        val m = toolbar.menu
        m.findItem(R.id.action_pin)?.let {
            it.icon?.mutate()?.alpha = if (pinned) 255 else 110
            it.title = getString(if (pinned) R.string.unpin else R.string.pin)
        }
        m.findItem(R.id.action_checklist)?.title =
            getString(if (checklist) R.string.to_text else R.string.to_checklist)
        m.findItem(R.id.action_remind)?.title =
            getString(if (reminder > System.currentTimeMillis()) R.string.remind_edit else R.string.remind)
        m.findItem(R.id.action_label)?.title =
            getString(if (label.isBlank()) R.string.label_add else R.string.label_edit)
        m.findItem(R.id.action_archive)?.title =
            getString(if (archived) R.string.unarchive else R.string.archive)
        m.findItem(R.id.action_lock)?.title =
            getString(if (locked) R.string.unlock_note else R.string.lock_note)
        val canUndo = !checklist && undoStack.isNotEmpty()
        val canRedo = !checklist && redoStack.isNotEmpty()
        m.findItem(R.id.action_undo)?.let {
            it.isEnabled = canUndo
            it.icon?.mutate()?.alpha = if (canUndo) 255 else 80
        }
        m.findItem(R.id.action_redo)?.let {
            it.isEnabled = canRedo
            it.icon?.mutate()?.alpha = if (canRedo) 255 else 80
        }
    }

    private fun showMode() {
        contentEt.visibility = if (checklist) View.GONE else View.VISIBLE
        checkList.visibility = if (checklist) View.VISIBLE else View.GONE
        addItem.visibility = if (checklist) View.VISIBLE else View.GONE
    }

    private fun updateChips() {
        labelChip.visibility = if (label.isBlank()) View.GONE else View.VISIBLE
        labelChip.text = label
        val rem = reminder > System.currentTimeMillis()
        reminderChip.visibility = if (rem) View.VISIBLE else View.GONE
        if (rem) {
            val whenText = DateUtils.formatDateTime(
                this, reminder,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
            )
            reminderChip.text = if (repeatMin > 0) "$whenText · ${Reminders.repeatLabel(this, repeatMin)}" else whenText
        }
        chipRow.visibility = if (label.isBlank() && !rem) View.GONE else View.VISIBLE
        updateMenu()
    }

    private fun snap(): Snap {
        val ed = contentEt.text
        return Snap(ed.toString(), RichText.toJson(ed, 0, ed.length))
    }

    private fun handleContentChanged() {
        if (restoring) return
        handler.removeCallbacks(commitRun)
        handler.postDelayed(commitRun, 600)
        scheduleSave()
    }

    private fun commitSnapshot() {
        val cur = snap()
        if (cur.text == committed.text && cur.spans == committed.spans) return
        undoStack.addLast(committed)
        if (undoStack.size > 100) undoStack.removeFirst()
        committed = cur
        redoStack.clear()
        updateMenu()
    }

    private fun undo() {
        handler.removeCallbacks(commitRun)
        commitSnapshot()
        if (undoStack.isEmpty()) return
        redoStack.addLast(committed)
        committed = undoStack.removeLast()
        restoreText(committed)
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(committed)
        committed = redoStack.removeLast()
        restoreText(committed)
    }

    private fun restoreText(sn: Snap) {
        restoring = true
        contentEt.setText(sn.text)
        RichText.apply(contentEt.text, sn.spans)
        contentEt.setSelection(sn.text.length)
        restoring = false
        scheduleSave()
        updateMenu()
    }

    private fun updateBars() {
        kbBar.visibility = if (imeVisible) View.VISIBLE else View.GONE
        mainBar.visibility = if (fmtOpen) View.GONE else View.VISIBLE
        fmtBar.visibility = if (fmtOpen) View.VISIBLE else View.GONE
    }

    private fun setupBars() {
        findViewById<TextView>(R.id.tbItalic).setTypeface(null, android.graphics.Typeface.BOLD_ITALIC)
        for (id in intArrayOf(R.id.tbUnderline, R.id.tbFormat)) {
            findViewById<TextView>(id).apply { paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG }
        }
        findViewById<View>(R.id.tbAttach).setOnClickListener { showAttachSheet() }
        findViewById<View>(R.id.tbTheme).setOnClickListener { showThemeSheet() }
        findViewById<View>(R.id.tbFormat).setOnClickListener {
            fmtOpen = true
            updateBars()
            updateFmtState()
        }
        findViewById<View>(R.id.tbClose).setOnClickListener {
            fmtOpen = false
            updateBars()
        }
        findViewById<View>(R.id.tbH1).setOnClickListener { toggleSpan("h1") }
        findViewById<View>(R.id.tbH2).setOnClickListener { toggleSpan("h2") }
        findViewById<View>(R.id.tbBold).setOnClickListener { toggleSpan("b") }
        findViewById<View>(R.id.tbItalic).setOnClickListener { toggleSpan("i") }
        findViewById<View>(R.id.tbUnderline).setOnClickListener { toggleSpan("u") }
        findViewById<View>(R.id.tbFont).setOnClickListener { showFontSheet() }
        findViewById<View>(R.id.tbClear).setOnClickListener { clearSpans() }
    }

    private fun showAttachSheet() {
        Sheet.show(
            this, getString(R.string.attach_title), listOf(
                SheetRow(R.drawable.ic_mic, getString(R.string.attach_record)) { startRecording() },
                SheetRow(R.drawable.ic_audio, getString(R.string.attach_audio)) { pickAudio.launch(arrayOf("audio/*")) },
                SheetRow(R.drawable.ic_image, getString(R.string.attach_image)) { pickImage.launch(arrayOf("image/*")) },
                SheetRow(R.drawable.ic_attach_file, getString(R.string.attach_file)) { pickFile.launch(arrayOf("*/*")) }
            )
        )
    }

    private fun fmtRange(toast: Boolean): Pair<Int, Int>? {
        if (checklist) {
            if (toast) Toast.makeText(this, R.string.fmt_no_checklist, Toast.LENGTH_SHORT).show()
            return null
        }
        val t = contentEt.text.toString()
        var s = contentEt.selectionStart
        var e = contentEt.selectionEnd
        if (s < 0 || e < 0) return null
        if (s > e) { val x = s; s = e; e = x }
        if (s == e) {
            val cur = s
            s = t.lastIndexOf('\n', cur - 1) + 1
            e = t.indexOf('\n', cur).let { if (it < 0) t.length else it }
        }
        if (e <= s) {
            if (toast) Toast.makeText(this, R.string.fmt_empty_line, Toast.LENGTH_SHORT).show()
            return null
        }
        return s to e
    }

    private fun toggleSpan(kind: String) {
        val (s, e) = fmtRange(true) ?: return
        Motion.haptic(toolbar, prefs)
        RichText.toggle(contentEt.text, kind, s, e)
        afterFormat()
    }

    private fun clearSpans() {
        val (s, e) = fmtRange(true) ?: return
        Motion.haptic(toolbar, prefs)
        RichText.clear(contentEt.text, s, e)
        afterFormat()
    }

    private fun afterFormat() {
        handler.removeCallbacks(commitRun)
        commitSnapshot()
        scheduleSave()
        updateFmtState()
    }

    private fun updateFmtState() {
        if (curText == 0) return
        val on = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val r = fmtRange(false)
        fun mark(id: Int, kind: String) {
            val active = r != null && RichText.has(contentEt.text, kind, r.first, r.second)
            findViewById<TextView>(id).setTextColor(if (active) on else curText)
        }
        mark(R.id.tbBold, "b")
        mark(R.id.tbItalic, "i")
        mark(R.id.tbUnderline, "u")
        mark(R.id.tbH1, "h1")
        mark(R.id.tbH2, "h2")
    }

    private fun changeSize(delta: Int) {
        val next = (fontSize + delta).coerceIn(FontFmt.MIN, FontFmt.MAX)
        if (next == fontSize) return
        fontSize = next
        applyFont()
        scheduleSave()
    }

    private fun applyFont() {
        val base = 17f * prefs.fontScale
        FontFmt.apply(contentEt, fontFamily, fontSize, base)
        for (i in 0 until checkList.childCount) {
            val row = checkList.getChildAt(i)
            val et = row.findViewById<EditText>(R.id.et)
            FontFmt.apply(et, fontFamily, fontSize, base)
            strike(et, row.findViewById<CheckBox>(R.id.cb).isChecked)
        }
    }

    private fun showFontSheet() {
        val dlg = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(32))
        }
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, Color.GRAY)

        fun title(r: Int) = TextView(this).apply {
            setText(r)
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(10))
        }

        box.addView(title(R.string.font_family))
        val famRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(famRow)
        })
        fun buildFamilies() {
            famRow.removeAllViews()
            FontFmt.names.forEachIndexed { i, name ->
                val sel = i == fontFamily
                famRow.addView(TextView(this).apply {
                    text = name
                    textSize = 15f
                    typeface = FontFmt.typeface(i)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setTextColor(if (sel) Color.WHITE else onSurface)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat()
                        setColor(if (sel) primary else Color.TRANSPARENT)
                        setStroke(dp(1), if (sel) primary else outline)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        Motion.haptic(it, prefs)
                        fontFamily = i
                        applyFont()
                        scheduleSave()
                        buildFamilies()
                    }
                })
            }
        }
        buildFamilies()

        box.addView(title(R.string.font_size))
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun refresh() { label.text = "${Math.round(FontFmt.scale(fontSize) * 100)}%" }
        fun stepBtn(t: String, delta: Int) = TextView(this).apply {
            text = t
            textSize = 20f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(onSurface)
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), outline)
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(44))
            setOnClickListener {
                Motion.haptic(it, prefs)
                changeSize(delta)
                refresh()
            }
        }
        sizeRow.addView(stepBtn("A\u2212", -1))
        sizeRow.addView(label)
        sizeRow.addView(stepBtn("A+", 1))
        refresh()
        box.addView(sizeRow)

        dlg.setContentView(box)
        Sheet.flat(dlg)
        dlg.show()
    }

    private fun addRow(text: String, checked: Boolean, index: Int = checkList.childCount, focus: Boolean = false) {
        val row = layoutInflater.inflate(R.layout.item_check, checkList, false)
        val cb = row.findViewById<CheckBox>(R.id.cb)
        val et = row.findViewById<EditText>(R.id.et)
        et.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        FontFmt.apply(et, fontFamily, fontSize, 17f * prefs.fontScale)
        et.setText(text)
        cb.isChecked = checked
        strike(et, checked)
        cb.setOnCheckedChangeListener { _, c ->
            strike(et, c)
            scheduleSave()
        }
        et.afterChanged { scheduleSave() }
        et.afterChanged { Links.apply(et.text, linkColor()) }
        Links.attachTap(et) { Links.open(this, et, it) }
        Links.apply(et.text, linkColor())
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                addRow("", false, checkList.indexOfChild(row) + 1, true)
                true
            } else false
        }
        et.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && et.text.isEmpty()) {
                removeRow(row, true)
                true
            } else false
        }
        row.findViewById<View>(R.id.remove).setOnClickListener { removeRow(row, false) }
        themeRow(row)
        checkList.addView(row, index)
        if (focus) {
            et.requestFocus()
            WindowCompat.getInsetsController(window, et).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun removeRow(row: View, focusPrevious: Boolean) {
        val idx = checkList.indexOfChild(row)
        checkList.removeView(row)
        if (focusPrevious && idx > 0) {
            val prev = checkList.getChildAt(idx - 1)?.findViewById<EditText>(R.id.et)
            prev?.requestFocus()
            prev?.setSelection(prev.text.length)
        }
        scheduleSave()
    }

    private fun strike(et: EditText, done: Boolean) {
        et.paintFlags = if (done) et.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else et.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        et.alpha = if (done) 0.5f else 1f
    }

    private fun rows(): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (i in 0 until checkList.childCount) {
            val r = checkList.getChildAt(i)
            val t = r.findViewById<EditText>(R.id.et).text.toString().replace('\n', ' ').trim()
            if (t.isNotEmpty()) out.add(t to r.findViewById<CheckBox>(R.id.cb).isChecked)
        }
        return out
    }

    private fun setChecklist(on: Boolean) {
        if (on == checklist) return
        handler.removeCallbacks(commitRun)
        if (on) {
            val parsed = Checklist.parse(contentEt.text.toString())
            checkList.removeAllViews()
            parsed.forEach { addRow(it.first, it.second) }
            if (parsed.isEmpty()) addRow("", false)
            checklist = true
        } else {
            val plain = Checklist.toGlyphs(rows())
            restoring = true
            contentEt.setText(plain)
            restoring = false
            committed = snap()
            checklist = false
        }
        undoStack.clear()
        redoStack.clear()
        showMode()
        updateMenu()
        scheduleSave()
    }

    private fun currentContent(): String =
        if (checklist) Checklist.encode(rows()) else contentEt.text.toString().trim()

    private fun editLabel() {
        labelDialog(db, label) {
            label = it
            updateChips()
            scheduleSave()
        }
    }

    private fun pickReminder() {
        if (titleEt.text.isBlank() && currentContent().isEmpty()) {
            Toast.makeText(this, R.string.write_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pick_date)
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .setCalendarConstraints(
                CalendarConstraints.Builder().setValidator(DateValidatorPointForward.now()).build()
            ).build()
        datePicker.addOnPositiveButtonClickListener { utc ->
            val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
            val soon = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(if (DateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(soon.get(Calendar.HOUR_OF_DAY))
                .setMinute(0)
                .setTitleText(R.string.pick_time)
                .build()
            timePicker.addOnPositiveButtonClickListener {
                val cal = Calendar.getInstance()
                cal.set(
                    picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH),
                    timePicker.hour, timePicker.minute, 0
                )
                cal.set(Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, R.string.reminder_future, Toast.LENGTH_SHORT).show()
                } else {
                    val at = cal.timeInMillis
                    askRepeat { rep ->
                        reminder = at
                        repeatMin = rep
                        updateChips()
                        save()
                        checkAlarmAccess()
                    }
                }
            }
            timePicker.show(supportFragmentManager, "time")
        }
        datePicker.show(supportFragmentManager, "date")
    }

    private fun askRepeat(onPick: (Int) -> Unit) {
        val names = arrayOf(
            getString(R.string.repeat_none), getString(R.string.repeat_daily),
            getString(R.string.repeat_weekly), getString(R.string.repeat_custom)
        )
        val current = when (repeatMin) {
            0 -> 0
            Reminders.DAILY -> 1
            Reminders.WEEKLY -> 2
            else -> 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.repeat_title)
            .setSingleChoiceItems(names, current) { dlg, which ->
                dlg.dismiss()
                when (which) {
                    0 -> onPick(0)
                    1 -> onPick(Reminders.DAILY)
                    2 -> onPick(Reminders.WEEKLY)
                    else -> askCustomDays(onPick)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askCustomDays(onPick: (Int) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.repeat_days_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            if (repeatMin > 0 && repeatMin % Reminders.DAILY == 0) setText((repeatMin / Reminders.DAILY).toString())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.repeat_custom)
            .setView(inputFrame(input))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val days = input.text.toString().toIntOrNull()?.coerceIn(1, 365) ?: 1
                onPick(days * Reminders.DAILY)
            }
            .show()
    }

    private fun checkAlarmAccess() {
        if (!Reminders.notificationsAllowed(this) && Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.notif_blocked, Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= 31 && !Reminders.canExact(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.exact_alarm_title)
                .setMessage(R.string.exact_alarm_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.allow) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(android.net.Uri.parse("package:$packageName"))
                        )
                    }
                }
                .show()
        }
    }

    private fun share() {
        val body = if (checklist) Checklist.toGlyphs(rows()) else contentEt.text.toString()
        val text = listOf(titleEt.text.toString(), body)
            .filter { it.isNotBlank() }.joinToString("\n\n")
        if (text.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun toggleArchive() {
        archived = !archived
        save()
        if (noteId == 0L) {
            archived = !archived
            Toast.makeText(this, R.string.write_first, Toast.LENGTH_SHORT).show()
            updateMenu()
            return
        }
        Toast.makeText(
            this, if (archived) R.string.archived_toast else R.string.unarchived_toast, Toast.LENGTH_SHORT
        ).show()
        discarded = true
        finishAfterTransition()
    }

    private fun confirmTrash() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_trash)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_confirm_btn) { _, _ -> moveToTrash() }
            .show()
    }

    private fun moveToTrash() {
        handler.removeCallbacks(autosave)
        if (noteId != 0L) {
            db.setTrashed(noteId, System.currentTimeMillis())
            Reminders.cancel(this, noteId)
        }
        discarded = true
        Toast.makeText(this, R.string.trashed_toast, Toast.LENGTH_SHORT).show()
        finishAfterTransition()
    }

    private fun attach(uris: List<Uri>, type: Int) {
        if (uris.isEmpty()) return
        db.bgFor({ uris.mapNotNull { Attachment.copyFrom(this, it, type) } }) { added ->
            if (added.size < uris.size) Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show()
            if (added.isEmpty()) return@bgFor
            files.addAll(added)
            renderFiles()
            scheduleSave()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) showRecorder() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Suppress("DEPRECATION")
    private fun showRecorder() {
        if (recorder != null) return
        val dir = File(filesDir, "attachments").apply { mkdirs() }
        val f = File(dir, "${System.nanoTime()}_recording.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_SHORT).show()
            return
        }
        recorder = r
        recFile = f
        recStart = System.currentTimeMillis()
        recPaused = false
        recPausedTotal = 0L

        val primary = themeColor(androidx.appcompat.R.attr.colorPrimary)
        val timer = TextView(this).apply {
            text = "00:00"
            textSize = 40f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(4))
        }
        recTimer = timer
        val pauseBtn = TextView(this).apply {
            setText(R.string.rec_pause)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(primary)
            setPadding(dp(28), 0, dp(28), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(ColorUtils.setAlphaComponent(primary, 36))
            }
            Motion.press(this)
            setOnClickListener { toggleRecPause() }
        }
        recPauseBtn = pauseBtn
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(timer)
            addView(pauseBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                bottomMargin = dp(8)
            })
        }
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
        val title = TextView(this).apply {
            setText(R.string.recording_title)
            textSize = 22f
            setTextColor(onSurface)
        }
        recTitle = title
        fun action(textRes: Int, onClick: () -> Unit) = TextView(this).apply {
            setText(textRes)
            textSize = 15f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(primary)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            Motion.press(this)
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(action(R.string.cancel) { stopRecording(false) })
            addView(action(R.string.recording_stop) { stopRecording(true) })
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
            addView(title)
            box.setPadding(0, 0, dp(10), 0)
            addView(box)
            addView(buttons, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(card)
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setWindowAnimations(R.style.Animation_App_Dialog)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.2f)
            val w = minOf(dp(320), resources.displayMetrics.widthPixels - dp(48))
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
        recDialog = dlg
        handler.post(recTick)
    }

    private fun toggleRecPause() {
        val r = recorder ?: return
        val now = System.currentTimeMillis()
        try {
            if (recPaused) {
                r.resume()
                recPausedTotal += now - recPausedAt
                recPaused = false
                handler.post(recTick)
            } else {
                r.pause()
                recPausedAt = now
                recPaused = true
                handler.removeCallbacks(recTick)
            }
        } catch (e: IllegalStateException) {
            return
        }
        updateRecTimer()
        recPauseBtn?.setText(if (recPaused) R.string.rec_resume else R.string.rec_pause)
        recTitle?.setText(if (recPaused) R.string.rec_paused_title else R.string.recording_title)
        recTimer?.alpha = if (recPaused) 0.5f else 1f
    }

    private fun stopRecording(save: Boolean) {
        val r = recorder ?: return
        recorder = null
        handler.removeCallbacks(recTick)
        val f = recFile
        recFile = null
        var ok = save
        try { r.stop() } catch (e: RuntimeException) { ok = false }
        runCatching { r.release() }
        recDialog?.let { if (it.isShowing) it.dismiss() }
        recDialog = null
        recTitle = null
        recTimer = null
        recPauseBtn = null
        recPaused = false
        if (f == null) return
        if (!ok || !f.exists() || f.length() == 0L) {
            f.delete()
            if (save) Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = DateUtils.formatDateTime(
            this, System.currentTimeMillis(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
        )
        files.add(Attachment(Attachment.AUDIO, f.absolutePath, getString(R.string.rec_name, stamp) + ".m4a"))
        renderFiles()
        scheduleSave()
    }

    private fun renderFiles() {
        attachList.removeAllViews()
        for (a in files) {
            val row = layoutInflater.inflate(R.layout.item_attach, attachList, false)
            val thumb = row.findViewById<ImageView>(R.id.thumb)
            row.findViewById<TextView>(R.id.name).text = a.name
            row.findViewById<TextView>(R.id.size).text = Formatter.formatShortFileSize(this, a.file().length())
            thumb.setImageResource(
                when (a.type) {
                    Attachment.AUDIO -> R.drawable.ic_audio
                    Attachment.IMAGE -> R.drawable.ic_image
                    else -> R.drawable.ic_attach_file
                }
            )
            if (a.type == Attachment.IMAGE) {
                db.bgFor({ a.thumb(dp(104)) }) { bmp ->
                    if (bmp != null) {
                        thumb.setPadding(0, 0, 0, 0)
                        thumb.setImageBitmap(bmp)
                    }
                }
            }
            row.setOnClickListener { openFile(a) }
            row.findViewById<View>(R.id.remove).setOnClickListener {
                files.remove(a)
                removedFiles.add(a.path)
                renderFiles()
                scheduleSave()
            }
            attachList.addView(row)
        }
    }

    private fun openFile(a: Attachment) {
        if (!a.file().exists()) {
            Toast.makeText(this, R.string.attach_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(a.uri(this), a.mime())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleLock() {
        if (!locked && getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            Toast.makeText(this, R.string.lock_need_secure, Toast.LENGTH_LONG).show()
            return
        }
        locked = !locked
        updateMenu()
        scheduleSave()
        Toast.makeText(
            this, if (locked) R.string.note_locked_toast else R.string.note_unlocked_toast, Toast.LENGTH_SHORT
        ).show()
    }

    private fun moveNote() {
        Folders.pick(this, db, folderId) { id ->
            folderId = id
            scheduleSave()
            val name = db.folderName(id) ?: getString(R.string.folder_none)
            Toast.makeText(this, getString(R.string.moved_to, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetails() {
        val body = if (checklist) rows().joinToString("\n") { it.first } else contentEt.text.toString()
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
            DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
        val o = original
        val dash = getString(R.string.none)
        fun date(ms: Long?) = if (ms == null || ms == 0L) dash else DateUtils.formatDateTime(this, ms, flags)
        val lines = listOf(
            R.string.d_created to date(o?.created),
            R.string.d_edited to date(o?.updated),
            R.string.d_words to (countWords(titleEt.text) + countWords(body)).toString(),
            R.string.d_chars to body.count { !it.isWhitespace() }.toString(),
            R.string.d_folder to (db.folderName(folderId) ?: getString(R.string.folder_none)),
            R.string.d_label to label.ifBlank { dash },
            R.string.d_attach to files.size.toString(),
            R.string.d_lock to getString(if (locked) R.string.on else R.string.off)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.note_details)
            .setMessage(lines.joinToString("\n") { "${getString(it.first)}:  ${it.second}" })
            .setPositiveButton(R.string.ok_close, null)
            .show()
    }

    private fun scheduleSave() {
        handler.removeCallbacks(autosave)
        handler.postDelayed(autosave, 1200)
    }

    private fun save() {
        if (discarded) return
        val t = titleEt.text.toString().trim()
        val c = currentContent()
        val spansJson = if (checklist) "" else {
            val raw = contentEt.text
            val lead = raw.length - raw.toString().trimStart().length
            RichText.toJson(raw, lead, c.length)
        }
        if (t.isEmpty() && c.isEmpty() && files.isEmpty()) {
            if (noteId != 0L) {
                Reminders.cancel(this, noteId)
                db.deleteForever(noteId)
                noteId = 0L
                original = null
            }
            dropRemoved()
            return
        }
        val o = original
        val n = Note(
            id = noteId, title = t, content = c, color = color, pinned = pinned,
            updated = System.currentTimeMillis(), archived = archived, trashedAt = 0L,
            label = label.trim(), checklist = checklist, reminder = reminder,
            position = o?.position ?: 0L, locked = locked, folder = folderId,
            attachments = Attachment.encode(files), created = o?.created ?: 0L,
            fontSize = fontSize, fontFamily = fontFamily, bgColor = bgColor, textColor = textColor,
            spans = spansJson, repeatMin = if (reminder > 0L) repeatMin else 0
        )
        if (o != null && n.sameAs(o)) return
        noteId = db.save(n)
        original = db.get(noteId)
        dropRemoved()
        if (reminder > System.currentTimeMillis()) Reminders.schedule(this, noteId, reminder)
        else Reminders.cancel(this, noteId)
    }

    private fun dropRemoved() {
        removedFiles.forEach { java.io.File(it).delete() }
        removedFiles.clear()
    }

    private fun countWords(s: CharSequence): Int {
        var n = 0
        var inWord = false
        for (ch in s) {
            if (ch.isWhitespace()) inWord = false
            else if (!inWord) { inWord = true; n++ }
        }
        return n
    }

    override fun onPause() {
        super.onPause()
        if (recorder != null) stopRecording(true)
        handler.removeCallbacks(autosave)
        save()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
