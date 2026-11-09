package com.shoppinglive.commercecore.sales.service;

import com.shoppinglive.commercecore.product.exception.ProductNotFoundException;
import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.entity.SalesStatus;
import com.shoppinglive.commercecore.sales.entity.SalesStock;
import com.shoppinglive.commercecore.sales.event.SalesInfoEventTracker;
import com.shoppinglive.commercecore.sales.exception.SalesAlreadyRegisteredException;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.commercecore.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 상품 원본 존재를 로컬 조회한 뒤 판매정보와 재고를 함께 등록한다. */
@Service
public class SalesRegistrationService {

    private final SalesInfoEventTracker events;
    private final SalesJpaRepository salesRepository;
    private final SalesStockJpaRepository salesStockRepository;
    private final ProductLookup productLookup;
    private final TransactionTemplate transactionTemplate;

    public SalesRegistrationService(
            SalesJpaRepository salesRepository,
            SalesStockJpaRepository salesStockRepository,
            ProductLookup productLookup,
            TransactionTemplate transactionTemplate,
            SalesInfoEventTracker events) {
        this.events = events;
        this.salesRepository = salesRepository;
        this.salesStockRepository = salesStockRepository;
        this.productLookup = productLookup;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 상품에 판매정보를 최초로 등록한다.
     *
     * <p>순서:
     *
     * <ol>
     *   <li>입력값 검증 — 가격 양수, 재고 0 이상
     *   <li>중복 검사 — 이미 판매정보가 있으면 409. DB 호출이라 싸므로 상품 조회보다 먼저 한다
     *   <li>Core 상품 원본 존재 확인 — 없으면 404, 물어보지 못했으면 그대로 전파
     *   <li>판매정보 + 재고를 한 트랜잭션으로 저장
     * </ol>
     *
     * @param productId Core 상품 식별자
     * @param price 판매가 (원, 양수)
     * @param initialStock 초기 판매 가능 재고 (0 이상)
     * @return 저장된 판매정보 (상태는 {@code READY})
     * @throws BusinessException 가격·재고가 입력 제약을 벗어남 (400)
     * @throws ProductNotFoundException 상품 원본이 없음 (404)
     * @throws SalesAlreadyRegisteredException 이미 판매정보가 등록된 상품 (409)
     */
    public Sales register(Long productId, Long price, Integer initialStock) {
        validate(productId, price, initialStock);

        if (salesRepository.existsByProductId(productId)) {
            throw new SalesAlreadyRegisteredException(productId);
        }

        // 트랜잭션 밖. 확인에 실패하면 (예외 전파) 아무것도 저장하지 않는다.
        var product =
                productLookup
                        .findProduct(productId)
                        .orElseThrow(() -> new ProductNotFoundException(productId));
        return persist(productId, price, initialStock, product.name());
    }

    /**
     * 판매정보와 재고를 한 트랜잭션으로 저장한다.
     *
     * <p>{@code existsByProductId} 검사를 통과한 두 요청이 동시에 여기 도달할 수 있다. 그때는 {@code
     * uk_sales_info_product_id} UNIQUE 제약이 한쪽을 떨어뜨리고, 그 결과를 중복 등록과 같은 409 로 되돌린다. {@code
     * saveAndFlush} 로 INSERT 시점을 앞당겨야 제약 위반이 커밋이 아니라 이 블록 안에서 발생해 잡을 수 있다.
     */
    private Sales persist(Long productId, Long price, Integer initialStock, String productName) {
        try {
            return transactionTemplate.execute(
                    status -> {
                        Sales sales = new Sales(productId, price, SalesStatus.READY);
                        sales.snapshotProductName(productName);
                        Sales saved = salesRepository.saveAndFlush(sales);
                        salesStockRepository.save(new SalesStock(saved.getId(), initialStock, 0));
                        events.registered(saved.getId());
                        return saved;
                    });
        } catch (DataIntegrityViolationException e) {
            throw new SalesAlreadyRegisteredException(productId, e);
        }
    }

    private void validate(Long productId, Long price, Integer initialStock) {
        if (productId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "productId 는 필수입니다.");
        }
        if (price == null || price <= 0L) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "가격은 양의 정수여야 합니다: " + price);
        }
        if (initialStock == null || initialStock < 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "초기 재고는 0 이상이어야 합니다: " + initialStock);
        }
    }
}
