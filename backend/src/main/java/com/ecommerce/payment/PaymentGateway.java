package com.ecommerce.payment;

import com.ecommerce.payment.dto.CardPaymentRequest;

import java.math.BigDecimal;

// 결제 게이트웨이 추상화 — 모의/실 PG 교체 seam. OrderService는 이 인터페이스에만 의존한다.
public interface PaymentGateway {

    // 카드 승인 — 성공 시 승인 정보 반환.
    // 형식 오류는 BadRequestException(400), 거절은 PaymentDeclinedException(402)을 던진다.
    Approval approve(CardPaymentRequest card, BigDecimal amount);

    // 환불 — 취소 시 호출(모의는 항상 성공).
    void refund(Payment payment);

    // 승인 결과 — 표시·기록용(민감정보 제외)
    record Approval(String cardBrand, String cardLast4, String approvalNo) {
    }
}
