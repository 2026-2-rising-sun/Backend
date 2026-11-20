# 상품 통합 등록·relay의 CDC 전환 회귀 보완

## 처음 확인한 실패

기준은 Backend HEAD `3537340c4686fec3b962da8ed02d368829d639af` 위의 B 불변 `rev-b-20261120-0d689babfe92f2ff`다. 조정자가 전달한 E의 `external-remaining.log`를 읽어 Core postgresTest190건 중4건 실패(종료1)를 확인했다. 이 수치는 E 실행이며 B의 재실행 결과로 대체하지 않는다.

- ProductBundleApiPostgresTest의 총 Outbox3건 기대값.
- ProductBundleRegistrationPostgresTest의 총3건 기대값과 구 상품 토픽만 차단한 Outbox 장애 주입.
- ProductBundleRelayPostgresTest의 구 상품/판매 토픽 세 이벤트 수신 기대값.

B 최초 검사에서는 이 외부 PostgreSQL 클래스들을 실행하지 않았고 전체 외부 선택 미실행으로 남겼다. 통합에서 드러난 회귀 누락을 이번 별도 증분으로 보완한다.

## 분포와 실패 경계 대조

실제 생산자는 ProductInfoEventPublisher와 SkuEventTracker→CatalogSnapshotPublisher/EventOutbox다. 상품1개·SKU2개 등록 직후의 정확한 분포는 ProductSnapshotChanged1+SkuSnapshotChanged2(모두 Catalog CDC 토픽)와 SalesRegistered2(기존 sales-info 토픽), 합계5건이다. 각각 product:{id}, sku:{id}, 숫자 SKU ID 키를 사용한다. 수량은 inventory_version0/inventory_published_version-1로 남고 InventorySnapshotChanged는 등록 직후0건이다. 기본60초 재고 배치와 알림 보존 정책에 맞는 별도 사실이므로 중복/누락으로 판정하지 않았다.

API와 서비스 회귀는 공통 검증 도우미로 **전체 타입·토픽·키 exact 목록**과 payload의 상품 ID/버전/판매의도/표시 상태를 대조한다. 같은 요청 재시도 뒤에도 같은5건을 확인한다. 단순 총개수 조건 완화는 하지 않았다.

CHECK 제약을 topic+event_type에 걸어 ProductSnapshotChanged, SkuSnapshotChanged, SalesRegistered 각각의 실제 append를 실패시켰다. 상품·SKU·이미지 메타데이터·Outbox가 모두0건이며 완료 response_json도0건, 참조 없는 실제 파일 삭제를 확인한다. 별도 커밋된 product_registration 복구 위치는 설계대로 남으므로 이를 업무 완료 결과와 혼동하지 않는다. 제약을 해제한 같은 요청의 재시도·최초 결과 재응답도 확인한다.

## Relay 검증 범위

일반과 claim 두 모드를 실제 OutboxRelay/OutboxLeaseStore로 각각 실행한다. 별도 Spring 자동 relay를 끄고 실제 커밋된 Outbox를 production relay 클래스에 전달한다. `excluded-topics`는 Core와 같은 Catalog 토픽이다. 실패 등록은 전송0건, 성공 등록은 기존 SalesRegistered2건만 Kafka ACK/DB 발행표시 후 실제 EmbeddedKafka consumer에서 수신한다. topic/key/eventType/eventId header와 ON_SALE/SOLD_OUT 표시를 대조하고, Catalog CDC3행은 published_at NULL/attempts0으로 남는 것을 확인한다. 구 commerce.product-info.v1 행도 만들지 않는다.

Catalog 메시지를 테스트 코드에서 Kafka에 수동 재발행하지 않는다. 이 검사는 실제 PostgreSQL 등록 원자성과 두 relay 모드의 판매 알림 전송 책임 증거이며, 앱→CDC→Catalog 종단은 E의 별도 검증이다. 업무 소스나 생산 계약의 결함을 발견한 것으로 보고하지 않으며 이번 변경은 테스트와 관련 설명만이다.

## 실행과 결과

B 전용 Lima `127.0.0.1:25433/catalog_cdc_b`와 각 클래스의 독립 UUID schema를 사용했다. E15433 DB에는 접속하지 않았으며 E와 다른 공간은 읽기만 했다. 비밀번호는 기록하지 않는다.

```bash
# B 전용 COMMERCECORE_TEST_POSTGRES_{URL,USER,PASSWORD}와 Java21 설정 후
./gradlew :services:commercecore-service:postgresTest \
  --tests '*ProductBundleApiPostgresTest' \
  --tests '*ProductBundleRegistrationPostgresTest' \
  --tests '*ProductBundleRelayPostgresTest' \
  --max-workers=2 --console=plain
./gradlew :services:commercecore-service:spotlessCheck --max-workers=2 --console=plain
scripts/check-docs.sh
python scripts/render_design_docs.py --check
git diff --check
```

B 전용 PostgreSQL에서 세 클래스 전체14건(API1·등록11·relay 일반/claim2)이 실제 실행돼 실패·오류·스킵0, 종료0을 확인했다. 포맷 검사와 문서272개/설계·기록 MD·HTML113쌍 검사도 오류0이다. JUnit XML과 로그는 인계 receipt에 포함한다. 불필요한 전체 build나 실제 PG·운영 검증은 하지 않았다.

## 적용 순서와 저장 상태

A→기존 frozen B→앞 Catalog schema 증분 `rev-catalog-schema-20261120-2096c476a6e37abf`→이번 ProductBundle 증분 순서로 인계한다. 두 증분은 파일 소유가 겹치지 않으며 원본 frozen B와 첫 증분을 덮어쓰지 않는다. 수정 전후 해시·patch·신규 도우미/기록 파일·E 초기 실패 로그와 B 수정 후 XML/로그를 새 불변 revision에 넣는다.

커밋·푸시·PR·이슈 수정은 하지 않았다. E 적용 및 E 공간 재실행은 별도이며 B 결과를 E에서 이미 실행한 것으로 표시하지 않는다.
