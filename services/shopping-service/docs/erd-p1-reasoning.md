# Shopping P1 ERD 설계 근거

## 소유 범위

| 서비스 | 소유 데이터 | 비고 |
|---|---|---|
| Shopping | 상품 기본정보(이름·설명·대표 이미지), 이미지 메타데이터·파일 | 이 문서 |
| Commerce | 판매정보(가격·판매 상태), 재고, 주문, 결제 | `sales_info.product_id` 로 Shopping 상품을 참조 (FK 없음) |

판매 상태는 재고와 한 트랜잭션에서 움직여야 하므로(재고 0 → `SOLD_OUT` 자동 전이, 주문 생성 시 상태·재고 동시 검증)
재고를 가진 Commerce 가 소유한다. Shopping 은 판매정보를 HTTP 로 **읽기만** 하고 복제하지 않는다.
서비스 간에는 DB·코드를 공유하지 않고 HTTP 계약으로만 연결한다.

```
product_image 1 ──< product           (Shopping DB)
                      │ id
                      ┆ (논리 참조, FK 아님)
                      ▼
                sales_info.product_id  (Commerce DB)
```

## product_image

- 파일은 `ImageStorage`(P1: 로컬 폴더, 운영: S3 예정)에 두고 DB 에는 서버가 만든 `storage_key` 만 저장한다.
- URL·파일 경로를 저장하지 않는다. URL 은 조회 시점에 설정(`public-base-url` 또는 추후 CDN)으로 만들어,
  저장소를 바꿔도 데이터 이관이 필요 없다.
- `original_filename` 은 표시용이다. 사용자가 준 파일명·경로를 저장 위치에 쓰지 않는다 (OWASP 파일 업로드 지침).
- `width`·`height`·`size_bytes` 는 검증을 통과한 실제 값이다 (0 이하 불가 CHECK).

## product

- `main_image_id NOT NULL FK` — 이미지 없는 상품을 만들 수 없어 "이미지 미등록 상품 비공개" 조건이 스키마로 보장된다.
- 이미지 교체는 이미 저장된 새 이미지로 `main_image_id` 만 바꾼다. 새 이미지 저장이나 연결 변경이 실패하면
  트랜잭션이 커밋되지 않아 기존 이미지가 유지된다. 기존 파일은 P1 에서 지우지 않는다 (고아 정리는 후속).
- `idempotency_key UNIQUE` — 등록 요청 재전송(`X-Idempotency-Key`)으로 상품이 중복 생성되지 않는다. NULL 은 여러 개 허용.
- `version` — 기본정보 수정 낙관적 락. 다른 수정이 먼저 반영되면 409 로 최신 내용을 확인하게 한다.
- `description VARCHAR(2000)` — `TEXT` 는 H2 에서 CLOB 로 해석되어 Hibernate validate 가 테스트에서 어긋나므로 VARCHAR 로 둔다.
- 판매 상태 컬럼이 없다. 공개 여부는 Commerce 판매정보로만 판정한다.

## 인덱스

- `ix_product_created_at_id (created_at DESC, id DESC)` — 공개 목록 최신 등록순 커서 조회.
  비공개 상품을 건너뛰며 여러 묶음을 읽으므로 offset 대신 `(created_at, id)` 커서를 쓴다.
- `ix_product_main_image_id` — 이미지 기준 역참조.
