# 보안 보완(Security Hardening) 설계

> 작성: 2026-06-04 · 선행 사이클: [어드민 인증](2026-06-03-admin-auth-design.md)

## 1. 목적과 범위

어드민 인증 사이클에서 식별된 골격 한계 중 운영 준비에 시급한 항목들을 해소한다.
가장 큰 변화는 토큰 저장을 **클라이언트 `document.cookie`(JS 노출) → httpOnly 쿠키(Server Action)**로 전환하는 것이다.

### 포함 (In scope) — 핵심 8건

| # | 항목 | 영역 | 규모 |
|---|------|------|------|
| 1 | httpOnly 쿠키 전환 (+ SameSite=Lax) | 프론트 | 중 |
| 2 | 401/로그아웃 시 쿠키 즉시 삭제 | 프론트 | 소 |
| 3 | 로그인 시도 제한 (IP 기준 brute-force 방어) | 백엔드 | 중 |
| 4 | 타이밍 공격 완화 (더미 BCrypt) | 백엔드 | 소 |
| 5 | 보안 한계 문서 단일 출처화 | 문서 | 소 |
| 6 | 만료/무효 토큰 → 401 테스트 추가 | 테스트 | 소 |
| 7 | Spring Security 7 deprecated API 정리 | 백엔드 | 소 |
| 8 | 테스트 전용 JWT 시크릿 프로파일 | 백엔드 | 소 |

### 제외 (Out of scope, 다음 사이클)

- **리프레시 토큰** (1시간마다 재로그인 — UX 성격, 별도 사이클)
- 토큰 서버 측 무효화(블랙리스트)
- 권한(Role) 세분화, 고객 회원가입
- 다중 인스턴스 분산 환경의 시도 제한(인메모리 → 외부 저장소)

## 2. 아키텍처 변화 — httpOnly 전환

### 2.1 현재 (취약)

```
[로그인 폼(client)] ──fetch──▶ [백엔드 POST /api/admin/login] (CORS 직접 호출)
        │ {token, expiresAt}
        ▼
document.cookie = "admin_token=..."   ← httpOnly 아님, JS로 읽힘 → XSS 취약
        │
[어드민 페이지(server component)] cookies()로 토큰 읽어 Bearer 호출
```

### 2.2 변경 후

```
[로그인 폼(client)] ──username/pw──▶ [Server Action: loginAction]
                                          │ (서버 측 fetch)
                                          ▼
                              [백엔드 POST /api/admin/login]
                                          │ {token, expiresAt}
                                          ▼
              cookies().set("admin_token", token, {
                  httpOnly: true, sameSite: "lax",
                  secure: NODE_ENV === "production",
                  maxAge, path: "/" })
                                          │
                  성공 → redirect("/admin") / 실패 → 에러 문자열 반환

[어드민 페이지(server component)] cookies()로 토큰 읽어 Bearer 호출  ← 변경 없음
```

**핵심 원칙:** 토큰을 클라이언트 JS가 읽거나 쓸 수 없다. 설정·삭제는 Server Action에서만, 읽기는 서버 컴포넌트에서만 일어난다.

### 2.3 로그아웃 · 401 처리 (항목 2)

Next.js 제약: `cookies().set()`/삭제는 **Server Action·Route Handler에서만** 가능하고 서버 컴포넌트 렌더 중에는 불가하다. 따라서:

- **로그아웃 Server Action** `logoutAction`: 쿠키 삭제(`cookies().delete("admin_token")`) 후 `/admin/login` 리다이렉트. `LogoutButton`이 이 액션을 호출.
- **401 처리**: 어드민 페이지가 백엔드에서 401(만료/무효 토큰)을 받으면, 직접 쿠키를 못 지우므로 `logoutAction` 경유로 리다이렉트(또는 쿠키를 비우는 전용 경로로 이동)하여 잔존 쿠키를 제거한다. (스펙 7절의 "쿠키 삭제 후 로그인" 문구와 동작 일치)

### 2.4 쿠키 플래그

| 플래그 | 값 | 비고 |
|--------|-----|------|
| `httpOnly` | 항상 true | JS 접근 차단 |
| `sameSite` | `lax` | CSRF 완화. 어드민/스토어 동일 오리진(localhost:3000) |
| `secure` | `NODE_ENV === "production"`일 때만 true | dev(http)에서는 false |
| `path` | `/` | |
| `maxAge` | 토큰 만료까지 초 | 기존과 동일 |

> 참고: 이 쿠키는 Next.js 도메인(localhost:3000)에 저장되고 서버 컴포넌트가 읽어 Bearer로 백엔드에 전달한다. 백엔드(localhost:8080)는 이 쿠키를 직접 보지 않는다. 로그인이 Server Action(서버-서버 fetch)으로 바뀌므로 로그인 경로의 브라우저 CORS 호출은 사라진다.

## 3. 백엔드 변경

### 3.1 로그인 시도 제한 (항목 3)

- **신규 `auth/LoginAttemptService`**: IP 기준 인메모리 카운터.
  - 자료구조: `ConcurrentHashMap<String ip, AttemptRecord>` (실패 횟수 + 잠금 만료 시각).
  - 정책: **5회 연속 실패 → 15분간 해당 IP 차단**. 로그인 성공 시 해당 IP 카운터 리셋.
  - 잠금 중 요청: `429 Too Many Requests` + `{"message": "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요."}`.
- **`AuthController`**: 요청에서 클라이언트 IP 추출(`X-Forwarded-For` 첫 항목 우선, 없으면 `request.getRemoteAddr()`). 로그인 처리 전 차단 여부 확인 → 차단이면 429. 실패 시 `recordFailure(ip)`, 성공 시 `reset(ip)`.
- **임계값·잠금 시간은 상수 또는 설정값**으로 두어 테스트에서 조정 가능하게 한다.

