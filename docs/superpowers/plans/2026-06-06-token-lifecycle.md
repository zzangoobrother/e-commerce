# 토큰 수명 주기(Token Lifecycle) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** access token을 15분(stateless)으로 줄이고, 7일 수명의 회전형 refresh token(MySQL 저장·재사용 탐지)을 도입해 재로그인 빈도를 낮추면서 로그아웃·탈취 시 세션을 무효화한다.

**Architecture:** 백엔드는 신규 `RefreshToken` 엔티티 + `RefreshTokenService`(opaque 토큰을 SHA-256 해시로 저장, 회전 시 옛 토큰 revoke, 폐기 토큰 재제출 시 해당 admin 전체 무효화)를 추가하고, `AuthController`에 `/refresh`·`/logout`을 더한다. 프론트는 httpOnly 쿠키를 access·refresh 2개로 운영하고, access 만료(401) 시 `/admin/refresh` Route Handler로 리다이렉트해 자동 갱신한다.

**Tech Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security(oauth2-resource-server, HS256) · JPA/Hibernate · H2(test/local)/MySQL(운영) · JUnit5/@DataJpaTest/MockMvc · Next.js 16.2.6(Server Action, Route Handler, middleware) · React 19

설계 문서: `docs/superpowers/specs/2026-06-06-token-lifecycle-design.md`

---

## 파일 구조

**백엔드 (신규)**
- `backend/src/main/java/com/ecommerce/auth/RefreshToken.java` — refresh 토큰 엔티티
- `backend/src/main/java/com/ecommerce/auth/RefreshTokenRepository.java` — 조회·일괄 폐기·만료 정리
- `backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java` — 발급/회전/재사용 탐지/폐기
- `backend/src/main/java/com/ecommerce/auth/dto/TokenResponse.java` — access+refresh 응답
- `backend/src/main/java/com/ecommerce/auth/dto/RefreshRequest.java` — refresh/logout 요청
- `backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java` — 서비스 단위/통합 테스트

**백엔드 (수정)**
- `backend/src/main/java/com/ecommerce/auth/AuthService.java` — login에 refresh 발급, refresh/logout 메서드
- `backend/src/main/java/com/ecommerce/auth/AuthController.java` — 클래스 매핑 `/api/admin`, /login·/refresh·/logout
- `backend/src/main/java/com/ecommerce/common/SecurityConfig.java` — /refresh·/logout permitAll
- `backend/src/main/resources/application.yml`·`application-local.yml`·`application-test.yml` — access 900초, refresh 604800초
- `backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java` — 로그인 응답 필드 변경, /refresh·/logout 테스트
- `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java` — 로그인 토큰 추출 필드명 변경
- 삭제: `backend/src/main/java/com/ecommerce/auth/dto/LoginResponse.java`

**프론트 (신규)**
- `frontend/src/lib/auth-cookies.ts` — 쿠키 이름/옵션 공유
- `frontend/src/app/admin/refresh/route.ts` — 자동 갱신 Route Handler

**프론트 (수정)**
- `frontend/src/lib/api.ts` — `TokenResponse` 타입, `login`/`refresh`/`logout`
- `frontend/src/app/admin/login/actions.ts` — 쿠키 2개 set
- `frontend/src/app/admin/logout/route.ts` — 백엔드 logout 호출 + 쿠키 2개 삭제
- `frontend/src/app/admin/suppliers/page.tsx`·`products/page.tsx` — 401 시 `/admin/refresh?next=…`
- `frontend/src/proxy.ts` — 보호 제외 경로 + refresh 분기

**문서 (수정)**: `README.md`, `docs/ROADMAP.md`

---

## Task 1: 토큰 수명 설정 변경

access 토큰 수명을 15분으로 줄이고 refresh 수명 설정을 추가한다. 아직 refresh를 쓰는 코드가 없으므로 회귀 없이 통과해야 한다.

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-local.yml`
- Modify: `backend/src/main/resources/application-test.yml`

- [ ] **Step 1: application.yml의 access 수명 단축 + refresh 블록 추가**

`application.yml`에서 `jwt.expiration-seconds`의 기본값을 900으로 바꾸고, 최상위에 `refresh` 블록을 추가한다. 기존:
```yaml
jwt:
  secret: ${JWT_SECRET:dev-only-secret-key-change-me-in-production-1234}
  expiration-seconds: ${JWT_EXPIRATION_SECONDS:3600}
