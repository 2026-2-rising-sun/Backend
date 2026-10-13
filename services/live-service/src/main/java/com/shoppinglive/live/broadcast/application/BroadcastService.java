package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.api.BroadcastPatchInput;
import com.shoppinglive.live.broadcast.api.BroadcastResponse;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BroadcastService {
    private static final int MAX_PAGE_SIZE = 100;

    private final BroadcastRepository repository;
    private final BroadcastEventPublisher events;
    private final TransactionTemplate transaction;

    public BroadcastService(final BroadcastRepository repository,
                            final BroadcastEventPublisher events,
                            final PlatformTransactionManager manager) {
        this.repository = repository;
        this.events = events;
        this.transaction = new TransactionTemplate(manager);
    }

    public Broadcast register(final String key, final BroadcastInput input) {
        final String fingerprint = fingerprint(input);
        final Broadcast existing = repository.findByRequestKey(key).orElse(null);
        if (existing != null) {
            return replay(existing, fingerprint);
        }
        try {
            return transaction.execute(status -> repository.saveAndFlush(
                new Broadcast(key, fingerprint, input.title(), input.scheduledAt(),
                    input.channelArn(), input.playbackUrl())
            ));
        } catch (DataIntegrityViolationException exception) {
            return repository.findByRequestKey(key)
                .map(found -> replay(found, fingerprint))
                .orElseThrow(() -> exception);
        }
    }

    @Transactional
    public Broadcast edit(final long id, final long version, final BroadcastPatchInput input) {
        final Broadcast broadcast = get(id);
        if (input.channelArn() == null ^ input.playbackUrl() == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "channelArn과 playbackUrl은 함께 변경해야 합니다.");
        }
        broadcast.edit(version,
            patched("title", input.title(), broadcast.getTitle()),
            patched("scheduledAt", input.scheduledAt(), broadcast.getScheduledAt()),
            patched("channelArn", input.channelArn(), broadcast.getChannelArn()),
            patched("playbackUrl", input.playbackUrl(), broadcast.getPlaybackUrl()));
        repository.flush();
        return broadcast;
    }

    /** 생략(null)은 기존 값 유지, 명시적 null(Optional.empty)은 거절. */
    private static <T> T patched(final String field, final java.util.Optional<T> given,
                                 final T current) {
        if (given == null) {
            return current;
        }
        return given.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
            field + "에 null을 지정할 수 없습니다."));
    }

    /**
     * 관리 목록. 외부(Shopping/Commerce/IVS) 호출 없이 방송 기본정보만 읽는다.
     * 정렬은 repository 쿼리가 상태별로 고정하므로 호출자가 바꿀 수 없다.
     */
    @Transactional(readOnly = true)
    public Page<BroadcastResponse> list(final BroadcastStatus status, final int page,
                                        final int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "page는 0 이상, size는 1 이상이어야 합니다.");
        }
        final Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        final Page<Broadcast> found = switch (status) {
            case null -> repository.findAllOrderByCreatedAtDesc(pageable);
            case PREPARING -> repository.findAllPreparing(pageable);
            case LIVE -> repository.findAllLive(pageable);
            case ENDED -> repository.findAllEnded(pageable);
        };
        return found.map(BroadcastResponse::from);
    }

    /** 종료는 짧은 트랜잭션의 원자적 상태 전이다. 외부 호출을 하지 않고 이벤트는 outbox 에 기록한다. */
    @Transactional
    public Broadcast end(final long id) {
        final Broadcast broadcast = repository.findForLikeUpdate(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // DB timestamp 컬럼은 마이크로초 정밀도라, 저장 전에 맞춰 잘라낸다(#65 start()와 동일 이유).
        final boolean transitioned =
            broadcast.end(java.time.Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.flush();
        // SSE 종료 신호(BroadcastEndNotifier)와 달리 다른 서비스로 가는 이벤트는 유실되면 안 되므로
        // 같은 트랜잭션의 outbox 에 남긴다. 반복 종료는 전이가 없어 기록하지 않는다.
        if (transitioned) {
            events.ended(broadcast);
        }
        return broadcast;
    }

    @Transactional(readOnly = true)
    public Broadcast get(final long id) {
        return repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Broadcast replay(final Broadcast broadcast, final String fingerprint) {
        if (!broadcast.getFingerprint().equals(fingerprint)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "같은 등록 키에 다른 요청을 사용할 수 없습니다.");
        }
        return broadcast;
    }

    private String fingerprint(final BroadcastInput input) {
        try {
            final String normalized = input.title() + "|" + input.scheduledAt() + "|" +
                input.channelArn() + "|" + input.playbackUrl();
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
