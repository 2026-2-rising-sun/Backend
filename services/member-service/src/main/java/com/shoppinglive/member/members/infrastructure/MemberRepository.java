package com.shoppinglive.member.members.infrastructure;

import com.shoppinglive.member.members.domain.Member;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, UUID> {
    Optional<Member> findByEmail(String email);
    boolean existsByEmail(String email);
}
