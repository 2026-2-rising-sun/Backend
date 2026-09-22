package com.shoppinglive.shopping.sales.infrastructure;

import com.shoppinglive.shopping.sales.domain.SalesInfo;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로컬 수동 E2E 용 판매정보 stub 시드 컨트롤러. {@code local} 프로필 + stub 모드에서만 뜬다.
 * 그 밖의 환경에서는 Commerce 가 판매정보의 원본이라 Shopping 에 판매정보 쓰기 경로가 있으면 안 된다.
 */
@RestController
@RequestMapping("/v1/dev/sales-stub")
@Profile("local")
@ConditionalOnProperty(prefix = "shopping.sales-client", name = "mode", havingValue = "stub", matchIfMissing = true)
public class SalesStubDevController {

    private final InMemorySalesInfoClientStub stub;

    public SalesStubDevController(InMemorySalesInfoClientStub stub) {
        this.stub = stub;
    }

    /** 상품의 판매정보를 등록하거나 덮어쓴다. */
    @PutMapping("/{productId}")
    public ResponseEntity<Void> seed(@PathVariable Long productId, @Valid @RequestBody SalesStubSeedRequest request) {
        stub.register(new SalesInfo(
            productId, request.salesId(), request.price(), request.status(), request.available()));
        return ResponseEntity.noContent().build();
    }

    /** 판매정보와 장애 상태를 모두 초기화한다. */
    @DeleteMapping
    public ResponseEntity<Void> clear() {
        stub.clear();
        return ResponseEntity.noContent().build();
    }
}
