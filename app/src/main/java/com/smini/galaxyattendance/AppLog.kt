package com.smini.galaxyattendance

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val FILE = "attendance.log"
    private val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)

    @Synchronized
    fun write(context: Context, message: String) {
        val line = "${format.format(Date())}  $message\n"
        context.openFileOutput(FILE, Context.MODE_APPEND).bufferedWriter().use { it.write(line) }
        trim(context)
    }

    fun read(context: Context): String = try {
        context.openFileInput(FILE).bufferedReader().use { it.readText() }.takeLast(12000)
    } catch (_: Exception) { "아직 로그가 없습니다." }

    private fun trim(context: Context) {
        try {
            val text = context.openFileInput(FILE).bufferedReader().use { it.readText() }
            if (text.length > 24000) {
                context.openFileOutput(FILE, Context.MODE_PRIVATE).bufferedWriter().use { it.write(text.takeLast(12000)) }
            }
        } catch (_: Exception) { }
    }
}
