package com.shoppinglive.payment.payment.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.web.CorrelationId;
import com.shoppinglive.payment.config.PaymentError;
import com.shoppinglive.payment.config.PaymentException;
import com.shoppinglive.payment.payment.dto.ApprovalRequest;
import com.shoppinglive.payment.payment.dto.ApprovalResponse;
import com.shoppinglive.payment.payment.dto.DeclinedResponse;
import com.shoppinglive.payment.payment.service.ApprovalProperties;
import com.shoppinglive.payment.payment.service.ApprovalResult;
import com.shoppinglive.payment.payment.service.ApprovalService;
import com.shoppinglive.payment.payment.service.ApprovalService.Answered;
import com.shoppinglive.payment.payment.service.ApprovalService.ApproveCommand;
import com.shoppinglive.payment.payment.service.ApprovalService.Attempt;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 승인 API. ① 검증·APPROVING 전환은 요청 스레드에서, ②③ 토스 호출·결과 반영은 가상 스레드에서 한다. 요청 스레드는 진입 시각부터 최대 {@code
 * payment.approval.response-wait}(기본 8초)만 기다리고, 넘으면 202 PENDING으로 답한다. 처리는 계속돼 결과가 DB·이벤트에
 * 반영된다(PAY-CONFIRM-11).
 */
@RestController
@RequestMapping("/v1/internal/payments")
@EnableConfigurationProperties(ApprovalProperties.class)
public class ApprovalController {
    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalService approvals;
    private final long responseWaitNanos;
    // 동시 실행 수는 제한하지 않는다(ADR 0337-5 정정). Spring Executor 빈으로 두면 Boot 기본 실행기가 물러나므로 필드로 둔다
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public ApprovalController(ApprovalService approvals, ApprovalProperties properties) {
        this.approvals = approvals;
        this.responseWaitNanos = properties.responseWait().toNanos();
    }

    /** 종료 때 진행 중인 처리를 기다리지 않는다. 끝나지 못한 결제는 APPROVING에 남아 정체 정리(#349)가 확정한다. */
    @PreDestroy
    void shutdown() {
        workers.shutdown();
    }

    @PostMapping("/{orderRef}/approval")
    public ResponseEntity<ApiResponse<Object>> approve(
            @PathVariable String orderRef, @Valid @RequestBody ApprovalRequest request) {
        long deadline = System.nanoTime() + responseWaitNanos;
        ApproveCommand command =
                new ApproveCommand(
                        orderRef,
                        request.buyerId().toString(),
                        request.paymentKey(),
                        request.pgOrderId(),
                        request.amount());
        ApprovalResult result =
                switch (approvals.start(command)) {
                    case Answered answered -> answered.result();
                    case Attempt attempt -> await(attempt, deadline);
                };
        return respond(orderRef, result);
    }

    private ApprovalResult await(Attempt attempt, long deadline) {
        ApprovalResult pending = new ApprovalResult.Pending(attempt.paymentId());
        Future<ApprovalResult> future;
        try {
            future = workers.submit(() -> process(attempt));
        } catch (RejectedExecutionException e) {
            // 종료 중. 이미 APPROVING을 커밋했으므로 정체 정리(#349)가 확정한다
            log.error("[승인 처리 거부: 종료 중] paymentId={}", attempt.paymentId());
            return pending;
        }
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // cancel하지 않는다. 인터럽트가 토스 호출이나 결과 반영을 끊으면 안 된다
            return pending;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return pending;
        } catch (ExecutionException e) {
            // process가 이미 ERROR로 남겼다. 결제는 처리 중이므로 외부에는 PENDING이다
            return pending;
        }
    }

    private ApprovalResult process(Attempt attempt) {
        try {
            return approvals.process(attempt);
        } catch (RuntimeException | Error e) {
            // 대기가 끝난 뒤에는 아무도 future를 읽지 않으므로 여기서 남긴다
            log.error("[승인 반영 실패] paymentId={}. 정체 정리(#349)가 확정한다", attempt.paymentId(), e);
            throw e;
        }
    }

    private static ResponseEntity<ApiResponse<Object>> respond(
            String orderRef, ApprovalResult result) {
        return switch (result) {
            case ApprovalResult.Approved r ->
                    ResponseEntity.ok(
                            ApiResponse.ok(new ApprovalResponse(r.paymentId(), "APPROVED")));
            case ApprovalResult.Pending r ->
                    ResponseEntity.accepted()
                            .location(URI.create("/v1/internal/payments/" + orderRef))
                            .body(ApiResponse.ok(new ApprovalResponse(r.paymentId(), "PENDING")));
            case ApprovalResult.Declined r ->
                    ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                            .body(
                                    new ApiResponse<>(
                                            false,
                                            new DeclinedResponse("DECLINED", r.code(), r.message()),
                                            new ApiResponse.ApiError(
                                                    PaymentError.DECLINED.name(),
                                                    PaymentError.DECLINED.message(),
                                                    CorrelationId.current())));
            case ApprovalResult.PgUnavailable r ->
                    throw new PaymentException(PaymentError.PG_UNAVAILABLE);
            case ApprovalResult.Closed r -> throw new PaymentException(PaymentError.PAYMENT_CLOSED);
        };
    }
}
