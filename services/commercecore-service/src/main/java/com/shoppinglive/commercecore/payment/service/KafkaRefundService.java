package com.shoppinglive.commercecore.payment.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.shoppinglive.commercecore.coupon.service.CouponReservationService;
import com.shoppinglive.commercecore.order.entity.*;
import com.shoppinglive.commercecore.order.event.PaymentGroupEventPublisher;
import com.shoppinglive.commercecore.order.repository.*;
import com.shoppinglive.commercecore.order.service.PurchaseGuard;
import com.shoppinglive.commercecore.payment.dto.*;
import com.shoppinglive.commercecore.payment.relay.*;
import com.shoppinglive.commercecore.sku.service.SkuService;
import com.shoppinglive.common.core.*;
import com.shoppinglive.common.events.EventEnvelope;
import com.shoppinglive.common.outbox.EventOutbox;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 신규 주문서의 공통 Kafka 환불. 외부 금전 실행과 로컬 자원 반영을 분리한다. */
@Service
public class KafkaRefundService {
    public record RequestResult(
            com.shoppinglive.commercecore.payment.dto.RefundView refund, boolean created) {}

    private final JdbcTemplate jdbc;
    private final PaymentGroupRepository groups;
    private final OrderJpaRepository orders;
    private final PurchaseGuard guard;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final EventOutbox outbox;
    private final PaymentRelayStore relay;
    private final RefundIntentStore intents;
    private final RefundQueryService queries;
    private final SkuService skus;
    private final CouponReservationService coupons;
    private final OrderPaymentResultApplier outcomes;
    private final PaymentGroupEventPublisher events;
    @PersistenceContext private EntityManager em;

