package com.ecommerce.auth;

import com.ecommerce.auth.dto.LoginRequest;
import com.ecommerce.auth.dto.LoginResponse;
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

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final long expirationSeconds;
    // 타이밍 공격 완화용 — username 부재 시에도 동일한 BCrypt 비용을 치르기 위한 더미 해시
    private final String dummyHash;

    public AuthService(AdminRepository adminRepository,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.expirationSeconds = expirationSeconds;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-mitigation");
    }

    // 로그인: 아이디/비밀번호 검증 후 JWT 발급
    public LoginResponse login(LoginRequest request) {
        Admin admin = adminRepository.findByUsername(request.username()).orElse(null);
        if (admin == null) {
            // 계정이 없어도 BCrypt 검증을 수행해 응답 시간으로 계정 존재 여부가 새지 않게 한다
            passwordEncoder.matches(request.password(), dummyHash);
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }
        if (!passwordEncoder.matches(request.password(), admin.getPassword())) {
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expirationSeconds);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(admin.getUsername())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new LoginResponse(token, expiresAt);
    }
}
