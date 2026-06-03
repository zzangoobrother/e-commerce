# 어드민 인증(Admin Auth) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어드민 로그인(JWT)을 도입해 현재 완전히 개방된 어드민 API(`/api/admin/**`)와 어드민 화면을 보호한다.

**Architecture:** 백엔드는 Spring Security + OAuth2 Resource Server(JWT HS256)로 `/api/admin/**`를 보호하고, `POST /api/admin/login`이 토큰을 발급한다. 프론트는 토큰을 쿠키(`admin_token`)에 저장해 서버 컴포넌트 SSR 구조를 유지하며, Next.js 16의 `proxy.ts`(구 middleware)로 어드민 경로를 보호한다. 스토어 화면/API는 변경하지 않는다.

**Tech Stack:** 기존 스택 + Spring Security 7(Spring Boot 4.0.6), Nimbus JWT(HS256), BCrypt. 프론트 추가 의존성 없음.

**참고 스펙:** `docs/superpowers/specs/2026-06-03-admin-auth-design.md`

**작업 브랜치:** `feature/admin-auth` (이미 생성되어 체크아웃됨)

---

## ⚠️ 실행자 필독 1: 이 프로젝트의 import 경로 (Spring Boot 4.x)

테스트 코드의 패키지 경로가 일반적으로 알려진 것과 다르다. **아래 경로를 그대로 사용할 것:**

| 클래스 | 이 프로젝트의 import |
|--------|---------------------|
| `ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `@AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |
| `@DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |

## ⚠️ 실행자 필독 2: Spring Boot 4.0.6 Security 의존성 아티팩트명

Spring Boot 3.x와 이름이 다르다. **Initializr 4.0.6에서 확인된 정확한 이름:**

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
testImplementation("org.springframework.boot:spring-boot-starter-security-test")
testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
```

(3.x의 `spring-boot-starter-oauth2-resource-server`가 아님에 주의)

## ⚠️ 실행자 필독 3: Next.js 16은 middleware가 아니라 proxy

Next.js 16에서 `middleware.ts` 파일 규약은 deprecated되었고 **`proxy.ts`로 개명**되었다.
- 파일 위치: `frontend/src/proxy.ts` (src 디렉터리 사용 시 src 안, app과 같은 레벨)
- 내보내는 함수명: `proxy` (또는 default export)
- 참조 문서: `frontend/node_modules/next/dist/docs/01-app/03-api-reference/03-file-conventions/proxy.md`

---

## 파일 구조 (최종 형태)

```
e-commerce/
├── README.md                                              # Task 8: 인증 안내 추가
├── backend/
│   ├── build.gradle.kts                                   # Task 1: Security 의존성 추가
│   └── src/
│       ├── main/java/com/ecommerce/
│       │   ├── auth/                                      # 신규 도메인 패키지
│       │   │   ├── Admin.java                             # Task 2
│       │   │   ├── AdminRepository.java                   # Task 2
│       │   │   ├── AuthService.java                       # Task 3
│       │   │   ├── AuthController.java                    # Task 3
│       │   │   └── dto/{LoginRequest, LoginResponse}.java # Task 3
│       │   └── common/
│       │       ├── SecurityConfig.java                    # Task 1 생성 → Task 3, 4 확장
│       │       ├── UnauthorizedException.java             # Task 3
│       │       ├── GlobalExceptionHandler.java            # Task 3: 401 핸들러 추가
│       │       ├── WebConfig.java                         # Task 1: 삭제 (CORS가 SecurityConfig로 이전)
│       │       └── DataSeeder.java                        # Task 5: 어드민 시드 추가
│       ├── main/resources/application.yml                 # Task 3: jwt.* / Task 5: admin.seed.* 추가
│       └── test/java/com/ecommerce/
│           ├── auth/AdminRepositoryTest.java              # Task 2
│           ├── auth/AuthControllerTest.java               # Task 3
│           ├── common/SecurityProtectionTest.java         # Task 4
│           ├── supplier/SupplierControllerTest.java       # Task 4: jwt() 대응
│           └── product/ProductApiTest.java                # Task 4: jwt() 대응
└── frontend/src/
    ├── proxy.ts                                           # Task 7: 어드민 경로 보호
    ├── lib/api.ts                                         # Task 6: login/ApiError/토큰 헤더
    └── app/admin/
        ├── login/page.tsx                                 # Task 6: 로그인 폼
        ├── LogoutButton.tsx                               # Task 7: 로그아웃 버튼
        ├── page.tsx                                       # Task 7: 로그아웃 버튼 추가
        ├── suppliers/page.tsx                             # Task 7: 토큰 전달 + 401 처리
        └── products/page.tsx                              # Task 7: 토큰 전달 + 401 처리
```

---

## Task 1: Security 의존성 추가 + 전면 개방 SecurityConfig (기존 테스트 그린 유지)

