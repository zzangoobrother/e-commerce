# 보안 보완 2차 — 토큰 수명 주기(Token Lifecycle) 설계

**작성일:** 2026-06-06
**사이클:** 보안 보완 2차 (ROADMAP "후보 1: 토큰 수명 주기")
**범위:** 리프레시 토큰 도입 + 토큰 서버 측 무효화(MySQL 기반). 로그인 시도 제한 공유 저장소(Redis)화는 **이번 범위 제외**(다음 사이클).

---

## 1. 배경 및 목표

현재 어드민 인증은 단일 access token(HS256 JWT, 수명 1시간)만 사용한다. 두 가지 운영 한계가 있다.

- **재로그인 빈도**: 토큰이 1시간마다 만료돼 어드민이 반복 로그인해야 한다.
- **무효화 불가**: access token이 stateless라 로그아웃 후에도 만료 전까지 유효하다. 서버가 토큰을 취소할 수단이 없다.

**목표:** 짧은 수명의 access token + 서버 저장 refresh token(회전·재사용 탐지)을 도입해, 재로그인 빈도를 낮추면서 로그아웃·탈취 시 세션을 무효화할 수 있게 한다.

### 설계 결정 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| access token 수명 | 15분(900초), stateless 유지 | 무효화 윈도우 최소화 + 일반 API 요청 DB 조회 0 유지 |
| refresh token 수명 | 7일(604800초) | 주 1회 재로그인. 어드민 운영 도구의 보안 민감도 고려 |
| refresh token 형태 | opaque 랜덤(256비트), DB에 SHA-256 해시 저장 | DB 유출 시 토큰 유출 방지 |
| 무효화 방식 | refresh 폐기(블랙리스트 없음) | access는 stateless 유지, 최대 15분 자연 만료 |
| 회전 정책 | 회전 + 재사용 탐지 | 탈취된 refresh의 지속 악용 차단 |
| 저장소 | 기존 MySQL | 새 인프라(Redis) 도입 없이 구현 |
| 프론트 갱신 | 401 반응형, `/admin/refresh` Route Handler 경유 | Task 6 패턴과 일관, 갱신 지점 단일화로 race 최소화 |

---

## 2. 아키텍처

```
[브라우저] --(httpOnly 쿠키: admin_token, admin_refresh)--> [Next.js 서버]
   Next 서버 컴포넌트/Route Handler가 쿠키를 읽어 Bearer/refresh로 백엔드 호출
                                  |
                                  v
[Spring Boot 백엔드]
  - access(JWT): SecurityConfig가 stateless 검증 (DB 조회 없음)
  - refresh(opaque): RefreshTokenService가 DB 조회·회전·재사용 탐지
```

- 백엔드는 토큰을 **응답 바디**로 반환한다. 쿠키 set/delete는 Next 서버(Server Action / Route Handler)가 수행한다(Task 6에서 확립한 제약: 서버 컴포넌트는 렌더 중 쿠키를 못 set하므로 Route Handler를 경유).
- access token 검증 경로는 현행과 동일하다(oauth2 resource server, HS256). refresh 경로만 상태를 가진다.

---

## 3. 백엔드 설계

### 3.1 엔티티: `RefreshToken`

`backend/src/main/java/com/ecommerce/auth/RefreshToken.java`

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long (PK, auto) | |
| `admin` | Admin (ManyToOne, FK) | 토큰 소유 어드민 |
| `tokenHash` | String (unique, 64자) | opaque 토큰의 SHA-256 hex |
| `expiresAt` | Instant | 발급 + 7일 |
| `revoked` | boolean | 회전·로그아웃·재사용 탐지로 폐기됨 |
| `createdAt` | Instant | 발급 시각 |

- 인덱스: `tokenHash`(unique), `admin`(재사용 탐지 시 전체 조회용).
- 평문 opaque 토큰은 저장하지 않는다. 조회는 제출된 토큰을 SHA-256 해싱해 `tokenHash`로 매칭한다.

### 3.2 `RefreshTokenService`

`backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java`

