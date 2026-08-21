package com.hga.media.ui

import android.app.Activity
import android.content.Intent
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.hga.media.R
import com.hga.media.data.Prefs
import com.hga.media.data.Repo

/**
 * Small shared helpers so every screen looks and behaves the same, and so the
 * settings screens can be described as a list rather than hand-built in XML.
 */
object UiKit {

    fun goFullScreen(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
    }

    // ------------------------------------------------------------- dialogs
    fun askPin(activity: Activity, prefs: Prefs, onCorrect: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_pin, null)
        val input = view.findViewById<EditText>(R.id.pinInput)
        AlertDialog.Builder(activity, R.style.Theme_HGA_Dialog)
            .setView(view)
            .setPositiveButton("Unlock") { d, _ ->
                if (input.text.toString().trim() == prefs.ownerPin) onCorrect()
                else android.widget.Toast.makeText(
                    activity, activity.getString(R.string.owner_wrong_pin),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel) { d, _ -> d.dismiss() }
            .show()
        input.requestFocus()
    }

    fun textInput(
        activity: Activity,
        label: String,
        initial: String,
        numeric: Boolean = false,
        onOk: (String) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_text, null)
        val labelView = view.findViewById<TextView>(R.id.textDialogLabel)
        val input = view.findViewById<EditText>(R.id.textDialogInput)
        labelView.text = label
        input.setText(initial)
        input.inputType =
            if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.setSelection(input.text.length)

        AlertDialog.Builder(activity, R.style.Theme_HGA_Dialog)
            .setView(view)
            .setPositiveButton(R.string.action_save) { d, _ ->
                onOk(input.text.toString().trim()); d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel) { d, _ -> d.dismiss() }
            .show()
        input.requestFocus()
    }

    fun choose(
        activity: Activity,
        title: String,
        labels: List<String>,
        currentIndex: Int,
        onPick: (Int) -> Unit
    ) {
        AlertDialog.Builder(activity, R.style.Theme_HGA_Dialog)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), currentIndex) { d, which ->
                onPick(which); d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel) { d, _ -> d.dismiss() }
            .show()
    }

    /**
     * Tick-box list. Used for choosing which channel categories a venue shows.
     */
    fun multiChoose(
        activity: Activity,
        title: String,
        labels: List<String>,
        checked: BooleanArray,
        onDone: (BooleanArray) -> Unit
    ) {
        val working = checked.copyOf()
        AlertDialog.Builder(activity, R.style.Theme_HGA_Dialog)
            .setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), working) { _, which, isChecked ->
                if (which in working.indices) working[which] = isChecked
            }
            .setPositiveButton(R.string.action_save) { d, _ -> onDone(working); d.dismiss() }
            .setNegativeButton(R.string.action_cancel) { d, _ -> d.dismiss() }
            .show()
    }

    /**
     * Android can restart any screen on its own after a crash or when it needs
     * memory back. When that happens the app is a fresh process with no playlist
     * in it, and a screen that assumes otherwise shows an empty list. Every
     * screen calls this first: if the playlist is not there, go back through the
     * loader rather than showing the user nothing.
     */
    fun ensureLoaded(activity: Activity): Boolean {
        if (Repo.loaded && Repo.liveChannels.isNotEmpty()) return true
        val intent = Intent(activity, SplashActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        activity.startActivity(intent)
        activity.finish()
        return false
    }

    fun confirm(activity: Activity, title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(activity, R.style.Theme_HGA_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { d, _ -> onYes(); d.dismiss() }
            .setNegativeButton("No") { d, _ -> d.dismiss() }
            .show()
    }

    fun info(activity: Activity, title: String, message: String) {
        AlertDialog.Builder(activity, R.style.Theme_HGA_Dialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.action_close) { d, _ -> d.dismiss() }
            .show()
    }

    // ------------------------------------------------------------- rows
    fun section(container: LinearLayout, title: String) {
        val view = LayoutInflater.from(container.context)
            .inflate(R.layout.item_section, container, false) as TextView
        view.text = title
        container.addView(view)
    }

    fun row(
        container: LinearLayout,
        title: String,
        summary: String? = null,
        value: String? = null,
        onClick: (() -> Unit)? = null
    ): View {
        val view = LayoutInflater.from(container.context)
            .inflate(R.layout.item_setting, container, false)
        view.findViewById<TextView>(R.id.setTitle).text = title
        val summaryView = view.findViewById<TextView>(R.id.setSummary)
        if (summary.isNullOrBlank()) summaryView.visibility = View.GONE
        else { summaryView.visibility = View.VISIBLE; summaryView.text = summary }
        view.findViewById<TextView>(R.id.setValue).text = value ?: ""
        if (onClick != null) view.setOnClickListener { onClick() }
        container.addView(view)
        return view
    }

    fun setRowValue(row: View, value: String) {
        row.findViewById<TextView>(R.id.setValue).text = value
    }

    fun setRowSummary(row: View, summary: String) {
        val v = row.findViewById<TextView>(R.id.setSummary)
        v.visibility = View.VISIBLE
        v.text = summary
    }

    fun onOff(flag: Boolean) = if (flag) "On" else "Off"
}
