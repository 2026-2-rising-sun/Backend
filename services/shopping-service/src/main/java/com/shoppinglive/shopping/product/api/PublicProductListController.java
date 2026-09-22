package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.application.ImageUrlResolver;
import com.shoppinglive.shopping.product.application.ProductListPosition;
import com.shoppinglive.shopping.product.application.PublicProductListService;
import com.shoppinglive.shopping.product.application.PublicProductPage;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 상품 목록 API (상품 5). 로그인·방송 여부와 무관하게 누구나 조회한다. 방송 연동은 필터가 아니라서
 * Live 를 호출하지 않는다. 판매정보를 확인하지 못하면 503 SALES_INFO_UNAVAILABLE.
 */
@RestController
@RequestMapping("/v1/products")
public class PublicProductListController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final PublicProductListService listService;
    private final ImageUrlResolver imageUrlResolver;

    public PublicProductListController(PublicProductListService listService, ImageUrlResolver imageUrlResolver) {
        this.listService = listService;
        this.imageUrlResolver = imageUrlResolver;
    }

    /**
     * 판매중·품절 상품을 최신 등록순으로 {@code size} 개까지. 다음 페이지는 응답의 {@code nextCursor} 를
     * {@code cursor} 로 넘긴다. 범위 밖 size·손상된 cursor 는 400 INVALID_REQUEST.
     */
    @GetMapping
    public ApiResponse<PublicProductListResponse> list(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", required = false) Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : size;
        if (pageSize < 1 || pageSize > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size 는 1~" + MAX_SIZE + " 이어야 합니다.");
        }
        ProductListPosition after = StringUtils.hasText(cursor) ? PublicProductCursor.decode(cursor) : null;
        PublicProductPage page = listService.list(after, pageSize);
        return ApiResponse.ok(new PublicProductListResponse(
                page.entries().stream().map(this::toItem).toList(),
                page.next() == null ? null : PublicProductCursor.encode(page.next()),
                page.hasNext()));
    }

    private PublicProductItemResponse toItem(PublicProductPage.Entry entry) {
        return new PublicProductItemResponse(entry.product().getId(), entry.product().getName(),
                imageUrlResolver.urlOf(entry.product().getMainImageId()), entry.salesInfo().price(),
                entry.status(), entry.status().isPurchasable() && entry.salesInfo().available() > 0);
    }
}
