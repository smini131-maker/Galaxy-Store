package com.smini.galaxyattendance

import android.content.Context

object AppPrefs {
    private const val NAME = "attendance_prefs"
    const val KEY_ENABLED = "enabled"
    const val KEY_HOUR = "hour"
    const val KEY_MINUTE = "minute"
    const val KEY_COORDINATE = "coordinate_enabled"
    const val KEY_X = "coordinate_x"
    const val KEY_Y = "coordinate_y"
    const val KEY_PENDING_RUN = "pending_run"

    fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
