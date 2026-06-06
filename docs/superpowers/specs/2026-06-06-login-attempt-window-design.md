# 보안 보완 3차 — 인메모리 시도 제한 고정 윈도우 개선 설계

**작성일:** 2026-06-06
**사이클:** 보안 보완 3차 (ROADMAP "후보 1"의 축소 버전)
**범위:** `LoginAttemptService`의 시도 제한 카운팅을 고정 윈도우로 개선. **공유 저장소(Redis/MySQL)·새 인프라는 도입하지 않는다.**

---

## 1. 배경 및 목표

현재 `LoginAttemptService`(IP 기준 인메모리 `ConcurrentHashMap`)는 카운트가 `maxAttempts` 미만일 때 **만료 없이 무기한 누적**된다. `lockedUntil`이 `null`(미잠금)이면 `expired=false`라 카운트가 영구히 쌓여, 정상 사용자가 오랜 기간에 걸쳐 5번 틀려도 잠긴다.

**목표:** 카운팅을 **고정 윈도우**로 바꿔 이 약점을 제거한다. "윈도우(15분) 내 maxAttempts(5)회 실패 시 윈도우가 끝날 때까지 차단, 윈도우가 지나면 자동 리셋."

**비목표:** 다중 인스턴스 카운트 공유는 이번 범위가 아니다(단일 인스턴스 운영). 공유 저장소는 ROADMAP에 "다중 배포 시" 조건부 후보로 남긴다.

---

## 2. 설계

### 2.1 상태 모델 변경

```
기존:  Attempt(int count, Instant lockedUntil)
변경:  Attempt(int count, Instant windowExpiry)
```

`windowExpiry`는 현재 윈도우가 끝나는 시각이다. 차단 기간 = 윈도우 잔여 시간(별도 lockout 개념 없음 — 단순 고정 윈도우).

### 2.2 동작

- **`recordFailure(ip)`**: 최초이거나 윈도우가 만료(`now ≥ prev.windowExpiry`)됐으면 새 윈도우를 시작한다(`count=1`, `windowExpiry=now+windowSeconds`). 아니면 같은 윈도우에서 `count++`(windowExpiry 유지).
- **`isBlocked(ip)`**: `attempt != null && now < windowExpiry && count >= maxAttempts`
- **`reset(ip)`**: 해당 IP 키 제거(로그인 성공 시). 변경 없음.
- **`clearAll()`**: 테스트 격리용 전체 초기화. 변경 없음.

### 2.3 설정

- `login.lockout-seconds`(기본 900) → **`login.window-seconds`**(기본 900)로 이름 변경(의미를 "윈도우=차단 기간"으로 명확화).
- `login.max-attempts`(기본 5) 유지.
- `application*.yml`에 해당 키가 명시돼 있지 않으면(현재 코드 `@Value` 기본값만 사용) 키 이름 변경만으로 충분하다. 명시돼 있으면 함께 변경한다.

### 2.4 동작 변화 요약

| | 기존 | 변경 |
|---|------|------|
| 카운트 누적 | 무기한(시간 무관) | 윈도우(15분) 내에서만 |
| 차단 트리거 | 누적 5회 | 윈도우 내 5회 |
| 차단 해제 | 잠금 15분 경과 후 다음 시도에서 리셋 | 윈도우 만료 시 자동 리셋 |

---

## 3. 영향 범위

- **수정**: `backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java`(로직), `backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java`(고정 윈도우 검증)
- **무영향(확인만)**: `AuthController`(공개 API `isBlocked`/`recordFailure`/`reset`/`clearAll` 시그니처 불변), `AuthControllerTest`의 429 테스트(즉시 5회 실패 시나리오라 동작 동일).
- **문서**: `docs/ROADMAP.md`(후보 1을 "인메모리 고정 윈도우 개선"으로 갱신 + 공유 저장소는 다중 배포 조건부로 남김). `README.md` 보안 한계의 "시도 제한 인메모리(다중 인스턴스)"는 유지(여전히 남는 한계).

---

## 4. 테스트 전략

`LoginAttemptServiceTest`(clock 주입으로 결정적 검증):
- 윈도우 내 maxAttempts 미만이면 차단되지 않는다
- 윈도우 내 maxAttempts 도달 시 차단된다
- 윈도우가 만료되면 자동으로 다시 허용된다(잠금 해제용 별도 시도 불필요)
- 윈도우 내 실패가 누적되다 윈도우 경계를 넘으면 카운트가 리셋된다(무기한 누적 제거 검증)
- 성공 reset 후 카운트가 사라진다
- 서로 다른 IP는 독립적으로 카운트된다

기존 `AuthControllerTest`의 "동일 IP 5회 실패 → 6회째 429"는 그대로 통과해야 한다(회귀 없음).

---

## 5. 남는 한계 (변동 없음)

- 시도 제한이 인메모리라 **다중 인스턴스 배포 시 인스턴스별로 카운트**된다. 공유 저장소(Redis/MySQL)는 다중 배포가 실제 필요해질 때 도입한다. README "보안 한계 > 남은 한계"를 단일 출처로 유지한다.
