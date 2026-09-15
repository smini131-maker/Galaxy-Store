from pathlib import Path
import re

service = Path('app/src/main/java/com/smini/galaxyattendance/AttendanceAccessibilityService.kt')
s = service.read_text()

# Add verified-scroll state.
s = s.replace(
'''    private var attendanceClickCount = 0
    private var verifyStartedAt = 0L
''',
'''    private var attendanceClickCount = 0
    private var verifyStartedAt = 0L
    private var scrollStrategyIndex = 0
    private var lastAttendanceTargetY: Int? = null
    private var suppressEventRescheduleUntil = 0L
''')

# Prevent accessibility event storms from cancelling/restarting an in-flight scroll probe.
s = s.replace(
'''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != AttendanceRunnerService.STORE_PACKAGE) return
        if (!AppPrefs.prefs(this).getBoolean(AppPrefs.KEY_PENDING_RUN, false)) return
        if (runStartedAt == 0L) beginRun()
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 350)
    }
''',
'''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != AttendanceRunnerService.STORE_PACKAGE) return
        if (!AppPrefs.prefs(this).getBoolean(AppPrefs.KEY_PENDING_RUN, false)) return
        if (runStartedAt == 0L) beginRun()
        if (System.currentTimeMillis() < suppressEventRescheduleUntil) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 350)
    }
''')

s = s.replace(
'''        attendanceClickCount = 0
        verifyStartedAt = 0L
        AppLog.write(this, "자동 탐색 세션 시작 (WebView 강제 스크롤 v1.2.3)")
''',
'''        attendanceClickCount = 0
        verifyStartedAt = 0L
        scrollStrategyIndex = 0
        lastAttendanceTargetY = null
        suppressEventRescheduleUntil = 0L
        AppLog.write(this, "자동 탐색 세션 시작 (GMP 검증형 스크롤 v1.3.0)")
''')

# Check-in page uses the dedicated target-aware verified scroll engine.
s = s.replace(
'''        if (scrollCount < MAX_SCROLL && scrollForward(root, "출석 페이지")) {
            scrollCount++
            AppLog.write(this, "출석 페이지 스크롤 ${scrollCount}/$MAX_SCROLL")
            return scheduleScan(2400)
        }
''',
'''        if (scrollCount < MAX_SCROLL && scrollAttendancePage(root)) {
            scrollCount++
            AppLog.write(this, "출석 페이지 스크롤 시도 ${scrollCount}/$MAX_SCROLL")
            return scheduleScan(SCROLL_SETTLE_MS)
        }
''')

