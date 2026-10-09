package com.shoppinglive.commerce.refunds.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.commerce.orders.domain.Order;
import com.shoppinglive.commerce.orders.domain.OrderStatus;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.domain.PaymentAttempt;
import com.shoppinglive.commerce.payments.domain.PaymentStatus;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.purchase.domain.PaymentGroup;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import com.shoppinglive.commerce.refunds.domain.RefundStatus;
import com.shoppinglive.commerce.sales.domain.Sales;
import com.shoppinglive.commerce.sales.infrastructure.SalesJpaRepository;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.commerce.shopping.domain.ProductSnapshot;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Records a refund request and its immutable order targets; execution is added by later refund steps. */
@Service
public class RefundIntakeService {
    private static final Duration REFUND_WINDOW = Duration.ofHours(168);
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PaymentAttemptJpaRepository attempts;
    private final SalesJpaRepository sales;
    private final ShoppingClient shopping;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RefundMockEngine mockRefundEngine;
    private final boolean executionEnabled;

    @Autowired
    public RefundIntakeService(PaymentGroupRepository groups, OrderJpaRepository orders,
        PaymentAttemptJpaRepository attempts, SalesJpaRepository sales, ShoppingClient shopping, JdbcTemplate jdbc,
        RefundMockEngine mockRefundEngine, @Value("${commerce.refunds.execution-enabled:false}") boolean executionEnabled) {
        this(groups, orders, attempts, sales, shopping, jdbc, Clock.systemUTC(), mockRefundEngine, executionEnabled);
    }

    RefundIntakeService(PaymentGroupRepository groups, OrderJpaRepository orders,
        PaymentAttemptJpaRepository attempts, SalesJpaRepository sales, ShoppingClient shopping, JdbcTemplate jdbc,
        Clock clock, RefundMockEngine mockRefundEngine, boolean executionEnabled) {
        this.groups = groups;
        this.orders = orders;
        this.attempts = attempts;
        this.sales = sales;
        this.shopping = shopping;
        this.jdbc = jdbc;
        this.clock = clock;
        this.mockRefundEngine = mockRefundEngine;
        this.executionEnabled = executionEnabled;
    }

