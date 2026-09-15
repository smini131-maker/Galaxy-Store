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
        AppLog.write(this, "자동 탐색 세션 시작 (WebView 강제 스크롤 v1.2.3)")
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

        if (findAndGestureClick(root, ATTEND_KEYWORDS, includeViewId = true)) {
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

        if (findAndGestureClick(root, ATTEND_KEYWORDS, includeViewId = true)) {
            attendanceClickCount++
            stage = Stage.VERIFY
            verifyStartedAt = System.currentTimeMillis()
            return scheduleScan(1900)
        }

        if (scrollCount < MAX_SCROLL && scrollForward(root, "출석 페이지")) {
            scrollCount++
            AppLog.write(this, "출석 페이지 스크롤 ${scrollCount}/$MAX_SCROLL")
            return scheduleScan(2400)
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

        if (attendanceClickCount < MAX_ATTEND_CLICK && findAndGestureClick(root, ATTEND_KEYWORDS, includeViewId = true)) {
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
        AppLog.write(this, "[$where 스크롤] 강제 스크롤 시도")

        // 1순위: WebView 자체에 직접 접근성 스크롤 액션을 전달한다.
        // 일부 Galaxy Store WebView는 isScrollable=false로 노출돼도 액션을 받을 수 있어
        // isScrollable 조건 없이 보이는 WebView를 모두 확인한다.
        if (root != null) {
            val webViews = allNodes(root).filter { node ->
                val className = node.className?.toString().orEmpty()
                if (className != "android.webkit.WebView" || !node.isVisibleToUser || !node.isEnabled) {
                    return@filter false
                }
                val rect = Rect().also { node.getBoundsInScreen(it) }
                !rect.isEmpty && isRectOnScreen(rect)
            }

            for ((index, webView) in webViews.withIndex()) {
                val rect = Rect().also { webView.getBoundsInScreen(it) }
                AppLog.write(
                    this,
                    "[$where 스크롤] WebView 후보 ${index + 1}/${webViews.size} bounds=$rect scrollable=${webView.isScrollable}"
                )

                val forward = webView.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AppLog.write(
                    this,
                    "[$where 스크롤] WebView ACTION_SCROLL_FORWARD=${if (forward) "success" else "failed"}"
                )
                if (forward) return true

                val down = webView.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                )
                AppLog.write(
                    this,
                    "[$where 스크롤] WebView ACTION_SCROLL_DOWN=${if (down) "success" else "failed"}"
                )
                if (down) return true
            }

            if (webViews.isEmpty()) {
                AppLog.write(this, "[$where 스크롤] 화면 안의 WebView 노드를 찾지 못함")
            }
        }

        // 2순위: S25 Ultra의 상/하단 시스템 제스처 영역을 피한 중앙 안전 구역에서
        // 짧고 명확한 물리 스와이프를 보낸다.
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * SCROLL_START_RATIO
        val endY = metrics.heightPixels * SCROLL_END_RATIO

        AppLog.write(
            this,
            "[$where 스크롤] 중앙 제스처 폴백 X=${x.toInt()} Y=${startY.toInt()}→${endY.toInt()} duration=${SCROLL_DURATION_MS}ms"
        )

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, SCROLL_DURATION_MS))
            .build()

        val accepted = dispatchGesture(gesture, null, null)
        AppLog.write(
            this,
            "[$where 스크롤] 중앙 제스처 dispatch=${if (accepted) "accepted" else "rejected"}"
        )
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
        private const val MAX_SCROLL = 10
        private const val MAX_ATTEND_CLICK = 2
        private const val MIN_NODE_SIZE_PX = 10
        private const val MAX_PARENT_ASCENT = 3
        private const val MAX_SUBTREE_DEPTH = 3
        private const val MAX_SUBTREE_NODES = 12
        private const val MAX_SUBTREE_TEXT_LENGTH = 80
        private const val MAX_SUBTREE_CHILDREN = 8
        private const val MAX_CLICK_HEIGHT_RATIO = 0.38f
        private const val SCROLL_DURATION_MS = 200L
        private const val SCROLL_START_RATIO = 0.65f
        private const val SCROLL_END_RATIO = 0.35f

        private val BENEFIT_TAB_KEYWORDS = listOf("혜택", "이벤트", "benefits")
        private val BANNER_KEYWORDS = listOf("위클리 출석체크", "출석체크", "출석 체크", "매일 출석", "출석 이벤트", "스탬프")
        private val ATTEND_KEYWORDS = listOf("출석 체크하기", "출석체크하기", "출석 체크", "오늘 출석", "출석하기", "스탬프 찍기", "참여하기", "체크인")
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
