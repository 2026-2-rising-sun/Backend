# Member

책임: 회원·로그인·접근 토큰·갱신 토큰 묶음·세션 확인.

접근 JWT의 만료와 refresh family의 만료·일반 폐기·보안 폐기는 서로 다른 조건이다.

기준: `e5a1b66bf3b06a971b1242dd02bd623e717c2902`의 계약·마이그레이션·주요 상태 변경 코드. 현재 #330 문서 변경은 미커밋이며 업무 코드는 변경하지 않았다. 운영 환경 검증 자료와 구분한다.

## 문서

- [API HTML](api.html) · [계약 YAML](../../../../contracts/api/member-service.yaml)
- [DB ERD 설명](erd.md) · [관계도](erd.html)
- [갱신 토큰·접근 세션](states/session.md) · [시각 자료](states/session.html)

## 원본·갱신

[구현](../../../../services/member-service/src/main/java) · [마이그레이션](../../../../services/member-service/src/main/resources/db) · [서비스 경계](../../../../ARCHITECTURE.md)

API 계약 변경 후 HTML을 재생성한다. DB·상태 변경은 MD의 근거·조건·실패 경로를 확인하고 HTML도 재생성한다. MD에 없는 전이를 시각 자료에만 추가하지 않는다.
