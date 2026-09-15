package com.smini.galaxyattendance

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var timePicker: TimePicker
    private lateinit var enabledSwitch: Switch
    private lateinit var coordinateSwitch: Switch
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        timePicker = findViewById(R.id.timePicker)
        enabledSwitch = findViewById(R.id.enabledSwitch)
        coordinateSwitch = findViewById(R.id.coordinateSwitch)
        xInput = findViewById(R.id.xInput)
        yInput = findViewById(R.id.yInput)

        val p = AppPrefs.prefs(this)
        timePicker.hour = p.getInt(AppPrefs.KEY_HOUR, 9)
        timePicker.minute = p.getInt(AppPrefs.KEY_MINUTE, 0)
        enabledSwitch.isChecked = p.getBoolean(AppPrefs.KEY_ENABLED, false)
        coordinateSwitch.isChecked = p.getBoolean(AppPrefs.KEY_COORDINATE, false)
        val x = p.getInt(AppPrefs.KEY_X, -1); val y = p.getInt(AppPrefs.KEY_Y, -1)
        if (x >= 0) xInput.setText(x.toString()); if (y >= 0) yInput.setText(y.toString())

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.exactAlarmButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testButton).setOnClickListener {
            savePrefsOnly()
            AttendanceRunnerService.start(this, true)
            Toast.makeText(this, "테스트 실행을 요청했습니다.", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.openStoreButton).setOnClickListener {
            packageManager.getLaunchIntentForPackage(AttendanceRunnerService.STORE_PACKAGE)?.let {
                startActivity(it)
            } ?: Toast.makeText(this, "Galaxy Store를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun savePrefsOnly() {
        val x = xInput.text.toString().toIntOrNull() ?: -1
        val y = yInput.text.toString().toIntOrNull() ?: -1
        AppPrefs.prefs(this).edit()
            .putBoolean(AppPrefs.KEY_ENABLED, enabledSwitch.isChecked)
            .putInt(AppPrefs.KEY_HOUR, timePicker.hour)
            .putInt(AppPrefs.KEY_MINUTE, timePicker.minute)
            .putBoolean(AppPrefs.KEY_COORDINATE, coordinateSwitch.isChecked)
            .putInt(AppPrefs.KEY_X, x)
            .putInt(AppPrefs.KEY_Y, y)
            .apply()
    }

    private fun save() {
        savePrefsOnly()
        if (enabledSwitch.isChecked) AlarmScheduler.scheduleNext(this) else AlarmScheduler.cancel(this)
        Toast.makeText(this, "설정을 저장했습니다.", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun refresh() {
        val access = AttendanceAccessibilityService.isEnabled(this)
        val alarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true
        statusText.text = "접근성: ${if (access) "ON" else "OFF"}   ·   정확 알람: ${if (alarm) "ON" else "OFF"}"
        logText.text = AppLog.read(this)
    }
}
