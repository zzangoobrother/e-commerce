package com.ecommerce.payment;

import com.ecommerce.common.BadRequestException;
import com.ecommerce.payment.dto.CardPaymentRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// 모의 PG — 테스트 카드번호로 승인/거절 판정. 실 PG 도입 시 이 구현만 교체한다.
// 규약: 끝 4자리 0002 = 거절(한도초과), 그 외 Luhn 통과 카드 = 승인.
// Luhn 실패·만료일 과거·CVC 형식 위반 = 형식 오류(400).
@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public Approval approve(CardPaymentRequest card, BigDecimal amount) {
        String number = card.cardNumber().replaceAll("\\s", "");
        validateFormat(number, card);
        if (number.endsWith("0002")) {
            throw new PaymentDeclinedException("카드가 거절되었습니다. (한도 초과)");
        }
        return new Approval(brandOf(number), number.substring(number.length() - 4),
                "MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    @Override
    public void refund(Payment payment) {
        // 모의 — 항상 성공. 실 PG 도입 시 승인번호로 실제 환불 호출.
    }

    // 카드 형식 1차 검증 — 길이·Luhn·CVC·만료일
    private void validateFormat(String number, CardPaymentRequest card) {
        if (!number.matches("\\d{13,19}") || !luhnValid(number)) {
            throw new BadRequestException("유효하지 않은 카드 번호입니다.");
        }
        if (card.cvc() == null || !card.cvc().matches("\\d{3}")) {
            throw new BadRequestException("유효하지 않은 CVC입니다.");
        }
        // 만료 말일(해당 월 마지막 날) 이 오늘 이전이면 만료
        LocalDate expiry = LocalDate.of(card.expiryYear(), card.expiryMonth(), 1)
                .plusMonths(1).minusDays(1);
        if (expiry.isBefore(LocalDate.now())) {
            throw new BadRequestException("만료된 카드입니다.");
        }
    }

    // Luhn 체크섬 — 카드번호 유효성 검증
    private boolean luhnValid(String number) {
        int sum = 0;
        boolean alternate = false;
        for (int i = number.length() - 1; i >= 0; i--) {
            int digit = number.charAt(i) - '0';
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    // 카드번호 prefix로 brand 판정 (4→VISA, 5→MASTERCARD, 그 외→CARD)
    private String brandOf(String number) {
        return switch (number.charAt(0)) {
            case '4' -> "VISA";
            case '5' -> "MASTERCARD";
            default -> "CARD";
        };
    }
}
