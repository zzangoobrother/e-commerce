package com.ecommerce.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 재사용 탐지 시 해당 owner의 살아있는 토큰을 일괄 폐기
    // flushAutomatically=true: 벌크 쿼리 전에 영속 엔티티 변경(revoke 등)을 먼저 flush해 유실 방지
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken r set r.revoked = true "
            + "where r.ownerType = :ownerType and r.ownerId = :ownerId and r.revoked = false")
    void revokeAllByOwner(@Param("ownerType") OwnerType ownerType, @Param("ownerId") Long ownerId);

    // lazy 정리 — 해당 owner의 만료된 행 삭제
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken r "
            + "where r.ownerType = :ownerType and r.ownerId = :ownerId and r.expiresAt < :now")
    void deleteExpiredByOwner(@Param("ownerType") OwnerType ownerType, @Param("ownerId") Long ownerId,
                              @Param("now") Instant now);
}
