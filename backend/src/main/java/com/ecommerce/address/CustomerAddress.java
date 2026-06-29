package com.ecommerce.address;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 고객 배송지(주소록) — Customer와의 관계는 스칼라 customerId로 둔다(Order 패턴).
@Entity
@Table(name = "customer_addresses")
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String zipCode;

    @Column(nullable = false)
    private String address1;

    // 상세주소는 선택 입력
    private String address2;

    @Column(nullable = false)
    private boolean isDefault;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected CustomerAddress() {
    }

    public CustomerAddress(Long customerId, String label, String recipientName,
                           String phone, String zipCode, String address1,
                           String address2, boolean isDefault) {
        this.customerId = customerId;
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.zipCode = zipCode;
        this.address1 = address1;
        this.address2 = address2;
        this.isDefault = isDefault;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // 필드 수정 — 기본배송지 여부는 여기서 바꾸지 않는다(전용 경로로 일원화)
    public void update(String label, String recipientName, String phone,
                       String zipCode, String address1, String address2) {
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.zipCode = zipCode;
        this.address1 = address1;
        this.address2 = address2;
    }

    public void markDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getLabel() { return label; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getZipCode() { return zipCode; }
    public String getAddress1() { return address1; }
    public String getAddress2() { return address2; }
    public boolean isDefault() { return isDefault; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
