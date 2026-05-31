package com.ecommerce.supplier;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
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
        this.name = name;
        this.contactEmail = contactEmail;
        this.status = SupplierStatus.ACTIVE;
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
