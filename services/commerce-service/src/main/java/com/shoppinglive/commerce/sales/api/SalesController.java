package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.application.SalesRegistrationService;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final SalesRegistrationService salesRegistrationService;

    public SalesController(
        SalesService salesService, SalesRegistrationService salesRegistrationService) {
        this.salesService = salesService;
        this.salesRegistrationService = salesRegistrationService;
    }

    /**
     * 상품에 판매정보를 최초로 등록한다 (판매 1).
     *
     * <p>등록 직후 상태는 판매 준비({@code READY})라 아직 공개·주문 대상이 아니다. 판매를
     * 시작하려면 이어서 판매 4 의 상태 변경을 호출한다.
     *
     * <p>상품 없음은 404, 이미 판매정보가 있는 상품은 409, 가격·재고 입력 제약 위반은 400.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SalesResponse register(@Valid @RequestBody RegisterSalesRequest request) {
        Sales registered = salesRegistrationService.register(
            request.productId(), request.price(), request.initialStock());
        return SalesResponse.from(registered);
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

    /**
     * 판매 상태를 변경한다 (판매 4).
     *
     * <p>관리자는 {@code ON_SALE} 또는 {@code PRIVATE} 만 지정 가능. 잘못된 값·전이 규칙 위반은
     * 400, 조회 실패는 404, 동시 변경 감지는 409.
     */
    @PatchMapping("/{id}/status")
    public SalesResponse changeStatus(
        @PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request) {
        Sales updated = salesService.changeStatus(id, request.status());
        return SalesResponse.from(updated);
    }
}
