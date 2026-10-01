# 실제 HTTP 통합 검증

`npm ci --prefix scripts/contracts --ignore-scripts` 후 `node scripts/integration/run.cjs`를 실행한다.
로컬은 기본으로 현재 checkout의 네 bootJar를 다시 빌드한다. JDK 21을 찾으면 host JVM, 없으면 Docker Temurin 21을 사용한다.
`SHOPPINGLIVE_INTEGRATION_RUNTIME=host|docker`로 선택할 수도 있다. Docker는 PostgreSQL 때문에 두 모드 모두 필요하다.
CI 또는 명시적 `--use-prebuilt`에서만 기존 jar를 사용하며 archive의 `build/integration-jars.sha`와 checkout SHA가 같아야 한다.

매 실행마다 고유 이름과 소유 label의 PostgreSQL/네트워크를 만들고 기존 DB·Compose·kind에는 접속하지 않는다.
DB는 tmpfs이며 서비스별 새 DB를 만든다. 키·비밀번호·토큰은 OS 임시 디렉터리 0700/파일 0600으로 만들고 finally에서 삭제한다.
Docker/JVM 종료도 이 실행에서 만든 대상만 수행한다. 기존 Gradle 캐시는 보존한다.
DB 장애는 소유 PostgreSQL을 pause/unpause하여 재현한다. 테스트 JDBC timeout은 2초, Hikari 연결 대기는 2초이다.

실제 Member 가입·로그인, 명시적 ADMIN CLI, Shopping/Commerce/Live HTTP와 PostgreSQL을 사용한다.
결제는 기존 MockPaymentEngine, IVS는 명시적으로 켠 local stub이다. 실제 결제사·IVS 송출 검증이 아니다.
현재 네 서비스에는 Kafka 송수신 코드가 없어 Kafka 기동과 검증을 제외하며 admin/listener 자동 시작을 끈다.
개발 결제 시나리오 제어는 이 로컬 fixture에서만 활성화한다. 일반 결제 body의 결과 선택은 별도로 거부 검증한다.

결과와 정리된 로그는 `build/integration/`에 저장한다. 원문 비밀번호·JWT·refresh·개인키는 기록하지 않는다.
`summary.json`에는 코드 SHA, jar/계약 hash, HTTP 응답 schema 대조, 불변식, 실패·미대응 응답·제외된 운영 경로를 남긴다.
정상 응답과 오류 응답 모두 현재 Git YAML을 사용하며, 검토 acknowledgment가 없으면 승인 완료라고 주장하지 않는다.
네 서비스 관리 포트의 무인증 probe, API 포트의 health 미노출, DB 장애 readiness와 독립 liveness를 검사한다.

세션 검사는 실제 refresh 회전·재사용·동시 소비, 일반 로그아웃과 타 기기 유지, 관리자 강제 차단,
비밀번호 확인 후 논리 탈퇴를 포함한다. 일반 로그아웃 뒤 기존 access는 남은 15분 만료까지 허용하며,
보안상 폐기한 family와 탈퇴 계정은 각 서비스에서 이후 인증 확인부터 거절한다.
세 서비스는 로컬 JWT 서명 검증 후 Member의 상태 API를 캐시 없이 조회하고, Member 자체는 DB를 조회한다.
Member 중단 시 인증된 요청의 503·쓰기 차단, 무토큰 공개 API와 내부 caller의 독립 동작,
재시작 후 정상 세션 복구 및 DB에 저장된 보안 폐기 유지도 검증한다.
소셜 로그인·이메일 소유권 인증은 사용자 요청으로 P2에서 제외했다. Apidog 프로젝트 왕복 검증 대기는 `deferred`에 명시되어 있다.
현재 구현된 필수 HTTP 흐름의 성공은 `passed`로, 정책 대기와 P2 전체 완료 여부는 `deferred`/`p2Complete`로 구분한다.
0건·실패·skip·누락을 성공으로 바꾸지 않으며 부분 실행을 P2 또는 CI 완료로 보고하지 않는다.
PostgreSQL migration·locking의 전체 회귀는 CI의 별도 필수 PostgreSQL job이 수행한다. 그 개수를 HTTP check 개수에 합산하지 않는다.

Prism 예제는 명시적인 의존 서비스 실패 사례에서만 사용한다. 복사한 임시 명세에 transport 503을 추가하며 공개 계약을 바꾸지 않는다.
별도 proxy가 응답 헤더를 지연시켜 실제 read timeout을 확인하고, 종료 후 실제 upstream URL을 복원한다.
추적·미추적 소스 모두 커밋한 뒤 실행해야 한다. Git에서 무시한 빌드 결과만 허용한다.
