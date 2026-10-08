package com.shoppinglive.live.like.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.live.broadcast.domain.BroadcastStatus;
import com.shoppinglive.live.broadcast.infrastructure.BroadcastRepository;
import com.shoppinglive.live.like.api.LikeTotalResponse;
import com.shoppinglive.live.like.api.MemberLikeResponse;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL owns member state, totals and request results in one transaction. */
@Service
public class LikeService {
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    public LikeService(BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public LikeTotalResponse total(long id) {
        final List<LikeTotalResponse> rows = totals(List.of(id));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다.");
        return rows.getFirst();
    }

    public List<LikeTotalResponse> totals(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        try {
            return jdbc.query("SELECT b.id, COALESCE(a.total, 0), COALESCE(a.version, 0) FROM broadcast b "
                + "LEFT JOIN broadcast_like_aggregate a ON a.broadcast_id = b.id WHERE b.id IN ("
                + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                (rs, n) -> new LikeTotalResponse(rs.getLong(1), rs.getLong(2), rs.getLong(3)), ids.toArray());
        } catch (DataAccessException e) { throw unavailable(); }
    }

    @Transactional(readOnly = true)
    public MemberLikeResponse mine(long id, String member) {
        try {
            final List<MemberLikeResponse> rows = jdbc.query("""
                SELECT b.id, COALESCE(m.liked, false), COALESCE(m.state_version, 0),
                       COALESCE(a.total, 0), COALESCE(a.version, 0)
                FROM broadcast b LEFT JOIN broadcast_like_aggregate a ON a.broadcast_id = b.id
                LEFT JOIN broadcast_member_like m ON m.broadcast_id = b.id AND m.member_id = ?
                WHERE b.id = ?
                """, (rs, n) -> new MemberLikeResponse(rs.getLong(1), rs.getBoolean(2), rs.getLong(3),
                    rs.getLong(4), rs.getLong(5)), member, id);
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다.");
            return rows.getFirst();
        } catch (DataAccessException e) { throw unavailable(); }
    }

    @Transactional
    public MemberLikeResponse set(long id, String member, String key, boolean desired) {
        final String request = requestKey(key);
        try {
            // end() uses the same row lock: a committed end always rejects a new request.
            final var broadcast = broadcasts.findForLikeUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "방송을 찾을 수 없습니다."));
            final List<MemberLikeResponse> replay = jdbc.query("""
                SELECT desired, liked, state_version, total, version FROM broadcast_like_request
                WHERE broadcast_id = ? AND member_id = ? AND request_id = ?
                """, (rs, n) -> {
                    if (rs.getBoolean(1) != desired) throw new BusinessException(ErrorCode.CONFLICT,
                        "같은 요청 키에 다른 좋아요 상태를 사용할 수 없습니다.");
                    return new MemberLikeResponse(id, rs.getBoolean(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
                }, id, member, request);
            if (!replay.isEmpty()) return replay.getFirst();
            if (broadcast.getStatus() != BroadcastStatus.LIVE)
                throw new BusinessException(ErrorCode.CONFLICT, "진행 중인 방송이 아닙니다.");

            final MemberLikeResponse before = mine(id, member);
            final boolean changed = before.liked() != desired;
            final long stateVersion = before.stateVersion() + (changed ? 1 : 0);
            final long total = before.total() + (changed ? (desired ? 1 : -1) : 0);
            final long version = before.version() + (changed ? 1 : 0);
            if (changed) {
                if (jdbc.update("UPDATE broadcast_like_aggregate SET total = ?, version = ? WHERE broadcast_id = ?",
                    total, version, id) == 0)
                    jdbc.update("INSERT INTO broadcast_like_aggregate (broadcast_id, total, version) VALUES (?, ?, ?)",
                        id, total, version);
                if (jdbc.update("UPDATE broadcast_member_like SET liked = ?, state_version = ? "
                    + "WHERE broadcast_id = ? AND member_id = ?", desired, stateVersion, id, member) == 0)
                    jdbc.update("INSERT INTO broadcast_member_like (broadcast_id, member_id, liked, state_version) "
                        + "VALUES (?, ?, ?, ?)", id, member, desired, stateVersion);
            }
            jdbc.update("""
                INSERT INTO broadcast_like_request
                    (broadcast_id, member_id, request_id, desired, liked, state_version, total, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, member, request, desired, desired, stateVersion, total, version);
            return new MemberLikeResponse(id, desired, stateVersion, total, version);
        } catch (DataAccessException e) { throw unavailable(); }
    }

    private String requestKey(String key) {
        try {
            final UUID parsed = UUID.fromString(key);
            if (!parsed.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
            return parsed.toString();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 UUID여야 합니다.");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "좋아요 저장소를 사용할 수 없습니다.");
    }
}
