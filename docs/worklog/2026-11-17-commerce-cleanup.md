# 2026-11-17 CommerceCore 코드 정리

## 범위와 환경

이슈 #362. origin/dev 2ef0a6c8에서 refactor/#362-commerce-cleanup 브랜치와 `/Users/junje/orca/workspaces/Backend/commerce-cleanup` Orca 워크스페이스를 만들었다. 원래 문서 작업 폴더와 미커밋 변경은 건드리지 않았다. [실행 계획](plans/completed/362-commerce-cleanup/README.md).

Cart 응답 중첩, Order 생성 단계, SKU 처리 순서와 빈 반복문, 오래된 필드 이름, 완전한 패키지명과 미사용 telemetry 의존성을 정리한다. API·이벤트·DB 계약과 Payment 소유 코드는 변경하지 않는다.

## 회귀 확인

OrderPaymentOutcomeServiceTest는 주문 ID와 SKU ID가 반대 순서인 결과를 사용한다. 실제 재고 변경을 정렬하지 않은 기존 반복문으로 실행하면 성공 소진과 실패 해제 2건 모두 VerificationInOrderFailure로 실패했다. 순서 보완 후 최초 실행은 2건 통과했다. 이는 호출 순서 회귀이며 PostgreSQL 교착을 직접 재현한 증거가 아니다. 실패 로그와 XML은 `/tmp/core362-order-red.log`, `/tmp/core362-order-red.xml`에 보존했다.

## 검증 진행

Java 21, 작업 전용 core362-postgres와 core362-kafka만 사용한다. PostgreSQL에는 Core와 독립 Payment fixture DB를 따로 만든다. 공유 kind와 다른 작업의 인프라는 변경하지 않는다. 로컬 전체 검사와 원격 CI 결과는 완료 후 추가한다.

## 로컬 일반 검사

`JAVA_HOME=/opt/homebrew/opt/openjdk@21`에서 Core build를 실행했다. 단위 176건과 일반 통합 312건은 실패·오류·스킵 0건이다. 일반 통합에는 전용 외부 Kafka를 이용한 기존 조건부 2건이 포함된다. PostgreSQL 전용 업무 검사와 같은 증거로 합산하지 않는다. 로그는 `/tmp/core362-build.log`다.

문서 169개 링크 검사, API HTML 6개·설계 HTML 30개 최신성, 이벤트 계약 8개·예시 23개·실제 직렬화 표본 23개가 통과했다. 포맷 검사와 `git diff --check`도 통과했다. 이번 변경은 API·이벤트·DB 스키마를 바꾸지 않는다. 현재 기준 생성기는 worklog를 자동 탐색하지 않으므로 동일 render 함수로 기록·계획 HTML을 만들고 문서 지도 링크를 실제 위치로 맞췄다.

## 정적 리뷰

Cart는 응답 구성만 분리했고 회원·SKU 조회, 누락 데이터 표시 및 순서를 유지했다. 주문서 검증·fingerprint 입력과 순서, Cart→Product→SKU 잠금, 금액 계산, DB 시각·600초, 항목 저장과 예약·쿠폰·Outbox는 같은 기존 트랜잭션 안에 있다. 기본 SKU 조회로 전환된 서비스 이름과 import만 정리했고 미사용 telemetry 주입 및 중복 owned 조회를 제거했다. 기존 결제 결과는 실제 상태 변경·재고 명령이 SKU 순서로 실행되도록 바꿨다. 교착을 실제 DB에서 재현했다고 주장하지 않는다.

## PostgreSQL 결과와 전달

작업 전용 PostgreSQL 16.15에서 Core 업무 검사 196건이 실패·오류·스킵 0건으로 통과했다(`/tmp/core362-postgres.log`). Core와 독립 Payment fixture DB를 분리했으며 기존 주문·장바구니·상품·SKU·기한·쿠폰/환불 업무 회귀를 실행했다. 단위 176 + 일반 통합 312 + PostgreSQL 업무 196 = 684건이며 마이그레이션은 변경하지 않아 별도 이행 검사는 실행하지 않았다. 실제 PG와 운영 환경, PostgreSQL 교착 직접 재현은 미실행이다.

PR [#363](https://github.com/2026-2-rising-sun/Backend/pull/363)은 dev를 기준으로 작성했다. 작성 직전 작성 가이드의 쉬운 설명·PR 확인 절을 읽었고 게시된 제목·본문과 head af125ee5를 다시 조회했다. "가독성 정리"만이 아니라 기존 결과의 실제 SKU 처리 순서 변경을 첫 문단에 썼으며, 2건 회귀의 변경 전 실패와 실제 교착 미재현을 구분했다. PostgreSQL 완료 뒤 대기 문구를 최종 로컬 결과로 갱신한다. 현재 미해결 리뷰 스레드는 0개다.

구현·로컬 검증 계획은 completed로 옮겼다. 최종 원격 CI 및 dev 병합 여부는 PR의 해당 head 검사와 merge 기록에서 확인하며, 이 기록을 병합 완료 증거로 대신하지 않는다. 사용자에게 병합 커밋과 최종 CI를 확인한 뒤 보고한다.