```
String issue(Admin admin)
    // 256비트 SecureRandom → base64url opaque 토큰 생성.
    // SHA-256 해시 + expiresAt(now+7일)로 RefreshToken 저장. 평문 토큰 반환(호출자에게 1회만 노출).

RotationResult rotate(String presentedToken)
    // 1. presentedToken을 SHA-256 해싱 → tokenHash로 조회.
    // 2. 미존재 또는 만료 → UnauthorizedException.
    // 3. revoked == true → 재사용 탐지: 해당 admin의 모든 RefreshToken을 revoke 후 UnauthorizedException.
    // 4. 유효 → 기존 토큰 revoke, issue(admin)로 새 토큰 발급. (admin, 새 refresh) 반환.

void revoke(String presentedToken)
    // 로그아웃: 제출된 토큰을 조회해 revoke. 미존재/이미 폐기여도 조용히 통과(멱등).

void purgeExpired(Admin admin)
    // lazy 정리: 해당 admin의 만료(expiresAt < now)된 행 삭제. login/rotate 시 호출.
```

- 시각 의존성은 `Supplier<Instant> clock`으로 주입해 테스트에서 만료를 결정적으로 검증한다(LoginAttemptService와 동일 패턴).
- 재사용 탐지·만료·미존재는 모두 기존 `UnauthorizedException`(401)으로 던진다. 별도 예외 클래스나 `GlobalExceptionHandler` 변경은 두지 않는다(클라이언트 응답은 일반 401과 동일하게 처리해 정보 노출 최소화). 재사용 탐지의 "전체 revoke"는 예외를 던지기 전 수행하는 부수효과다.

### 3.3 엔드포인트 (`AuthController`)

| 메서드/경로 | 입력 | 출력 | 보안 |
|-------------|------|------|------|
| `POST /api/admin/login` | `LoginRequest{username,password}` | `TokenResponse` | permitAll |
| `POST /api/admin/refresh` | `RefreshRequest{refreshToken}` | `TokenResponse` | permitAll |
| `POST /api/admin/logout` | `RefreshRequest{refreshToken}` | 204 No Content | permitAll |

- 세 경로 모두 access 없이 호출되므로 `permitAll`. refresh/logout은 refresh 토큰 자체가 자격 증명이다.
- 시도 제한(`LoginAttemptService`)은 `/login`에만 적용(현행 유지). `/refresh`는 refresh 토큰 보유가 전제라 별도 제한 없음.

### 3.4 DTO

```
TokenResponse(
    String accessToken, Instant accessExpiresAt,
    String refreshToken, Instant refreshExpiresAt)
RefreshRequest(String refreshToken)
```

- 기존 `LoginResponse(token, expiresAt)`는 `TokenResponse`로 대체한다. 프론트 타입도 함께 변경한다.

### 3.5 설정 변경

`application.yml` / `application-local.yml` / `application-test.yml`:
```yaml
jwt:
  expiration-seconds: 900        # 3600 → 900 (access 15분)
refresh:
  expiration-seconds: 604800     # 7일
```

- 신규 엔티티로 스키마가 바뀐다. 운영(MySQL) `ddl-auto: update`, 로컬/테스트(H2)는 create-drop로 자동 반영.

---

## 4. 프론트 설계

### 4.1 쿠키

| 쿠키 | 내용 | 옵션 |
|------|------|------|
| `admin_token` | access JWT | httpOnly, SameSite=Lax, secure(prod), path=/, maxAge=accessExpiresAt 기반 |
| `admin_refresh` | opaque refresh | httpOnly, SameSite=Lax, secure(prod), path=/, maxAge=refreshExpiresAt 기반 |

### 4.2 변경 파일

- `lib/api.ts` — `login` 응답을 `TokenResponse`로, `refresh(refreshToken)`·`logout(refreshToken)` 추가.
- `admin/login/actions.ts` — 로그인 성공 시 쿠키 2개 set.
- `admin/refresh/route.ts`(신규) — GET: `admin_refresh` 쿠키로 `POST /api/admin/refresh` 호출 → 성공 시 새 access/refresh 쿠키 set 후 `next` 파라미터 경로로 리다이렉트, 실패 시 `/admin/logout`으로.
- `admin/logout/route.ts` — `admin_refresh`로 `POST /api/admin/logout`(폐기) 호출 후 쿠키 2개 삭제 + `/admin/login` 리다이렉트.
- `admin/suppliers/page.tsx`·`admin/products/page.tsx` — 401 시 `redirect("/admin/refresh?next=<현재경로>")`.
- `proxy.ts` — 보호 제외 경로(`/admin/login`, `/admin/refresh`, `/admin/logout`)는 그대로 통과(리다이렉트 루프 방지). 그 외 `/admin/*`에서 `admin_token` 없음 + `admin_refresh` 있음 → `/admin/refresh?next=<경로>`로, 둘 다 없음 → `/admin/login`.

