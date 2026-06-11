package com.ecommerce.cart.dto;

import jakarta.validation.constraints.Min;

// 수량 변경 요청 — 절대값으로 설정
public record UpdateCartItemRequest(
        @Min(1) int quantity
) {
}
