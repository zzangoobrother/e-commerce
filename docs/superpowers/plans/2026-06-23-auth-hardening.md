# 인증 하드닝 3건 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인계된 Minor 보안 3건(로그아웃 GET 측면효과·register 레이트리밋·revoke ownerType 가드)을 한 사이클로 처리한다.

**Architecture:** 모두 기존 구조를 따르는 작은 하드닝이다. ③ revoke에 기대 ownerType를 받아 refresh 가드를 대칭으로 완성하고(불일치 no-op), ② register는 기존 `LoginAttemptService`를 `"register:"` 버킷으로 재사용해 모든 시도를 계수하며, ① 프론트는 refresh 라우트가 갱신 실패 시 직접 쿠키를 지우게 하고 logout 라우트에서 GET을 제거한다.

**Tech Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security(JWT) · JPA/Hibernate · H2(test) · JUnit5/MockMvc · Next.js 16.2.6(Route Handler) · React 19

설계 문서: `docs/superpowers/specs/2026-06-23-auth-hardening-design.md`

## Global Constraints

- **언어:** 코드 주석·커밋 메시지·문서는 한국어, 식별자는 영어(프로젝트 규약).
- **무변경:** 새 인프라(Redis 등) 금지 — `LoginAttemptService` 인메모리 카운터 재사용. `OwnerType`·`SecurityConfig`·`GlobalExceptionHandler`의 기존 429 매핑은 그대로 활용한다.
- **멱등성 우선:** 로그아웃은 어떤 경우에도 측면효과 없이 204를 반환한다(미존재·타입 불일치 모두 no-op).
- **Next.js 16:** `cookies()`는 async(await). Route Handler의 `NextResponse.redirect`는 예외를 던지지 않는다.

---

## 파일 구조

**백엔드 수정**
- `auth/RefreshTokenService.java` — `revoke(String, OwnerType)`로 시그니처 변경, 타입 일치 시에만 폐기
- `auth/AuthService.java` — `logout`이 `revoke(token, OwnerType.ADMIN)` 호출
- `auth/CustomerAuthService.java` — `logout`이 `revoke(token, OwnerType.CUSTOMER)` 호출
- `auth/CustomerAuthController.java` — `register`에 `"register:"` 레이트리밋 가드 추가

**백엔드 테스트 수정**
- `auth/RefreshTokenServiceTest.java` — `revoke` 호출 2곳 시그니처 보정 + 타입 불일치 no-op 테스트 추가
- `auth/CustomerAuthControllerTest.java` — 로그아웃 교차가드 2건 + register 레이트리밋 1건 추가

**프론트 수정**
- `frontend/src/app/refresh/route.ts` — 실패 시 쿠키 직접 삭제 후 `/login`
- `frontend/src/app/admin/refresh/route.ts` — 실패 시 쿠키 직접 삭제 후 `/admin/login`
- `frontend/src/app/logout/route.ts` — `GET` export 제거(POST 전용)
- `frontend/src/app/admin/logout/route.ts` — `GET` export 제거(POST 전용)

**문서**: `README.md`, `docs/ROADMAP.md`

---

## Task 1: revoke() ownerType 가드 (백엔드, TDD)

로그아웃 폐기 경로가 토큰 소유자 타입을 검사하지 않는 결함을 고친다. refresh(rotate) 경로의 양방향 가드를 로그아웃에도 대칭으로 적용하되, 로그아웃의 멱등성을 위해 불일치는 예외 없이 no-op으로 둔다.

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/AuthService.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/CustomerAuthService.java`
- Modify: `backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java`
- Modify: `backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java`

**Interfaces:**
- Consumes: `RefreshToken.getOwnerType()`, `OwnerType.{ADMIN,CUSTOMER}`(모두 `com.ecommerce.auth` 같은 패키지라 import 불필요), `RefreshTokenService.TokenOwner`.
- Produces: `RefreshTokenService.revoke(String presentedToken, OwnerType expected)` — 기존 `revoke(String)`을 대체(시그니처 변경).

- [ ] **Step 1: 실패하는 서비스 단위 테스트 작성 + 기존 호출 보정**

`backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java`의 기존 `revoke` 호출 2곳을 새 시그니처로 바꾼다.

121번째 줄 부근:
```java
        service.revoke(token.token());
```
→
```java
        service.revoke(token.token(), OwnerType.ADMIN);
```

128~131번째 줄 부근:
```java
    @Test
    void revoke는_존재하지_않는_토큰에도_조용히_통과한다() {
        RefreshTokenService service = service(Instant::now);
        service.revoke("nonexistent");
    }
