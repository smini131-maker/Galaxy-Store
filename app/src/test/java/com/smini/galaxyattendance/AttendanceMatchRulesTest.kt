package com.smini.galaxyattendance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceMatchRulesTest {
    @Test
    fun realAttendanceButton_isAccepted() {
        assertTrue(
            AttendanceMatchRules.isAttendanceActionCandidate(
                text = null,
                contentDescription = "출석체크하기",
                viewId = "gmp-7d5094e2-cd35-4a82-81a0-a38d5419e08f",
                selfClickable = true,
                clickableAncestor = false
            )
        )
    }

    @Test
    fun spacedRealAttendanceButton_isAccepted() {
        assertTrue(
            AttendanceMatchRules.isAttendanceActionCandidate(
                text = "출석 체크하기",
                contentDescription = null,
                viewId = null,
                selfClickable = true,
                clickableAncestor = false
            )
        )
    }

    @Test
    fun weeklyEventTitle_isNeverAttendanceButton() {
        assertFalse(
            AttendanceMatchRules.isAttendanceActionCandidate(
                text = null,
                contentDescription = "위클리 출석체크 [명일방주]",
                viewId = "com.sec.android.app.samsungapps:id/actionbar_title",
                selfClickable = false,
                clickableAncestor = false
            )
        )
    }

    @Test
    fun weeklyEventTitle_staysRejectedEvenIfContainerIsClickable() {
        assertFalse(
            AttendanceMatchRules.isAttendanceActionCandidate(
                text = "위클리 출석체크 [명일방주]",
                contentDescription = null,
                viewId = "com.sec.android.app.samsungapps:id/actionbar_title",
                selfClickable = true,
                clickableAncestor = true
            )
        )
    }

    @Test
    fun genericAttendanceCheckText_isNotAnAction() {
        assertFalse(
            AttendanceMatchRules.isAttendanceActionCandidate(
                text = "출석 체크",
                contentDescription = null,
                viewId = null,
                selfClickable = true,
                clickableAncestor = false
            )
        )
    }

    @Test
    fun actionRequiresClickableNodeOrAncestor() {
        assertFalse(
            AttendanceMatchRules.isAttendanceActionCandidate(
                text = "출석체크하기",
                contentDescription = null,
                viewId = null,
                selfClickable = false,
                clickableAncestor = false
            )
        )
        assertTrue(
            AttendanceMatchRules.isAttendanceActionCandidate(
                text = "출석체크하기",
                contentDescription = null,
                viewId = null,
                selfClickable = false,
                clickableAncestor = true
            )
        )
    }

    @Test
    fun regressionTrace_selectsRealButtonNotVisibleTitle() {
        data class Candidate(
            val text: String?,
            val desc: String?,
            val id: String?,
            val clickable: Boolean,
            val parentClickable: Boolean
        )

        val trace = listOf(
            Candidate(
                text = null,
                desc = "위클리 출석체크 [명일방주]",
                id = "com.sec.android.app.samsungapps:id/actionbar_title",
                clickable = false,
                parentClickable = false
            ),
            Candidate(
                text = null,
                desc = "출석체크하기",
                id = "gmp-7d5094e2-cd35-4a82-81a0-a38d5419e08f",
                clickable = true,
                parentClickable = false
            )
        )

        val matches = trace.filter {
            AttendanceMatchRules.isAttendanceActionCandidate(
                it.text, it.desc, it.id, it.clickable, it.parentClickable
            )
        }

        assertTrue(matches.size == 1)
        assertTrue(matches.single().desc == "출석체크하기")
    }

    @Test
    fun weeklyTitle_isRecognizedAsEventPageTitle() {
        assertTrue(
            AttendanceMatchRules.isEventPageTitle(
                text = "위클리 출석체크 [명일방주]",
                contentDescription = null,
                viewId = "com.sec.android.app.samsungapps:id/actionbar_title"
            )
        )
    }
}
