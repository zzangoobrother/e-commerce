# 고객 회원가입/로그인(Customer Auth) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 스토어 고객(Customer) 계정의 회원가입·로그인·로그아웃·토큰(access+refresh) 발급을 도입하고, JWT role 클레임으로 어드민/고객 권한을 분리한다.

**Architecture:** `Customer`를 `Admin`과 별도 엔티티로 두고(email 식별자), 검증된 `RefreshTokenService`(회전·재사용 탐지)를 `ownerType`+`ownerId` 다형 소유로 일반화해 어드민·고객이 공유한다. access JWT에 `role` 클레임을 넣고 `/api/admin/**`를 `hasRole('ADMIN')`로 강화해 고객 토큰의 어드민 접근을 차단한다. 프론트는 `customer_*` httpOnly 쿠키 2개로 가입 시 auto-login한다.

**Tech Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security(oauth2-resource-server, HS256) · JPA/Hibernate · H2(test)/MySQL(운영) · JUnit5/@DataJpaTest/MockMvc/Jackson3 · Next.js 16.2.6(Server Action, Route Handler) · React 19

설계 문서: `docs/superpowers/specs/2026-06-07-customer-auth-design.md`

---

## 스펙 대비 의도적 편차 (실행자 필독)

1. **`CustomerAuthServiceTest` 미작성**: 이 저장소는 service 단위 테스트 관행이 없다(`AuthService`도 `AuthControllerTest`로만 검증). 일관성을 위해 `CustomerAuthService`도 `CustomerAuthControllerTest`(MockMvc)로 검증한다.
2. **프론트 `customerRefresh` 미작성(YAGNI)**: 이번 사이클엔 고객 보호 페이지·자동 갱신 라우트가 없어 호출자가 없다. 백엔드 `/api/store/auth/refresh` 엔드포인트는 토큰 인프라 완결성을 위해 만들고 테스트하되, 프론트 wrapper는 보호 페이지 사이클로 미룬다.
3. **SecurityConfig store-auth permitAll 추가 불필요**: `/api/store/auth/**`는 `/api/admin/**`에 매칭되지 않아 기존 `.anyRequest().permitAll()`로 이미 개방된다. 실제 변경은 어드민 매처를 `hasRole('ADMIN')`로 바꾸고 role 변환기를 추가하는 것뿐이다.

---

## 파일 구조

**백엔드 신규**
- `backend/src/main/java/com/ecommerce/auth/Customer.java` — 고객 엔티티(email 식별자)
- `backend/src/main/java/com/ecommerce/auth/CustomerRepository.java` — findByEmail/existsByEmail
- `backend/src/main/java/com/ecommerce/auth/OwnerType.java` — refresh 토큰 소유자 종류 enum
- `backend/src/main/java/com/ecommerce/auth/CustomerAuthService.java` — 가입/로그인/리프레시/로그아웃
- `backend/src/main/java/com/ecommerce/auth/CustomerAuthController.java` — `/api/store/auth/**`
- `backend/src/main/java/com/ecommerce/auth/dto/RegisterRequest.java`
- `backend/src/main/java/com/ecommerce/auth/dto/CustomerLoginRequest.java`
- `backend/src/main/java/com/ecommerce/common/ConflictException.java` — 409 매핑
- `backend/src/main/java/com/ecommerce/common/ClientIp.java` — X-Forwarded-For 추출 유틸(DRY)
- `backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java`

**백엔드 수정**
- `RefreshToken.java` — `@ManyToOne Admin` → `ownerType`+`ownerId`
- `RefreshTokenRepository.java` — owner 기준 쿼리
- `RefreshTokenService.java` — `TokenOwner` 기반 일반화
- `AuthService.java` — admin owner로 발급/회전, access에 `role=ADMIN` 클레임
- `AuthController.java` — `ClientIp` 유틸 사용
- `common/SecurityConfig.java` — `hasRole('ADMIN')` + `JwtAuthenticationConverter`
- `common/GlobalExceptionHandler.java` — `ConflictException` 핸들러
- `backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java` — TokenOwner 기준 재작성
- `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java` — mock JWT 권한 + 고객 차단 테스트

**프론트 신규**
- `frontend/src/app/register/page.tsx`, `frontend/src/app/register/actions.ts`
- `frontend/src/app/login/page.tsx`, `frontend/src/app/login/actions.ts`
- `frontend/src/app/logout/route.ts`

**프론트 수정**
- `frontend/src/lib/auth-cookies.ts` — 고객 쿠키 이름 추가
- `frontend/src/lib/api.ts` — registerCustomer/customerLogin/customerLogout
- `frontend/src/app/page.tsx` — 헤더 로그인/로그아웃 링크

**문서**: `README.md`, `docs/ROADMAP.md`

---

## Task 1: Customer 엔티티 + Repository

**Files:**
- Create: `backend/src/main/java/com/ecommerce/auth/Customer.java`
- Create: `backend/src/main/java/com/ecommerce/auth/CustomerRepository.java`

- [ ] **Step 1: Customer 엔티티 작성**

