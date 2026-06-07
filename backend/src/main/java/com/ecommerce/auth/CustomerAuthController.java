package com.ecommerce.auth;

import com.ecommerce.auth.dto.CustomerLoginRequest;
import com.ecommerce.auth.dto.RefreshRequest;
import com.ecommerce.auth.dto.RegisterRequest;
import com.ecommerce.auth.dto.TokenResponse;
import com.ecommerce.common.ClientIp;
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

// 고객 인증 API — 가입/로그인/리프레시/로그아웃 (모두 인증 없이 접근)
@RestController
@RequestMapping("/api/store/auth")
public class CustomerAuthController {

    private static final String BLOCKED_MESSAGE = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.";

    private final CustomerAuthService customerAuthService;
    private final LoginAttemptService loginAttemptService;

    public CustomerAuthController(CustomerAuthService customerAuthService,
                                  LoginAttemptService loginAttemptService) {
        this.customerAuthService = customerAuthService;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request) {
        return customerAuthService.register(request);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody CustomerLoginRequest request, HttpServletRequest http) {
        // 어드민 시도 제한과 버킷이 섞이지 않게 "customer:" 접두로 격리
        // 각 영역은 독립 카운터라 영역별로 5회 제한이 적용된다.
        String key = "customer:" + ClientIp.from(http);
        if (loginAttemptService.isBlocked(key)) {
            throw new TooManyAttemptsException(BLOCKED_MESSAGE);
        }
        try {
            TokenResponse response = customerAuthService.login(request);
            loginAttemptService.reset(key);
            return response;
        } catch (UnauthorizedException e) {
            loginAttemptService.recordFailure(key);
            throw e;
        }
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return customerAuthService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        customerAuthService.logout(request.refreshToken());
    }
}
