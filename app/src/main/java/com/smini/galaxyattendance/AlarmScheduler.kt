package com.smini.galaxyattendance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    private const val REQUEST_CODE = 9101

    fun scheduleNext(context: Context, forceDelayMinutes: Int? = null) {
        val prefs = AppPrefs.prefs(context)
        if (!prefs.getBoolean(AppPrefs.KEY_ENABLED, false)) return

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val trigger = if (forceDelayMinutes != null) {
            System.currentTimeMillis() + forceDelayMinutes * 60_000L
        } else {
            val hour = prefs.getInt(AppPrefs.KEY_HOUR, 9)
            val minute = prefs.getInt(AppPrefs.KEY_MINUTE, 0)
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            AppLog.write(context, "정확한 알람 권한 없음: 일반 알람으로 예약")
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            AppLog.write(context, "다음 실행 예약: ${java.util.Date(trigger)}")
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(context, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pi)
        AppLog.write(context, "자동 실행 예약 해제")
    }
}
