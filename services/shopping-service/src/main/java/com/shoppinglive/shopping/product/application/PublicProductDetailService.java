package com.shoppinglive.shopping.product.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.shopping.image.application.ImageUrlResolver;
import com.shoppinglive.shopping.product.domain.Product;
import com.shoppinglive.shopping.product.infrastructure.ProductRepository;
import com.shoppinglive.shopping.sales.application.SalesInfoClient;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import com.shoppinglive.shopping.sales.domain.SalesDisplayStatus;
import com.shoppinglive.shopping.sales.domain.SalesInfo;
import org.springframework.stereotype.Service;

/**
 * 사용자용 공개 상품 상세. 목록·직접 URL·방송 어디서 들어와도 같은 상품 id 로 이 조회를 거쳐 같은 결과를 본다.
 * 읽기 전용이며 재고를 차감·예약하지 않는다.
 */
@Service
public class PublicProductDetailService {

    private final ProductRepository productRepository;
    private final SalesInfoClient salesInfoClient;
    private final ImageUrlResolver imageUrlResolver;

    public PublicProductDetailService(ProductRepository productRepository, SalesInfoClient salesInfoClient,
            ImageUrlResolver imageUrlResolver) {
        this.productRepository = productRepository;
        this.salesInfoClient = salesInfoClient;
        this.imageUrlResolver = imageUrlResolver;
    }

    /**
     * 상품이 없을 때와 판매정보가 없거나 비공개(READY·PRIVATE)일 때 같은 404 를 준다. 응답을 나누면
     * 비공개 상품의 존재가 드러나기 때문이다.
     *
     * @throws BusinessException NOT_FOUND — 없거나 공개 대상이 아님
     * @throws SalesInfoUnavailableException 판매정보 조회 실패 (503, 구매를 막고 재시도 안내)
     */
    public PublicProductDetail getDetail(Long productId) {
        Product product = productRepository.findById(productId).orElseThrow(() -> notFound(productId));
        SalesInfo salesInfo = salesInfoClient.findByProductId(productId).orElse(null);
        SalesDisplayStatus status = SalesDisplayStatus.from(salesInfo);
        if (!status.isPubliclyVisible()) {
            throw notFound(productId);
        }
        return new PublicProductDetail(product, imageUrlResolver.urlOf(product.getMainImageId()), salesInfo, status);
    }

    /**
     * 주문 화면으로 넘어가기 전 수량 사전 확인. 부작용이 없고 재고를 예약·차감하지 않는다. 최종 가격·재고 검증은
     * 주문 생성 시 Commerce 가 하므로, 여기서 통과해도 주문이 실패할 수 있다. 공개 여부 규칙은
     * {@link #getDetail(Long)} 과 같다 (404/503).
     *
     * @param quantity 1 이상 (형식 검증은 호출 측 책임)
     */
    public PurchaseCheck checkPurchase(Long productId, int quantity) {
        return PurchaseCheck.of(getDetail(productId), quantity);
    }

    private static BusinessException notFound(Long productId) {
        return new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다: " + productId);
    }
}
