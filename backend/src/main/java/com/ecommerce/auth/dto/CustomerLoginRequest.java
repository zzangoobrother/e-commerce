package com.ecommerce.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 고객 로그인 요청 — 이메일 식별자
public record CustomerLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