```
변경:
```yaml
jwt:
  secret: ${JWT_SECRET:dev-only-secret-key-change-me-in-production-1234}
  expiration-seconds: ${JWT_EXPIRATION_SECONDS:900}

refresh:
  # 리프레시 토큰 수명 — 7일(604800초)
  expiration-seconds: ${REFRESH_EXPIRATION_SECONDS:604800}
```

- [ ] **Step 2: application-test.yml에 동일 반영**

`application-test.yml`의 `jwt.expiration-seconds: 3600`을 `900`으로 바꾸고, 파일 끝에 추가:
```yaml
refresh:
  expiration-seconds: 604800
```

- [ ] **Step 3: application-local.yml에 refresh 설정 추가**

`application-local.yml`은 `jwt` 블록이 없어 application.yml 기본값(900)을 상속하므로 access는 그대로 둔다. 파일 끝에 추가:
```yaml
refresh:
  expiration-seconds: 604800
```

- [ ] **Step 4: 전체 테스트로 회귀 없음 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS (기존 테스트는 토큰을 발급 즉시 사용하므로 수명 단축 영향 없음)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/application.yml \
        backend/src/main/resources/application-local.yml \
        backend/src/main/resources/application-test.yml
git commit -m "feat: access 토큰 수명 15분 단축·리프레시 수명 설정 추가"
```

---

## Task 2: RefreshToken 엔티티 + Repository

**Files:**
- Create: `backend/src/main/java/com/ecommerce/auth/RefreshToken.java`
- Create: `backend/src/main/java/com/ecommerce/auth/RefreshTokenRepository.java`

- [ ] **Step 1: RefreshToken 엔티티 작성**

`backend/src/main/java/com/ecommerce/auth/RefreshToken.java`:
```java
package com.ecommerce.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

// 리프레시 토큰 — opaque 토큰의 SHA-256 해시만 저장한다(평문 미저장).
// 회전 시 기존 행을 revoked 처리하고 새 행을 발급한다.
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_token_hash", columnList = "tokenHash", unique = true)
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_id", nullable = false)
    private Admin admin;

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

    public RefreshToken(Admin admin, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.admin = admin;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.createdAt = createdAt;
    }

    public void revoke() {
        this.revoked = true;
    }

    public Long getId() { return id; }
    public Admin getAdmin() { return admin; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: RefreshTokenRepository 작성**

`backend/src/main/java/com/ecommerce/auth/RefreshTokenRepository.java`:
```java
package com.ecommerce.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 재사용 탐지 시 해당 admin의 살아있는 토큰을 일괄 폐기
    // flushAutomatically=true: 벌크 쿼리 전에 영속 엔티티 변경(revoke 등)을 먼저 flush해 유실 방지
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken r set r.revoked = true where r.admin = :admin and r.revoked = false")
    void revokeAllByAdmin(@Param("admin") Admin admin);

    // lazy 정리 — 해당 admin의 만료된 행 삭제
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken r where r.admin = :admin and r.expiresAt < :now")
    void deleteExpiredByAdmin(@Param("admin") Admin admin, @Param("now") Instant now);
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/RefreshToken.java \
        backend/src/main/java/com/ecommerce/auth/RefreshTokenRepository.java
