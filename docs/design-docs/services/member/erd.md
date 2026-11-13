# Member DB ERD

<!-- visual: relations -->

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 코드·계약 조사. #330의 미커밋 문서 변경을 포함하며 업무 코드 변경은 없다. 실제 DB·HTTP·Kafka·PG 실행 검증은 별도다.

[시각 자료](erd.html)

전체 CREATE/ALTER 순서를 대조한 테이블·핵심 키·관계 요약이다. 전체 열 타입·인덱스·CHECK와 데이터 이관 SQL은 아래 원본을 따른다. 운영 DB에 적용된 실제 스키마를 조회한 결과는 아니다.

## 테이블

| 테이블 | 핵심 키·열 | DDL 근거 |
|---|---|---|
| members | PK UUID id; UQ email; password_hash; role USER/SELLER; failed_login_attempts; login_locked_until; withdrawn_at | [V1](../../../../services/member-service/src/main/resources/db/migration/V1__members.sql) · [V2](../../../../services/member-service/src/main/resources/db/migration/V2__login_and_refresh_sessions.sql) · [V3](../../../../services/member-service/src/main/resources/db/migration/V3__session_rotation_and_withdrawal.sql) · [V4](../../../../services/member-service/src/main/resources/db/migration/V4__rename_admin_role_to_seller.sql) |
| refresh_families | PK id; member_id; expires_at; revoked_at; security_revoked_at | [V2](../../../../services/member-service/src/main/resources/db/migration/V2__login_and_refresh_sessions.sql) · [V3](../../../../services/member-service/src/main/resources/db/migration/V3__session_rotation_and_withdrawal.sql) |
| refresh_tokens | PK token_hash; family_id; used_at | [V2](../../../../services/member-service/src/main/resources/db/migration/V2__login_and_refresh_sessions.sql) |

## 관계

| 출발 | 도착 | 구분·조건 |
|---|---|---|
| refresh_families.member_id | members.id | 물리 FK · 회원의 갱신 토큰 묶음 |
| refresh_tokens.family_id | refresh_families.id | 물리 FK · 교체 전 토큰 사용 이력도 보존 |

## 제약과 이관

V3의 withdrawn_at·security_revoked_at과 V4의 ADMIN→SELLER 전환을 포함한다. 일반 revoked_at과 보안 폐기는 접근 세션 확인에 다르게 사용된다.

## 원본과 매핑

- [V1__members.sql](../../../../services/member-service/src/main/resources/db/migration/V1__members.sql)
- [V2__login_and_refresh_sessions.sql](../../../../services/member-service/src/main/resources/db/migration/V2__login_and_refresh_sessions.sql)
- [V3__session_rotation_and_withdrawal.sql](../../../../services/member-service/src/main/resources/db/migration/V3__session_rotation_and_withdrawal.sql)
- [V4__rename_admin_role_to_seller.sql](../../../../services/member-service/src/main/resources/db/migration/V4__rename_admin_role_to_seller.sql)

JPA 매핑: [Member.java](../../../../services/member-service/src/main/java/com/shoppinglive/member/members/entity/Member.java).

JPA로 매핑하지 않는 테이블은 JDBC 저장 코드와 DDL을 함께 본다. 상태·제약을 변경할 때 이 문서와 HTML을 재생성하고 실제 DB 검증 여부를 별도로 기록한다.
