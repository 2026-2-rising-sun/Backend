# Product 모듈

Product #333의 상품 표현 정보·전체 판매 제어·소프트 삭제와 거래 검증 경계를 설명한다. 기준은 `cc91c09b` 위의 이 워크트리 구현이며 배포/운영 DB 적용 결과는 아니다. SKU #334의 가격·재고·개별 상태 구현과 후속 Order 연결은 별도로 통합한다.

- [ERD 원본](ERD.md) · [ERD HTML](ERD.html)
- [상태 원본](상태관리도.md) · [상태 HTML](상태관리도.html)
- [실행 계획](../../../exec-plans/active/333-product-module/README.md) · [API 변경 조각](../../../exec-plans/active/333-product-module/api-changes.md)
- [정책과 대안](../../../adr/0333-product-sku-boundary.md) · [현재 서비스 ERD](../../services/commercecore/erd.md)

MD가 원본이고 HTML은 저장소 렌더러로 생성한다. 실제 테스트 결과와 환경·미실행 범위는 [작업 기록](../../../worklog/2026-11-13.md)을 따른다.