    @Transactional
    public RequestResult request(String memberId, String groupNumber, String idempotencyKey,
        Collection<Long> requestedCartItemIds) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw invalid("Idempotency-Key는 1~64자여야 합니다.");
        }
        if (requestedCartItemIds == null || requestedCartItemIds.size() > 100
            || requestedCartItemIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw invalid("cartItemIds는 양수 ID 최대 100개여야 합니다.");
        }
        List<Long> cartIds = requestedCartItemIds.stream().sorted().toList();
        if (new HashSet<>(cartIds).size() != cartIds.size()) throw invalid("cartItemIds에 중복 ID가 있습니다.");

        PaymentGroup owned = groups.findByGroupNumberAndMemberId(groupNumber, memberId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        PaymentGroup group = groups.lockById(owned.getId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));

        String fingerprint = fingerprint(group.getId(), cartIds);
        ExistingRequest existing = findByKey(memberId, idempotencyKey);
        if (existing != null) {
            if (!Objects.equals(existing.fingerprint(), fingerprint)) {
                throw new BusinessException(ErrorCode.CONFLICT, "같은 Idempotency-Key에 다른 환불 요청을 사용할 수 없습니다.");
            }
            scheduleIfPending(existing.id(), existing.status());
            return new RequestResult(load(existing.id(), groupNumber), false);
        }

        List<Order> groupOrders = orders.findByPaymentGroupIdOrderByIdAsc(group.getId());
        if (group.getStatus() != OrderStatus.PAID || groupOrders.isEmpty()
            || groupOrders.stream().anyMatch(order -> order.getStatus() != OrderStatus.PAID
                && order.getStatus() != OrderStatus.REFUNDED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "결제 완료된 주문만 환불할 수 있습니다.");
        }
        PaymentAttempt attempt = group.getPaymentId() == null ? null : attempts.findById(group.getPaymentId()).orElse(null);
        if (attempt == null || attempt.getStatus() != PaymentStatus.SUCCESS || attempt.getResolvedAt() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "성공한 결제 이력을 확인할 수 없습니다.");
        }
        Instant now = clock.instant();
        if (!isRefundWindowOpen(now, attempt.getResolvedAt())) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 접수 가능 기간이 지났습니다.");
        }

        List<Order> targets;
        if (cartIds.isEmpty()) {
            targets = groupOrders;
        } else {
            Map<Long, List<Order>> byCartId = groupOrders.stream().filter(order -> order.getSourceCartItemId() != null)
                .collect(Collectors.groupingBy(Order::getSourceCartItemId));
            if (!byCartId.keySet().containsAll(cartIds)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "환불 대상 장바구니 항목을 결제 묶음에서 찾을 수 없습니다.");
            }
            targets = cartIds.stream().flatMap(id -> byCartId.get(id).stream()).sorted(Comparator.comparing(Order::getId)).toList();
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
            for (Order order : targets) amount = Math.addExact(amount, order.getPayableAmount());
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 금액 범위를 초과했습니다.");
        }
        Set<Long> salesIds = targets.stream().map(Order::getSalesInfoId).collect(Collectors.toSet());
        Map<Long, Long> productIds = sales.findAllById(salesIds).stream()
            .collect(Collectors.toMap(Sales::getId, Sales::getProductId));
        if (!productIds.keySet().containsAll(salesIds)) {
            throw new BusinessException(ErrorCode.CONFLICT, "주문 상품 스냅샷을 확인할 수 없습니다.");
        }
        Map<Long, String> sellerIds = new HashMap<>();
        for (ProductSnapshot product : shopping.findProducts(productIds.values())) {
            sellerIds.put(product.id(), product.sellerId());
        }

        Long requestId = insertRequest(group.getId(), memberId, idempotencyKey, fingerprint, amount, now);
        if (requestId == null) {
            ExistingRequest winner = findByKey(memberId, idempotencyKey);
            if (winner != null && Objects.equals(winner.fingerprint(), fingerprint)) {
                scheduleIfPending(winner.id(), winner.status());
                return new RequestResult(load(winner.id(), groupNumber), false);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "같은 Idempotency-Key에 다른 환불 요청을 사용할 수 없습니다.");
        }
        for (Order order : targets) {
            jdbc.update("""
                INSERT INTO refund_target_order
                    (refund_request_id, order_id, product_id_snapshot, seller_id_snapshot, refund_amount, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, requestId, order.getId(), productIds.get(order.getSalesInfoId()),
                sellerIds.get(productIds.get(order.getSalesInfoId())), order.getPayableAmount(), Timestamp.from(now));
        }
        scheduleIfPending(requestId, RefundStatus.PROCESSING);
        return new RequestResult(load(requestId, groupNumber), true);
    }

    @Transactional(readOnly = true)
    public List<RefundView> list(String memberId, String groupNumber) {
        PaymentGroup group = groups.findByGroupNumberAndMemberId(groupNumber, memberId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        List<Long> ids = jdbc.query("SELECT id FROM refund_request WHERE payment_group_id=? ORDER BY requested_at DESC,id DESC",
            (rs, row) -> rs.getLong(1), group.getId());
        return ids.stream().map(id -> load(id, groupNumber)).toList();
    }

    @Transactional(readOnly = true)
    public RefundView get(String memberId, String groupNumber, long requestId) {
        PaymentGroup group = groups.findByGroupNumberAndMemberId(groupNumber, memberId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "결제 묶음을 찾을 수 없습니다."));
        Long matched = jdbc.query("SELECT id FROM refund_request WHERE id=? AND payment_group_id=? AND member_id=?",
            rs -> rs.next() ? rs.getLong(1) : null, requestId, group.getId(), memberId);
        if (matched == null) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        return load(requestId, groupNumber);
    }

    @Transactional(readOnly = true)
    public List<SellerRefundView> listSeller(String sellerId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("page는 0 이상, size는 1~100이어야 합니다.");
        List<Long> ids = jdbc.query("""
            SELECT DISTINCT r.id FROM refund_request r
            JOIN refund_target_order t ON t.refund_request_id=r.id
            WHERE t.seller_id_snapshot=? ORDER BY r.id DESC LIMIT ? OFFSET ?
            """, (rs, row) -> rs.getLong(1), sellerId, size, (long) page * size);
        return ids.stream().map(id -> loadSeller(id, sellerId)).toList();
    }

    @Transactional(readOnly = true)
    public SellerRefundView getSeller(String sellerId, long requestId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM refund_target_order
            WHERE refund_request_id=? AND seller_id_snapshot=?
            """, Integer.class, requestId, sellerId);
        if (count == null || count == 0) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        return loadSeller(requestId, sellerId);
    }

    private Long insertRequest(Long groupId, String member, String key, String fingerprint, long amount, Instant at) {
        List<Long> ids = jdbc.query("""
            INSERT INTO refund_request
                (payment_group_id, member_id, idempotency_key, request_fingerprint, refund_amount, status, requested_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uk_refund_request_member_key DO NOTHING
            RETURNING id
            """, (rs, row) -> rs.getLong(1), groupId, member, key, fingerprint, amount, RefundStatus.PROCESSING.name(),
            Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private boolean hasPreviouslyRequestedTargets(Long groupId, List<Order> targets) {
        Set<Long> requestedOrderIds = jdbc.query("""
            SELECT t.order_id FROM refund_request r
            JOIN refund_target_order t ON t.refund_request_id=r.id
            WHERE r.payment_group_id=? AND r.status <> 'FAILED'
            """, (rs, row) -> rs.getLong(1), groupId).stream().collect(Collectors.toSet());
        return targets.stream().anyMatch(order -> requestedOrderIds.contains(order.getId()));
    }

    private ExistingRequest findByKey(String member, String key) {
        List<ExistingRequest> rows = jdbc.query("SELECT id,payment_group_id,request_fingerprint,status FROM refund_request WHERE member_id=? AND idempotency_key=?",
            (rs, row) -> new ExistingRequest(rs.getLong(1), rs.getLong(2), rs.getString(3),
                RefundStatus.valueOf(rs.getString(4))), member, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void scheduleIfPending(long requestId, RefundStatus status) {
        if (!executionEnabled || (status != RefundStatus.PROCESSING && status != RefundStatus.UNKNOWN)) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            mockRefundEngine.schedule(requestId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mockRefundEngine.schedule(requestId);
            }
        });
    }

    private RefundView load(long id, String groupNumber) {
        RequestRow request = jdbc.query("SELECT payment_group_id,refund_amount,status,requested_at,resolved_at,retry_count,GREATEST(next_action_at,lease_until) FROM refund_request WHERE id=?",
            rs -> rs.next() ? new RequestRow(rs.getLong(1), rs.getLong(2), RefundStatus.valueOf(rs.getString(3)),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                rs.getInt(6), rs.getTimestamp(7).toInstant()) : null, id);
        if (request == null) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        long cumulative = jdbc.queryForObject("SELECT COALESCE(SUM(refund_amount),0) FROM refund_request WHERE payment_group_id=? AND status='SUCCESS'",
            Long.class, request.groupId());
        List<Target> targets = jdbc.query("""
            SELECT o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,o.quantity,t.refund_amount
              FROM refund_target_order t JOIN orders o ON o.id=t.order_id
             WHERE t.refund_request_id=? ORDER BY t.order_id
            """, (rs, row) -> new Target((Long) rs.getObject(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getInt(5), rs.getLong(6)), id);
        return new RefundView(id, groupNumber, request.amount(), cumulative, request.status(), request.requestedAt(), request.resolvedAt(), targets, recoveryView(request));
    }

    private SellerRefundView loadSeller(long id, String sellerId) {
        RequestRow request = jdbc.query("SELECT payment_group_id,refund_amount,status,requested_at,resolved_at,retry_count,GREATEST(next_action_at,lease_until) FROM refund_request WHERE id=?",
            rs -> rs.next() ? new RequestRow(rs.getLong(1), rs.getLong(2), RefundStatus.valueOf(rs.getString(3)),
                rs.getTimestamp(4).toInstant(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                rs.getInt(6), rs.getTimestamp(7).toInstant()) : null, id);
        if (request == null) throw new BusinessException(ErrorCode.NOT_FOUND, "환불 요청을 찾을 수 없습니다.");
        List<Target> targets = jdbc.query("""
            SELECT o.source_cart_item_id,t.order_id,t.product_id_snapshot,o.product_name_snapshot,o.quantity,t.refund_amount
              FROM refund_target_order t JOIN orders o ON o.id=t.order_id
             WHERE t.refund_request_id=? AND t.seller_id_snapshot=? ORDER BY t.order_id
            """, (rs, row) -> new Target((Long) rs.getObject(1), rs.getLong(2), rs.getLong(3),
                rs.getString(4), rs.getInt(5), rs.getLong(6)), id, sellerId);
        long sellerAmount = 0;
        try {
            for (Target target : targets) sellerAmount = Math.addExact(sellerAmount, target.refundAmount());
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.CONFLICT, "환불 금액 범위를 초과했습니다.");
        }
        Long cumulative = jdbc.queryForObject("""
            SELECT COALESCE(SUM(t.refund_amount),0) FROM refund_request r
            JOIN refund_target_order t ON t.refund_request_id=r.id
            WHERE r.payment_group_id=? AND r.status='SUCCESS' AND t.seller_id_snapshot=?
            """, Long.class, request.groupId(), sellerId);
        return new SellerRefundView(id, sellerAmount, cumulative == null ? 0L : cumulative,
            request.status(), request.requestedAt(), request.resolvedAt(), targets, recoveryView(request));
    }

    private static RecoveryView recoveryView(RequestRow request) {
        if (request.status() == RefundStatus.SUCCESS || request.status() == RefundStatus.FAILED) return null;
        return new RecoveryView(request.retryCount(), request.retryCount() == RefundRetryPolicy.MAX_RETRIES,
            request.nextActionAt());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    static boolean isRefundWindowOpen(Instant now, Instant resolvedAt) {
        return now.isBefore(resolvedAt.plus(REFUND_WINDOW));
    }

    private static String fingerprint(Long groupId, List<Long> cartIds) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((groupId + ":" + cartIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private record ExistingRequest(long id, long groupId, String fingerprint, RefundStatus status) { }
    private record RequestRow(long groupId, long amount, RefundStatus status, Instant requestedAt, Instant resolvedAt,
                              int retryCount, Instant nextActionAt) { }
    public record RecoveryView(int retryCount, boolean retryExhausted, Instant nextActionAt) { }
    public record Target(Long cartItemId, long orderId, long productId, String productName, int quantity, long refundAmount) { }
    public record RefundView(long id, String paymentGroupNumber, long refundAmount, long cumulativeRefundAmount, RefundStatus status,
        Instant requestedAt, Instant resolvedAt, List<Target> targets, RecoveryView recovery) { }
    public record RequestResult(RefundView refund, boolean created) { }
    public record SellerRefundView(long id, long refundAmount, long cumulativeRefundAmount, RefundStatus status, Instant requestedAt,
        Instant resolvedAt, List<Target> targets, RecoveryView recovery) { }
}
