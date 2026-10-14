package com.shoppinglive.member.members.service;

import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.members.dto.MemberProfile;
import com.shoppinglive.member.members.entity.Member;
import com.shoppinglive.member.members.repository.MemberRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public MemberProfile register(String email, String password, String displayName) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "password는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        String normalizedEmail = Member.normalizeEmail(email);
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw duplicateEmail();
        }
        try {
            String passwordHash = passwordEncoder.encode(password);
            Member member = Member.register(normalizedEmail, passwordHash, displayName);
            Member savedMember = memberRepository.saveAndFlush(member);
            return MemberProfile.from(savedMember);
        } catch (DataIntegrityViolationException exception) {
            // 동시 가입은 사전 조회를 모두 통과할 수 있으므로 최종 DB unique 제약으로 막는다.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "uk_members_email".equals(constraint.getConstraintName())) {
                    throw duplicateEmail();
                }
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public MemberProfile profile(UUID memberId) {
        return MemberProfile.from(requireMember(memberId));
    }

    @Transactional
    public MemberProfile updateProfile(UUID memberId, String displayName) {
        Member member =
                memberRepository
                        .findForUpdate(memberId)
                        .filter(found -> !found.isWithdrawn())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
        member.changeDisplayName(displayName);
        return MemberProfile.from(member);
    }

    private Member requireMember(UUID id) {
        return memberRepository
                .findById(id)
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
    }

    private BusinessException duplicateEmail() {
        return new BusinessException(ErrorCode.CONFLICT, "이미 사용 중인 이메일입니다.");
    }
}
