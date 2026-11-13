# Catalog

책임: 상품·판매·쿠폰 공개 조회 사본.

상품·판매·쿠폰 원본의 변경 주체는 CommerceCore다. 사본의 버전과 원본의 확정을 구분한다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/catalog-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [판매 사본·표시 상태](states/sales-view.md) · [시각 자료](states/sales-view.html)

## 원본·갱신

[구현](../../../../services/catalog-service/src/main/java) · [마이그레이션](../../../../services/catalog-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
