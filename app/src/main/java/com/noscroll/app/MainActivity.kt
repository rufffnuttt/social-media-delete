package com.noscroll.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btn_app_info).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
        // The "Allow restricted settings" step only exists on Android 13+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            findViewById<View>(R.id.restricted_help).visibility = View.GONE
        }

        val list = findViewById<LinearLayout>(R.id.rule_list)
        for (rule in Rules.all) {
            val row = layoutInflater.inflate(R.layout.row_rule, list, false)
            row.findViewById<TextView>(R.id.rule_title).text = rule.title
            row.findViewById<TextView>(R.id.rule_subtitle).text =
                if (isInstalled(rule)) rule.subtitle else "${rule.subtitle} · not installed"
            val sw = row.findViewById<Switch>(R.id.rule_switch)
            sw.isChecked = prefs.isEnabled(rule)
            sw.setOnCheckedChangeListener { _, checked -> prefs.setEnabled(rule, checked) }
            row.setOnClickListener { sw.toggle() }
            list.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        val status = findViewById<TextView>(R.id.status)
        status.text = if (on) "● Blocking is ON" else "● Blocking is OFF"
        status.setTextColor(getColor(if (on) R.color.ok else R.color.warn))
        findViewById<View>(R.id.setup_card).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.stats).text =
            "Blocked today: ${prefs.todayBlocked}   ·   All time: ${prefs.totalBlocked}"
    }

    private fun isInstalled(rule: AppRule) = rule.packages.any {
        try {
            packageManager.getPackageInfo(it, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, BlockerService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { ComponentName.unflattenFromString(it) == expected }
    }
}
