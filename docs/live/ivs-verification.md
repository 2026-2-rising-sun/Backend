# IVS 준비 조회 및 시청 검증

## 개요

Issue #64 구현: AWS IVS 영상 시청 준비 상태를 조회하고 플레이백 URL을 확인하는 서비스.

## 서비스 계약

`IvsReadinessClient` 인터페이스는 두 가지 작업을 제공한다.

### `boolean isReady(String channelArn)`

주어진 채널 ARN의 현재 스트림 상태를 조회한다.

- **READY**: 스트림이 LIVE + HEALTHY인 경우
- **NOT_READY**: 채널이 미송출 상태(ChannelNotBroadcastingException) 또는 STARVING 상태인 경우
- **UNAVAILABLE**: SDK 호출 실패, 권한 오류, 네트워크 오류 등 → `IvsUnavailableException` (원문 진단 미노출)

### `IvsPlaybackInfo getPlaybackInfo(String channelArn)`

채널 ARN의 플레이백 URL을 조회한다.

- 성공: `IvsPlaybackInfo(playbackUrl)` 반환
- 실패: URL이 없거나 SDK 호출 실패 → `IvsUnavailableException`

## 구현 전략

### AWS 모드 (기본값)

환경 변수 또는 설정: `live.ivs.mode=aws` (기본값)

- `GetStream`: 현재 스트림 상태 확인 (isReady)
- `GetChannel`: 채널 플레이백 URL 확인 (getPlaybackInfo)
- AWS 기본 자격증명 체인 사용 (boto profile, env vars, IAM role 등)
- SDK 타임아웃: 전체 3초, 개별 시도 2초

#### 자격증명 보안

- 자격증명은 SDK 클라이언트 빌더(Region, credentials)에만 사용하며, 로그·응답에 기록하지 않음
- AWS SDK 전체 응답을 직렬화하지 않음 (원문 진단이 민감 정보 포함 가능)
- 각 SDK 예외를 `IvsUnavailableException`으로 감싸 원문 메시지 미노출

### Stub 모드 (local/test 프로필)

환경 변수 또는 설정:
- `spring.profiles.active=local` 또는 `test`
- `live.ivs.mode=stub`

#### 특성

- AWS 클라이언트 생성 안 함 → 자격증명 필요 없음
- `isReady()`: `live.ivs.stub-ready` 설정값 반환 (기본값 false)
- `getPlaybackInfo()`: 고정 테스트 URL 반환
- 실제 영상 송출/수신 없음

#### 활성화 조건

- `local` 또는 `test` 프로필에서만 사용 가능
- 다른 프로필에서 설정 시 애플리케이션 시작 실패 (fail-closed)

## 테스트

### 단위 테스트 (IvsReadinessTest)

모든 테스트는 AWS SDK를 mock으로 교체하며, 실제 AWS 호출 없음.

#### isReady 테스트

1. **liveStreamIsReadyAndOfflineStreamIsNot**: LIVE + HEALTHY = true, ChannelNotBroadcasting = false
2. **starvingOrUnknownHealthIsNotReady**: STARVING/null health = false
3. **missingStreamIsNotReady**: stream이 null = false
4. **networkAndPermissionFailuresAreUnavailableAndSanitized**: SDK 오류 → IvsUnavailableException (원문 미노출)

#### getPlaybackInfo 테스트

5. **getPlaybackUrlReturnsChannelPlaybackUrl**: GetChannel → playbackUrl 반환
6. **getPlaybackUrlThrowsWhenChannelHasNoPlaybackUrl**: 채널에 playbackUrl 없음 → IvsUnavailableException
7. **getPlaybackUrlThrowsWhenChannelResponseIsNull**: 채널 정보 없음 → IvsUnavailableException
8. **getPlaybackUrlHandlesGetChannelErrors**: SDK 오류 → IvsUnavailableException (원문 미노출)

#### Spring 설정 테스트