Spring Security 의존성을 추가하면 **기본 동작으로 모든 요청이 차단**되어 기존 테스트가 전부
깨진다. 이 태스크에서는 의존성을 추가하되 일단 모든 요청을 허용(permitAll)하는 SecurityConfig를
만들어 기존 테스트를 그린으로 유지한다. 보호는 Task 4에서 단계적으로 활성화한다.
기존 `WebConfig`의 CORS 설정은 SecurityConfig로 이전한다(Spring Security와 CORS 통합).

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java`
- Delete: `backend/src/main/java/com/ecommerce/common/WebConfig.java`

- [ ] **Step 1: 기존 WebConfig의 CORS 규칙 확인**

`backend/src/main/java/com/ecommerce/common/WebConfig.java`를 Read로 읽고 허용된
origin/메서드 목록을 기록해 둔다 (Step 3에서 동일 규칙을 SecurityConfig로 이전).

- [ ] **Step 2: build.gradle.kts에 Security 의존성 추가**

`backend/build.gradle.kts`의 `dependencies { }` 블록에 다음 4줄을 추가한다
(기존 의존성은 그대로 유지):

```kotlin
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
```

- [ ] **Step 3: 전면 개방 SecurityConfig 작성 (CORS 포함)**

Create `backend/src/main/java/com/ecommerce/common/SecurityConfig.java`:

```java
package com.ecommerce.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

// 보안 설정 — 1단계: 전체 개방 상태 (어드민 API 보호는 이후 태스크에서 활성화)
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 토큰 기반 stateless API — CSRF/세션 불필요
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    // 프론트(localhost:3000) → 백엔드 CORS 허용 (기존 WebConfig에서 이전)
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
}
```

**주의:** Step 1에서 확인한 WebConfig의 CORS 규칙(origin/메서드)이 위와 다르면 위 코드를
기존 규칙에 맞게 조정한다 (기존 동작 보존이 우선).

- [ ] **Step 4: WebConfig 삭제**

`backend/src/main/java/com/ecommerce/common/WebConfig.java` 파일을 삭제한다
(CORS 설정이 SecurityConfig로 이전되었으므로 중복 제거).

```bash
rm backend/src/main/java/com/ecommerce/common/WebConfig.java
```

- [ ] **Step 5: 전체 테스트 통과 확인 (기존 동작 보존)**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, 기존 13개 테스트 전부 통과 (Security 추가 후에도 전면 개방이므로
기존 동작과 동일해야 함).

**실패 시:** Spring Security의 기본 헤더/필터가 응답에 영향을 줄 수 있다. 실패한 테스트의
원인을 파악하되, 보호 규칙을 추가해 해결하려 하지 말 것 (이 태스크의 목표는 "동작 불변").

- [ ] **Step 6: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: Spring Security 의존성 추가 및 전면 개방 보안 설정(CORS 이전)"
```

---

## Task 2: Admin 엔티티 + 리포지토리 (TDD)

**Files:**
- Test: `backend/src/test/java/com/ecommerce/auth/AdminRepositoryTest.java`
- Create: `backend/src/main/java/com/ecommerce/auth/Admin.java`
- Create: `backend/src/main/java/com/ecommerce/auth/AdminRepository.java`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

Create `backend/src/test/java/com/ecommerce/auth/AdminRepositoryTest.java`:

```java
package com.ecommerce.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class AdminRepositoryTest {

    @Autowired
    AdminRepository adminRepository;

    @Test
    void 어드민_계정을_저장하고_아이디로_조회한다() {
        adminRepository.save(new Admin("admin", "encoded-password-hash"));

        Admin found = adminRepository.findByUsername("admin").orElseThrow();

        assertThat(found.getUsername()).isEqualTo("admin");
        assertThat(found.getPassword()).isEqualTo("encoded-password-hash");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 동일한_아이디의_어드민은_저장할_수_없다() {
        adminRepository.saveAndFlush(new Admin("admin", "hash1"));

        assertThatThrownBy(() -> adminRepository.saveAndFlush(
                new Admin("admin", "hash2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 아이디로_어드민_존재_여부를_확인한다() {
        adminRepository.save(new Admin("admin", "hash"));

        assertThat(adminRepository.existsByUsername("admin")).isTrue();
        assertThat(adminRepository.existsByUsername("nobody")).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.AdminRepositoryTest"`
Expected: FAIL — `Admin`, `AdminRepository` 미존재로 컴파일 불가.

- [ ] **Step 3: Admin 엔티티 작성**

Create `backend/src/main/java/com/ecommerce/auth/Admin.java`:

```java
package com.ecommerce.auth;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 어드민 계정 — 비밀번호는 BCrypt 해시로 저장
@Entity
@Table(name = "admins")
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 로그인 아이디 — 중복 불가
    @Column(nullable = false, unique = true)
    private String username;

    // BCrypt 해시된 비밀번호 (평문 저장 금지)
    @Column(nullable = false)
    private String password;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected Admin() {
    }

    public Admin(String username, String encodedPassword) {
        this.username = username;
        this.password = encodedPassword;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 4: AdminRepository 작성**

Create `backend/src/main/java/com/ecommerce/auth/AdminRepository.java`:

```java
package com.ecommerce.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByUsername(String username);

    boolean existsByUsername(String username);
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.auth.AdminRepositoryTest"`
Expected: PASS (3개).

