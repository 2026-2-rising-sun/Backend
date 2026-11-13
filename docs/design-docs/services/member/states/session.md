# Member 갱신 토큰·접근 세션

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](session.html)

## 범위와 불변조건

고정 enum이 아니라 토큰 used_at·family 시각·회원 상태로 설명한 수명 주기다. 없는 토큰으로 임의 family를 폐기하지 않는다. 일반 로그아웃·refresh 만료만으로 기존 접근 JWT 유효 기간을 줄이지 않는 실제 구현에 유의한다.

## 전이

| 출발 | 도착 | 조건·부수 효과 |
|---|---|---|
| 로그인 성공 | 활성 묶음·미사용 토큰 | refresh_families와 해시 토큰을 생성하고 JWT에 세션 ID를 넣는다. |
| 미사용 토큰 | 사용된 토큰 | 회원·family 잠금 후 used_at을 기록하고 같은 family에 다음 토큰을 발급한다. |
| 사용된 토큰 재사용 | 보안 폐기 | 실제 저장 토큰 재사용이면 family의 revoked_at·security_revoked_at을 기록한 뒤 401을 응답한다. |
| 활성 묶음 | 일반 폐기 | 로그아웃은 revoked_at만 기록한다. 이미 발급된 접근 JWT를 즉시 만료시키지 않는다. |
| 활성 묶음 | 갱신 만료 | expires_at 경과로 refresh를 거절한다. 접근 JWT의 만료는 별도로 검사한다. |
| 회원 활성 | 탈퇴·전체 보안 폐기 | 비밀번호 확인 후 withdrawn_at과 모든 family의 보안 폐기를 같은 트랜잭션에 기록한다. |
| 접근 세션 | 접근 거절 | security_revoked_at 또는 withdrawn_at이 있거나 세션 소유권이 맞지 않으면 활성 확인에 실패한다. |

## 구현과 검증 근거

- [MemberSessionService.java](../../../../../services/member-service/src/main/java/com/shoppinglive/member/auth/service/MemberSessionService.java)
- [RefreshSessionRepository.java](../../../../../services/member-service/src/main/java/com/shoppinglive/member/auth/repository/RefreshSessionRepository.java)
- [DatabaseAccessSessionVerifier.java](../../../../../services/member-service/src/main/java/com/shoppinglive/member/auth/service/DatabaseAccessSessionVerifier.java)

관련 기존 테스트: [MemberSessionTest.java](../../../../../services/member-service/src/integrationTest/java/com/shoppinglive/member/auth/controller/MemberSessionTest.java) · [MemberSessionPostgresTest.java](../../../../../services/member-service/src/integrationTest/java/com/shoppinglive/member/auth/controller/MemberSessionPostgresTest.java).

테스트 링크는 검증 경로다. 이번 문서 작업에서는 업무 테스트를 재실행하지 않았으며, 실제 실행 여부는 워크로그에서 확인한다.
