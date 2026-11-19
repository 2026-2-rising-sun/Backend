# 실행 계획

여러 단계·의존성·불확실성이 있는 작업은 `active/<이슈>-<주제>/README.md`에서 목표·범위·진행 상태·검증·관련 자료를 연결한다. 내용이 필요한 판단·조사 자료만 같은 폴더에 추가한다.

진행 작업: [#337 결제 재설계](active/337-payment-redesign/README.md) · [#331 감사 보완·범위 정정](active/331-order-audit-fixes/README.md) · [#333 Product](active/333-product-module/README.md) · [#334 SKU](active/334-sku-module/README.md) · [#332 Cart](active/332-cart-module/README.md). 각 모듈 독립 구현과 공유 통합 검증을 구분한다.

완료 작업: [문서 구조·하네스 정리](completed/document-layout/README.md) · [#331 Order 이전 범위 역사 기록](completed/331-order-module/README.md) · [#330 문서 하네스](completed/330-document-harness/README.md)

완료 기준을 확인한 뒤 작업 폴더를 `completed/`로 옮기고 링크를 갱신한다. 남은 과제는 [기술부채](tech-debt.md)와 후속 이슈로 연결한다. 진행 중인 필수 작업을 완료 처리하지 않는다.

Order 별도 후속 [#345 동시 환불](https://github.com/2026-2-rising-sun/Backend/issues/345)과 이전 [#346 보류 작업 공간](https://github.com/2026-2-rising-sun/Backend/issues/346)은 이번에 재개하거나 병합하지 않는다. 현재 Core의 준비/승인 기한 분리 구현은 [최신 계획](active/331-order-audit-fixes/processing-deadline.md)에 기록하며, 보류 작업의 전체 승인·운영 검증과 구분한다.

사용자는 CommerceCore 구현 범위의 dev 병합을 승인했다. 실제 SDK/PG·환불 실행·UNKNOWN 중단 통지·과거 미확정 거래 대사는 서비스 공동 후속이며 이번 Core 병합의 선행조건으로 확대하지 않는다. 전체 후속 계획은 active에 남기고 completed331은 이전 범위의 역사 기록으로 유지한다.

- [#340 구매 SKU 장바구니 정리](active/340-cart-sku-cleanup/README.md): 로컬 구현·검증과 외부 FigJam 잔여.

- [#371 SKU 일반 버전 제거](completed/371-sku-version/README.md): 구현·계약·실제 DB/브로커 로컬 검증 완료.

- [#372 통합 상품 등록](completed/372-product-registration/README.md): 로컬 구현·검증 완료. DB 원자성·멱등·파일 복구와 Catalog 부분 수신 보호.
