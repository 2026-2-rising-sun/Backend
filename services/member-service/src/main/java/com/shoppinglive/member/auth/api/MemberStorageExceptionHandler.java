package com.shoppinglive.member.auth.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.shoppinglive.member")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MemberStorageExceptionHandler {
    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
        CannotCreateTransactionException.class})
    ResponseEntity<ApiResponse<Void>> storageUnavailable(RuntimeException exception) {
        return ResponseEntity.status(503).body(ApiResponse.fail(ErrorCode.SERVICE_UNAVAILABLE,
            "회원 저장소를 사용할 수 없습니다.", CorrelationId.current()));
    }
}
