package com.smini.galaxyattendance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppLog.write(context, "부팅/앱 업데이트 감지: 예약 복원")
        AlarmScheduler.scheduleNext(context)
    }
}
