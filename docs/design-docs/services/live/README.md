# Live

책임: 방송·방송 상품·프로모션·채팅·좋아요·실시간 전달.

상품·판매 원본은 Core, 회원 원본은 Member다. 방송 종료가 주문·재고·송출 종료를 직접 처리하는 것은 아니다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/live-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [방송 상태](states/broadcast.md) · [시각 자료](states/broadcast.html)
- [프로모션 상태](states/promotion.md) · [시각 자료](states/promotion.html)

## 원본·갱신

[구현](../../../../services/live-service/src/main/java) · [마이그레이션](../../../../services/live-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
