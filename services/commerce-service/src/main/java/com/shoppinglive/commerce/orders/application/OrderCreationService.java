package com.shoppinglive.commerce.orders.application;

import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.sales.application.InsufficientStockException;
import com.shoppinglive.commerce.sales.application.SalesNotFoundException;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.domain.SalesStatus;
import com.shoppinglive.commerce.sales.domain.SalesStock;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.sales.infrastructure.SalesStockJpaRepository;
import com.shoppinglive.commerce.shopping.application.ProductNotFoundException;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 생성 유스케이스 (주문 2).
 *
 * <p>P1 에서 재고를 배정하는 유일한 경로다. 취소(주문 4)·만료(주문 5)·결제 실패(결제 2)가
 * 되돌리는 {@code reserved} 수량이 여기서 생긴다.
 *
 * <p><b>트랜잭션 경계</b> (ERD 설계 문서 §6.1):
 * <pre>
 *   (밖) 입력 검증 → 멱등키로 기존 주문 조회 → Shopping 에서 상품명 확인
 *   (안) 판매정보 조회 → 상태·금액 검증 → 재고 배정 → 주문 INSERT → 품절 전이
 * </pre>
 * Shopping 호출을 트랜잭션 밖에 두는 이유는 timeout 동안 DB 커넥션을 잡고 있지 않기 위함이다.
 * 반대로 가격·재고 검증은 배정과 같은 트랜잭션 안에 있어야 한다. 검증과 배정 사이에 틈이 있으면
 * 그 사이 바뀐 값으로 주문이 만들어진다.
 *
 * <p><b>재고와 주문의 원자성:</b> 배정과 INSERT 가 한 트랜잭션이라 "주문은 있는데 재고는 그대로"
 * 나 "재고만 줄고 주문은 없음" 이 남지 않는다. 어느 쪽이든 실패하면 둘 다 롤백된다.
 */
@Service
public class OrderCreationService {

    private static final Logger log = LoggerFactory.getLogger(OrderCreationService.class);

    /** 주문번호 난수 충돌 시 재시도 횟수. 하루 100 만 개 공간이라 1 회 이상 걸릴 일은 드물다. */
    private static final int ORDER_NUMBER_RETRY = 3;

    /** 멱등키 직렬화용 잠금 개수. 자세한 이유는 {@link #lockFor(String)} 주석 참고. */
    private static final int LOCK_STRIPES = 64;

    private final Object[] idempotencyLocks = createLockStripes();

    private final OrderJpaRepository orderRepository;
    private final SalesJpaRepository salesRepository;
    private final SalesStockJpaRepository salesStockRepository;
    private final ShoppingClient shoppingClient;
    private final OrderNumberGenerator orderNumberGenerator;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final Duration expiration;

    public OrderCreationService(
        OrderJpaRepository orderRepository,
        SalesJpaRepository salesRepository,
        SalesStockJpaRepository salesStockRepository,
        ShoppingClient shoppingClient,
        OrderNumberGenerator orderNumberGenerator,
        PasswordEncoder passwordEncoder,
        TransactionTemplate transactionTemplate,
        // 기본값을 둔 이유: 테스트 클래스패스의 application.yml 이 main 쪽을 가리므로 통합
        // 테스트에서는 이 속성이 보이지 않는다. 운영 값은 main application.yml 이 정한다.
        @Value("${commerce.order.expiration.duration:PT15M}") Duration expiration) {
        this.orderRepository = orderRepository;
        this.salesRepository = salesRepository;
        this.salesStockRepository = salesStockRepository;
        this.shoppingClient = shoppingClient;
        this.orderNumberGenerator = orderNumberGenerator;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.expiration = expiration;
    }

