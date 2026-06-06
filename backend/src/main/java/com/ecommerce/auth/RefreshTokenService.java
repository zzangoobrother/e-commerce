package com.ecommerce.auth;

import com.ecommerce.common.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Supplier;

// 리프레시 토큰 서비스 — opaque 토큰 발급(해시 저장), 회전, 재사용 탐지, 폐기.
// 단일 인스턴스/단일 어드민 가정(동시 회전 race는 README 한계 참고).
@Service
public class RefreshTokenService {

    private static final String INVALID_REFRESH = "리프레시 토큰이 유효하지 않습니다.";

    private final RefreshTokenRepository repository;
    private final long refreshSeconds;
    private final Supplier<Instant> clock;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${refresh.expiration-seconds:604800}") long refreshSeconds) {
        this(repository, refreshSeconds, Instant::now);
    }

    // 테스트용 — 시각 공급자 주입
    RefreshTokenService(RefreshTokenRepository repository, long refreshSeconds, Supplier<Instant> clock) {
        this.repository = repository;
        this.refreshSeconds = refreshSeconds;
        this.clock = clock;
    }

    // 새 refresh 토큰 발급 — 평문 토큰은 반환값으로만 1회 노출, DB엔 해시 저장
    @Transactional
    public IssuedToken issue(Admin admin) {
        Instant now = clock.get();
        repository.deleteExpiredByAdmin(admin, now);
        String token = generateToken();
        Instant expiresAt = now.plusSeconds(refreshSeconds);
        repository.save(new RefreshToken(admin, hash(token), expiresAt, now));
        return new IssuedToken(token, expiresAt);
    }

    // 회전 — 검증 후 옛 토큰 revoke, 새 토큰 발급. 재사용/만료/미존재는 401.
    @Transactional
    public RotationResult rotate(String presentedToken) {
        Instant now = clock.get();
        RefreshToken stored = repository.findByTokenHash(hash(presentedToken))
                .orElseThrow(() -> new UnauthorizedException(INVALID_REFRESH));

        if (stored.isRevoked()) {
            // 이미 폐기된 토큰 재제출 = 탈취 정황 → 해당 admin의 살아있는 토큰을 전부 폐기
            repository.revokeAllByAdmin(stored.getAdmin());
            throw new UnauthorizedException(INVALID_REFRESH);
        }
        if (!now.isBefore(stored.getExpiresAt())) {
            throw new UnauthorizedException(INVALID_REFRESH);
        }

        stored.revoke();
        IssuedToken refresh = issue(stored.getAdmin());
        return new RotationResult(stored.getAdmin(), refresh);
    }

    // 로그아웃 — 제출된 토큰을 폐기(미존재/중복이어도 멱등)
    @Transactional
    public void revoke(String presentedToken) {
        repository.findByTokenHash(hash(presentedToken)).ifPresent(RefreshToken::revoke);
    }

    private String generateToken() {
        byte[] bytes = new byte[32]; // 256비트
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원", e);
        }
    }

    // 발급 결과 — 평문 토큰과 만료 시각
    public record IssuedToken(String token, Instant expiresAt) {}

    // 회전 결과 — 토큰 소유 admin과 새 refresh
    public record RotationResult(Admin admin, IssuedToken refresh) {}
}
