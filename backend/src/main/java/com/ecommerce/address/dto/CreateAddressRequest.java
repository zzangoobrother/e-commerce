package com.ecommerce.address.dto;

import jakarta.validation.constraints.NotBlank;

// 배송지 등록 요청 — address2(상세주소)만 선택 입력
public record CreateAddressRequest(
        @NotBlank String label,
        @NotBlank String recipientName,
        @NotBlank String phone,
        @NotBlank String zipCode,
        @NotBlank String address1,
        String address2,
        boolean isDefault) {
}
