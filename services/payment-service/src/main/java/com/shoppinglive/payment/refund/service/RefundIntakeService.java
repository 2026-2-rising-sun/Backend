package com.shoppinglive.payment.refund.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.outbox.EventOutbox;
import com.shoppinglive.payment.payment.entity.Payment;
import com.shoppinglive.payment.payment.repository.PaymentRepository;
import com.shoppinglive.payment.refund.entity.Refund;
import com.shoppinglive.payment.refund.entity.RefundPurpose;
import com.shoppinglive.payment.refund.entity.RefundRequestConflict;
import com.shoppinglive.payment.refund.entity.RefundStatus;
import com.shoppinglive.payment.refund.event.RefundResultRecorded;
import com.shoppinglive.payment.refund.repository.RefundRepository;
import com.shoppinglive.payment.refund.repository.RefundRequestConflictRepository;
import com.shoppinglive.payment.refund.service.compensation.CompensationExecutor;
import com.shoppinglive.payment.refund.service.user.UserRefundExecutor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 요청을 refund_request_id 기준으로 한 번만 접수한다(REF-ACCEPT-01·02·05·06). 돈을 움직이지 않는 실패는 접수와 같은 트랜잭션에서 결과
 * outbox까지 쓴다. 실제 환불 실행은 커밋 뒤 {@link #execute}에서 목적별로 시작한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundIntakeService {
    static final String PAYMENT_NOT_FOUND = "PAYMENT_NOT_FOUND";
    static final String PAYMENT_ID_REQUIRED = "PAYMENT_ID_REQUIRED";
    public static final String PAYMENT_MISMATCH = "PAYMENT_MISMATCH";
    static final String COMPENSATION_IN_PROGRESS = "COMPENSATION_IN_PROGRESS";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RefundRepository refunds;
    private final RefundRequestConflictRepository conflicts;
    private final PaymentRepository payments;
    private final EventOutbox outbox;
    private final UserRefundExecutor userRefunds;
    private final CompensationExecutor compensations;

    /**
     * 요청 payload 하나를 접수한다. 형식은 {@code RefundRequestParser}가 이미 확인했다.
     *
     * @return 이번에 새로 접수한 PROCESSING 환불. 실패로 답했거나 이미 받은 요청이면 비어 있다
     */
    @Transactional
    public Optional<Refund> receive(JsonNode request) {
        String id = request.get("refundRequestId").asText();
        String orderRef = request.get("orderRef").asText();
        RefundPurpose purpose = RefundPurpose.valueOf(request.get("purpose").asText());
        String body = write(request);
        UUID executionId = UUID.randomUUID();

        Optional<Payment> payment = payments.findByOrderRef(orderRef);
        Long paymentId = payment.map(Payment::getId).orElse(null);
        String failure = identityFailure(request, paymentId);
        if (failure == null) {
            if (insert(id, executionId, paymentId, purpose, request, body, null, null, null) == 1) {
                return refunds.findByRefundRequestId(id);
            }
            if (refunds.findByRefundRequestId(id).isPresent()) {
                return redelivered(id, request, body);
            }
            // 같은 id가 없는데 0행이면 부분 UNIQUE(결제당 진행 중 보상 1건)에 걸렸다
            failure = COMPENSATION_IN_PROGRESS;
        }
        Instant resolvedAt = Instant.now();
        String result =
                write(RefundResultRecorded.failed(request, executionId, failure, resolvedAt));
        if (insert(id, executionId, paymentId, purpose, request, body, failure, result, resolvedAt)
                == 0) {
            return redelivered(id, request, body);
        }
        log.error(
                "[사람 확인 필요] 환불 요청을 처리할 수 없어 돈을 움직이지 않고 FAILED로 답한다: refundRequestId={}, orderRef={},"
                        + " purpose={}, failureCode={}",
                id,
                orderRef,
                purpose,
                failure);
        publish(result);
        return Optional.empty();
    }

    /**
     * 새로 접수한 환불의 실행을 목적별로 시작한다. 토스를 부르므로 트랜잭션 밖에서 부른다. 실행이 붙기 전까지는 접수만 하고 결과를 보내지 않는다.
     *
     * <p>새로 넣은 행에서만 불린다. 재수신·커밋 뒤 장애·이 메서드의 예외로는 다시 불리지 않으므로 끝나지 못한 PROCESSING은 재처리
     * 스케줄러(REF-EXEC-01)가 집어야 한다.
     */
    public void execute(Refund refund) {
        switch (refund.getPurpose()) {
            case USER_REFUND -> userRefunds.execute(refund); // #370
            case SYSTEM_COMPENSATION -> compensations.execute(refund); // #386
        }
    }

    /** 요청을 이 결제에 묶을 수 없으면 실패 코드. 요청에 없는 paymentId는 결과에도 채우지 않는다. */
    private static String identityFailure(JsonNode request, Long paymentId) {
        if (paymentId == null) {
            return PAYMENT_NOT_FOUND;
        }
        if (!request.has("paymentId")) {
            return PAYMENT_ID_REQUIRED;
        }
        return request.get("paymentId").longValue() == paymentId ? null : PAYMENT_MISMATCH;
    }

    /** 이미 받은 요청. 같은 본문이면 저장한 결과를 그대로 다시 보내고, 다른 본문이면 충돌만 기록한다(결과를 보내면 Core 파티션이 막힌다). */
    private Optional<Refund> redelivered(String id, JsonNode request, String body) {
        Refund refund = refunds.findByRefundRequestId(id).orElseThrow();
        if (!read(refund.getRequestPayload()).equals(request)) {
            conflicts.save(new RefundRequestConflict(id, body));
            log.error(
                    "[사람 확인 필요] {}: 같은 환불 요청 ID에 다른 본문이 왔다. 결과는 보내지 않는다: refundRequestId={}, status={}",
                    RefundRequestConflict.FAILURE_CODE,
                    id,
                    refund.getStatus());
            return Optional.empty();
        }
        if (refund.getResultPayload() != null) {
            publish(refund.getResultPayload());
        }
        return Optional.empty();
    }

    private int insert(
            String id,
            UUID executionId,
            Long paymentId,
            RefundPurpose purpose,
            JsonNode request,
            String body,
            String failure,
            String result,
            Instant resolvedAt) {
        return refunds.insertIfAbsent(
                id,
                executionId,
                paymentId,
                purpose.name(),
                request.get("amount").longValue(),
                body,
                (failure == null ? RefundStatus.PROCESSING : RefundStatus.FAILED).name(),
                failure,
                result == null ? 0 : 1,
                result,
                resolvedAt);
    }

    /** 저장한 결과 문자열을 그대로 싣는다. 같은 resultVersion은 같은 payload여야 Core가 중복으로 받는다. */
    private void publish(String result) {
        outbox.append(
                RefundResultRecorded.TOPIC,
                RefundResultRecorded.envelope(read(result), Instant.now()));
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
