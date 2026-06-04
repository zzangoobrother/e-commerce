# 보안 보완(Security Hardening) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민 인증의 운영 준비 한계 8건(httpOnly 쿠키 전환, IP 기준 로그인 시도 제한, 타이밍 공격 완화, 만료 토큰 테스트, deprecated API 정리, 테스트 시크릿, 문서 동기화)을 해소한다.

**Architecture:** 백엔드는 `AuthService`/`SecurityConfig`에 국소 변경 + 신규 `LoginAttemptService`(인메모리 IP 카운터)를 추가한다. 프론트는 토큰 저장을 클라이언트 `document.cookie`에서 Next.js **Server Action + httpOnly 쿠키**로 옮기고, 로그아웃/401 시 쿠키 삭제를 서버 측(Route Handler)에서 수행한다.

**Tech Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security(oauth2-resource-server, HS256) · JUnit5/MockMvc/AssertJ · Next.js 16.2.6(Server Action, Route Handler, `useActionState`) · React 19

설계 문서: `docs/superpowers/specs/2026-06-04-security-hardening-design.md`

---

## 파일 구조

**백엔드 (신규)**
- `backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java` — IP 기준 인메모리 시도 카운터
- `backend/src/main/java/com/ecommerce/common/TooManyAttemptsException.java` — 429 매핑 예외
- `backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java` — 카운터 단위 테스트

**백엔드 (수정)**
- `backend/src/main/java/com/ecommerce/auth/AuthService.java` — 더미 BCrypt(타이밍 완화)
- `backend/src/main/java/com/ecommerce/auth/AuthController.java` — IP 추출 + 시도 제한 연동
- `backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java` — 429 핸들러
- `backend/src/main/java/com/ecommerce/common/SecurityConfig.java` — deprecated API 정리
- `backend/src/main/resources/application-test.yml` — 테스트 전용 jwt.secret
- `backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java` — 429 테스트 + 카운터 초기화
- `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java` — 만료 토큰 401 테스트 + 카운터 초기화

**프론트 (신규)**
- `frontend/src/app/admin/login/actions.ts` — `loginAction` Server Action
- `frontend/src/app/admin/logout/route.ts` — 로그아웃/쿠키 삭제 Route Handler

**프론트 (수정)**
- `frontend/src/app/admin/login/page.tsx` — `useActionState` 폼, `document.cookie` 제거
- `frontend/src/app/admin/LogoutButton.tsx` — `/admin/logout` POST 폼
- `frontend/src/app/admin/suppliers/page.tsx` — 401 시 `/admin/logout` 리다이렉트
- `frontend/src/app/admin/products/page.tsx` — 401 시 `/admin/logout` 리다이렉트

**문서 (수정)**: `README.md`, 인증 스펙 9장, `docs/ROADMAP.md`

---

## Task 1: 테스트 전용 JWT 시크릿 (항목 8)

**Files:**
- Modify: `backend/src/main/resources/application-test.yml`

- [ ] **Step 1: 테스트 프로파일에 jwt 블록 추가**

`application-test.yml` 끝에 다음을 추가한다(운영 디폴트 시크릿을 테스트가 상속하지 않도록 분리). HS256은 최소 32바이트(256비트) 시크릿이 필요하므로 32자 이상 문자열을 사용한다.

```yaml
jwt:
  secret: test-only-secret-key-not-for-production-0123456789
  expiration-seconds: 3600
```

- [ ] **Step 2: 전체 테스트 실행으로 회귀 없음 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS (기존 모든 테스트 통과 — 테스트가 새 시크릿으로 발급/검증)

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/application-test.yml
git commit -m "test: 테스트 전용 JWT 시크릿 분리(운영 디폴트 미사용)"
```

---

## Task 2: 타이밍 공격 완화 — 더미 BCrypt (항목 4)

현재 `AuthService.login`은 username이 없으면 BCrypt 검증을 건너뛰고 즉시 401을 던져, 응답 시간으로 계정 존재 여부가 노출된다. username 부재 시에도 더미 해시에 대해 `matches`를 수행해 두 경로의 시간을 맞춘다.

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/auth/AuthService.java`