```
→
```java
    @Test
    void revoke는_존재하지_않는_토큰에도_조용히_통과한다() {
        RefreshTokenService service = service(Instant::now);
        service.revoke("nonexistent", OwnerType.ADMIN);
    }

    @Test
    void revoke는_타입이_불일치하면_폐기하지_않는다() {
        RefreshTokenService service = service(Instant::now);
        IssuedToken token = service.issue(ADMIN_1);

        // 어드민 토큰을 고객 타입으로 폐기 시도 → no-op
        service.revoke(token.token(), OwnerType.CUSTOMER);

        // 여전히 유효 → 회전 성공
        RotationResult result = service.rotate(token.token());
        assertThat(result.owner()).isEqualTo(ADMIN_1);
    }
```

- [ ] **Step 2: 컴파일 실패 확인**

Run: `cd backend && ./gradlew compileTestJava`
Expected: FAIL — `revoke(String, OwnerType)` 메서드 없음.

- [ ] **Step 3: revoke 시그니처 변경**

`backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java`의 기존 `revoke` 메서드(84~87번째 줄 부근)를 교체:
```java
    // 로그아웃 — 제출된 토큰을 폐기(미존재/타입 불일치/중복이어도 멱등).
    // expected와 소유자 타입이 일치할 때만 폐기해 교차 타입 폐기를 막는다(refresh 가드와 대칭).
    @Transactional
    public void revoke(String presentedToken, OwnerType expected) {
        repository.findByTokenHash(hash(presentedToken))
                .filter(token -> token.getOwnerType() == expected)
                .ifPresent(RefreshToken::revoke);
    }
```

- [ ] **Step 4: 서비스 호출부 2곳 보정**

`backend/src/main/java/com/ecommerce/auth/AuthService.java`의 `logout`(86~88번째 줄 부근):
```java
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken, OwnerType.ADMIN);
    }
```

`backend/src/main/java/com/ecommerce/auth/CustomerAuthService.java`의 `logout`(101~103번째 줄 부근):
```java
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken, OwnerType.CUSTOMER);
    }
```
(두 서비스 모두 `com.ecommerce.auth` 패키지라 `OwnerType` import 불필요.)

- [ ] **Step 5: 서비스 단위 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.RefreshTokenServiceTest"`
Expected: PASS (기존 + 신규 `revoke는_타입이_불일치하면_폐기하지_않는다`).

- [ ] **Step 6: 컨트롤러 교차가드 통합 테스트 2건 추가**

`backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java`의 `존재하지_않는_refresh로_로그아웃해도_204를_반환한다` 테스트(218~224번째 줄 부근) 아래에 추가:
```java
    @Test
    void 어드민_refresh_토큰을_고객_로그아웃에_쓰면_204지만_어드민_토큰은_폐기되지_않는다() throws Exception {
        adminRepository.save(new Admin("admin", passwordEncoder.encode("admin1234")));
        String adminLoginBody = objectMapper.writeValueAsString(
                Map.of("username", "admin", "password", "admin1234"));
        String adminResp = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON).content(adminLoginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String adminRefresh = objectMapper.readTree(adminResp).get("refreshToken").asString();
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", adminRefresh));

        // 고객 로그아웃 경로에 어드민 토큰 → 204(멱등)
        mockMvc.perform(post("/api/store/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        // 어드민 토큰은 폐기되지 않아 어드민 리프레시가 정상 동작
        mockMvc.perform(post("/api/admin/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void 고객_refresh_토큰을_어드민_로그아웃에_쓰면_204지만_고객_토큰은_폐기되지_않는다() throws Exception {
        String customerRefresh = registerAndGetRefreshToken("user@example.com", "pw12345678");
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", customerRefresh));

        // 어드민 로그아웃 경로에 고객 토큰 → 204(멱등)
        mockMvc.perform(post("/api/admin/logout")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        // 고객 토큰은 폐기되지 않아 고객 리프레시가 정상 동작
        mockMvc.perform(post("/api/store/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 7: 컨트롤러 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.CustomerAuthControllerTest"`
Expected: PASS (기존 + 신규 교차가드 2건).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java \
        backend/src/main/java/com/ecommerce/auth/AuthService.java \
        backend/src/main/java/com/ecommerce/auth/CustomerAuthService.java \
        backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java \
        backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java
git commit -m "feat: 로그아웃 폐기에 ownerType 가드 추가(타입 불일치 시 no-op, refresh 가드와 대칭)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: register 레이트리밋 (백엔드, TDD)

