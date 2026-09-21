package com.shoppinglive.shopping.product.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import com.shoppinglive.shopping.product.application.ProductUpdateService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 상품 API 의 요청 해석 실패를 400, 동시 수정 충돌을 409 로 바꾼다. 공통 GlobalExceptionHandler 는 이런 경우도 500 으로
 * 돌리므로, 그보다 먼저 적용되도록 순서를 올리고 범위는 이 패키지 컨트롤러로 좁힌다.
 */
@RestControllerAdvice(basePackageClasses = ProductApiExceptionHandler.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return badRequest("요청 본문을 해석할 수 없습니다 (JSON 형식과 필드 타입을 확인해 주세요).");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest(e.getName() + " 값의 형식이 올바르지 않습니다.");
    }

    /** 서비스가 flush 에서 잡지 못한 동시 수정(커밋 시점 충돌)도 덮어쓰기 대신 409 로 알린다. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(ErrorCode.CONFLICT.status()).body(
                ApiResponse.fail(ErrorCode.CONFLICT, ProductUpdateService.CONFLICT_MESSAGE, CorrelationId.current()));
    }

    private static ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message, CorrelationId.current()));
    }
}
