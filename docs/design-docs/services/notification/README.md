# Notification

책임: 이벤트 기반 알림함·방송/재입고 구독·방송/판매/주문 조회 상태.

알림과 조회 사본은 거래 원본이 아니다. 회원 소유권·중복 이벤트·버전 조건을 소비 코드에서 확인한다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/notification-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [알림 읽음·보존](states/inbox.md) · [시각 자료](states/inbox.html)
- [방송·재입고 구독](states/subscription.md) · [시각 자료](states/subscription.html)

## 원본·갱신

[구현](../../../../services/notification-service/src/main/java) · [마이그레이션](../../../../services/notification-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
