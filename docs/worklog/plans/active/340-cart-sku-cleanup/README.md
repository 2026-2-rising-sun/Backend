# #340 결제 성공 후 구매 SKU 장바구니 정리

상태: 로컬 구현·검증 완료, 외부 FigJam 반영 잔여

[이슈](https://github.com/2026-2-rising-sun/Backend/issues/340) · [Cart 설계](../../../../design-docs/services/commercecore/cart/overview.md) · [ADR 후속 결정](../../../../adr/0332-cart-sku-boundary.md) · [실행 기록](../../../2026-11-18-cart-sku-cleanup.md)

## 범위와 기준

2026-11-18 사용자 지시로 별도 Orca Chat 워크스페이스에서 로컬 구현·테스트·문서만 수행한다. 사용자 확인 모델/추론은 GPT-6.1-Sol / medium이다. 브랜치 `fix/#340-cart-sku-cleanup`, 시작 HEAD와 fetch한 origin/dev는 `32cc00d53646b906a41de3295a59754d3cfc45a3`, 초기 clean이다. 원본 dirty 체크아웃은 수정하지 않는다. 최초 요청은 커밋·푸시·PR·병합을 제외했으나, 같은 날 사용자가 검증 후 푸시·PR·병합까지 후속 승인했다. 배포·이슈 편집·외부 FigJam 쓰기 및 추가 에이전트 실행은 계속 제외한다.

## 수용 기준과 진행

- [x] 구 묶음·과거 단건·새 relay의 실제 성공 경로 추적과 회원+SKU 삭제 적용.
- [x] Cart 출처 항목만 정리, 출처 ID/version과 생성 검증·선택 환불 계약 보존.
- [x] 선택 SKU·수량 변경·삭제 재추가 삭제, 같은 상품 다른 SKU·다른 회원 보존 검증.
- [x] 직접 구매·실패·취소·만료·늦은 승인 보존, 성공 중복 후 재담기 보존 검증.
- [x] PostgreSQL 행 잠금과 상태·재고·쿠폰·Cart·알림 롤백 및 재시도 검증.
- [x] 설계·API 설명·HTML 갱신과 프로젝트 검사·최종 diff 확인.
- [ ] 외부 FigJam Cart 영역 반영: 이번 쓰기 권한에서 제외되어 잔여. 이슈 완료로 표시하지 않는다.

필수 외부 반영이 남아 계획을 active에 유지한다. 로컬 결과·실패·스킵과 실제 PG/운영 미검증은 실행 기록에서 구분한다.
