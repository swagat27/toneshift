package com.toneshift.toneshift

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hook 1: ACTION_PROCESS_TEXT.
 * Android shows "ToneShift" in the text selection popup in every app.
 * We show a small sheet with tone chips, call Gemini, and hand the result
 * back with EXTRA_PROCESS_TEXT so Android replaces the highlighted text
 * in place. No keyboard change, Gboard stays exactly as it is.
 *
 * Note on Undo: this flow cannot offer Undo, because once we return the
 * result, Android owns the replacement. The source app's own Undo
 * (for example shake or Ctrl+Z in some apps) still works.
 */
class ProcessTextActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var originalText: String = ""
    private var isReadOnly: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_text)

        originalText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

        if (originalText.isBlank()) {
            Toast.makeText(this, "No text selected.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.previewText).text = originalText

        val chipRow = findViewById<LinearLayout>(R.id.chipRow)
        val presets = SecureStore.getPresets(this)
        for (preset in presets) {
            chipRow.addView(makeChip(preset.label) { onPresetTapped(preset) })
        }
        chipRow.addView(makeChip("Cancel") { finish() })

        // Tap outside the sheet to dismiss
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }
    }

    private fun makeChip(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 14f
        tv.gravity = Gravity.CENTER
        tv.setBackgroundResource(R.drawable.chip_bg)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 16, 0)
        tv.layoutParams = params
        tv.setPadding(28, 16, 28, 16)
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun onPresetTapped(preset: SecureStore.Preset) {
        if (SecureStore.getApiKey(this) == null) {
            Toast.makeText(
                this,
                "Open ToneShift and add your Gemini API key first.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        val progress = findViewById<ProgressBar>(R.id.progressBar)
        val status = findViewById<TextView>(R.id.statusText)
        progress.visibility = View.VISIBLE
        status.text = "Rewriting as " + preset.label + "..."

        scope.launch {
            try {
                val rewritten = GeminiClient.rewrite(this@ProcessTextActivity, originalText, preset)
                if (isReadOnly) {
                    // Read-only fields cannot be replaced, copy-style fallback:
                    // return the text anyway is ignored by Android, so show it.
                    status.text = rewritten
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this@ProcessTextActivity,
                        "This field is read-only, so text cannot be replaced here.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val resultIntent = Intent()
                    resultIntent.putExtra(Intent.EXTRA_PROCESS_TEXT, rewritten)
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            } catch (e: GeminiClient.GeminiException) {
                progress.visibility = View.GONE
                status.text = ""
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ProcessTextActivity, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
