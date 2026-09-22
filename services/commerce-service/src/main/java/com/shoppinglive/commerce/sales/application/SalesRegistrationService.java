package com.shoppinglive.commerce.sales.application;

import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.application.ProductNotFoundException;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매정보 최초 설정 유스케이스 (판매 1).
 *
 * <p>Shopping 에 등록된 상품에 "얼마에, 몇 개를 파는지" 를 처음 붙인다. 등록 결과는 판매 준비
 * ({@link SalesStatus#READY}) 상태이며 아직 공개·주문 대상이 아니다. 실제 판매 시작은 판매 4 의
 * 상태 전이가 담당한다.
 *
 * <p><b>왜 {@code SalesService} 와 분리했나:</b> 이 유스케이스만 Shopping 서비스를 호출한다.
 * 가격 변경·재고 조정·상태 전이는 모두 Commerce DB 안에서 끝나는 일이라, 네트워크 의존을 한
 * 클래스에 가둬 두면 나머지 판매 유스케이스가 외부 장애에 영향받지 않는다.
 *
 * <p><b>트랜잭션 경계:</b> Shopping 호출은 트랜잭션 <i>밖</i>에서 한다 (ERD 설계 문서 §6.1 의
 * 원칙). 화요일에 stub 이 실제 HTTP 구현체로 바뀌면 이 호출은 timeout 까지 수 초가 걸릴 수 있고,
 * 그동안 DB 커넥션을 붙잡고 있으면 커넥션 풀이 마른다. 저장만 {@link TransactionTemplate} 으로
 * 묶어 판매정보와 재고가 함께 커밋되거나 함께 롤백되게 한다. 메서드에 {@code @Transactional} 을
 * 걸고 내부 메서드를 호출하는 방식은 self-invocation 이라 프록시를 타지 않으므로 쓰지 않는다.
 */
@Service
public class SalesRegistrationService {

    private final SalesJpaRepository salesRepository;
    private final SalesStockJpaRepository salesStockRepository;
    private final ShoppingClient shoppingClient;
    private final TransactionTemplate transactionTemplate;

    public SalesRegistrationService(
        SalesJpaRepository salesRepository,
        SalesStockJpaRepository salesStockRepository,
        ShoppingClient shoppingClient,
        TransactionTemplate transactionTemplate) {
        this.salesRepository = salesRepository;
        this.salesStockRepository = salesStockRepository;
        this.shoppingClient = shoppingClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 상품에 판매정보를 최초로 등록한다.
     *
     * <p>순서:
     * <ol>
     *   <li>입력값 검증 — 가격 양수, 재고 0 이상</li>
     *   <li>중복 검사 — 이미 판매정보가 있으면 409. DB 호출이라 싸므로 Shopping 호출보다 먼저 한다</li>
     *   <li>Shopping 에 상품 존재 확인 — 없으면 404, 물어보지 못했으면 그대로 전파</li>
     *   <li>판매정보 + 재고를 한 트랜잭션으로 저장</li>
     * </ol>
     *
     * @param productId Shopping 상품 식별자
     * @param price 판매가 (원, 양수)
     * @param initialStock 초기 판매 가능 재고 (0 이상)
     * @return 저장된 판매정보 (상태는 {@code READY})
     * @throws BusinessException 가격·재고가 입력 제약을 벗어남 (400)
     * @throws ProductNotFoundException Shopping 에 상품이 없음 (404)
     * @throws SalesAlreadyRegisteredException 이미 판매정보가 등록된 상품 (409)
     * @throws com.shoppinglive.commerce.shopping.application.ShoppingUnavailableException
     *     Shopping 일시 장애로 존재 여부를 확인하지 못함
     */
    public Sales register(Long productId, Long price, Integer initialStock) {
        validate(productId, price, initialStock);

        if (salesRepository.existsByProductId(productId)) {
            throw new SalesAlreadyRegisteredException(productId);
        }

        // 트랜잭션 밖. 확인에 실패하면 (예외 전파) 아무것도 저장하지 않는다.
        if (!shoppingClient.exists(productId)) {
            throw new ProductNotFoundException(productId);
        }

        return persist(productId, price, initialStock);
    }

    /**
     * 판매정보와 재고를 한 트랜잭션으로 저장한다.
     *
     * <p>{@code existsByProductId} 검사를 통과한 두 요청이 동시에 여기 도달할 수 있다. 그때는
     * {@code uk_sales_info_product_id} UNIQUE 제약이 한쪽을 떨어뜨리고, 그 결과를 중복 등록과
     * 같은 409 로 되돌린다. {@code saveAndFlush} 로 INSERT 시점을 앞당겨야 제약 위반이 커밋이
     * 아니라 이 블록 안에서 발생해 잡을 수 있다.
     */
    private Sales persist(Long productId, Long price, Integer initialStock) {
        try {
            return transactionTemplate.execute(status -> {
                Sales saved = salesRepository
                    .saveAndFlush(new Sales(productId, price, SalesStatus.READY));
                salesStockRepository.save(new SalesStock(saved.getId(), initialStock, 0));
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
            throw new BusinessException(
                ErrorCode.INVALID_REQUEST, "가격은 양의 정수여야 합니다: " + price);
        }
        if (initialStock == null || initialStock < 0) {
            throw new BusinessException(
                ErrorCode.INVALID_REQUEST, "초기 재고는 0 이상이어야 합니다: " + initialStock);
        }
    }
}
