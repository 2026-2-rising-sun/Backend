# Swagger에서 API 명세 보기

Swagger UI는 같은 Git YAML을 화면으로 읽는 문서 뷰어다. 실제 서비스나 Mock 서버를 대신하지 않는다.
Member·Commerce·Shopping·Live를 상단 선택 메뉴에서 바꿔 볼 수 있다.

## 실행

Node.js 22 이상과 Docker Desktop을 준비하고 Backend에서 실행한다.

```sh
npm ci --prefix scripts/contracts --ignore-scripts
node scripts/local/contracts.cjs docs
```

브라우저에서 <http://127.0.0.1:18090>을 연다. 별도 포트가 필요하면 `docs 18091`처럼 지정한다.
기존 Kafka UI의 8090을 기본값으로 사용하지 않는다. 실행 터미널을 켜 두고, 끝나면 Ctrl-C한다.

## 읽는 순서

| 화면 | 확인할 내용 |
|---|---|
| 서비스 선택 | API를 소유하는 Member·Commerce·Shopping·Live 구분 |
| method/path와 설명 | 공개·회원·ADMIN·서비스 caller 작업 구분 |
| Parameters / Request body | path/query/header, 필수 입력, JSON 필드 |
| Responses | 성공과 400/401/403/404/409/503, 204의 빈 본문 |
| Schemas | nullable, enum, UUID, ApiResponse와 Commerce raw DTO 구분 |

Member의 refresh/logout/탈퇴/강제 폐기와 내부 상태 API를 각각 펼쳐 정책 차이를 읽는다.
일반 logout은 기존 access를 즉시 차단하지 않으며, 재사용·강제 폐기·탈퇴는 이후 신규 인증 검사부터 차단한다.
세션 확인이 불가할 때의 503과 상품/판매 연동 실패 503은 `error.code`와 설명을 함께 확인한다.

Try it out은 현재 설정에서 비활성화했다. 직접 호출은 [수동 검수 환경](manual-review.md)과 Apidog·Bruno·curl을 사용한다.
문서가 열리는 것만으로 서비스가 실행 중이거나 인증·DB 동작이 성공했다고 판단하지 않는다.

## 어떤 버전인지 확인하기

실행 시 YAML 사본과 Git SHA/hash를 만들어 보여준다. 메뉴의 `working tree @ <SHA>`는 작업 파일 스냅샷이며 독립 리뷰 승인 표시는 아니다.
<http://127.0.0.1:18090/contracts/provenance.json>에서 원본 파일명·hash를 확인할 수 있다.
YAML을 수정했으면 문서 프로세스를 종료 후 다시 실행한다. 이미 열린 스냅샷은 자동으로 바뀌지 않는다.

설정은 `contracts/docs/swagger-config.json`이며 고정 이미지 버전은 `scripts/contracts/images.json`에 있다.
`urls`로 같은 네 YAML을 선택하게 하는 방식은 [Swagger UI 공식 설정](https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/)을 따른다.