- [ ] **Step 1: 기존 "존재하지 않는 아이디 → 401" 테스트가 통과하는지 확인 (기준선)**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.AuthControllerTest"`
Expected: PASS (변경 전 기준선 — 이 동작은 변경 후에도 유지되어야 한다)

- [ ] **Step 2: AuthService에 더미 해시 검증 추가**

`AuthService.java`에서 필드와 생성자에 더미 해시를 준비하고, `login`의 username 조회 실패 분기를 수정한다.

생성자 끝에 더미 해시 초기화 추가(기존 필드 대입 직후):

```java
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-mitigation");
```

클래스 필드에 추가(`expirationSeconds` 필드 아래):

```java
    // 타이밍 공격 완화용 — username 부재 시에도 동일한 BCrypt 비용을 치르기 위한 더미 해시
    private final String dummyHash;
```

`login` 메서드의 조회 분기를 다음으로 교체한다(기존 `orElseThrow` 한 줄을 대체):

```java
        Admin admin = adminRepository.findByUsername(request.username()).orElse(null);
        if (admin == null) {
            // 계정이 없어도 BCrypt 검증을 수행해 응답 시간으로 계정 존재 여부가 새지 않게 한다
            passwordEncoder.matches(request.password(), dummyHash);
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }
        if (!passwordEncoder.matches(request.password(), admin.getPassword())) {
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }
```

(기존의 `Admin admin = adminRepository.findByUsername(...).orElseThrow(...)` 와 그 아래 `if (!passwordEncoder.matches(...))` 블록을 위 코드로 대체한다.)

- [ ] **Step 3: 테스트 실행으로 동작 유지 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.AuthControllerTest"`
Expected: PASS (존재하지 않는 아이디·잘못된 비밀번호 모두 여전히 401, 메시지 동일)

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/AuthService.java
git commit -m "feat: 로그인 타이밍 공격 완화(계정 부재 시 더미 BCrypt 수행)"
```

---

## Task 3: 로그인 시도 제한 — IP 기준 (항목 3)

동일 IP에서 5회 연속 실패하면 15분간 차단(429). 성공 시 카운터 리셋. 인메모리(`ConcurrentHashMap`). 잠금 시간이 지나면 카운트가 초기화된다.

**Files:**
- Create: `backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java`
- Create: `backend/src/main/java/com/ecommerce/common/TooManyAttemptsException.java`
- Create: `backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java`
- Modify: `backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/AuthController.java`
- Modify: `backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java`

- [ ] **Step 1: LoginAttemptService 단위 테스트 작성 (실패하는 테스트)**

`backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java`:

```java
package com.ecommerce.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    void 최대_시도_횟수_미만이면_차단되지_않는다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 최대_시도_횟수에_도달하면_차단된다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
    }

    @Test
    void 잠금_시간이_지나면_다시_허용된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-04T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, 900, now::get);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
        now.set(now.get().plusSeconds(901));
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 성공_리셋_후에는_차단_카운트가_사라진다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        service.reset("1.1.1.1");
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 서로_다른_IP는_독립적으로_카운트된다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("2.2.2.2")).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행으로 컴파일 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.LoginAttemptServiceTest"`
Expected: FAIL (LoginAttemptService 클래스 없음 → 컴파일 에러)

- [ ] **Step 3: LoginAttemptService 구현**

`backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java`:

