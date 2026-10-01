package com.shoppinglive.member.members.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.member.members.application.MemberProfile;
import com.shoppinglive.member.members.application.MemberService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberController {
    private final MemberService members;
    public MemberController(MemberService members) { this.members = members; }

    @PostMapping(value = "/v1/auth/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MemberProfile> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(members.register(request.email(), request.password(), request.displayName()));
    }

    @GetMapping("/v1/members/me")
    public ApiResponse<MemberProfile> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(members.profile(UUID.fromString(user.memberId())));
    }

    @PatchMapping(value = "/v1/members/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MemberProfile> update(@AuthenticationPrincipal AuthenticatedUser user,
                                           @Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.ok(members.updateProfile(UUID.fromString(user.memberId()), request.displayName()));
    }
}
