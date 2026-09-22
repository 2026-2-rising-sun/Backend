package com.shoppinglive.shopping.image.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

/**
 * 저장소 입출력 실패 (디스크 가득 참, 쓰기 권한 없음 등). 사용자 파일이 아니라 서버 문제라 500 으로 알려
 * "파일을 고쳐야 하는 경우(400)"와 "잠시 후 재시도할 경우(500)"를 구분하게 한다. 경로 같은 내부 정보는
 * 응답이 아니라 {@link #detail()} 과 cause 로만 로그에 남긴다.
 */
public class ImageStorageException extends BusinessException {

    public static final String USER_MESSAGE = "이미지 저장에 실패했습니다. 잠시 후 다시 시도해 주세요.";

    private final String detail;

    public ImageStorageException(String detail, Throwable cause) {
        super(ErrorCode.INTERNAL_ERROR, USER_MESSAGE, cause);
        this.detail = detail;
    }

    public String detail() {
        return detail;
    }
}