- [ ] **Step 6: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: Admin 엔티티/리포지토리 추가"
```

---

## Task 3: JWT 발급 + 로그인 API (TDD)

**Files:**
- Test: `backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java`
- Create: `backend/src/main/java/com/ecommerce/common/UnauthorizedException.java`
- Modify: `backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java` (401 핸들러 추가)
- Modify: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java` (JWT/PasswordEncoder 빈 추가)
- Modify: `backend/src/main/resources/application.yml` (jwt.* 설정 추가)
- Create: `backend/src/main/java/com/ecommerce/auth/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/ecommerce/auth/dto/LoginResponse.java`
- Create: `backend/src/main/java/com/ecommerce/auth/AuthService.java`
- Create: `backend/src/main/java/com/ecommerce/auth/AuthController.java`

- [ ] **Step 1: 실패하는 로그인 API 테스트 작성**

Create `backend/src/test/java/com/ecommerce/auth/AuthControllerTest.java`:

```java
package com.ecommerce.auth;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminRepository adminRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        // 각 테스트 후 데이터 정리 — 다른 테스트와의 격리
        adminRepository.deleteAll();
    }

    @Test
    void 올바른_계정으로_로그인하면_토큰을_발급한다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));

        String body = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "admin1234"));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void 잘못된_비밀번호로_로그인하면_401을_반환한다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));

        String body = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "wrong-password"));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 존재하지_않는_아이디로_로그인하면_401을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "nobody", "password", "whatever"));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 빈_입력으로_로그인하면_400을_반환한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "", "password", ""));

        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.AuthControllerTest"`
Expected: FAIL — `PasswordEncoder` 빈 미존재 또는 `AuthController` 미존재로 컴파일/컨텍스트 실패.

- [ ] **Step 3: application.yml에 JWT 설정 추가**

`backend/src/main/resources/application.yml` 맨 아래(`server:` 블록 다음)에 추가:

```yaml

jwt:
  # HS256 서명용 시크릿 — 최소 32바이트(256비트) 필요. 운영에서는 반드시 환경변수로 교체
  secret: ${JWT_SECRET:dev-only-secret-key-change-me-in-production-1234}
  expiration-seconds: ${JWT_EXPIRATION_SECONDS:3600}
```

- [ ] **Step 4: UnauthorizedException 작성 + GlobalExceptionHandler에 401 핸들러 추가**

Create `backend/src/main/java/com/ecommerce/common/UnauthorizedException.java`:

```java
package com.ecommerce.common;

// 인증 실패(로그인 실패 등) 시 던지는 예외 (401 매핑)
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
```

Modify `backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java` —
기존 `handleNotFound` 메서드 아래에 다음 핸들러를 추가한다 (기존 핸들러들은 그대로 유지):

```java
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", e.getMessage()));
    }
```

- [ ] **Step 5: SecurityConfig에 PasswordEncoder + JWT Encoder/Decoder 빈 추가**

Modify `backend/src/main/java/com/ecommerce/common/SecurityConfig.java` — 클래스에 필드와
빈 3개를 추가한다. 전체 파일을 다음으로 교체:

```java
package com.ecommerce.common;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
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
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
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

**주의:** Task 1의 CORS 규칙 조정이 있었다면 그 내용을 보존한 채 빈만 추가할 것.

- [ ] **Step 6: 로그인 DTO 작성**

Create `backend/src/main/java/com/ecommerce/auth/dto/LoginRequest.java`:

```java
package com.ecommerce.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 로그인 요청
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password
) {
}
```

Create `backend/src/main/java/com/ecommerce/auth/dto/LoginResponse.java`:

```java
package com.ecommerce.auth.dto;

import java.time.Instant;

// 로그인 응답 — JWT 토큰과 만료 시각
public record LoginResponse(
        String token,
        Instant expiresAt
) {
}
```

- [ ] **Step 7: AuthService 작성**

Create `backend/src/main/java/com/ecommerce/auth/AuthService.java`:

```java
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
```

- [ ] **Step 8: AuthController 작성**

Create `backend/src/main/java/com/ecommerce/auth/AuthController.java`:

```java
package com.ecommerce.auth;

import com.ecommerce.auth.dto.LoginRequest;
import com.ecommerce.auth.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 어드민 로그인 API — 인증 없이 접근 가능한 유일한 어드민 경로
@RestController
@RequestMapping("/api/admin/login")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
```

- [ ] **Step 9: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.auth.AuthControllerTest"`
Expected: PASS (4개).

- [ ] **Step 10: 전체 테스트 통과 확인 후 커밋**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 전체 테스트 20개(기존 13 + Task 2의 3 + 이번 4) 통과.

```bash
cd .. && git add backend && git commit -m "feat: JWT 발급 및 어드민 로그인 API 추가"
```

---

## Task 4: /api/admin/** 보호 활성화 + 기존 테스트 인증 대응 (TDD)

**Files:**
- Test: `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java`
- Modify: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java` (보호 규칙 + 401 EntryPoint)
- Modify: `backend/src/test/java/com/ecommerce/supplier/SupplierControllerTest.java` (jwt() 추가)
- Modify: `backend/src/test/java/com/ecommerce/product/ProductApiTest.java` (jwt() 추가)

- [ ] **Step 1: 실패하는 보호 규칙 테스트 작성**

Create `backend/src/test/java/com/ecommerce/common/SecurityProtectionTest.java`:

```java
package com.ecommerce.common;