9. **explicitLocalStubRequiresNoSdkAndCanSimulateReady**: stub 모드에서 AWS 클라이언트 미생성, 자격증명 불필요
10. **testStubDefaultsToNotReady**: test 프로필의 stub은 false 반환
11. **stubIsRejectedOutsideLocalAndTest**: dev 등 다른 프로필에서 stub 사용 시 실패
12. **unknownModeFailsClosed**: 알 수 없는 mode 값은 실패
13. **defaultModeUsesAwsWithoutCallingAwsDuringStartup**: AWS 모드가 기본값, 시작 시 AWS 호출 없음
14. **stubProvidesPlaybackUrlWithoutSdk**: stub 모드에서 playbackUrl 제공, SDK 미생성

## 설정

### application.properties (기본값)

```properties
live.ivs.mode=aws
live.ivs.region=ap-northeast-2
live.ivs.stub-ready=false
```

### local/test 오버라이드

```properties
# application-local.properties
spring.profiles.active=local
live.ivs.mode=stub
live.ivs.stub-ready=false

# application-test.properties
spring.profiles.active=test
live.ivs.mode=stub
live.ivs.stub-ready=false
```

## E2E 검증 (미완성 - 물리적 환경 필요)

현재 단계에서는 자동 테스트(mock SDK)만 수행했다. 실제 AWS 송출·시청 검증은 다음 환경이 필요하다.

### 준비 조건

1. **AWS 계정**: 실제 IVS 채널 및 자격증명
2. **OBS**: 실제 송출 (GetStream의 LIVE + HEALTHY 상태 재현)
3. **브라우저/플레이어**: IVS Player 또는 hls.js 플레이어로 playbackUrl 재생 테스트
4. **네트워크**: 로컬 개발 환경에서 AWS 접근 가능

### 검증 절차 (수동)

1. 기존 채널의 ARN과 playback URL을 방송에 등록한다.
2. 송출 비밀은 OBS에만 설정한다.
3. 미송출 시 `isReady(arn)` = false 확인.
4. OBS에서 송출 시작 후 `isReady(arn)` = true 확인.
5. `getPlaybackInfo(arn).playbackUrl()`이 실제 playback URL과 일치하는지 확인.
6. 해당 URL을 IVS Player/플레이어로 열어 실제 영상·음성 재생 확인.
7. OBS 일시 단절 후 업무 방송 상태 유지 여부, 재연결 후 재생 복구 여부 확인.

### 블로커

- 현재 환경에서는 실제 AWS 자격증명, 채널, OBS 송출 및 시청 클라이언트가 없어 E2E 검증 불가
- 이 서비스는 **준비 상태 조회만 제공**하며, 영상 프록시나 재생 서버는 없음
- 공개 시청은 FE가 playbackUrl을 IVS Player/hls.js로 직접 재생함

## 의존성

- `software.amazon.awssdk:ivs:2.49.6` (AWS SDK for Java v2)
- Spring Boot 자동 설정 (Configuration)
- Lombok (RequiredArgsConstructor)

## 비밀 관리

다음 항목은 로그, 응답, 테스트 fixture에 절대 기록하지 않는다.

- AWS access key / secret key
- Stream key (ingest secret)
- 채널 stream key
- AWS SDK 원문 예외 메시지 (connection details, diagnostic info 포함)

## Broadcast 연동 (완료)

