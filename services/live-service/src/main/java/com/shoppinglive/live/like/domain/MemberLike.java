package com.shoppinglive.live.like.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "broadcast_member_like")
@IdClass(MemberLike.Key.class)
public class MemberLike {
    @Id
    @Column(name = "broadcast_id", nullable = false)
    private Long broadcastId;
    @Id
    @Column(name = "member_id", nullable = false, length = 36)
    private String memberId;
    @Column(name = "liked", nullable = false)
    private boolean liked;
    @Column(name = "state_version", nullable = false)
    private long stateVersion;

    protected MemberLike() {}

    public static class Key implements Serializable {
        public Long broadcastId;
        public String memberId;
        public Key() {}
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key key)) return false;
            return Objects.equals(broadcastId, key.broadcastId) && Objects.equals(memberId, key.memberId);
        }
        @Override public int hashCode() { return Objects.hash(broadcastId, memberId); }
    }
}
