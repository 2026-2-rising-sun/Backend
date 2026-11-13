# Backend 디렉터리 구조

상태: 구현 · 로컬 검증 완료

관련 작업: [하네스 재구성 #330](https://github.com/2026-2-rising-sun/Backend/issues/330)

문서 지도: [docs/README.md](README.md) · 시각 자료: [directory-structure.html](directory-structure.html)

아래는 #330에서 적용한 문서·도구와 주요 구현 디렉터리다. 선택·미연결 또는 미생성 항목은 별도 표시한다. 생성된 문서와 실제 외부 환경의 검증 완료는 구분한다. 이동·추가 시 실제 경로와 이 구조를 함께 갱신한다.

## 참고 구조와 문서의 역할

참고 이미지처럼 문서 종류별 폴더를 `docs/` 바로 아래에 둔다. 하네스는 AI 에이전트가 요청 범위를 지키고 팀의 공통 기준에 따라 판단·변경·검증·기록하도록 하는 지침과 실행 절차다. 필요한 프로젝트 지식과 작업 기록을 읽고 갱신하는 시점도 이 절차에서 연결한다.

| 역할 | 위치 | 관리 기준 |
|---|---|---|
| 행동 지침 | `AGENTS.md`, `CLAUDE.md`, `docs/guides/`, `docs/templates/` | 명령·금지사항·작업 순서·품질 기준·문서 포인터를 관리한다. |
| 프로젝트 지식 | `ARCHITECTURE.md`, `docs/product-specs/`, `docs/requirements/`, `docs/design-docs/` | 제품 목적·검증 가능한 수용 기준·구조·설계를 설명한다. 제안·채택·구현 상태를 구분한다. |
| 판단·실행 기록 | `docs/adr/`, `docs/design-docs/failures/`, `docs/exec-plans/`, `docs/worklog/` | 당시 조건·선택 이유·진행 상태·실행 결과를 남기고, 정정과 후속 결정을 연결한다. |
| 실행 도구 | `scripts/`, `.github/` | 실제 연결된 검사와 실행 경로를 명시한다. 실패·스킵·미실행을 구분한다. |

공유 기준과 기록의 용도 구분은 위 역할로 유지한다. 작업에서 채택한 내용을 공유 문서에 반영하고, 관련 결정과 실행 근거를 서로 연결한다. ADR에는 선택 이유를 남기고 현재 구조를 설명하는 문서에는 적용된 내용과 해당 ADR 링크를 둔다.

`requirements/`의 ERD는 Engineering Requirements Document를 뜻한다. 데이터베이스 관계도와 구분해 검증 가능한 수용 기준과 검증 방법을 작성한다.

## MD와 HTML의 역할

공유 문서와 기록 모두 MD와 HTML을 사용할 수 있다.

| 형식 | 주된 용도 | 작성 기준 |
|---|---|---|
| MD | AI 에이전트가 맥락·규칙·근거를 읽고 참조한다. 사람도 읽을 수 있는 설명 문서로 쓴다. | 핵심 설명·판단·제약·근거를 제목, 문장, 표, 링크로 명확하게 정리한다. |
| HTML | 사람이 구조·흐름·상태·차이를 시각적으로 이해하도록 돕는다. | 다이어그램, 상태 변화, 비교 화면, 차트와 필요한 상호작용으로 내용을 풀어낸다. |

같은 주제의 MD와 HTML은 가까이 배치하고 서로 연결한다. 핵심 설명은 MD에 남기고, 내용이 바뀌면 관련 HTML도 함께 확인한다. HTML은 시각화 효과가 있는 자료에 추가한다. API·이벤트 형식의 원본은 `contracts/`에서 관리한다. API HTML은 기존 OpenAPI YAML에서 생성하며 명세 전체를 MD에 다시 복사하지 않는다.

## 디렉터리 구조

```text
Backend/
├── README.md                         # 프로젝트 소개·실행 진입점
├── AGENTS.md                         # AI 에이전트의 명령·금지사항·문서 포인터
├── ARCHITECTURE.md                   # 레이어·서비스 경계·의존 방향·핵심 도메인
│
├── docs/
│   ├── README.md                    # 문서 지도·작성·갱신 기준
│   ├── directory-structure.md       # AI 에이전트와 사람이 읽는 구조·주석 원본
│   ├── directory-structure.html     # 사람이 폴더를 펼쳐 보는 시각 자료
│   │
│   ├── product-specs/               # PRD: 무엇을, 왜 만드는가
│   │   ├── document-harness.md      # 하네스 개편의 목적·사용자·범위
│   │   ├── shoppinglive.md          # 제품 목적·기능 범위·주요 정책
│   │   └── glossary.md              # 공통 업무 용어
│   │
│   ├── requirements/                # Engineering Requirements Document: 검증 가능한 수용 기준
│   │   ├── document-harness.md      # 문서 하네스의 요구사항
│   │   ├── order-payment.md         # 주문·결제의 수용 기준
│   │   └── catalog-sync.md          # 사본 동기화의 수용 기준
│   │
│   ├── adr/                         # 왜 이렇게 결정했는가: 대안·선택 이유·결정 상태
│   │   └── 0001-document-harness.md
│   │
│   ├── design-docs/                 # 어떻게 구현하는가: 구조·흐름·설계·비교 자료
│   │   ├── README.md                # 설계·서비스 간 흐름 지도
│   │   ├── document-harness.md      # 하네스 동작과 문서·검사 연결 설계
│   │   ├── authentication.md        # 인증·세션 확인 흐름
│   │   ├── authentication.html      # 인증·세션의 서비스 간 흐름 시각화
│   │   ├── order-payment.md         # 주문·결제의 책임·처리 순서·예외
│   │   ├── order-payment.html       # 서비스 간 흐름·상태 변화 시각화
│   │   ├── refund.md                # 환불 접수·실행·결과 반영
│   │   ├── refund.html              # 서비스 간 환불 처리와 실패·복구 흐름
│   │   ├── catalog-sync.md          # 상품·판매·쿠폰 사본 동기화
│   │   ├── catalog-sync.html         # 원본·조회 사본과 동기화 경로 시각화
│   │   ├── live-notification.md     # 방송 이벤트와 알림 연결
│   │   ├── live-notification.html   # 방송·알림의 서비스 간 협력 시각화
│   │   ├── services/                # 모듈별 현재 구조·API·DB ERD·상태·수명 주기
│   │   │   ├── README.md            # 모듈 문서 지도
│   │   │   ├── commercecore/
│   │   │   │   ├── README.md        # 책임·소유 데이터·의존·문서와 원본의 지도
│   │   │   │   ├── api.html         # 계약 YAML에서 생성한 API 열람 화면
│   │   │   │   ├── erd.md           # 마이그레이션·엔티티에 근거한 테이블·키·관계
│   │   │   │   ├── erd.html         # 테이블·관계를 탐색하는 DB 관계도
│   │   │   │   └── states/
│   │   │   │       ├── order.md     # 주문 상태·허용 전이·조건·구현·테스트 근거
│   │   │   │       ├── order.html   # 주문 상태 전이 시각화
│   │   │   │       ├── payment.md   # CommerceCore의 결제 접수·결과 반영
│   │   │   │       ├── payment.html
│   │   │   │       ├── refund.md    # CommerceCore의 환불 접수·결과 반영
│   │   │   │       ├── refund.html
│   │   │   │       ├── sales.md     # 판매 상태와 변경 조건
│   │   │   │       └── sales.html
│   │   │   ├── payment/
│   │   │   │   ├── README.md
│   │   │   │   ├── api.html
│   │   │   │   ├── erd.md           # db/payment-migration/와 엔티티 기준
│   │   │   │   ├── erd.html
│   │   │   │   └── states/
│   │   │   │       ├── payment.md   # 결제 실행·실패·미확정 결과의 복구
│   │   │   │       ├── payment.html
│   │   │   │       ├── refund.md    # 환불 실행·실패·복구
│   │   │   │       └── refund.html
│   │   │   ├── catalog/
│   │   │   │   ├── README.md
│   │   │   │   ├── api.html
│   │   │   │   ├── erd.md
│   │   │   │   ├── erd.html
│   │   │   │   └── states/
│   │   │   │       ├── sales-view.md # 판매 상태의 조회 사본·표시·동기화 조건
│   │   │   │       └── sales-view.html
│   │   │   ├── member/
│   │   │   │   ├── README.md
│   │   │   │   ├── api.html
│   │   │   │   ├── erd.md
│   │   │   │   ├── erd.html
│   │   │   │   └── states/
│   │   │   │       ├── session.md   # 세션 발급·만료·폐기·갱신의 수명 주기
│   │   │   │       └── session.html
│   │   │   ├── live/
│   │   │   │   ├── README.md
│   │   │   │   ├── api.html
│   │   │   │   ├── erd.md
│   │   │   │   ├── erd.html
│   │   │   │   └── states/
│   │   │   │       ├── broadcast.md # 방송 상태·변경 조건
│   │   │   │       ├── broadcast.html
│   │   │   │       ├── promotion.md # 프로모션 상태·변경 조건
│   │   │   │       └── promotion.html
│   │   │   ├── notification/
│   │   │   │   ├── README.md
│   │   │   │   ├── api.html
│   │   │   │   ├── erd.md
│   │   │   │   ├── erd.html
│   │   │   │   └── states/
│   │   │   │       ├── inbox.md     # 알림 읽음 처리·보존 수명 주기
│   │   │   │       ├── inbox.html
│   │   │   │       ├── subscription.md # 구독 설정과 변경 조건
│   │   │   │       └── subscription.html
│   │   │   └── common-module/       # 공통 구현·계약·제약: 독립 서비스 아님
│   │   │       ├── README.md
│   │   │       ├── outbox.md        # 공통 Outbox 처리·소유 서비스와의 관계
│   │   │       └── outbox.html      # 발행·재시도 흐름 시각화
│   │   └── failures/                # 무엇이 깨졌고 왜 실패했는가
│   │       └── 330-stale-links.md   # 이관 중 링크 단절의 원인·검증
│   │
│   ├── exec-plans/
│   │   ├── README.md                # 진행·완료 계획의 지도
│   │   ├── active/                  # 새 진행 계획이 생길 때 생성
│   │   ├── completed/               # 완료한 계획·결과·남은 과제
│   │   │   └── 330-document-harness/
│   │   │       ├── README.md        # 실행 계획·상태·검증·관련 자료의 진입점
│   │   │       ├── decision.md      # 이 작업의 판단 기준·공통 ADR 연결
│   │   │       ├── research.md      # 구현 전 조사·근거·후속 결과 연결
│   │   │       └── tables.md        # 모듈별 문서 범위·원본·진행 상태
│   │   └── tech-debt.md             # 미룬 문제·영향·다시 다룰 조건
│   │
│   ├── worklog/
│   │   └── 2026-11-13.md            # 날짜별 작업·판단·실제 검증 결과 (YYYY-MM-DD.md)
│   │
│   ├── guides/                      # Backend의 작업 방식·품질 기준
│   │   ├── workflow.md
│   │   ├── code.md
│   │   ├── tests.md
│   │   ├── contracts.md
│   │   ├── tooling.md
│   │   └── writing.md
│   ├── templates/                   # 필요한 작성 양식
│   │   ├── README.md
│   │   ├── issue-feature.md
│   │   ├── issue-bug.md
│   │   ├── issue-refactor.md
│   │   ├── pull-request.md
│   │   ├── commit-message.md
│   │   ├── adr.md
│   │   ├── exec-plan.md
│   │   └── worklog.md
│   └── assets/
│       ├── architecture/            # 기존 SVG·PNG 자료
│       ├── harness/                 # 하네스 HTML의 실제 화면 확인 자료
│       │   ├── 330-directory-structure.png # 역할·문서 지도 화면 캡처
│       │   └── 330-order-state-filter.png # 주문 상태 필터 동작 화면 캡처
│       └── api/                     # 선택·미생성: 외부 뷰어 자산이 필요할 때
│
├── scripts/                         # 검사 선택·실행·문서 생성 도구
│   ├── check.sh                     # 변경 영향에 따른 포맷·테스트·계약·문서 검사
│   ├── check-docs.sh                # 문서 링크·요구사항 상태·검증 참조 검사
│   ├── verify.py                    # 검사 선택·실행·결과 집계
│   ├── check_docs.py                # 문서 검사 구현
│   ├── render-api-docs.sh           # OpenAPI HTML 생성·최신성 확인
│   ├── render_api_docs.py           # 저장소 내부 API 렌더러 버전 1
│   ├── render_design_docs.py        # MD 표에서 관계·전이 HTML 생성
│   ├── doc_html.py                  # 외부 자산 없는 공통 HTML 화면
│   ├── test_render_docs.py          # 참조·표시·생성 최신성 회귀 검사
│   ├── test_verify.py               # 검증 실행기 회귀 테스트
│   ├── test_check_docs.py            # 문서 검사기 회귀 테스트
│   ├── guard.sh                     # 선택·미연결: 위험 명령 차단
│   ├── post-edit.sh                 # 선택·미연결: 파일 수정 후 포맷·검사
│   └── local/                       # 기존 로컬 실행 도구
│
├── CLAUDE.md                         # @AGENTS.md 참조·필요한 Claude 전용 안내
├── .mcp.json                         # 선택·미연결: 팀 공통 MCP 서버
├── .claude/                          # 선택·미연결: Claude 전용 설정
│   ├── settings.json                # 권한·훅 등록과 scripts/ 호출
│   ├── settings.local.json          # 개인 설정·Git 추적 제외
│   ├── rules/                       # 필요한 Claude 전용 규칙
│   ├── skills/                      # 필요한 Claude 전용 스킬
│   ├── agents/                      # 필요한 Claude 전용 에이전트 설정
│   └── commands/                    # 필요한 Claude 전용 명령
│
├── contracts/                       # AI 에이전트·검증 도구가 참조하는 계약 원본
│   ├── api/                         # OpenAPI 원본: AI 참조·계약 검사·HTML 생성 입력
│   │   ├── common.yaml              # 공통 스키마·참조
│   │   ├── commercecore-service.yaml
│   │   ├── payment-service.yaml
│   │   ├── catalog-service.yaml
│   │   ├── member-service.yaml
│   │   ├── live-service.yaml
│   │   └── notification-service.yaml
│   └── events/                      # AsyncAPI
├── services/                        # 기존 6개 서비스 구현·설정·테스트
│   ├── commercecore-service/
│   ├── payment-service/
│   ├── catalog-service/
│   ├── member-service/
│   ├── live-service/
│   └── notification-service/
├── common-module/                   # 공통 구현·테스트
├── .github/                         # CI·계약 검사·변경 선택 도구
├── build.gradle.kts                 # 기존 빌드 설정
├── settings.gradle.kts              # 기존 모듈 등록
├── gradle/                          # 기존 Gradle wrapper 설정
├── gradlew                          # 기존 Unix 실행 진입점
└── gradlew.bat                      # 기존 Windows 실행 진입점
```

서비스의 `states/`는 조사한 변경 코드·기존 테스트 경로를 연결한다. Member의 세션·Notification의 읽음/구독처럼 enum이 없는 수명 주기와 Catalog의 조회 표시도 구분한다. 전체 런타임 경로를 실행한 상태도라고 주장하지 않는다. 위 구조는 하네스·문서와 주요 구현 디렉터리를 보여 주며 Java 패키지·빌드 산출물의 하위 파일은 생략했다.

## 모듈 문서의 원본과 갱신

| 문서 | 근거와 작성 방식 | 사람이 확인할 HTML |
|---|---|---|
| 모듈 안내 | `README.md`에 책임·소유 데이터·외부 의존·관련 코드·문서 링크를 작성한다. | 별도 복제 페이지를 필수로 만들지 않는다. |
| API 명세 | `contracts/api/<서비스명>.yaml`과 공통 참조를 원본으로 유지한다. 생성 도구·버전을 고정하고 계약 변경 때 재생성한다. | `api.html`에서 경로·인증·요청·응답·스키마를 탐색한다. |
| DB ERD | 마이그레이션 적용 순서와 엔티티 매핑을 대조해 `erd.md`에 테이블·키·관계·제약과 근거를 남긴다. | `erd.html`에서 물리 FK·논리 참조·다른 서비스의 식별자를 구분해 그린다. |
| 상태·수명 주기 | `states/<주제>.md`에 상태값·트리거·선행 조건·허용 전이·부수 효과·실패·멱등 처리와 코드·테스트 근거를 남긴다. | 같은 이름의 HTML에서 상태·조건·실패·복구 경로를 탐색한다. |

API HTML은 저장소 내부 렌더러 버전 1로 YAML·공통 참조를 포함해 생성한다. 설계 HTML은 같은 이름의 MD 표에서 생성한다. 필요한 스타일·스크립트는 출력에 포함하므로 별도 서버·원격 YAML·CDN 요청 없이 열람한다. 생성 결과를 Git에 추적해 바로 열어 볼 수 있게 하고, 입력이 달라지면 최신성 검사로 재생성 누락을 찾는다. ERD·상태 HTML의 재생성 누락도 검사한다. 생성 결과가 같아도 코드와 문서의 전체 의미 일치가 증명되는 것은 아니다.

모듈 문서에는 기준 소스 경로·리비전·미커밋 변경 반영 여부·확인 범위·미확인 사항을 적는다. 코드에서 읽은 설계와 실제 DB·HTTP·브로커 실행 결과를 구분한다. 공통 모듈의 Outbox 저장 구조는 실제 데이터를 소유한 서비스의 ERD에 포함한다.

여러 서비스에 걸친 흐름은 `docs/design-docs/order-payment.md` 같은 상위 문서에서 설명하고 모듈 내부 상세로 연결한다. 모듈별 근거와 작성 순서는 [문서 범위표](exec-plans/completed/330-document-harness/tables.md), 전체 단계와 완료 기준은 [실행 계획](exec-plans/completed/330-document-harness/README.md)에 있다.

## 배치와 갱신 기준

- `AGENTS.md`는 작업을 시작할 때 필요한 지도다. 상세 규칙과 공유 맥락은 해당 문서로 연결한다.
- 전체 서비스 경계는 `ARCHITECTURE.md`, 서비스별 책임은 `docs/design-docs/services/`, 업무 흐름은 `docs/design-docs/`의 해당 문서에서 설명한다.
- 제품 목적은 `product-specs/`, 검증 가능한 수용 조건은 `requirements/`, 결정 이유는 `adr/`, 구현 방법은 `design-docs/`에 남긴다. 설계 조사·실험·비교 자료도 관련 설계 문서 옆에 둔다.
- 복잡한 작업은 `exec-plans/active/<이슈번호>-<주제>/README.md`에서 계획·상태·검증·관련 자료를 연결한다. 판단·조사·실험 자료는 내용이 있을 때 작업 폴더 안에 추가한다.
- 여러 작업에 적용되는 결정은 공통 ADR, 날짜별 실제 결과는 워크로그를 원본으로 두고 작업 문서에서 링크한다. 모든 작업에 ADR·실행 계획을 새로 요구하지 않는다.
- 완료한 작업 폴더는 `completed/`로 옮기고 연결된 링크도 갱신한다.
- 작업 기록에는 실행 환경과 결과를 함께 남긴다. 로컬 검사, 원격 CI, 실제 DB·브로커 검증을 구분한다.
- 새 자료는 역할에 맞는 위치에 작성하고 관련 문서와 연결한다. 생성 예정 폴더와 예시 파일은 내용이 생길 때 추가한다.
- 오래된 설명은 갱신하거나 대체 문서를 연결한다. 기록을 정정할 때는 당시 사실과 정정 이유가 드러나게 한다.

## 선택 항목과 실제 연결 상태

구조도에서 `선택·미연결`로 표시한 항목은 참고 이미지의 확장 지점이다. 현재 저장소에는 해당 파일을 생성하거나 훅을 등록하지 않았다.

| 항목 | 추가할 때 확인할 내용 |
|---|---|
| `scripts/guard.sh` | 차단할 명령과 예외 범위를 정하고, 필요한 의존성과 실제 호출 경로를 검증한다. |
| `scripts/post-edit.sh` | 변경 파일에 적용할 포맷·검사 범위를 정하고 실제 편집 후 실행되는지 확인한다. |
| `.mcp.json` | 팀이 사용할 MCP 서버와 필요한 설정을 정한다. 자격증명은 저장소에 넣지 않는다. |
| `.claude/settings.json` | 사용할 권한·훅과 `scripts/` 호출을 설정한다. 파일 존재와 실제 훅 실행을 구분한다. |
| `.claude/settings.local.json` | 개인 설정을 분리하고 Git 추적에서 제외한다. |
| `.claude/rules/`, `skills/`, `agents/`, `commands/` | 실제 사용할 Claude 전용 기능이 생길 때 추가한다. 공통 지침은 `AGENTS.md`와 `docs/`를 참조한다. |

지침에 적힌 규칙과 도구로 자동 검사하는 규칙을 구분한다. 훅·가드·MCP 설정은 해당 기능을 구현·연결하고 실제 실행을 확인한 뒤 상태를 갱신한다.