    public KafkaRefundService(
            JdbcTemplate jdbc,
            PaymentGroupRepository groups,
            OrderJpaRepository orders,
            PurchaseGuard guard,
            TransactionTemplate tx,
            ObjectMapper mapper,
            EventOutbox outbox,
            PaymentRelayStore relay,
            RefundIntentStore intents,
            RefundQueryService queries,
            SkuService skus,
            CouponReservationService coupons,
            OrderPaymentResultApplier outcomes,
            PaymentGroupEventPublisher events) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.orders = orders;
        this.guard = guard;
        this.tx = tx;
        this.mapper = mapper;
        this.outbox = outbox;
        this.relay = relay;
        this.intents = intents;
        this.queries = queries;
        this.skus = skus;
        this.coupons = coupons;
        this.outcomes = outcomes;
        this.events = events;
    }

    public boolean supports(String member, String number) {
        return owned(member, number).isOrderSheet();
    }

    public RequestResult request(String member, String number, String key, RefundSelection input) {
        if (key == null || key.isBlank() || key.length() > 64)
            throw invalid("Idempotency-Key는 1~64자여야 합니다.");
        var found = owned(member, number);
        guard.ensure(member);
        return tx.execute(
                t -> {
                    guard.lock(member);
                    var group = locked(found.getId());
                    String fingerprint = hash(group.getId() + ":" + json(input));
                    var existing =
                            jdbc.queryForList(
                                    "SELECT id,request_fingerprint FROM refund_request WHERE member_id=? AND idempotency_key=?",
                                    member,
                                    key);
                    if (!existing.isEmpty()) {
                        require(
                                fingerprint.equals(existing.getFirst().get("request_fingerprint")),
                                "같은 키의 환불 원문이 다릅니다.");
                        return new RequestResult(
                                queries.load(
                                        ((Number) existing.getFirst().get("id")).longValue(),
                                        number),
                                false);
                    }
                    require(group.isOrderSheet(), "신규 주문서 환불만 지원합니다.");
                    require(!intents.fenced(group.getId()), "시스템 보상 중에는 사용자 환불을 접수할 수 없습니다.");
                    require(
                            Set.of(OrderStatus.PAID, OrderStatus.PARTIALLY_REFUNDED)
                                    .contains(group.getStatus()),
                            "결제 완료된 주문만 환불할 수 있습니다.");
                    require(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM refund_request WHERE payment_group_id=? AND status IN ('PROCESSING','UNKNOWN')",
                                            Long.class,
                                            group.getId())
                                    == 0,
                            "같은 결제의 미완료 환불이 있습니다.");
                    var binding = relay.binding(group.getId());
                    require(
                            binding != null
                                    && binding.paymentId() != null
                                    && binding.approvedAt() != null
                                    && RefundWindowPolicy.isOpen(
                                            Instant.now(), binding.approvedAt()),
                            "환불 가능 결제 이력이나 기간을 확인할 수 없습니다.");
                    var all = orders.findByPaymentGroupIdOrderByIdAsc(group.getId());
                    Map<Long, Integer> selected = new TreeMap<>();
                    if (input.mode().equals("ALL"))
                        for (var o : all) {
                            if (remaining(o) > 0) selected.put(o.getId(), remaining(o));
                        }
                    else if (input.mode().equals("ITEMS"))
                        for (var item : input.items()) {
                            var o =
                                    all.stream()
                                            .filter(v -> v.getId() == item.orderId())
                                            .findFirst()
                                            .orElseThrow(() -> missing("주문 항목을 찾을 수 없습니다."));
                            selected.put(
                                    o.getId(),
                                    item.quantity() == null ? remaining(o) : item.quantity());
                        }
                    else
                        for (long cart : input.cartItemIds()) {
                            var matches =
                                    all.stream()
                                            .filter(
                                                    o ->
                                                            Objects.equals(
                                                                    o.getSourceCartItemId(), cart))
                                            .toList();
                            if (matches.isEmpty()) throw missing("장바구니 출처를 찾을 수 없습니다.");
                            require(matches.size() == 1, "장바구니 출처가 모호합니다.");
                            var o = matches.getFirst();
                            selected.put(o.getId(), remaining(o));
                        }
                    require(!selected.isEmpty(), "환불 가능한 잔여 수량이 없습니다.");
                    List<Target> targets = new ArrayList<>();
                    long amount = 0;
                    for (var o : all)
                        if (selected.containsKey(o.getId())) {
                            int quantity = selected.get(o.getId());
                            require(quantity > 0 && quantity <= remaining(o), "잔여 환불 수량을 초과했습니다.");
                            require(
                                    Set.of(OrderStatus.PAID, OrderStatus.PARTIALLY_REFUNDED)
                                            .contains(o.getStatus()),
                                    "항목을 환불할 수 없는 상태입니다.");
                            long paid = o.getPayableAmount() - succeededAmount(o.getId());
                            long money =
                                    OrderRefundAmounts.refund(o.getUnitPrice(), quantity, paid);
                            amount = Math.addExact(amount, money);
                            targets.add(new Target(o, quantity, money));
                        }
                    require(
                            input.expectedAmount() == null || input.expectedAmount() == amount,
                            "예상 환불 금액이 서버 계산과 다릅니다.");
                    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                    String uuid = UUID.randomUUID().toString();
                    long id =
                            jdbc.queryForObject(
                                    "INSERT INTO refund_request(payment_group_id,member_id,idempotency_key,request_fingerprint,refund_amount,status,requested_at,created_at,updated_at) VALUES(?,?,?,?,?,'PROCESSING',?,?,?) RETURNING id",
                                    Long.class,
                                    group.getId(),
                                    member,
                                    key,
                                    fingerprint,
                                    amount,
                                    Timestamp.from(now),
                                    Timestamp.from(now),
                                    Timestamp.from(now));
                    for (var target : targets) {
                        var o = target.order();
                        jdbc.update(
                                "INSERT INTO refund_target_order(refund_request_id,order_id,product_id_snapshot,seller_id_snapshot,refund_amount,created_at,quantity,previous_status) VALUES(?,?,?,?,?,?,?,?)",
                                id,
                                o.getId(),
                                o.getProductIdSnapshot(),
                                o.getSellerIdSnapshot(),
                                target.amount(),
                                Timestamp.from(now),
                                target.quantity(),
                                o.getStatus().name());
                        require(
                                orders.transitionStatus(
                                                o.getId(), o.getStatus().name(), "CANCEL_PENDING")
                                        == 1,
                                "주문 항목 상태가 변경되었습니다.");
                    }
                    group.transition(OrderStatus.CANCEL_PENDING);
                    groups.saveAndFlush(group);
                    save(
                            group,
                            id,
                            payload(
                                    group,
                                    uuid,
                                    "USER_REFUND",
                                    "CUSTOMER_REQUEST",
                                    binding.paymentId(),
                                    targets,
                                    now));
                    return new RequestResult(queries.load(id, number), true);
                });
    }

    /** 승인 작업 응답 유실 또는 닫힌 주문의 늦은 승인에서만 호출한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void compensateLocked(PaymentGroup group, String reason) {
        if (intents.fenced(group.getId())
                || Set.of(
                                OrderStatus.PAID,
                                OrderStatus.PARTIALLY_REFUNDED,
                                OrderStatus.CANCEL_PENDING,
                                OrderStatus.REFUNDED)
                        .contains(group.getStatus())) return;
        var binding = relay.binding(group.getId());
        require(binding != null && binding.paymentId() != null, "결제 ID가 없는 보상 요청은 만들 수 없습니다.");
        // 결제 대기 주문은 승인 응답 유실로 보상할 수 없다. 상태를 바꾸지 않고 원문만 남지 않게 막는다.
        require(group.getStatus() != OrderStatus.PENDING_PAYMENT, "결제 대기 주문은 보상할 수 없습니다.");
        require(
                jdbc.queryForObject(
                                "SELECT COUNT(*) FROM payment_relay_refund WHERE payment_group_id=?",
                                Long.class,
                                group.getId())
                        == 0,
                "기존 보상 원문은 별도 복구가 필요합니다.");
        if (group.getStatus() == OrderStatus.PAYMENT_CONFIRMING) {
            for (var item : orders.findByPaymentGroupIdOrderByIdAsc(group.getId()))
                require(
                        orders.transitionStatus(
                                        item.getId(),
                                        group.getStatus().name(),
                                        "COMPENSATION_PENDING")
                                == 1,
                        "보상 대기 항목 상태가 다릅니다.");
            group.transition(OrderStatus.COMPENSATION_PENDING);
            groups.saveAndFlush(group);
        }
        var targets =
                orders.findByPaymentGroupIdOrderByIdAsc(group.getId()).stream()
                        .map(o -> new Target(o, o.getQuantity(), o.getPayableAmount()))
                        .toList();
        save(
                group,
                null,
                payload(
                        group,
                        UUID.randomUUID().toString(),
                        "SYSTEM_COMPENSATION",
                        reason,
                        binding.paymentId(),
                        targets,
                        Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private ObjectNode payload(
            PaymentGroup group,
            String id,
            String purpose,
            String reason,
            Long paymentId,
            List<Target> targets,
            Instant now) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("refundRequestId", id).put("orderRef", group.getGroupNumber());
        payload.put("paymentId", paymentId);
        payload.put("purpose", purpose)
                .put("reason", reason)
                .put("currency", "KRW")
                .put(
                        "amount",
                        targets.stream().mapToLong(Target::amount).reduce(0, Math::addExact));
        ArrayNode items = payload.putArray("items");
        var sellers =
                jdbc.queryForList(
                        "SELECT seller_id,seller_order_number FROM seller_order WHERE payment_group_id=? ORDER BY seller_order_number",
                        group.getId());
        for (var seller : sellers) {
            var parts =
                    targets.stream()
                            .filter(
                                    v ->
                                            Objects.equals(
                                                    v.order().getSellerIdSnapshot(),
                                                    seller.get("seller_id")))
                            .sorted(Comparator.comparing(v -> v.order().getId()))
                            .toList();
            if (parts.isEmpty()) continue;
            var item = items.addObject();
            item.put("sellerOrderNumber", (String) seller.get("seller_order_number"))
                    .put(
                            "amount",
                            parts.stream().mapToLong(Target::amount).reduce(0, Math::addExact));
            var children = item.putArray("orderItems");
            for (var p : parts)
                children.addObject()
                        .put("orderItemId", p.order().getId())
                        .put("quantity", p.quantity())
                        .put("amount", p.amount());
        }
        require(
                items.size() > 0
                        && items.findValues("orderItems").stream().mapToInt(JsonNode::size).sum()
                                == targets.size(),
                "판매자 주문 원문이 대상과 다릅니다.");
        payload.put("requestedAt", now.toString());
        return payload;
    }

    private void save(PaymentGroup group, Long userId, ObjectNode payload) {
        jdbc.update(
                "INSERT INTO core_refund_intent(refund_request_id,payment_group_id,user_request_id,purpose,request_payload,status) VALUES(?,?,?,?,?,'REQUESTED')",
                payload.path("refundRequestId").asText(),
                group.getId(),
                userId,
                payload.path("purpose").asText(),
                json(payload));
        outbox.append(
                "commerce.refund.requests.v1",
                EventEnvelope.create(
                        "RefundRequested",
                        1,
                        "commercecore-service",
                        group.getGroupNumber(),
                        Instant.now(),
                        payload));
    }

    public boolean result(String key, JsonNode incoming) {
        JsonNode event = tree(json(incoming));
        contract(
                event.path("eventType").asText().equals("RefundResultRecorded")
                        && event.path("schemaVersion").isInt()
                        && event.path("schemaVersion").intValue() == 1
                        && event.path("source").asText().equals("payment-service"),
                "환불 결과 봉투가 올바르지 않습니다.");
        uuid(event.path("eventId"));
        instant(event.path("occurredAt"));
        contract(event.isObject() && event.size() == 7, "환불 결과 봉투 필드가 올바르지 않습니다.");
        JsonNode result = event.path("payload");
        Set<String> resultFields =
                Set.of(
                        "refundRequestId",
                        "orderRef",
                        "paymentId",
                        "purpose",
                        "reason",
                        "currency",
                        "amount",
                        "items",
                        "requestedAt",
                        "executionId",
                        "resultVersion",
                        "status",
                        "refundedAmount",
                        "resolvedAt",
                        "nextActionAt",
                        "failureCode");
        contract(result.isObject(), "환불 결과 본문이 필요합니다.");
        result.fieldNames()
                .forEachRemaining(
                        field -> contract(resultFields.contains(field), "지원하지 않는 결과 필드입니다."));
        for (String field : resultFields)
            if (!field.equals("paymentId")) contract(result.has(field), "필수 결과 필드가 없습니다: " + field);
        contract(
                result.path("failureCode").isNull()
                        || result.path("failureCode").isTextual()
                                && !result.path("failureCode").asText().isBlank()
                                && result.path("failureCode").asText().length() <= 100,
                "실패 사유 형식이 올바르지 않습니다.");
        String requestId = result.path("refundRequestId").asText();
        contract(
                result.path("refundRequestId").isTextual()
                        && result.path("refundRequestId").asText().matches("[A-Za-z0-9_-]{1,64}"),
                "환불 요청 ID 형식이 올바르지 않습니다.");
        contract(
                key != null
                        && key.equals(event.path("aggregateId").asText())
                        && key.equals(result.path("orderRef").asText()),
                "환불 결과 키가 주문과 다릅니다.");
        var found =
                jdbc.queryForList(
                        "SELECT payment_group_id FROM core_refund_intent WHERE refund_request_id=?",
                        requestId);
        contract(!found.isEmpty(), "저장된 환불 요청이 없습니다.");
        var group =
                groups.findById(((Number) found.getFirst().get("payment_group_id")).longValue())
                        .orElseThrow();
        guard.ensure(group.getMemberId());
        return tx.execute(
                t -> {
                    guard.lock(group.getMemberId());
                    var locked = locked(group.getId());
                    return applyLocked(locked, event, result);
                });
    }

    private boolean applyLocked(PaymentGroup group, JsonNode event, JsonNode result) {
        String id = result.path("refundRequestId").asText();
        var row =
                jdbc.queryForMap(
                        "SELECT * FROM core_refund_intent WHERE refund_request_id=? FOR UPDATE",
                        id);
        JsonNode request = tree((String) row.get("request_payload"));
        validateOriginal(request, result);
        ((ObjectNode) result).set("items", normalizeItems(result.path("items")));
        if (!request.has("paymentId") && result.hasNonNull("paymentId")) {
            var binding = relay.binding(group.getId());
            require(
                    binding != null
                            && Objects.equals(
                                    binding.paymentId(), result.path("paymentId").longValue()),
                    "새 원결제 ID를 저장된 연결에서 확인할 수 없습니다.");
        }
        String status = result.path("status").asText();
        require(
                Set.of("UNKNOWN", "SUCCEEDED", "FAILED", "NO_CHARGE").contains(status),
                "지원하지 않는 환불 결과 상태입니다.");
        uuid(result.path("executionId"));
        long version = positive(result.path("resultVersion"));
        long refunded = nonnegative(result.path("refundedAmount"));
        boolean system = request.path("purpose").asText().equals("SYSTEM_COMPENSATION");
        require(!status.equals("NO_CHARGE") || system, "사용자 환불에는 NO_CHARGE를 적용할 수 없습니다.");
        if (status.equals("SUCCEEDED"))
            require(refunded == request.path("amount").longValue(), "환불 성공 금액이 요청과 다릅니다.");
        if (status.equals("NO_CHARGE"))
            require(
                    result.path("failureCode").asText().equals("NO_CHARGE"),
                    "미승인 종료 사유가 올바르지 않습니다.");
        if (Set.of("FAILED", "NO_CHARGE").contains(status)) {
            require(refunded == 0, "확정 실패·미승인 결과는 환불 금액이 0이어야 합니다.");
            require(
                    result.path("failureCode").isTextual()
                            && !result.path("failureCode").asText().isBlank(),
                    "실패 사유가 필요합니다.");
        }
        if (status.equals("UNKNOWN")) {
            require(
                    refunded <= request.path("amount").longValue()
                            && result.path("resolvedAt").isNull(),
                    "미확정 금액·시각이 올바르지 않습니다.");
            instant(result.path("nextActionAt"));
        } else {
            instant(result.path("resolvedAt"));
            require(result.path("nextActionAt").isNull(), "확정 결과의 재처리 시각은 비어야 합니다.");
        }
        var seen =
                jdbc.queryForList(
                        "SELECT payload FROM core_refund_result_event WHERE event_id=?",
                        event.path("eventId").asText());
        if (!seen.isEmpty()) {
            require(
                    tree((String) seen.getFirst().get("payload")).equals(event),
                    "같은 이벤트 ID의 원문이 다릅니다.");
            return false;
        }
        long previous = ((Number) row.get("result_version")).longValue();
        String execution = (String) row.get("execution_id");
        require(
                execution == null || execution.equals(result.path("executionId").asText()),
                "환불 실행 ID가 변경되었습니다.");
        if (version == previous) {
            require(tree((String) row.get("result_payload")).equals(result), "같은 결과 버전의 원문이 다릅니다.");
            remember(event, id);
            return false;
        }
        if (version < previous) {
            require(
                    status.equals("UNKNOWN")
                            || system && status.equals("FAILED")
                            || Objects.equals(row.get("outcome"), status),
                    "역순 확정 결과가 현재 결과와 모순됩니다.");
            remember(event, id);
            return false;
        }
        if ("COMPLETED".equals(row.get("status"))
                || !system && "FAILED".equals(row.get("status"))) {
            require(Objects.equals(row.get("outcome"), status), "확정 결과를 다른 결과로 변경할 수 없습니다.");
            jdbc.update(
                    "UPDATE core_refund_intent SET result_version=?,result_payload=? WHERE refund_request_id=?",
                    version,
                    json(result),
                    id);
            remember(event, id);
            return false;
        }
        if (system) {
            if (Set.of("SUCCEEDED", "NO_CHARGE").contains(status)
                    && group.getStatus() == OrderStatus.COMPENSATION_PENDING)
                outcomes.completeCompensation(group);
        } else applyUser(group, ((Number) row.get("user_request_id")).longValue(), status, result);
        jdbc.update(
                "UPDATE core_refund_intent SET status=?,outcome=?,execution_id=?,result_version=?,result_payload=?,completed_at=?,failure_code=? WHERE refund_request_id=?",
                status.equals("UNKNOWN")
                        ? "UNKNOWN"
                        : status.equals("FAILED") ? "FAILED" : "COMPLETED",
                status,
                result.path("executionId").asText(),
                version,
                json(result),
                Set.of("SUCCEEDED", "NO_CHARGE").contains(status)
                        ? Timestamp.from(instant(result.path("resolvedAt")))
                        : null,
                result.path("failureCode").isNull() ? null : result.path("failureCode").asText(),
                id);
        remember(event, id);
        return true;
    }

    private void applyUser(PaymentGroup group, long id, String status, JsonNode result) {
        require(group.getStatus() == OrderStatus.CANCEL_PENDING, "사용자 환불 대기 주문이 아닙니다.");
        if (!status.equals("UNKNOWN")) {
            var targets =
                    jdbc.queryForList(
                            "SELECT t.order_id,t.quantity,t.previous_status,COALESCE(o.sku_id,o.sales_info_id) AS sku_id,o.quantity AS original_quantity FROM refund_target_order t JOIN orders o ON o.id=t.order_id WHERE t.refund_request_id=? ORDER BY COALESCE(o.sku_id,o.sales_info_id),o.id",
                            id);
            for (var target : targets) {
                long orderId = ((Number) target.get("order_id")).longValue();
                int q = ((Number) target.get("quantity")).intValue();
                String next = (String) target.get("previous_status");
                if (status.equals("SUCCEEDED")) {
                    int total = succeededQuantity(orderId) + q;
                    require(
                            total <= ((Number) target.get("original_quantity")).intValue(),
                            "환불 누적 수량을 초과했습니다.");
                    next =
                            total == ((Number) target.get("original_quantity")).intValue()
                                    ? "REFUNDED"
                                    : "PARTIALLY_REFUNDED";
                    skus.restock(((Number) target.get("sku_id")).longValue(), q);
                }
                require(
                        orders.transitionStatus(orderId, "CANCEL_PENDING", next) == 1,
                        "환불 대상 상태가 변경되었습니다.");
            }
        }
        jdbc.update(
                "UPDATE refund_request SET status=?,resolved_at=?,result_detail=?,next_action_at=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PROCESSING','UNKNOWN')",
                status.equals("SUCCEEDED") ? "SUCCESS" : status,
                status.equals("UNKNOWN")
                        ? null
                        : Timestamp.from(instant(result.path("resolvedAt"))),
                result.path("failureCode").asText(null),
                status.equals("UNKNOWN")
                        ? Timestamp.from(instant(result.path("nextActionAt")))
                        : Timestamp.from(instant(result.path("resolvedAt"))),
                id);
        if (!status.equals("UNKNOWN")) {
            long remaining =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM orders WHERE payment_group_id=? AND status<>'REFUNDED'",
                            Long.class,
                            group.getId());
            long successes =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM refund_request WHERE payment_group_id=? AND status='SUCCESS'",
                            Long.class,
                            group.getId());
            group.transition(
                    remaining == 0
                            ? OrderStatus.REFUNDED
                            : successes > 0 ? OrderStatus.PARTIALLY_REFUNDED : OrderStatus.PAID);
            groups.saveAndFlush(group);
            if (remaining == 0)
                coupons.restoreForOrder(group.getMemberId(), group.getCouponId(), group.getId());
            if (status.equals("SUCCEEDED"))
                events.refunded(group.getId(), id, result.path("amount").longValue());
        }
    }

    private void validateOriginal(JsonNode request, JsonNode result) {
        for (String field :
                List.of(
                        "refundRequestId",
                        "orderRef",
                        "purpose",
                        "reason",
                        "currency",
                        "amount",
                        "requestedAt"))
            require(request.path(field).equals(result.path(field)), "환불 결과 원문 불일치: " + field);
        if (request.has("paymentId"))
            require(request.get("paymentId").equals(result.path("paymentId")), "원결제 ID가 다릅니다.");
        else if (result.has("paymentId"))
            require(
                    result.get("paymentId").isNull()
                            || result.get("paymentId").isIntegralNumber()
                                    && result.get("paymentId").longValue() > 0,
                    "원결제 ID가 올바르지 않습니다.");
        require(
                normalizeItems(request.path("items")).equals(normalizeItems(result.path("items"))),
                "환불 결과 대상·수량·금액이 다릅니다.");
    }

    private JsonNode normalizeItems(JsonNode input) {
        require(input.isArray() && !input.isEmpty(), "환불 판매자 항목이 필요합니다.");
        List<JsonNode> sellers = new ArrayList<>();
        Set<String> sellerIds = new HashSet<>();
        Set<Long> itemIds = new HashSet<>();
        for (JsonNode value : input) {
            require(
                    value.isObject()
                            && value.size() == 3
                            && value.path("sellerOrderNumber").isTextual()
                            && sellerIds.add(value.path("sellerOrderNumber").asText()),
                    "중복·잘못된 판매자 항목입니다.");
            nonnegative(value.path("amount"));
            var children = value.path("orderItems");
            require(children.isArray() && !children.isEmpty(), "환불 주문 항목이 필요합니다.");
            List<JsonNode> parts = new ArrayList<>();
            for (JsonNode child : children) {
                require(
                        child.isObject()
                                && child.size() == 3
                                && itemIds.add(positive(child.path("orderItemId"))),
                        "중복·잘못된 주문 항목입니다.");
                require(positive(child.path("quantity")) <= Integer.MAX_VALUE, "수량 범위를 초과했습니다.");
                nonnegative(child.path("amount"));
                parts.add(child);
            }
            parts.sort(Comparator.comparingLong(v -> v.path("orderItemId").longValue()));
            ObjectNode copy = value.deepCopy();
            copy.set("orderItems", mapper.valueToTree(parts));
            sellers.add(copy);
        }
        sellers.sort(Comparator.comparing(v -> v.path("sellerOrderNumber").asText()));
        return mapper.valueToTree(sellers);
    }

    private void remember(JsonNode event, String id) {
        jdbc.update(
                "INSERT INTO core_refund_result_event(event_id,refund_request_id,payload) VALUES(?,?,?)",
                event.path("eventId").asText(),
                id,
                json(event));
    }

    public int succeededQuantity(long orderId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(t.quantity),0) FROM refund_target_order t JOIN refund_request r ON r.id=t.refund_request_id WHERE t.order_id=? AND r.status='SUCCESS'",
                Integer.class,
                orderId);
    }

    public long succeededAmount(long orderId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(t.refund_amount),0) FROM refund_target_order t JOIN refund_request r ON r.id=t.refund_request_id WHERE t.order_id=? AND r.status='SUCCESS'",
                Long.class,
                orderId);
    }

    private int remaining(Order order) {
        return order.getQuantity() - succeededQuantity(order.getId());
    }

    private PaymentGroup owned(String member, String number) {
        return groups.findByGroupNumberAndMemberId(number, member)
                .orElseThrow(() -> missing("결제 묶음을 찾을 수 없습니다."));
    }

    private PaymentGroup locked(long id) {
        var group = groups.lockById(id).orElseThrow();
        em.refresh(group);
        return group;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("환불 원문을 직렬화할 수 없습니다.", e);
        }
    }

    private JsonNode tree(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("저장된 환불 원문을 읽을 수 없습니다.", e);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long positive(JsonNode node) {
        long value = nonnegative(node);
        require(value > 0, "양수 결과 값이 필요합니다.");
        return value;
    }

    private static long nonnegative(JsonNode node) {
        require(
                node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0,
                "정수 결과 값이 필요합니다.");
        return node.longValue();
    }

    private static Instant instant(JsonNode value) {
        try {
            contract(value.isTextual(), "결과 시각이 필요합니다.");
            return Instant.parse(value.asText());
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.shoppinglive.common.kafka.ContractViolationException(
                    "결과 시각 형식이 올바르지 않습니다.");
        }
    }

    private static void uuid(JsonNode value) {
        try {
            contract(
                    value.isTextual()
                            && UUID.fromString(value.asText())
                                    .toString()
                                    .equalsIgnoreCase(value.asText()),
                    "결과 식별자가 UUID 형식이 아닙니다.");
        } catch (RuntimeException e) {
            throw new com.shoppinglive.common.kafka.ContractViolationException(
                    "결과 식별자가 UUID 형식이 아닙니다.");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw conflict(message);
    }

    /** 소비 메시지의 계약 위반. 재시도 없이 DLT로 보낸다. */
    private static void contract(boolean condition, String message) {
        if (!condition) throw new com.shoppinglive.common.kafka.ContractViolationException(message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }

    private static BusinessException missing(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    private record Target(Order order, int quantity, long amount) {}
}