`Admin`과 동일 형태(식별자만 email). `backend/src/main/java/com/ecommerce/auth/Customer.java`:
```java
package com.ecommerce.auth;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 스토어 고객 계정 — 비밀번호는 BCrypt 해시로 저장. 로그인 식별자는 email.
@Entity
@Table(name = "customers")
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그인 식별자 — 중복 불가
    @Column(nullable = false, unique = true)
    private String email;

    // BCrypt 해시된 비밀번호 (평문 저장 금지)
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected Customer() {
    }

    public Customer(String email, String encodedPassword) {
        this.email = email;
        this.password = encodedPassword;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: CustomerRepository 작성**

`backend/src/main/java/com/ecommerce/auth/CustomerRepository.java`:
```java
package com.ecommerce.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/Customer.java \
        backend/src/main/java/com/ecommerce/auth/CustomerRepository.java
git commit -m "feat: Customer 엔티티·리포지토리 추가(email 식별자)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: refresh 토큰 다형 소유 일반화 (TDD)

`RefreshToken`을 Admin 직접 결합에서 `ownerType`+`ownerId` 다형 소유로 바꾸고, `RefreshTokenService`를 `TokenOwner` 기반으로 일반화한다. 어드민 경로(`AuthService`)도 새 API로 갱신한다. **이 Task에서는 `SecurityConfig`를 건드리지 않는다** — 어드민 매처가 아직 `.authenticated()`라 access에 role 클레임이 붙어도 기존 테스트가 모두 통과한다.

**Files:**
- Modify: `backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java`
- Create: `backend/src/main/java/com/ecommerce/auth/OwnerType.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/RefreshToken.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/RefreshTokenRepository.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/AuthService.java`

- [ ] **Step 1: RefreshTokenServiceTest를 TokenOwner 기준으로 재작성(실패하는 테스트)**

`RefreshTokenServiceTest.java`를 다음으로 교체한다. 더 이상 `Admin`을 저장하지 않고 `TokenOwner` 리터럴을 쓴다. 핵심 추가: `재사용_탐지는_다른_owner의_토큰을_무효화하지_않는다`가 `(ownerType, ownerId)` 격리를 검증한다.
```java
package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.auth.RefreshTokenService.TokenOwner;
import com.ecommerce.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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
    private static final TokenOwner ADMIN_1 = new TokenOwner(OwnerType.ADMIN, 1L);
    private static final TokenOwner CUSTOMER_1 = new TokenOwner(OwnerType.CUSTOMER, 1L);

    @Autowired RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService service(Supplier<Instant> clock) {
        return new RefreshTokenService(refreshTokenRepository, SEVEN_DAYS, clock);
    }

    @Test
    void 발급한_토큰의_만료시각은_발급시각에_7일을_더한_값이다() {
        Instant fixed = Instant.parse("2026-06-06T00:00:00Z");
        RefreshTokenService service = service(() -> fixed);

        IssuedToken token = service.issue(ADMIN_1);

        assertThat(token.expiresAt()).isEqualTo(fixed.plusSeconds(SEVEN_DAYS));
    }

    @Test
    void 발급한_토큰으로_회전하면_새_토큰을_반환하고_옛_토큰은_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(ADMIN_1);

        RotationResult result = service.rotate(first.token());

        assertThat(result.refresh().token()).isNotEqualTo(first.token());
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 회전_결과의_owner는_토큰을_발급한_owner와_동일하다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(CUSTOMER_1);

        RotationResult result = service.rotate(first.token());

        assertThat(result.owner()).isEqualTo(CUSTOMER_1);
    }

    @Test
    void 만료된_토큰으로_회전하면_거부된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        RefreshTokenService service = service(now::get);
        IssuedToken token = service.issue(ADMIN_1);

        now.set(now.get().plusSeconds(SEVEN_DAYS + 1));

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 폐기된_토큰_재사용시_해당_owner의_모든_토큰이_무효화된다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken first = service.issue(ADMIN_1);
        RotationResult rotated = service.rotate(first.token());

        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> service.rotate(rotated.refresh().token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 재사용_탐지는_다른_owner의_토큰을_무효화하지_않는다() {
        RefreshTokenService service = service(Instant::now);
        // 같은 id(1)지만 타입이 다른 owner — (ownerType, ownerId) 격리를 검증
        IssuedToken customerToken = service.issue(CUSTOMER_1);
        IssuedToken first = service.issue(ADMIN_1);
        service.rotate(first.token());
        assertThatThrownBy(() -> service.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);

        // CUSTOMER_1 토큰은 영향받지 않고 정상 회전된다
        RotationResult result = service.rotate(customerToken.token());
        assertThat(result.owner()).isEqualTo(CUSTOMER_1);
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
        IssuedToken token = service.issue(ADMIN_1);

        service.revoke(token.token());

        assertThatThrownBy(() -> service.rotate(token.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void revoke는_존재하지_않는_토큰에도_조용히_통과한다() {
        RefreshTokenService service = service(Instant::now);
        service.revoke("nonexistent");
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `cd backend && ./gradlew compileTestJava`
Expected: FAIL — `OwnerType`, `TokenOwner`, `issue(TokenOwner)`, `result.owner()`가 아직 없어 컴파일 에러.

- [ ] **Step 3: OwnerType enum 작성**

`backend/src/main/java/com/ecommerce/auth/OwnerType.java`:
```java
package com.ecommerce.auth;

// refresh 토큰 소유자 종류 — 단일 refresh_tokens 테이블을 어드민/고객이 공유한다.
public enum OwnerType {
    ADMIN,
    CUSTOMER
}
```

- [ ] **Step 4: RefreshToken을 다형 소유로 교체**

`RefreshToken.java`를 다음으로 교체한다(`@ManyToOne Admin` 제거, `ownerType`+`ownerId` 추가, `(ownerType, ownerId)` 인덱스 추가):
```java
package com.ecommerce.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

