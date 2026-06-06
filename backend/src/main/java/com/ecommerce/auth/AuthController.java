package com.ecommerce.auth;

import com.ecommerce.auth.dto.LoginRequest;
import com.ecommerce.auth.dto.RefreshRequest;
import com.ecommerce.auth.dto.TokenResponse;
import com.ecommerce.common.TooManyAttemptsException;
import com.ecommerce.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 어드민 인증 API — 로그인/리프레시/로그아웃 (모두 인증 없이 접근, refresh 토큰이 자격 증명)
@RestController
@RequestMapping("/api/admin")
public class AuthController {

    private static final String BLOCKED_MESSAGE = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.";

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;

    public AuthController(AuthService authService, LoginAttemptService loginAttemptService) {
        this.authService = authService;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        if (loginAttemptService.isBlocked(ip)) {
            throw new TooManyAttemptsException(BLOCKED_MESSAGE);
        }
        try {
            TokenResponse response = authService.login(request);
            loginAttemptService.reset(ip);
            return response;
        } catch (UnauthorizedException e) {
            loginAttemptService.recordFailure(ip);
            throw e;
        }
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    // 클라이언트 IP 추출 — 프록시 뒤에서는 X-Forwarded-For 첫 항목, 없으면 원격 주소
    private String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
