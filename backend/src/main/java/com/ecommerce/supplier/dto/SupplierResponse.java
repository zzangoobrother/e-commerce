package com.ecommerce.supplier.dto;

import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierStatus;
import java.time.LocalDateTime;

// 공급사 응답
public record SupplierResponse(
        Long id,
        String name,
        String contactEmail,
        SupplierStatus status,
        LocalDateTime createdAt
) {
    public static SupplierResponse from(Supplier s) {
        return new SupplierResponse(
                s.getId(), s.getName(), s.getContactEmail(),
                s.getStatus(), s.getCreatedAt());
    }
}
