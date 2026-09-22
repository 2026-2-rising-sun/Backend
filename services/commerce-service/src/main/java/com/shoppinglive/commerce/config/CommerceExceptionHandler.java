package com.shoppinglive.commerce.config;

import com.shoppinglive.commerce.shopping.application.ShoppingUnavailableException;
import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * commerce-service 전용 예외 매핑.
 *
 * <p>{@code common-web} 의 {@code GlobalExceptionHandler} 에는
 * {@code @ExceptionHandler(Exception.class)} 가 있고, Spring 은 {@code @ControllerAdvice} 처리를
 * {@code @ResponseStatus} 해석보다 먼저 한다. 그래서 여기서 따로 잡지 않으면 아래 예외들이 전부
 * 500 으로 나간다. {@code @Order(HIGHEST_PRECEDENCE)} 로 공통 핸들러보다 먼저 선택되게 한다.
 * shopping-service 의 {@code SalesInfoUnavailableExceptionHandler} 도 같은 방식이다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommerceExceptionHandler {

    /** 공통 {@link ErrorCode} 에 503 이 없어 이 서비스에서 코드 문자열을 정한다. */
    public static final String SHOPPING_UNAVAILABLE = "SHOPPING_UNAVAILABLE";

    private static final String SHOPPING_UNAVAILABLE_MESSAGE =
        "상품 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";

    private static final Logger log = LoggerFactory.getLogger(CommerceExceptionHandler.class);

    /**
     * Shopping 을 확인하지 못한 요청은 503 으로 돌려준다.
     *
     * <p>500 이 아닌 이유: 이쪽 코드가 잘못된 게 아니라 의존 서비스가 잠깐 답을 못 준 상황이고,
     * 재시도하면 성공할 수 있다. 호출하는 화면도 "오류" 가 아니라 "잠시 후 다시" 로 안내해야
     * 한다. "상품이 없음" 은 이 예외가 아니라 404 로 따로 구분한다.
     */
    @ExceptionHandler(ShoppingUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleShoppingUnavailable(
        ShoppingUnavailableException e) {
        log.warn("shopping unavailable: {}", e.getMessage());
        ApiResponse<Void> body = new ApiResponse<>(false, null, new ApiResponse.ApiError(
            SHOPPING_UNAVAILABLE, SHOPPING_UNAVAILABLE_MESSAGE, CorrelationId.current()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * 쿼리 파라미터·경로 변수의 타입이 맞지 않는 요청은 400 이다.
     *
     * <p>예: {@code ?productId=abc}. 잘못 보낸 쪽은 클라이언트인데 500 을 돌려주면 서버 장애로
     * 오인되고, 모니터링에서도 실제 장애와 섞인다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
        MethodArgumentTypeMismatchException e) {
        String message = "%s 값의 형식이 올바르지 않습니다.".formatted(e.getName());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.status())
            .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message, CorrelationId.current()));
    }
}
