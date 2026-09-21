package com.shoppinglive.commerce.sales.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 조건부 UPDATE 로 상태를 바꾸려 했는데 이미 다른 트랜잭션이 상태를 바꿔서 실패한 경우.
 *
 * <p>Spring 이 409 Conflict 로 매핑한다. 클라이언트는 최신 상태를 다시 조회한 뒤 재시도.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConcurrentStateChangeException extends RuntimeException {

    public ConcurrentStateChangeException(Long salesId) {
        super("sales status changed concurrently: id=" + salesId + ". reload and retry.");
    }
}
