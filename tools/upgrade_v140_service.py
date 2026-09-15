from pathlib import Path

p = Path('app/src/main/java/com/smini/galaxyattendance/AttendanceAccessibilityService.kt')
s = p.read_text()

s = s.replace(
    '자동 탐색 세션 시작 (물리 스와이프 전용 v1.3.1)',
    '자동 탐색 세션 시작 (출석 액션 오탐 차단 v1.4.0)'
)

old = '''        if (findAndGestureClick(root, ATTEND_KEYWORDS, includeViewId = true)) {
            attendanceClickCount++
            stage = Stage.VERIFY
            verifyStartedAt = System.currentTimeMillis()
            return scheduleScan(1800)
        }

        if (findAndGestureClick(root, BANNER_KEYWORDS, includeViewId = true)) {'''
new = '''        // 이벤트 WebView에 직접 진입한 상태라면 상단 제목을 출석 버튼으로 오인하지 않고
        // CHECKIN 단계로 명시적으로 전환한다.
        if (isAttendanceEventPage(root)) {
            AppLog.write(this, "[상태 전환] 출석 이벤트 페이지 감지 -> CHECKIN")
            stage = Stage.CHECKIN
            scrollCount = 0
            return scheduleScan(350)
        }

        if (findAttendanceButtonAndClick(root)) {
            attendanceClickCount++
            stage = Stage.VERIFY
            verifyStartedAt = System.currentTimeMillis()
            return scheduleScan(1800)
        }

        if (findAndGestureClick(root, BANNER_KEYWORDS, includeViewId = true)) {'''
if old not in s:
    raise SystemExit('scanEntry attendance block not found')
s = s.replace(old, new, 1)

old = '''        if (findAndGestureClick(root, ATTEND_KEYWORDS, includeViewId = true)) {
            attendanceClickCount++
            stage = Stage.VERIFY
            verifyStartedAt = System.currentTimeMillis()
            return scheduleScan(1900)
        }
'''
new = '''        if (findAttendanceButtonAndClick(root)) {
            attendanceClickCount++
            stage = Stage.VERIFY
            verifyStartedAt = System.currentTimeMillis()
            return scheduleScan(1900)
        }
'''
if old not in s:
    raise SystemExit('scanCheckIn attendance block not found')
s = s.replace(old, new, 1)

old = '''        if (attendanceClickCount < MAX_ATTEND_CLICK && findAndGestureClick(root, ATTEND_KEYWORDS, includeViewId = true)) {
            attendanceClickCount++
            verifyStartedAt = System.currentTimeMillis()
            AppLog.write(this, "출석 버튼 재시도 ${attendanceClickCount}/$MAX_ATTEND_CLICK")
            return scheduleScan(1900)
        }
'''
new = '''        if (attendanceClickCount < MAX_ATTEND_CLICK && findAttendanceButtonAndClick(root)) {
            attendanceClickCount++
            verifyStartedAt = System.currentTimeMillis()
            AppLog.write(this, "출석 버튼 재시도 ${attendanceClickCount}/$MAX_ATTEND_CLICK")
            return scheduleScan(1900)
        }
'''
if old not in s:
    raise SystemExit('scanVerify attendance block not found')
s = s.replace(old, new, 1)

needle = '''    private fun findAndGestureClick(
        root: AccessibilityNodeInfo,
        needles: List<String>,
        includeViewId: Boolean
    ): Boolean {'''
helpers = '''    private fun isAttendanceEventPage(root: AccessibilityNodeInfo): Boolean {
        val nodes = allNodes(root)
        val hasVisibleTitle = nodes.any { node ->
            node.isVisibleToUser && AttendanceMatchRules.isEventPageTitle(
                text = node.text,
                contentDescription = node.contentDescription,
                viewId = node.viewIdResourceName
            )
        }
        val hasAttendanceActionInTree = nodes.any { node ->
            AttendanceMatchRules.isExactAttendanceAction(node.text, node.contentDescription)
        }
        return hasVisibleTitle && hasAttendanceActionInTree
    }

    /**
     * 출석 버튼은 일반 키워드 부분 매칭을 사용하지 않는다.
     * 실제 로그에서 `위클리 출석체크 [명일방주]` 제목이 `출석 체크` 부분 매칭으로
     * 오인되어 VERIFY로 넘어가던 회귀 버그를 차단하기 위한 전용 경로다.
     */
    private fun findAttendanceButtonAndClick(root: AccessibilityNodeInfo): Boolean {
        for (node in allNodes(root)) {
            val ancestorClickable = hasClickableAncestor(node, MAX_ACTION_ANCESTOR_DEPTH)
            val isCandidate = AttendanceMatchRules.isAttendanceActionCandidate(
                text = node.text,
                contentDescription = node.contentDescription,
                viewId = node.viewIdResourceName,
                selfClickable = node.isClickable,
                clickableAncestor = ancestorClickable
            )
            if (!isCandidate) continue

            AppLog.write(
                this,
                "[출석 액션 후보] text='${displayText(node)}' id='${node.viewIdResourceName}' " +
                    "visible=${node.isVisibleToUser} clickable=${node.isClickable} ancestorClickable=$ancestorClickable"
            )
            if (safeGestureClick(node, "출석 액션 정확 매칭='${displayText(node)}'")) return true
        }
        return false
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo, maxDepth: Int): Boolean {
        var current = node.parent
        var depth = 1
        while (current != null && depth <= maxDepth) {
            if (current.isClickable && current.isEnabled) return true
            current = current.parent
            depth++
        }
        return false
    }

'''
if needle not in s:
    raise SystemExit('helper insertion point not found')
s = s.replace(needle, helpers + needle, 1)

old = '''        val candidates = allNodes(root).filter { node ->
            val text = searchableText(node)
            text.isNotEmpty() && ATTEND_KEYWORDS.any { text.contains(normalize(it)) }
        }
'''
new = '''        val candidates = allNodes(root).filter { node ->
            AttendanceMatchRules.isExactAttendanceAction(node.text, node.contentDescription)
        }
'''
if old not in s:
    raise SystemExit('findBestAttendanceTarget block not found')
s = s.replace(old, new, 1)

s = s.replace(
    '        private const val MAX_PARENT_ASCENT = 3\n',
    '        private const val MAX_PARENT_ASCENT = 3\n        private const val MAX_ACTION_ANCESTOR_DEPTH = 2\n',
    1
)

attend_line = '        private val ATTEND_KEYWORDS = listOf("출석 체크하기", "출석체크하기", "출석 체크", "오늘 출석", "출석하기", "스탬프 찍기", "참여하기", "체크인")\n'
if attend_line not in s:
    raise SystemExit('ATTEND_KEYWORDS line not found')
s = s.replace(attend_line, '', 1)

# Hard regression assertions: the old generic attendance matcher must be gone.
if 'findAndGestureClick(root, ATTEND_KEYWORDS' in s or 'ATTEND_KEYWORDS' in s:
    raise SystemExit('regression guard failed: generic ATTEND_KEYWORDS path still present')
if 'findAttendanceButtonAndClick(root)' not in s:
    raise SystemExit('regression guard failed: strict attendance matcher missing')

p.write_text(s)
