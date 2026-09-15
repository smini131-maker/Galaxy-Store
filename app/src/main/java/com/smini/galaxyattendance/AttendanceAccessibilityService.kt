package com.smini.galaxyattendance

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Calendar
import java.util.Locale

class AttendanceAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var runStartedAt = 0L
    private var lastActionAt = 0L
    private var stage = Stage.ENTRY
    private var scrollCount = 0
    private var termsHandled = false
    private var attendanceClickCount = 0
    private var verifyStartedAt = 0L
    private var scrollStrategyIndex = 0
    private var lastAttendanceTargetY: Int? = null
    private var suppressEventRescheduleUntil = 0L

    override fun onServiceConnected() {
        instance = this
        AppLog.write(this, "접근성 서비스 연결됨")
        if (AppPrefs.prefs(this).getBoolean(AppPrefs.KEY_PENDING_RUN, false)) beginRun()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(scanRunnable)
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != AttendanceRunnerService.STORE_PACKAGE) return
        if (!AppPrefs.prefs(this).getBoolean(AppPrefs.KEY_PENDING_RUN, false)) return
        if (runStartedAt == 0L) beginRun()
        if (System.currentTimeMillis() < suppressEventRescheduleUntil) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 350)
    }

    private val scanRunnable = Runnable { scan() }

    private fun beginRun() {
        runStartedAt = System.currentTimeMillis()
        lastActionAt = 0L
        stage = Stage.ENTRY
        scrollCount = 0
        termsHandled = false
        attendanceClickCount = 0
        verifyStartedAt = 0L
        scrollStrategyIndex = 0
        lastAttendanceTargetY = null
        suppressEventRescheduleUntil = 0L
        AppLog.write(this, "자동 탐색 세션 시작 (출석 액션 오탐 차단 v1.4.0)")
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 800)
    }

    private fun scan() {
        if (!AppPrefs.prefs(this).getBoolean(AppPrefs.KEY_PENDING_RUN, false)) return

        val root = rootInActiveWindow ?: return retry("활성 창 없음")
        if (System.currentTimeMillis() - runStartedAt > RUN_TIMEOUT_MS) {
            val prefs = AppPrefs.prefs(this)
            if (prefs.getBoolean(AppPrefs.KEY_COORDINATE, false)) {
                val x = prefs.getInt(AppPrefs.KEY_X, -1)
                val y = prefs.getInt(AppPrefs.KEY_Y, -1)
                if (isCoordinateOnScreen(x, y)) {
                    AppLog.write(this, "UI 탐색 시간 초과; 사용자 지정 좌표 클릭 시도 ($x,$y)")
                    gestureClick(x.toFloat(), y.toFloat(), "사용자 지정 좌표")
                    finish(false, "좌표 클릭 수행 - 결과 확인 필요")
                    return
                }
            }
            finish(false, "60초 동안 출석 UI를 찾지 못함")
            return
        }

        dumpCompact(root)

        when (stage) {
            Stage.ENTRY -> scanEntry(root)
            Stage.BANNER -> scanBanner(root)
            Stage.CHECKIN -> scanCheckIn(root)
            Stage.VERIFY -> scanVerify(root)
        }
    }

    private fun scanEntry(root: AccessibilityNodeInfo) {
        if (hasAny(root, SUCCESS_WORDS)) {
            finish(true, "이미 오늘 출석 완료 상태")
            return
        }

        // 이벤트 WebView에 직접 진입한 상태라면 상단 제목을 출석 버튼으로 오인하지 않고
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

        if (findAndGestureClick(root, BANNER_KEYWORDS, includeViewId = true)) {
            stage = Stage.CHECKIN
            scrollCount = 0
            return scheduleScan(5000)
        }

        if (findAndGestureClick(root, BENEFIT_TAB_KEYWORDS, includeViewId = false)) {
            stage = Stage.BANNER
            scrollCount = 0
            return scheduleScan(2400)
        }

        retry("출석 진입 UI 탐색 중")
    }

    private fun scanBanner(root: AccessibilityNodeInfo) {
        if (findAndGestureClick(root, BANNER_KEYWORDS, includeViewId = true)) {
            stage = Stage.CHECKIN
            scrollCount = 0
            return scheduleScan(5000)
        }

        if (scrollCount < MAX_SCROLL && scrollForward(root, "혜택 화면")) {
            scrollCount++
            AppLog.write(this, "혜택 화면 스크롤 ${scrollCount}/$MAX_SCROLL")
            return scheduleScan(1700)
        }

        finish(false, "혜택 화면에서 화면에 보이는 출석 배너를 찾지 못함")
    }

    private fun scanCheckIn(root: AccessibilityNodeInfo) {
        if (hasAny(root, SUCCESS_WORDS)) {
            finish(true, "이미 오늘 출석 완료 상태")
            return
        }

        if (!termsHandled && isMonday()) {
            handleMondayTerms(root)
            termsHandled = true
            return scheduleScan(1300)
        }

        if (findAttendanceButtonAndClick(root)) {
            attendanceClickCount++
            stage = Stage.VERIFY
            verifyStartedAt = System.currentTimeMillis()
            return scheduleScan(1900)
        }

        if (scrollCount < MAX_SCROLL && scrollAttendancePage(root)) {
            scrollCount++
            AppLog.write(this, "출석 페이지 스크롤 시도 ${scrollCount}/$MAX_SCROLL")
            return scheduleScan(SCROLL_SETTLE_MS)
        }

        finish(false, "출석 이벤트 페이지에서 화면에 보이는 출석 버튼을 찾지 못함")
    }

    private fun scanVerify(root: AccessibilityNodeInfo) {
        if (hasAny(root, SUCCESS_WORDS)) {
            finish(true, "출석 완료 문구 확인")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        val elapsed = System.currentTimeMillis() - verifyStartedAt
        if (elapsed < VERIFY_WAIT_MS) {
            return retry("출석 완료 여부 확인 중")
        }

        if (attendanceClickCount < MAX_ATTEND_CLICK && findAttendanceButtonAndClick(root)) {
            attendanceClickCount++
            verifyStartedAt = System.currentTimeMillis()
            AppLog.write(this, "출석 버튼 재시도 ${attendanceClickCount}/$MAX_ATTEND_CLICK")
            return scheduleScan(1900)
        }

        finish(false, "출석 버튼 터치는 수행했지만 완료 문구를 확인하지 못함")
    }

    private fun handleMondayTerms(root: AccessibilityNodeInfo) {
        val checkBoxes = allNodes(root).filter {
            it.className?.toString() == "android.widget.CheckBox" && !it.isChecked && isSafeVisibleNode(it)
        }

        var clickedAny = false
        for (box in checkBoxes) {
            if (safeGestureClick(box, "월요일 약관 체크박스")) clickedAny = true
        }

        if (!clickedAny) {
            findAndGestureClick(root, listOf("동의", "약관"), includeViewId = false)
        }
    }

    private fun isAttendanceEventPage(root: AccessibilityNodeInfo): Boolean {
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

    private fun findAndGestureClick(
        root: AccessibilityNodeInfo,
        needles: List<String>,
        includeViewId: Boolean
    ): Boolean {
        val nodes = allNodes(root)

        if (includeViewId) {
            for (node in nodes) {
                val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
                if (id.contains("attendance") || id.contains("checkin") || id.contains("check_in")) {
                    if (safeGestureClick(node, "viewId=${node.viewIdResourceName}")) return true
                }
            }
        }

        for (needle in needles) {
            val target = normalize(needle)
            for (node in nodes) {
                val combined = searchableText(node)
                if (combined.isNotEmpty() && combined == target) {
                    if (safeGestureClick(node, "정확 매칭='${displayText(node)}'")) return true
                }
            }
        }

        for (needle in needles) {
            val target = normalize(needle)
            for (node in nodes) {
                val combined = searchableText(node)
                if (combined.isNotEmpty() && combined.contains(target)) {
                    if (safeGestureClick(node, "공백무시 부분 매칭='${displayText(node)}'")) return true
                }
            }
        }

        // WebView가 "출석" / "체크하기"처럼 문장을 형제 노드로 분리한 경우,
        // 너무 큰 WebView 전체가 아닌 작은 컨테이너의 하위 텍스트만 합쳐서 찾는다.
        for (needle in needles) {
            val target = normalize(needle)
            for (node in nodes) {
                if (!isReasonableSubtreeCandidate(node)) continue
                val subtree = compactSubtreeText(node) ?: continue
                if (subtree.contains(target)) {
                    if (safeGestureClick(node, "분할 텍스트 결합 매칭='$subtree'")) return true
                }
            }
        }

        return false
    }

    private fun safeGestureClick(node: AccessibilityNodeInfo, why: String): Boolean {
        val nodeText = displayText(node)
        val originalRect = Rect().also { node.getBoundsInScreen(it) }
        AppLog.write(
            this,
            "[후보 발견] $why text='$nodeText' visible=${node.isVisibleToUser} enabled=${node.isEnabled} bounds=$originalRect"
        )

        if (containsForbiddenWord(nodeText)) {
            AppLog.write(this, "[후보 무시] 금지 단어 포함 text='$nodeText'")
            return false
        }

        if (!node.isVisibleToUser) {
            AppLog.write(this, "[후보 무시] isVisibleToUser=false text='$nodeText' bounds=$originalRect")
            return false
        }
        if (!node.isEnabled) {
            AppLog.write(this, "[후보 무시] enabled=false text='$nodeText' bounds=$originalRect")
            return false
        }

        if (!originalRect.isEmpty &&
            originalRect.width() >= MIN_NODE_SIZE_PX &&
            originalRect.height() >= MIN_NODE_SIZE_PX
        ) {
            if (!isRectOnScreen(originalRect)) {
                AppLog.write(this, "[후보 무시] 화면 밖 WebView 노드 text='$nodeText' bounds=$originalRect")
                return false
            }
            if (!isReasonableClickRect(originalRect)) {
                AppLog.write(this, "[후보 무시] 클릭 영역이 지나치게 큼 text='$nodeText' bounds=$originalRect")
                return false
            }
            return gestureClick(
                originalRect.centerX().toFloat(),
                originalRect.centerY().toFloat(),
                "$why bounds=$originalRect parentDepth=0"
            )
        }

        AppLog.write(
            this,
            "[부모 탐색] 원본 영역이 작거나 비어 있음 text='$nodeText' width=${originalRect.width()} height=${originalRect.height()}"
        )

        var target: AccessibilityNodeInfo? = node.parent
        for (depth in 1..MAX_PARENT_ASCENT) {
            val candidate = target ?: break
            val rect = Rect().also { candidate.getBoundsInScreen(it) }
            AppLog.write(
                this,
                "[부모 후보] depth=$depth text='${displayText(candidate)}' visible=${candidate.isVisibleToUser} enabled=${candidate.isEnabled} bounds=$rect"
            )

            if (candidate.isVisibleToUser && candidate.isEnabled &&
                !rect.isEmpty &&
                rect.width() >= MIN_NODE_SIZE_PX &&
                rect.height() >= MIN_NODE_SIZE_PX
            ) {
                if (!isRectOnScreen(rect)) {
                    AppLog.write(this, "[부모 후보 무시] 화면 밖 depth=$depth bounds=$rect")
                } else if (!isReasonableClickRect(rect)) {
                    // 부모로 갈수록 영역은 더 커지므로 여기서 중단한다.
                    AppLog.write(this, "[부모 후보 무시] 영역이 너무 커 잘못된 WebView 중앙 클릭 방지 depth=$depth bounds=$rect")
                    break
                } else {
                    return gestureClick(
                        rect.centerX().toFloat(),
                        rect.centerY().toFloat(),
                        "$why bounds=$rect parentDepth=$depth"
                    )
                }
            }
            target = candidate.parent
        }

        AppLog.write(this, "[후보 무시] 화면 안의 유효 클릭 영역을 확보하지 못함 text='$nodeText' original=$originalRect")
        return false
    }

    private fun isSafeVisibleNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return !rect.isEmpty &&
            rect.width() >= MIN_NODE_SIZE_PX &&
            rect.height() >= MIN_NODE_SIZE_PX &&
            isRectOnScreen(rect) &&
            isReasonableClickRect(rect)
    }

    private fun isRectOnScreen(rect: Rect): Boolean {
        val metrics = resources.displayMetrics
        val screen = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (!Rect.intersects(screen, rect)) return false
        val cx = rect.centerX()
        val cy = rect.centerY()
        return cx in 0 until metrics.widthPixels && cy in 0 until metrics.heightPixels
    }

    private fun isReasonableClickRect(rect: Rect): Boolean {
        val metrics = resources.displayMetrics
        // 버튼 컨테이너를 찾는 과정에서 전체 WebView/화면 컨테이너까지 올라가
        // 화면 중앙을 잘못 누르는 것을 차단한다.
        return rect.height() <= (metrics.heightPixels * MAX_CLICK_HEIGHT_RATIO).toInt()
    }

    private fun isReasonableSubtreeCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty || !isRectOnScreen(rect)) return false
        if (!isReasonableClickRect(rect)) return false
        return node.childCount in 1..MAX_SUBTREE_CHILDREN
    }

    private fun containsForbiddenWord(text: String): Boolean {
        val normalized = text.lowercase(Locale.KOREA)
        if (FORBIDDEN_WORDS.any { normalized.contains(it) }) return true
        return CURRENCY_REGEX.containsMatchIn(normalized)
    }

    private fun hasAny(root: AccessibilityNodeInfo, needles: List<String>): Boolean {
        return allNodes(root).any { node ->
            if (!node.isVisibleToUser) return@any false
            val text = searchableText(node)
            needles.any { text.contains(normalize(it)) }
        }
    }

    private fun allNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(160)

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || out.size >= 700) return
            out.add(node)
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        return out
    }

    private fun normalize(cs: CharSequence?): String =
        cs?.toString()
            ?.lowercase(Locale.KOREA)
            ?.replace(WHITESPACE_REGEX, "")
            .orEmpty()

    private fun searchableText(node: AccessibilityNodeInfo): String =
        normalize(node.text) + normalize(node.contentDescription)

    private fun displayText(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" | ")
            .trim()

    private fun compactSubtreeText(root: AccessibilityNodeInfo): String? {
        if (!root.isVisibleToUser) return null
        val parts = ArrayList<String>(MAX_SUBTREE_NODES)
        var visited = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_SUBTREE_DEPTH || visited >= MAX_SUBTREE_NODES) return
            visited++
            val own = searchableText(node)
            if (own.isNotEmpty()) parts.add(own)
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)
        if (parts.isEmpty()) return null
        val combined = parts.joinToString("")
        return combined.takeIf { it.length <= MAX_SUBTREE_TEXT_LENGTH }
    }

    private fun dumpCompact(root: AccessibilityNodeInfo) {
        if (System.currentTimeMillis() - lastDumpAt < 2500) return
        lastDumpAt = System.currentTimeMillis()

        val interesting = allNodes(root).mapNotNull { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isBlank() && desc.isBlank()) return@mapNotNull null

            val rect = Rect().also { node.getBoundsInScreen(it) }
            "[${node.viewIdResourceName ?: "-"}] t='$text' d='$desc' visible=${node.isVisibleToUser} enabled=${node.isEnabled} click=${node.isClickable} bounds=$rect"
        }.take(40)

        if (interesting.isNotEmpty()) {
            AppLog.write(this, "[화면 노드] ${interesting.joinToString(" | ")}")
        }
    }

    private fun scrollForward(root: AccessibilityNodeInfo?, where: String): Boolean {
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
                if (kotlin.math.abs(delta) >= MIN_SCROLL_DELTA_PX) {
                    AppLog.write(this, "[스크롤 검증] 실제 이동 확인: 출석 버튼 Y $previousY -> $currentY (delta=$delta)")
                } else {
                    AppLog.write(this, "[스크롤 검증] 실제 이동 없음: 출석 버튼 Y=$currentY (이전=$previousY)")
                }
            }
            lastAttendanceTargetY = currentY
            AppLog.write(
                this,
                "[출석 타깃] text='${displayText(target)}' class='${target.className}' id='${target.viewIdResourceName}' visible=${target.isVisibleToUser} bounds=$targetRect"
            )
        } else {
            AppLog.write(this, "[출석 타깃] 출석 버튼 노드를 찾지 못함")
        }

        // Galaxy Store 이벤트 페이지는 native ACTION_SCROLL_* 가 true를 반환해도
        // 실제 화면이 움직이지 않는 경우가 있다. CHECKIN 단계에서는 반환값을 절대 신뢰하지 않고
        // 수동 스크롤과 동일한 물리 터치 제스처만 사용한다.
        suppressEventRescheduleUntil = System.currentTimeMillis() + EVENT_SUPPRESS_MS

        val strategy = scrollStrategyIndex % PHYSICAL_SCROLL_STRATEGY_COUNT
        scrollStrategyIndex++

        val spec = when (strategy) {
            0 -> PhysicalScrollSpec(0.50f, 0.60f, 0.30f, 150L)
            1 -> PhysicalScrollSpec(0.25f, 0.64f, 0.28f, 160L)
            2 -> PhysicalScrollSpec(0.75f, 0.64f, 0.28f, 160L)
            3 -> PhysicalScrollSpec(0.50f, 0.72f, 0.24f, 170L)
            4 -> PhysicalScrollSpec(0.32f, 0.68f, 0.22f, 150L)
            else -> PhysicalScrollSpec(0.68f, 0.68f, 0.22f, 150L)
        }

        AppLog.write(
            this,
            "[출석 페이지 스크롤] 물리 전용 전략 ${strategy + 1}/$PHYSICAL_SCROLL_STRATEGY_COUNT " +
                "x=${(spec.xRatio * 100).toInt()}% y=${(spec.startRatio * 100).toInt()}→${(spec.endRatio * 100).toInt()}% ${spec.durationMs}ms"
        )

        return dispatchScrollGesture(
            root = root,
            where = "출석 페이지",
            xRatio = spec.xRatio,
            startRatio = spec.startRatio,
            endRatio = spec.endRatio,
            durationMs = spec.durationMs
        )
    }

    private data class PhysicalScrollSpec(
        val xRatio: Float,
        val startRatio: Float,
        val endRatio: Float,
        val durationMs: Long
    )

    private fun findBestAttendanceTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val metrics = resources.displayMetrics
        val candidates = allNodes(root).filter { node ->
            AttendanceMatchRules.isExactAttendanceAction(node.text, node.contentDescription)
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

    private fun gestureClick(x: Float, y: Float, why: String): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()

        val accepted = dispatchGesture(gesture, null, null)
        AppLog.write(
            this,
            "[제스처 전달] ${if (accepted) "accepted" else "rejected"}: X=${x.toInt()} Y=${y.toInt()} $why"
        )
        return accepted
    }

    private fun isCoordinateOnScreen(x: Int, y: Int): Boolean {
        val metrics = resources.displayMetrics
        return x in 0 until metrics.widthPixels && y in 0 until metrics.heightPixels
    }

    private fun isMonday(): Boolean =
        Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY

    private fun retry(reason: String) {
        if (System.currentTimeMillis() - lastActionAt > 3000) AppLog.write(this, reason)
        scheduleScan(850)
    }

    private fun scheduleScan(delay: Long) {
        lastActionAt = System.currentTimeMillis()
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delay)
    }

    private fun finish(success: Boolean, message: String) {
        AppLog.write(this, "${if (success) "성공" else "종료"}: $message")
        AppPrefs.prefs(this).edit().putBoolean(AppPrefs.KEY_PENDING_RUN, false).apply()
        handler.removeCallbacks(scanRunnable)
        runStartedAt = 0L
        stage = Stage.ENTRY
    }

    private enum class Stage { ENTRY, BANNER, CHECKIN, VERIFY }

    companion object {
        @Volatile private var instance: AttendanceAccessibilityService? = null
        @Volatile private var lastDumpAt: Long = 0L

        private const val RUN_TIMEOUT_MS = 60_000L
        private const val VERIFY_WAIT_MS = 7_000L
        private const val MAX_SCROLL = 12
        private const val MAX_ATTEND_CLICK = 2
        private const val MIN_NODE_SIZE_PX = 10
        private const val MAX_PARENT_ASCENT = 3
        private const val MAX_ACTION_ANCESTOR_DEPTH = 2
        private const val MAX_SUBTREE_DEPTH = 3
        private const val MAX_SUBTREE_NODES = 12
        private const val MAX_SUBTREE_TEXT_LENGTH = 80
        private const val MAX_SUBTREE_CHILDREN = 8
        private const val MAX_CLICK_HEIGHT_RATIO = 0.38f
        private const val SCROLL_SETTLE_MS = 900L
        private const val EVENT_SUPPRESS_MS = 750L
        private const val MIN_SCROLL_DELTA_PX = 24
        private const val MAX_SCROLL_ANCESTOR_DEPTH = 12
        private const val PHYSICAL_SCROLL_STRATEGY_COUNT = 6

        private val BENEFIT_TAB_KEYWORDS = listOf("혜택", "이벤트", "benefits")
        private val BANNER_KEYWORDS = listOf("위클리 출석체크", "출석체크", "출석 체크", "매일 출석", "출석 이벤트", "스탬프")
        private val SUCCESS_WORDS = listOf("출석 완료", "오늘 출석 완료", "출석했습니다", "출석 성공", "내일 또", "already checked", "checked in")

        private val FORBIDDEN_WORDS = listOf("구매", "결제", "구독", "주문", "카드", "₩")
        private val CURRENCY_REGEX = Regex("(?:^|\\s)\\d[\\d,]*\\s*원(?:\\s|$)")
        private val WHITESPACE_REGEX = Regex("\\s+")

        fun prepareRun() {
            instance?.beginRun()
        }

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AttendanceAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