```java
package com.ecommerce.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// 로그인 시도 제한 — IP 기준 인메모리 카운터.
// 단일 인스턴스 한정(다중 인스턴스 배포 시 인스턴스별로 카운트됨 — README 보안 한계 참고).
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final long lockoutSeconds;
    private final Supplier<Instant> clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    // 실패 누적 횟수와 잠금 만료 시각(미잠금이면 null)
    private record Attempt(int count, Instant lockedUntil) {}

    public LoginAttemptService(
            @Value("${login.max-attempts:5}") int maxAttempts,
            @Value("${login.lockout-seconds:900}") long lockoutSeconds) {
        this(maxAttempts, lockoutSeconds, Instant::now);
    }

    // 테스트용 — 시각 공급자를 주입해 잠금 만료를 결정적으로 검증
    LoginAttemptService(int maxAttempts, long lockoutSeconds, Supplier<Instant> clock) {
        this.maxAttempts = maxAttempts;
        this.lockoutSeconds = lockoutSeconds;
        this.clock = clock;
    }

    // 현재 해당 IP가 잠금 상태인지
    public boolean isBlocked(String ip) {
        Attempt attempt = attempts.get(ip);
        return attempt != null
                && attempt.lockedUntil() != null
                && clock.get().isBefore(attempt.lockedUntil());
    }

    // 실패 1회 기록. 임계값 도달 시 잠금. 잠금이 이미 만료됐으면 카운트를 새로 시작한다.
    public void recordFailure(String ip) {
        attempts.compute(ip, (key, prev) -> {
            Instant now = clock.get();
            boolean expired = prev != null && prev.lockedUntil() != null && !now.isBefore(prev.lockedUntil());
            int prevCount = (prev == null || expired) ? 0 : prev.count();
            int count = prevCount + 1;
            Instant lockedUntil = count >= maxAttempts ? now.plusSeconds(lockoutSeconds) : null;
            return new Attempt(count, lockedUntil);
        });
    }

    // 로그인 성공 시 해당 IP 카운터 제거
    public void reset(String ip) {
        attempts.remove(ip);
    }

    // 테스트 격리용 — 전체 카운터 초기화(싱글톤 빈이라 테스트 간 상태가 누적되는 것을 방지)
    public void clearAll() {
        attempts.clear();
    }
}
```

- [ ] **Step 4: 단위 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.LoginAttemptServiceTest"`
Expected: PASS (5개 모두)

- [ ] **Step 5: TooManyAttemptsException + 429 핸들러 추가**

`backend/src/main/java/com/ecommerce/common/TooManyAttemptsException.java`:

```java
package com.ecommerce.common;

// 로그인 시도 제한 초과 시 던지는 예외 (429 매핑)
public class TooManyAttemptsException extends RuntimeException {
    public TooManyAttemptsException(String message) {
        super(message);
    }
}
```

`GlobalExceptionHandler.java`에 핸들러 메서드를 추가한다(기존 `handleUnauthorized` 아래). 동시에 import에 추가가 필요하면 추가한다(`org.springframework.http.HttpStatus`는 이미 import됨).

```java
    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyAttempts(TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("message", e.getMessage()));
    }
```

- [ ] **Step 6: AuthController에 IP 추출 + 시도 제한 연동**

`AuthController.java`를 다음으로 교체한다:

```java
package com.ecommerce.auth;

import com.ecommerce.auth.dto.LoginRequest;
import com.ecommerce.auth.dto.LoginResponse;
import com.ecommerce.common.TooManyAttemptsException;
import com.ecommerce.common.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 어드민 로그인 API — 인증 없이 접근 가능한 유일한 어드민 경로
@RestController
@RequestMapping("/api/admin/login")
public class AuthController {

    private static final String BLOCKED_MESSAGE = "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.";

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;

    public AuthController(AuthService authService, LoginAttemptService loginAttemptService) {
        this.authService = authService;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = clientIp(http);
        if (loginAttemptService.isBlocked(ip)) {
            throw new TooManyAttemptsException(BLOCKED_MESSAGE);
        }
        try {
            LoginResponse response = authService.login(request);
            loginAttemptService.reset(ip);
            return response;
        } catch (UnauthorizedException e) {
            loginAttemptService.recordFailure(ip);
            throw e;
        }
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
```

- [ ] **Step 7: AuthControllerTest에 카운터 초기화 + 429 테스트 추가**

`AuthControllerTest.java`에 import를 추가한다:

```java
import org.junit.jupiter.api.BeforeEach;
```

필드 영역에 추가:

```java
    @Autowired LoginAttemptService loginAttemptService;
```

