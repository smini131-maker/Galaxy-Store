# Galaxy Store 자동 출석 (Android / Kotlin)

Galaxy Store의 출석체크 화면을 매일 지정 시간에 열고 Android AccessibilityService로 UI 요소를 찾아 클릭하는 로컬 자동화 앱입니다.

## 핵심 설계
- 대상: Samsung Galaxy S25 Ultra / Android 16 우선
- Kotlin, minSdk 31, target/compileSdk 36
- 정확한 알람 + 부팅 후 재등록
- Galaxy Store 패키지 `com.sec.android.app.samsungapps` 실행
- AccessibilityService 탐색 우선순위:
  1. viewId (`attendance`, `checkin`, `check_in`)
  2. 정확 텍스트
  3. 부분 텍스트
  4. contentDescription
  5. 화면에 실제로 보이는 노드만 Bounds Check 후 중앙 제스처 터치
  6. 사용자가 명시적으로 켠 경우만 최후 수단 좌표 제스처
- 잠금 화면에서는 15분 뒤 재시도
- 최근 접근성 노드를 앱 내부 로그에 기록해 Galaxy Store UI 변경 시 디버깅 가능

## 처음 설치 후
1. 앱 실행 → 접근성 서비스 설정 → **Galaxy Store 자동 출석** 허용
2. 정확한 알람 권한 허용
3. 실행 시간 지정 → **매일 자동 출석 사용** 켜기 → 저장
4. `지금 1회 테스트 실행`으로 먼저 검증

## 중요한 제한
Galaxy Store는 업데이트로 UI 텍스트/viewId/화면 구조가 바뀔 수 있습니다. 또한 Android 보안 정책상 잠금 화면을 우회할 수 없으므로, 실행 시각에 기기가 잠겨 있으면 자동화를 미룹니다. Samsung/Galaxy Store에서 자동화 행위를 제한하는 정책이 적용될 경우 정상 동작하지 않을 수 있습니다.

## v1.1.0 수정
- 숨겨진 0x0/화면 밖 더미 `출석체크` 노드를 무시하도록 `isVisibleToUser` + 화면 경계 + 최소 15px Bounds Check 추가
- 같은 문구의 모든 후보 노드를 순회해 실제로 보이는 후보가 나올 때까지 탐색
- 혜택/이벤트 탭 → 출석 배너 → 출석 버튼 상태 머신과 최대 5회 스크롤 추가
- 월요일 약관 체크박스 처리 추가
- 구매/결제/구독/주문/카드/금액 형태 요소 터치 방지
- 클릭 직후 성공 처리하지 않고 완료 문구를 실제 확인해야 성공으로 기록
