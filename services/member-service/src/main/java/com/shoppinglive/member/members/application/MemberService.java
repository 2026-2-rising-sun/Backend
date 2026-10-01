package com.shoppinglive.member.members.application;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.members.domain.Member;
import com.shoppinglive.member.members.infrastructure.MemberRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {
    private final MemberRepository members;
    private final PasswordEncoder passwords;

    public MemberService(MemberRepository members, PasswordEncoder passwords) {
        this.members = members;
        this.passwords = passwords;
    }

    @Transactional
    public MemberProfile register(String email, String password, String displayName) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "password는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        String normalizedEmail = Member.normalizeEmail(email);
        if (members.existsByEmail(normalizedEmail)) throw duplicateEmail();
        try {
            return MemberProfile.from(members.saveAndFlush(Member.register(normalizedEmail, passwords.encode(password), displayName)));
        } catch (DataIntegrityViolationException exception) {
            // 동시 가입은 사전 조회를 모두 통과할 수 있으므로 최종 DB unique 제약으로 막는다.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                    && "uk_members_email".equals(constraint.getConstraintName())) throw duplicateEmail();
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public MemberProfile profile(UUID memberId) { return MemberProfile.from(requireMember(memberId)); }

    @Transactional
    public MemberProfile updateProfile(UUID memberId, String displayName) {
        Member member = members.findForUpdate(memberId).filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
        member.changeDisplayName(displayName);
        return MemberProfile.from(member);
    }

    private Member requireMember(UUID id) {
        return members.findById(id).filter(found -> !found.isWithdrawn())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
    }

    private BusinessException duplicateEmail() {
        return new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 이메일입니다.");
    }
}