import com.ecommerce.auth.Admin;
import com.ecommerce.auth.AdminRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 어드민 API 보호 규칙 검증 — 토큰 없으면 401, 있으면 통과, 스토어는 항상 개방
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityProtectionTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdminRepository adminRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanup() {
        adminRepository.deleteAll();
    }

    @Test
    void 토큰_없이_어드민_API_호출시_401과_메시지를_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/suppliers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 토큰_없이_스토어_API는_정상_접근된다() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }

    @Test
    void 모의_JWT로_어드민_API에_접근한다() throws Exception {
        mockMvc.perform(get("/api/admin/suppliers").with(jwt()))
                .andExpect(status().isOk());
    }

    @Test
    void 로그인으로_발급받은_실제_토큰으로_어드민_API에_접근한다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));

        // 로그인 → 토큰 추출
        String loginBody = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "admin1234"));
        String response = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(response).get("token").asText();

        // 발급받은 실제 토큰으로 보호된 API 접근 (발급→검증 왕복 검증)
        mockMvc.perform(get("/api/admin/suppliers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.common.SecurityProtectionTest"`
Expected: FAIL — 아직 전면 개방 상태라 `토큰_없이_어드민_API_호출시_401...` 테스트가
200을 받아 실패. (나머지 3개는 통과할 수 있음 — 401 테스트의 실패가 확인 대상)

- [ ] **Step 3: SecurityConfig에 보호 규칙 + 401 EntryPoint 활성화**

Modify `backend/src/main/java/com/ecommerce/common/SecurityConfig.java` —
`securityFilterChain` 메서드를 다음으로 교체하고, import에
`org.springframework.http.HttpMethod`, `org.springframework.http.HttpStatus`,
`org.springframework.http.MediaType`,
`org.springframework.security.web.AuthenticationEntryPoint`를 추가한다
(`Customizer`는 이미 import되어 있음. 나머지 빈들은 그대로 유지):

```java
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 토큰 기반 stateless API — CSRF/세션 불필요
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 로그인은 인증 없이 허용
                        .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                        // 나머지 어드민 API는 JWT 필수
                        .requestMatchers("/api/admin/**").authenticated()
                        // 스토어 API 등 그 외는 모두 개방
                        .anyRequest().permitAll())
                // Bearer 토큰(JWT) 검증
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(jsonAuthenticationEntryPoint()))
                // 미인증 접근(토큰 없음)에도 동일한 401 JSON 응답
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(jsonAuthenticationEntryPoint()));
        return http.build();
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
```

- [ ] **Step 4: 신규 보호 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.common.SecurityProtectionTest"`
Expected: PASS (4개).

- [ ] **Step 5: 기존 어드민 API 테스트에 jwt() 인증 추가**

보호 활성화로 기존 어드민 API 테스트들이 401로 깨진다. 모의 JWT 인증을 추가한다.

Modify `backend/src/test/java/com/ecommerce/supplier/SupplierControllerTest.java`:
1. import 추가:
```java
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
```
2. 모든 `mockMvc.perform(...)` 호출에서 `/api/admin/` 경로 요청에 `.with(jwt())`를 추가한다. 예:
```java
        mockMvc.perform(post("/api/admin/suppliers").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
```
```java
        mockMvc.perform(get("/api/admin/suppliers").with(jwt()))
```
(이 파일의 어드민 API 요청 4곳: 생성 테스트의 post/get, 비활성 생성 테스트의 post)

Modify `backend/src/test/java/com/ecommerce/product/ProductApiTest.java`:
1. 동일한 import 추가
2. `/api/admin/products` 경로 요청에 `.with(jwt())` 추가
   (어드민 생성/조회 테스트의 post/get, 숨김 상태 생성 테스트의 post —
   `/api/products` 스토어 경로 요청에는 추가하지 **않는다**)

- [ ] **Step 6: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 전체 테스트 24개(이전 20 + 이번 4) 통과.

- [ ] **Step 7: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: 어드민 API JWT 보호 활성화 및 기존 테스트 인증 대응"
```

---

## Task 5: 어드민 계정 시드 (DataSeeder 확장)

**Files:**
- Modify: `backend/src/main/resources/application.yml` (admin.seed.* 추가)
- Modify: `backend/src/main/java/com/ecommerce/common/DataSeeder.java`

- [ ] **Step 1: application.yml에 어드민 시드 설정 추가**

`backend/src/main/resources/application.yml`의 `jwt:` 블록 아래에 추가:

```yaml

admin:
  seed:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:admin1234}
```

- [ ] **Step 2: DataSeeder에 어드민 시드 추가**

Modify `backend/src/main/java/com/ecommerce/common/DataSeeder.java` — 전체 파일을 다음으로 교체:

```java
package com.ecommerce.common;

import com.ecommerce.auth.Admin;
import com.ecommerce.auth.AdminRepository;
import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// 앱 시작 시 샘플 데이터 시드 (이미 있으면 건너뜀 — 멱등)
@Component
@Profile("!test")
public class DataSeeder implements CommandLineRunner {

