package com.shoppinglive.shopping.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.shoppinglive.shopping.client.commerce.HttpCouponSnapshotClient;
import com.shoppinglive.shopping.coupon.dto.CouponSnapshot;
import com.shoppinglive.shopping.coupon.repository.CouponDisplayRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CouponSyncTest {

    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    private static final String BASE = "http://commerce";

    private MockRestServiceServer server;
    private CouponDisplayRepository replica;
    private CouponSync sync;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        replica = mock(CouponDisplayRepository.class);
        when(replica.apply(any())).thenReturn(true);
        sync =
                new CouponSync(
                        new HttpCouponSnapshotClient(builder.build()),
                        replica,
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("목록을 마지막 페이지까지 읽어 모두 반영한다")
    void appliesEveryPage() {
        expectPage(null, "\"c2\"", true, coupon("c1"), coupon("c2"));
        expectPage("c2", "null", false, coupon("c3"));
        when(replica.findOpenCouponIds(NOW)).thenReturn(List.of());

        assertThat(sync.sync()).isEqualTo(3);

        ArgumentCaptor<CouponSnapshot> applied = ArgumentCaptor.forClass(CouponSnapshot.class);
        verify(replica, times(3)).apply(applied.capture());
        assertThat(applied.getAllValues())
                .extracting(CouponSnapshot::couponId)
                .containsExactly("c1", "c2", "c3");
        server.verify();
    }

    private void expectPage(String afterId, String nextCursor, boolean hasNext, String... items) {
        String url =
                BASE
                        + "/v1/internal/coupon-snapshots?size=100"
                        + (afterId == null ? "" : "&afterId=" + afterId);
        String body =
                "{\"success\":true,\"data\":{\"items\":[%s],\"nextCursor\":%s,\"hasNext\":%s},\"error\":null}"
                        .formatted(String.join(",", items), nextCursor, hasNext);
        server.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String coupon(String id) {
        return """
               {"couponId":"%s","snapshotVersion":1,"name":"쿠폰","fixedDiscount":1000,
                "productIds":[501],"startsAt":"2026-10-15T09:00:00Z","endsAt":"2026-10-16T09:00:00Z",
                "expiresAt":"2026-10-17T09:00:00Z","soldOut":false}"""
                .formatted(id);
    }
}
