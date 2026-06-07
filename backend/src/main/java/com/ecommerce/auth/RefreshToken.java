package com.ecommerce.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

// 리프레시 토큰 — opaque 토큰의 SHA-256 해시만 저장한다(평문 미저장).
// 소유자는 (ownerType, ownerId) 다형 참조 — 어드민/고객이 단일 테이블을 공유한다(FK 없음).
// 회전 시 기존 행을 revoked 처리하고 새 행을 발급한다.
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_refresh_token_owner", columnList = "ownerType,ownerId")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(OwnerType ownerType, Long ownerId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.createdAt = createdAt;
    }

    public void revoke() {
        this.revoked = true;
    }

    public Long getId() { return id; }
    public OwnerType getOwnerType() { return ownerType; }
    public Long getOwnerId() { return ownerId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public Instant getCreatedAt() { return createdAt; }
}