// 리프레시 토큰 — opaque 토큰의 SHA-256 해시만 저장한다(평문 미저장).
// 소유자는 (ownerType, ownerId) 다형 참조 — 어드민/고객이 단일 테이블을 공유한다(FK 없음).
// 회전 시 기존 행을 revoked 처리하고 새 행을 발급한다.
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_token_hash", columnList = "tokenHash", unique = true),
        @Index(name = "idx_refresh_token_owner", columnList = "ownerType,ownerId")
})
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(nullable = false)
    private Long ownerId;

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

    public RefreshToken(OwnerType ownerType, Long ownerId, String tokenHash, Instant expiresAt, Instant createdAt) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = false;
        this.createdAt = createdAt;
    }

    public void revoke() {
        this.revoked = true;
    }

    public Long getId() { return id; }
    public OwnerType getOwnerType() { return ownerType; }
    public Long getOwnerId() { return ownerId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: RefreshTokenRepository를 owner 기준으로 교체**

`RefreshTokenRepository.java`를 다음으로 교체한다(`@EntityGraph` 제거 — 스칼라 필드라 불필요):
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
```

- [ ] **Step 6: RefreshTokenService를 TokenOwner 기반으로 교체**

`RefreshTokenService.java`를 다음으로 교체한다:
```java
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
    private final long refreshSeconds;
    private final Supplier<Instant> clock;
    private final SecureRandom random = new SecureRandom();

    // Spring DI용 — 생성자가 2개이므로 @Autowired로 명시
    @Autowired
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
            // 이미 폐기된 토큰 재제출 = 탈취 정황 → 해당 owner의 살아있는 토큰을 전부 폐기
            repository.revokeAllByOwner(stored.getOwnerType(), stored.getOwnerId());
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

    // 토큰 소유자 신원 — 어드민/고객 + 엔티티 id
    public record TokenOwner(OwnerType type, Long id) {}

    // 발급 결과 — 평문 토큰과 만료 시각
    public record IssuedToken(String token, Instant expiresAt) {}

    // 회전 결과 — 토큰 소유자와 새 refresh
    public record RotationResult(TokenOwner owner, IssuedToken refresh) {}
}
```

- [ ] **Step 7: AuthService를 새 API로 갱신 + role 클레임 추가**

> 순서 주의: `RefreshTokenService` 교체(Step 6) 직후엔 `AuthService`가 아직 옛 API(`issue(admin)`/`result.admin()`)를 참조해 **모듈 전체 컴파일이 깨진다**. 테스트를 돌리기 전에 반드시 `AuthService`를 먼저 갱신한다.

`AuthService.java`를 다음으로 교체한다(rotate 결과 owner 검증·admin 로드, access에 `role=ADMIN`):
```java
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

    // 로그아웃: refresh 폐기
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
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
```

- [ ] **Step 8: RefreshTokenServiceTest 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.RefreshTokenServiceTest"`
Expected: PASS (8개 모두). 이 시점엔 `AuthService`까지 새 API로 갱신돼 모듈이 정상 컴파일된다.

- [ ] **Step 9: 전체 백엔드 테스트로 회귀 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — `AuthControllerTest`·`SecurityProtectionTest` 포함 전부 통과(SecurityConfig 미변경이라 role 클레임이 붙어도 영향 없음).

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/OwnerType.java \
        backend/src/main/java/com/ecommerce/auth/RefreshToken.java \
        backend/src/main/java/com/ecommerce/auth/RefreshTokenRepository.java \
        backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java \
        backend/src/main/java/com/ecommerce/auth/AuthService.java \
        backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java
git commit -m "refactor: refresh 토큰 다형 소유 일반화(ownerType+ownerId)·access role 클레임

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 권한 분리 — role 강제 (보안 구멍 차단)

