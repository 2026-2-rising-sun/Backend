package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.commerce.sales.domain.SalesLookup;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 서비스에 판매정보를 제공하는 배치 조회 유스케이스.
 *
 * <p>가격·재고·판매상태의 원본은 Commerce 가 가지고 있고, Shopping 상품 목록·상세와 Live 방송
 * 상품 카드는 그 값을 빌려다 화면에 표시한다. 화면 하나에 상품이 수십 개씩 올라가므로 상품마다
 * 묻지 않고 한 번에 묻게 한다.
 *
 * <p><b>읽기 전용이다.</b> 재고를 잡거나 상태를 바꾸지 않는다. 호출한 쪽이 본 값은 그 순간의
 * 스냅샷일 뿐이고, 실제 구매 가능 여부는 주문 생성(주문 2)이 다시 검증한다. 그래서 같은 요청을
 * 몇 번 보내도 결과가 달라지지 않는다 — 호출하는 쪽이 timeout·5xx 에 그대로 재시도할 수 있다.
 */
@Service
public class SalesLookupService {

    /**
     * 한 번에 물어볼 수 있는 상품 수 상한.
     *
     * <p>상한이 없으면 쿼리 파라미터가 무한히 길어지고 {@code IN} 절도 같이 커진다. 부르는 쪽
     * (Shopping {@code HttpSalesInfoClient}, Live {@code HttpSalesClient}) 도 같은 값으로
     * 잘라서 보내므로 함께 바꿔야 한다.
     */
    public static final int MAX_PRODUCT_IDS = 100;

    private final SalesJpaRepository salesRepository;

    public SalesLookupService(SalesJpaRepository salesRepository) {
        this.salesRepository = salesRepository;
    }

    /**
     * 상품들의 판매정보를 한 번에 조회한다.
     *
     * <p>판매정보가 없는 상품은 <b>결과에서 빠진다.</b> 404 도 아니고 빈 원소도 아니다. 미등록과
     * 조회 실패를 구분해야 하기 때문이다 — 응답을 받았는데 목록에 없으면 "아직 판매정보가 없는
     * 상품", 응답 자체를 못 받았으면 "판매 상태를 알 수 없음" 이다. 둘을 같은 모양으로 돌려주면
     * 부르는 쪽이 장애를 품절로 표시하게 된다.
     *
     * @param productIds 조회할 상품 식별자. 중복은 여기서 제거한다
     * @return 판매정보가 있는 상품만 담긴 목록. 순서는 보장하지 않는다
     * @throws BusinessException 비었거나 {@value #MAX_PRODUCT_IDS} 개를 넘음 (400)
     */
    @Transactional(readOnly = true)
    public List<SalesLookup> findByProductIds(Collection<Long> productIds) {
        return salesRepository.findLookupsByProductIds(distinct(productIds));
    }

    /**
     * 중복을 제거하고 개수 제약을 확인한다.
     *
     * <p>같은 상품이 두 번 들어와도 거절하지 않는다. 방송 화면이 같은 상품을 여러 슬롯에 걸어둘
     * 수 있어서 부르는 쪽이 중복을 걸러내야만 하는 계약은 불편하다. 대신 조회 전에 합쳐서 결과에
     * 같은 상품이 두 번 나오지 않게 한다.
     */
    private static Set<Long> distinct(Collection<Long> productIds) {
        if (productIds == null) {
            throw invalidProductIds("productIds 는 필수입니다.");
        }

        Set<Long> unique = new LinkedHashSet<>();
        for (Long productId : productIds) {
            if (productId == null) {
                throw invalidProductIds("productIds 에 빈 값이 있습니다.");
            }
            unique.add(productId);
        }

        if (unique.isEmpty()) {
            throw invalidProductIds("productIds 는 1개 이상이어야 합니다.");
        }
        if (unique.size() > MAX_PRODUCT_IDS) {
            throw invalidProductIds(
                "productIds 는 최대 " + MAX_PRODUCT_IDS + "개입니다: " + unique.size());
        }
        return unique;
    }

    private static BusinessException invalidProductIds(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
