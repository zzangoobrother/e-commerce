package com.ecommerce.auth.dto;

import java.time.Instant;

// 인증 응답 — access(JWT)와 refresh(opaque) 토큰 및 각 만료 시각
public record TokenResponse(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt
) {
}
