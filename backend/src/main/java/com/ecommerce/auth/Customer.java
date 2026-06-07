package com.ecommerce.auth;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 스토어 고객 계정 — 비밀번호는 BCrypt 해시로 저장. 로그인 식별자는 email.
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그인 식별자 — 중복 불가
    @Column(nullable = false, unique = true)
    private String email;

    // BCrypt 해시된 비밀번호 (평문 저장 금지)
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected Customer() {
    }

    public Customer(String email, String encodedPassword) {
        this.email = email;
        this.password = encodedPassword;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
