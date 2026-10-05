package com.shoppinglive.live.integration.member;

/** 작성자 표시 이름을 Member 의 본인 조회 API 로 읽는다. 캐시·재시도는 없다. */
public interface MemberProfileClient {
    /**
     * @param authorization 작성 요청의 Authorization 헤더. 저장하거나 로그에 남기지 않는다.
     * @param memberId 인증 principal 의 회원 ID. 응답의 회원 ID 와 같아야 한다.
     * @throws MemberProfileException Member 가 토큰을 거절했거나 응답을 신뢰할 수 없을 때
     */
    String displayName(String authorization, String memberId);
}