# Replace the old class-name/WebView-only scroll logic with target-aware action/gesture escalation.
new_scroll = r'''    private fun scrollForward(root: AccessibilityNodeInfo?, where: String): Boolean {
        if (root == null) return false
        suppressEventRescheduleUntil = System.currentTimeMillis() + EVENT_SUPPRESS_MS

        val generic = findActionScrollableNode(root)
        if (generic != null) {
            val rect = Rect().also { generic.getBoundsInScreen(it) }
            val ids = generic.actionList.map { it.id }
            AppLog.write(
                this,
                "[$where 스크롤] 액션 노드 class='${generic.className}' scrollable=${generic.isScrollable} bounds=$rect actions=${actionSummary(generic)}"
            )
            if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids) {
                val ok = generic.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AppLog.write(this, "[$where 스크롤] ACTION_SCROLL_FORWARD=$ok")
                if (ok) return true
            }
            if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids) {
                val ok = generic.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                AppLog.write(this, "[$where 스크롤] ACTION_SCROLL_DOWN=$ok")
                if (ok) return true
            }
        }

        return dispatchScrollGesture(root, where, 0.50f, 0.78f, 0.28f, 220L)
    }

    private fun scrollAttendancePage(root: AccessibilityNodeInfo): Boolean {
        val target = findBestAttendanceTarget(root)
        val targetRect = target?.let { Rect().also { rect -> it.getBoundsInScreen(rect) } }

        if (target != null && targetRect != null) {
            val currentY = targetRect.centerY()
            val previousY = lastAttendanceTargetY
            if (previousY != null) {
                val delta = previousY - currentY
                if (delta >= MIN_SCROLL_DELTA_PX || delta <= -MIN_SCROLL_DELTA_PX) {
                    AppLog.write(this, "[스크롤 검증] 출석 버튼 Y 이동 감지: $previousY -> $currentY (delta=$delta)")
                } else {
                    AppLog.write(this, "[스크롤 검증] 이전 시도 후 출석 버튼 위치 변화 없음: Y=$currentY")
                }
            }
            lastAttendanceTargetY = currentY
            AppLog.write(
                this,
                "[출석 타깃] text='${displayText(target)}' class='${target.className}' id='${target.viewIdResourceName}' visible=${target.isVisibleToUser} bounds=$targetRect actions=${actionSummary(target)}"
            )
            logAncestorScrollCapabilities(target)
        } else {
            AppLog.write(this, "[출석 타깃] 트리에서 출석 버튼 노드를 찾지 못함")
        }

        suppressEventRescheduleUntil = System.currentTimeMillis() + EVENT_SUPPRESS_MS
        val strategy = scrollStrategyIndex % SCROLL_STRATEGY_COUNT
        scrollStrategyIndex++
        AppLog.write(this, "[출석 페이지 스크롤] 전략 ${strategy + 1}/$SCROLL_STRATEGY_COUNT 실행")

        return when (strategy) {
            0 -> {
                if (target != null) {
                    val actionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                    val ok = target.performAction(actionId)
                    AppLog.write(this, "[출석 페이지 스크롤] 타깃 ACTION_SHOW_ON_SCREEN=$ok")
                    if (ok) true
                    else tryAncestorScroll(target, root) ||
                        dispatchScrollGesture(root, "출석 페이지", 0.18f, 0.78f, 0.28f, 220L)
                } else {
                    dispatchScrollGesture(root, "출석 페이지", 0.18f, 0.78f, 0.28f, 220L)
                }
            }

            1 -> {
                if (tryAncestorScroll(target, root)) true
                else dispatchScrollGesture(root, "출석 페이지", 0.50f, 0.78f, 0.28f, 220L)
            }

            2 -> dispatchScrollGesture(root, "출석 페이지", 0.18f, 0.80f, 0.24f, 200L)
            3 -> dispatchScrollGesture(root, "출석 페이지", 0.50f, 0.80f, 0.24f, 200L)
            4 -> dispatchScrollGesture(root, "출석 페이지", 0.82f, 0.80f, 0.24f, 200L)
            else -> dispatchScrollGesture(root, "출석 페이지", 0.32f, 0.84f, 0.18f, 180L)
        }
    }

    private fun findBestAttendanceTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val metrics = resources.displayMetrics
        val candidates = allNodes(root).filter { node ->
            val text = searchableText(node)
            text.isNotEmpty() && ATTEND_KEYWORDS.any { text.contains(normalize(it)) }
        }

        return candidates.minByOrNull { node ->
            val rect = Rect().also { node.getBoundsInScreen(it) }
            when {
                rect.isEmpty -> Int.MAX_VALUE / 2
                rect.top >= metrics.heightPixels -> rect.top - metrics.heightPixels
                rect.bottom <= 0 -> -rect.bottom
                else -> 0
            }
        }
    }

    private fun tryAncestorScroll(target: AccessibilityNodeInfo?, root: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = target?.parent
        var depth = 1
        while (current != null && depth <= MAX_SCROLL_ANCESTOR_DEPTH) {
            val node = current
            val actionIds = node.actionList.map { it.id }
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val hasScrollAction =
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actionIds ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in actionIds

            if (hasScrollAction) {
                AppLog.write(
                    this,
                    "[스크롤 조상] depth=$depth class='${node.className}' scrollable=${node.isScrollable} bounds=$rect actions=${actionSummary(node)}"
                )
                if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actionIds) {
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    AppLog.write(this, "[스크롤 조상] depth=$depth ACTION_SCROLL_FORWARD=$ok")
                    if (ok) return true
                }
                if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in actionIds) {
                    val ok = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                    AppLog.write(this, "[스크롤 조상] depth=$depth ACTION_SCROLL_DOWN=$ok")
                    if (ok) return true
                }
            }
            current = node.parent
            depth++
        }

        val generic = findActionScrollableNode(root) ?: return false
        val rect = Rect().also { generic.getBoundsInScreen(it) }
        val ids = generic.actionList.map { it.id }
        AppLog.write(
            this,
            "[스크롤 대체 노드] class='${generic.className}' scrollable=${generic.isScrollable} bounds=$rect actions=${actionSummary(generic)}"
        )
        if (AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids &&
            generic.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        ) {
            AppLog.write(this, "[스크롤 대체 노드] ACTION_SCROLL_FORWARD=true")
            return true
        }
        if (AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids &&
            generic.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
        ) {
            AppLog.write(this, "[스크롤 대체 노드] ACTION_SCROLL_DOWN=true")
            return true
        }
        return false
    }

    private fun findActionScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return allNodes(root).mapNotNull { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@mapNotNull null
            val ids = node.actionList.map { it.id }
            val hasScroll =
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids
            if (!hasScroll) return@mapNotNull null
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (rect.isEmpty || !isRectOnScreen(rect)) return@mapNotNull null
            node to rect
        }.maxByOrNull { (_, rect) -> rect.width().toLong() * rect.height().toLong() }?.first
    }

    private fun logAncestorScrollCapabilities(target: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = target
        var depth = 0
        while (current != null && depth <= MAX_SCROLL_ANCESTOR_DEPTH) {
            val node = current
            val ids = node.actionList.map { it.id }
            val interesting =
                depth == 0 || node.isScrollable ||
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in ids ||
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id in ids
            if (interesting) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                AppLog.write(
                    this,
                    "[타깃 조상 진단] depth=$depth class='${node.className}' id='${node.viewIdResourceName}' visible=${node.isVisibleToUser} scrollable=${node.isScrollable} bounds=$rect actions=${actionSummary(node)}"
                )
            }
            current = node.parent
            depth++
        }
    }

    private fun actionSummary(node: AccessibilityNodeInfo): String =
        node.actionList.joinToString(prefix = "[", postfix = "]") { action ->
            val label = action.label?.toString().orEmpty()
            if (label.isBlank()) action.id.toString() else "${action.id}:$label"
        }

    private fun dispatchScrollGesture(
        root: AccessibilityNodeInfo,
        where: String,
        xRatio: Float,
        startRatio: Float,
        endRatio: Float,
        durationMs: Long
    ): Boolean {
        val metrics = resources.displayMetrics
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val left = maxOf(0, rootRect.left)
        val right = minOf(metrics.widthPixels, rootRect.right).takeIf { it > left } ?: metrics.widthPixels
        val top = maxOf(0, rootRect.top)
        val bottom = minOf(metrics.heightPixels, rootRect.bottom).takeIf { it > top } ?: metrics.heightPixels
        val width = right - left
        val height = bottom - top

        val x = left + width * xRatio
        val startY = top + height * startRatio
        val endY = top + height * endRatio
        AppLog.write(
            this,
            "[$where 스크롤] 물리 스와이프 x=${x.toInt()} y=${startY.toInt()}→${endY.toInt()} duration=${durationMs}ms root=$rootRect"
        )

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                AppLog.write(this@AttendanceAccessibilityService, "[$where 스크롤] 물리 스와이프 콜백=completed")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                AppLog.write(this@AttendanceAccessibilityService, "[$where 스크롤] 물리 스와이프 콜백=cancelled")
            }
        }

        val accepted = dispatchGesture(gesture, callback, null)
        AppLog.write(this, "[$where 스크롤] dispatchGesture=${if (accepted) "accepted" else "rejected"}")
        return accepted
    }
'''