    // 시드의 기준이 되는 첫 번째 공급사명 (존재하면 이미 시드된 것으로 간주)
    private static final String FIRST_SUPPLIER_NAME = "신선식품 주식회사";

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public DataSeeder(SupplierRepository supplierRepository,
                      ProductRepository productRepository,
                      AdminRepository adminRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${admin.seed.username}") String adminUsername,
                      @Value("${admin.seed.password}") String adminPassword) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    // 어드민 계정·카탈로그 시드를 한 트랜잭션으로 묶어 원자적으로 커밋/롤백한다
    // (이름 존재 여부로 멱등성 판단 — 동시 기동 race는 유니크 제약이 최종 방어)
    // 주의: race에서 진 인스턴스는 제약 위반으로 기동이 실패할 수 있음 (다중 인스턴스 배포 시 재고려)
    @Override
    @Transactional
    public void run(String... args) {
        seedAdmin();
        seedCatalog();
    }

    // 어드민 계정 시드 — 비밀번호는 BCrypt 해싱 저장
    private void seedAdmin() {
        if (adminRepository.existsByUsername(adminUsername)) {
            return; // 이미 시드됨
        }
        adminRepository.save(new Admin(adminUsername, passwordEncoder.encode(adminPassword)));
    }

    // 공급사/상품 시드
    private void seedCatalog() {
        if (supplierRepository.existsByName(FIRST_SUPPLIER_NAME)) {
            return; // 이미 시드됨
        }

        Supplier fresh = supplierRepository.save(
                new Supplier(FIRST_SUPPLIER_NAME, "fresh@example.com"));
        Supplier snack = supplierRepository.save(
                new Supplier("바삭과자 주식회사", "snack@example.com"));

        productRepository.save(new Product(fresh, "유기농 사과 1kg",
                "당도 높은 유기농 사과", new BigDecimal("8900"), 50));
        productRepository.save(new Product(fresh, "제철 딸기 500g",
                "신선한 제철 딸기", new BigDecimal("12000"), 30));
        productRepository.save(new Product(snack, "감자칩 오리지널",
                "바삭한 감자칩", new BigDecimal("1500"), 200));
        productRepository.save(new Product(snack, "초코쿠키 12개입",
                "달콤한 초코쿠키", new BigDecimal("3500"), 120));
    }
}
```

**주의:** 기존 DataSeeder와 비교해 `seedCatalog()`의 내용(공급사 2개, 상품 4개, 가격/재고)이
바뀌지 않았는지 Read로 확인할 것. 변경되는 것은 어드민 시드 추가와 메서드 분리뿐이다.

- [ ] **Step 3: 전체 테스트 통과 확인 (시드는 test 프로파일에서 비활성)**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL — DataSeeder는 `@Profile("!test")`라 테스트에 영향 없음 (24개 통과).

- [ ] **Step 4: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: 어드민 계정 시드 추가(환경변수 설정 가능, BCrypt)"
```

---

## Task 6: 프론트 — api.ts 확장 + 로그인 페이지

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Create: `frontend/src/app/admin/login/page.tsx`

- [ ] **Step 1: api.ts에 인증 지원 추가**

Modify `frontend/src/lib/api.ts` — 전체 파일을 다음으로 교체
(기존 타입/함수는 유지하면서 ApiError, login, 토큰 헤더 지원이 추가된 형태):

```ts
// 백엔드 REST API 호출 래퍼 + 공유 타입

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type ProductStatus = "ON_SALE" | "SOLD_OUT" | "HIDDEN";
export type SupplierStatus = "ACTIVE" | "INACTIVE";

export interface Product {
  id: number;
  supplierId: number;
  supplierName: string;
  name: string;
  description: string | null;
  price: number;
  stockQuantity: number;
  status: ProductStatus;
  createdAt: string;
}

export interface Supplier {
  id: number;
  name: string;
  contactEmail: string | null;
  status: SupplierStatus;
  createdAt: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
}

// HTTP 상태 코드를 보존하는 API 에러 (401 구분용)
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function getJson<T>(path: string, token?: string): Promise<T> {
  const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {};
  const res = await fetch(`${API_BASE}${path}`, { cache: "no-store", headers });
  if (!res.ok) {
    throw new ApiError(res.status, `API 요청 실패 (${res.status}): ${path}`);
  }
  return res.json() as Promise<T>;
}

// 스토어 (인증 불필요)
export const getProducts = () => getJson<Product[]>("/api/products");
export const getProduct = (id: string | number) =>
  getJson<Product>(`/api/products/${id}`);

// 인증
export async function login(
  username: string,
  password: string,
): Promise<LoginResponse> {
  const res = await fetch(`${API_BASE}/api/admin/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
    cache: "no-store",
  });
  if (!res.ok) {
    const data = await res.json().catch(() => null);
    throw new ApiError(
      res.status,
      data?.message ?? `로그인 실패 (${res.status})`,
    );
  }
  return res.json() as Promise<LoginResponse>;
}

// 어드민 (Bearer 토큰 필요)
export const getSuppliers = (token: string) =>
  getJson<Supplier[]>("/api/admin/suppliers", token);
export const getAdminProducts = (token: string, supplierId?: number) =>
  getJson<Product[]>(
    `/api/admin/products${supplierId ? `?supplierId=${supplierId}` : ""}`,
    token,
  );
