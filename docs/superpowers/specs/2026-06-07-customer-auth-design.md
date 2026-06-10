# 고객 회원가입/로그인(Customer Auth) 설계

> 작성일: 2026-06-07 · 사이클: 고객 인증(핵심) · 상태: 설계 승인됨

## 목표

스토어 고객(Customer) 계정을 도입한다. 이번 사이클 범위는 **핵심만** — 회원가입, 로그인, 로그아웃, 토큰(access+refresh) 발급. 이메일 검증·비밀번호 재설정·프로필은 범위 밖(YAGNI). 이 사이클은 이후 장바구니/주문 도메인의 전제 조건이다.

## 배경 · 재사용 자산

어드민 인증 사이클에서 다음이 이미 갖춰져 있어 그대로 재사용한다.

- `PasswordEncoder`(BCrypt), `JwtEncoder`/`JwtDecoder`(HS256) — `SecurityConfig` 빈
- `RefreshTokenService` — opaque 토큰 발급(SHA-256 해시 저장), 회전, 재사용 탐지, 폐기
- `LoginAttemptService` — IP 기준 인메모리 고정 윈도우 시도 제한
- 타이밍 공격 완화 패턴(미존재 계정에도 더미 BCrypt 검증)
- `TokenResponse`/`RefreshRequest` DTO, `{"message":...}` 에러 형식

## 핵심 설계 결정

### 결정 1: 식별자 — 이메일

고객 로그인 식별자는 `email`(유니크). 어드민은 `username`을 유지한다.

### 결정 2: 엔티티 모델 — 분리 (Customer 별도)

`Admin`과 `Customer`를 별도 엔티티로 둔다(통합 User+role 비채택 — 작동·머지된 어드민 인증을 대공사하지 않기 위함). `Customer`는 인증 전용인 이번 사이클에서 `com.ecommerce.auth` 패키지에 `Admin`과 나란히 둔다.

### 결정 3: refresh 토큰 일반화 — 다형 소유

검증된 `RefreshTokenService`(회전·재사용 탐지)를 어드민·고객이 공유하도록 일반화한다. 토큰 복제(별도 CustomerRefreshToken) 대신 단일 테이블 다형 소유를 택한다.

- `RefreshToken`: `@ManyToOne Admin admin`(admin_id FK) → `OwnerType ownerType`(ADMIN|CUSTOMER) + `Long ownerId`로 교체.
- 트레이드오프: 다형 연관이라 DB FK(참조 무결성)를 포기한다. 단일 인스턴스·소규모 운영에서 수용 가능.
- 부가 효과: 기존 `@EntityGraph("admin")` lazy 로딩 처리가 불필요해져 코드가 단순해진다(스칼라 필드).

### 결정 4: 권한 분리 — JWT role 클레임 (보안 구멍 차단)

현재 `SecurityConfig`는 `/api/admin/**`에 `.authenticated()`만 건다. 고객 JWT도 같은 시크릿으로 서명되므로, role 구분이 없으면 **고객 토큰으로 어드민 API에 접근 가능**한 구멍이 있다. 이를 막는 것이 이번 사이클의 보안 핵심이다.

- access JWT 발급 시 `.claim("role", "ADMIN"|"CUSTOMER")` 추가.
- `JwtAuthenticationConverter`로 `role` 클레임 → `ROLE_ADMIN`/`ROLE_CUSTOMER` 권한 매핑.
- `/api/admin/**`(permitAll 인증 경로 제외) → `.hasRole("ADMIN")`.
- 이번 사이클엔 고객 보호 엔드포인트가 없으므로 `ROLE_CUSTOMER` 매처는 추가하지 않는다(YAGNI). role 클레임은 미리 심어 기반만 마련.

---

## 데이터 모델

### Customer (신규, `com.ecommerce.auth`)

```
Customer
  id        Long  (PK, IDENTITY)
  email     String(unique, not null)   ← 로그인 식별자
  password  String(not null)           ← BCrypt 해시
  createdAt LocalDateTime(not null, updatable=false)
```

`Admin`과 동일 형태(식별자만 username→email). `@PrePersist`로 `createdAt` 세팅.

### CustomerRepository (신규)

- `Optional<Customer> findByEmail(String email)`
- `boolean existsByEmail(String email)`

### RefreshToken (수정 — 다형 소유)

```
변경 전: @ManyToOne(LAZY) Admin admin   // admin_id FK, @EntityGraph 필요
변경 후: @Enumerated(STRING) OwnerType ownerType
        Long ownerId
```

