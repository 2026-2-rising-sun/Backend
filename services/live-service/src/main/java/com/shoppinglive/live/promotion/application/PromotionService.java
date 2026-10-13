package com.shoppinglive.live.promotion.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.promotion.api.PromotionInput;
import com.shoppinglive.live.promotion.domain.BroadcastPromotion;
import com.shoppinglive.live.promotion.domain.PromotionCloseReason;
import com.shoppinglive.live.promotion.domain.PromotionStatus;
import com.shoppinglive.live.promotion.infrastructure.BroadcastPromotionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PromotionService {

    private final BroadcastRepository broadcasts;
    private final BroadcastPromotionRepository promotions;
    private final PromotionEventPublisher events;
    private final PromotionNotifier notifier;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public PromotionService(final BroadcastRepository broadcasts,
                            final BroadcastPromotionRepository promotions,
                            final PromotionEventPublisher events,
                            final PromotionNotifier notifier,
                            final ObjectMapper objectMapper,
                            final PlatformTransactionManager manager) {
        this.broadcasts = broadcasts;
        this.promotions = promotions;
        this.events = events;
        this.notifier = notifier;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(manager);
    }

    /**
     * 생성은 READY 로만 끝낸다. 같은 Idempotency-Key 재요청은 fingerprint 비교로 멱등 응답.
     * 종료된 방송에는 더 못 만든다. Kafka 는 열림 전에 아무도 알 필요 없으므로 발행하지 않는다.
     */
    public BroadcastPromotion create(final long broadcastId, final String idempotencyKey,
                                     final PromotionInput input) {
        final Broadcast broadcast = broadcasts.findById(broadcastId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        if (broadcast.getStatus() == BroadcastStatus.ENDED) {
            throw new BusinessException(ErrorCode.CONFLICT, "종료된 방송에는 프로모션을 만들 수 없습니다.");
        }
        final String canonicalAttributes = canonicalize(input.attributes());
        final String fingerprint = fingerprint(input.type(), input.title(), canonicalAttributes,
            input.durationSeconds());

        final BroadcastPromotion existing = promotions.findByRequestKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return replay(existing, broadcastId, fingerprint);
        }
        try {
            return transaction.execute(status -> promotions.saveAndFlush(
                new BroadcastPromotion(broadcastId, idempotencyKey, fingerprint,
                    input.type(), input.title(), canonicalAttributes, input.durationSeconds())
            ));
        } catch (DataIntegrityViolationException exception) {
            return promotions.findByRequestKey(idempotencyKey)
                .map(found -> replay(found, broadcastId, fingerprint))
                .orElseThrow(() -> exception);
        }
    }

    /**
     * 열기. LIVE 방송에서만 성공한다. 같은 방송 행에 비관락을 잡아 종료 흐름과 충돌하지 않게 한다.
     * 실제 전이가 일어난 경우에만 Kafka outbox 에 기록하고, 커밋 뒤 SSE 를 쏜다.
     */
    @Transactional
    public BroadcastPromotion open(final long broadcastId, final long promotionId) {
        requireLive(broadcastId);
        final BroadcastPromotion promotion = find(broadcastId, promotionId);
        final boolean transitioned = promotion.open(now());
        if (transitioned) {
            promotions.flush();
            events.opened(promotion);
            notifyAfterCommit(() -> notifier.opened(promotion));
        }
        return promotion;
    }

    /** 판매자 수동 닫기. 같은 방송 비관락. 열려 있던 적이 있는 경우에만 Kafka·SSE 를 낸다. */
    @Transactional
    public BroadcastPromotion close(final long broadcastId, final long promotionId) {
        broadcasts.findForLikeUpdate(broadcastId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        final BroadcastPromotion promotion = find(broadcastId, promotionId);
        final boolean wasOpenBefore = promotion.wasOpen();
        final boolean transitioned = promotion.close(now(), PromotionCloseReason.SELLER_CLOSED);
        if (transitioned) {
            promotions.flush();
            if (wasOpenBefore) {
                events.closed(promotion);
                notifyAfterCommit(() -> notifier.closed(promotion));
            }
        }
        return promotion;
    }

    private static void notifyAfterCommit(final Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { task.run(); }
            });
        } else {
            task.run();
        }
    }

    /**
     * 방송 종료 시 모든 READY·OPEN 을 끌어 BROADCAST_ENDED 로 닫는다. 호출자 트랜잭션 안에서만
     * 돌아야 outbox 순서(모든 Closed → Ended)를 보장할 수 있다.
     * 열려 있던 것만 Kafka 를 내고, 그 id 목록을 돌려 호출자가 Ended payload 에 싣게 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> closeAllForEndedBroadcast(final long broadcastId, final Instant now) {
        final List<BroadcastPromotion> toClose = promotions.findByBroadcastIdAndStatusIn(
            broadcastId, List.of(PromotionStatus.READY, PromotionStatus.OPEN));
        if (toClose.isEmpty()) {
            return List.of();
        }
        final Instant stamp = now.truncatedTo(ChronoUnit.MICROS);
        final List<BroadcastPromotion> transitionedOpen = new ArrayList<>();
        for (BroadcastPromotion promotion : toClose) {
            final boolean wasOpenBefore = promotion.wasOpen();
            if (promotion.close(stamp, PromotionCloseReason.BROADCAST_ENDED) && wasOpenBefore) {
                transitionedOpen.add(promotion);
            }
        }
        promotions.flush();
        final List<Long> closedOpenIds = new ArrayList<>(transitionedOpen.size());
        for (BroadcastPromotion promotion : transitionedOpen) {
            events.closed(promotion);
            closedOpenIds.add(promotion.getId());
        }
        return Collections.unmodifiableList(closedOpenIds);
    }

    /**
     * 스케줄러가 호출한다. 아직 OPEN 이고 closes_at 이 지난 프로모션을 EXPIRED 로 닫는다.
     * 다른 pod 와의 레이스는 낙관적 락 충돌로 걸러지고, 걸리면 조용히 건너뛴다.
     * 커밋 뒤 SSE 가 자동으로 발행되므로 호출자는 추가 작업 없이 반환값을 로그로만 쓰면 된다.
     * 반환은 "이 호출이 전이시킨 OPEN 프로모션" 뿐 — 이미 닫혀 있었거나 READY→CLOSED 는 비어 있다.
     */
    public java.util.Optional<BroadcastPromotion> expireOne(final long promotionId) {
        return java.util.Optional.ofNullable(transaction.execute(status -> {
            final BroadcastPromotion promotion = promotions.findById(promotionId).orElse(null);
            if (promotion == null) {
                return null;
            }
            final boolean wasOpenBefore = promotion.wasOpen();
            if (!promotion.close(now(), PromotionCloseReason.EXPIRED)) {
                return null;
            }
            promotions.flush();
            if (wasOpenBefore) {
                events.closed(promotion);
                notifyAfterCommit(() -> notifier.closed(promotion));
                return promotion;
            }
            return null;
        }));
    }

    public List<Long> findExpiredCandidateIds(final Instant now) {
        return promotions.findTop100ByStatusAndClosesAtLessThanEqualOrderByClosesAtAsc(
                PromotionStatus.OPEN, now)
            .stream().map(BroadcastPromotion::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<BroadcastPromotion> list(final long broadcastId) {
        return promotions.findByBroadcastIdOrderByIdAsc(broadcastId);
    }

    @Transactional(readOnly = true)
    public List<BroadcastPromotion> listOpen(final long broadcastId) {
        return promotions.findByBroadcastIdAndStatusOrderByIdAsc(broadcastId, PromotionStatus.OPEN);
    }

    private void requireLive(final long broadcastId) {
        final Broadcast broadcast = broadcasts.findForLikeUpdate(broadcastId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        if (broadcast.getStatus() != BroadcastStatus.LIVE) {
            throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송이 아닙니다.");
        }
    }

    private BroadcastPromotion find(final long broadcastId, final long promotionId) {
        return promotions.findByIdAndBroadcastId(promotionId, broadcastId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "프로모션을 찾을 수 없습니다."));
    }

    private BroadcastPromotion replay(final BroadcastPromotion promotion, final long broadcastId,
                                      final String fingerprint) {
        if (promotion.getBroadcastId() != broadcastId) {
            // 같은 Idempotency-Key 를 다른 방송에서 재사용했다 — 멱등이 아니라 충돌이다.
            throw new BusinessException(ErrorCode.CONFLICT,
                "같은 Idempotency-Key 를 다른 방송에서 재사용할 수 없습니다.");
        }
        if (!promotion.getFingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "같은 Idempotency-Key 에 다른 요청을 사용할 수 없습니다.");
        }
        return promotion;
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * attributes 를 결정론적 JSON 으로 직렬화한다. 재요청 fingerprint 가 키 순서로 흔들리지 않게
     * {@link TreeMap} 으로 재감싼다. 중첩 Map 도 재귀적으로 정렬한다.
     */
    private String canonicalize(final Map<String, Object> attributes) {
        final Map<String, Object> normalized = attributes == null ? Map.of() : attributes;
        try {
            return objectMapper.writeValueAsString(sortRecursively(normalized));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "attributes 를 직렬화할 수 없습니다: " + exception.getOriginalMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Object sortRecursively(final Object value) {
        if (value instanceof Map<?, ?> map) {
            final Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortRecursively(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            final List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(sortRecursively(item));
            }
            return result;
        }
        return value;
    }

    private String fingerprint(final String type, final String title, final String attributesJson,
                               final Integer durationSeconds) {
        try {
            final String normalized = type + "|" + title + "|" + attributesJson + "|"
                + (durationSeconds == null ? "" : durationSeconds);
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
