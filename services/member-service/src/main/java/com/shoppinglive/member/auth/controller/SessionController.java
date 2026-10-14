package com.shoppinglive.member.auth.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.common.security.AccessSessionVerifier;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.member.auth.dto.TokenPair;
import com.shoppinglive.member.auth.service.MemberSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
        this.sessions = sessions;
        this.verifier = verifier;
    }

    @Operation(
            summary = "Refresh 교체 및 access 재발급",
            description =
                    "Access 없이 refresh JSON으로 호출한다. 사용한 refresh는 재사용할 수 없다. 알려진 사용 완료 토큰의 재사용은 해당 family를 보안 폐기한 뒤 401을 반환한다. 만료·미지·폐기된 토큰도 401. 교체해도 최초 로그인 +30일의 절대 만료를 유지한다.")
    @PostMapping(value = "/v1/auth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(sessions.refresh(request.refreshToken()));
    }

    @Operation(
            summary = "현재 family refresh 로그아웃",
            description =
                    "Access 없이 refresh JSON으로 호출한다. 해당 family의 refresh만 폐기하고 기존 access는 만료까지 허용한다. 미지·이미 만료·폐기된 토큰도 같은 204로 처리한다. 알려진 refresh 재사용 보안 폐기와 구분한다.")
    @PostMapping(value = "/v1/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        sessions.logout(request.refreshToken());
    }

    @Operation(
            summary = "비밀번호 확인 후 본인 탈퇴",
            description =
                    "회원 상태를 탈퇴로 바꾸고 모든 family를 보안 폐기한다. 이후 신규 인증 검사는 기존 access도 401로 거부한다. 물리 삭제·타 도메인 주문 삭제가 아니며 같은 이메일 재가입은 409로 거부한다. 비밀번호 불일치 401.")
    @SecurityRequirement(name = "BearerAuth")
    @DeleteMapping(value = "/v1/members/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody WithdrawRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "password는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        sessions.withdraw(UUID.fromString(user.memberId()), request.password());
    }

    @Operation(
            summary = "판매자 본인의 모든 기존 세션 폐기",
            description =
                    "SELLER 본인의 모든 기존 family를 보안 폐기한다. 다른 회원 id는 403이며 존재 여부를 노출하지 않는다. 이후 새 로그인은 가능하다.")
    @SecurityRequirement(name = "BearerAuth")
    @PostMapping("/v1/admin/members/{id}/sessions/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        if (!id.toString().equals(user.memberId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        sessions.revokeAll(id);
    }

    @Operation(
            summary = "내부 세션 활성 상태 확인",
            description =
                    "Shopping/Commerce/Live caller만 허용한다. memberId와 sessionId가 일치하고 회원·세션이 유효하면 active=true. 미지·불일치·보안 폐기·탈퇴는 false. 일반 로그아웃은 기존 access를 즉시 차단하지 않는다. 이 응답은 캐시하지 않는다.")
    @SecurityRequirement(name = "ServiceToken")
    @PostMapping(
            value = "/v1/internal/auth/sessions/check",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SessionStatus> check(@Valid @RequestBody SessionCheckRequest request) {
        return ApiResponse.ok(
                new SessionStatus(verifier.isActive(request.memberId(), request.sessionId())));
    }

    public record RefreshRequest(
            @NotBlank(message = "비어 있을 수 없습니다.")
                    @Size(max = 512, message = "크기는 {min}~{max} 범위여야 합니다.")
                    String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[REDACTED]";
        }
    }

    public record WithdrawRequest(
            @NotBlank(message = "비어 있을 수 없습니다.")
                    @Size(max = 72, message = "크기는 {min}~{max} 범위여야 합니다.")
                    String password) {
        @Override
        public String toString() {
            return "WithdrawRequest[REDACTED]";
        }
    }

    public record SessionCheckRequest(
            @NotNull(message = "필수 값입니다.") UUID memberId,
            @NotNull(message = "필수 값입니다.") UUID sessionId) {}

    public record SessionStatus(boolean active) {}
}