- 신규 `OwnerType { ADMIN, CUSTOMER }` enum.
- 인덱스: `tokenHash` 유니크 유지 + `(ownerType, ownerId)` 복합 인덱스 추가.
- 생성자/게터를 `ownerType`/`ownerId` 기준으로 교체.

### TokenOwner (신규 값 객체)

```java
record TokenOwner(OwnerType type, Long id) {}
```

`RefreshTokenService`의 발급/회전 결과가 주고받는 소유자 신원.

---

## 백엔드 인증 흐름

### RefreshTokenService (일반화)

- `IssuedToken issue(TokenOwner owner)`
- `RotationResult rotate(String presentedToken)` — `RotationResult(TokenOwner owner, IssuedToken refresh)`
- `void revoke(String presentedToken)` — 멱등(불변)
- 내부 재사용 탐지: `revokeAllByOwner(ownerType, ownerId)`, 만료 정리: `deleteExpiredByOwner(ownerType, ownerId, now)`
- `rotate`는 소유자 신원만 반환 → 각 AuthService가 자기 엔티티를 id로 로드해 access JWT 발급.

### RefreshTokenRepository (수정)

- `findByTokenHash(String)` — `@EntityGraph` 제거(스칼라 필드라 불필요)
- `revokeAllByOwner(@Param OwnerType, @Param Long)` — `@Modifying`
- `deleteExpiredByOwner(@Param OwnerType, @Param Long, @Param Instant now)` — `@Modifying`

### AuthService (어드민, 수정)

- `issue(new TokenOwner(ADMIN, admin.getId()))`로 호출.
- `refresh`: rotate 결과의 `owner.type() != ADMIN`이면 401, 아니면 `adminRepository.findById(owner.id())`로 로드해 access 발급.
- access JWT에 `.claim("role", "ADMIN")` 추가.
- 동작은 동일, 시그니처만 변경 — 기존 테스트가 회귀를 잡는다.

### CustomerAuthService (신규)

- `register(RegisterRequest)`: `existsByEmail` 선검사 → 중복이면 `ConflictException`(409). 아니면 BCrypt 인코딩 후 저장, **즉시 토큰 발급(auto-login)** → `TokenResponse`.
- `login(CustomerLoginRequest)`: email 조회 → 미존재 시에도 더미 BCrypt 검증(타이밍 완화) 후 401, 비번 불일치 401. 성공 시 토큰 발급.
- `refresh(String)`: rotate 결과 `owner.type() != CUSTOMER`면 401, 아니면 `customerRepository.findById`로 로드해 access(`role=CUSTOMER`) 발급.
- `logout(String)`: `refreshTokenService.revoke`.
- access JWT subject = email, `.claim("role", "CUSTOMER")`.

### CustomerAuthController (신규, base `/api/store/auth`)

| 메서드 | 경로 | 동작 | 응답 |
|--------|------|------|------|
| POST | `/register` | 가입 + auto-login | 201 + `TokenResponse` |
| POST | `/login` | 로그인 | 200 + `TokenResponse` |
| POST | `/logout` | refresh 폐기 | 204 |
| POST | `/refresh` | 고객 refresh 회전 | 200 + `TokenResponse` |

- 로그인 시도 제한: `LoginAttemptService` 재사용, 키를 `"customer:" + clientIp`로 분리해 어드민 버킷과 격리. 초과 시 429.
- `clientIp` 추출은 어드민 컨트롤러 패턴(X-Forwarded-For 첫 항목→remoteAddr) 동일.

### SecurityConfig (수정)

- permitAll에 추가(POST): `/api/store/auth/register`, `/api/store/auth/login`, `/api/store/auth/refresh`, `/api/store/auth/logout`.
- `/api/admin/**` → `.hasRole("ADMIN")`.
- `JwtAuthenticationConverter` 빈: `role` 클레임 → `ROLE_*` 권한.
- CORS는 이미 `/api/**` 커버 — 변경 없음.

### DTO

- `RegisterRequest(@Email @NotBlank email, @NotBlank password)` (신규)
- `CustomerLoginRequest(@Email @NotBlank email, @NotBlank password)` (신규 — 지금은 register와 동일하나 향후 분기 여지로 분리)
- `TokenResponse`, `RefreshRequest` 재사용

### 에러 규약

