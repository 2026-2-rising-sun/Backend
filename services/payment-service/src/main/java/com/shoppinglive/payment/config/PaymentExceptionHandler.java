package com.shoppinglive.payment.config;

import com.shoppinglive.common.core.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class PaymentExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException e) {
        return ResponseEntity.status(e.errorCode().status())
                .body(
                        ApiResponse.fail(
                                e.errorCode(),
                                e.getMessage(),
                                com.shoppinglive.common.web.CorrelationId.current()));
    }
}
