package com.shoppinglive.commerce.order.service;

import com.shoppinglive.commerce.client.shopping.ProductNotFoundException;
import com.shoppinglive.commerce.client.shopping.ProductSnapshot;
import com.shoppinglive.commerce.client.shopping.ShoppingClient;
import com.shoppinglive.commerce.coupon.service.CouponPreviewService;
import com.shoppinglive.commerce.order.dto.CartOrderCommand;
import com.shoppinglive.commerce.order.dto.CreateOrderCommand;
import com.shoppinglive.commerce.order.dto.OrderCreationResult;
import com.shoppinglive.commerce.order.entity.CartItem;
import com.shoppinglive.commerce.order.entity.Order;
import com.shoppinglive.commerce.order.entity.OrderAmounts;
import com.shoppinglive.commerce.order.exception.CartItemNotFoundException;
import com.shoppinglive.commerce.order.exception.OrderAmountMismatchException;
import com.shoppinglive.commerce.order.exception.OrderNotAcceptableException;
import com.shoppinglive.commerce.order.repository.CartItemRepository;
import com.shoppinglive.commerce.order.repository.OrderJpaRepository;
import com.shoppinglive.commerce.sales.entity.Sales;
import com.shoppinglive.commerce.sales.entity.SalesStatus;
import com.shoppinglive.commerce.sales.entity.SalesStock;
import com.shoppinglive.commerce.sales.exception.InsufficientStockException;
import com.shoppinglive.commerce.sales.exception.SalesNotFoundException;
import com.shoppinglive.commerce.sales.repository.SalesJpaRepository;
import com.shoppinglive.commerce.sales.repository.SalesStockJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 생성 유스케이스 (주문 2).
 *
 * <p>주문 생성 시 재고를 예약한다. 취소(주문 4)·만료(주문 5)·결제 실패(결제 2)가 되돌리는 {@code reserved} 수량이 여기서 생긴다.
 *
 * <p><b>트랜잭션 경계</b> (ERD 설계 문서 §6.1):
 *
 * <pre>
 *   (밖) 입력 검증 → 멱등키로 기존 주문 조회 → Shopping 에서 상품명 확인
 *   (안) 판매정보 조회 → 상태·금액 검증 → 주문 INSERT → 재고 예약 → 품절 전이
 * </pre>
 *
 * Shopping 호출을 트랜잭션 밖에 두는 이유는 timeout 동안 DB 커넥션을 잡고 있지 않기 위함이다. 반대로 가격·재고 검증은 배정과 같은 트랜잭션 안에 있어야
 * 한다. 검증과 배정 사이에 틈이 있으면 그 사이 바뀐 값으로 주문이 만들어진다.
 *
 * <p><b>재고와 주문의 원자성:</b> 배정과 INSERT 가 한 트랜잭션이라 "주문은 있는데 재고는 그대로" 나 "재고만 줄고 주문은 없음" 이 남지 않는다. 어느 쪽이든
 * 실패하면 둘 다 롤백된다.
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
    private final CartItemRepository cartItems;
    private final TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;
    private final Duration expiration;
    private final PurchaseGuard purchaseGuard;
    private final PaymentGroupService paymentGroups;

    public OrderCreationService(
            OrderJpaRepository orderRepository,
            SalesJpaRepository salesRepository,
            SalesStockJpaRepository salesStockRepository,
            ShoppingClient shoppingClient,
            OrderNumberGenerator orderNumberGenerator,
            CartItemRepository cartItems,
            TransactionTemplate transactionTemplate,
            PurchaseGuard purchaseGuard,
            PaymentGroupService paymentGroups,
            // 기본값을 둔 이유: 테스트 클래스패스의 application.yml 이 main 쪽을 가리므로 통합
            // 테스트에서는 이 속성이 보이지 않는다. 운영 값은 main application.yml 이 정한다.
            @Value("${commerce.order.expiration.duration:PT15M}") Duration expiration) {
        this.orderRepository = orderRepository;
        this.salesRepository = salesRepository;
        this.salesStockRepository = salesStockRepository;
        this.shoppingClient = shoppingClient;
        this.orderNumberGenerator = orderNumberGenerator;
        this.cartItems = cartItems;
        this.transactionTemplate = transactionTemplate;
        this.expiration = expiration;
        this.purchaseGuard = purchaseGuard;
        this.paymentGroups = paymentGroups;
    }

    /**
     * 주문을 만들고 재고를 배정한다.
     *
     * @param command 주문 생성 입력
     * @param idempotencyKey 클라이언트가 보낸 멱등키 ({@code X-Idempotency-Key}). {@code null} 이면 멱등 처리를 하지
     *     않으므로 재전송이 별개 주문이 된다
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
        purchaseGuard.ensure(command.memberId());
        validateKey(idempotencyKey, false);

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return doCreate(command, null);
        }
        // 같은 멱등키 요청을 한 줄로 세운다. 이유는 lockFor 주석 참고.
        synchronized (lockFor(command.memberId() + ":" + idempotencyKey)) {
            return doCreate(command, idempotencyKey);
        }
    }

    /** 선택한 항목 하나당 주문 하나를 생성한다. 항목이 삭제된 후에도 재요청 결과를 재현할 수 있다. */
    public OrderCreationResult createFromCart(
            String memberId, Long itemId, CartOrderCommand request, String idempotencyKey) {
        validateKey(idempotencyKey, true);
        purchaseGuard.ensure(memberId);
        synchronized (lockFor(memberId + ":" + idempotencyKey)) {
            Optional<Order> replay = findByIdempotencyKey(memberId, idempotencyKey);
            if (replay.isPresent()) {
                return replayCart(itemId, request, replay.get());
            }
            try {
                CartItem item =
                        cartItems
                                .findByIdAndMemberId(itemId, memberId)
                                .orElseThrow(CartItemNotFoundException::new);
                CreateOrderCommand command =
                        new CreateOrderCommand(
                                item.getProductId(),
                                item.getQuantity(),
                                request.buyerName(),
                                request.buyerPhone(),
                                memberId,
                                request.expectedTotalAmount(),
                                itemId,
                                item.getVersion());
                command.validate();
                return doCreate(command, idempotencyKey);
            } catch (CartItemNotFoundException e) {
                // 다른 인스턴스가 동일한 요청을 먼저 커밋하고 항목을 삭제했을 수 있다.
                Optional<Order> concurrent = findByIdempotencyKey(memberId, idempotencyKey);
                if (concurrent.isPresent()) {
                    return replayCart(itemId, request, concurrent.get());
                }
                throw e;
            }
        }
    }

    private OrderCreationResult replayCart(Long itemId, CartOrderCommand request, Order order) {
        if (!Objects.equals(order.getSourceCartItemId(), itemId)
                || !Objects.equals(order.getBuyerName(), request.buyerName())
                || !Objects.equals(order.getBuyerPhone(), request.buyerPhone())
                || !Objects.equals(
                        order.getRequestedTotalAmount(), request.expectedTotalAmount())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 다른 주문 요청에 사용된 멱등키입니다.");
        }
        return OrderCreationResult.replayed(order);
    }

    private void validateKey(String key, boolean required) {
        if ((required && (key == null || key.isBlank()))
                || (key != null && (key.isBlank() || key.length() > 64))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "멱등키는 1~64자여야 합니다.");
        }
    }

    private OrderCreationResult doCreate(CreateOrderCommand command, String idempotencyKey) {
        // 재전송이면 새 주문을 만들지 않고 기존 결과로 연결한다. 응답을 놓친 사용자가 다시
        // 눌러도 주문이 둘로 늘지 않는다.
        Optional<Order> replay = findByIdempotencyKey(command.memberId(), idempotencyKey);
        if (replay.isPresent()) {
            return replayMatching(command, replay.get());
        }

        // 트랜잭션 밖. 주문에 남길 상품명을 확정하지 못하면 재고를 건드리기 전에 멈춘다.
        ProductSnapshot product =
                shoppingClient
                        .findProduct(command.productId())
                        .orElseThrow(() -> new ProductNotFoundException(command.productId()));

        for (int attempt = 1; attempt <= ORDER_NUMBER_RETRY; attempt++) {
            try {
                return persist(command, product.name(), idempotencyKey);
            } catch (DataIntegrityViolationException e) {
                if (violatesUniqueConstraint(e, "uk_orders_member_idempotency")) {
                    // 실패한 트랜잭션이 종료된 뒤 다른 요청이 커밋한 주문을 조회한다.
                    Optional<Order> concurrent =
                            findByIdempotencyKey(command.memberId(), idempotencyKey);
                    if (concurrent.isPresent()) {
                        return replayMatching(command, concurrent.get());
                    }
                    throw e;
                }
                if (!violatesUniqueConstraint(e, "uk_orders_order_number")) {
                    throw e;
                }
                log.warn("주문 번호 중복으로 재시도: 시도 횟수={}/{}", attempt, ORDER_NUMBER_RETRY);
            }
        }

        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "주문번호를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private boolean violatesUniqueConstraint(Throwable failure, String expectedName) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof ConstraintViolationException violation)
                    || !"23505".equals(violation.getSQLState())
                    || violation.getConstraintName() == null) {
                continue;
            }
            String name = violation.getConstraintName().replace("\"", "");
            name = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (name.equals(expectedName)) {
                return true;
            }
            // H2는 명명된 UNIQUE 제약 대신 자동 생성한 인덱스 이름을 보고한다.
            if (violation.getSQLException().getClass().getName().startsWith("org.h2.")
                    && name.matches(expectedName + "_index_[0-9a-f]+(?:_[0-9]+)?")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 검증·배정·저장을 한 트랜잭션으로 처리한다.
     *
     * <p>판매정보를 여기서 다시 읽는 이유: 주문서(주문 1)에서 본 값은 스냅샷일 뿐이고, 그 사이 가격이 바뀌었거나 판매가 중단됐을 수 있다. 명세 주문 2 의 "최종
     * 생성 때 다시 검증한다".
     */
    private OrderCreationResult persist(
            CreateOrderCommand command, String productName, String idempotencyKey) {

        return transactionTemplate.execute(
                status -> {
                    purchaseGuard.lock(command.memberId());
                    Optional<Order> existing =
                            findByIdempotencyKey(command.memberId(), idempotencyKey);
                    if (existing.isPresent()) {
                        return replayMatching(command, existing.get());
                    }
                    purchaseGuard.requireNoActive(command.memberId());
                    CartItem selected = null;
                    if (command.sourceCartItemId() != null) {
                        selected =
                                cartItems
                                        .lockOwned(command.sourceCartItemId(), command.memberId())
                                        .orElseThrow(CartItemNotFoundException::new);
                        if (entityManager != null) {
                            entityManager.refresh(selected);
                        }
                        if (!Objects.equals(selected.getProductId(), command.productId())
                                || !Objects.equals(selected.getQuantity(), command.quantity())
                                || !Objects.equals(
                                        selected.getVersion(), command.sourceCartItemVersion())) {
                            throw new BusinessException(
                                    ErrorCode.CONFLICT, "장바구니가 변경되었습니다. 다시 확인해 주세요.");
                        }
                    }
                    Sales sales =
                            salesRepository
                                    .findByProductId(command.productId())
                                    .orElseThrow(
                                            () ->
                                                    new SalesNotFoundException(
                                                            "상품의 판매정보를 찾을 수 없습니다: productId="
                                                                    + command.productId()));

                    purchaseGuard.lockSales(sales.getId());
                    if (entityManager != null) {
                        entityManager.refresh(sales);
                    }
                    if (!sales.getStatus().canAcceptNewOrder()) {
                        throw new OrderNotAcceptableException(sales.getStatus());
                    }

                    long unitPrice = sales.getPrice();
                    long totalAmount = OrderAmounts.total(unitPrice, command.quantity());
                    command.verifyExpectedAmount(totalAmount);
                    var coupon =
                            command.couponId() == null
                                    ? new CouponPreviewService.Preview(
                                            null, 0L, totalAmount, Map.of())
                                    : paymentGroups.previewSingle(
                                            command.memberId(),
                                            command.couponId(),
                                            command.productId(),
                                            totalAmount);

                    // 재고 예약 전에 INSERT 하여 중복 요청을 uk_orders_member_idempotency 로 차단한다.
                    // 같은 JVM 의 요청은 lockFor(String) 로 직렬화한다. 실패하면 INSERT 와 예약이 함께 롤백된다.
                    Order order =
                            new Order(
                                    orderNumberGenerator.generate(),
                                    sales.getId(),
                                    command.quantity(),
                                    unitPrice,
                                    command.buyerName(),
                                    command.buyerPhone(),
                                    command.memberId(),
                                    productName,
                                    idempotencyKey,
                                    Instant.now().plus(expiration),
                                    command.sourceCartItemId(),
                                    command.expectedTotalAmount(),
                                    coupon.discountAmount());
                    order.recordSourceCartVersion(command.sourceCartItemVersion());
                    order = orderRepository.saveAndFlush(order);

                    // 조건부 UPDATE. 이 한 줄이 초과 판매를 막는다. 동시에 들어온 요청들은 행 잠금으로
                    // 줄을 서고, 재고가 모자란 순간부터 대상 행이 0 이 되어 여기서 걸러진다. 실패하면
                    // 위의 주문 INSERT 까지 함께 롤백되므로 "주문만 있고 재고는 그대로" 가 남지 않는다.
                    if (salesStockRepository.reserve(sales.getId(), command.quantity()) == 0) {
                        throw new InsufficientStockException(
                                "주문 가능한 재고가 부족합니다: productId="
                                        + command.productId()
                                        + ", quantity="
                                        + command.quantity());
                    }

                    markSoldOutIfDepleted(sales);
                    if (coupon.couponId() == null) {
                        paymentGroups.bindSingle(
                                order,
                                idempotencyKey,
                                selected == null ? null : selected.getVersion());
                    } else {
                        paymentGroups.bindSingle(
                                order,
                                idempotencyKey,
                                selected == null ? null : selected.getVersion(),
                                coupon.couponId(),
                                coupon.discountAmount());
                    }
                    return OrderCreationResult.created(order);
                });
    }

    /**
     * 이번 주문으로 판매 가능 재고가 0 이 되면 판매 상태를 품절로 바꾼다 (판매 4 기준).
     *
     * <p>실패해도 주문을 롤백하지 않는다. 상태가 잠시 늦게 따라가도 신규 주문은 어차피 재고 조건부 UPDATE 가 막고, 다음 주문·재고 조정 때 다시 맞춰진다. 이미
     * 다른 트랜잭션이 전이를 마쳤으면 조건부 UPDATE 가 0 행을 반환하므로 중복 전이도 없다.
     */
    private void markSoldOutIfDepleted(Sales sales) {
        SalesStock stock = salesStockRepository.findById(sales.getId()).orElse(null);
        if (stock == null || stock.getAvailable() > 0) {
            return;
        }

        int updated =
                salesRepository.transitionStatus(
                        sales.getId(), SalesStatus.ON_SALE.name(), SalesStatus.SOLD_OUT.name());
        if (updated > 0) {
            log.info("주문 후 판매 상태를 SOLD_OUT으로 변경: 판매정보 ID={}", sales.getId());
        }
    }

    /** 멱등키만으로 다른 구매자의 주문 결과를 공개하지 않는다. 충돌 경로에도 같은 검증을 쓴다. */
    private OrderCreationResult replayMatching(CreateOrderCommand command, Order order) {
        boolean sameProduct =
                salesRepository
                        .findById(order.getSalesInfoId())
                        .map(sales -> Objects.equals(sales.getProductId(), command.productId()))
                        .orElse(false);
        if (!Objects.equals(order.getMemberId(), command.memberId())
                || !Objects.equals(order.getSourceCartItemId(), command.sourceCartItemId())
                || !sameProduct
                || !Objects.equals(order.getQuantity(), command.quantity())
                || !Objects.equals(order.getBuyerName(), command.buyerName())
                || !Objects.equals(order.getBuyerPhone(), command.buyerPhone())
                || !Objects.equals(order.getRequestedTotalAmount(), command.expectedTotalAmount())
                || !Objects.equals(
                        order.getPaymentGroup() == null
                                ? null
                                : order.getPaymentGroup().getCouponId(),
                        command.couponId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 다른 주문 요청에 사용된 멱등키입니다.");
        }
        return OrderCreationResult.replayed(order);
    }

    private Optional<Order> findByIdempotencyKey(String memberId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return orderRepository.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey);
    }

    /**
     * 멱등키를 해시로 나눠 담은 고정 개수의 잠금 중 하나를 고른다.
     *
     * <p><b>왜 필요한가:</b> 버튼 연타나 네트워크 재전송으로 같은 키의 요청이 동시에 들어오면, 모두 멱등키 조회를 통과한 뒤 각자 재고를 잡으러 간다. 최종적으로
     * 주문은 UNIQUE 제약 덕에 한 건만 남지만, 진 요청들이 재고를 점유했다 되돌리는 사이 다른 구매자에게 "품절" 이 보이고, 연타한 본인도 자기 주문 대신 오류를
     * 받는다. 실제로 그렇게 동작하다 동시성 테스트에서 잡혔다.
     *
     * <p><b>DB 제약과의 역할:</b> 유니크 제약은 중복 저장을 막고, 이 잠금은 같은 JVM 의 요청을 기존 주문 조회부터 직렬화한다. DB 엔진의 충돌 처리
     * 시점에 기대지 않고 동일 요청의 중복 실행을 줄인다.
     *
     * <p><b>한계:</b> 프로세스 안에서만 유효하다. 인스턴스를 여러 개 띄우면 인스턴스 간 동시 재전송은 다시 DB 제약에 기대게 되고, 진 쪽은 기존 주문 대신
     * 409 를 받을 수 있다. 동시 요청의 멱등 처리는 DB 제약과 기존 주문 재조회로 확인한다. 어떤 경우에도 <b>주문이 두 건 생기지는 않는다</b> — {@code
     * uk_orders_member_idempotency} 가 최종 방어선이다.
     *
     * <p>스트라이프 방식이라 서로 다른 키가 같은 잠금을 공유할 수 있다. 잠깐 줄을 설 뿐 정확성에 영향은 없고, 키마다 잠금 객체를 만들고 지우는 번거로움을 피한다.
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
