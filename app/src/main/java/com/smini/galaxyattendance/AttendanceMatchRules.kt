package com.smini.galaxyattendance

/**
 * Pure text/metadata rules used to distinguish the real attendance action
 * from page titles such as "위클리 출석체크 [명일방주]".
 *
 * Keep this object Android-free so it can be regression-tested on the JVM.
 */
object AttendanceMatchRules {
    private val exactActionTexts = setOf(
        "출석체크하기",
        "오늘출석",
        "출석하기",
        "스탬프찍기",
        "참여하기",
        "체크인하기",
        "checkin",
        "check-in"
    ).map(::normalize).toSet()

    private val titleIdMarkers = listOf(
        "actionbar_title",
        "banner_title",
        "title_text"
    ).map(::normalize)

    private val eventTitleMarkers = listOf(
        "위클리출석체크",
        "weeklyattendance",
        "weeklycheckin"
    ).map(::normalize)

    fun normalize(value: CharSequence?): String =
        value?.toString()
            ?.lowercase()
            ?.replace(Regex("\\s+"), "")
            .orEmpty()

    fun isExactAttendanceAction(text: CharSequence?, contentDescription: CharSequence?): Boolean {
        val fields = sequenceOf(normalize(text), normalize(contentDescription))
            .filter { it.isNotEmpty() }
        return fields.any { it in exactActionTexts }
    }

    fun isAttendanceActionCandidate(
        text: CharSequence?,
        contentDescription: CharSequence?,
        viewId: String?,
        selfClickable: Boolean,
        clickableAncestor: Boolean
    ): Boolean {
        if (!isExactAttendanceAction(text, contentDescription)) return false

        val normalizedId = normalize(viewId)
        if (titleIdMarkers.any { normalizedId.contains(it) }) return false

        return selfClickable || clickableAncestor
    }

    fun isEventPageTitle(
        text: CharSequence?,
        contentDescription: CharSequence?,
        viewId: String?
    ): Boolean {
        val normalizedText = listOf(normalize(text), normalize(contentDescription))
            .joinToString("")
        if (eventTitleMarkers.any { normalizedText.contains(it) }) return true

        val normalizedId = normalize(viewId)
        return normalizedId.contains(normalize("actionbar_title")) &&
            normalizedText.contains(normalize("출석체크"))
    }
}
