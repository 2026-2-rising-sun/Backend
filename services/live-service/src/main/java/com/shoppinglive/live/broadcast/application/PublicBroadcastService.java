package com.shoppinglive.live.broadcast.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.api.PublicBroadcastResponse;
import com.shoppinglive.live.broadcast.domain.Broadcast;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.integration.ivs.IvsReadiness;
import com.shoppinglive.live.integration.ivs.IvsReadinessClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 목록은 LIVE → PREPARING → ENDED 그룹 순서이고 각 그룹 안에서는
 * #60 이 정의한 상태별 정렬을 그대로 쓴다. 외부 호출은 하지 않는다.
 *
 * <p>그룹별 전체 건수를 먼저 세고, 요청한 page/size 구간이 걸치는 그룹만
 * 해당 offset/limit 으로 조회한다. 그룹이 3개이므로 count 3회 + slice 최대 3회면 충분하며
 * 전체 방송 수와 무관하게 정확한 순서·total 을 돌려준다.
 */
@Service
public class PublicBroadcastService {
    private static final int MAX_PAGE_SIZE = 100;

    private final BroadcastRepository repository;
    private final IvsReadinessClient ivs;

    public PublicBroadcastService(final BroadcastRepository repository,
                                  final IvsReadinessClient ivs) {
        this.repository = repository;
        this.ivs = ivs;
    }

    @Transactional(readOnly = true)
    public Page<PublicBroadcastResponse> list(final int page, final int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                "page는 0 이상, size는 1 이상이어야 합니다.");
        }
        final Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));

        final List<BroadcastStatus> groups =
            List.of(BroadcastStatus.LIVE, BroadcastStatus.PREPARING, BroadcastStatus.ENDED);
        final List<BiFunction<Integer, Integer, Page<Broadcast>>> finders = List.of(
            (offset, limit) -> repository.findAllLive(new OffsetPageable(offset, limit)),
            (offset, limit) -> repository.findAllPreparing(new OffsetPageable(offset, limit)),
            (offset, limit) -> repository.findAllEnded(new OffsetPageable(offset, limit)));

        final long windowStart = pageable.getOffset();
        final long windowEnd = windowStart + pageable.getPageSize();

        final List<PublicBroadcastResponse> content = new ArrayList<>();
        long total = 0;
        long groupStart = 0;
        for (int i = 0; i < groups.size(); i++) {
            final long groupCount = repository.countByStatus(groups.get(i));
            final long groupEnd = groupStart + groupCount;
            final long from = Math.max(windowStart, groupStart);
            final long to = Math.min(windowEnd, groupEnd);
            if (to > from) {
                finders.get(i).apply((int) (from - groupStart), (int) (to - from))
                    .forEach(broadcast -> content.add(PublicBroadcastResponse.from(broadcast)));
            }
            groupStart = groupEnd;
            total += groupCount;
        }
        return new PageImpl<>(content, pageable, total);
    }

    @Transactional(readOnly = true)
    public PublicBroadcastResponse get(final long id) {
        final Broadcast broadcast = repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
        return PublicBroadcastResponse.from(broadcast, videoStatus(broadcast));
    }

    /**
     * 시청 연결. LIVE 에서만 IVS 를 조회하고, 조회가 실패해도 기본정보 응답을 실패시키지 않는다.
     * 공개 시청은 IVS → FE 직접 재생이며 Live API 는 영상 프록시가 아니다.
     */
    private IvsReadiness videoStatus(final Broadcast broadcast) {
        if (broadcast.getStatus() != BroadcastStatus.LIVE) {
            return null;
        }
        try {
            return ivs.isReady(broadcast.getChannelArn())
                ? IvsReadiness.READY : IvsReadiness.NOT_READY;
        } catch (RuntimeException e) {
            return IvsReadiness.UNAVAILABLE;
        }
    }

    /** PageRequest 는 offset 이 page*size 로 고정되므로 임의 offset 용 Pageable 을 쓴다. */
    private record OffsetPageable(int offset, int limit) implements Pageable {
        @Override
        public int getPageNumber() {
            return offset / limit;
        }

        @Override
        public int getPageSize() {
            return limit;
        }

        @Override
        public long getOffset() {
            return offset;
        }

        @Override
        public Sort getSort() {
            return Sort.unsorted();
        }

        @Override
        public Pageable next() {
            return new OffsetPageable(offset + limit, limit);
        }

        @Override
        public Pageable previousOrFirst() {
            return hasPrevious() ? new OffsetPageable(offset - limit, limit) : first();
        }

        @Override
        public Pageable first() {
            return new OffsetPageable(0, limit);
        }

        @Override
        public Pageable withPage(final int pageNumber) {
            return new OffsetPageable(pageNumber * limit, limit);
        }

        @Override
        public boolean hasPrevious() {
            return offset >= limit;
        }

        @Override
        public boolean isPaged() {
            return true;
        }
    }
}
