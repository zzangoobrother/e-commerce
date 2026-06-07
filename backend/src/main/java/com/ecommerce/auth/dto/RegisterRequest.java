package com.ecommerce.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 회원가입 요청 — 이메일 형식·필수 검증
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