    /**
     * 주문을 만들고 재고를 배정한다.
     *
     * @param command 주문 생성 입력
     * @param idempotencyKey 클라이언트가 보낸 멱등키 ({@code X-Idempotency-Key}). {@code null}
     *     이면 멱등 처리를 하지 않으므로 재전송이 별개 주문이 된다
     * @return 새로 만든 주문, 또는 멱등키로 찾은 기존 주문
     * @throws BusinessException 입력 제약 위반 (400)
     * @throws SalesNotFoundException 판매정보가 없는 상품 (404)
     * @throws ProductNotFoundException Shopping 에 상품이 없음 (404)
     * @throws OrderNotAcceptableException 판매 중이 아닌 상품 (409)
     * @throws OrderAmountMismatchException 확인한 금액과 현재 금액이 다름 (409)
     * @throws InsufficientStockException 배정할 재고가 없음 (409)
     */
    public OrderCreationResult create(CreateOrderCommand command, String idempotencyKey) {
        command.validate();

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreate(command, null);
        }
        // 같은 멱등키 요청을 한 줄로 세운다. 이유는 lockFor 주석 참고.
        synchronized (lockFor(idempotencyKey)) {
            return doCreate(command, idempotencyKey);
        }
    }

    private OrderCreationResult doCreate(CreateOrderCommand command, String idempotencyKey) {
        // 재전송이면 새 주문을 만들지 않고 기존 결과로 연결한다. 응답을 놓친 사용자가 다시
        // 눌러도 주문이 둘로 늘지 않는다.
        Optional<Order> replay = findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return OrderCreationResult.replayed(replay.get());
        }

        // 트랜잭션 밖. 주문에 남길 상품명을 확정하지 못하면 재고를 건드리기 전에 멈춘다.
        ProductSnapshot product = shoppingClient.findProduct(command.productId())
            .orElseThrow(() -> new ProductNotFoundException(command.productId()));

        String passwordHash = passwordEncoder.encode(command.lookupPassword());

        for (int attempt = 1; attempt <= ORDER_NUMBER_RETRY; attempt++) {
            try {
                return OrderCreationResult.created(
                    persist(command, product.name(), passwordHash, idempotencyKey));
            } catch (DataIntegrityViolationException e) {
                // 멱등키 충돌이면 같은 키로 이미 만들어진 주문이 있다는 뜻이다. 동시에 들어온
                // 두 재전송 중 진 쪽이 여기로 온다.
                Optional<Order> concurrent = findByIdempotencyKey(idempotencyKey);
                if (concurrent.isPresent()) {
                    return OrderCreationResult.replayed(concurrent.get());
                }
                // 아니면 주문번호 난수가 겹친 것이므로 새 번호로 다시 시도한다.
                log.warn("order number collision, retrying (attempt {}/{})",
                    attempt, ORDER_NUMBER_RETRY);
            }
        }

        throw new BusinessException(
            ErrorCode.INTERNAL_ERROR, "주문번호를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    /**
     * 검증·배정·저장을 한 트랜잭션으로 처리한다.
     *
     * <p>판매정보를 여기서 다시 읽는 이유: 주문서(주문 1)에서 본 값은 스냅샷일 뿐이고, 그 사이
     * 가격이 바뀌었거나 판매가 중단됐을 수 있다. 명세 주문 2 의 "최종 생성 때 다시 검증한다".
     */
    private Order persist(
        CreateOrderCommand command,
        String productName,
        String passwordHash,
        String idempotencyKey) {

        return transactionTemplate.execute(status -> {
            Sales sales = salesRepository.findByProductId(command.productId())
                .orElseThrow(() -> new SalesNotFoundException(
                    "sales not found for product: productId=" + command.productId()));

            if (!sales.getStatus().canAcceptNewOrder()) {
                throw new OrderNotAcceptableException(sales.getStatus());
            }

            long unitPrice = sales.getPrice();
            long totalAmount = unitPrice * command.quantity();
            command.verifyExpectedAmount(totalAmount);

            // 재고 배정보다 주문 INSERT 를 먼저 한다. Postgres 는 미커밋 중복 INSERT 에서
            // 뒤따르는 트랜잭션을 대기시키므로, 같은 멱등키의 동시 요청이 재고에 손대기 전에
            // uk_orders_idempotency_key 에서 걸린다. 다만 H2 는 커밋 시점에야 위반을 알려
            // 이 순서만으로는 부족하다 — 엔진과 무관한 보장은 lockFor(String) 가 맡는다.
            // 어느 쪽이든 실패하면 트랜잭션 전체가 롤백되므로 결과는 같다.
            Order order = orderRepository.saveAndFlush(new Order(
                orderNumberGenerator.generate(),
                sales.getId(),
                command.quantity(),
                unitPrice,
                command.buyerName(),
                command.buyerPhone(),
                passwordHash,
                productName,
                idempotencyKey,
                Instant.now().plus(expiration)));

            // 조건부 UPDATE. 이 한 줄이 초과 판매를 막는다. 동시에 들어온 요청들은 행 잠금으로
            // 줄을 서고, 재고가 모자란 순간부터 대상 행이 0 이 되어 여기서 걸러진다. 실패하면
            // 위의 주문 INSERT 까지 함께 롤백되므로 "주문만 있고 재고는 그대로" 가 남지 않는다.
            if (salesStockRepository.reserve(sales.getId(), command.quantity()) == 0) {
                throw new InsufficientStockException(
                    "주문 가능한 재고가 부족합니다: productId=" + command.productId()
                        + ", quantity=" + command.quantity());
            }

            markSoldOutIfDepleted(sales);
            return order;
        });
    }

    /**
     * 이번 주문으로 판매 가능 재고가 0 이 되면 판매 상태를 품절로 바꾼다 (판매 4 기준).
     *
     * <p>실패해도 주문을 롤백하지 않는다. 상태가 잠시 늦게 따라가도 신규 주문은 어차피 재고
     * 조건부 UPDATE 가 막고, 다음 주문·재고 조정 때 다시 맞춰진다. 이미 다른 트랜잭션이 전이를
     * 마쳤으면 조건부 UPDATE 가 0 행을 반환하므로 중복 전이도 없다.
     */
    private void markSoldOutIfDepleted(Sales sales) {
        SalesStock stock = salesStockRepository.findById(sales.getId()).orElse(null);
        if (stock == null || stock.getAvailable() > 0) {
            return;
        }

        int updated = salesRepository.transitionStatus(
            sales.getId(), SalesStatus.ON_SALE.name(), SalesStatus.SOLD_OUT.name());
        if (updated > 0) {
            log.info("sales marked SOLD_OUT after order: salesId={}", sales.getId());
        }
    }

    private Optional<Order> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return orderRepository.findByIdempotencyKey(idempotencyKey);
    }

    /**
     * 멱등키를 해시로 나눠 담은 고정 개수의 잠금 중 하나를 고른다.
     *
     * <p><b>왜 필요한가:</b> 버튼 연타나 네트워크 재전송으로 같은 키의 요청이 동시에 들어오면,
     * 모두 멱등키 조회를 통과한 뒤 각자 재고를 잡으러 간다. 최종적으로 주문은 UNIQUE 제약 덕에
     * 한 건만 남지만, 진 요청들이 재고를 점유했다 되돌리는 사이 다른 구매자에게 "품절" 이
     * 보이고, 연타한 본인도 자기 주문 대신 오류를 받는다. 실제로 그렇게 동작하다 동시성
     * 테스트에서 잡혔다.
     *
     * <p><b>왜 DB 제약만으로는 부족한가:</b> Postgres 는 미커밋 중복 INSERT 에서 뒤따르는
     * 트랜잭션을 대기시키지만, 통합 테스트가 쓰는 H2 는 커밋 시점에야 위반을 알린다. 엔진에
     * 기대지 않고 같은 결과를 보장하려면 이 층이 있어야 한다.
     *
     * <p><b>한계:</b> 프로세스 안에서만 유효하다. 인스턴스를 여러 개 띄우면 인스턴스 간 동시
     * 재전송은 다시 DB 제약에 기대게 되고, 진 쪽은 기존 주문 대신 409 를 받을 수 있다. P1 은
     * 단일 인스턴스라 범위 밖이고, 그때는 멱등키 전용 테이블에 선점 레코드를 넣는 방식으로
     * 바꾸면 된다. 어떤 경우에도 <b>주문이 두 건 생기지는 않는다</b> —
     * {@code uk_orders_idempotency_key} 가 최종 방어선이다.
     *
     * <p>스트라이프 방식이라 서로 다른 키가 같은 잠금을 공유할 수 있다. 잠깐 줄을 설 뿐 정확성에
     * 영향은 없고, 키마다 잠금 객체를 만들고 지우는 번거로움을 피한다.
     */
    private Object lockFor(String idempotencyKey) {
        return idempotencyLocks[Math.floorMod(idempotencyKey.hashCode(), LOCK_STRIPES)];
    }

    private static Object[] createLockStripes() {
        Object[] stripes = new Object[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }
}
