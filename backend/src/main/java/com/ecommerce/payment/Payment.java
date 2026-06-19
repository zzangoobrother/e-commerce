package com.ecommerce.payment;

import com.ecommerce.common.BadRequestException;
import com.ecommerce.payment.PaymentGateway.Approval;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 결제 — 주문과 1:1. orderId는 스칼라(애그리거트 간 FK 회피, Order가 customerId를 스칼라로 갖는 것과 동일).
// 카드 전체번호·CVC는 저장하지 않는다 — last4·brand만.
@Entity
@Table(name = "payments",
        uniqueConstraints = @UniqueConstraint(name = "uk_payments_order", columnNames = "order_id"))
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(nullable = false)
    private String cardBrand;

    @Column(nullable = false, length = 4)
    private String cardLast4;

    @Column(nullable = false)
    private String approvalNo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime paidAt;

    private LocalDateTime refundedAt;

    protected Payment() {
    }

    private Payment(Long orderId, BigDecimal amount, Approval approval) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = PaymentStatus.PAID;
        this.cardBrand = approval.cardBrand();
        this.cardLast4 = approval.cardLast4();
        this.approvalNo = approval.approvalNo();
    }

    // 승인 결과로 PAID 결제 생성
    public static Payment of(Long orderId, BigDecimal amount, Approval approval) {
        return new Payment(orderId, amount, approval);
    }

    @PrePersist
    void onCreate() {
        this.paidAt = LocalDateTime.now();
    }

    // 환불 — PAID일 때만 REFUNDED로. 이미 환불/미결제면 거부.
    public void refund() {
        if (status != PaymentStatus.PAID) {
            throw new BadRequestException("이미 환불되었거나 결제되지 않은 주문입니다.");
        }
        this.status = PaymentStatus.REFUNDED;
        this.refundedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getCardBrand() { return cardBrand; }
    public String getCardLast4() { return cardLast4; }
    public String getApprovalNo() { return approvalNo; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public LocalDateTime getRefundedAt() { return refundedAt; }
}