`@BeforeEach`를 추가한다(기존 `@AfterEach cleanup` 위/아래 어디든):

```java
    @BeforeEach
    void resetAttempts() {
        // 싱글톤 LoginAttemptService의 상태가 다른 테스트로 누적되지 않도록 초기화
        loginAttemptService.clearAll();
    }
```

429 테스트 메서드를 추가한다:

```java
    @Test
    void 동일_IP에서_연속_5회_실패하면_6회째_429를_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "nobody", "password", "wrong"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/admin/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }
```

- [ ] **Step 8: 전체 백엔드 테스트 실행**

Run: `cd backend && ./gradlew test`
Expected: PASS (신규 429 테스트 포함 전체 통과. 기존 로그인 실패 테스트가 누적되지 않음을 `clearAll`이 보장)

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java \
        backend/src/main/java/com/ecommerce/common/TooManyAttemptsException.java \
        backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java \
        backend/src/main/java/com/ecommerce/auth/AuthController.java \
        backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java \
        backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java
git commit -m "feat: 로그인 시도 제한 추가(IP 기준 5회/15분, 429)"
```

---

## Task 4: 만료 토큰 → 401 테스트 추가 (항목 6)

만료/무효 토큰이 401로 거부되는 경로는 이미 동작하지만 테스트로 커버되지 않았다. `JwtEncoder`로 과거 `exp` 토큰을 발급해 검증 경로를 고정한다.

**Files:**
- Modify: `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java`

- [ ] **Step 1: 만료 토큰 테스트 작성**

`SecurityProtectionTest.java`에 import를 추가한다:

```java
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import com.ecommerce.auth.LoginAttemptService;

import java.time.Instant;
```

필드 영역에 추가:

```java
    @Autowired JwtEncoder jwtEncoder;
    @Autowired LoginAttemptService loginAttemptService;
```

`@BeforeEach`를 추가한다(시도 제한 상태가 다른 테스트 클래스에서 넘어오는 것을 방지):

```java
    @BeforeEach
    void resetAttempts() {
        loginAttemptService.clearAll();
    }
```

테스트 메서드를 추가한다:

```java
    @Test
    void 만료된_토큰으로_어드민_API_호출시_401을_반환한다() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("admin")
                .issuedAt(past.minusSeconds(3600))
                .expiresAt(past)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }
```

- [ ] **Step 2: 테스트 실행**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.common.SecurityProtectionTest"`
Expected: PASS (만료 토큰 → 401. 기존 동작이 테스트로 고정됨)

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java
git commit -m "test: 만료 토큰 → 401 경로 테스트 추가"
```

---

## Task 5: Spring Security 7 deprecated API 정리 (항목 7)

`SecurityConfig`에서 deprecation 경고가 나는 API를 권장 형태로 교체한다. **정확한 대체 API는 추측하지 말고 실제 경고와 문서를 근거로 적용한다.**

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java`

- [ ] **Step 1: deprecation 경고를 실제로 surfacing**

Run: `cd backend && ./gradlew clean compileJava -Dorg.gradle.warning.mode=all`
또는 컴파일러 lint 활성화 후 빌드해 `SecurityConfig.java`에서 발생하는 deprecation 경고(어느 메서드/라인인지)를 확인한다.
Expected: 경고 목록에서 deprecated 호출 지점 식별(예: `oauth2ResourceServer`/`jwt`/`csrf` 관련 람다 DSL 중 하나).

- [ ] **Step 2: 권장 대체 API 확인 (문서 근거)**

context7 MCP(`mcp__context7__resolve-library-id` → `query-docs`로 "Spring Security 7 SecurityFilterChain DSL deprecation") 또는 공식 문서로 Step 1에서 식별한 메서드의 비-deprecated 대체 형태를 확인한다. 추측 금지 — 문서에 명시된 형태만 사용한다.

- [ ] **Step 3: 식별된 호출을 권장 형태로 교체**

Step 2에서 확인한 대체 API로 해당 라인만 수정한다. 동작(규칙: `/api/admin/login` permitAll, `/api/admin/**` authenticated, 그 외 permitAll, stateless, CORS, 커스텀 401 EntryPoint)은 동일하게 유지한다.

