# 인증 하드닝 3건 설계 (사이클 13)

> 마지막 갱신: 2026-06-23

## 배경

이전 사이클들에서 인계된 Minor 보안 항목 3건을 한 사이클로 묶어 처리한다. 모두 기존 구조를 따르는 작은 하드닝으로, 새 인프라·새 엔티티·새 서비스를 도입하지 않는다.

| # | 항목 | 영역 |
|---|------|------|
| ① | 로그아웃 GET 측면효과 제거 | 프론트 |
| ② | register 레이트리밋 | 백엔드 |
| ③ | revoke() ownerType 가드 | 백엔드 |

## 전역 제약

- **언어:** 코드 주석·커밋 메시지·문서는 한국어, 식별자는 영어(프로젝트 규약).
- **무변경:** 새 인프라(Redis 등) 금지 — `LoginAttemptService` 인메모리 카운터 재사용. `OwnerType` enum·`SecurityConfig`·`GlobalExceptionHandler`의 기존 429 매핑은 그대로 활용한다.
- **멱등성 우선:** 로그아웃은 어떤 경우에도 측면효과 없이 204를 반환해야 한다(미존재·타입 불일치 모두 no-op).
- **Next.js 16:** `cookies()`는 async(await). Route Handler의 `NextResponse.redirect`는 예외를 던지지 않으므로 Server Action `redirect()` 제약과 무관.

---

## ① 로그아웃 GET 측면효과 제거 (프론트)

### 문제
`logout/route.ts`·`admin/logout/route.ts`에 `GET`·`POST` 핸들러가 둘 다 있고, 둘 다 **백엔드 refresh 토큰 폐기 + 쿠키 삭제**라는 측면효과를 수행한다. 측면효과를 가진 GET 엔드포인트는 프리페치·크롤러·`<img src>` 형태 CSRF로 의도치 않게 트리거될 수 있다.

실제 로그아웃 버튼(`page.tsx`, `admin/LogoutButton.tsx`)은 **POST 폼만** 사용한다. 그러나 GET 핸들러는 죽은 코드가 아니다 — `refresh/route.ts`·`admin/refresh/route.ts`가 갱신 실패 시 `/logout`(`/admin/logout`)으로 **303 리다이렉트(→GET 강제)** 하여 정리 경로로 쓰고 있다. 따라서 단순 삭제는 불가하다.

### 설계
정리(쿠키 삭제) 책임을 refresh 라우트로 옮긴다.

- `refresh/route.ts`의 실패 분기: `/logout`으로 보내는 대신 **그 자리에서 두 고객 쿠키를 삭제하고 `/login`으로 303**.
- `admin/refresh/route.ts`의 실패 분기: 동일하게 **두 어드민 쿠키 삭제 후 `/admin/login`으로 303**.
- 그 후 `logout/route.ts`·`admin/logout/route.ts`에서 **`GET` export 제거**, `POST`만 유지.

### 데이터 흐름 (변경 후)
- refresh 성공 → 새 access/refresh 쿠키 set → `next` 경로로 303.
- refresh 실패 → (refresh 라우트가 직접) 쿠키 삭제 → 로그인 경로로 303.
- 로그아웃 버튼 → POST `/logout` → 백엔드 폐기 + 쿠키 삭제 → 로그인 경로로 303.

더 이상 GET 요청으로 토큰 폐기·쿠키 삭제가 트리거되지 않는다.

---

## ② register 레이트리밋 (백엔드)

### 문제
`/api/store/auth/register`에는 시도 제한이 없다(현재 `/login`만 `LoginAttemptService`로 IP당 보호). 대량 계정 생성(스팸)과 BCrypt 연산 자원 고갈에 노출된다.

### 설계
기존 `LoginAttemptService`를 `"register:" + ip` 버킷으로 재사용한다. 새 설정 키·새 클래스 없이 기존 `max-attempts`·`window-seconds` 설정을 그대로 적용한다.

**계수 의미는 로그인과 다르다.** 로그인은 "실패만 세고 성공 시 리셋"이지만, 가입 스팸의 위협은 *성공적인 대량 계정 생성*이므로 **성공·실패 구분 없이 모든 시도를 계수**한다(리셋 없음). 윈도우당 IP별 가입 횟수 자체를 상한한다.

`CustomerAuthController.register` 가드(로그인과 동일 패턴, 단 성공 시 `reset` 미호출):
1. `key = "register:" + ClientIp.from(http)`.
2. `loginAttemptService.isBlocked(key)`이면 `TooManyAttemptsException`(가입 전용 메시지) → 429.
3. 통과면 `loginAttemptService.recordFailure(key)`로 1 계수 후 `customerAuthService.register(request)` 진행.

