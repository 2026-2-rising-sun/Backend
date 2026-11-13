package com.shoppinglive.commercecore.payment.service;

import com.shoppinglive.commercecore.order.entity.Order;
import com.shoppinglive.commercecore.order.entity.OrderStatus;
import com.shoppinglive.commercecore.order.entity.PaymentGroup;
import com.shoppinglive.commercecore.order.repository.OrderJpaRepository;
import com.shoppinglive.commercecore.order.repository.PaymentGroupRepository;
import com.shoppinglive.commercecore.payment.dto.PaymentExecution;
import com.shoppinglive.commercecore.payment.dto.RefundView;
import com.shoppinglive.commercecore.payment.entity.PaymentStatus;
import com.shoppinglive.commercecore.payment.entity.RefundStatus;
import com.shoppinglive.commercecore.payment.repository.RefundQueryRepository;
import com.shoppinglive.commercecore.payment.repository.RefundQueryRepository.ExistingRequest;
import com.shoppinglive.commercecore.product.dto.ProductSnapshot;
import com.shoppinglive.commercecore.product.service.ProductLookup;
import com.shoppinglive.commercecore.sales.entity.Sales;
import com.shoppinglive.commercecore.sales.repository.SalesJpaRepository;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 변경할 수 없는 환불 대상을 기록하고 실행이 활성화된 경우 커밋 후 처리를 시작한다. */
@Service
public class RefundIntakeService {
    private final RefundQueryService queries;
    private final RefundQueryRepository repository;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PaymentClient attempts;
    private final SalesJpaRepository sales;
    private final ProductLookup productLookup;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final PaymentDeliveryStore deliveries;
    private final PaymentDispatcher dispatcher;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private final boolean executionEnabled;

