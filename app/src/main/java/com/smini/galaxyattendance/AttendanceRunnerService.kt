package com.smini.galaxyattendance

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class AttendanceRunnerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NotificationHelper.ID, NotificationHelper.build(this, "출석 자동화를 준비하는 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        run(manual)
        return START_NOT_STICKY
    }

    private fun run(manual: Boolean) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        if (keyguard.isKeyguardLocked || !power.isInteractive) {
            AppLog.write(this, "화면이 잠겨 있어 실행 보류; 15분 뒤 재시도")
            if (!manual) AlarmScheduler.scheduleNext(this, 15)
            stopSelf()
            return
        }

        if (!AttendanceAccessibilityService.isEnabled(this)) {
            AppLog.write(this, "접근성 서비스가 꺼져 있어 자동화 중단")
            stopSelf()
            return
        }

        val launch = packageManager.getLaunchIntentForPackage(STORE_PACKAGE)
        if (launch == null) {
            AppLog.write(this, "Galaxy Store 실행 인텐트를 찾지 못함")
            stopSelf()
            return
        }

        AppPrefs.prefs(this).edit().putBoolean(AppPrefs.KEY_PENDING_RUN, true).apply()
        AttendanceAccessibilityService.prepareRun()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(launch)
            AppLog.write(this, "Galaxy Store 실행 요청; 접근성 탐색 시작")
        } catch (e: Exception) {
            AppLog.write(this, "Galaxy Store 실행 실패: ${e.javaClass.simpleName}: ${e.message}")
        }
        stopSelf()
    }

    companion object {
        const val STORE_PACKAGE = "com.sec.android.app.samsungapps"
        private const val EXTRA_MANUAL = "manual"
        fun start(context: Context, manual: Boolean) {
            val i = Intent(context, AttendanceRunnerService::class.java).putExtra(EXTRA_MANUAL, manual)
            try { context.startForegroundService(i) }
            catch (e: Exception) { AppLog.write(context, "포그라운드 서비스 시작 실패: ${e.message}") }
        }
    }
}
