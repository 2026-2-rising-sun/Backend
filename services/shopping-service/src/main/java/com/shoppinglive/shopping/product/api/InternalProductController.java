package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.product.application.ProductQueryService;
import com.shoppinglive.shopping.product.application.ProductSnapshot;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 상품 조회 API (docs/internal-product-api.md). 클러스터 내부 호출용이라 Ingress 로 외부에 노출하지 않는다.
 */
@RestController
@RequestMapping("/v1/internal/products")
public class InternalProductController {

    static final int MAX_IDS = 100;

    private final ProductQueryService productQueryService;

    public InternalProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    /**
     * 벌크 조회 {@code ?ids=1,2,3}. 중복을 제거한 뒤 1~{@value #MAX_IDS} 개여야 한다. 없는 상품은 결과에서 빠진다.
     * 파라미터 누락·형식 오류를 스프링에 맡기면 공통 핸들러가 500 을 주므로 직접 해석한다.
     */
    @GetMapping
    public ApiResponse<List<ProductSnapshot>> getAll(@RequestParam(name = "ids", required = false) String ids) {
        return ApiResponse.ok(productQueryService.findSnapshots(parseIds(ids)));
    }

    /** 없으면 404 NOT_FOUND. */
    @GetMapping("/{productId}")
    public ApiResponse<ProductSnapshot> get(@PathVariable Long productId) {
        return ApiResponse.ok(productQueryService.getSnapshot(productId));
    }

    private static Set<Long> parseIds(String ids) {
        Set<Long> parsed = new LinkedHashSet<>();
        try {
            for (String token : (ids == null ? "" : ids).split(",")) {
                if (!token.isBlank()) {
                    parsed.add(Long.parseLong(token.strip()));
                }
            }
        } catch (NumberFormatException e) {
            throw invalidIds();
        }
        if (parsed.isEmpty() || parsed.size() > MAX_IDS) {
            throw invalidIds();
        }
        return parsed;
    }

    private static BusinessException invalidIds() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "ids 는 쉼표로 구분한 상품 id 1~" + MAX_IDS + "개여야 합니다.");
    }
}