- [ ] **Step 4: 경고 소멸 + 테스트 통과 확인**

Run: `cd backend && ./gradlew clean compileJava -Dorg.gradle.warning.mode=all && ./gradlew test`
Expected: 해당 deprecation 경고 사라짐 + 전체 테스트 PASS(보호 규칙 회귀 없음)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ecommerce/common/SecurityConfig.java
git commit -m "refactor: Spring Security 7 deprecated API 정리(동작 동일)"
```

---

## Task 6: httpOnly 쿠키 전환 + 로그아웃/401 처리 (항목 1, 2)

토큰 저장을 클라이언트 `document.cookie`에서 Server Action + httpOnly 쿠키로 옮긴다. 로그아웃·401 시 쿠키 삭제는 서버 측 Route Handler에서 수행한다.

> **Next.js 16 필독:** 코드 작성 전 `frontend/node_modules/next/dist/docs/` 에서 다음을 확인한다 — (1) `cookies()` 사용법과 `set`/`delete` 옵션(httpOnly/sameSite/secure/maxAge), (2) Server Action(`"use server"`) 시그니처와 `useActionState`, (3) Route Handler(`route.ts`)의 `NextResponse.redirect` + `response.cookies`. `frontend/AGENTS.md` 지침에 따라 학습 데이터와 다를 수 있으므로 반드시 문서를 본다.

**Files:**
- Create: `frontend/src/app/admin/login/actions.ts`
- Create: `frontend/src/app/admin/logout/route.ts`
- Modify: `frontend/src/app/admin/login/page.tsx`
- Modify: `frontend/src/app/admin/LogoutButton.tsx`
- Modify: `frontend/src/app/admin/suppliers/page.tsx`
- Modify: `frontend/src/app/admin/products/page.tsx`

- [ ] **Step 1: Next.js 16 문서 확인 (위 필독 항목)**

Run: `ls frontend/node_modules/next/dist/docs/` 후 cookies / server-actions / route-handlers 관련 가이드를 읽는다.
Expected: `cookies().set(name, value, options)`·Server Action·Route Handler API 형태 확정. Step 2~6 코드가 현재 버전과 맞는지 대조(다르면 그 형태로 보정).

- [ ] **Step 2: loginAction Server Action 작성**

`frontend/src/app/admin/login/actions.ts`:

```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { login } from "@/lib/api";

// 로그인 Server Action — 백엔드 로그인 호출 후 httpOnly 쿠키 설정.
// 실패 시 에러 메시지 문자열을 반환(폼에서 표시), 성공 시 /admin으로 리다이렉트.
export async function loginAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const username = String(formData.get("username") ?? "");
  const password = String(formData.get("password") ?? "");

  let token: string;
  let expiresAt: string;
  try {
    const res = await login(username, password);
    token = res.token;
    expiresAt = res.expiresAt;
  } catch (err) {
    return err instanceof Error ? err.message : "로그인에 실패했습니다.";
  }

  const maxAge = Math.max(
    0,
    Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000),
  );
  const store = await cookies();
  store.set("admin_token", token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge,
  });

  // redirect는 내부적으로 예외를 던지므로 try/catch 밖에서 호출한다
  redirect("/admin");
}
```

- [ ] **Step 3: 로그아웃 Route Handler 작성**

`frontend/src/app/admin/logout/route.ts` — 버튼(POST)과 401 리다이렉트(GET) 모두에서 사용하는 단일 로그아웃 경로:

```ts
import { NextResponse } from "next/server";

// 로그아웃 — httpOnly 쿠키를 삭제하고 로그인 페이지로 리다이렉트.
// 서버 컴포넌트는 렌더 중 쿠키를 못 지우므로, 401 처리도 이 경로로 리다이렉트해 잔존 쿠키를 제거한다.
function clearAndRedirect(request: Request) {
  const response = NextResponse.redirect(
    new URL("/admin/login", request.url),
    303,
  );
  response.cookies.delete("admin_token");
  return response;
}

