package com.ecommerce.supplier.dto;

import com.ecommerce.supplier.SupplierStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 공급사 생성/수정 요청
public record SupplierRequest(
        @NotBlank String name,
        String contactEmail,
        @NotNull SupplierStatus status
) {
}
