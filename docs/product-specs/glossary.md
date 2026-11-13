# 공통 업무 용어

| 용어 | 의미·소유권 |
|---|---|
| 상품 product | Core가 소유하는 상품 원본. Catalog의 같은 ID는 조회 사본이다. |
| 판매 sales_info | 가격·판매 상태·재고의 기준. 상품 ID와 판매 ID를 구분한다. |
| 결제 묶음 payment_group | Core의 여러 주문과 결제·쿠폰 정책 단위. Notification의 같은 이름 테이블은 알림용 사본이다. |
| 결제 전달 request | Core가 저장하는 재전달 가능한 원문과 결과 사본. |
| 결제 실행 payment_attempt | Payment가 소유하는 제공자 실행·결과·복구 원본. |
| 환불 접수 / 실행 | Core의 대상·기간·정책과 Payment의 실제 제공자 실행을 구분한다. |
| UNKNOWN | 결과 미확정. 확정 실패·품절·미등록으로 바꾸어 해석하지 않는다. |
| Outbox | 업무 트랜잭션에 저장한 발행 요청. ACK·소비 반영까지의 단일 트랜잭션이 아니다. |
| snapshotVersion | 조회 사본 갱신 순서 판정에 사용하는 원본 버전. DB 낙관 잠금 version과 문맥을 구분한다. |
| refresh family | Member의 갱신 토큰 묶음. 일반 폐기와 보안 폐기가 접근 세션에 미치는 영향이 다르다. |

[모듈 문서](../design-docs/services/README.md)와 실제 계약·구현에서 해당 용어를 함께 확인한다.