pattern = r'    private fun scrollForward\(root: AccessibilityNodeInfo\?, where: String\): Boolean \{.*?\n    private fun gestureClick'
s2, count = re.subn(pattern, new_scroll + '\n    private fun gestureClick', s, flags=re.S)
if count != 1:
    raise SystemExit(f'scroll engine replacement count={count}')
s = s2

s = s.replace('private const val MAX_SCROLL = 10', 'private const val MAX_SCROLL = 12')
s = s.replace(
'''        private const val SCROLL_DURATION_MS = 200L
        private const val SCROLL_START_RATIO = 0.65f
        private const val SCROLL_END_RATIO = 0.35f
''',
'''        private const val SCROLL_SETTLE_MS = 1_150L
        private const val EVENT_SUPPRESS_MS = 900L
        private const val MIN_SCROLL_DELTA_PX = 24
        private const val MAX_SCROLL_ANCESTOR_DEPTH = 12
        private const val SCROLL_STRATEGY_COUNT = 6
''')
service.write_text(s)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
g = g.replace('versionCode = 6', 'versionCode = 7')
g = g.replace('versionName = "1.2.3"', 'versionName = "1.3.0"')
gradle.write_text(g)

readme = Path('README.md')
r = readme.read_text()
r = r.replace(
    '**v1.2.3** — WebView 직접 스크롤 + S25 Ultra 중앙 안전구역 200ms 스와이프 버전입니다.',
    '**v1.3.0** — Galaxy Store GMP 가상 접근성 트리를 대상으로 실제 스크롤 이동을 검증하는 다단계 스크롤 엔진 버전입니다.'
)
if '## v1.3.0 GMP 검증형 스크롤 엔진' not in r:
    r += '''\n\n## v1.3.0 GMP 검증형 스크롤 엔진\n- 실제 기기 로그에서 이벤트 페이지가 일반 WebView 클래스가 아니라 `gmp-*` 가상 접근성 노드로 구성되고, `출석체크하기` 노드가 `visible=false`, 화면 하단 경계에 0 높이로 존재하는 것을 확인했습니다.\n- 더 이상 `android.webkit.WebView` 클래스명이나 `isScrollable` 값만 믿지 않습니다.\n- 숨겨진 출석 버튼에 `ACTION_SHOW_ON_SCREEN`을 먼저 요청합니다.\n- 버튼의 부모 체인을 최대 12단계까지 추적하여 실제 `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_DOWN`을 광고하는 컨테이너에 직접 스크롤 액션을 보냅니다.\n- native action이 성공을 반환해도 그대로 믿지 않고 다음 스캔에서 출석 버튼 Y 좌표가 실제 이동했는지 비교합니다.\n- 이동이 없으면 18% / 50% / 82% X 위치와 서로 다른 거리·속도의 물리 스와이프 전략으로 자동 승격합니다.\n- 제스처는 `GestureResultCallback`으로 completed/cancelled 여부까지 기록합니다.\n- 스크롤 직후 접근성 이벤트 폭주가 예약된 검증 스캔을 앞당기지 않도록 900ms 동안 이벤트 재예약을 억제합니다.\n- 클릭/금지 단어/Bounds/성공 문구 검증 로직은 기존 그대로 유지합니다.\n'''
readme.write_text(r)
