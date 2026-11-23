# Core 결제 결과 소비의 DLT 처리

<!-- visual: relations -->

기준: #389. Core 결제 결과 소비자 두 개(`payment.execution.results.v1`, `payment.refund.results.v1`)에 같은 오류 처리기를 쓴다. 공통 구현은 [ConsumerErrorHandlers](../../../common-module/src/main/java/com/shoppinglive/common/kafka/ConsumerErrorHandlers.java)이고 Payment 환불 소비자와 같은 규칙이다. Catalog·Notification 소비자는 이번에 바꾸지 않았다.

## 분류와 처리

| 실패 | 처리 |
|---|---|
| DB 연결 장애·timeout | offset을 넘기지 않고 1초 간격으로 복구될 때까지 재시도한다. DLT로 보내지 않는다. |
| 역직렬화 실패·계약 위반 | 재시도 없이 `{원본 토픽}.commercecore.dlt`로 보낸다. |
| 그 밖의 실패(모르는 주문 포함) | 1초부터 간격을 늘려 5번 재시도한 뒤 DLT로 보낸다. |
| DLT 전송 실패 | 원본 offset을 넘기지 않는다. 같은 메시지를 다시 처리하고 다시 보낸다. |

DLT partition은 -1로 두어 Kafka가 key로 고른다. 로그에는 `EVENT_SENT_TO_DLT`와 토픽·partition·offset·원인·원문을 남기고, DLT 헤더에는 `kafka_dlt-original-topic`, `kafka_dlt-original-partition`, `kafka_dlt-original-offset`, `kafka_dlt-exception-message`가 붙는다.

## 배포 전제

- DLT 토픽(`payment.execution.results.v1.commercecore.dlt`, `payment.refund.results.v1.commercecore.dlt`)은 [Infra #64](https://github.com/2026-2-rising-sun/Infra/issues/64) 범위다. 이 변경은 토픽을 만들지 않는다.
- 토픽이 없으면 DLT 전송이 실패해 해당 partition이 멈춘다. 배포 전에 토픽을 만들고 consumer lag를 확인한다.

## 운영 절차

1. **원인 확인**: 로그의 `EVENT_SENT_TO_DLT`로 DLT 건을 찾고, DLT 헤더의 원본 위치와 예외 메시지로 원인을 본다.
2. **대량(DB 장애 후 누적, 같은 원인의 다수 건)**: 소비자 그룹의 원본 토픽 offset을 장애 시점으로 되감는다. 되감은 뒤 같은 결과가 두 번 반영되지 않아야 한다. 아래 "중복 안전성"을 확인한다.
3. **단건**: Kafka UI에서 DLT 메시지를 원본 토픽에 key·value를 그대로 두고 다시 발행한다.
4. **대상 없는 메시지**(주문·환불 요청이 없는 결과 등): 원본 토픽·partition·offset, 판단 근거, 폐기 시각을 워크로그에 남기고 DLT에서 폐기한다. 근거 없는 폐기는 하지 않는다.

## 중복 안전성

되감기와 재발행은 같은 결과를 다시 받게 한다. 결제 승인은 `paymentId`로, 환불 결과는 환불 요청 ID로 중복을 걸러 한 번만 반영한다. 근거 테스트는 `ResultConsumerRewindKafkaPostgresTest`다. EmbeddedKafka와 격리 PostgreSQL에서 실제 승인·환불 결과 소비자를 띄워 같은 결과를 두 번씩 보낸 뒤, 두 소비자 그룹(`commercecore-payment-results-v1`, `commercecore-common-refund-results-v1`)의 offset을 AdminClient로 처음으로 되감고 다시 시작한다. 되감기 전후의 주문 상태, 환불 상태, 재고(available/reserved), 환불 intent 수, 발행된 `RefundRequested` outbox 수가 같아야 하며, 두 그룹이 토픽 끝까지 따라잡아야 통과한다.

실행 명령: `set -a && source <test-db.env> && set +a && ./gradlew :services:commercecore-service:postgresTest --tests '*ResultConsumerRewindKafkaPostgresTest*'`

한계: 쿠폰 사용 수는 이 테스트에서 검증하지 않는다. 배포 전 대량 되감기는 운영 토픽 길이와 그룹 lag에 따라 시간이 걸리므로 격리 환경에서 먼저 확인한다.

[시각 자료](kafka-dlt.html) · [주문·결제](order-payment.md) · [환불](refund.md)
