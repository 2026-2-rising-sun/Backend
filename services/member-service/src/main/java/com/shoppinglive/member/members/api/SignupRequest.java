package com.shoppinglive.member.members.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(@NotBlank @Email @Size(max = 254) String email,
                            @NotBlank @Size(min = 8, max = 72) String password,
                            @NotBlank @Size(max = 80) String displayName) {
    public SignupRequest {
        email = email == null ? null : email.strip();
        displayName = displayName == null ? null : displayName.strip();
    }

    @Override public String toString() { return "SignupRequest[REDACTED]"; }
}
