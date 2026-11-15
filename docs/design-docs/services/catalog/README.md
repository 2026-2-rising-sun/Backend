# Catalog

책임: 상품·판매·쿠폰 공개 조회 사본.

상품·판매·쿠폰 원본의 변경 주체는 CommerceCore다. 사본의 버전과 원본의 확정을 구분한다.

기준: Product V2·SKU V3와 단일 SKU 공개 호환 모델. 통합 감사 기준은 [Order `5048413b`](https://github.com/2026-2-rising-sun/Backend/tree/5048413b26337cbdc84e258347750d1870f763a8)이다. 해당 후행 브랜치에는 Product·SKU·Cart·Order 연결이 존재한다. 이 SKU PR은 Product 위에 쌓인 모듈 체크포인트이며 Order를 역병합하지 않는다. 최종 통합 검사·배포 완료를 이 문서의 모듈 검사와 혼동하지 않는다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/catalog-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [판매 사본·표시 상태](states/sales-view.md) · [시각 자료](states/sales-view.html)

## 원본·갱신

[구현](../../../../services/catalog-service/src/main/java) · [마이그레이션](../../../../services/catalog-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.

현재 수정·환경별 검사와 미답변 정책은 [2026-11-15 SKU 기록](../../../worklog/2026-11-15-sku.md)을 따른다.