git commit -m "feat: RefreshToken 엔티티·리포지토리 추가"
```

---

## Task 3: RefreshTokenService (TDD)

opaque 토큰 발급(해시 저장), 회전, 재사용 탐지, 폐기를 구현한다. 시각을 `Supplier<Instant>`로 주입해 만료를 결정적으로 검증한다. Repository가 실제 DB를 타므로 `@DataJpaTest`로 H2 위에서 서비스를 수동 생성해 테스트한다.

**Files:**
- Create: `backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java`
- Create: `backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java`

- [ ] **Step 1: 실패하는 테스트 작성**

`backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java`:
```java
package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.common.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class RefreshTokenServiceTest {

    private static final long SEVEN_DAYS = 604800L;

    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AdminRepository adminRepository;

    private Admin admin;

    @BeforeEach
    void setUp() {
        admin = adminRepository.save(new Admin("admin", "encoded-pw"));
    }

    private RefreshTokenService service(Supplier<Instant> clock) {
        return new RefreshTokenService(refreshTokenRepository, SEVEN_DAYS, clock);
    }

    @Test
    void 발급한_토큰으로_회전하면_새_토큰을_반환하고_옛_토큰은_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(admin);

        RotationResult result = service.rotate(first.token());

        assertThat(result.refresh().token()).isNotEqualTo(first.token());
        // 옛 토큰 재제출은 재사용으로 거부된다
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 만료된_토큰으로_회전하면_거부된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        RefreshTokenService service = service(now::get);
        IssuedToken token = service.issue(admin);

        now.set(now.get().plusSeconds(SEVEN_DAYS + 1));

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 폐기된_토큰_재사용시_해당_admin의_모든_토큰이_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(admin);
        RotationResult rotated = service.rotate(first.token()); // first revoked, second 발급

        // 폐기된 first 재제출 → 재사용 탐지 → second까지 전부 무효
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);

        // 살아있던 second 토큰도 이제 거부되어야 한다
        assertThatThrownBy(() -> service.rotate(rotated.refresh().token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 존재하지_않는_토큰으로_회전하면_거부된다() {
        RefreshTokenService service = service(Instant::now);
        assertThatThrownBy(() -> service.rotate("nonexistent-token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 폐기_후에는_회전이_거부된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken token = service.issue(admin);

        service.revoke(token.token());

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void revoke는_존재하지_않는_토큰에도_조용히_통과한다() {
        RefreshTokenService service = service(Instant::now);
        service.revoke("nonexistent"); // 예외 없이 통과
    }
}
```

- [ ] **Step 2: 테스트 실행으로 컴파일 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.RefreshTokenServiceTest"`
Expected: FAIL (RefreshTokenService 클래스 없음 → 컴파일 에러)

- [ ] **Step 3: RefreshTokenService 구현**

`backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java`:
```java
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
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.RefreshTokenServiceTest"`
Expected: PASS (6개 모두)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java \
        backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java
git commit -m "feat: RefreshTokenService 추가(발급·회전·재사용 탐지·폐기)"
```

---

## Task 4: DTO 교체 + AuthService에 refresh 통합

`LoginResponse`를 `TokenResponse`로 대체하고, `AuthService.login`이 access+refresh를 함께 발급하도록 한다. access JWT 발급 로직을 login/refresh가 공유하도록 추출한다.

**Files:**
- Create: `backend/src/main/java/com/ecommerce/auth/dto/TokenResponse.java`
- Create: `backend/src/main/java/com/ecommerce/auth/dto/RefreshRequest.java`
- Delete: `backend/src/main/java/com/ecommerce/auth/dto/LoginResponse.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/AuthService.java`
- Modify: `backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java`
- Modify: `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java`

- [ ] **Step 1: TokenResponse / RefreshRequest 작성, LoginResponse 삭제**

`backend/src/main/java/com/ecommerce/auth/dto/TokenResponse.java`:
```java
package com.ecommerce.auth.dto;

import java.time.Instant;

// 인증 응답 — access(JWT)와 refresh(opaque) 토큰 및 각 만료 시각
public record TokenResponse(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt
) {
}
```

`backend/src/main/java/com/ecommerce/auth/dto/RefreshRequest.java`:
```java
package com.ecommerce.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 리프레시/로그아웃 요청 — refresh 토큰 자체가 자격 증명
public record RefreshRequest(
        @NotBlank String refreshToken
) {
}
```

`LoginResponse.java`를 삭제한다:
```bash
git rm backend/src/main/java/com/ecommerce/auth/dto/LoginResponse.java
```

- [ ] **Step 2: AuthService 수정 — refresh 발급 + access 인코딩 추출**

`AuthService.java`를 다음으로 교체한다(refresh 발급 통합, login/refresh 공용 access 인코딩, 쓰기 트랜잭션 명시):
```java
package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
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

    // 리프레시: refresh 회전 후 새 access + refresh 발급
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        RotationResult result = refreshTokenService.rotate(refreshToken);
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plusSeconds(expirationSeconds);
        String accessToken = encodeAccess(result.admin(), now, accessExpiresAt);
        return new TokenResponse(accessToken, accessExpiresAt,
                result.refresh().token(), result.refresh().expiresAt());
    }

    // 로그아웃: refresh 폐기
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private TokenResponse issueTokens(Admin admin) {
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plusSeconds(expirationSeconds);
        String accessToken = encodeAccess(admin, now, accessExpiresAt);
        IssuedToken refresh = refreshTokenService.issue(admin);
        return new TokenResponse(accessToken, accessExpiresAt, refresh.token(), refresh.expiresAt());
    }

    private String encodeAccess(Admin admin, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(admin.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
```

- [ ] **Step 3: SecurityProtectionTest의 토큰 추출 필드명 변경**

`SecurityProtectionTest.java` 102번째 줄 부근의 토큰 추출을 `token` → `accessToken`으로 바꾼다. 기존:
```java
        String token = objectMapper.readTree(response).get("token").asString();
```
변경:
```java
        String token = objectMapper.readTree(response).get("accessToken").asString();
```

- [ ] **Step 4: AuthControllerTest의 로그인 응답 검증 필드 변경**

`AuthControllerTest.java`의 `올바른_계정으로_로그인하면_토큰을_발급한다` 테스트에서 응답 JSON 필드 검증을 `token` → `accessToken`으로 바꾸고 `refreshToken` 존재를 추가한다. 해당 테스트 메서드의 `jsonPath`/검증 라인을 다음 형태로 맞춘다(기존 코드의 검증 표현을 유지하되 필드명만 교체):
```java
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
```
(기존에 `$.token`을 검증하던 부분이 있으면 `$.accessToken`으로 교체한다. `$.expiresAt`을 검증했다면 `$.accessExpiresAt`으로 교체한다.)

- [ ] **Step 5: 전체 백엔드 테스트 실행**

Run: `cd backend && ./gradlew test`
Expected: PASS (로그인 응답 구조 변경 반영, refresh 발급이 로그인 경로에 포함되어도 회귀 없음)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/dto/TokenResponse.java \
        backend/src/main/java/com/ecommerce/auth/dto/RefreshRequest.java \
        backend/src/main/java/com/ecommerce/auth/AuthService.java \
        backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java \
        backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java \
        backend/src/main/java/com/ecommerce/auth/dto/LoginResponse.java
git commit -m "feat: 로그인 시 access+refresh 발급·TokenResponse 도입(LoginResponse 대체)"
```

---

## Task 5: /refresh·/logout 엔드포인트 + 보안 설정

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/auth/AuthController.java`
- Modify: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java`
- Modify: `backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java`

- [ ] **Step 1: AuthController에 /refresh·/logout 추가 (클래스 매핑 변경)**

`AuthController.java`를 다음으로 교체한다(클래스 매핑을 `/api/admin`으로 올리고 메서드별 경로 부여):
```java
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
```

- [ ] **Step 2: SecurityConfig에 /refresh·/logout permitAll**

`SecurityConfig.java`의 `authorizeHttpRequests` 블록에서 로그인 permitAll 라인을 refresh·logout까지 확장한다. 기존:
```java
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
```
변경:
```java
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/login", "/api/admin/refresh", "/api/admin/logout").permitAll()
```

- [ ] **Step 3: 컴파일·기존 테스트 회귀 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS (login 경로가 `/api/admin/login`으로 동일하게 유지됨 — 클래스 매핑 변경 후에도 최종 경로 불변)

- [ ] **Step 4: AuthControllerTest에 refresh·logout 테스트 추가**

`AuthControllerTest.java`에 다음 테스트를 추가한다(로그인으로 토큰을 얻고, 응답에서 refreshToken을 꺼내 회전·재사용·로그아웃을 검증). import에 추가:
```java
import tools.jackson.databind.JsonNode;
```
테스트 메서드:
```java
    @Test
    void 리프레시하면_새_토큰을_발급하고_옛_refresh는_무효화된다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String refreshToken = loginAndGetRefreshToken("admin", "admin1234");

        // 정상 리프레시 → 새 토큰
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());

        // 옛 refresh 재사용 → 401
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_refresh가_폐기되어_이후_리프레시가_거부된다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String refreshToken = loginAndGetRefreshToken("admin", "admin1234");
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/admin/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    // 로그인해서 응답 바디의 refreshToken을 꺼낸다
    private String loginAndGetRefreshToken(String username, String password) throws Exception {
        String loginBody = objectMapper.writeValueAsString(
                Map.of("username", username, "password", password));
        String response = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.get("refreshToken").asString();
    }
```

- [ ] **Step 5: 전체 테스트 실행**

Run: `cd backend && ./gradlew test`
Expected: PASS (신규 refresh·logout 테스트 포함 전체 통과)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/AuthController.java \
        backend/src/main/java/com/ecommerce/common/SecurityConfig.java \
        backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java
git commit -m "feat: /api/admin/refresh·/logout 엔드포인트 추가(회전·재사용 탐지·폐기)"
```

---

## Task 6: 프론트 — 쿠키 2개 운영 + 자동 갱신

> **Next.js 16 필독:** 코드 작성 전 `frontend/node_modules/next/dist/docs/` 에서 (1) `cookies()`의 async set/delete, (2) Route Handler의 `NextResponse.redirect` + `response.cookies.set/delete`, (3) middleware(`proxy.ts`)의 `NextRequest.cookies`를 확인한다. 본 플랜 코드가 현재 버전과 다르면 그 형태로 보정한다.

**Files:**
- Create: `frontend/src/lib/auth-cookies.ts`
- Create: `frontend/src/app/admin/refresh/route.ts`
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/app/admin/login/actions.ts`
- Modify: `frontend/src/app/admin/logout/route.ts`
- Modify: `frontend/src/app/admin/suppliers/page.tsx`
- Modify: `frontend/src/app/admin/products/page.tsx`
- Modify: `frontend/src/proxy.ts`

- [ ] **Step 1: lib/api.ts — TokenResponse 타입 + refresh/logout 함수**

`lib/api.ts`에서 `LoginResponse` 인터페이스를 `TokenResponse`로 교체하고, `login` 반환 타입을 바꾸고, `refresh`/`logout`을 추가한다.

`LoginResponse` 인터페이스를 다음으로 교체:
```ts
export interface TokenResponse {
  accessToken: string;
  accessExpiresAt: string;
  refreshToken: string;
  refreshExpiresAt: string;
}
```

`login` 함수의 반환 타입 `Promise<LoginResponse>`를 `Promise<TokenResponse>`로 바꾼다(본문은 그대로, 마지막 `return res.json() as Promise<TokenResponse>`).

파일 끝(어드민 함수 아래)에 추가:
```ts
// 리프레시 — refresh 토큰으로 새 access+refresh 발급
export async function refresh(refreshToken: string): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/admin/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
  if (!res.ok) {
    throw new ApiError(res.status, `리프레시 실패 (${res.status})`);
  }
  return res.json() as Promise<TokenResponse>;
}

// 로그아웃 — refresh 토큰 서버 폐기(204). 실패해도 쿠키 삭제는 호출자가 진행
export async function logout(refreshToken: string): Promise<void> {
  await fetch(`${API_BASE}/api/admin/logout`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
}
```

- [ ] **Step 2: lib/auth-cookies.ts — 쿠키 이름/옵션 공유**

`frontend/src/lib/auth-cookies.ts`:
```ts
// 어드민 인증 쿠키 — access/refresh 공통 이름과 옵션.
// Server Action(cookies())과 Route Handler(response.cookies)가 옵션 객체를 공유한다.

export const ACCESS_COOKIE = "admin_token";
export const REFRESH_COOKIE = "admin_refresh";

// ISO 만료 시각 → 남은 초(maxAge). 과거면 0.
function maxAgeSeconds(expiresAt: string): number {
  return Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
}

// httpOnly 쿠키 옵션 — secure는 운영에서만(로컬 http 개발 허용)
export function authCookieOptions(expiresAt: string) {
  return {
    httpOnly: true as const,
    sameSite: "lax" as const,
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: maxAgeSeconds(expiresAt),
  };
}
```

- [ ] **Step 3: login/actions.ts — 쿠키 2개 set**

`login/actions.ts`를 다음으로 교체한다:
```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { login } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE, authCookieOptions } from "@/lib/auth-cookies";

// 로그인 Server Action — 백엔드 로그인 호출 후 access·refresh httpOnly 쿠키 설정.
// 실패 시 에러 메시지 문자열 반환(폼 표시), 성공 시 /admin으로 리다이렉트.
export async function loginAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const username = String(formData.get("username") ?? "");
  const password = String(formData.get("password") ?? "");

  let tokens;
  try {
    tokens = await login(username, password);
  } catch (err) {
    return err instanceof Error ? err.message : "로그인에 실패했습니다.";
  }

  // cookies()는 Next.js 16에서 async — await 필수
  const store = await cookies();
  store.set(ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
  store.set(REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));

  // redirect는 내부적으로 예외를 던지므로 try/catch 밖에서 호출한다
  redirect("/admin");
}
```

- [ ] **Step 4: admin/refresh/route.ts — 자동 갱신 Route Handler**

`frontend/src/app/admin/refresh/route.ts`:
```ts
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { refresh } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE, authCookieOptions } from "@/lib/auth-cookies";

// access 만료 시 자동 갱신 — admin_refresh 쿠키로 백엔드 refresh 호출.
// 성공: 새 access/refresh 쿠키 set 후 next 경로로. 실패: /admin/logout(쿠키 삭제→로그인).
export async function GET(request: Request) {
  const url = new URL(request.url);
  const next = url.searchParams.get("next") ?? "/admin";
  // 오픈 리다이렉트 방지 — /admin 하위 경로만 허용
  const safeNext = next.startsWith("/admin") ? next : "/admin";

  const store = await cookies();
  const refreshToken = store.get(REFRESH_COOKIE)?.value;
  if (!refreshToken) {
    return NextResponse.redirect(new URL("/admin/logout", request.url), 303);
  }

  try {
    const tokens = await refresh(refreshToken);
    const response = NextResponse.redirect(new URL(safeNext, request.url), 303);
    response.cookies.set(ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
    response.cookies.set(REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));
    return response;
  } catch {
    return NextResponse.redirect(new URL("/admin/logout", request.url), 303);
  }
}
```

- [ ] **Step 5: logout/route.ts — 백엔드 폐기 호출 + 쿠키 2개 삭제**

`logout/route.ts`를 다음으로 교체한다:
```ts
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { logout } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "@/lib/auth-cookies";

// 로그아웃 — refresh를 백엔드에서 폐기하고 access·refresh 쿠키를 삭제 후 로그인 페이지로.
// 서버 컴포넌트는 렌더 중 쿠키를 못 지우므로, 401 처리도 이 경로로 리다이렉트해 잔존 쿠키를 제거한다.
async function clearAndRedirect(request: Request) {
  const store = await cookies();
  const refreshToken = store.get(REFRESH_COOKIE)?.value;
  if (refreshToken) {
    // 백엔드 폐기 실패가 로그아웃을 막지 않도록 무시(쿠키 삭제는 진행)
    await logout(refreshToken).catch(() => {});
  }
  // 303 See Other: POST → GET 리다이렉트 표준 응답
  const response = NextResponse.redirect(new URL("/admin/login", request.url), 303);
  response.cookies.delete(ACCESS_COOKIE);
  response.cookies.delete(REFRESH_COOKIE);
  return response;
}

export function GET(request: Request) {
  return clearAndRedirect(request);
}

export function POST(request: Request) {
  return clearAndRedirect(request);
}
```

- [ ] **Step 6: suppliers/products 페이지의 401 처리를 /admin/refresh 경유로**

`suppliers/page.tsx`의 401 분기를 자동 갱신 경로로 바꾼다. 기존:
```tsx
    // 401 = 토큰 만료/무효 → 쿠키 삭제 후 로그인 페이지로 (/admin/logout 경유)
    if (err instanceof ApiError && err.status === 401) {
      redirect("/admin/logout");
    }
```
변경:
```tsx
    // 401 = access 만료/무효 → 자동 갱신 시도(/admin/refresh). 갱신 실패 시 그쪽에서 로그아웃 처리.
    if (err instanceof ApiError && err.status === 401) {
      redirect("/admin/refresh?next=/admin/suppliers");
    }
```

`products/page.tsx`도 동일하게 401 분기를 바꾼다(next 경로만 `/admin/products`로):
```tsx
    if (err instanceof ApiError && err.status === 401) {
      redirect("/admin/refresh?next=/admin/products");
    }
```

- [ ] **Step 7: proxy.ts — 보호 제외 경로 + refresh 분기**

`proxy.ts`를 다음으로 교체한다(리다이렉트 루프 방지를 위해 인증 경로 3개는 통과, access 없고 refresh 있으면 자동 갱신으로):
```ts
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// 인증 자체 경로는 보호하지 않는다(리다이렉트 루프 방지)
const PUBLIC_PATHS = ["/admin/login", "/admin/refresh", "/admin/logout"];

// 어드민 경로 보호 — access 쿠키 유무로 1차 판정.
// access 없음 + refresh 있음 → 자동 갱신(/admin/refresh)로. 둘 다 없음 → 로그인.
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (PUBLIC_PATHS.includes(pathname)) {
    return NextResponse.next();
  }

  if (request.cookies.has("admin_token")) {
    return NextResponse.next();
  }

  if (request.cookies.has("admin_refresh")) {
    const url = new URL("/admin/refresh", request.url);
    url.searchParams.set("next", pathname);
    return NextResponse.redirect(url);
  }

  return NextResponse.redirect(new URL("/admin/login", request.url));
}

export const config = {
  matcher: "/admin/:path*",
};
```

- [ ] **Step 8: 프로덕션 빌드 + 린트 검증**

Run: `cd frontend && npm run build && npm run lint`
Expected: PASS (`/admin/refresh` 라우트 등록, 타입 에러 없음)

- [ ] **Step 9: Commit**

```bash
git add frontend/src/lib/auth-cookies.ts \
        frontend/src/app/admin/refresh/route.ts \
        frontend/src/lib/api.ts \
        frontend/src/app/admin/login/actions.ts \
        frontend/src/app/admin/logout/route.ts \
        frontend/src/app/admin/suppliers/page.tsx \
        frontend/src/app/admin/products/page.tsx \
        frontend/src/proxy.ts
git commit -m "feat: 프론트 access+refresh 쿠키 2개 운영·401 자동 갱신(/admin/refresh)"
```

---

## Task 7: 문서 동기화 + 최종 검증

**Files:**
- Modify: `README.md`
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: README 보안 한계 갱신**

`README.md`의 "보안 한계" 섹션에서:
- "남은 한계"의 **리프레시 토큰·토큰 무효화** 항목을 "해결됨(2026-06-06 토큰 수명 주기 사이클)"로 이동/표시한다(access 15분 + refresh 7일 회전, 로그아웃 시 서버 폐기).
- 남는 한계로 다음을 유지/추가한다: **로그인 시도 제한 인메모리(다중 인스턴스)**, **refresh 회전 동시성(race) — 단일 어드민 가정**.

- [ ] **Step 2: ROADMAP 동기화**

`docs/ROADMAP.md`에서:
- "완료된 사이클" 표에 행 추가: 토큰 수명 주기(2026-06-06, 리프레시+무효화), 브랜치 `feature/token-lifecycle`(머지 상태는 실제에 맞게 — `git log --oneline origin/main..` 확인 후 기재).
- "후보 1: 보안 보완 2차 — 토큰 수명 주기" 표에서 해소된 2개 항목(리프레시, 무효화)을 제거하고, 남은 1개(시도 제한 인메모리 → Redis)만 별도 후보로 남긴다.

- [ ] **Step 3: 문서 일관성 확인**

Run: `grep -n "리프레시\|refresh\|무효화\|회전\|시도 제한" README.md docs/ROADMAP.md`
Expected: 해소/남음 분류가 두 문서에서 모순 없이 일치.

- [ ] **Step 4: 백엔드·프론트 최종 검증**

Run: `cd backend && ./gradlew test && cd ../frontend && npm run build`
Expected: 양쪽 모두 PASS.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/ROADMAP.md
git commit -m "docs: 토큰 수명 주기 해소 항목 반영(README·ROADMAP 동기화)"
```

---

## 최종 검증 (Definition of Done)

- [ ] `cd backend && ./gradlew test` 전체 통과(RefreshTokenServiceTest 6 + refresh/logout 컨트롤러 테스트 포함)
- [ ] `cd frontend && npm run build` 통과(`/admin/refresh` 라우트 등록)
- [ ] 로그인 시 `admin_token`(15분)·`admin_refresh`(7일) 쿠키 2개 set, 둘 다 HttpOnly
- [ ] access 만료(401) → `/admin/refresh` 자동 갱신 → 원래 페이지 복귀
- [ ] 로그아웃 → 백엔드 refresh 폐기 + 쿠키 2개 삭제 + `/admin/login`
- [ ] 폐기된 refresh 재사용 → 401 + 해당 admin 전체 무효
- [ ] README/ROADMAP에서 리프레시·무효화가 "해결됨"으로 일치
```
