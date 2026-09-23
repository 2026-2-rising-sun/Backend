package com.shoppinglive.commerce.sales.api;

import com.shoppinglive.commerce.sales.application.SalesLookupService;
import com.shoppinglive.commerce.sales.application.SalesRegistrationService;
import com.shoppinglive.commerce.sales.application.SalesService;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final SalesLookupService salesLookupService;

    public SalesController(
        SalesService salesService,
        SalesRegistrationService salesRegistrationService,
        SalesLookupService salesLookupService) {
        this.salesService = salesService;
        this.salesRegistrationService = salesRegistrationService;
        this.salesLookupService = salesLookupService;
    }

    /**
     * 여러 상품의 판매정보를 한 번에 조회한다 (서비스 간 배치 조회).
     *
     * <p>Shopping 상품 목록·상세와 Live 방송 상품 카드가 가격·판매상태·재고를 표시하려고 부른다.
     * 상품마다 단건으로 물으면 N+1 호출이 되므로 한 번에 받는다.
     *
     * <p><b>응답을 {@code ApiResponse} 로 감싸지 않는다.</b> 부르는 두 서비스가 모두 봉투 없는
     * 배열로 읽도록 구현돼 있어서, 감싸면 양쪽이 동시에 깨진다. 이 서비스의 다른 조회 API 도
     * 봉투를 쓰지 않으므로 커머스 안에서도 일관된다.
     *
     * <p>판매정보가 없는 상품은 <b>배열에서 빠진다.</b> 404 로 전체를 실패시키지 않는다 — 상품
     * 50개 중 하나가 미등록이라고 목록 화면 전체가 못 뜨면 안 된다.
     *
     * <p>파라미터는 두 가지 형태를 모두 받는다. Shopping 은 {@code ?productIds=1,2,3} 으로,
     * Live 는 {@code ?productIds=1&productIds=2} 로 보낸다. 둘 중 하나만 받으면 한쪽이 못 쓴다.
     *
     * @param productIds 상품 식별자 1~{@value SalesLookupService#MAX_PRODUCT_IDS} 개. 중복 허용
     * @return 판매정보가 있는 상품만 담긴 배열. 순서는 보장하지 않는다
     * @throws BusinessException 파라미터 누락·숫자 아님·개수 초과 (400)
     */
    @GetMapping
    public List<SalesLookupResponse> findByProductIds(
        @RequestParam(name = "productIds", required = false) List<String> productIds) {
        return salesLookupService.findByProductIds(parseProductIds(productIds)).stream()
            .map(SalesLookupResponse::from)
            .toList();
    }

    /**
     * 쿼리 파라미터를 상품 식별자로 해석한다.
     *
     * <p>{@code List<Long>} 으로 바로 바인딩하지 않고 문자열로 받아 직접 파싱하는 이유: 바인딩
     * 단계에서 터지는 변환 예외는 어느 값이 잘못됐는지 응답에 담기 어렵다. 여기서 해석하면
     * "productIds 는 숫자여야 합니다: abc" 처럼 원인을 그대로 돌려줄 수 있다.
     */
    private static List<Long> parseProductIds(List<String> productIds) {
        if (productIds == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "productIds 는 필수입니다.");
        }

        List<Long> parsed = new ArrayList<>(productIds.size());
        for (String token : productIds) {
            if (token == null || token.isBlank()) {
                continue;
            }
            try {
                parsed.add(Long.parseLong(token.strip()));
            } catch (NumberFormatException e) {
                throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "productIds 는 숫자여야 합니다: " + token, e);
            }
        }
        return parsed;
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
