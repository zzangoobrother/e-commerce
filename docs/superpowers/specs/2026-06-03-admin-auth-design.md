# 어드민 인증(Admin Auth) 설계

## 1. 목적과 범위

현재 어드민 API(`/api/admin/**`)가 완전히 개방되어 있어 누구나 공급사/상품을 수정·삭제할 수 있다.
어드민 로그인(JWT)을 도입해 어드민 API와 어드민 화면을 보호한다.

### 포함 (In scope)

1. 백엔드: Spring Security + JWT(HS256) — 로그인 API, `/api/admin/**` 보호
2. 어드민 계정: 시드 1개 (환경변수 오버라이드 가능, BCrypt 해싱)
3. 프론트: 로그인 페이지, 토큰 쿠키 저장, middleware 경로 보호, 로그아웃
4. 기존 어드민 API 테스트의 인증 대응 (테스트 헬퍼)

### 제외 (Out of scope, 이후 사이클)

- 고객(스토어) 회원가입/로그인
- 리프레시 토큰, 서버 측 토큰 무효화(블랙리스트)
- 권한(Role) 세분화 — 어드민 단일 역할만
- 어드민 계정 CRUD 화면
- httpOnly 쿠키 + CSRF 방어 고도화 (골격에서는 일반 쿠키)

## 2. 기술 스택 / 의존성

- 기존: Java 25, Spring Boot 4.x, Spring Data JPA, H2(테스트), Next.js 16
- **추가 의존성 (backend/build.gradle.kts — Spring Initializr 4.0.6 기준 확인 완료):**
  - `org.springframework.boot:spring-boot-starter-security`
  - `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` (Nimbus JWT 인코더/디코더 포함)
  - 테스트: `org.springframework.boot:spring-boot-starter-security-test`,
    `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test`
- 프론트 추가 의존성: 없음 (Next.js 내장 `cookies()`, `middleware` 사용)

## 3. 아키텍처 개요

```
[어드민 브라우저]
   │ (1) /admin 접근
   ▼
[Next.js middleware] ─ admin_token 쿠키 없음 → /admin/login 리다이렉트
   │ (2) 로그인 폼 제출
   ▼
[POST /api/admin/login] ─ AuthService: username/password(BCrypt) 검증
   │ (3) 200 {token, expiresAt}     └ 실패 → 401 {"message": ...}
   ▼
[쿠키 저장: admin_token] → /admin 이동
   │ (4) 어드민 페이지(서버 컴포넌트): cookies()로 토큰 읽기
   ▼
[GET /api/admin/** + Authorization: Bearer <token>]
   │
[SecurityFilterChain] ─ JwtDecoder(HS256) 검증 OK → 컨트롤러
                      └ 토큰 없음/무효/만료 → 401 {"message": ...}

[스토어 화면/API(/api/products/**)] — 인증 없음, 변경 없음
```

## 4. 백엔드 설계

### 4.1 `auth` 도메인 패키지 (신규)

| 파일 | 책임 |
|------|------|
| `auth/Admin.java` | 어드민 계정 엔티티 — id, username(유니크, not null), password(BCrypt 해시), createdAt |
| `auth/AdminRepository.java` | `Optional<Admin> findByUsername(String)`, `boolean existsByUsername(String)` |
| `auth/AuthService.java` | 로그인: username 조회 → BCrypt 매칭 → JWT 발급. 실패 시 401 예외 |
| `auth/AuthController.java` | `POST /api/admin/login` |
| `auth/dto/LoginRequest.java` | `record(String username, String password)` — `@NotBlank` |
| `auth/dto/LoginResponse.java` | `record(String token, Instant expiresAt)` |

### 4.2 `common/SecurityConfig.java` (신규)

- `SecurityFilterChain` 빈:
  - `POST /api/admin/login` → permitAll
  - `/api/admin/**` → authenticated (JWT)
  - 그 외 전부 → permitAll (스토어 API, H2 콘솔 등)
  - CSRF 비활성 (토큰 기반 stateless API), 세션 STATELESS
  - 기존 CORS 설정(WebConfig)과 연동 — `Authorization` 헤더 허용 확인
- `PasswordEncoder` 빈: BCrypt
- `JwtEncoder`/`JwtDecoder` 빈: HS256 대칭키, 시크릿 `${JWT_SECRET:기본값}`
  - HS256은 시크릿이 **최소 256비트(32바이트) 이상**이어야 함 — 개발용 기본값도 32자 이상의 문자열로 지정
    (예: `dev-only-secret-key-change-me-in-production-1234`)
- 인증 실패(401) 응답을 기존 `{"message": "..."}` JSON 형식으로 통일 (AuthenticationEntryPoint 커스텀)

### 4.3 JWT 정책

- 클레임: `sub`(username), `iat`, `exp`
- 만료: 발급 후 1시간
- 서명: HS256, 시크릿은 환경변수 (개발 기본값 내장)

### 4.4 어드민 계정 시드 (DataSeeder 확장)

- `${ADMIN_USERNAME:admin}` / `${ADMIN_PASSWORD:admin1234}` 환경변수
- BCrypt 해싱 후 저장, `existsByUsername` 멱등 체크 (기존 시드 패턴과 동일)
- username 유니크 제약 (DB 레벨 방어 — Supplier.name 패턴과 동일)

