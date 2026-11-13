# 인증·세션 확인 서비스 간 흐름

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](authentication.html)

## 책임과 경계

일반 로그아웃, refresh family 만료, JWT 만료, 보안 폐기는 서로 다른 조건이다. 서비스 간 내부 인증은 사용자 JWT 공개 API와 구분해 해당 OpenAPI security를 확인한다.

## 전이

| 출발 | 도착 | 조건·처리 |
|---|---|---|
| 사용자 로그인 | Member 발급 | 회원 인증 후 접근 JWT와 갱신 토큰 묶음을 발급한다. |
| JWT 요청 | 서비스 인증 | 토큰 서명·만료·권한 조건과 Member 접근 세션 활성 확인을 적용한다. |
| 갱신 요청 | Member 토큰 교체 | family·회원 잠금, 사용 여부·만료·폐기 확인 후 이전 토큰 used_at과 새 토큰을 저장한다. |
| 실제 사용 토큰 재사용 | 보안 폐기 | 폐기를 먼저 커밋한 뒤 인증 오류를 응답한다. |
| 일반 로그아웃 | 갱신 폐기 | refresh는 막지만 이미 발급된 접근 토큰의 유효 기간은 유지한다. |
| 탈퇴·전체 폐기 | 접근 세션 차단 | withdrawn_at 또는 security_revoked_at으로 활성 확인을 거절한다. |

## 상세와 근거

- [Member 갱신 토큰·접근 세션](services/member/states/session.md)

[공통 Outbox](services/common-module/outbox.md) · [이벤트 계약](../../contracts/events)
