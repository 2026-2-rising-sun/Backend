package com.shoppinglive.live.integration.ivs;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;

public final class IvsUnavailableException extends BusinessException {
    public IvsUnavailableException() {
        super(ErrorCode.SERVICE_UNAVAILABLE, "영상 준비 상태를 확인할 수 없습니다.");
    }
}
