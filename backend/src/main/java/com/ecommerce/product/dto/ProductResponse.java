package com.ecommerce.product.dto;

import com.ecommerce.product.Product;
import com.ecommerce.product.ProductStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 상품 응답 (공급사 정보 평탄화)
public record ProductResponse(
        Long id,
        Long supplierId,
        String supplierName,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        ProductStatus status,
        LocalDateTime createdAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(),
                p.getSupplier().getId(),
                p.getSupplier().getName(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getStockQuantity(),
                p.getStatus(),
                p.getCreatedAt());
    }
}
