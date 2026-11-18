package com.toneshift.toneshift

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hook 2: Floating pill, no highlighting required.
 * When an editable field gains focus, a small draggable pill appears at
 * the right edge of the screen. Tap it, pick a tone, and the whole field
 * is rewritten in place with ACTION_SET_TEXT. Undo restores the last
 * original text for the same field.
 *
 * Privacy note: this service only reads the focused field when you tap
 * a rewrite chip. It does not log keystrokes and does not run in the
 * background on non-editable screens.
 */
class ToneShiftAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var expanded = false
    private var busy = false

    private var lastOriginalText: String? = null
    private var lastFieldKey: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val focused = findFocusedEditableNode()
                if (focused != null) {
                    showPill()
                    focused.recycle()
                } else {
                    hidePill()
                }
            }
        }
    }

    override fun onInterrupt() {
        hidePill()
    }

    override fun onDestroy() {
        hidePill()
        scope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hidePill()
        return super.onUnbind(intent)
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val node = if (focused != null && focused.isEditable) {
            focused
        } else {
            findEditableRecursive(root)
        }
        if (node !== root && node !== focused) {
            // Caller recycles the returned node, root handling is best-effort
        }
        if (node !== focused) root.recycle()
        return node
    }

    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableRecursive(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun fieldKey(node: AccessibilityNodeInfo): String {
        return (node.packageName?.toString().orEmpty()) + "|" +
            (node.viewIdResourceName ?: node.className?.toString().orEmpty())
    }

    private fun showPill() {
        if (pillView != null) return
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.floating_pill, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        params.x = 8
        params.y = 0
        pillParams = params

        val pillButton = view.findViewById<TextView>(R.id.pillButton)
        pillButton.setOnClickListener {
            expanded = !expanded
            view.findViewById<HorizontalScrollView>(R.id.chipScroll).visibility =
                if (expanded) View.VISIBLE else View.GONE
            if (expanded) buildChips(view)
        }
        attachDrag(view, pillButton, params)

        try {
            windowManager?.addView(view, params)
            pillView = view
        } catch (e: Exception) {
            pillView = null
        }
    }

    private fun hidePill() {
        val view = pillView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // Already removed
        }
        pillView = null
        expanded = false
    }

    private fun attachDrag(view: View, handle: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = startX - dx
                        params.y = startY + dy
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            // Ignore layout races
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> moved
                else -> false
            }
        }
    }

    private fun buildChips(rootView: View) {
        val row = rootView.findViewById<LinearLayout>(R.id.pillChipRow)
        row.removeAllViews()
        val presets = SecureStore.getPresets(this)
        for (preset in presets) {
            row.addView(makeChip(preset.label) { onPresetTapped(preset) })
        }
        row.addView(makeChip("Undo") { onUndoTapped() })
    }

    private fun makeChip(label: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 13f
        tv.gravity = Gravity.CENTER
        tv.setBackgroundResource(R.drawable.chip_bg)
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(4, 0, 4, 0)
        tv.layoutParams = params
        tv.setPadding(24, 12, 24, 12)
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun onPresetTapped(preset: SecureStore.Preset) {
        if (busy) return
        if (SecureStore.getApiKey(this) == null) {
            Toast.makeText(this, "Open ToneShift and add your Gemini API key first.", Toast.LENGTH_LONG).show()
            return
        }
        val node = findFocusedEditableNode() ?: run {
            Toast.makeText(this, "No text field is focused.", Toast.LENGTH_SHORT).show()
            return
        }
        val original = node.text?.toString().orEmpty()
        if (original.isBlank()) {
            Toast.makeText(this, "Field is empty, type something first.", Toast.LENGTH_SHORT).show()
            node.recycle()
            return
        }
        val key = fieldKey(node)
        busy = true
        setBusyUi(true)
        scope.launch {
            try {
                val rewritten = GeminiClient.rewrite(this@ToneShiftAccessibilityService, original, preset)
                // Re-fetch the node, the old one may be stale after the network call
                val fresh = findFocusedEditableNode()
                if (fresh != null) {
                    lastOriginalText = original
                    lastFieldKey = key
                    setNodeText(fresh, rewritten)
                    fresh.recycle()
                } else {
                    Toast.makeText(this@ToneShiftAccessibilityService, "Text field changed, tap again.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: GeminiClient.GeminiException) {
                Toast.makeText(this@ToneShiftAccessibilityService, e.message, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                setBusyUi(false)
            }
        }
        node.recycle()
    }

    private fun onUndoTapped() {
        val original = lastOriginalText ?: run {
            Toast.makeText(this, "Nothing to undo yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val node = findFocusedEditableNode() ?: return
        if (fieldKey(node) == lastFieldKey) {
            setNodeText(node, original)
            lastOriginalText = null
            lastFieldKey = null
        } else {
            Toast.makeText(this, "Undo is only for the last field you rewrote.", Toast.LENGTH_SHORT).show()
        }
        node.recycle()
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun setBusyUi(isBusy: Boolean) {
        val view = pillView ?: return
        view.findViewById<ProgressBar>(R.id.pillProgress).visibility =
            if (isBusy) View.VISIBLE else View.GONE
    }
}
