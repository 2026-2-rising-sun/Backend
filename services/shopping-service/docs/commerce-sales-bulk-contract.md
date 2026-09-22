# Commerce 판매정보 벌크 조회 — Shopping 기대 계약

상품 목록·상세(#43~#45)는 상품마다 판매 상태·가격을 보여줘야 하고, 판매정보는 Commerce 가 소유한다.
상품마다 호출하면 N+1 이므로 여러 상품을 한 번에 묻는 API 가 필요하다.
**이 API 는 Commerce 팀이 구현한다.** Shopping 은 Commerce 코드를 수정·import 하지 않으며, 배포 전까지
`shopping.sales-client.mode=stub` 으로 동작한다.

## 요청

`GET /v1/sales?productIds=1,2,3` — 쉼표 구분 상품 id 1~**100** 개. 초과·형식 오류는 `400`.
중복 id 허용. 부작용 없는 조회여야 한다 (Shopping 은 timeout·5xx 에 같은 요청을 재시도한다).

## 응답

`200`, `ApiResponse` 로 감싸지 않은 JSON 배열.

```json
[{ "productId": 1, "salesId": 10, "price": 15000, "status": "ON_SALE", "available": 7 }]
```

| 필드 | 원본 | 의미 |
|---|---|---|
| `productId` | `sales_info.product_id` | 요청한 상품 id |
| `salesId` | `sales_info.id` | 판매정보 id |
| `price` | `sales_info.price` | 판매가 (원, ≥ 0) |
| `status` | `sales_info.status` | `READY` · `ON_SALE` · `SOLD_OUT` · `PRIVATE` |
| `available` | `sales_stock.available` | 주문 가능 재고 (예약분 제외, ≥ 0) |

- 모든 필드 필수. **판매정보가 없는 상품은 배열에서 빠진다** (404·`null` 원소 아님). 순서는 무의미.
- 필드 추가는 호환된다. 삭제·변경, 그리고 **새 status 값 추가는 사전 합의**가 필요하다.
  Shopping 은 모르는 값을 추측하지 않고 조회 실패로 처리한다.

## Shopping 의 해석

| status | 공개 노출 | 구매 |
|---|---|---|
| 배열에 없음 (`NOT_REGISTERED`) | X | X |
| `READY`, `PRIVATE` | X (관리자만) | X |
| `ON_SALE` | O | O |
| `SOLD_OUT` | O | X |

품절 판정은 `status` 를 따르고 `available` 로 다시 하지 않는다. 가격·재고 확정은 주문 시점에 Commerce 가 한다.

연결 실패·timeout·5xx·4xx·서킷 오픈·해석 불가 응답은 "판매 상태를 알 수 없음"(`UNKNOWN`)이다.
관리자 목록은 `UNKNOWN` 으로 표시하고 **품절로 표시하지 않는다.** 공개 목록·상세는 구매 가능 상태를
보여주지 않고 재시도를 안내한다.