가입 엔드포인트에 IP 기준 시도 제한을 추가한다. 로그인과 달리 성공도 계수해(리셋 없음) 대량 계정 생성 자체를 상한한다. 기존 `LoginAttemptService`를 `"register:"` 버킷으로 재사용한다.

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/auth/CustomerAuthController.java`
- Modify: `backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java`

**Interfaces:**
- Consumes: `LoginAttemptService.{isBlocked,recordFailure}(String)`, `ClientIp.from(HttpServletRequest)`, `TooManyAttemptsException`(모두 기존, 이미 `CustomerAuthController`에서 사용·import 중).
- Produces: 없음(엔드포인트 동작 변경만).

- [ ] **Step 1: 실패하는 레이트리밋 테스트 작성**

`backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java`의 `동일_IP에서_연속_5회_로그인_실패하면_6회째_429를_반환한다` 테스트(121~136번째 줄 부근) 아래에 추가:
```java
    @Test
    void 동일_IP에서_가입을_5회_초과하면_429를_반환한다() throws Exception {
        // 성공 가입도 계수된다 — 서로 다른 이메일로 5회 모두 성공한 뒤 6회째는 429
        for (int i = 0; i < 5; i++) {
            String body = objectMapper.writeValueAsString(
                    Map.of("email", "user" + i + "@example.com", "password", "pw12345678"));
            mockMvc.perform(post("/api/store/auth/register")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated());
        }

        String body = objectMapper.writeValueAsString(
                Map.of("email", "user5@example.com", "password", "pw12345678"));
        mockMvc.perform(post("/api/store/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }
```
(기본 `login.max-attempts`=5. `@BeforeEach`의 `loginAttemptService.clearAll()`이 테스트 간 카운터를 초기화하므로 다른 테스트의 가입 호출과 섞이지 않는다.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.CustomerAuthControllerTest.동일_IP에서_가입을_5회_초과하면_429를_반환한다"`
Expected: FAIL — 가입에 제한이 없어 6회째도 201이 반환됨.

- [ ] **Step 3: register에 레이트리밋 가드 추가**

`backend/src/main/java/com/ecommerce/auth/CustomerAuthController.java`의 클래스 상단 상수(`BLOCKED_MESSAGE` 아래)에 가입 전용 메시지 추가:
```java
    private static final String REGISTER_BLOCKED_MESSAGE = "가입 시도가 너무 많습니다. 잠시 후 다시 시도하세요.";
```
기존 `register` 메서드(35~39번째 줄 부근)를 교체:
```java
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        // 가입 스팸·자원 고갈 방지 — IP당 윈도우 내 시도(성공 포함)를 계수한다(로그인과 달리 성공해도 리셋 없음).
        String key = "register:" + ClientIp.from(http);
        if (loginAttemptService.isBlocked(key)) {
            throw new TooManyAttemptsException(REGISTER_BLOCKED_MESSAGE);
        }
        loginAttemptService.recordFailure(key);
        return customerAuthService.register(request);
    }
```
(`HttpServletRequest`·`ClientIp`·`TooManyAttemptsException`·`loginAttemptService`는 이미 이 컨트롤러에서 사용 중이라 추가 import·필드 불필요.)

- [ ] **Step 4: 레이트리밋 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.CustomerAuthControllerTest"`
Expected: PASS — 신규 429 테스트 + 기존 전부(각 테스트는 5회 미만 가입이라 영향 없음).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/CustomerAuthController.java \
        backend/src/test/java/com/ecommerce/auth/CustomerAuthControllerTest.java
git commit -m "feat: 회원가입에 IP 레이트리밋 추가(성공 포함 모든 시도 계수, 윈도우당 상한)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 로그아웃 GET 측면효과 제거 (프론트)

측면효과(토큰 폐기·쿠키 삭제)를 가진 GET 핸들러를 제거한다. 단, refresh 라우트가 갱신 실패 시 `/logout`(303→GET)으로 정리를 위임하고 있으므로, 정리 책임을 refresh 라우트로 옮긴 뒤 GET을 제거한다.

**Files:**
- Modify: `frontend/src/app/refresh/route.ts`
- Modify: `frontend/src/app/admin/refresh/route.ts`
- Modify: `frontend/src/app/logout/route.ts`
- Modify: `frontend/src/app/admin/logout/route.ts`

**Interfaces:**
- Consumes: `customerRefresh`/`refresh`/`customerLogout`/`logout`(lib/api), `authCookieOptions`·쿠키 상수(lib/auth-cookies). 모두 기존.
- Produces: 없음(라우트 동작 변경만).

> **주의(동작 변화):** refresh 실패 분기에서 더 이상 백엔드 토큰 폐기를 호출하지 않는다. 실패는 토큰이 이미 무효(거부됨)거나 토큰 부재인 경우라 폐기 대상이 없고, 전이적 오류 시에도 쿠키에서 제거된 토큰은 만료까지 DB에 남았다가 자동 정리된다. 명시적 로그아웃(POST)은 기존대로 백엔드 폐기를 수행한다.

- [ ] **Step 1: 고객 refresh 라우트 — 실패 시 직접 정리**

`frontend/src/app/refresh/route.ts` 전체 교체:
```ts
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { customerRefresh } from "@/lib/api";
import {
  CUSTOMER_ACCESS_COOKIE,
  CUSTOMER_REFRESH_COOKIE,
  authCookieOptions,
} from "@/lib/auth-cookies";

// 고객 access 만료 시 자동 갱신 — customer_refresh 쿠키로 백엔드 refresh 호출.
// 성공: 새 access/refresh 쿠키 set 후 next 경로로. 실패: 쿠키 삭제 후 /login으로(측면효과 GET 제거).
export async function GET(request: Request) {
  const url = new URL(request.url);
  const next = url.searchParams.get("next") ?? "/";
  // 오픈 리다이렉트 방지 — 같은 오리진 상대 경로만 허용("//host"는 프로토콜 상대 URL이라 차단)
  const safeNext = next.startsWith("/") && !next.startsWith("//") ? next : "/";

  const store = await cookies();
  const refreshToken = store.get(CUSTOMER_REFRESH_COOKIE)?.value;
  if (!refreshToken) {
    return clearAndRedirectToLogin(request);
  }

  try {
    const tokens = await customerRefresh(refreshToken);
    const response = NextResponse.redirect(new URL(safeNext, request.url), 303);
    response.cookies.set(CUSTOMER_ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
    response.cookies.set(CUSTOMER_REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));
    return response;
  } catch {
    return clearAndRedirectToLogin(request);
  }
}

// 갱신 실패 — 쿠키를 직접 삭제하고 로그인으로. (이전엔 /logout GET으로 위임했으나 측면효과 GET을 제거)
function clearAndRedirectToLogin(request: Request) {
  const response = NextResponse.redirect(new URL("/login", request.url), 303);
  response.cookies.delete(CUSTOMER_ACCESS_COOKIE);
  response.cookies.delete(CUSTOMER_REFRESH_COOKIE);
  return response;
}
```

- [ ] **Step 2: 어드민 refresh 라우트 — 실패 시 직접 정리**

`frontend/src/app/admin/refresh/route.ts` 전체 교체:
```ts
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { refresh } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE, authCookieOptions } from "@/lib/auth-cookies";

// access 만료 시 자동 갱신 — admin_refresh 쿠키로 백엔드 refresh 호출.
// 성공: 새 access/refresh 쿠키 set 후 next 경로로. 실패: 쿠키 삭제 후 /admin/login으로(측면효과 GET 제거).
export async function GET(request: Request) {
  const url = new URL(request.url);
  const next = url.searchParams.get("next") ?? "/admin";
  // 오픈 리다이렉트 방지 — /admin 하위 경로만 허용
  const safeNext = next.startsWith("/admin") ? next : "/admin";

  const store = await cookies();
  const refreshToken = store.get(REFRESH_COOKIE)?.value;
  if (!refreshToken) {
    return clearAndRedirectToLogin(request);
  }

  try {
    const tokens = await refresh(refreshToken);
    const response = NextResponse.redirect(new URL(safeNext, request.url), 303);
    response.cookies.set(ACCESS_COOKIE, tokens.accessToken, authCookieOptions(tokens.accessExpiresAt));
    response.cookies.set(REFRESH_COOKIE, tokens.refreshToken, authCookieOptions(tokens.refreshExpiresAt));
    return response;
  } catch {
    return clearAndRedirectToLogin(request);
  }
}

// 갱신 실패 — 쿠키를 직접 삭제하고 로그인으로. (이전엔 /admin/logout GET으로 위임했으나 측면효과 GET을 제거)
function clearAndRedirectToLogin(request: Request) {
  const response = NextResponse.redirect(new URL("/admin/login", request.url), 303);
  response.cookies.delete(ACCESS_COOKIE);
  response.cookies.delete(REFRESH_COOKIE);
  return response;
}
```

- [ ] **Step 3: 고객 logout 라우트 — GET 제거**

`frontend/src/app/logout/route.ts` 전체 교체(`export function GET` 삭제, POST만 유지):
```ts
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { customerLogout } from "@/lib/api";
import { CUSTOMER_ACCESS_COOKIE, CUSTOMER_REFRESH_COOKIE } from "@/lib/auth-cookies";

// 고객 로그아웃 — refresh를 백엔드에서 폐기하고 쿠키를 삭제 후 로그인 페이지로. POST 전용(측면효과 GET 제거).
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

export function POST(request: Request) {
  return clearAndRedirect(request);
}
```

- [ ] **Step 4: 어드민 logout 라우트 — GET 제거**

`frontend/src/app/admin/logout/route.ts` 전체 교체(`export function GET` 삭제, POST만 유지):
```ts
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { logout } from "@/lib/api";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "@/lib/auth-cookies";

// 어드민 로그아웃 — refresh를 백엔드에서 폐기하고 access·refresh 쿠키를 삭제 후 로그인 페이지로. POST 전용(측면효과 GET 제거).
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

export function POST(request: Request) {
  return clearAndRedirect(request);
}
```

- [ ] **Step 5: 빌드·린트·GET 제거 확인**

Run: `cd frontend && npm run build && npm run lint`
Expected: PASS — 타입 에러 없음.

Run: `grep -rn "export function GET\|export async function GET" frontend/src/app/logout/route.ts frontend/src/app/admin/logout/route.ts`
Expected: 출력 없음(GET 핸들러 제거 확인).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/app/refresh/route.ts \
        frontend/src/app/admin/refresh/route.ts \
        frontend/src/app/logout/route.ts \
        frontend/src/app/admin/logout/route.ts
git commit -m "fix: 로그아웃 GET 측면효과 제거(refresh 실패 정리를 refresh 라우트로 이동, logout은 POST 전용)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 문서 동기화

**Files:**
- Modify: `README.md`
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: README 갱신**

`README.md`를 Read로 확인한 뒤:
- 보안 관련 서술(시도 제한·인증 한계 등 기존 형식)에 **회원가입 IP 레이트리밋**(성공 포함 윈도우당 상한)과 **로그아웃 폐기의 ownerType 가드**(자기 타입 토큰만 폐기)를 기존 문구 형식에 맞춰 추가한다.
- 실제 문구는 Read로 확인 후 기존 형식에 맞춰 작성한다(추측으로 항목을 만들지 말 것).

- [ ] **Step 2: ROADMAP 갱신**

`docs/ROADMAP.md`를 Read로 확인한 뒤:
- `> 마지막 갱신:` 행을 `> 마지막 갱신: 2026-06-23 (인증 하드닝 사이클)`로 교체.
- 완료된 사이클 표에 행 추가(사이클 12 행 아래):
```markdown
| 13. 인증 하드닝 3건 | 2026-06-23 | 로그아웃 GET 측면효과 제거(refresh 실패 정리 이동·logout POST 전용), 회원가입 IP 레이트리밋(성공 포함 윈도우당 상한), 로그아웃 폐기 ownerType 가드(자기 타입만 폐기·불일치 no-op) | `feature/auth-hardening` 브랜치 (머지 대기) |
```
- 잔여 Minor 항목 서술이 있으면 3건 모두 사이클 13으로 해소되었음을 반영한다.

- [ ] **Step 3: 문서 일관성 확인**

Run: `grep -n "레이트리밋\|ownerType\|로그아웃\|하드닝\|사이클 13" README.md docs/ROADMAP.md`
Expected: 인증 하드닝 3건 서술이 두 문서에서 모순 없이 일치.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/ROADMAP.md
git commit -m "docs: 인증 하드닝 3건 사이클 반영(README 보안·ROADMAP 사이클 13)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 최종 검증 (Definition of Done)

- [ ] `cd backend && ./gradlew test` 전체 통과 (revoke 교차가드 + register 429 + 기존 전부)
- [ ] `cd frontend && npm run build && npm run lint` 통과
- [ ] revoke: 고객·어드민 로그아웃이 자기 타입 토큰만 폐기, 타입 불일치는 204 no-op (테스트로 고정)
- [ ] register: 동일 IP 윈도우당 5회 초과 시 429, 성공 가입도 계수됨 (테스트로 고정)
- [ ] logout/admin logout route에 GET 핸들러 없음, refresh 실패 정리가 refresh 라우트로 이동 (grep·빌드로 고정)
- [ ] 로그아웃은 모든 경우 204 (멱등성 유지)
- [ ] 새 인프라·새 엔티티·새 서비스 없음, `OwnerType`·`SecurityConfig`·`GlobalExceptionHandler` 기존 매핑 재사용
- [ ] README/ROADMAP 동기화, Minor 3건 모두 사이클 13으로 해소 명시