> 메서드명 `recordFailure`는 "한 번의 시도 계수"라는 의미로 재사용한다(이름은 로그인 맥락에서 유래했으나 동작은 카운터 증가). 별도 메서드 추가는 YAGNI로 하지 않는다.

### 에러 처리
`TooManyAttemptsException`은 기존 `GlobalExceptionHandler`가 이미 429로 매핑한다 — 새 핸들러 불필요. 메시지는 로그인용과 구분되는 가입 전용 문구.

---

## ③ revoke() ownerType 가드 (백엔드)

### 문제
`RefreshTokenService.revoke(String presentedToken)`는 토큰 해시로 조회해 **ownerType 검사 없이 무조건 폐기**한다. refresh(rotate) 경로는 `AuthService.refresh`/`CustomerAuthService.refresh`에서 `result.owner().type()`을 검사해 교차 사용을 401로 막는 양방향 가드가 있으나, 로그아웃 경로만 이 가드가 없다. 어드민 refresh 토큰을 고객 로그아웃에 제출하면 폐기된다(토큰 소지가 전제라 치명적 악용은 아니나 일관성 결함).

### 설계
`revoke`에 기대 ownerType를 받아 refresh 가드를 대칭으로 미러링한다.

- **시그니처:** `void revoke(String presentedToken, OwnerType expected)`.
- **동작:** 해시로 조회된 토큰의 `getOwnerType() == expected`일 때만 `RefreshToken::revoke`. 불일치 또는 미존재면 **no-op**.
- **호출부:**
  - `AuthService.logout` → `refreshTokenService.revoke(token, OwnerType.ADMIN)`.
  - `CustomerAuthService.logout` → `refreshTokenService.revoke(token, OwnerType.CUSTOMER)`.

### 에러 처리
타입 불일치는 예외 없이 no-op → 로그아웃은 항상 204를 반환한다(멱등성·관대함 유지, 토큰 존재 여부 비노출). refresh의 401과 의도적으로 다르다.

---

## 테스트 전략

### ② register 레이트리밋 (`CustomerAuthControllerTest`)
- 동일 IP로 `max-attempts` 초과 가입 시도 → 429 검증.
- **성공 가입도 계수됨**을 고정: 성공적으로 N회 가입한 뒤 추가 시도가 429가 되는지 확인(리셋 없음).
- ⚠️ **테스트 격리:** `LoginAttemptService`는 `@Component` 인메모리 싱글톤이라 테스트 간 카운터가 누수된다. 기존 로그인 제한 테스트의 격리 방식(테스트별 고유 IP 또는 reset)을 플랜 작성 시 먼저 확인해 동일하게 적용한다.

### ③ revoke ownerType 가드 (`CustomerAuthControllerTest` / 교차가드 테스트)
- 고객 로그아웃에 **어드민 refresh 토큰** 제출 → 204지만 어드민 토큰은 **여전히 유효(미폐기)** 검증.
- 어드민 로그아웃에 고객 토큰 제출 → 대칭으로 204·미폐기.
- 정상 케이스(고객 토큰 로그아웃 → 폐기, 어드민 토큰 로그아웃 → 폐기) 회귀 유지.

### ① 로그아웃 GET 제거 (프론트)
- 프론트 자동 테스트 부재 → `npm run build && npm run lint` 통과로 타입·라우트 무결성 확인.
- `logout/route.ts`·`admin/logout/route.ts`에 `GET` export가 없어졌는지 grep으로 고정.
- refresh 실패 → 쿠키 삭제 → 로그인 흐름이 코드상 성립함을 리뷰로 확인.

---

## Definition of Done

- [ ] `cd backend && ./gradlew test` 전체 통과 (register 429 + 성공 계수 + revoke 교차가드 신규 테스트 포함)
- [ ] `cd frontend && npm run build && npm run lint` 통과
- [ ] register: 동일 IP 윈도우당 `max-attempts` 초과 시 429, 성공 가입도 계수됨 (테스트로 고정)
- [ ] revoke: 고객·어드민 로그아웃이 자기 타입 토큰만 폐기, 타입 불일치는 204 no-op (테스트로 고정)
- [ ] logout/admin logout route에 GET 핸들러 없음, refresh 실패 정리가 refresh 라우트로 이동 (grep·빌드로 고정)
- [ ] 새 인프라·새 엔티티·새 서비스 없음, `OwnerType`·`SecurityConfig`·`GlobalExceptionHandler` 기존 매핑 재사용
- [ ] 로그아웃은 모든 경우 204 (멱등성 유지)
