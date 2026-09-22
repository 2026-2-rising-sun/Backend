package com.shoppinglive.shopping.sales.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.web.CorrelationId;
import com.shoppinglive.shopping.sales.application.SalesInfoUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 판매정보를 확인하지 못한 요청을 503 으로 돌려준다.
 *
 * <p>공개 목록·상세는 최신 판매정보 없이 구매 가능한 것처럼 보여주면 안 되므로 결과를 만들지 않고
 * 재시도를 안내한다. 공통 {@code ErrorCode} 에 503 이 없어 코드 문자열을 이 서비스에서 정한다
 * (공용 라이브러리는 shopping 작업 범위 밖). 관리 화면처럼 기본정보만이라도 보여줘야 하는 곳은
 * 이 예외를 직접 잡아 {@code SalesDisplayStatus.unknown()} 으로 표시한다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SalesInfoUnavailableExceptionHandler {

    public static final String CODE = "SALES_INFO_UNAVAILABLE";
    private static final String MESSAGE = "판매정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.";
    private static final Logger log = LoggerFactory.getLogger(SalesInfoUnavailableExceptionHandler.class);

    @ExceptionHandler(SalesInfoUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handle(SalesInfoUnavailableException e) {
        log.warn("sales info unavailable: {}", e.getMessage());
        ApiResponse<Void> body = new ApiResponse<>(false, null,
            new ApiResponse.ApiError(CODE, MESSAGE, CorrelationId.current()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
