from pathlib import Path
import re

service = Path('app/src/main/java/com/smini/galaxyattendance/AttendanceAccessibilityService.kt')
s = service.read_text()

s = s.replace(
    '자동 탐색 세션 시작 (GMP 검증형 스크롤 v1.3.0)',
    '자동 탐색 세션 시작 (물리 스와이프 전용 v1.3.1)'
)

# Replace only the attendance-page scroll function. Native scroll helpers remain for the
# benefits screen, but CHECKIN never trusts performAction() return values anymore.
new_func = r'''    private fun scrollAttendancePage(root: AccessibilityNodeInfo): Boolean {
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
'''

pattern = r'    private fun scrollAttendancePage\(root: AccessibilityNodeInfo\): Boolean \{.*?\n    private fun findBestAttendanceTarget'
s2, count = re.subn(pattern, new_func + '\n    private fun findBestAttendanceTarget', s, flags=re.S)
if count != 1:
    raise SystemExit(f'scrollAttendancePage replacement count={count}')
s = s2

s = s.replace('private const val SCROLL_SETTLE_MS = 1_150L', 'private const val SCROLL_SETTLE_MS = 900L')
s = s.replace('private const val EVENT_SUPPRESS_MS = 900L', 'private const val EVENT_SUPPRESS_MS = 750L')
s = s.replace('private const val SCROLL_STRATEGY_COUNT = 6', 'private const val PHYSICAL_SCROLL_STRATEGY_COUNT = 6')
service.write_text(s)

gradle = Path('app/build.gradle.kts')
g = gradle.read_text()
g = g.replace('versionCode = 7', 'versionCode = 8')
g = g.replace('versionName = "1.3.0"', 'versionName = "1.3.1"')
gradle.write_text(g)

readme = Path('README.md')
r = readme.read_text()
r = r.replace(
    '**v1.3.0** — Galaxy Store GMP 가상 접근성 트리를 대상으로 실제 스크롤 이동을 검증하는 다단계 스크롤 엔진 버전입니다.',
    '**v1.3.1** — 출석 이벤트 페이지에서 네이티브 스크롤 반환값을 완전히 무시하고 물리 스와이프만 사용하는 검증형 버전입니다.'
)
if '## v1.3.1 물리 스와이프 전용' not in r:
    r += '''\n\n## v1.3.1 물리 스와이프 전용\n- 실제 로그에서 `출석체크하기`가 `visible=false`, `Rect(0,3064-1441,3064)`의 높이 0 노드로 확인되었습니다.\n- 사용자가 수동으로 스크롤하면 이후 클릭이 성공하므로 클릭 로직은 유지하고 CHECKIN 스크롤 경로만 교체했습니다.\n- 출석 페이지에서는 `ACTION_SCROLL_FORWARD`, `ACTION_SCROLL_DOWN`, `ACTION_SHOW_ON_SCREEN`의 반환값을 제어 판단에 사용하지 않습니다.\n- 첫 시도부터 `dispatchGesture` 물리 스와이프를 강제합니다. 기본 전략은 화면 중앙 X=50%, Y=60%→30%, 150ms입니다.\n- 첫 스와이프가 실제 이동을 만들지 못하면 X=25%/75% 및 더 긴 스와이프 범위로 자동 변경합니다.\n- 다음 스캔에서 숨겨진 출석 버튼 Y 좌표의 실제 변화량을 비교하여 화면이 움직였는지 검증합니다.\n- 제스처 콜백 completed/cancelled와 dispatch accepted/rejected를 AppLog에 남깁니다.\n- 접근성 이벤트가 제스처 직후 검증 타이밍을 깨뜨리지 않도록 750ms 동안 재예약을 억제합니다.\n'''
readme.write_text(r)
