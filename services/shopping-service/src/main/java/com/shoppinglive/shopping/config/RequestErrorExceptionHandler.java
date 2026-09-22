package com.shoppinglive.shopping.config;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.web.CorrelationId;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 요청 형식 오류를 500 대신 알맞은 4xx 로 돌려준다.
 *
 * <p>공통 {@code GlobalExceptionHandler} 는 모르는 예외를 모두 500 으로 만든다. 잘못된 Content-Type(415),
 * 허용되지 않는 메서드(405), 없는 경로(404), 누락된 파라미터·헤더(400) 같은 Spring MVC 예외는 대부분
 * 컨트롤러가 정해지기 전에 나므로 패키지 범위 advice 로는 잡히지 않아 서비스 전역으로 둔다.
 * 기능별 advice(업로드·상품·판매정보)가 먼저 처리하도록 우선순위는 그보다 낮게, 공통 핸들러보다는 높게 둔다.
 * (공용 라이브러리 수정은 shopping 작업 범위 밖이라 이 서비스 안에서 보정한다.)
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class RequestErrorExceptionHandler {

    /** Spring 6 의 MVC 예외(ErrorResponse 구현)는 알맞은 상태 코드를 스스로 가지고 있다. */
    @ExceptionHandler({
        HttpMediaTypeException.class,                 // 415 Content-Type, 406 Accept
        HttpRequestMethodNotSupportedException.class, // 405
        NoResourceFoundException.class,               // 404 없는 경로
        NoHandlerFoundException.class,                // 404
        ServletRequestBindingException.class,         // 400 누락된 파라미터·헤더
        ErrorResponseException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleErrorResponse(Exception e) {
        ErrorResponse error = (ErrorResponse) e;
        HttpStatusCode status = error.getStatusCode();
        String detail = error.getBody().getDetail();
        return toResponse(status, detail != null ? detail : e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return toResponse(HttpStatus.BAD_REQUEST, "요청 본문을 해석할 수 없습니다.");
    }

    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(TypeMismatchException e) {
        String name = e.getPropertyName() != null ? e.getPropertyName() : "value";
        return toResponse(HttpStatus.BAD_REQUEST, "요청 값 형식이 잘못되었습니다: " + name);
    }

    private static ResponseEntity<ApiResponse<Void>> toResponse(HttpStatusCode status, String message) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved != null ? resolved.name() : "HTTP_" + status.value();
        ApiResponse<Void> body = new ApiResponse<>(false, null,
                new ApiResponse.ApiError(code, message, CorrelationId.current()));
        return ResponseEntity.status(status).body(body);
    }
}
