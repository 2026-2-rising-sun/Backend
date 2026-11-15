package com.shoppinglive.payment.client.toss;

/** 토스 호출 결과. 분류 규칙은 실행 계획 #338 설계서의 "결과 분류" 표를 따른다. */
public sealed interface TossResult {
    /** 토스가 처리했고 200 본문이 호출별 기대 상태다. */
    record Success(TossPayment payment) implements TossResult {}

    /** 토스가 처리하고 오류 코드로 거절했다. */
    record Declined(String code, String message) implements TossResult {}

    /** 토스가 요청을 처리하지 않았다. 다시 보내도 돈이 두 번 움직이지 않는다. */
    record NotSent(String reason) implements TossResult {}

    /** 처리 여부를 모른다. 조회로 확정한다. */
    record Unknown(String reason) implements TossResult {}
}
