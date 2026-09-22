package com.shoppinglive.shopping.image.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import com.shoppinglive.shopping.image.application.ImageStorageException;
import com.shoppinglive.shopping.image.application.ImageStorageProperties;
import com.shoppinglive.shopping.image.application.ImageValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 업로드 예외를 공통 응답 형식으로 바꾼다. 공통 GlobalExceptionHandler 는 모르는 예외를 모두 500 으로 돌려
 * 용량 초과·파일 누락 같은 사용자 실수도 서버 오류로 보이므로 여기서 먼저 잡는다. multipart 파싱은
 * 컨트롤러가 정해지기 전에 실패하므로 특정 컨트롤러로 범위를 좁히지 않는다.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImageUploadExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadExceptionHandler.class);

    private final ImageStorageProperties properties;

    public ImageUploadExceptionHandler(ImageStorageProperties properties) {
        this.properties = properties;
    }

    /** multipart 한도는 검증기 한도보다 조금 크게 잡혀 있어 여기 걸린 요청은 어차피 용량 초과다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("multipart size limit exceeded: {}", e.getMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, ImageValidator.oversizeMessage(properties));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException e) {
        return toResponse(ErrorCode.INVALID_REQUEST,
                "업로드할 파일이 없습니다 (multipart 파트 이름: " + e.getRequestPartName() + ")");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipart(MultipartException e) {
        log.warn("multipart request rejected: {}", e.getMessage());
        return toResponse(ErrorCode.INVALID_REQUEST, "파일 업로드 요청을 해석할 수 없습니다 (multipart/form-data 로 보내 주세요)");
    }

    /** 서버 문제라 원인을 error 로 남기고, 응답에는 경로 같은 내부 정보 없이 재시도 안내만 준다. */
    @ExceptionHandler(ImageStorageException.class)
    public ResponseEntity<ApiResponse<Void>> handleStorage(ImageStorageException e) {
        log.error("image storage failure: {}", e.detail(), e);
        return toResponse(e.errorCode(), e.getMessage());
    }

    private static ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, message, CorrelationId.current()));
    }
}