### 4.3 갱신 흐름(반응형)

```
서버 컴포넌트 fetch → 401
  → redirect /admin/refresh?next=/admin/suppliers
     → Route Handler: POST /api/admin/refresh (admin_refresh 쿠키)
        → 200: 새 쿠키 2개 set → redirect /admin/suppliers (정상 렌더)
        → 401: redirect /admin/logout (쿠키 삭제 → /admin/login)
```

---

## 5. 에러 처리 및 엣지케이스

- **재사용 탐지**: revoked 토큰 재제출 시 해당 admin 전체 refresh 폐기 → 다음 요청부터 재로그인. 정상 사용자와 공격자를 동시에 차단(설계 의도).
- **refresh 만료(7일)**: rotate가 401 → `/admin/logout` → 재로그인.
- **동시 요청 회전 race**: 브라우저 병렬 요청이 같은 refresh를 동시에 교환하면 한쪽이 재사용으로 오인될 수 있다. 단일 어드민 + 서버 컴포넌트(순차 렌더) 환경이라 위험이 낮다. **알려진 한계로 README에 기록**한다(완전 해소는 refresh 토큰 family/grace window 도입이 필요하며 이번 범위 밖).
- **만료 행 누적**: `purgeExpired`를 login/rotate 시 호출하는 lazy 정리로 처리. 스케줄러는 도입하지 않는다(YAGNI).

---

## 6. 테스트 전략

**단위 — `RefreshTokenServiceTest`** (clock 주입으로 결정적 검증)
- 발급 후 rotate 시 새 토큰 반환·옛 토큰 revoke
- 만료 토큰 rotate → 거부
- revoked 토큰 재제출 → 재사용 탐지(해당 admin 전체 revoke)
- revoke 멱등(미존재/중복 폐기)
- 서로 다른 admin 토큰 독립성

**통합 — `AuthControllerTest`**
- `/login` → `TokenResponse`(access+refresh) 반환
- `/refresh` 정상 → 새 토큰, 옛 refresh 무효
- `/refresh` 만료/무효 → 401
- `/refresh` 재사용 → 401 + 후속 refresh 전부 무효
- `/logout` → refresh 폐기(이후 rotate 401)
- 로그인 → refresh → 새 access로 보호 API(`/api/admin/suppliers`) 접근 성공
- 기존: 만료 access 토큰 → 401(유지)

**프론트**
- `npm run build` / `npm run lint` 통과
- 수동/자동 E2E(가능 시 DevTools MCP): 로그인→15분 경과 모사(만료 토큰 주입)→자동 refresh→재접근, 로그아웃 시 쿠키 2개 삭제 + 백엔드 폐기

---

## 7. 보안 한계(이번 사이클 이후 남는 것)

- **로그인 시도 제한 인메모리**: 다중 인스턴스 배포 시 인스턴스별 카운트(Redis 등 공유 저장소 미도입).
- **refresh 회전 동시성**: 위 5절의 race. family/grace window 미도입.
- README "보안 한계 > 남은 한계"를 단일 출처로 갱신한다.

---

## 8. 구현 산출물 요약

**백엔드 신규:** `RefreshToken`, `RefreshTokenRepository`, `RefreshTokenService`, dto(`TokenResponse`, `RefreshRequest`), `RefreshTokenServiceTest`
**백엔드 수정:** `AuthService`, `AuthController`, `SecurityConfig`, `application*.yml`, `AuthControllerTest`, 기존 `LoginResponse` 제거
**프론트 신규:** `admin/refresh/route.ts`
**프론트 수정:** `lib/api.ts`, `admin/login/actions.ts`, `admin/logout/route.ts`, `admin/suppliers/page.tsx`, `admin/products/page.tsx`, `proxy.ts`
**문서:** README 보안 한계 갱신, ROADMAP 동기화(후보 1 → 진행/완료)
