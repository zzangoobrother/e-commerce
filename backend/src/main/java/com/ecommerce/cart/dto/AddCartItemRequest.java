package com.ecommerce.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 담기 요청 — 같은 상품이 이미 있으면 수량 가산
public record AddCartItemRequest(
        @NotNull Long productId,
        @Min(1) int quantity
) {
}