`/api/admin/**`를 `hasRole('ADMIN')`로 강화하고 `role` 클레임을 권한으로 변환한다. 고객 토큰의 어드민 접근 차단을 회귀 테스트로 고정한다.

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java`
- Modify: `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java`

- [ ] **Step 1: SecurityProtectionTest 갱신 — mock JWT 권한 + 고객 차단 테스트(실패 유도)**

`SecurityProtectionTest.java`를 다음 두 군데 수정한다.

(1) import에 추가:
```java
import org.springframework.security.core.authority.SimpleGrantedAuthority;
```

(2) `모의_JWT로_어드민_API에_접근한다` 테스트의 `.with(jwt())`를 권한 포함으로 교체:
```java
    @Test
    void 모의_JWT로_어드민_API에_접근한다() throws Exception {
        mockMvc.perform(get("/api/admin/suppliers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }
```

(3) 클래스 끝(마지막 `}` 직전)에 고객 차단 테스트 추가:
```java
    @Test
    void 고객_JWT로_어드민_API_접근시_차단된다() throws Exception {
        // role=CUSTOMER 클레임을 가진 실제 서명 JWT — 인증은 되지만 ADMIN 권한이 없어 403
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("customer@example.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("role", "CUSTOMER")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String customerToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
    }
```

- [ ] **Step 2: 테스트 실행으로 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.common.SecurityProtectionTest"`
Expected: FAIL — `고객_JWT로_어드민_API_접근시_차단된다`가 실패(현재 `.authenticated()`라 고객 토큰도 200). `모의_JWT…`는 통과(아직 hasRole 아님이라 권한 무관).

- [ ] **Step 3: SecurityConfig에 hasRole + role 변환기 적용**

`SecurityConfig.java`를 다음으로 교체한다(어드민 매처 `hasRole('ADMIN')`, `JwtAuthenticationConverter`로 `role`→`ROLE_*`):
```java
package com.ecommerce.common;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

// 보안 설정 — JWT 발급/검증 빈 + 보안 필터 체인
@Configuration
public class SecurityConfig {

    // HS256 서명용 시크릿 (최소 32바이트)
    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 토큰 기반 stateless API — CSRF/세션 불필요
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 어드민 로그인/리프레시/로그아웃은 인증 없이 허용 (refresh 토큰이 자격 증명)
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/login", "/api/admin/refresh", "/api/admin/logout").permitAll()
                        // 나머지 어드민 API는 ADMIN 권한 필수 (고객 토큰 접근 차단)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 스토어 API·고객 인증(/api/store/auth/**) 등 그 외는 모두 개방
                        .anyRequest().permitAll())
                // Bearer 토큰(JWT) 검증 — role 클레임을 권한으로 변환
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint()))
                // 미인증 접근(토큰 없음)에도 동일한 401 JSON 응답
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(jsonAuthenticationEntryPoint()));
        return http.build();
    }

    // JWT의 단일 문자열 "role" 클레임("ADMIN"/"CUSTOMER")을 ROLE_* 권한으로 변환
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) {
                return List.of();
            }
            Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            return authorities;
        });
        return converter;
    }

    // 미인증/무효 토큰 요청에 기존 에러 형식({"message": ...})으로 401 응답
    private AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\": \"인증이 필요합니다.\"}");
        };
    }

    // 프론트(localhost:3000) → 백엔드 CORS 허용
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // 비밀번호 해싱 (BCrypt)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // JWT 발급 (HS256 대칭키)
    @Bean
    public JwtEncoder jwtEncoder() {
        SecretKey key = secretKey();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    // JWT 검증 (HS256 대칭키)
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private SecretKey secretKey() {
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
```

- [ ] **Step 4: 전체 백엔드 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — `고객_JWT로_어드민_API_접근시_차단된다` 포함 전부 통과. 어드민 로그인/리프레시로 받은 토큰은 `role=ADMIN`이라 `hasRole('ADMIN')`을 통과한다.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ecommerce/common/SecurityConfig.java \
        backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java
git commit -m "feat: 어드민 API를 ROLE_ADMIN으로 강화(고객 토큰 접근 차단)·role 권한 변환

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 플러밍 — DTO·예외·ClientIp 유틸

고객 인증 구현 전 공통 부품을 마련한다. `clientIp` 추출 중복을 `ClientIp` 유틸로 일원화한다.

**Files:**
- Create: `backend/src/main/java/com/ecommerce/auth/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/ecommerce/auth/dto/CustomerLoginRequest.java`
- Create: `backend/src/main/java/com/ecommerce/common/ConflictException.java`
- Create: `backend/src/main/java/com/ecommerce/common/ClientIp.java`
- Modify: `backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/AuthController.java`

- [ ] **Step 1: RegisterRequest / CustomerLoginRequest 작성**

`backend/src/main/java/com/ecommerce/auth/dto/RegisterRequest.java`:
```java
package com.ecommerce.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 회원가입 요청 — 이메일 형식·필수 검증
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
```

`backend/src/main/java/com/ecommerce/auth/dto/CustomerLoginRequest.java`:
```java
package com.ecommerce.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

// 고객 로그인 요청 — 이메일 식별자
public record CustomerLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
```

- [ ] **Step 2: ConflictException + 핸들러 작성**

`backend/src/main/java/com/ecommerce/common/ConflictException.java`:
```java
package com.ecommerce.common;

// 리소스 충돌(이메일 중복 등) 시 던지는 예외 (409 매핑)
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
```

`GlobalExceptionHandler.java`의 `handleUnauthorized` 메서드 바로 아래(34행 `}` 다음)에 핸들러를 추가한다:
```java
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage()));
    }
```

- [ ] **Step 3: ClientIp 유틸 작성**

`backend/src/main/java/com/ecommerce/common/ClientIp.java`:
```java
package com.ecommerce.common;

import jakarta.servlet.http.HttpServletRequest;

// 클라이언트 IP 추출 — 프록시 뒤에서는 X-Forwarded-For 첫 항목, 없으면 원격 주소
public final class ClientIp {

    private ClientIp() {
    }

    public static String from(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }
}
```

- [ ] **Step 4: AuthController가 ClientIp 유틸을 쓰도록 변경**

`AuthController.java`에서 (1) import 추가, (2) `clientIp(http)` 호출을 `ClientIp.from(http)`로 바꾸고, (3) private `clientIp` 메서드를 삭제한다.

import 추가:
```java
import com.ecommerce.common.ClientIp;
```
`login` 메서드 안의 호출 변경:
```java
        String ip = ClientIp.from(http);
```
그리고 파일 하단의 private `clientIp(HttpServletRequest http)` 메서드(59~66행)와 그 위 주석을 삭제한다. `HttpServletRequest` import는 `login` 시그니처에서 계속 쓰므로 유지한다.

- [ ] **Step 5: 컴파일 + 전체 테스트로 회귀 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — `AuthControllerTest`의 429 테스트 포함 동작 동일(IP 추출 로직 불변).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/dto/RegisterRequest.java \
        backend/src/main/java/com/ecommerce/auth/dto/CustomerLoginRequest.java \
        backend/src/main/java/com/ecommerce/common/ConflictException.java \
        backend/src/main/java/com/ecommerce/common/ClientIp.java \
        backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java \
        backend/src/main/java/com/ecommerce/auth/AuthController.java
git commit -m "feat: 고객 인증 플러밍 추가(DTO·ConflictException·ClientIp 유틸)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: CustomerAuthService + Controller (TDD)

가입(auto-login)·로그인·로그아웃·리프레시를 구현한다. 프로젝트 관행대로 service는 컨트롤러 테스트(MockMvc)로 검증한다.

**Files:**
- Create: `backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java`
- Create: `backend/src/main/java/com/ecommerce/auth/CustomerAuthService.java`
- Create: `backend/src/main/java/com/ecommerce/auth/CustomerAuthController.java`

- [ ] **Step 1: CustomerAuthControllerTest 작성(실패하는 테스트)**

`backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java`:
```java
package com.ecommerce.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomerRepository customerRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LoginAttemptService loginAttemptService;

    @BeforeEach
    void resetAttempts() {
        loginAttemptService.clearAll();
    }

    @AfterEach
    void cleanup() {
        refreshTokenRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void 회원가입하면_201과_토큰을_발급한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "user@example.com", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void 중복_이메일로_가입하면_409를_반환한다() throws Exception {
        customerRepository.save(new Customer("dup@example.com", passwordEncoder.encode("pw12345678")));

        String body = objectMapper.writeValueAsString(
                Map.of("email", "dup@example.com", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 이메일_형식이_아니면_400을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "not-an-email", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 가입한_계정으로_로그인하면_토큰을_발급한다() throws Exception {
        customerRepository.save(new Customer("user@example.com", passwordEncoder.encode("pw12345678")));

        String body = objectMapper.writeValueAsString(
                Map.of("email", "user@example.com", "password", "pw12345678"));

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void 잘못된_비밀번호로_로그인하면_401을_반환한다() throws Exception {
        customerRepository.save(new Customer("user@example.com", passwordEncoder.encode("pw12345678")));

        String body = objectMapper.writeValueAsString(
                Map.of("email", "user@example.com", "password", "wrong-password"));

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 존재하지_않는_이메일로_로그인하면_401을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "nobody@example.com", "password", "whatever12"));

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 동일_IP에서_연속_5회_로그인_실패하면_6회째_429를_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("email", "nobody@example.com", "password", "whatever12"));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/store/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/store/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 리프레시하면_새_토큰을_발급하고_옛_refresh는_무효화된다() throws Exception {
        String refreshToken = registerAndGetRefreshToken("user@example.com", "pw12345678");
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());

        // 옛 refresh 재사용 → 401
        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_refresh가_폐기되어_이후_리프레시가_거부된다() throws Exception {
        String refreshToken = registerAndGetRefreshToken("user@example.com", "pw12345678");
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken));

        mockMvc.perform(post("/api/store/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 가입으로_받은_access로는_어드민_API에_접근할_수_없다() throws Exception {
        String accessToken = registerAndGetAccessToken("user@example.com", "pw12345678");

        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    private JsonNode register(String email, String password) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", password));
        String response = mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String registerAndGetRefreshToken(String email, String password) throws Exception {
        return register(email, password).get("refreshToken").asString();
    }

    private String registerAndGetAccessToken(String email, String password) throws Exception {
        return register(email, password).get("accessToken").asString();
    }
}
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `cd backend && ./gradlew compileTestJava`
Expected: FAIL — `CustomerAuthService`/`CustomerAuthController`가 없어 컴파일 에러.

- [ ] **Step 3: CustomerAuthService 구현**

`backend/src/main/java/com/ecommerce/auth/CustomerAuthService.java`:
```java
package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.auth.RefreshTokenService.TokenOwner;
import com.ecommerce.auth.dto.CustomerLoginRequest;
import com.ecommerce.auth.dto.RegisterRequest;
import com.ecommerce.auth.dto.TokenResponse;
import com.ecommerce.common.ConflictException;
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

// 고객 인증 서비스 — 가입(auto-login)/로그인/리프레시/로그아웃.
@Service
@Transactional(readOnly = true)
public class CustomerAuthService {

    private static final String LOGIN_FAIL_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";
    private static final String DUPLICATE_EMAIL_MESSAGE = "이미 사용 중인 이메일입니다.";
    private static final String INVALID_REFRESH_MESSAGE = "리프레시 토큰이 유효하지 않습니다.";
    private static final String DUMMY_PASSWORD = "dummy-password-for-timing-mitigation";

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final RefreshTokenService refreshTokenService;
    private final long expirationSeconds;
    // 타이밍 공격 완화용 — email 부재 시에도 동일한 BCrypt 비용을 치르기 위한 더미 해시
    private final String dummyHash;

    public CustomerAuthService(CustomerRepository customerRepository,
                               PasswordEncoder passwordEncoder,
                               JwtEncoder jwtEncoder,
                               RefreshTokenService refreshTokenService,
                               @Value("${jwt.expiration-seconds}") long expirationSeconds) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenService = refreshTokenService;
        this.expirationSeconds = expirationSeconds;
        this.dummyHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    // 회원가입: 이메일 중복 검사 후 생성, 즉시 토큰 발급(auto-login)
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (customerRepository.existsByEmail(request.email())) {
            throw new ConflictException(DUPLICATE_EMAIL_MESSAGE);
        }
        Customer customer = customerRepository.save(
                new Customer(request.email(), passwordEncoder.encode(request.password())));
        return issueTokens(customer);
    }

    // 로그인: 자격 검증 후 access(JWT) + refresh(opaque) 발급
    @Transactional
    public TokenResponse login(CustomerLoginRequest request) {
        Customer customer = customerRepository.findByEmail(request.email()).orElse(null);
        if (customer == null) {
            // 계정이 없어도 BCrypt 검증을 수행해 응답 시간으로 계정 존재 여부가 새지 않게 한다.
            @SuppressWarnings("unused")
            boolean ignored = passwordEncoder.matches(request.password(), dummyHash);
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }
        if (!passwordEncoder.matches(request.password(), customer.getPassword())) {
            throw new UnauthorizedException(LOGIN_FAIL_MESSAGE);
        }
        return issueTokens(customer);
    }

    // 리프레시: refresh 회전 후 새 access + refresh 발급 (고객 토큰만 허용)
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        RotationResult result = refreshTokenService.rotate(refreshToken);
        if (result.owner().type() != OwnerType.CUSTOMER) {
            throw new UnauthorizedException(INVALID_REFRESH_MESSAGE);
        }
        Customer customer = customerRepository.findById(result.owner().id())
                .orElseThrow(() -> new UnauthorizedException(INVALID_REFRESH_MESSAGE));
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plusSeconds(expirationSeconds);
        String accessToken = encodeAccess(customer, now, accessExpiresAt);
        return new TokenResponse(accessToken, accessExpiresAt,
                result.refresh().token(), result.refresh().expiresAt());
    }

    // 로그아웃: refresh 폐기
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    private TokenResponse issueTokens(Customer customer) {
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plusSeconds(expirationSeconds);
        String accessToken = encodeAccess(customer, now, accessExpiresAt);
        IssuedToken refresh = refreshTokenService.issue(new TokenOwner(OwnerType.CUSTOMER, customer.getId()));
        return new TokenResponse(accessToken, accessExpiresAt, refresh.token(), refresh.expiresAt());
    }

    private String encodeAccess(Customer customer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(customer.getEmail())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("role", "CUSTOMER")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
```

- [ ] **Step 4: CustomerAuthController 구현**

`backend/src/main/java/com/ecommerce/auth/CustomerAuthController.java`:
```java
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
```

- [ ] **Step 5: 전체 백엔드 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — `CustomerAuthControllerTest` 10개 + 기존 전부 통과.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/CustomerAuthService.java \
        backend/src/main/java/com/ecommerce/auth/CustomerAuthController.java \
        backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java
git commit -m "feat: 고객 인증 서비스·컨트롤러 추가(가입 auto-login·로그인·리프레시·로그아웃)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 프론트 — 쿠키 이름 + API 함수

> **Next.js 16 필독:** 코드 작성 전 `frontend/node_modules/next/dist/docs/` 에서 관련 가이드를 확인하고, 본 플랜 코드가 현재 버전과 다르면 그 형태로 보정한다. (`frontend/AGENTS.md` 지침)

**Files:**
- Modify: `frontend/src/lib/auth-cookies.ts`
- Modify: `frontend/src/lib/api.ts`

- [ ] **Step 1: auth-cookies.ts에 고객 쿠키 이름 추가**

`auth-cookies.ts`의 어드민 쿠키 상수(`REFRESH_COOKIE = "admin_refresh";`) 바로 아래에 고객 쿠키 상수를 추가한다:
```ts
export const CUSTOMER_ACCESS_COOKIE = "customer_token";
export const CUSTOMER_REFRESH_COOKIE = "customer_refresh";
```
`authCookieOptions`/`maxAgeSeconds`는 그대로 공유한다(변경 없음).

- [ ] **Step 2: api.ts에 고객 인증 함수 추가**

`api.ts` 파일 끝(기존 `logout` 함수 아래)에 추가한다. (참고: `customerRefresh`는 이번 사이클에 호출자가 없어 추가하지 않는다 — 보호 페이지 사이클로 미룸.)
```ts
// 고객 회원가입 — 성공 시 auto-login 토큰(201)
export async function registerCustomer(
  email: string,
  password: string,
): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/store/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
    cache: "no-store",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(res.status, data?.message ?? `회원가입 실패 (${res.status})`);
  }
  return res.json() as Promise<TokenResponse>;
}

// 고객 로그인
export async function customerLogin(
  email: string,
  password: string,
): Promise<TokenResponse> {
  const res = await fetch(`${API_BASE}/api/store/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
    cache: "no-store",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(res.status, data?.message ?? `로그인 실패 (${res.status})`);
  }
  return res.json() as Promise<TokenResponse>;
}

// 고객 로그아웃 — refresh 토큰 서버 폐기(204). 실패해도 쿠키 삭제는 호출자가 진행
export async function customerLogout(refreshToken: string): Promise<void> {
  await fetch(`${API_BASE}/api/store/auth/logout`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
    cache: "no-store",
  });
}
```

- [ ] **Step 3: 타입/린트 확인**

Run: `cd frontend && npm run lint`
Expected: PASS (타입/린트 에러 없음)

- [ ] **Step 4: Commit**

```bash
git add frontend/src/lib/auth-cookies.ts frontend/src/lib/api.ts
git commit -m "feat: 프론트 고객 인증 API 함수·쿠키 이름 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 프론트 — 가입/로그인 화면 + 로그아웃 + 헤더

> **Next.js 16 필독:** `cookies()`는 async(`await` 필수), Route Handler는 `NextResponse.redirect` + `response.cookies.delete`. 기존 어드민 코드(`admin/login/actions.ts`, `admin/logout/route.ts`)와 동일 패턴이다.

**Files:**
- Create: `frontend/src/app/register/actions.ts`
- Create: `frontend/src/app/register/page.tsx`
- Create: `frontend/src/app/login/actions.ts`
- Create: `frontend/src/app/login/page.tsx`
- Create: `frontend/src/app/logout/route.ts`
- Modify: `frontend/src/app/page.tsx`

- [ ] **Step 1: register/actions.ts**

`frontend/src/app/register/actions.ts`:
```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { registerCustomer } from "@/lib/api";
import {
  CUSTOMER_ACCESS_COOKIE,
  CUSTOMER_REFRESH_COOKIE,
  authCookieOptions,
} from "@/lib/auth-cookies";

// 회원가입 Server Action — 가입 성공 시 auto-login 쿠키 설정 후 스토어 홈으로.
export async function registerAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  let tokens;
  try {
    tokens = await registerCustomer(email, password);
  } catch (err) {
    return err instanceof Error ? err.message : "회원가입에 실패했습니다.";
  }

  const store = await cookies();
  store.set(CUSTOMER_ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
  store.set(CUSTOMER_REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));

  redirect("/");
}
```

- [ ] **Step 2: register/page.tsx**

`frontend/src/app/register/page.tsx`:
```tsx
"use client";

import { useActionState } from "react";
import type { CSSProperties } from "react";
import { registerAction } from "./actions";

// 고객 회원가입 폼 — Server Action이 auto-login 쿠키를 설정하고 홈으로 리다이렉트한다
export default function RegisterPage() {
  const [error, formAction] = useActionState(registerAction, null);

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>회원가입</h1>
      <form action={formAction} style={{ display: "grid", gap: 12 }}>
        <input name="email" type="email" placeholder="이메일" style={inputStyle} />
        <input
          name="password"
          type="password"
          placeholder="비밀번호"
          style={inputStyle}
        />
        <button type="submit" style={{ padding: 8, cursor: "pointer" }}>
          가입하기
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

- [ ] **Step 3: login/actions.ts**

`frontend/src/app/login/actions.ts`:
```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { customerLogin } from "@/lib/api";
import {
  CUSTOMER_ACCESS_COOKIE,
  CUSTOMER_REFRESH_COOKIE,
  authCookieOptions,
} from "@/lib/auth-cookies";

// 고객 로그인 Server Action — 성공 시 쿠키 설정 후 스토어 홈으로.
export async function loginAction(
  _prevError: string | null,
  formData: FormData,
): Promise<string | null> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  let tokens;
  try {
    tokens = await customerLogin(email, password);
  } catch (err) {
    return err instanceof Error ? err.message : "로그인에 실패했습니다.";
  }

  const store = await cookies();
  store.set(CUSTOMER_ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
  store.set(CUSTOMER_REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));

  redirect("/");
}
```

- [ ] **Step 4: login/page.tsx**

`frontend/src/app/login/page.tsx`:
```tsx
"use client";

import { useActionState } from "react";
import type { CSSProperties } from "react";
import Link from "next/link";
import { loginAction } from "./actions";

// 고객 로그인 폼 — Server Action이 httpOnly 쿠키를 설정하고 홈으로 리다이렉트한다
export default function LoginPage() {
  const [error, formAction] = useActionState(loginAction, null);

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>로그인</h1>
      <form action={formAction} style={{ display: "grid", gap: 12 }}>
        <input name="email" type="email" placeholder="이메일" style={inputStyle} />
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
      <p style={{ marginTop: 12 }}>
        계정이 없으신가요? <Link href="/register">회원가입</Link>
      </p>
    </main>
  );
}

const inputStyle: CSSProperties = {
  border: "1px solid #ddd",
  padding: 8,
  borderRadius: 4,
};
```

- [ ] **Step 5: logout/route.ts**

`frontend/src/app/logout/route.ts`:
```ts
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { customerLogout } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE, CUSTOMER_REFRESH_COOKIE } from "@/lib/auth-cookies";

// 고객 로그아웃 — refresh를 백엔드에서 폐기하고 쿠키를 삭제 후 로그인 페이지로.
async function clearAndRedirect(request: Request) {
  const store = await cookies();
  const refreshToken = store.get(CUSTOMER_REFRESH_COOKIE)?.value;
  if (refreshToken) {
    // 백엔드 폐기 실패가 로그아웃을 막지 않도록 무시(쿠키 삭제는 진행)
    await customerLogout(refreshToken).catch(() => {});
  }
  // 303 See Other: POST → GET 리다이렉트 표준 응답
  const response = NextResponse.redirect(new URL("/login", request.url), 303);
  response.cookies.delete(CUSTOMER_ACCESS_COOKIE);
  response.cookies.delete(CUSTOMER_REFRESH_COOKIE);
  return response;
}

export function GET(request: Request) {
  return clearAndRedirect(request);
}

export function POST(request: Request) {
  return clearAndRedirect(request);
}
```

- [ ] **Step 6: 홈 헤더에 로그인/로그아웃 링크 추가**

`frontend/src/app/page.tsx`를 다음으로 교체한다(서버 컴포넌트에서 `customer_token` 쿠키 유무로 분기):
```tsx
import Link from "next/link";
import { cookies } from "next/headers";
import { getProducts } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";

export default async function HomePage() {
  const isLoggedIn = (await cookies()).has(CUSTOMER_ACCESS_COOKIE);

  let products;
  try {
    products = await getProducts();
  } catch {
    return (
      <main style={{ padding: 24 }}>
        <h1>상품 목록</h1>
        <p>백엔드에 연결할 수 없습니다. (http://localhost:8080)</p>
      </main>
    );
  }

  return (
    <main style={{ padding: 24 }}>
      <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1>스토어</h1>
        <nav style={{ display: "flex", gap: 12 }}>
          {isLoggedIn ? (
            <Link href="/logout">로그아웃</Link>
          ) : (
            <>
              <Link href="/login">로그인</Link>
              <Link href="/register">회원가입</Link>
            </>
          )}
          <Link href="/admin">어드민 →</Link>
        </nav>
      </header>
      <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
        {products.map((p) => (
          <li key={p.id} style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
            <Link href={`/products/${p.id}`}>
              <strong>{p.name}</strong>
            </Link>
            <div>{p.price.toLocaleString()}원</div>
            <small>{p.supplierName}</small>
          </li>
        ))}
      </ul>
    </main>
  );
}
```

- [ ] **Step 7: 프로덕션 빌드 + 린트 검증**

Run: `cd frontend && npm run build && npm run lint`
Expected: PASS — `/login`·`/register`·`/logout` 라우트 등록, 타입 에러 없음.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/app/register/actions.ts \
        frontend/src/app/register/page.tsx \
        frontend/src/app/login/actions.ts \
        frontend/src/app/login/page.tsx \
        frontend/src/app/logout/route.ts \
        frontend/src/app/page.tsx
git commit -m "feat: 고객 가입/로그인/로그아웃 화면·헤더 인증 링크 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 문서 동기화 + 최종 검증

**Files:**
- Modify: `docs/ROADMAP.md`
- Modify: `README.md`

- [ ] **Step 1: ROADMAP 갱신**

`docs/ROADMAP.md`에서:
- "완료된 사이클" 표의 사이클 5·6 "상태" 컬럼을 실제에 맞게 정정한다. `git log --oneline | grep "pull request"`로 확인하면 PR #8(토큰 수명)·PR #9(고정 윈도우)가 이미 머지됨 → 두 행의 `... 브랜치 (머지 대기)`를 `main 머지됨 (PR #8)` / `main 머지됨 (PR #9)`로 바꾼다.
- 표에 행 추가: `7. 고객 인증 | 2026-06-07 | 고객 회원가입/로그인/로그아웃, refresh 토큰 다형 소유 일반화, JWT role 클레임으로 어드민/고객 권한 분리 | feature/customer-auth 브랜치 (머지 대기)` — 실제 컬럼 구조에 맞춰 기재.
- "다음 사이클 후보"에서 "후보 2: 고객 회원가입/로그인"을 완료로 처리하고, 남은 후보(어드민 CRUD 폼, 주문/장바구니)의 우선순위를 정리한다. 주문/장바구니의 "고객 회원가입 필요" 전제가 해소됐음을 반영한다.

- [ ] **Step 2: README 갱신**

`README.md`를 Read로 확인한 뒤:
- 기능/인증 설명에 고객 인증(가입·로그인·로그아웃, access 15분 + refresh 7일, 어드민/고객 role 분리)을 추가한다.
- "보안 한계 > 남은 한계"에 refresh 토큰 다형 소유로 **DB FK(참조 무결성)를 포기**했다는 점을 한 줄 추가한다(단일 인스턴스 가정). 기존 "로그인 시도 제한 인메모리(다중 인스턴스)" 항목은 그대로 유지한다.
- 실제 문구는 Read로 확인 후 기존 형식에 맞춰 작성한다(추측으로 항목을 만들지 말 것).

- [ ] **Step 3: 문서 일관성 확인**

Run: `grep -n "고객\|role\|권한\|refresh\|다형\|FK" README.md docs/ROADMAP.md`
Expected: 고객 인증 완료·권한 분리·FK 포기 한계가 두 문서에서 모순 없이 일치.

- [ ] **Step 4: 백엔드·프론트 최종 검증**

Run: `cd backend && ./gradlew test && cd ../frontend && npm run build`
Expected: 양쪽 모두 PASS.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/ROADMAP.md
git commit -m "docs: 고객 인증 사이클 반영·이전 사이클(5·6) 머지 상태 정정

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 최종 검증 (Definition of Done)

- [ ] `cd backend && ./gradlew test` 전체 통과(RefreshTokenServiceTest 8 + CustomerAuthControllerTest 10 + SecurityProtectionTest 고객 차단 + 어드민 회귀 없음)
- [ ] `cd frontend && npm run build` 통과(`/register`·`/login`·`/logout` 라우트 등록)
- [ ] 고객 가입 시 `customer_token`·`customer_refresh` 쿠키 2개 set(둘 다 HttpOnly), auto-login으로 `/` 진입
- [ ] 고객 로그인/로그아웃 동작, 로그아웃 시 백엔드 refresh 폐기 + 쿠키 삭제
- [ ] **고객 JWT로 `/api/admin/**` 접근 시 403**(테스트로 고정)
- [ ] 어드민 인증 전 기능 회귀 없음(로그인/리프레시/로그아웃/보호 API)
- [ ] README/ROADMAP 동기화(사이클 5·6 머지 상태 정정 포함)
```
