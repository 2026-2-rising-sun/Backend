package com.shoppinglive.member.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "비어 있을 수 없습니다.")
                @Email(message = "올바른 이메일 주소 형식이어야 합니다.")
                @Size(max = 254, message = "크기는 {min}~{max} 범위여야 합니다.")
                String email,
        @NotBlank(message = "비어 있을 수 없습니다.") @Size(max = 72, message = "크기는 {min}~{max} 범위여야 합니다.")
                String password) {
    public LoginRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "LoginRequest[REDACTED]";
    }
}
