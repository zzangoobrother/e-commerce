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

    public AuthService(AdminRepository adminRepository,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.expirationSeconds = expirationSeconds;
    }

    // 로그인: 아이디/비밀번호 검증 후 JWT 발급
    public LoginResponse login(LoginRequest request) {
        Admin admin = adminRepository.findByUsername(request.username())
                .orElseThrow(() -> new UnauthorizedException(LOGIN_FAIL_MESSAGE));

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
