package com.shoppinglive.member.auth.api;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AccessSessionVerifier;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.member.auth.application.MemberSessionService;
import com.shoppinglive.member.auth.application.TokenPair;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final MemberSessionService sessions;
    private final AccessSessionVerifier verifier;
    public SessionController(MemberSessionService sessions, AccessSessionVerifier verifier) {
        this.sessions = sessions; this.verifier = verifier;
    }

    @PostMapping(value = "/v1/auth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(sessions.refresh(request.refreshToken()));
    }

    @PostMapping(value = "/v1/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) { sessions.logout(request.refreshToken()); }

    @DeleteMapping(value = "/v1/members/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody WithdrawRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "password는 UTF-8 기준 72바이트 이하여야 합니다.");
        sessions.withdraw(UUID.fromString(user.memberId()), request.password());
    }

    @PostMapping("/v1/admin/members/{id}/sessions/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) { sessions.revokeAll(id); }

    @PostMapping(value = "/v1/internal/auth/sessions/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SessionStatus> check(@Valid @RequestBody SessionCheckRequest request) {
        return ApiResponse.ok(new SessionStatus(verifier.isActive(request.memberId(), request.sessionId())));
    }

    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
        @Override public String toString() { return "RefreshRequest[REDACTED]"; }
    }
    public record WithdrawRequest(@NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "WithdrawRequest[REDACTED]"; }
    }
    public record SessionCheckRequest(@NotNull UUID memberId, @NotNull UUID sessionId) { }
    public record SessionStatus(boolean active) { }
}
