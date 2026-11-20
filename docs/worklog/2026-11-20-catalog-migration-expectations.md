# Catalog V5 테이블 목록 회귀 보완

## 초기 누락과 근거

Catalog PR 귀속 후속 수정이다. 기준은 Backend HEAD `3537340c4686fec3b962da8ed02d368829d639af` 위의 B 불변 인계 `rev-b-20261120-0d689babfe92f2ff`이며 기존 revision은 덮어쓰지 않는다. 업무 코드·migration·API·이벤트 계약은 변경하지 않는다.

E가 독립 PostgreSQL의 Catalog migrationTest 전체3건을 실행해1건 실패(종료1)를 보고했다. B가 읽은 `CatalogSchemaPostgresTest` XML에는 클래스2건 중1건 실패가 기록돼 있다. V5가 실제 생성한 `catalog_snapshot_state`와 `catalog_snapshot_receipt`가 `initializesOnlyReplicaTables`의 exact 기대 목록에서 빠진 것이 원인이다. 최초 B 검증에서는 Catalog migrationTest 전체를 미실행으로 명시했으며 이 누락을 잡지 못했다. 이전 실패를 통과로 소급하지 않는다.

## 수정과 경계

테스트명을 `initializesOnlyReplicasAndSnapshotTracking`으로 바꾸고 표시명을 조회 사본·스냅샷 적용 상태·수신 원문에 맞췄다. V5 두 테이블을 추가한 업무 테이블6개와 Flyway 이력1개를 `containsExactlyInAnyOrder`로 계속 검증한다. 부분 포함·와일드카드·예외 무시는 추가하지 않았다.

V5 두 테이블은 조회 사본의 독립 revision/삭제 fence/미수신 payload 및 eventId 수신 원문을 보관한다. Core 상품 원본의 쓰기 권한, 이미지 파일·파일 메타데이터, 상품 등록 멱등키를 Catalog에 두는 변경이 아니다. 기존 `product.idempotency_key` 부재 검사와 Core ID 강제·최초 버전0·음수 거절 검사를 유지한다. [Catalog ERD](../design-docs/services/catalog/erd.md)에 이 저장 경계와 정확한 목록 보호를 연결했다.

## 실제 검증

검증 전용 접속은 B의 Lima `127.0.0.1:25433/catalog_cdc_b`와 각 테스트의 UUID schema다. E의15433 DB에는 접속하지 않았고, E 실패 XML과 다른 작업공간은 읽기만 했다. 비밀번호는 문서·산출물에 저장하지 않는다.

```bash
# B 전용 CATALOG_TEST_POSTGRES_{URL,USER,PASSWORD}와 Java21 설정 후
./gradlew :services:catalog-service:migrationTest --max-workers=2 --console=plain
./gradlew :services:catalog-service:spotlessCheck --max-workers=2 --console=plain
scripts/check-docs.sh
# 기존 API 가상환경의 Python으로 실행
python scripts/render_design_docs.py --check
git diff --check
```

수정 후 B 전용 PostgreSQL에서 Catalog migrationTest 전체3건이 실제 실행돼 실패·오류·스킵0, 종료0을 확인했다. V5 정확한 테이블 목록과 상품 ID/버전 제약2건, 기존 SKU 사본 보존 이행1건이다. 포맷과 문서 검사는 별도 로그로 전달한다. 전체 build·다른 서비스 검사·PG 결제·운영 환경은 이번 작은 테스트 보완 범위가 아니다.

## 증분 전달과 저장 상태

최종 B 이후 변경한 테스트1개·Catalog ERD MD/HTML·이 기록 MD/HTML만 새 불변 revision의 증분 patch/신규 파일 묶음으로 전달한다. before/after SHA-256, 기준 B receipt SHA-256, 최초 E 실패 XML, 수정 후 검사 로그·JUnit XML을 receipt에 포함한다. E는 기준 B를 적용한 상태에서 before hash를 대조하고 증분을 적용해야 한다.

커밋·푸시·PR·이슈 수정은 하지 않았다. 다른 B/A 파일의 기존 미커밋 변경과 이전 불변 인계는 보존한다. 수정 후 B 검증이 통과해도 E의 통합 공간에서 재실행한 것으로 간주하지 않는다.