> 단일 인스턴스 한계: 인메모리라 다중 인스턴스 배포 시 인스턴스별로 카운트된다. README 보안 한계에 명시한다.

### 3.2 타이밍 공격 완화 (항목 4)

- 현재 `AuthService.login`은 username 부재 시 BCrypt 검증을 건너뛰고 즉시 예외를 던져, 응답 시간으로 계정 존재 여부가 노출된다.
- 변경: `findByUsername` 실패 시에도 **더미 BCrypt 해시**에 대해 `passwordEncoder.matches(rawPassword, DUMMY_HASH)`를 수행한 뒤 동일한 401을 던진다. 더미 해시는 부팅 시 1회 생성하거나 상수로 둔다.

### 3.3 deprecated API 정리 (항목 7)

- `oauth2ResourceServer().jwt(Customizer.withDefaults())` 등 Spring Security 7에서 deprecation 경고가 나는 API를 권장 대체 API로 교체.
- **정확한 대체 API는 추측하지 않고 Spring Security 7 문서(또는 `node_modules`/의존성 소스)로 확인 후 적용**한다.
- 검증: 빌드 로그에서 해당 deprecation 경고 소멸.

### 3.4 테스트 전용 JWT 시크릿 (항목 8)

- 현재 테스트가 운영 디폴트 `jwt.secret`을 사용한다. `application-test.yml`(또는 해당 테스트의 `@TestPropertySource`)에 테스트 전용 시크릿을 분리한다.

## 4. 프론트엔드 변경

| 파일 | 변경 |
|------|------|
| `app/admin/login/actions.ts` (신규) | `"use server"` — `loginAction(formData/인자)`: 백엔드 로그인 호출 → 성공 시 httpOnly 쿠키 set + `/admin` 리다이렉트, 실패 시 에러 메시지 반환. `logoutAction()`: 쿠키 삭제 + `/admin/login` 리다이렉트 |
| `app/admin/login/page.tsx` (수정) | `document.cookie` 제거. 폼이 `loginAction` 호출, 반환된 에러 표시 (클라이언트 컴포넌트 유지 + 액션 호출, 또는 폼 `action` 바인딩) |
| `app/admin/LogoutButton.tsx` (수정) | `document.cookie` 제거 → `logoutAction` 호출 |
| `app/admin/suppliers/page.tsx`, `products/page.tsx` (수정) | 401 시 `redirect("/admin/login")` 대신 쿠키를 비우는 로그아웃 경로 경유 리다이렉트 |
| `lib/api.ts` | 토큰 읽기/전달 구조는 유지(서버 컴포넌트가 `cookies()`로 읽어 전달). `login()`은 Server Action 내부에서 사용 |

> Next.js 16 주의: `cookies()`는 async(await 필요), `src/proxy.ts`(함수명 `proxy`) 유지. **구현 전 `node_modules/next/dist/docs/`의 관련 가이드를 확인**한다(쿠키 set 옵션·Server Action 시그니처). proxy는 기존대로 쿠키 존재 여부만 검사 — httpOnly여도 서버 미들웨어(proxy)는 쿠키를 읽을 수 있으므로 동작 변화 없음.

## 5. 문서 단일 출처화 (항목 5)

보안 한계가 스펙 9장(4개)·README(6개)·ROADMAP(9개)에 분산되어 갱신 시 세 곳을 손봐야 한다.

- **README를 정본**으로 삼는다. 이번 사이클에서 해소된 항목(httpOnly, 시도 제한, 타이밍)은 "해결됨(2026-06-04 사이클)"으로, 남은 항목(리프레시 토큰, 서버측 무효화)은 "다음 사이클"로 갱신.
- 인증 스펙 9장과 ROADMAP 후보 1 표는 README의 보안 섹션을 가리키도록 정리(중복 목록 제거 또는 링크화).

## 6. 테스트 전략

| 대상 | 테스트 |
|------|--------|
| 만료/무효 토큰 (항목 6) | 과거 `exp`(또는 즉시 만료) 토큰으로 `/api/admin/suppliers` → 401 |
| 시도 제한 (항목 3) | 동일 IP 5회 실패 → 6번째 429 / 성공 시 카운터 리셋 후 재시도 정상 |
| 타이밍 완화 (항목 4) | 없는 username 로그인 → 401(기존 메시지 유지). 가능하면 더미 BCrypt 경로 수행 검증 |
| 기존 인증/어드민 API 테스트 | 그대로 통과 (인증 헬퍼 유지) |
| 프론트 | `npm run build` 통과 + 수동 E2E |

## 7. 검증 기준 (Definition of Done)

1. `./gradlew test` 전체 통과 (신규 + 기존)
2. `cd frontend && npm run build` 통과
3. 브라우저 DevTools에서 `admin_token` 쿠키가 **HttpOnly 플래그**를 가지며 `document.cookie`로 읽히지 않음
4. 동일 IP 6회 로그인 실패 → 429 응답
5. 로그아웃·만료 토큰 시 쿠키가 비워지고 `/admin/login`으로 이동
6. Spring Security deprecation 경고가 빌드 로그에서 사라짐
7. README/스펙/ROADMAP의 보안 한계 기술이 일치(해소 항목 반영)

## 8. 작업 순서(권장)

1. 백엔드 독립 항목 먼저: 타이밍 완화(4) → 시도 제한(3) → deprecated 정리(7) → 테스트 시크릿(8) → 만료토큰 테스트(6)
2. 프론트 httpOnly 전환(1) + 로그아웃/401(2) — Server Action 도입
3. 문서 동기화(5) 마지막에 실제 변경 반영
