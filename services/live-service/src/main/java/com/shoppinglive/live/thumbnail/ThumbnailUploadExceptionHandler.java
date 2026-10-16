package com.shoppinglive.live.thumbnail;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.web.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ThumbnailUploadExceptionHandler {
    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> invalidUpload(Exception exception) {
        return ResponseEntity.badRequest()
                .body(
                        ApiResponse.fail(
                                ErrorCode.INVALID_REQUEST,
                                "2MB 이하 PNG·JPEG 파일을 file 필드로 업로드해 주세요.",
                                CorrelationId.current()));
    }
}
