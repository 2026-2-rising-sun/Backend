package com.shoppinglive.live.broadcast.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class LiveExceptionHandler {

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(
        HandlerMethodValidationException e) {
        final String message = e.getAllErrors().stream()
            .findFirst()
            .map(error -> error.getDefaultMessage())
            .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
        ConstraintViolationException e) {
        final String message = e.getConstraintViolations().stream()
            .findFirst()
            .map(violation -> violation.getMessage())
            .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(
        MissingRequestHeaderException e) {
        final String message = e.getHeaderName() + " 헤더는 필수입니다.";
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
        MissingServletRequestParameterException e) {
        final String message = e.getParameterName() + " 파라미터는 필수입니다.";
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(final ErrorCode code,
                                                         final String message) {
        return ResponseEntity.status(code.status())
            .body(ApiResponse.fail(code, message, CorrelationId.current()));
    }
}
