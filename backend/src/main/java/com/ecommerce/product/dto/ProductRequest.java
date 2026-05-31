package com.ecommerce.product.dto;

import com.ecommerce.product.ProductStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

// 상품 생성/수정 요청
public record ProductRequest(
        @NotNull Long supplierId,
        @NotBlank String name,
        String description,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero int stockQuantity,
        @NotNull ProductStatus status
) {
}
