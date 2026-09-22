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
import com.shoppinglive.live.integration.shopping.ProductClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 상품 연결·해제. 외부 확인은 트랜잭션 밖에서 하고, 변경 직전 짧은 트랜잭션에서
 * 상태·version·연결 집합을 다시 검증한다. JVM/분산 lock 은 쓰지 않고 방송 행의
 * optimistic version 과 (broadcast_id, product_id) unique 로 동시성을 보장한다.
 */
@Service
public class BroadcastProductService {
    static final int MAX_PRODUCTS = 100;

    private final BroadcastRepository broadcasts;
    private final BroadcastProductRepository links;
    private final ProductClient productClient;
    private final SalesClient salesClient;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;

    public BroadcastProductService(final BroadcastRepository broadcasts,
                                   final BroadcastProductRepository links,
                                   final ProductClient productClient,
                                   final SalesClient salesClient,
                                   final EntityManager entityManager,
                                   final PlatformTransactionManager transactionManager) {
        this.broadcasts = broadcasts;
        this.links = links;
        this.productClient = productClient;
        this.salesClient = salesClient;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public BroadcastProduct link(final long broadcastId, final long productId,
                                 final long expectedVersion) {
        // 1) 긴 DB 잠금을 만들지 않도록 외부 조회를 트랜잭션 밖에서 먼저 한다.
        requireChangeable(readBroadcast(broadcastId), expectedVersion);
        final SalesSnapshot sales = lookup(productId);
        if (!sales.status().broadcastable()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "판매중 또는 품절 상품만 연결할 수 있습니다.");
        }
        // 2) 짧은 트랜잭션에서 상태·version·연결 집합을 다시 검증하고 저장한다.
        return transaction.execute(
            ignored -> persistLink(broadcastId, productId, sales.salesId(), expectedVersion));
    }

    private BroadcastProduct persistLink(final long broadcastId, final long productId,
                                         final long salesId, final long expectedVersion) {
        final Broadcast broadcast = readBroadcast(broadcastId);
        requireChangeable(broadcast, expectedVersion);
        final List<BroadcastProduct> current =
            links.findByBroadcastIdOrderByPositionAsc(broadcastId);
        if (current.stream().anyMatch(link -> link.getProductId() == productId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 연결된 상품입니다.");
        }
        if (current.size() >= MAX_PRODUCTS) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "방송당 연결 상품은 최대 " + MAX_PRODUCTS + "개입니다.");
        }
        bumpVersion(broadcast);
        try {
            return links.saveAndFlush(
                new BroadcastProduct(broadcast, productId, salesId, current.size()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "이미 연결된 상품입니다.");
        }
    }

    /** 같은 방송에서 이미 사라진 linkId 는 204, 다른 방송 소속이면 404. */
    @Transactional
    public void unlink(final long broadcastId, final long linkId, final long expectedVersion) {
        final Broadcast broadcast = readBroadcast(broadcastId);

        final BroadcastProduct link = links.findById(linkId).orElse(null);
        if (link == null) {
            // 이미 해제된 linkId 는 순수 no-op 이다. version 은 검사하지도 올리지도 않아
            // 응답이 유실된 클라이언트가 원래 발급받은 expectedVersion 으로 재시도해도 204 다.
            // 다만 ENDED 는 읽기 전용이므로 no-op 이라도 거절해야 한다.
            if (broadcast.getStatus() == BroadcastStatus.ENDED) {
                throw new BusinessException(ErrorCode.CONFLICT, "종료된 방송은 읽기 전용입니다.");
            }
            return;
        }
        requireChangeable(broadcast, expectedVersion);
        if (!link.getBroadcast().getId().equals(broadcastId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "다른 방송의 연결입니다.");
        }
        final List<BroadcastProduct> current =
            links.findByBroadcastIdOrderByPositionAsc(broadcastId);
        if (broadcast.getStatus() == BroadcastStatus.LIVE && current.size() == 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "진행 중 방송의 마지막 연결 상품은 해제할 수 없습니다.");
        }
        bumpVersion(broadcast);
        links.delete(link);
        int position = 0;
        for (final BroadcastProduct remaining : current) {
            if (!remaining.getId().equals(linkId)) {
                remaining.moveTo(position++);
            }
        }
        links.flush();
    }

    /**
     * 편성 변경은 방송 행의 optimistic version 경계에 참여한다. 연결 테이블만 바뀌어도
     * 방송 version 이 올라가야 시작·정렬·해제가 같은 경계에서 충돌을 감지한다.
     */
    private void bumpVersion(final Broadcast broadcast) {
        entityManager.lock(broadcast, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    }

    Broadcast readBroadcast(final long broadcastId) {
        return broadcasts.findById(broadcastId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
    }

    static void requireChangeable(final Broadcast broadcast, final long expectedVersion) {
        if (broadcast.getStatus() == BroadcastStatus.ENDED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "종료된 방송의 상품 편성은 변경할 수 없습니다.");
        }
        if (broadcast.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.CONFLICT, "방송이 변경되었습니다. 다시 조회하세요.");
        }
    }

    /** Shopping 존재 확인 + Commerce 판매정보. 미존재는 404, 인프라 장애는 503. */
    private SalesSnapshot lookup(final long productId) {
        try {
            productClient.get(productId);
            return salesClient.get(productId);
        } catch (ProductLookupException e) {
            if (e.reason() == ProductLookupException.Reason.NOT_FOUND) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                    "상품 또는 판매정보를 찾을 수 없습니다.");
            }
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "상품 정보를 조회할 수 없습니다.");
        }
    }
}
