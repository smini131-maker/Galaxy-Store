package com.smini.galaxyattendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppLog.write(context, "예약 시간 도달")
        AttendanceRunnerService.start(context, false)
        AlarmScheduler.scheduleNext(context)
    }
}
