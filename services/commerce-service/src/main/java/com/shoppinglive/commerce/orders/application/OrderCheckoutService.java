package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.sales.application.SalesNotFoundException;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.application.ProductNotFoundException;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문서 확인 유스케이스 (주문 1).
 *
 * <p>일반 상품 상세와 방송 상품 어느 쪽에서 들어와도 같은 주문서를 만든다. 상품명은 Shopping
 * 에서, 가격·재고·판매 상태는 Commerce 에서 가져와 조합한다.
 *
 * <p><b>읽기 전용이다.</b> 주문서를 열어보는 것만으로 재고가 줄면, 결제까지 가지 않고 이탈한
 * 사람들 때문에 팔 수 있는 물건이 묶인다. 재고 확보는 주문 2 가 실제로 주문을 만들 때만 한다.
 */
@Service
public class OrderCheckoutService {

    private final SalesJpaRepository salesRepository;
    private final SalesStockJpaRepository salesStockRepository;
    private final ShoppingClient shoppingClient;

    public OrderCheckoutService(
        SalesJpaRepository salesRepository,
        SalesStockJpaRepository salesStockRepository,
        ShoppingClient shoppingClient) {
        this.salesRepository = salesRepository;
        this.salesStockRepository = salesStockRepository;
        this.shoppingClient = shoppingClient;
    }

    /**
     * 주문서에 표시할 정보를 조합한다.
     *
     * <p>판매정보가 아예 없는 상품은 판매 대상이 아니므로 404 로 거절한다. 판매정보는 있으나
     * 지금 살 수 없는 상태(비공개·품절·재고 부족)는 404 가 아니라 200 + 사유로 안내한다. 구매자는
     * 방금까지 상품 페이지를 보고 있었으므로, "없는 페이지" 가 아니라 "왜 못 사는지" 를 알려주는
     * 편이 맞다.
     *
     * @param productId Shopping 상품 식별자
     * @param quantity 확인할 수량 (1 이상)
     * @return 주문서 스냅샷. 주문 불가여도 예외 대신 사유를 담아 돌려준다
     * @throws BusinessException 수량이 1 미만 (400)
     * @throws SalesNotFoundException 판매정보가 등록되지 않은 상품 (404)
     * @throws ProductNotFoundException Shopping 에 상품이 없음 (404)
     * @throws com.shoppinglive.commerce.shopping.application.ShoppingUnavailableException
     *     Shopping 일시 장애로 상품명을 확인하지 못함 (503)
     */
    @Transactional(readOnly = true)
    public OrderCheckout preview(Long productId, int quantity) {
        if (productId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "productId 는 필수입니다.");
        }
        if (quantity < 1) {
            throw new BusinessException(
                ErrorCode.INVALID_REQUEST, "수량은 1 이상이어야 합니다: " + quantity);
        }

        Sales sales = salesRepository.findByProductId(productId)
            .orElseThrow(() -> new SalesNotFoundException(
                "sales not found for product: productId=" + productId));

        SalesStock stock = salesStockRepository.findById(sales.getId())
            .orElseThrow(() -> new SalesNotFoundException(
                "sales stock not found: salesId=" + sales.getId()));

        // 상품명 없이 주문서를 그리면 무엇을 사는지 모르는 화면이 된다. Shopping 장애는 그대로
        // 전파해 503 으로 재시도를 안내하고, 확인된 "없음" 만 404 로 구분한다.
        ProductSnapshot product = shoppingClient.findProduct(productId)
            .orElseThrow(() -> new ProductNotFoundException(productId));

        return OrderCheckout.of(
            productId,
            sales.getId(),
            product.name(),
            sales.getPrice(),
            quantity,
            stock.getAvailable(),
            sales.getStatus());
    }
}
