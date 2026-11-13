# Payment

책임: 결제·환불 실행과 Mock 제공자 결과·복구·결과 Outbox.

공개 브라우저 API는 Core가 소유한다. 내부 ServiceToken 계약을 사용한다. 실제 PG 운영 연동은 이번 문서의 증거가 아니다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/payment-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [결제 실행](states/payment.md) · [시각 자료](states/payment.html)
- [환불 실행](states/refund.md) · [시각 자료](states/refund.html)

## 원본·갱신

[구현](../../../../services/payment-service/src/main/java) · [마이그레이션](../../../../services/payment-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
