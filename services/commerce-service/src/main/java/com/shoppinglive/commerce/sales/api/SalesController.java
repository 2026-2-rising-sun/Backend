package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.domain.Sales;
import jakarta.validation.Valid;
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
     * 판매정보의 가격을 변경한다.
     *
     * @param id 판매정보 식별자
     * @param request 변경할 가격 (양의 정수 원 단위)
     * @return 변경 반영된 판매정보 스냅샷
     */
    @PatchMapping("/{id}/price")
    public SalesResponse changePrice(
        @PathVariable Long id,
        @Valid @RequestBody ChangePriceRequest request
    ) {
        Sales updated = salesService.changePrice(id, request.price());
        return SalesResponse.from(updated);
    }
}