### 4.5 기존 테스트 영향

- `SupplierControllerTest`, `ProductApiTest`의 어드민 API 호출에 토큰 필요
- 해결: 테스트 헬퍼(또는 `@BeforeEach`)로 로그인 → 토큰 발급 → 요청에 헤더 부착
- 시드 계정은 `@Profile("!test")`라 테스트에서 비활성 → 테스트는 자체적으로 어드민 계정을 저장 후 로그인

## 5. API 표면 변경

| 메서드 · 경로 | 변경 | 설명 |
|------|------|------|
| `POST /api/admin/login` | **신규** | 로그인. 성공 200 `{token, expiresAt}` / 실패 401 |
| `/api/admin/suppliers/**` | **보호** | Bearer 토큰 필수. 미인증 401 |
| `/api/admin/products/**` | **보호** | Bearer 토큰 필수. 미인증 401 |
| `/api/products/**` | 유지 | 익명 접근 (변경 없음) |

## 6. 프론트엔드 설계

### 6.1 신규/변경 파일

| 파일 | 책임 |
|------|------|
| `app/admin/login/page.tsx` (신규) | 로그인 폼 — 클라이언트 컴포넌트. 성공 시 쿠키 저장 + `/admin` 이동, 실패 시 에러 메시지 |
| `src/proxy.ts` (신규) | `/admin/*` 접근 시 `admin_token` 쿠키 없으면 `/admin/login` 리다이렉트 (`/admin/login` 자신은 제외). **Next.js 16에서 middleware가 proxy로 개명됨** — 파일명 `proxy.ts`, 내보내는 함수명 `proxy` |
| `lib/api.ts` (확장) | `login(username, password)` 추가. 어드민 API 함수들에 `token` 파라미터 추가 → `Authorization: Bearer` 헤더 부착 |
| `app/admin/page.tsx` 등 어드민 3개 페이지 (수정) | `cookies()`로 토큰 읽어 API 호출에 전달. 401 시 `/admin/login` 리다이렉트. 로그아웃 버튼 |

### 6.2 토큰 저장: 쿠키 (`admin_token`)

- 로그인 성공 시 `document.cookie`로 저장 (`path=/; max-age=3600`)
- 서버 컴포넌트가 `next/headers`의 `cookies()`로 읽음 → 기존 SSR 구조 유지
- 로그아웃 = 쿠키 삭제(max-age=0) + `/admin/login` 이동
- 골격 한계(문서화): httpOnly가 아니므로 XSS에 취약 — 운영 전 httpOnly 쿠키 + 서버 액션 전환 권장

### 6.3 proxy(구 middleware) 동작

- 매칭: `/admin/:path*` (단, `/admin/login` 제외)
- 검사: `admin_token` 쿠키 **존재 여부만** (서명/만료 검증은 백엔드 책임)
- 만료된 토큰으로 통과해도 백엔드가 401 → 페이지에서 로그인 리다이렉트 (이중 방어)

## 7. 에러 처리

| 상황 | 동작 |
|------|------|
| 로그인 실패 (잘못된 계정/비밀번호) | 401 `{"message": "아이디 또는 비밀번호가 올바르지 않습니다."}` → 폼에 표시 |
| 토큰 없이 어드민 API 호출 | 401 `{"message": "인증이 필요합니다."}` |
| 만료/무효 토큰 | 401 동일 형식 → 프론트가 쿠키 삭제 후 로그인 페이지로 |
| 스토어 API | 영향 없음 |

## 8. 테스트 전략

| 대상 | 테스트 |
|------|--------|
| `AuthController` | 로그인 성공(200+토큰), 실패(401), 빈 입력(400) |
| 보호 규칙 | 토큰 없이 `/api/admin/suppliers` → 401, 유효 토큰 → 200, 스토어 API는 토큰 없이 200 |
| 기존 어드민 API 테스트 | 테스트 헬퍼로 토큰 발급 후 기존 시나리오 그대로 통과 |
| `Admin` 엔티티/리포지토리 | 저장/조회, username 유니크 제약 |
| 프론트 | `npm run build` 통과 + 수동 E2E (로그인 → 어드민 → 로그아웃) |

## 9. 보안 고려사항 및 골격 한계

- BCrypt 해싱, JWT 시크릿/계정 환경변수 외부화 — 적용
- **한계 (운영 전 보완 필요, 코드 주석/README에 명시):**
  - 일반 쿠키 저장 (httpOnly 아님) → XSS 취약
  - 리프레시 토큰 없음 → 1시간마다 재로그인
  - 토큰 서버 측 무효화 불가 (로그아웃은 클라이언트 쿠키 삭제만)
  - 로그인 시도 제한(brute-force 방어) 없음

## 10. 검증 기준 (Definition of Done)

1. `./gradlew test` 전체 통과 (신규 인증 테스트 + 기존 테스트 인증 대응 포함)
2. `cd frontend && npm run build` 통과 (`/admin/login` 라우트 등록)
3. 토큰 없이 `curl /api/admin/suppliers` → 401, 로그인 후 토큰으로 → 200
4. 토큰 없이 `curl /api/products` → 200 (스토어 영향 없음)
5. 브라우저: `/admin` 접근 → 로그인 페이지 리다이렉트 → 로그인 → 어드민 화면 정상 동작 → 로그아웃 → 다시 리다이렉트