export function GET(request: Request) {
  return clearAndRedirect(request);
}

export function POST(request: Request) {
  return clearAndRedirect(request);
}
```

- [ ] **Step 4: 로그인 페이지를 useActionState 폼으로 전환**

`frontend/src/app/admin/login/page.tsx`를 다음으로 교체(`document.cookie`·`login` 직접 호출 제거):

```tsx
"use client";

import { useActionState } from "react";
import type { CSSProperties } from "react";
import { loginAction } from "./actions";

// 어드민 로그인 폼 — Server Action(loginAction)이 httpOnly 쿠키를 설정하고 리다이렉트한다
export default function AdminLoginPage() {
  const [error, formAction] = useActionState(loginAction, null);

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>어드민 로그인</h1>
      <form action={formAction} style={{ display: "grid", gap: 12 }}>
        <input name="username" placeholder="아이디" style={inputStyle} />
        <input
          name="password"
          type="password"
          placeholder="비밀번호"
          style={inputStyle}
        />
        <button type="submit" style={{ padding: 8, cursor: "pointer" }}>
          로그인
        </button>
        {error && <p style={{ color: "crimson" }}>{error}</p>}
      </form>
    </main>
  );
}

const inputStyle: CSSProperties = {
  border: "1px solid #ddd",
  padding: 8,
  borderRadius: 4,
};
```

- [ ] **Step 5: LogoutButton을 POST 폼으로 전환**

`frontend/src/app/admin/LogoutButton.tsx`를 다음으로 교체(`"use client"`·`document.cookie` 제거 — 서버 컴포넌트):

```tsx
// 로그아웃 — /admin/logout 라우트로 POST하여 httpOnly 쿠키 삭제 후 로그인 페이지로 이동
export default function LogoutButton() {
  return (
    <form action="/admin/logout" method="post" style={{ margin: 0 }}>
      <button type="submit" style={{ padding: "4px 12px", cursor: "pointer" }}>
        로그아웃
      </button>
    </form>
  );
}
```

- [ ] **Step 6: 어드민 페이지의 401 처리를 /admin/logout 경유로 변경**

`frontend/src/app/admin/suppliers/page.tsx`에서 401 catch 블록의 리다이렉트 대상을 바꾼다(토큰 부재 시 `redirect("/admin/login")`은 그대로 — 지울 쿠키가 없음). 401 분기만 교체:

```tsx
    // 401 = 토큰 만료/무효 → 쿠키 삭제 후 로그인 페이지로 (/admin/logout 경유)
    if (err instanceof ApiError && err.status === 401) {
      redirect("/admin/logout");
    }
```

`frontend/src/app/admin/products/page.tsx`도 동일하게 401 분기를 `redirect("/admin/logout")`으로 바꾼다. (해당 파일의 401 처리 패턴을 동일하게 적용. 파일 내 `ApiError`·`redirect` import가 없으면 suppliers/page.tsx와 동일하게 추가한다.)

- [ ] **Step 7: 프로덕션 빌드로 검증**

Run: `cd frontend && npm run build`
Expected: PASS (`/admin/login`, `/admin/logout` 라우트 등록, 타입 에러 없음)

- [ ] **Step 8: 수동 E2E + httpOnly 확인**

1. 백엔드(`cd backend && ./gradlew bootRun`)와 프론트(`cd frontend && npm run dev`) 기동.
2. `/admin` 접근 → `/admin/login` 리다이렉트 확인.
3. 로그인 → `/admin` 진입. 브라우저 DevTools > Application > Cookies에서 `admin_token`의 **HttpOnly 플래그 체크** 확인.
4. DevTools Console에서 `document.cookie` 실행 → `admin_token`이 **보이지 않음** 확인.
5. 로그아웃 → 쿠키 삭제 + `/admin/login` 이동 확인.
Expected: 위 5개 모두 충족.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/app/admin/login/actions.ts \
        frontend/src/app/admin/logout/route.ts \
        frontend/src/app/admin/login/page.tsx \
        frontend/src/app/admin/LogoutButton.tsx \
        frontend/src/app/admin/suppliers/page.tsx \
        frontend/src/app/admin/products/page.tsx
git commit -m "feat: 어드민 토큰 httpOnly 쿠키 전환(Server Action)·로그아웃/401 쿠키 삭제"
```

