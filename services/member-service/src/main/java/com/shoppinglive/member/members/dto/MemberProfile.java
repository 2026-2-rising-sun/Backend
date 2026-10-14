package com.shoppinglive.member.members.dto;

import com.shoppinglive.member.members.entity.Member;
import java.util.Set;

public record MemberProfile(String memberId, String email, String displayName, Set<String> roles) {
    public static MemberProfile from(Member member) {
        return new MemberProfile(
                member.getId().toString(),
                member.getEmail(),
                member.getDisplayName(),
                member.roles());
    }
}
