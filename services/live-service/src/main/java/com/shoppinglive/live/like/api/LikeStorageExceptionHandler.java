package com.shoppinglive.live.like.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Transaction begin/commit errors happen outside the service method's catch block. */
@RestControllerAdvice(assignableTypes = LikeController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LikeStorageExceptionHandler {
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiResponse<Void>> unavailable(RuntimeException exception) {
        return ResponseEntity.status(ErrorCode.SERVICE_UNAVAILABLE.status()).body(ApiResponse.fail(
            ErrorCode.SERVICE_UNAVAILABLE, "좋아요 저장소를 사용할 수 없습니다.", CorrelationId.current()));
    }
}
