package com.ecommerce.payment;

import com.ecommerce.common.BadRequestException;
import com.ecommerce.payment.PaymentGateway.Approval;
import com.ecommerce.payment.dto.CardPaymentRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentGatewayTest {

    private final MockPaymentGateway gateway = new MockPaymentGateway();

    private CardPaymentRequest card(String number) {
        return new CardPaymentRequest(number, 12, 2999, "123", "HONG GILDONG");
    }

    @Test
    void 승인_카드는_brand와_last4와_승인번호를_반환한다() {
        Approval approval = gateway.approve(card("4242424242424242"), new BigDecimal("6000"));
        assertThat(approval.cardBrand()).isEqualTo("VISA");
        assertThat(approval.cardLast4()).isEqualTo("4242");
        assertThat(approval.approvalNo()).startsWith("MOCK-");
    }

    @Test
    void 마스터카드_prefix는_MASTERCARD로_판정한다() {
        Approval approval = gateway.approve(card("5555555555554444"), new BigDecimal("1000"));
        assertThat(approval.cardBrand()).isEqualTo("MASTERCARD");
    }

    @Test
    void 끝자리_0002_카드는_거절된다() {
        assertThatThrownBy(() -> gateway.approve(card("4000000000000002"), new BigDecimal("1000")))
                .isInstanceOf(PaymentDeclinedException.class);
    }

    @Test
    void Luhn_불통과_카드는_형식오류다() {
        assertThatThrownBy(() -> gateway.approve(card("4242424242424241"), new BigDecimal("1000")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 만료된_카드는_형식오류다() {
        CardPaymentRequest expired = new CardPaymentRequest("4242424242424242", 1, 2000, "123", "HONG");
        assertThatThrownBy(() -> gateway.approve(expired, new BigDecimal("1000")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void CVC_형식_위반은_형식오류다() {
        CardPaymentRequest badCvc = new CardPaymentRequest("4242424242424242", 12, 2999, "12", "HONG");
        assertThatThrownBy(() -> gateway.approve(badCvc, new BigDecimal("1000")))
                .isInstanceOf(BadRequestException.class);
    }
}
