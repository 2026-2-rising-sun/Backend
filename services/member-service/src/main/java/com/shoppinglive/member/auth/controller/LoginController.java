package com.shoppinglive.member.auth.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import com.shoppinglive.member.auth.dto.LoginRequest;
import com.shoppinglive.member.auth.dto.TokenPair;
import com.shoppinglive.member.auth.service.MemberLoginService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {
    private final MemberLoginService login;

    public LoginController(MemberLoginService login) {
        this.login = login;
    }

    @Operation(
            summary = "이메일 로그인 및 토큰 발급",
            description =
                    "이메일 정규화 후 인증한다. 없는 계정·틀린 비밀번호·잠긴 계정은 모두 같은 401. 쿠키를 사용하지 않고 응답 JSON으로 토큰을 돌려준다. 연속 실패 제한과 잠금 기간은 서버 설정이다.")
    @PostMapping(value = "/v1/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST, "password는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
        return ApiResponse.ok(login.login(request.email(), request.password()));
    }
}
