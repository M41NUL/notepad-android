package com.simplenotes.app

import android.app.KeyguardManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.color.MaterialColors

object AppLock {
    @Volatile private var lastActive = 0L
    @Volatile private var everUnlocked = false

    fun unlocked() {
        everUnlocked = true
        lastActive = System.currentTimeMillis()
    }

    fun touch() {
        lastActive = System.currentTimeMillis()
    }

    fun needsUnlock(prefs: Prefs): Boolean =
        prefs.lock && (!everUnlocked || System.currentTimeMillis() - lastActive > 30_000L)
}

open class BaseActivity : AppCompatActivity() {
    private var cover: View? = null
    private var authing = false
    private var noteAuthing = false

    private val themeStamp = ThemeState.stamp

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            authing = false
            if (r.resultCode == RESULT_OK) {
                AppLock.unlocked()
                cover?.visibility = View.GONE
            } else {
                finishAffinity()
            }
        }

    private var pendingAuth: (() -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null

    private val noteAuthLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            noteAuthing = false
            val ok = pendingAuth
            val denied = pendingDenied
            pendingAuth = null
            pendingDenied = null
            if (r.resultCode == RESULT_OK) {
                AppLock.unlocked()
                ok?.invoke()
            } else denied?.invoke()
        }

    fun authenticate(onOk: () -> Unit, onDenied: () -> Unit = {}) {
        val km = getSystemService(KeyguardManager::class.java)
        val i = km?.takeIf { it.isDeviceSecure }?.createConfirmDeviceCredentialIntent(
            getString(R.string.app_name), getString(R.string.unlock_note_msg)
        )
        if (i == null) {
            onOk()
            return
        }
        pendingAuth = onOk
        pendingDenied = onDenied
        noteAuthing = true
        AppLock.touch()
        noteAuthLauncher.launch(i)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Accent.styles[Prefs(this).accent], true)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val lightUi = MaterialColors.isColorLight(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightUi
            isAppearanceLightNavigationBars = lightUi
        }
    }

    protected fun installLock() {
        val c = View(this)
        c.setBackgroundColor(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.WHITE)
        )
        c.isClickable = true
        c.visibility = View.GONE
        addContentView(
            c, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        cover = c
    }

    override fun onStart() {
        super.onStart()
        if (!authing && !noteAuthing && AppLock.needsUnlock(Prefs(this))) {
            val km = getSystemService(KeyguardManager::class.java)
            val i = km?.createConfirmDeviceCredentialIntent(
                getString(R.string.app_name), getString(R.string.unlock_msg)
            )
            if (i == null) {
                AppLock.unlocked()
            } else {
                cover?.visibility = View.VISIBLE
                authing = true
                authLauncher.launch(i)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (themeStamp != ThemeState.stamp) recreate()
    }

    override fun onPause() {
        super.onPause()
        AppLock.touch()
    }

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

fun EditText.afterChanged(action: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = action()
    })
}