---

## Task 7: 보안 한계 문서 단일 출처화 (항목 5)

보안 한계가 README(6개)·인증 스펙 9장(4개)·ROADMAP(9개)에 분산돼 있다. README를 정본으로 삼고 해소 항목을 반영한다.

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-06-03-admin-auth-design.md` (9장)
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: README 보안 섹션을 정본으로 갱신**

`README.md`의 보안 한계 섹션에서, 이번 사이클로 해소된 항목을 "해결됨(2026-06-04 보안 보완 사이클)"로 표시하고 남은 항목과 분리한다.
- 해결됨: httpOnly 쿠키(+SameSite=Lax) 전환, 로그인 시도 제한(IP 5회/15분), 타이밍 공격 완화, 401/로그아웃 시 쿠키 삭제
- 남음(다음 사이클): 리프레시 토큰, 토큰 서버 측 무효화(블랙리스트), 시도 제한 다중 인스턴스 한계(인메모리)

(README의 정확한 현재 문구는 파일을 열어 확인 후, 위 분류에 맞게 항목을 이동/표시한다.)

- [ ] **Step 2: 인증 스펙 9장을 README 참조로 정리**

`2026-06-03-admin-auth-design.md`의 9장 "보안 고려사항 및 골격 한계"에서 중복 목록을 줄이고, "최신 보안 한계 현황은 README의 보안 섹션을 단일 출처로 한다"는 참조 문구를 추가한다. 9장에 이번 사이클(2026-06-04)로 해소된 항목이라는 한 줄 메모를 남긴다.

- [ ] **Step 3: ROADMAP 후보 1 표 동기화**

`docs/ROADMAP.md`에서:
- "완료된 사이클" 표에 사이클 4(보안 보완, 2026-06-04, 핵심 8건) 행을 추가하고, 어드민 인증 사이클 상태를 실제 머지 상태에 맞게 갱신한다.
- "다음 사이클 후보 > 후보 1" 표에서 이번에 해소된 항목(httpOnly, 401 쿠키 삭제, 시도 제한, 타이밍 완화, 문서 동기화, 만료토큰 테스트, deprecated 정리, 테스트 시크릿)을 제거하고, 남은 항목(리프레시 토큰)만 후보로 남기거나 별도 후보로 승격한다.
- "운영 배포 전 체크리스트"의 "보안 보완 사이클(후보 1) 완료" 항목을 체크 처리한다.

- [ ] **Step 4: 문서 일관성 육안 확인**

Run: `grep -n "httpOnly\|시도 제한\|타이밍\|리프레시" README.md docs/ROADMAP.md docs/superpowers/specs/2026-06-03-admin-auth-design.md`
Expected: 해소 항목이 세 문서에서 모순 없이 일치(해결됨/남음 분류 동일).

- [ ] **Step 5: Commit**

```bash
git add README.md docs/ROADMAP.md docs/superpowers/specs/2026-06-03-admin-auth-design.md
git commit -m "docs: 보안 한계 문서 단일 출처화(README 정본)·해소 항목 반영"
```

---

## 최종 검증 (Definition of Done)

- [ ] `cd backend && ./gradlew test` 전체 통과
- [ ] `cd frontend && npm run build` 통과
- [ ] DevTools에서 `admin_token`이 HttpOnly 플래그 보유 + `document.cookie`로 안 읽힘
- [ ] 동일 IP 6회 로그인 실패 → 429
- [ ] 로그아웃·만료 토큰 시 쿠키 비워지고 `/admin/login` 이동
- [ ] `./gradlew compileJava -Dorg.gradle.warning.mode=all`에서 SecurityConfig deprecation 경고 없음
- [ ] README/스펙/ROADMAP 보안 한계 기술 일치
