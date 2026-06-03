package com.ecommerce.auth;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 어드민 계정 — 비밀번호는 BCrypt 해시로 저장
@Entity
@Table(name = "admins")
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그인 아이디 — 중복 불가
    @Column(nullable = false, unique = true)
    private String username;

    // BCrypt 해시된 비밀번호 (평문 저장 금지)
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected Admin() {
    }

    public Admin(String username, String encodedPassword) {
        this.username = username;
        this.password = encodedPassword;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
