package com.ecommerce.supplier;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 공급사명 — 중복 불가 (시드 멱등성의 DB 레벨 방어선)
    @Column(nullable = false, unique = true)
    private String name;

    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected Supplier() {
    }

    public Supplier(String name, String contactEmail) {
        this(name, contactEmail, SupplierStatus.ACTIVE);
    }

    // 상태를 명시해 생성 (어드민 생성 요청 등)
    public Supplier(String name, String contactEmail, SupplierStatus status) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // 도메인 수정 메서드
    public void update(String name, String contactEmail, SupplierStatus status) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getContactEmail() { return contactEmail; }
    public SupplierStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
