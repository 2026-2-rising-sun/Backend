package com.shoppinglive.member.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
                           @NotBlank @Size(max = 72) String password) {
    public LoginRequest { email = email == null ? null : email.strip(); }
    @Override public String toString() { return "LoginRequest[REDACTED]"; }
}
