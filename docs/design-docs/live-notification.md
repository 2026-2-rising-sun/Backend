# 방송·알림 서비스 간 흐름

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](live-notification.html)

## 책임과 경계

알림 생성·조회 상태와 원본 방송·판매 상태를 구분한다. 알림 보존 기간 종료가 원본 데이터를 삭제하지 않는다. Outbox·Kafka·소비 DB·SSE의 각 단계를 따로 검증한다.

## 전이

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| Live 등록·변경 | 방송 이벤트 | 방송 일정·상태와 관련 이벤트를 기록한다. |
| 방송 이벤트 | Notification 일정 사본 | 버전·중복 조건으로 일정을 반영한다. |
| 사용자 신청 | Notification 구독 | 받아 둔 일정 사본이 있어야 신청할 수 있다. |
| 시작·예약 시점 | Notification 알림함 | 구독·이벤트 중복 조건과 발송 표시를 확인해 알림을 만든다. |
| Core 판매 이벤트 | 재입고 알림 | 판매 상태 사본의 전이를 판단해 신청 대상의 알림을 만든다. |
| Live 실시간 이벤트 | Redis·SSE | Live 인스턴스 간 채팅·방송 상태를 중계한다. Notification 알림함과 다른 전달 경로다. |

## 상세와 근거

- [Live 방송 상태](services/live/states/broadcast.md)
- [Live 프로모션 상태](services/live/states/promotion.md)
- [Notification 알림 읽음·보존](services/notification/states/inbox.md)
- [Notification 방송·재입고 구독](services/notification/states/subscription.md)

[공통 Outbox](services/common-module/outbox.md) · [이벤트 계약](../../contracts/events)
