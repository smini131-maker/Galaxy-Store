# Galaxy Store 자동 출석 (Android / Kotlin)

Galaxy Store의 출석체크 화면을 매일 지정 시간에 열고 Android AccessibilityService로 UI 요소를 찾아 클릭하는 로컬 자동화 앱입니다.

## 현재 버전
**v1.3.0** — Galaxy Store GMP 가상 접근성 트리를 대상으로 실제 스크롤 이동을 검증하는 다단계 스크롤 엔진 버전입니다.

## 핵심 설계
- 대상: Samsung Galaxy S25 Ultra / Android 16 우선
- Kotlin, minSdk 31, target/compileSdk 36
- 정확한 알람 + 부팅 후 재등록
- Galaxy Store 패키지 `com.sec.android.app.samsungapps` 실행
- AccessibilityService 탐색 우선순위:
  1. viewId (`attendance`, `checkin`, `check_in`)
  2. 공백/줄바꿈을 제거한 text + contentDescription 매칭
  3. 실제 화면 안에 렌더링된 Bounds 검사
  4. 너무 작은 텍스트 노드면 최대 3단계 부모 컨테이너까지 확장
  5. 중앙 좌표 제스처 터치
  6. 사용자가 명시적으로 켠 경우만 최후 수단 좌표 fallback
- 잠금 화면에서는 15분 뒤 재시도
- 최근 접근성 노드를 앱 내부 로그에 기록해 Galaxy Store UI 변경 시 디버깅 가능

## v1.2.0 WebView 수정
- `출석 체크하기`, `출석체크하기`, 줄바꿈/공백이 섞인 표현을 동일하게 인식합니다.
- `text`와 `contentDescription`을 결합해서 WebView가 속성을 나눠 제공하는 경우도 탐색합니다.
- 화면 아래 Y 좌표 등 실제 디스플레이 밖의 WebView 유령 노드는 클릭하지 않습니다.
- 텍스트 노드의 크기가 0이거나 너무 작으면 최대 3단계 부모 노드까지 올라가 실제 버튼 컨테이너 중앙을 터치합니다.
- 스크롤 탐색을 최대 7회까지 수행합니다.
- 기존의 구매/결제/구독/주문/카드/금액 요소 안전 차단은 유지합니다.
- 제스처가 전달됐다는 이유만으로 성공 처리하지 않고, 실제 출석 완료 문구가 나타나야 성공으로 기록합니다.

## 처음 설치 후
1. 앱 실행 → 접근성 서비스 설정 → **Galaxy Store 자동 출석** 허용
2. 정확한 알람 권한 허용
3. 실행 시간 지정 → **매일 자동 출석 사용** 켜기 → 저장
4. `지금 1회 테스트 실행`으로 먼저 검증

## 중요한 제한
Galaxy Store는 업데이트로 UI 텍스트/viewId/화면 구조가 바뀔 수 있습니다. Android 보안 정책상 잠금 화면을 우회하지 않으며, 실행 시각에 기기가 잠겨 있으면 자동화를 미룹니다. Samsung/Galaxy Store 측 정책이나 UI 변경에 따라 자동화가 동작하지 않을 수 있습니다.

## v1.1.0 변경 이력
- 숨겨진 0x0/화면 밖 더미 `출석체크` 노드 무시
- 동일 문구의 모든 후보 노드 순회
- 혜택/이벤트 탭 → 출석 배너 → 출석 버튼 상태 머신 추가
- 월요일 약관 체크 처리
- 실제 완료 문구 검증 후 성공 기록

## v1.2.1 진단 강화
- WebView 후보를 찾을 때 텍스트/ContentDescription, visibility, enabled, bounds를 앱 내부 로그에 기록
- 화면 밖/숨김/너무 작은 후보 및 부모 승격 실패 이유를 구체적으로 기록
- 최대 스크롤 10회, 85%→15% 범위의 600ms 긴 스와이프로 WebView 스크롤 강화
- 이벤트 페이지 진입 후 로딩 대기 시간을 5초로 확대
- 분할된 `출석` + `체크하기` 형제 노드 결합 매칭 유지
- 전체 WebView 같은 지나치게 큰 부모를 클릭 대상으로 승격하지 않아 허공/중앙 오클릭 방지
- 출석 버튼 터치 후 성공 문구를 실제로 확인해야 성공 처리


## v1.2.2 WebView 스크롤 보강
- `isScrollable=true`인 WebView/ScrollView를 찾아 `ACTION_SCROLL_FORWARD`를 1순위로 수행합니다.
- 필요 시 `ACTION_SCROLL_DOWN`도 시도합니다.
- 네이티브 스크롤 실패 시 화면 높이 70% → 30%를 250ms로 스와이프합니다.
- 하단 내비게이션/삼성페이 영역과의 충돌 및 WebView 롱프레스 오인을 줄였습니다.
- 내부 로그에 스크롤 후보 클래스/Bounds, 네이티브 액션 성공 여부, 제스처 폴백 결과를 남깁니다.


## v1.2.3 강제 스크롤 보강
- 화면에 보이는 `android.webkit.WebView`를 `isScrollable` 값과 무관하게 직접 탐색합니다.
- 각 WebView에 `ACTION_SCROLL_FORWARD`, `ACTION_SCROLL_DOWN`을 순서대로 직접 전달합니다.
- 네이티브 액션이 실패하면 S25 Ultra의 상/하단 시스템 제스처 영역을 피한 화면 중앙 65% → 35% 구간을 200ms로 스와이프합니다.
- 기존 v1.2.2의 Bounds/유령 노드 차단, 분할 텍스트 결합, 성공 문구 검증은 유지합니다.
- 제안된 `0.95f` 하단 컷은 화면 안에 보이는 버튼까지 제외할 수 있어 적용하지 않고 기존 화면 교차/중심점 Bounds 검사를 유지합니다.


## v1.3.0 GMP 검증형 스크롤 엔진
- 실제 기기 로그에서 이벤트 페이지가 일반 WebView 클래스가 아니라 `gmp-*` 가상 접근성 노드로 구성되고, `출석체크하기` 노드가 `visible=false`, 화면 하단 경계에 0 높이로 존재하는 것을 확인했습니다.
- 더 이상 `android.webkit.WebView` 클래스명이나 `isScrollable` 값만 믿지 않습니다.
- 숨겨진 출석 버튼에 `ACTION_SHOW_ON_SCREEN`을 먼저 요청합니다.
- 버튼의 부모 체인을 최대 12단계까지 추적하여 실제 `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_DOWN`을 광고하는 컨테이너에 직접 스크롤 액션을 보냅니다.
- native action이 성공을 반환해도 그대로 믿지 않고 다음 스캔에서 출석 버튼 Y 좌표가 실제 이동했는지 비교합니다.
- 이동이 없으면 18% / 50% / 82% X 위치와 서로 다른 거리·속도의 물리 스와이프 전략으로 자동 승격합니다.
- 제스처는 `GestureResultCallback`으로 completed/cancelled 여부까지 기록합니다.
- 스크롤 직후 접근성 이벤트 폭주가 예약된 검증 스캔을 앞당기지 않도록 900ms 동안 이벤트 재예약을 억제합니다.
- 클릭/금지 단어/Bounds/성공 문구 검증 로직은 기존 그대로 유지합니다.
