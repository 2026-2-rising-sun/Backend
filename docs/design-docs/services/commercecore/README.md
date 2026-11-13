# CommerceCore

책임: 상품·이미지·판매·재고·쿠폰·장바구니·주문·결제/환불 접수와 결과 반영.

Payment 실행 원본과 분리한다. 회원 ID는 Member 식별자이며 서비스 간 FK는 없다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/commercecore-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [주문·결제 묶음](states/order.md) · [시각 자료](states/order.html)
- [결제 전달·결과 반영](states/payment.md) · [시각 자료](states/payment.html)
- [환불 접수·결과 반영](states/refund.md) · [시각 자료](states/refund.html)
- [판매 상태](states/sales.md) · [시각 자료](states/sales.html)

## 원본·갱신

[구현](../../../../services/commercecore-service/src/main/java) · [마이그레이션](../../../../services/commercecore-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
