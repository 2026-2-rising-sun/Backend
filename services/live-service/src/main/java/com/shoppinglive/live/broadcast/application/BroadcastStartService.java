package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastProduct;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastProductRepository;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.integration.ProductLookupException;
import com.shoppinglive.live.integration.commerce.SalesClient;
import com.shoppinglive.live.integration.commerce.SalesSnapshot;
import com.shoppinglive.live.integration.ivs.IvsPlaybackInfo;
import com.shoppinglive.live.integration.ivs.IvsReadinessClient;
import com.shoppinglive.live.integration.ivs.IvsUnavailableException;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 방송 시작. IVS 준비와 현재 ON_SALE/SOLD_OUT 연결 상품 ≥1 을 확인한 뒤 LIVE 로 전환한다.
 * available 재고는 시작 판단에 쓰지 않고 재고 조회·차감·예약도 하지 않는다.
 * 외부 조회는 긴 DB 잠금 밖에서 하고, 전이 직전 짧은 트랜잭션에서 방송 version 을 재확인한다.
 * 동일 channelArn 의 동시 LIVE 는 V3 의 partial unique index 가 막는다.
 */
@Service
public class BroadcastStartService {
    private final BroadcastRepository broadcasts;
    private final BroadcastProductRepository links;
    private final IvsReadinessClient ivs;
    private final SalesClient salesClient;
    private final TransactionTemplate transaction;

    public BroadcastStartService(final BroadcastRepository broadcasts,
                                 final BroadcastProductRepository links,
                                 final IvsReadinessClient ivs,
                                 final SalesClient salesClient,
                                 final PlatformTransactionManager transactionManager) {
        this.broadcasts = broadcasts;
        this.links = links;
        this.ivs = ivs;
        this.salesClient = salesClient;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Broadcast start(final long broadcastId, final long expectedVersion) {
        final Broadcast broadcast = read(broadcastId);
        if (broadcast.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.CONFLICT, "방송이 변경되었습니다. 다시 조회하세요.");
        }
        // 이미 LIVE 면 외부를 다시 확인하지 않고 최초 결과를 그대로 돌려준다.
        if (broadcast.getStatus() == BroadcastStatus.LIVE) {
            return broadcast;
        }
        if (broadcast.getStatus() == BroadcastStatus.ENDED) {
            throw new BusinessException(ErrorCode.CONFLICT, "종료된 방송은 다시 시작할 수 없습니다.");
        }

        requireIvsReady(broadcast);
        requireBroadcastableProduct(broadcastId);

        return transaction.execute(ignored -> {
            final Broadcast fresh = read(broadcastId);
            fresh.start(expectedVersion, Instant.now());
            try {
                broadcasts.flush();
            } catch (DataIntegrityViolationException e) {
                // V3 partial unique index: 같은 채널로 이미 다른 방송이 LIVE 다.
                throw new BusinessException(ErrorCode.CONFLICT,
                    "같은 채널로 이미 진행 중인 방송이 있습니다.");
            }
            return fresh;
        });
    }

    /** LIVE+HEALTHY 이면서 저장된 playbackUrl 이 채널의 실제 URL 과 같아야 시작할 수 있다. */
    private void requireIvsReady(final Broadcast broadcast) {
        final boolean ready;
        final IvsPlaybackInfo playback;
        try {
            ready = ivs.isReady(broadcast.getChannelArn());
            playback = ivs.getPlaybackInfo(broadcast.getChannelArn());
        } catch (IvsUnavailableException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "영상 준비 상태를 확인할 수 없습니다.");
        }
        if (!ready) {
            throw new BusinessException(ErrorCode.CONFLICT, "IVS 채널이 아직 송출 중이 아닙니다.");
        }
        if (!broadcast.getPlaybackUrl().equals(playback.playbackUrl())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "저장된 시청 URL이 채널의 실제 URL과 다릅니다.");
        }
    }

    /** ON_SALE 또는 SOLD_OUT 상품이 최소 1개. available 은 보지 않는다. */
    private void requireBroadcastableProduct(final long broadcastId) {
        final List<BroadcastProduct> connected =
            links.findByBroadcastIdOrderByPositionAsc(broadcastId);
        if (connected.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "연결된 상품이 없습니다.");
        }
        final List<SalesSnapshot> sales;
        try {
            sales = salesClient.get(connected.stream()
                .map(BroadcastProduct::getProductId).toList());
        } catch (ProductLookupException e) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "판매 정보를 조회할 수 없습니다.");
        }
        if (sales.stream().noneMatch(snapshot -> snapshot.status().broadcastable())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "판매중 또는 품절인 연결 상품이 최소 1개 필요합니다.");
        }
    }

    private Broadcast read(final long broadcastId) {
        return broadcasts.findById(broadcastId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
    }
}
