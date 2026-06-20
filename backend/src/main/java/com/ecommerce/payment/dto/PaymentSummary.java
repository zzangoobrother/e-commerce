package com.ecommerce.payment.dto;

import com.ecommerce.payment.Payment;
import com.ecommerce.payment.PaymentStatus;

import java.math.BigDecimal;

// 응답용 결제 요약 — 민감정보 없이 표시에 필요한 것만(금액·상태·brand·last4·승인번호)
public record PaymentSummary(BigDecimal amount, PaymentStatus status,
                             String cardBrand, String cardLast4, String approvalNo) {

    public static PaymentSummary from(Payment payment) {
        return new PaymentSummary(payment.getAmount(), payment.getStatus(),
                payment.getCardBrand(), payment.getCardLast4(), payment.getApprovalNo());
    }
}
