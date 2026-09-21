# 서비스 간 상품 조회 API

Commerce(`HttpShoppingClient`: 주문 상품 스냅샷)와 Live(방송 상품 표시)가 Shopping 상품을 조회하는 API.
판매·공개 상태와 무관하게 존재하는 상품이면 내려준다 (판매 1 은 판매정보가 아직 없는 상품을 대상으로 한다).

## 경로

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/v1/internal/products/{id}` | 단건. 없으면 `404` |
| GET | `/v1/internal/products?ids=1,2,3` | 벌크. 쉼표 구분 1~100개 (중복 제거 후), 초과·형식 오류는 `400` |

## 응답

공통 `ApiResponse` 로 감싼다. 호출 측은 `data` 를 읽는다.

```json
{ "success": true, "data": { "id": 1, "name": "상품A", "mainImageUrl": "http://localhost:8082/v1/product-images/3" }, "error": null }
```

- 벌크는 `data` 가 같은 모양의 배열이다. **없는 id 는 빠지고** 요청 순서를 따른다 (404 아님).
- `mainImageUrl` 은 조회 시점에 만든 URL 이다. 저장소(로컬 → S3/CDN)가 바뀌면 달라지므로 받는 쪽은 표시용으로만 쓴다.
- URL 형태는 `shopping.image.public-base-url` 설정을 따른다. local 은 위 예시처럼 절대 URL, dev 프로필 기본값은 Ingress 기준 상대 경로(`/api/shopping/v1/product-images/3`)다. 다른 호스트의 화면에서 쓰려면 `SHOPPING_IMAGE_PUBLIC_BASE_URL` 에 절대 URL 을 설정한다.
  주문 스냅샷에 보존하는 것은 괜찮지만, 그 URL 이 계속 유효하다고 가정하지 않는다.

## 오류

| 상태 | `error.code` | 호출 측 해석 |
|---|---|---|
| 404 | `NOT_FOUND` | 상품 없음 (Commerce `ShoppingClient`: `Optional.empty()`) |
| 400 | `INVALID_REQUEST` | 요청 오류. 재시도해도 같다 |
| 5xx·timeout | — | 일시 장애. 재시도 가능 (`ShoppingUnavailableException`) |

## 노출 범위

`/v1/internal/**` 는 클러스터 내부 호출 전용이라 인증이 없다. **Ingress 로 외부에 노출하지 않도록** 라우팅에서
제외해야 한다 (Infra 작업).
