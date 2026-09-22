package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.api.BroadcastInput;
import com.shoppinglive.live.broadcast.api.BroadcastPatchInput;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BroadcastService {
    private final BroadcastRepository repository;
    private final TransactionTemplate transaction;

    public BroadcastService(final BroadcastRepository repository,
                            final PlatformTransactionManager manager) {
        this.repository = repository;
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
