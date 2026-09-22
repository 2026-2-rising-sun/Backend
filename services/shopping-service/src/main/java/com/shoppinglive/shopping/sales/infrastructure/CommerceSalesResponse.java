package com.shoppinglive.shopping.sales.infrastructure;

/**
 * {@code GET /v1/sales} 응답 원소 (HTTP 계약 그대로). status 는 문자열로 받아 모르는 값을 역직렬화가 아닌
 * 매핑 단계에서 명확한 오류로 드러내고, 숫자는 누락을 잡으려고 래퍼 타입을 쓴다.
 */
record CommerceSalesResponse(Long productId, Long salesId, Long price, String status, Integer available) {
}