```

**기존과의 차이:** ① `ApiError` 클래스(status 보존), ② `login()` 추가, ③ `getJson`에
선택적 token 파라미터, ④ 어드민 함수들(`getSuppliers`, `getAdminProducts`)의 **첫 번째 파라미터가
token으로 변경됨** (기존 호출부는 Task 7에서 수정).

- [ ] **Step 2: 로그인 페이지 작성**

Create `frontend/src/app/admin/login/page.tsx`:

```tsx
"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { CSSProperties, FormEvent } from "react";
import { login } from "@/lib/api";

// 어드민 로그인 폼 — 성공 시 토큰을 쿠키에 저장하고 어드민으로 이동
export default function AdminLoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      const { token, expiresAt } = await login(username, password);
      // 토큰을 쿠키에 저장 (만료 시각까지)
      const maxAge = Math.max(
        0,
        Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000),
      );
      document.cookie = `admin_token=${token}; path=/; max-age=${maxAge}`;
      router.push("/admin");
    } catch (err) {
      setError(err instanceof Error ? err.message : "로그인에 실패했습니다.");
    }
  }

  return (
    <main style={{ padding: 24, maxWidth: 360 }}>
      <h1>어드민 로그인</h1>
      <form onSubmit={handleSubmit} style={{ display: "grid", gap: 12 }}>
        <input
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="아이디"
          style={inputStyle}
        />
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
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

- [ ] **Step 3: 빌드 검증 (어드민 페이지의 기존 호출부 깨짐 확인)**

Run: `cd frontend && npm run build`
Expected: **FAIL** — `getSuppliers()`/`getAdminProducts()`의 시그니처 변경으로 기존 어드민
페이지(suppliers/products)에서 타입 에러. 이는 의도된 중간 상태로, Task 7에서 호출부를
수정하면 해소된다. 에러가 시그니처 불일치(`Expected 1 arguments, but got 0` 류)인지 확인만 할 것.

**중요:** 이 시점에는 커밋하지 않는다. Task 7과 묶어서 커밋한다 — 빌드가 깨진 상태로
커밋을 남기지 않기 위함이다. (또는 이 Step을 건너뛰고 Task 7 완료 후 빌드 검증해도 무방)

- [ ] **Step 4: 커밋 보류 확인**

이 태스크의 변경사항은 작업 트리에 남겨두고 Task 7에서 함께 커밋한다.
`git status`로 변경 파일 2개(`api.ts`, `login/page.tsx`)가 staged되지 않은 상태인지 확인.

---

## Task 7: 프론트 — proxy.ts 경로 보호 + 어드민 페이지 토큰 전달 + 로그아웃

**Files:**
- Create: `frontend/src/proxy.ts`
- Create: `frontend/src/app/admin/LogoutButton.tsx`
- Modify: `frontend/src/app/admin/page.tsx`
- Modify: `frontend/src/app/admin/suppliers/page.tsx`
- Modify: `frontend/src/app/admin/products/page.tsx`

- [ ] **Step 1: proxy.ts 작성 (어드민 경로 보호)**

Create `frontend/src/proxy.ts` (위치: `src/` 바로 아래, `app/`과 같은 레벨):

```ts
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// 어드민 경로 보호 — 토큰 쿠키가 없으면 로그인 페이지로 리다이렉트
// (토큰 유효성/만료 검증은 백엔드 책임 — 여기서는 존재 여부만 확인하는 1차 방어)
export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // 로그인 페이지 자신은 보호하지 않음
  if (pathname === "/admin/login") {
    return NextResponse.next();
  }

  const token = request.cookies.get("admin_token");
  if (!token) {
    return NextResponse.redirect(new URL("/admin/login", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: "/admin/:path*",
};
```

- [ ] **Step 2: 로그아웃 버튼 컴포넌트 작성**

Create `frontend/src/app/admin/LogoutButton.tsx`:

```tsx
"use client";

import { useRouter } from "next/navigation";

// 로그아웃 — 토큰 쿠키 삭제 후 로그인 페이지로 이동
export default function LogoutButton() {
  const router = useRouter();

  function handleLogout() {
    document.cookie = "admin_token=; path=/; max-age=0";
    router.push("/admin/login");
  }

  return (
    <button
      onClick={handleLogout}
      style={{ padding: "4px 12px", cursor: "pointer" }}
    >
      로그아웃
    </button>
  );
}
```

- [ ] **Step 3: 어드민 대시보드에 로그아웃 버튼 추가**

Replace `frontend/src/app/admin/page.tsx`:

```tsx
import Link from "next/link";
import LogoutButton from "./LogoutButton";

export default function AdminHome() {
  return (
    <main style={{ padding: 24 }}>
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <h1>어드민</h1>
        <LogoutButton />
      </header>
      <nav style={{ display: "flex", gap: 16 }}>
        <Link href="/admin/suppliers">공급사 관리</Link>
        <Link href="/admin/products">상품 관리(공급사별)</Link>
        <Link href="/">← 스토어</Link>
      </nav>
    </main>
  );
}
```

- [ ] **Step 4: 공급사 관리 페이지에 토큰 전달 + 401 처리 추가**

Replace `frontend/src/app/admin/suppliers/page.tsx`:

```tsx
import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { CSSProperties } from "react";
import { getSuppliers, ApiError } from "@/lib/api";
import LogoutButton from "../LogoutButton";

export default async function AdminSuppliersPage() {
  // 쿠키에서 토큰 읽기 (없으면 로그인으로 — proxy의 2차 방어)
  const cookieStore = await cookies();
  const token = cookieStore.get("admin_token")?.value;
  if (!token) {
    redirect("/admin/login");
  }

  let suppliers;
  try {
    suppliers = await getSuppliers(token);
  } catch (err) {
    // 401 = 토큰 만료/무효 → 로그인 페이지로
    if (err instanceof ApiError && err.status === 401) {
      redirect("/admin/login");
    }
    return <main style={{ padding: 24 }}><p>백엔드 연결 실패</p></main>;
  }

  return (
    <main style={{ padding: 24 }}>
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <Link href="/admin">← 어드민</Link>
        <LogoutButton />
      </header>
      <h1>공급사 관리</h1>
      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th style={cell}>ID</th><th style={cell}>이름</th>
            <th style={cell}>이메일</th><th style={cell}>상태</th>
          </tr>
        </thead>
        <tbody>
          {suppliers.map((s) => (
            <tr key={s.id}>
              <td style={cell}>{s.id}</td>
              <td style={cell}>{s.name}</td>
              <td style={cell}>{s.contactEmail}</td>
              <td style={cell}>{s.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}

const cell: CSSProperties = { border: "1px solid #ddd", padding: 8, textAlign: "left" };
```

**주의:** 작업 전 기존 파일을 Read로 확인할 것. 기존 파일이 위 코드의 베이스와 다르면
(예: import 방식) 테이블 렌더링 부분은 기존 그대로 유지하고 토큰/401/로그아웃 부분만 추가.

- [ ] **Step 5: 상품 관리 페이지에 토큰 전달 + 401 처리 추가**

Replace `frontend/src/app/admin/products/page.tsx`:

```tsx
import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { CSSProperties } from "react";
import { getAdminProducts, getSuppliers, ApiError } from "@/lib/api";
import LogoutButton from "../LogoutButton";

export default async function AdminProductsPage({
  searchParams,
}: {
  searchParams: Promise<{ supplierId?: string }>;
}) {
  // 쿠키에서 토큰 읽기 (없으면 로그인으로 — proxy의 2차 방어)
  const cookieStore = await cookies();
  const token = cookieStore.get("admin_token")?.value;
  if (!token) {
    redirect("/admin/login");
  }

  const { supplierId } = await searchParams;
  const selectedId = supplierId ? Number(supplierId) : undefined;

  let suppliers, products;
  try {
    [suppliers, products] = await Promise.all([
      getSuppliers(token),
      getAdminProducts(token, selectedId),
    ]);
  } catch (err) {
    // 401 = 토큰 만료/무효 → 로그인 페이지로
    if (err instanceof ApiError && err.status === 401) {
      redirect("/admin/login");
    }
    return <main style={{ padding: 24 }}><p>백엔드 연결 실패</p></main>;
  }

  return (
    <main style={{ padding: 24 }}>
      <header
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
        }}
      >
        <Link href="/admin">← 어드민</Link>
        <LogoutButton />
      </header>
      <h1>상품 관리 (공급사별)</h1>

      <nav style={{ display: "flex", gap: 12, margin: "12px 0" }}>
        <Link href="/admin/products">전체</Link>
        {suppliers.map((s) => (
          <Link key={s.id} href={`/admin/products?supplierId=${s.id}`}>
            {s.name}
          </Link>
        ))}
      </nav>

      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th style={cell}>ID</th><th style={cell}>상품명</th>
            <th style={cell}>공급사</th><th style={cell}>가격</th>
            <th style={cell}>재고</th><th style={cell}>상태</th>
          </tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.id}>
              <td style={cell}>{p.id}</td>
              <td style={cell}>{p.name}</td>
              <td style={cell}>{p.supplierName}</td>
              <td style={cell}>{p.price.toLocaleString()}원</td>
              <td style={cell}>{p.stockQuantity}</td>
              <td style={cell}>{p.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}

const cell: CSSProperties = { border: "1px solid #ddd", padding: 8, textAlign: "left" };
```

- [ ] **Step 6: 빌드 검증**

Run: `cd frontend && npm run build`
Expected: 빌드 성공. 라우트에 `/admin/login` 추가 (총 7개: `/`, `/_not-found`, `/admin`,
`/admin/login`, `/admin/products`, `/admin/suppliers`, `/products/[id]`).
Task 6에서 발생했던 시그니처 타입 에러가 해소되어야 함.

- [ ] **Step 7: 커밋 (Task 6 + Task 7 묶음)**

```bash
cd .. && git add frontend && git commit -m "feat: 어드민 로그인 화면 및 경로 보호(proxy)·토큰 전달·로그아웃 추가"
```

---

## Task 8: README 갱신 + 전체 검증

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README에 인증 안내 추가**

`README.md`를 Read로 확인 후 두 가지를 수정한다:

1. **기존 인용문 교체**: `> 이번 골격에는 인증이 없다(개방 API).` 를 다음으로 교체:

```markdown
> **어드민 인증:** 어드민 화면/API는 JWT 로그인이 필요하다. 기본 계정은 `admin` / `admin1234`
> (환경변수 `ADMIN_USERNAME` / `ADMIN_PASSWORD`로 변경 가능). 로그인: http://localhost:3000/admin/login
> 스토어 화면/API는 인증 없이 접근 가능하다.
```

2. **주요 API 표에 로그인 행 추가 + 어드민 보호 표시**: "주요 API" 표에서
   어드민 영역 첫 행 위에 다음 행을 추가:

```markdown
| 어드민 | `POST /api/admin/login` | 어드민 로그인 (JWT 발급) — 인증 불필요 |
```

   그리고 표 아래에 다음 문장을 추가:

```markdown
> 어드민 API(`/api/admin/**`)는 로그인 API를 제외하고 모두 `Authorization: Bearer <token>` 헤더가 필요하다.
```

3. **보안 한계 안내 추가** (스펙 9장 — 운영 전 보완 필요 항목): README 맨 아래에 다음 섹션을 추가:

```markdown
## 보안 한계 (골격 수준 — 운영 전 보완 필요)

- 토큰을 일반 쿠키에 저장한다 (httpOnly 아님 → XSS에 취약). 운영 전 httpOnly 쿠키 전환 권장
- 리프레시 토큰이 없다 (만료 시 재로그인 필요, 기본 1시간)
- 서버 측 토큰 무효화가 없다 (로그아웃은 클라이언트 쿠키 삭제만)
- 로그인 시도 제한(brute-force 방어)이 없다
```

- [ ] **Step 2: 백엔드 전체 테스트 + 프론트 빌드 최종 확인**

Run: `cd backend && ./gradlew test && cd ../frontend && npm run build && cd ..`
Expected: 백엔드 BUILD SUCCESSFUL(테스트 24개), 프론트 빌드 성공(7개 라우트).

- [ ] **Step 3: 커밋**

```bash
git add README.md
git commit -m "docs: README에 어드민 인증 안내 추가"
```

---

## E2E 수동 검증 (Task 8 이후, 컨트롤러 수행 권장)

코드 변경 없음 — 플랜 실행 완료 후 컨트롤러(메인 세션)가 전체 스택을 기동해 검증한다:

1. `docker compose up -d` → 백엔드 `./gradlew bootRun` → 프론트 `npm run dev`
2. 토큰 없이 `curl http://localhost:8080/api/admin/suppliers` → **401**
3. `curl http://localhost:8080/api/products` → **200** (스토어 영향 없음)
4. 브라우저: `http://localhost:3000/admin` 접근 → `/admin/login` 리다이렉트 확인
5. `admin` / `admin1234` 로그인 → 어드민 대시보드 → 공급사/상품 관리 정상 동작
6. 로그아웃 → 다시 `/admin` 접근 시 로그인 페이지로 리다이렉트
7. 프로세스 종료 + `docker compose down`

---

## Self-Review 메모

- **스펙 커버리지:** 의존성/SecurityConfig(Task 1·3·4), Admin 엔티티(Task 2), 로그인 API(Task 3),
  보호 규칙·401 형식·기존 테스트 대응(Task 4), 시드(Task 5), 프론트 로그인/api.ts(Task 6),
  proxy·토큰 전달·로그아웃(Task 7), README(Task 8), DoD의 E2E(별도 섹션) — 스펙 전 항목 매핑됨.
- **테스트 개수 추적:** 시작 13개 → Task 2 +3 (16) → Task 3 +4 (20) → Task 4 +4 (24).
  Task 4 Step 6, Task 5 Step 3, Task 8 Step 2의 기대 개수(24개)와 일치.
- **타입 일관성:** `Admin(String username, String encodedPassword)` 생성자가 Task 2 정의 ↔
  Task 3·4 테스트 ↔ Task 5 DataSeeder 사용처 일치. `LoginResponse(token, expiresAt)` ↔ 프론트
  `LoginResponse` 인터페이스(`token`, `expiresAt`) 일치. `getSuppliers(token)`/
  `getAdminProducts(token, supplierId?)` 시그니처가 Task 6 정의 ↔ Task 7 호출부 일치.
  `UnauthorizedException`은 Task 3에서 정의되고 같은 Task의 AuthService에서 사용.
- **의존성/임포트 정확성:** SB 4.0.6 Security 아티팩트명은 Initializr로 확인된 값. 테스트 임포트는
  기존 테스트와 동일한 SB4 경로. Next.js 16 proxy.ts 규약은 설치된 버전의 공식 문서로 확인된 값.
- **플레이스홀더:** 없음 — 모든 단계에 실제 코드 포함.
- **실행 순서 의존성:** Task 1→2→3→4→5는 순서 필수 (의존성→엔티티→로그인→보호→시드).
  Task 6→7은 순서 필수이며 **Task 6은 커밋하지 않고 Task 7과 묶어 커밋** (중간 상태가 빌드 깨짐).
  Task 8은 마지막.
- **주의(실행자용):** Task 4에서 기존 테스트 수정 시 스토어 경로(`/api/products`)에는 jwt()를
  붙이지 않는다 (개방 검증이 목적). Spring Security 7 API가 플랜 코드와 다를 경우(메서드 시그니처 등)
  설치된 버전의 공식 문서를 확인하고 최소 조정 후 보고할 것.
