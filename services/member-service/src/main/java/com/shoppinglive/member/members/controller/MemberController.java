package com.shoppinglive.member.members.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.member.members.dto.MemberProfile;
import com.shoppinglive.member.members.dto.ProfileUpdateRequest;
import com.shoppinglive.member.members.dto.SignupRequest;
import com.shoppinglive.member.members.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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

    public MemberController(MemberService members) {
        this.members = members;
    }

    @Operation(summary = "이메일 회원가입")
    @PostMapping(value = "/v1/auth/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MemberProfile> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.ok(
                members.register(request.email(), request.password(), request.displayName()));
    }

    @Operation(summary = "본인 회원정보 조회")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/v1/members/me")
    public ApiResponse<MemberProfile> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(members.profile(UUID.fromString(user.memberId())));
    }

    @Operation(summary = "본인 표시 이름 수정")
    @SecurityRequirement(name = "BearerAuth")
    @PatchMapping(value = "/v1/members/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<MemberProfile> update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ProfileUpdateRequest request) {
        return ApiResponse.ok(
                members.updateProfile(UUID.fromString(user.memberId()), request.displayName()));
    }
}
