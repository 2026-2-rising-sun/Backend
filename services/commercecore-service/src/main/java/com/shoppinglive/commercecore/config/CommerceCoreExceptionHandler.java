package com.shoppinglive.commercecore.config;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * commercecore-service 전용 예외 매핑.
 *
 * <p>{@code common-module} 의 {@code GlobalExceptionHandler} 에는
 * {@code @ExceptionHandler(Exception.class)} 가 있고, Spring 은 {@code @ControllerAdvice} 처리를
 * {@code @ResponseStatus} 해석보다 먼저 한다. 그래서 여기서 따로 잡지 않으면 아래 예외들이 전부 500 으로 나간다.
 * {@code @Order(HIGHEST_PRECEDENCE)} 로 공통 핸들러보다 먼저 선택되게 한다. catalog-service 의 {@code
 * SalesInfoUnavailableExceptionHandler} 도 같은 방식이다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommerceCoreExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
            OptimisticLockingFailureException e) {
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(
                        ApiResponse.fail(
                                ErrorCode.CONFLICT,
                                "다른 수정이 먼저 반영되었습니다. 다시 조회해 주세요.",
                                CorrelationId.current()));
    }

    /**
     * 쿼리 파라미터·경로 변수의 타입이 맞지 않는 요청은 400 이다.
     *
     * <p>예: {@code ?productId=abc}. 잘못 보낸 쪽은 클라이언트인데 500 을 돌려주면 서버 장애로 오인되고, 모니터링에서도 실제 장애와 섞인다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        String message = "%s 값의 형식이 올바르지 않습니다.".formatted(e.getName());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
                .body(
                        ApiResponse.fail(
                                ErrorCode.INVALID_REQUEST, message, CorrelationId.current()));
    }
}
