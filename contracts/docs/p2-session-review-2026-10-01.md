# 세션 계약 독립 검토

A4가 작성한 네 YAML을 작성자와 별도인 root 검토 단계에서 확인했다. 기준 dev는 `1891c966`, 실제 통합 검증은 `7377ed335ff9e480b0ae9c93f8d6e846b63bdcea`이다.

- Member SessionController·요청 DTO·MemberSessionService·SecurityFilterChain과 5개 추가 operation의 method/path/body/상태/권한을 대조했다. 기존 4개와 합쳐 9개이다.
- refresh/logout은 JSON credential을 사용한다. 204는 빈 본문이며 탈퇴 비밀번호 UTF-8 길이 제한, unknown field 거부, ADMIN 강제 폐기, 서비스 caller만 상태 조회하도록 구분했다.
- 일반 로그아웃은 기존 access를 남겨 두며 refresh 재사용·관리자 강제 폐기·탈퇴는 이후 인증 검사를 거부한다. 절대 30일은 회전으로 연장되지 않는다. 이미 진행 중인 요청 취소나 영구 계정 정지는 아니다.
- 세 소비 서비스의 Bearer 인증은 sid와 Member 상태 조회를 요구한다. 실패 시 SERVICE_UNAVAILABLE 503을 계약에 추가했다. 상품/판매 의존 장애와는 오류 의미를 구분한다.
- 공개 API의 무토큰 호출과 내부 서비스 caller 접근을 유지했다. 원본 계약의 request/response schema 및 named example을 검사했다.
- 구문·참조·예제 검증: 79 schemas, 412 references, 111 examples, 오류 0. 실제 네 서비스 HTTP 검증 365 checks, 이 중 계약 대조 244 responses, failed/skip/unmatched 0, 자원 정리 성공.
- 소셜 로그인 및 이메일 소유권 인증은 사용자가 P2에서 제외했다. 이메일 형식 검사 및 BCrypt 해시 저장은 실제 Member 구현과 별도 테스트의 검증 대상이다.

이 기록은 정확한 두 계약 스냅샷에 대한 구조 변경 검토이다. 자동 호환성 증명이나 GitHub 사람 승인으로 해석하지 않는다. Apidog export 변환은 별도 왕복 검토 대상이며 Git 원본을 덮어쓰지 않는다.