| 상황 | 상태 | 처리 |
|------|------|------|
| 입력 검증 실패 | 400 | `@Valid` + 기존 핸들러 |
| 이메일 중복 | 409 | `ConflictException`(신규) + 핸들러 |
| 자격 실패(미존재/불일치) | 401 | `UnauthorizedException`, 타이밍 무구분 |
| 시도 초과 | 429 | `TooManyAttemptsException`(기존) |
| refresh 무효/타입 불일치 | 401 | `UnauthorizedException` |

모두 `{"message":...}` 형식 유지.

---

## 프론트엔드

### 라우트/페이지 (스토어 루트 레벨)

| 경로 | 종류 | 동작 |
|------|------|------|
| `/register` | 페이지 + Server Action | register 호출 → auto-login 토큰 쿠키 set → `/` |
| `/login` | 페이지 + Server Action | login 호출 → 쿠키 set → `/` |
| `/logout` | Route Handler | 백엔드 logout(refresh 폐기) + 쿠키 삭제 → `/login` |

### 쿠키 (`lib/auth-cookies.ts` 확장)

- 어드민과 충돌 방지 위해 별도 이름: `customer_token`, `customer_refresh`.
- `authCookieOptions`(httpOnly·sameSite=lax·secure(운영)·maxAge) 공유, 쿠키 이름 상수만 추가.

### lib/api.ts

- 고객 함수 추가: `registerCustomer`, `customerLogin`, `customerLogout`, `customerRefresh` — 각 `/api/store/auth/*` 호출, `TokenResponse` 타입 재사용.

### 스토어 네비게이션 (최소)

- 헤더에서 `customer_token` 쿠키 유무로 "로그인/회원가입" ↔ "로그아웃" 링크 전환(서버 컴포넌트에서 쿠키 읽어 분기).

### 의도적 제외 (YAGNI)

- 고객용 401 자동 갱신 Route Handler 및 `proxy.ts` 스토어 보호: 이번 사이클엔 고객 보호 페이지가 없으므로 미도입. refresh 토큰·쿠키는 발급해 기반만 마련하고, 자동 갱신 플러밍은 보호 페이지(장바구니/주문) 사이클에서 추가.
- `proxy.ts`는 현재 `/admin/:path*`만 보호 — 변경 없음.

---

## 테스트 전략 (TDD, 기존 패턴 준수)

기존 관행: `@DataJpaTest`/`MockMvc`/Jackson3(`tools.jackson`)/시각 주입(`Supplier<Instant>`).

### 백엔드 신규

- `CustomerAuthServiceTest`: 가입 성공, 이메일 중복 409, 로그인 성공/실패, 미존재 email도 BCrypt 검증 수행(타이밍).
- `CustomerAuthControllerTest`: register(201·auto-login 토큰)·login·logout·refresh 전 흐름, 409 중복, 429 시도 초과, 옛 refresh 재사용 401.

### 백엔드 갱신 (회귀 보호)

- `RefreshTokenServiceTest`: `Admin` → `TokenOwner(ADMIN, id)` 시그니처 갱신 + 고객 owner 회전/재사용 탐지 케이스 추가.
- `AuthControllerTest`(어드민): 일반화된 refresh로도 기존 흐름 유지.
- `SecurityProtectionTest`: **고객 JWT로 `/api/admin/**` 접근 시 차단(403/401)** 회귀 추가 — 이번 사이클의 보안 핵심.

### 프론트

- `npm run build` + `npm run lint` 통과(신규 `/login`·`/register` 라우트 등록, 타입 에러 없음).

---

## 문서 동기화

- `README.md`: 고객 인증 기능 반영, 보안 한계 갱신.
- `docs/ROADMAP.md`: 완료 사이클 표에 "고객 인증" 추가. **사이클 5(토큰 수명)·6(고정 윈도우)이 "머지 대기"로 잘못 남아있는 것을 "머지됨(PR #8/#9)"으로 정정**.

---

## Definition of Done

- `cd backend && ./gradlew test` 전체 통과(신규 고객 테스트 + 일반화 회귀 + 보안 차단 회귀).
- `cd frontend && npm run build` 통과(`/register`·`/login` 라우트 등록).
- 고객 가입 시 `customer_token`·`customer_refresh` 쿠키 2개 set(둘 다 HttpOnly), auto-login으로 `/` 진입.
- 고객 로그인/로그아웃 동작, 로그아웃 시 백엔드 refresh 폐기 + 쿠키 삭제.
- **고객 JWT로 `/api/admin/**` 접근 시 차단**(테스트로 고정).
- 어드민 인증 전 기능 회귀 없음.
- README/ROADMAP 동기화(사이클 5·6 머지 상태 정정 포함).
