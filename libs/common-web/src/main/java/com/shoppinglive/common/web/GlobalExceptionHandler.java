package com.shoppinglive.common.web;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("business exception: {}", e.getMessage());
        return toResponse(e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    /** 낙관적 잠금 충돌은 서버 오류가 아니라 동시 변경 충돌이므로 409 로 내린다. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException e) {
        log.warn("optimistic locking failure: {}", e.getMessage());
        return toResponse(ErrorCode.CONFLICT, "다른 요청이 먼저 변경했습니다. 다시 조회하세요.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return toResponse(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode code, String message) {
        return ResponseEntity.status(code.status())
                .body(ApiResponse.fail(code, message, CorrelationId.current()));
    }
}
