package com.ecommerce.auth;

import com.ecommerce.common.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
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
// 소유자 무관(어드민/고객) — (ownerType, ownerId)로 격리한다. 단일 인스턴스 가정.
@Service
public class RefreshTokenService {

    private static final String INVALID_REFRESH = "리프레시 토큰이 유효하지 않습니다.";

    private final RefreshTokenRepository repository;
    private final TokenTheftResponder theftResponder;
    private final long refreshSeconds;
    private final Supplier<Instant> clock;
    private final SecureRandom random = new SecureRandom();

    // Spring DI용 — 생성자가 2개이므로 @Autowired로 명시
    @Autowired
    public RefreshTokenService(RefreshTokenRepository repository,
                               TokenTheftResponder theftResponder,
                               @Value("${refresh.expiration-seconds:604800}") long refreshSeconds) {
        this(repository, theftResponder, refreshSeconds, Instant::now);
    }

    // 테스트용 — 시각 공급자 주입
    RefreshTokenService(RefreshTokenRepository repository, TokenTheftResponder theftResponder,
                        long refreshSeconds, Supplier<Instant> clock) {
        this.repository = repository;
        this.theftResponder = theftResponder;
        this.refreshSeconds = refreshSeconds;
        this.clock = clock;
    }

    // 새 refresh 토큰 발급 — 평문 토큰은 반환값으로만 1회 노출, DB엔 해시 저장
    @Transactional
    public IssuedToken issue(TokenOwner owner) {
        Instant now = clock.get();
        repository.deleteExpiredByOwner(owner.type(), owner.id(), now);
        String token = generateToken();
        Instant expiresAt = now.plusSeconds(refreshSeconds);
        repository.save(new RefreshToken(owner.type(), owner.id(), hash(token), expiresAt, now));
        return new IssuedToken(token, expiresAt);
    }

    // 회전 — 검증 후 옛 토큰 revoke, 새 토큰 발급. 재사용/만료/미존재는 401.
    @Transactional
    public RotationResult rotate(String presentedToken) {
        Instant now = clock.get();
        RefreshToken stored = repository.findByTokenHash(hash(presentedToken))
                .orElseThrow(() -> new UnauthorizedException(INVALID_REFRESH));

        if (stored.isRevoked()) {
            // 이미 폐기된 토큰 재제출 = 탈취 정황 → 형제 토큰 일괄 폐기.
            // 별도 트랜잭션(REQUIRES_NEW)으로 즉시 커밋 — 아래 예외로 이 트랜잭션이 롤백돼도 폐기는 남는다.
            theftResponder.revokeAllFor(stored.getOwnerType(), stored.getOwnerId());
            throw new UnauthorizedException(INVALID_REFRESH);
        }
        if (!now.isBefore(stored.getExpiresAt())) {
            throw new UnauthorizedException(INVALID_REFRESH);
        }

        // owner를 plain 값으로 바인딩 — issue()의 clearAutomatically 이후에도 안전
        TokenOwner owner = new TokenOwner(stored.getOwnerType(), stored.getOwnerId());
        stored.revoke();
        IssuedToken refresh = issue(owner);
        return new RotationResult(owner, refresh);
    }

    // 로그아웃 — 제출된 토큰을 폐기(미존재/타입 불일치/중복이어도 멱등).
    // expected와 소유자 타입이 일치할 때만 폐기해 교차 타입 폐기를 막는다(refresh 가드와 대칭).
    @Transactional
    public void revoke(String presentedToken, OwnerType expected) {
        repository.findByTokenHash(hash(presentedToken))
                .filter(token -> token.getOwnerType() == expected)
                .ifPresent(RefreshToken::revoke);
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

    // 토큰 소유자 신원 — 어드민/고객 + 엔티티 id
    public record TokenOwner(OwnerType type, Long id) {}

    // 발급 결과 — 평문 토큰과 만료 시각
    public record IssuedToken(String token, Instant expiresAt) {}

    // 회전 결과 — 토큰 소유자와 새 refresh
    public record RotationResult(TokenOwner owner, IssuedToken refresh) {}
}