    @Autowired
    public RefundIntakeService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PaymentClient attempts,
            SalesJpaRepository sales,
            ProductLookup productLookup,
            JdbcTemplate jdbc,
            RefundQueryService queries,
            RefundQueryRepository repository,
            PaymentDeliveryStore deliveries,
            PaymentDispatcher dispatcher,
            org.springframework.transaction.support.TransactionTemplate transaction,
            @Value("${commercecore.refunds.execution-enabled:false}") boolean executionEnabled) {
        this(
                groups,
                orders,
                attempts,
                sales,
                productLookup,
                jdbc,
                queries,
                repository,
                Clock.systemUTC(),
                deliveries,
                dispatcher,
                transaction,
                executionEnabled);
    }

    RefundIntakeService(
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PaymentClient attempts,
            SalesJpaRepository sales,
            ProductLookup productLookup,
            JdbcTemplate jdbc,
            RefundQueryService queries,
            RefundQueryRepository repository,
            Clock clock,
            PaymentDeliveryStore deliveries,
            PaymentDispatcher dispatcher,
            org.springframework.transaction.support.TransactionTemplate transaction,
            boolean executionEnabled) {
        this.groups = groups;
        this.orders = orders;
        this.attempts = attempts;
        this.sales = sales;
        this.productLookup = productLookup;
        this.jdbc = jdbc;
        this.queries = queries;
        this.repository = repository;
        this.clock = clock;
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
        this.transaction = transaction;
        this.executionEnabled = executionEnabled;
    }

    public RequestResult request(
            String memberId,
            String groupNumber,
            String idempotencyKey,
            Collection<Long> requestedCartItemIds) {
        PaymentGroup owned =
                groups.findByGroupNumberAndMemberId(groupNumber, memberId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.length() > 64
                || requestedCartItemIds == null
                || requestedCartItemIds.size() > 100
                || requestedCartItemIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(requestedCartItemIds).size() != requestedCartItemIds.size())
            throw invalid("환불 키와 대상 ID가 올바르지 않습니다.");
        RequestResult replay =
                transaction.execute(
                        t -> {
                            var existing = repository.findByKey(memberId, idempotencyKey);
                            if (existing == null) return null;
                            groups.lockById(owned.getId()).orElseThrow();
                            if (!Objects.equals(
                                    existing.fingerprint(),
                                    fingerprint(
                                            owned.getId(),
                                            requestedCartItemIds.stream().sorted().toList())))
                                throw new BusinessException(
                                        ErrorCode.CONFLICT,
                                        "같은 Idempotency-Key에 다른 환불 요청을 사용할 수 없습니다.");
                            return new RequestResult(
                                    queries.load(existing.id(), groupNumber), false);
                        });
        if (replay != null) {
            if (executionEnabled
                    && (replay.refund().status() == RefundStatus.PROCESSING
                            || replay.refund().status() == RefundStatus.UNKNOWN))
                dispatcher.refund(replay.refund().id());
            return new RequestResult(queries.load(replay.refund().id(), groupNumber), false);
        }
        // 원격 조회는 Core 잠금과 DB 트랜잭션 밖에서 끝낸다. 저장 직전 잠금 아래 원결제 참조를 재검증한다.
        PaymentExecution payment =
                owned.getPaymentId() == null ? null : attempts.get(owned.getPaymentId());
        RequestResult result =
                transaction.execute(
                        t ->
                                requestLocked(
                                        memberId,
                                        groupNumber,
                                        idempotencyKey,
                                        requestedCartItemIds,
                                        payment));
        if (executionEnabled
                && (result.refund().status() == RefundStatus.PROCESSING
                        || result.refund().status() == RefundStatus.UNKNOWN)) {
            dispatcher.refund(result.refund().id());
            return new RequestResult(
                    queries.load(result.refund().id(), groupNumber), result.created());
        }
        return result;
    }

    private RequestResult requestLocked(
            String memberId,
            String groupNumber,
            String idempotencyKey,
            Collection<Long> requestedCartItemIds,
            PaymentExecution attempt) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw invalid("Idempotency-Key는 1~64자여야 합니다.");
        }
        if (requestedCartItemIds == null
                || requestedCartItemIds.size() > 100
                || requestedCartItemIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw invalid("cartItemIds는 양수 ID 최대 100개여야 합니다.");
        }
        List<Long> cartIds = requestedCartItemIds.stream().sorted().toList();
        if (new HashSet<>(cartIds).size() != cartIds.size()) {
            throw invalid("cartItemIds에 중복 ID가 있습니다.");
        }

        PaymentGroup owned =
                groups.findByGroupNumberAndMemberId(groupNumber, memberId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        PaymentGroup group =
                groups.lockById(owned.getId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));

        entityManager.refresh(group);
        String fingerprint = fingerprint(group.getId(), cartIds);
        ExistingRequest existing = repository.findByKey(memberId, idempotencyKey);
        if (existing != null) {
            if (!Objects.equals(existing.fingerprint(), fingerprint)) {
                throw new BusinessException(
                        ErrorCode.CONFLICT, "같은 Idempotency-Key에 다른 환불 요청을 사용할 수 없습니다.");
            }
            return new RequestResult(queries.load(existing.id(), groupNumber), false);
        }

        if (group.isOrderSheet())
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE, "SKU 주문의 부분 환불 정책을 확정한 뒤 접수를 지원합니다.");
        List<Order> groupOrders = orders.findByPaymentGroupIdOrderByIdAsc(group.getId());
        if (group.getStatus() != OrderStatus.PAID
                || groupOrders.isEmpty()
                || groupOrders.stream()
                        .anyMatch(
                                order ->
                                        order.getStatus() != OrderStatus.PAID
                                                && order.getStatus() != OrderStatus.REFUNDED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 완료된 주문만 환불할 수 있습니다.");
        }
        if (attempt == null
                || !Objects.equals(group.getPaymentId(), attempt.paymentId())
                || !Objects.equals(group.getId(), attempt.paymentGroupId())
                || !Objects.equals(group.getGroupNumber(), attempt.referenceNumber())
                || group.getPayableAmount() != attempt.amount()
                || attempt.getStatus() != PaymentStatus.SUCCESS
                || attempt.getResolvedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "성공한 결제 이력을 확인할 수 없습니다.");
        }
        Instant now = clock.instant();
        if (!RefundWindowPolicy.isOpen(now, attempt.getResolvedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 접수 가능 기간이 지났습니다.");
        }

        List<Order> targets;
        if (cartIds.isEmpty()) {
            targets = groupOrders;
        } else {
            Map<Long, List<Order>> byCartId =
                    groupOrders.stream()
                            .filter(order -> order.getSourceCartItemId() != null)
                            .collect(Collectors.groupingBy(Order::getSourceCartItemId));
            if (!byCartId.keySet().containsAll(cartIds)) {
                throw new BusinessException(
                        ErrorCode.NOT_FOUND, "환불 대상 장바구니 항목을 결제 묶음에서 찾을 수 없습니다.");
            }
            targets =
                    cartIds.stream()
                            .flatMap(id -> byCartId.get(id).stream())
                            .sorted(Comparator.comparing(Order::getId))
                            .toList();
            if (targets.size() != cartIds.size()) {
                throw new BusinessException(ErrorCode.CONFLICT, "장바구니 항목과 주문의 연결이 모호합니다.");
            }
        }
        if (hasPreviouslyRequestedTargets(group.getId(), targets)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 환불 요청에 포함된 주문이 있습니다.");
        }
        if (targets.stream().anyMatch(order -> order.getStatus() != OrderStatus.PAID)) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 가능한 주문 상태가 아닙니다.");
        }

        long amount = 0;
        try {
            for (Order order : targets) {
                amount = Math.addExact(amount, order.getPayableAmount());
            }
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 금액 범위를 초과했습니다.");
        }
        Set<Long> salesIds =
                targets.stream().map(Order::getSalesInfoId).collect(Collectors.toSet());
        Map<Long, Long> productIds =
                sales.findAllById(salesIds).stream()
                        .collect(Collectors.toMap(Sales::getId, Sales::getProductId));
        if (!productIds.keySet().containsAll(salesIds)) {
            throw new BusinessException(ErrorCode.CONFLICT, "주문 상품 스냅샷을 확인할 수 없습니다.");
        }
        Map<Long, String> sellerIds = new HashMap<>();
        for (ProductSnapshot product : productLookup.findProducts(productIds.values())) {
            sellerIds.put(product.id(), product.sellerId());
        }

        Long requestId =
                insertRequest(group.getId(), memberId, idempotencyKey, fingerprint, amount, now);
        if (requestId == null) {
            ExistingRequest winner = repository.findByKey(memberId, idempotencyKey);
            if (winner != null && Objects.equals(winner.fingerprint(), fingerprint)) {
                return new RequestResult(queries.load(winner.id(), groupNumber), false);
            }
            throw new BusinessException(
                    ErrorCode.CONFLICT, "같은 Idempotency-Key에 다른 환불 요청을 사용할 수 없습니다.");
        }
        for (Order order : targets) {
            jdbc.update(
                    """
                INSERT INTO refund_target_order
                    (refund_request_id, order_id, product_id_snapshot, seller_id_snapshot, refund_amount, created_at, quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                    requestId,
                    order.getId(),
                    productIds.get(order.getSalesInfoId()),
                    sellerIds.get(productIds.get(order.getSalesInfoId())),
                    order.getPayableAmount(),
                    Timestamp.from(now),
                    order.getQuantity());
        }
        deliveries.refund(
                requestId,
                attempt.paymentId(),
                attempt.orderId(),
                group.getId(),
                groupNumber,
                amount);
        return new RequestResult(queries.load(requestId, groupNumber), true);
    }

    private Long insertRequest(
            Long groupId, String member, String key, String fingerprint, long amount, Instant at) {
        List<Long> ids =
                jdbc.query(
                        """
            INSERT INTO refund_request
                (payment_group_id, member_id, idempotency_key, request_fingerprint, refund_amount, status, requested_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uk_refund_request_member_key DO NOTHING
            RETURNING id
            """,
                        (rs, row) -> rs.getLong(1),
                        groupId,
                        member,
                        key,
                        fingerprint,
                        amount,
                        RefundStatus.PROCESSING.name(),
                        Timestamp.from(at),
                        Timestamp.from(at),
                        Timestamp.from(at));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private boolean hasPreviouslyRequestedTargets(Long groupId, List<Order> targets) {
        Set<Long> requestedOrderIds = new HashSet<>(repository.requestedOrderIds(groupId));
        return targets.stream().anyMatch(order -> requestedOrderIds.contains(order.getId()));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private static String fingerprint(Long groupId, List<Long> cartIds) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    (groupId
                                                    + ":"
                                                    + cartIds.stream()
                                                            .map(String::valueOf)
                                                            .collect(Collectors.joining(",")))
                                            .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 알고리즘이 필요합니다.", impossible);
        }
    }

    public record RequestResult(RefundView refund, boolean created) {}
}