이 문서 초안 시점(R9)에는 이 서비스가 Broadcast와 분리된 독립 조회 서비스였다. 이후 R10(#65)·R12(#64 시청 연결)에서 다음과 같이 통합이 끝났고, 현재 dev 제출 Stack(`feat/#64-viewing`, PR #83 기준)에 반영돼 있다:

1. `Broadcast.channelArn`을 저장하고 있다(#59, `broadcast` 테이블).
2. `BroadcastStartService.start()`가 시작 직전 `IvsReadinessClient`를 호출해 READY(LIVE+HEALTHY) 및 channelArn/playbackUrl 동일성을 확인한다 — 미준비/불일치 시 시작을 거절한다.
3. `PublicBroadcastService`가 공개 상세 조회(`GET /v1/broadcasts/{id}`) 시 `IvsReadinessClient`를 호출해 `videoStatus`(READY/NOT_READY/UNAVAILABLE)를 채운다. 준비 상태(videoStatus)와 업무 상태(`Broadcast.status`)는 여전히 별개다 — 송출이 끊겨도 업무 상태는 LIVE로 유지된다. 목록 조회는 N+1을 피하기 위해 `videoStatus`를 채우지 않는다(null).
4. 시청 URL은 `PublicBroadcastResponse.playbackUrl`로 FE에 전달되며, `playbackAllowed`(=`status==LIVE`)가 true일 때만 `videoStatus`도 함께 채워진다. ENDED/PREPARING은 둘 다 null이다.

실제 검증 절차는 위 "E2E 검증" 절을 따르되, 위 통합 지점(BroadcastStartService, PublicBroadcastService)이 실제 코드 경로임을 전제로 한다.

## SDK 버전

[AWS SDK for Java v2 2.49.6](https://github.com/aws/aws-sdk-java-v2/releases/tag/2.49.6)

[IvsClient.getStream() 계약](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/ivs/IvsClient.html)

[IvsClient.getChannel() 계약](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/ivs/IvsClient.html#getChannel-software.amazon.awssdk.services.ivs.model.GetChannelRequest-)

---

## R12 공개 시청 연결 (#64)

`GET /v1/broadcasts/{id}` 가 LIVE 방송에 한해 IVS 준비 상태를 조회해 `videoStatus` 로 돌려준다.

| 응답 필드 | 의미 | 출처 |
|---|---|---|
| `status` | 업무 상태(예정/진행/종료) | Live DB |
| `playbackAllowed` | 업무적으로 재생 진입을 제공하는가 | 업무 상태 LIVE 여부 |
| `playbackUrl` | HLS 재생 URL. LIVE 에서만 반환 | 등록 시 저장된 값 |
| `videoStatus` | 지금 이 순간의 영상 신호 | IVS GetStream |

규칙:

- 목록(`GET /v1/broadcasts`)은 IVS 를 호출하지 않는다. 방송 수만큼 SDK 호출이 늘어나는 것을 막는다.
- IVS 조회가 실패해도 상세 응답은 200 이고 `videoStatus: UNAVAILABLE` 로만 표시된다.
  외부 장애가 방송 기본정보 응답을 실패시키지 않는다.
- OBS 가 잠시 끊기면 `videoStatus` 만 `NOT_READY` 가 되고 업무 상태는 LIVE 를 유지한다.
  FE 는 재시도 안내를 띄우고 관리자는 종료를 누르지 않아도 된다.
- 종료하면 `playbackAllowed: false` 이고 `playbackUrl` 과 `videoStatus` 는 모두 null 이다.
  채널을 재사용해 새 송출이 시작돼도 종료된 방송 페이지는 그 URL 을 제공하지 않는다.
  이미 공개 HLS URL 을 가진 사람의 접근 자체를 철회하는 기능은 P1 범위가 아니다.

## 실제 검증 절차 (미수행 — 환경 없음)

아래는 실행 절차이고, 이 PR 시점에 **실제로 수행하지 않았다**. blocker B3 가 열려 있다.

필요한 것: 기존 IVS 채널, GetStream/GetChannel 읽기 권한만 가진 IAM 자격증명, OBS 송출자, FE 플레이어.

1. `live.ivs.mode=aws`, `live.ivs.region` 을 지정하고 자격증명을 환경에 둔 채 기동한다.
   자격증명·streamKey 는 설정 파일·로그·PR 본문에 남기지 않는다.
2. OBS 송출 전: `POST /v1/admin/broadcasts/{id}/start` 가 409 "송출 중이 아닙니다" 인지 확인한다.
3. OBS 송출 시작 → 같은 요청이 200 LIVE 이고 `startedAt` 이 기록되는지 확인한다.
4. `GET /v1/broadcasts/{id}` 의 `playbackUrl` 로 FE 플레이어가 실제로 재생되는지 확인한다.
5. OBS 를 30초 끊었다가 재연결한다. 업무 상태는 LIVE 를 유지하고 `videoStatus` 만
   `NOT_READY` → `READY` 로 돌아오는지 확인한다.
6. `POST /v1/admin/broadcasts/{id}/end` 후 공개 상세에 재생 진입이 없는지 확인한다.
   OBS 는 운영자가 수동으로 중단한다.
7. 같은 채널로 다른 방송을 등록해 동시 LIVE 시도가 409 인지 확인한다.

기록할 것: 실행 환경·시각·각 단계 결과·로그 위치. 자격증명과 송출키는 기록하지 않는다.
이 절차를 통과하기 전에는 P1 전체 완료로 표시하지 않는다.
