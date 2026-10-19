package com.glowled.app.ui

import android.app.KeyguardManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glowled.app.R
import com.glowled.app.core.DebugLog
import com.glowled.app.core.LedController
import com.glowled.app.core.NotificationStore
import com.glowled.app.view.AppInfoView

/**
 * Quick reply from the always-on screen: a small sheet with the last message and a text field.
 * The text goes to the app through its own notification "Reply" action, exactly like replying
 * from the notification shade. On a locked phone Android asks to unlock first.
 */
class ReplyActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private var entry: NotificationStore.Entry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedController.init(this)
        setShowWhenLocked(true)
        val e = intent.getStringExtra(EXTRA_KEY)?.let { NotificationStore.get(it) }
        if (e?.replyAction == null) {
            Toast.makeText(this, R.string.reply_unavailable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        entry = e
        setContentView(buildUi(e))

        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = focusInput()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            focusInput()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(e: NotificationStore.Entry): View {
        val pm = packageManager
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { finish() } // tap outside the sheet
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // don't let taps on the sheet close it
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.dialog_surface))
                cornerRadii = floatArrayOf(28f, 28f, 28f, 28f, 0f, 0f, 0f, 0f).map { it * resources.displayMetrics.density }.toFloatArray()
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply { setImageDrawable(AppInfoView.iconFor(pm, e.pkg)) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
        head.addView(TextView(this).apply {
            text = getString(R.string.reply_to, e.title ?: AppInfoView.labelFor(pm, e.pkg))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(getColor(R.color.text_primary))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sheet.addView(head)
        if (!e.hidden && e.lines.isNotEmpty()) {
            sheet.addView(TextView(this).apply {
                text = e.lines.takeLast(3).joinToString("\n")
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(getColor(R.color.text_secondary))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = getString(R.string.reply_hint)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            background = GradientDrawable().apply {
                cornerRadius = 24f * resources.displayMetrics.density
                setColor(getColor(R.color.surface_high))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES or
                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            setOnEditorActionListener { _, id, ev ->
                val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (id == EditorInfo.IME_ACTION_SEND || enter) { send(); true } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = ColorStateList.valueOf(Color.BLACK)
            contentDescription = getString(R.string.reply_send)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.accent))
            }
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(10) })
        sheet.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // Edge-to-edge: keep the sheet above the keyboard and the navigation bar.
        val base = sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
            )
            sheet.updatePadding(bottom = base + bottom)
            insets
        }
        return root
    }

    private fun focusInput() {
        if (isFinishing || !::input.isInitialized) return
        input.requestFocus()
        input.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun send() {
        val e = entry ?: return finish()
        val action = e.replyAction ?: return finish()
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val inputs = action.remoteInputs ?: return finish()
        val results = Bundle()
        inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        val ok = try {
            action.actionIntent.send(this, 0, fill)
            true
        } catch (ex: Exception) {
            DebugLog.add("Reply failed: ${ex.javaClass.simpleName}")
            false
        }
        Toast.makeText(this, if (ok) R.string.reply_sent else R.string.reply_failed, Toast.LENGTH_SHORT).show()
        if (ok) DebugLog.add("Replied from the always-on screen")
        finish()
    }

    companion object {
        const val EXTRA_KEY = "key"

        fun start(context: Context, key: String): Boolean = try {
            context.startActivity(
                Intent(context, ReplyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(EXTRA_KEY, key),
            )
            true
        } catch (ex: Exception) {
            DebugLog.add("Could not open reply: ${ex.javaClass.simpleName}")
            false
        }
    }
}
