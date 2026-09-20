package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매 도메인 REST 컨트롤러.
 *
 * <p>컨트롤러 경로는 {@code /v1/...} 로 시작한다. Infra 의 k8s Ingress 가
 * {@code /api/commerce/} prefix 를 벗겨낸 뒤 넘겨주므로 여기서 prefix 를 붙이지 않는다.
 */
@RestController
@RequestMapping("/v1/sales")
public class SalesController {

    private final SalesService salesService;

    public SalesController(SalesService salesService) {
        this.salesService = salesService;
    }

    /**
     * 판매정보의 가격을 변경한다 (판매 2).
     */
    @PatchMapping("/{id}/price")
    public SalesResponse changePrice(
        @PathVariable Long id, @Valid @RequestBody ChangePriceRequest request) {
        Sales updated = salesService.changePrice(id, request.price());
        return SalesResponse.from(updated);
    }

    /**
     * 판매 재고를 조회한다 (판매 3).
     */
    @GetMapping("/{id}/stock")
    public SalesStockResponse getStock(@PathVariable Long id) {
        SalesStock stock = salesService.getStock(id);
        return SalesStockResponse.from(stock);
    }

    /**
     * 판매 재고 available 을 delta 만큼 조정한다 (판매 3, 관리자 대상).
     *
     * <p>delta 양수: 추가. 음수: 감소. 감소 후 available 이 음수가 되면 409 Conflict.
     */
    @PatchMapping("/{id}/stock")
    public SalesStockResponse adjustStock(
        @PathVariable Long id, @Valid @RequestBody AdjustStockRequest request) {
        SalesStock updated = salesService.adjustAvailable(id, request.delta());
        return SalesStockResponse.from(updated);
    }
}
