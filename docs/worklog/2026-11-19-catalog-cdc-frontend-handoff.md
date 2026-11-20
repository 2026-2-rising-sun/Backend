# Catalog 지연 재고의 Frontend 인계

## 범위와 확인 기준

Backend B는 Frontend를 읽기만 했다. Frontend 파일 변경·이슈 생성·브라우저/E2E 실행은 하지 않았다. 이 문서는 후속 Frontend 구현자가 새 Catalog 응답 의미를 적용할 때 필요한 인계이며, 백엔드만으로 현재 화면의 false rejection이 해결됐다는 보고가 아니다.

확인한 체크아웃은 `/Users/junje/Projects/shoppinglive/Frontend`, 브랜치 `dev`, HEAD `890b475db6cf38da53b13bc7dac0953413d97f16`이며 확인 당시 미커밋 변경이 없었다. 아래 링크는 해당 SHA에 고정한다. Backend 기준은 `3537340c4686fec3b962da8ed02d368829d639af` 위의 B 미커밋 구현, 원본 API는 [Catalog YAML](../../contracts/api/catalog-service.yaml) 2.1.0이다.

## 현재 화면이 먼저 거절하는 지점

| 근거 | 현재 조건 | 지연 사본에서 생기는 결과 |
|---|---|---|
| [ProductDetailPage:41](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/features/shopping/ProductDetailPage.tsx#L41) | buyable = status==='SELLING' && stock!==null && stock>0 | 사본0/Core양수이거나 사본의 SOLD_OUT이 늦으면 Core에 묻기 전 진입을 막음 |
| [ProductDetailPage:54](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/features/shopping/ProductDetailPage.tsx#L54) | addCart가 !buyable이면 반환 | 같은 사본 조건으로 장바구니 진입도 막음 |
| [ProductDetailPage:75](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/features/shopping/ProductDetailPage.tsx#L75) | QuantityStepper max=product.stock??1, disabled=!buyable | 사본2/Core20에서5개를 선택하지 못함 |
| [ProductDetailPage:81](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/features/shopping/ProductDetailPage.tsx#L81) | buyable이 아니면 비활성 버튼과 품절 표시 | 서버의 purchasable=true를 받아도 stock/status 조건 때문에 구매 링크를 만들지 않음 |
| [QuantityStepper:17/37](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/components/ui/QuantityStepper.tsx#L17) | value>=max이면 증가 버튼 비활성 | 사본 재고를 max로 전달하는 호출자가 false rejection의 원인 |
| [mappers:4/10](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/api/http/mappers.ts#L4) | SOLD_OUT은 SOLD_OUT, available은 stock, purchasable은 전달. maxQuantity는 ProductDto/매핑에 없음 | 새로운 진입 가능 표시와 입력 상한을 화면이 분리해 사용하지 못함 |
| [LiveProductItem:12](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/features/live/components/LiveProductItem.tsx#L12) | status!=='SELLING'이면 soldOut | 같은 상품 상세 진입의 사본 상태 차단을 후속 점검해야 함. Live의 별도 원본·판매 정책을 이번 B가 바꾸지는 않음 |

[api/http/index.ts:50/51](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/api/http/index.ts#L50)는 Catalog 상세와 purchase-check 어댑터를 제공한다. `rg 'products\.check\(' src`로 확인한 현재 src에는 purchase-check 호출자가 없었다. 따라서 purchase-check 서버 변경만으로 현재 상세 화면을 고칠 수 없다.

## 새 Catalog 응답 계약

새 필드를 추가하지 않고 기존 필드의 의미를 명시적으로 변경했다. 목록·상세의 available/salesStatus와 Core의 주문 승인 사이 경계를 유지한다.

| 필드 | 새 의미 | 금지할 사용 |
|---|---|---|
| available | 기본60초 배치 이후 전파되는 표시 재고 사본 | 입력 수량 상한·예약·구매 확정의 근거 |
| salesStatus | 표시 상태. SKU 판매의도 ON_SALE+사본available0이면 SOLD_OUT으로 표시 | SOLD_OUT 사본만으로 최신 Core 재고 확인 진입을 영구 차단 |
| purchasable | 공개 판매 상품이며 상품 전체 판매가 허용됐을 때 Core 확인 화면 진입 가능. SOLD_OUT/available0 사본이어도 true일 수 있음 | 재고 예약 또는 구매 승인 완료로 해석 |
| 상세 maxQuantity | 입력 형식 상한999999999. 상품 전체 중지이면0 | 실제 남은 재고·판매량으로 표시 |
| purchase-check orderable | 공개 상품의 전체 판매 허용에 따른 Core 확인 진입 가능. 사본 수량 부족만으로 false가 되지 않음 | 결제·주문 생성 성공 보장 |
| purchase-check reason | 전체 중지는 SALE_SUSPENDED, 허용이면null. SOLD_OUT/EXCEEDS_STOCK enum은 구 응답 호환용으로 남지만 새 사본 검사는 반환하지 않음 | 구 enum이 존재한다는 이유로 사본 재고 거절을 다시 구현 |
| purchase-check maxQuantity | 허용 시999999999, 전체 중지 시0 | 요청 수량을 available로 잘라 전송 |

삭제/비공개는 기존404, 사본 미완성·기존 단일값 API로 읽을 수 없는 다중 SKU는503 경계를 유지한다. 품절 표시와 진입 허용은 다를 수 있다. 실제 가격·판매 가능량·경쟁 예약은 Core의 checkout과 최종 주문 생성에서 검증한다.

## 필요한 후속 Frontend 변경

1. Product DTO/도메인/매핑에서 상세 maxQuantity를 입력 형식 상한으로 전달하고 stock의 설명을 지연 사본으로 고친다. `purchasable`이 없거나 응답이 불완전하면 임의 true로 간주하지 말고 계약 오류/재조회로 처리한다.
2. ProductDetail의 buyable·장바구니·바로 구매 조건은 서버의 진입 가능 표시와 응답 완전성을 사용한다. 공개 SOLD_OUT/available0 사본만으로 Core 확인을 막지 않는다. 판매 중지/삭제/비공개는 계속 막는다.
3. QuantityStepper의 max는 사본 stock 대신 입력 형식 상한을 사용한다. 선택한 수량을 available로 축소하지 않고 Core checkout에 전달한다.
4. [CheckoutPage:29](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/features/commerce/CheckoutPage.tsx#L29)의 Core checkout과 [같은 파일:53](https://github.com/2026-2-rising-sun/Frontend/blob/890b475db6cf38da53b13bc7dac0953413d97f16/src/features/commerce/CheckoutPage.tsx#L53)의 최신 quote/orderable 검사, orderSheets.create의 최종 서버 검증을 유지한다. 이미 Core가 반환한 재고·오류를 Catalog 사본으로 덮어쓰지 않는다.
5. LiveProductItem 등 다른 진입점은 자기 서버 응답의 의미를 대조한 뒤 같은 차단이 있는지 점검한다. Catalog 변경을 Live 업무 정책 변경 허가로 확대하지 않는다.

## 후속 수용 시나리오

| 상황 | Frontend 기대 동작 | 서버 최종 결과 |
|---|---|---|
| Catalog 사본0/SOLD_OUT, Core available20, 전체 판매 허용 | 상세에서 Core 확인으로 진입하고5개 선택을 허용 | Core checkout·주문 생성이 최신20 기준으로5개 검증/예약 |
| Catalog 사본2, Core available20,5개 구매 | Stepper가2에서 막거나5를2로 줄이지 않고5개를 전송 | Core가5개를 검증/예약 |
| Catalog 사본20, Core available2,5개 구매 | 진입 허용이 구매 성공을 보장하지 않음을 처리 | Core checkout 또는 최종 생성이 수량 부족을 거절. UI는 성공/결제 화면으로 진행하지 않음 |
| Catalog 사본20, Core available0 또는 동시 예약으로 소진 | Core 거절/충돌을 표시하고 최신 데이터를 다시 확인 | 음수 재고·중복 예약 없이 거절 |
| 상품 전체 판매 중지 | purchasable=false/orderable=false/SALE_SUSPENDED 또는0상한을 유지 | Core도 신규 주문 거절 |
| 삭제/비공개/미완성 다중 SKU |404/503을 성공이나 재고0으로 바꾸지 않음 | 기존 노출·단일값 계약 경계 유지 |

위 시나리오는 Frontend 후속 수용 기준이며 이 작업에서 실행한 브라우저/E2E 결과가 아니다. Backend에서는 사본0과 수량 초과에 대한 Catalog HTTP 회귀를 실행했다. 코드·DB·Kafka 검증 결과는 [B 실행 기록](2026-11-19-catalog-cdc-sync.md)을 따른다.
