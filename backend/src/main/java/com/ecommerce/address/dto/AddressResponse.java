package com.ecommerce.address.dto;

import com.ecommerce.address.CustomerAddress;

import java.time.LocalDateTime;

public record AddressResponse(
        Long id,
        String label,
        String recipientName,
        String phone,
        String zipCode,
        String address1,
        String address2,
        boolean isDefault,
        LocalDateTime createdAt) {

    public static AddressResponse from(CustomerAddress a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getRecipientName(),
                a.getPhone(), a.getZipCode(), a.getAddress1(), a.getAddress2(),
                a.isDefault(), a.getCreatedAt());
    }
}
