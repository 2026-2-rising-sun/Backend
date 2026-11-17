# Payment Service 내부 API

<!-- generated: OpenAPI -->

[계약 YAML](../../../../contracts/api/payment-service.yaml) · OpenAPI 3.1.0 · 계약 1.0.0

계약에 선언된 API입니다. 실제 응답·인증·외부 서비스 실행 결과는 워크로그에서 확인합니다.

브라우저 공개 경로가 없는 독립 결제 실행 서비스. 기존 Mock 결제·환불 API는 #347에서 지웠고 지금 열린 경로는 없다(모든 /v1/internal 하위 요청은 서비스 토큰 검사 뒤 403). 새 결제 API는 #348에서 다시 정의한다.

## 공통 인증과 서버

```yaml
security:
- ServiceToken: []
securitySchemes:
  ServiceToken:
    type: apiKey
    in: header
    name: X-Service-Token
    description: COMMERCECORE_PAYMENT_SERVICE_TOKEN으로 인증된 commercecore 호출자만 허용한다.
servers:
- url: http://localhost:8086
```

## 전체 스키마 원본

공통·순환 참조는 계약의 components 정의를 따릅니다.

### payment-service.yaml

```yaml
components:
  securitySchemes:
    ServiceToken:
      type: apiKey
      in: header
      name: X-Service-Token
      description: COMMERCECORE_PAYMENT_SERVICE_TOKEN으로 인증된 commercecore 호출자만 허용한다.
```

입력 SHA-256: `173b324d0e890ce239fa60fde76425098ca41a910d9bc6efc306ebdc3afbfca1`
