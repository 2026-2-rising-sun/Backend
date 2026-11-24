package com.shoppinglive.common.kafka;

/** 메시지가 계약(봉투·필드·키)과 맞지 않는다. 다시 읽어도 같으므로 재시도 없이 DLT로 보낸다. */
public class ContractViolationException extends RuntimeException {
    public ContractViolationException(String message) {
        super(message);
    }

    public ContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
