package com.shoppinglive.payment.config;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 공통 GlobalExceptionHandler의 {@code Exception} 처리보다 먼저 업무 코드를 응답한다. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentExceptionHandler {
    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ApiResponse<Void>> payment(PaymentException e) {
        return ResponseEntity.status(e.error().httpStatus())
                .body(
                        new ApiResponse<>(
                                false,
                                null,
                                new ApiResponse.ApiError(
                                        e.error().name(),
                                        e.getMessage(),
                                        CorrelationId.current())));
    }
}
