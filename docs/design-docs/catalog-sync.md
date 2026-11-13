# Catalog 사본 동기화 서비스 간 흐름

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](catalog-sync.html)

## 책임과 경계

Catalog는 조회 사본의 소유자이고 Core는 상품·거래 원본의 소유자다. replica와 HTTP 모드는 설정 경로다. 사본을 갱신했다고 PostgreSQL CDC나 실제 Kafka 운영 전달을 검증한 것은 아니다.

## 전이

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| Core 원본 변경 | Core Outbox | 상품·판매·쿠폰의 변경 내용과 버전을 기록한다. |
| Core Outbox | Kafka | 커밋 후 발행. ACK와 소비 DB 반영은 별도 단계다. |
| Kafka | Catalog 사본 | 소비한 버전이 현재보다 최신일 때 반영하고 중복·역순으로 되돌리지 않는다. |
| Core 판매 조회 | 판매 backfill | 없는 사본만 version 0으로 채운다. 이후 이벤트 버전이 우선한다. |
| 사용자 구매 진입 | 원본 확인 | 사본의 화면 표시와 실제 주문 시점의 판매·재고 확정을 구분한다. |

## 상세와 근거

- [Catalog 판매 사본·표시 상태](services/catalog/states/sales-view.md)
- [CommerceCore 판매 상태](services/commercecore/states/sales.md)

[공통 Outbox](services/common-module/outbox.md) · [이벤트 계약](../../contracts/events)
