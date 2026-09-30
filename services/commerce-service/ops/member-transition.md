# 회원 전용 Commerce 데이터 전환

기존 V1은 수정하지 않는다. 신규 빈 DB는 V1→V2→V3을 바로 적용한다.
비회원 주문이 남은 DB는 V3의 NOT NULL 제약에서 중단되며 자동 삭제하지 않는다.

1. 대상 DB·현재 주문/결제/예약 재고를 백업하고 모든 Commerce 인스턴스와 scheduler를 중지한다.
2. 배포용 Flyway에서 `target=2`로 V2까지만 적용한다. 애플리케이션을 이 상태로 공개하지 않는다.
3. 승인된 **개발용 local/dev DB만**, 아래 환경 변수로 대상을 명시하여 정리 스크립트를 실행한다.
4. Flyway target 제한을 제거하고 V3 적용 후 회원 전용 코드를 시작한다.
5. 회원 주문 생성·재고 예약·본인 조회와 비회원 401을 확인한 뒤 트래픽을 연다.

```sh
# PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD는 승인된 개발 DB 접속값을 직접 설정한다.
export COMMERCE_STOPPED=yes
export CONFIRM_DATABASE="$PGDATABASE@$PGHOST:${PGPORT:-5432}"
./ops/purge-development-guest-orders.sh local DELETE-DEVELOPMENT-GUEST-ORDERS
```

스크립트는 member_id가 NULL인 주문과 그 결제 시도만 삭제한다. 미결제·결제 진행 중
예약 재고는 먼저 복원하며 회원 주문과 이미 소비된 PAID 재고는 보존한다.
예약 재고 불일치면 전체 트랜잭션이 실패한다. 실행 후 주문 건수·회원 행·재고를 백업과 비교한다.
환경 인수는 DB를 자동 판별하는 장치가 아니다. 운영·미지정 DB에는 삭제 승인이 없다.
보존이 필요한 다른 환경은 별도 이관 정책을 확정할 때까지 V3을 진행하지 않는다.

V2부터 전역 멱등키 대신 (member_id, idempotency_key)를 사용하므로 구·신 코드를 혼합 실행하지 않는다.
회원 주문 생성 이후 구 버전으로 단순 롤백하지 않는다. 복구가 필요하면 트래픽을 차단하고
전환 전 백업을 복원하거나 회원 데이터를 이해하는 수정 버전을 배포한다.
