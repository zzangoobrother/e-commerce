package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.auth.RefreshTokenService.TokenOwner;
import com.ecommerce.auth.dto.LoginRequest;
import com.ecommerce.auth.dto.TokenResponse;
import com.ecommerce.common.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final String LOGIN_FAIL_MESSAGE = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String INVALID_REFRESH_MESSAGE = "리프레시 토큰이 유효하지 않습니다.";
    private static final String DUMMY_PASSWORD = "dummy-password-for-timing-mitigation";

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final RefreshTokenService refreshTokenService;
    private final long expirationSeconds;
    // 타이밍 공격 완화용 — username 부재 시에도 동일한 BCrypt 비용을 치르기 위한 더미 해시
    private final String dummyHash;

    public AuthService(AdminRepository adminRepository,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       RefreshTokenService refreshTokenService,
                       @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenService = refreshTokenService;
        this.expirationSeconds = expirationSeconds;
        this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    // 로그인: 자격 검증 후 access(JWT) + refresh(opaque) 발급
    @Transactional
    public TokenResponse login(LoginRequest request) {
        Admin admin = adminRepository.findByUsername(request.username()).orElse(null);
        if (admin == null) {
            // 계정이 없어도 BCrypt 검증을 수행해 응답 시간으로 계정 존재 여부가 새지 않게 한다.
            @SuppressWarnings("unused")
            boolean ignored = passwordEncoder.matches(request.password(), dummyHash);
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }
        if (!passwordEncoder.matches(request.password(), admin.getPassword())) {
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }
        return issueTokens(admin);
    }

    // 리프레시: refresh 회전 후 새 access + refresh 발급 (어드민 토큰만 허용)
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        RotationResult result = refreshTokenService.rotate(refreshToken);
        // 어드민 토큰이 아니면 예외를 던져 이 트랜잭션(rotate 회전 포함)을 통째로 롤백한다
        // — 고객 토큰이 어드민 리프레시 경로에서 조용히 소비되지 않도록 보장.
        if (result.owner().type() != OwnerType.ADMIN) {
            throw new UnauthorizedException(INVALID_REFRESH_MESSAGE);
        }
        Admin admin = adminRepository.findById(result.owner().id())
                .orElseThrow(() -> new UnauthorizedException(INVALID_REFRESH_MESSAGE));
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plusSeconds(expirationSeconds);
        String accessToken = encodeAccess(admin, now, accessExpiresAt);
        return new TokenResponse(accessToken, accessExpiresAt,
                result.refresh().token(), result.refresh().expiresAt());
    }

    // 로그아웃: refresh 폐기 — 어드민 타입 가드 적용(타입 불일치 시 no-op, 멱등)
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken, OwnerType.ADMIN);
    }

    private TokenResponse issueTokens(Admin admin) {
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plusSeconds(expirationSeconds);
        String accessToken = encodeAccess(admin, now, accessExpiresAt);
        IssuedToken refresh = refreshTokenService.issue(new TokenOwner(OwnerType.ADMIN, admin.getId()));
        return new TokenResponse(accessToken, accessExpiresAt, refresh.token(), refresh.expiresAt());
    }

    private String encodeAccess(Admin admin, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(admin.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", "ADMIN")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
