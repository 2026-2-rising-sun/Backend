package com.shoppinglive.member.members.entity;

import com.shoppinglive.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "members")
public class Member extends AuditedEntity {
    @Id private UUID id;

    @Column(nullable = false, unique = true, length = 254, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Version private Long version;

    @Column(nullable = false)
    private int failedLoginAttempts;

    private Instant loginLockedUntil;
    private Instant withdrawnAt;

    protected Member() {}

    /** 공개 가입에서 역할을 전달받지 않는다. */
    public static Member register(String email, String passwordHash, String displayName) {
        var member = new Member();
        member.id = UUID.randomUUID();
        member.email = normalizeEmail(email);
        member.passwordHash = passwordHash;
        member.displayName = displayName.strip();
        member.role = Role.USER;
        return member;
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    public void changeDisplayName(String displayName) {
        this.displayName = displayName.strip();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<String> roles() {
        return Set.of(role.name());
    }

    public boolean isWithdrawn() {
        return withdrawnAt != null;
    }

    public void withdraw(Instant now) {
        if (withdrawnAt == null) {
            withdrawnAt = now;
        }
    }

    public boolean loginLocked(Instant now) {
        return loginLockedUntil != null && loginLockedUntil.isAfter(now);
    }

    public void recordLoginFailure(Instant now, int maximum, java.time.Duration lockDuration) {
        if (loginLockedUntil != null && !loginLockedUntil.isAfter(now)) {
            failedLoginAttempts = 0;
            loginLockedUntil = null;
        }
        failedLoginAttempts++;
        if (failedLoginAttempts >= maximum) {
            loginLockedUntil = now.plus(lockDuration);
        }
    }

    public void recordLoginSuccess() {
        failedLoginAttempts = 0;
        loginLockedUntil = null;
    }

    private enum Role {
        USER,
        SELLER
    }
}
