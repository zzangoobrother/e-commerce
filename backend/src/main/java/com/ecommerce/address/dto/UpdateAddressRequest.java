package com.ecommerce.address.dto;

import jakarta.validation.constraints.NotBlank;

// 배송지 수정 요청 — 기본배송지 여부는 전용 엔드포인트로 일원화하므로 여기에 없다
public record UpdateAddressRequest(
        @NotBlank String label,
        @NotBlank String recipientName,
        @NotBlank String phone,
        @NotBlank String zipCode,
        @NotBlank String address1,
        String address2) {
}
