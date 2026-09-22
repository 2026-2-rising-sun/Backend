package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.api.BroadcastProductViewResponse;
import com.shoppinglive.live.broadcast.domain.BroadcastProduct;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastProductRepository;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.integration.ProductLookupException;
import com.shoppinglive.live.integration.commerce.SalesClient;
import com.shoppinglive.live.integration.commerce.SalesSnapshot;
import com.shoppinglive.live.integration.shopping.ProductClient;
import com.shoppinglive.live.integration.shopping.ProductSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 방송 연결 순서로 Shopping 기본정보와 Commerce 최신 판매정보를 결합한다.
 * 연결 집합은 읽기만 하고 가격·재고를 복제 저장하지 않는다. 필수 외부 조회가 실패하면
 * 빈 목록으로 숨기지 않고 전체 503 을 반환한다(기본정보·영상 endpoint 는 계속 동작한다).
 */
@Service
public class BroadcastProductViewService {
    private final BroadcastRepository broadcasts;
    private final BroadcastProductRepository links;
    private final ProductClient productClient;
    private final SalesClient salesClient;

    public BroadcastProductViewService(final BroadcastRepository broadcasts,
                                       final BroadcastProductRepository links,
                                       final ProductClient productClient,
                                       final SalesClient salesClient) {
        this.broadcasts = broadcasts;
        this.links = links;
        this.productClient = productClient;
        this.salesClient = salesClient;
    }

    /** 관리 조회는 정상 조회에서 사라진 연결도 missing 으로 드러낸다. */
    public List<BroadcastProductViewResponse> forAdmin(final long broadcastId) {
        return view(broadcastId, false);
    }

    /** 공개 조회는 ON_SALE/SOLD_OUT 만 보여주고 READY/PRIVATE·삭제된 상품은 제외한다. */
    public List<BroadcastProductViewResponse> forPublic(final long broadcastId) {
        return view(broadcastId, true);
    }

    private List<BroadcastProductViewResponse> view(final long broadcastId,
                                                    final boolean publicOnly) {
        final List<BroadcastProduct> connected = readLinks(broadcastId);
        if (connected.isEmpty()) {
            return List.of();
        }
        final List<Long> productIds = connected.stream()
            .map(BroadcastProduct::getProductId).toList();

        final Map<Long, ProductSnapshot> products;
        final Map<Long, SalesSnapshot> sales;
        try {
            products = productClient.get(productIds).stream()
                .collect(Collectors.toMap(ProductSnapshot::id, Function.identity()));
            sales = salesClient.get(productIds).stream()
                .collect(Collectors.toMap(SalesSnapshot::productId, Function.identity()));
        } catch (ProductLookupException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "상품 정보를 조회할 수 없습니다.");
        }

        final List<BroadcastProductViewResponse> view = new ArrayList<>();
        for (final BroadcastProduct link : connected) {
            final Long productId = link.getProductId();
            final SalesSnapshot sale = sales.get(productId);
            if (publicOnly && (sale == null || !sale.status().broadcastable()
                || !products.containsKey(productId))) {
                continue;
            }
            view.add(BroadcastProductViewResponse.of(link.getId(), link.getPosition(), productId,
                link.getSalesId(), products.get(productId), sale));
        }
        return view;
    }

    private List<BroadcastProduct> readLinks(final long broadcastId) {
        if (!broadcasts.existsById(broadcastId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다.");
        }
        return links.findByBroadcastIdOrderByPositionAsc(broadcastId);
    }
}
