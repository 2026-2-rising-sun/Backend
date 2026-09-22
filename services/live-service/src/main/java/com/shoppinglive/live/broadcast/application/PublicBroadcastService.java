package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.api.PublicBroadcastResponse;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 목록은 LIVE → PREPARING → ENDED 그룹 순서이고 각 그룹 안에서는
 * #60 이 정의한 상태별 정렬을 그대로 쓴다. 외부 호출은 하지 않는다.
 */
@Service
public class PublicBroadcastService {
    private static final int MAX_PAGE_SIZE = 100;
    // ponytail: 그룹 결합 순서를 DB 한 번에 표현하려면 union 쿼리가 필요하다.
    // P1 방송 수(운영 화면 기준 수백 건)에서는 그룹별 페이지를 이어 붙이는 편이 단순하다.
    // 방송 수가 이 상한을 넘으면 정렬 키를 가진 단일 네이티브 union 쿼리로 바꾼다.
    private static final int GROUP_SCAN_LIMIT = 1000;

    private final BroadcastRepository repository;

    public PublicBroadcastService(final BroadcastRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<PublicBroadcastResponse> list(final int page, final int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "page는 0 이상, size는 1 이상이어야 합니다.");
        }
        final Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        final Pageable scan = PageRequest.ofSize(GROUP_SCAN_LIMIT);

        final List<PublicBroadcastResponse> ordered = new ArrayList<>();
        for (final Page<Broadcast> group : List.of(repository.findAllLive(scan),
            repository.findAllPreparing(scan), repository.findAllEnded(scan))) {
            group.forEach(broadcast -> ordered.add(PublicBroadcastResponse.from(broadcast)));
        }

        final int from = Math.min((int) pageable.getOffset(), ordered.size());
        final int to = Math.min(from + pageable.getPageSize(), ordered.size());
        return new PageImpl<>(ordered.subList(from, to), pageable, ordered.size());
    }

    @Transactional(readOnly = true)
    public PublicBroadcastResponse get(final long id) {
        final Broadcast broadcast = repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        return PublicBroadcastResponse.from(broadcast);
    }
}
