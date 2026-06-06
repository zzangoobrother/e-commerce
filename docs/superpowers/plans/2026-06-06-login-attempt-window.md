# 인메모리 시도 제한 고정 윈도우 개선 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `LoginAttemptService`의 시도 제한 카운팅을 고정 윈도우로 바꿔 "무기한 누적" 약점을 제거한다(윈도우 내 5회 실패 시 윈도우 끝까지 차단, 윈도우 만료 시 자동 리셋).

**Architecture:** 인메모리 `ConcurrentHashMap` 구조는 유지하고, 상태 모델을 `Attempt(count, lockedUntil)`에서 `Attempt(count, windowExpiry)`로 바꾼다. 공유 저장소(Redis/MySQL)·새 인프라는 도입하지 않는다(단일 인스턴스, YAGNI).

**Tech Stack:** Java 25 · Spring Boot 4.0.6 · JUnit5/AssertJ

설계 문서: `docs/superpowers/specs/2026-06-06-login-attempt-window-design.md`

---

## 파일 구조

- 수정: `backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java` — 고정 윈도우 로직, 설정 키 `login.lockout-seconds`→`login.window-seconds`
- 수정: `backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java` — 고정 윈도우 검증(무기한 누적 제거 테스트 추가)
- 수정: `docs/ROADMAP.md`, `README.md` — 문서 동기화

`AuthController`는 공개 API(`isBlocked`/`recordFailure`/`reset`/`clearAll`) 시그니처가 불변이라 수정하지 않는다. `application*.yml`에는 `login.*` 키가 명시돼 있지 않아(코드 `@Value` 기본값만 사용) 수정 불필요.

---

## Task 1: 고정 윈도우 로직 (TDD)

**Files:**
- Modify: `backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java`

- [ ] **Step 1: 테스트를 고정 윈도우 기준으로 교체(실패하는 테스트 포함)**

`LoginAttemptServiceTest.java`를 다음으로 교체한다. 핵심은 `실패가_윈도우_경계를_넘으면_카운트가_리셋된다` — 기존 무기한 누적 코드에서는 실패한다.

```java
package com.ecommerce.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    void 윈도우_내_최대_시도_미만이면_차단되지_않는다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 윈도우_내_최대_시도에_도달하면_차단된다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
    }

    @Test
    void 윈도우가_만료되면_자동으로_다시_허용된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, 900, now::get);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isTrue();
        now.set(now.get().plusSeconds(901));
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 실패가_윈도우_경계를_넘으면_카운트가_리셋된다() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-06T00:00:00Z"));
        LoginAttemptService service = new LoginAttemptService(5, 900, now::get);
        // 윈도우 내 4회 실패(미차단)
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        // 윈도우가 지난 뒤 1회 더 실패 → 새 윈도우 count=1이라 차단되지 않아야 한다
        now.set(now.get().plusSeconds(901));
        service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 성공_리셋_후에는_차단_카운트가_사라진다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        service.reset("1.1.1.1");
        for (int i = 0; i < 4; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void 서로_다른_IP는_독립적으로_카운트된다() {
        LoginAttemptService service = new LoginAttemptService(5, 900, Instant::now);
        for (int i = 0; i < 5; i++) service.recordFailure("1.1.1.1");
        assertThat(service.isBlocked("2.2.2.2")).isFalse();
    }
}
```

- [ ] **Step 2: 테스트 실행으로 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.LoginAttemptServiceTest"`
Expected: FAIL — `실패가_윈도우_경계를_넘으면_카운트가_리셋된다`가 실패한다(기존 무기한 누적 로직은 4회+경계후 1회를 5회로 누적해 차단). 나머지는 통과.

- [ ] **Step 3: LoginAttemptService를 고정 윈도우로 구현**

`LoginAttemptService.java`를 다음으로 교체한다:

```java
package com.ecommerce.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// 로그인 시도 제한 — IP 기준 인메모리 고정 윈도우 카운터.
// 윈도우(기본 15분) 내 maxAttempts회 실패 시 윈도우가 끝날 때까지 차단하고, 윈도우 만료 시 자동 리셋한다.
// 단일 인스턴스 한정(다중 인스턴스 배포 시 인스턴스별로 카운트됨 — README 보안 한계 참고).
@Service
public class LoginAttemptService {

    private final int maxAttempts;
    private final long windowSeconds;
    private final Supplier<Instant> clock;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    // 현재 윈도우의 실패 누적 횟수와 윈도우 만료 시각
    private record Attempt(int count, Instant windowExpiry) {}

    @Autowired
    public LoginAttemptService(
            @Value("${login.max-attempts:5}") int maxAttempts,
            @Value("${login.window-seconds:900}") long windowSeconds) {
        this(maxAttempts, windowSeconds, Instant::now);
    }

    // 테스트용 — 시각 공급자를 주입해 윈도우 만료를 결정적으로 검증
    LoginAttemptService(int maxAttempts, long windowSeconds, Supplier<Instant> clock) {
        this.maxAttempts = maxAttempts;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    // 현재 해당 IP가 차단 상태인지 — 윈도우가 살아있고 실패 누적이 임계값 이상
    public boolean isBlocked(String ip) {
        Attempt attempt = attempts.get(ip);
        return attempt != null
                && clock.get().isBefore(attempt.windowExpiry())
                && attempt.count() >= maxAttempts;
    }

    // 실패 1회 기록. 윈도우가 없거나 만료됐으면 새 윈도우를 시작하고, 아니면 같은 윈도우에서 누적한다.
    public void recordFailure(String ip) {
        attempts.compute(ip, (key, prev) -> {
            Instant now = clock.get();
            boolean newWindow = prev == null || !now.isBefore(prev.windowExpiry());
            if (newWindow) {
                return new Attempt(1, now.plusSeconds(windowSeconds));
            }
            return new Attempt(prev.count() + 1, prev.windowExpiry());
        });
    }

    // 로그인 성공 시 해당 IP 카운터 제거
    public void reset(String ip) {
        attempts.remove(ip);
    }

    // 테스트 격리용 — 전체 카운터 초기화(싱글톤 빈이라 테스트 간 상태가 누적되는 것을 방지)
    public void clearAll() {
        attempts.clear();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.LoginAttemptServiceTest"`
Expected: PASS (6개 모두)

- [ ] **Step 5: 전체 백엔드 테스트로 회귀 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — 특히 `AuthControllerTest`의 "동일 IP 5회 실패 → 6회째 429"가 그대로 통과(즉시 5회 실패 시나리오라 동작 동일).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/LoginAttemptService.java \
        backend/src/test/java/com/ecommerce/auth/LoginAttemptServiceTest.java
git commit -m "feat: 로그인 시도 제한 고정 윈도우 전환(무기한 누적 약점 제거)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 문서 동기화

**Files:**
- Modify: `docs/ROADMAP.md`
- Modify: `README.md`

- [ ] **Step 1: ROADMAP 갱신**

`docs/ROADMAP.md`에서:
- "완료된 사이클" 표에 행 추가: `6. 보안 보완 3차 | 2026-06-06 | 로그인 시도 제한 고정 윈도우 전환(무기한 누적 약점 제거) | feature/login-attempt-window 브랜치 (머지 대기)` — 표의 실제 컬럼 구조에 맞춰 기재한다. 머지 상태는 `git log --oneline origin/main..feature/login-attempt-window`로 확인 후 사실대로("머지 대기").
- "후보 1: 보안 보완 3차 — 시도 제한 공유 저장소" 섹션을 갱신한다: 이번 사이클로 **인메모리 로직 개선은 완료**됐고, **공유 저장소(Redis/MySQL)를 통한 다중 인스턴스 카운트 공유는 "다중 배포가 실제 필요해질 때" 조건부 후보로** 남긴다는 취지로 문구를 고친다. (현재 단일 인스턴스 운영이라 보류했음을 명시.)

- [ ] **Step 2: README 확인 및 갱신**

`README.md`의 "보안 한계 > 남은 한계" 섹션을 확인한다. "로그인 시도 제한이 인메모리라 다중 인스턴스 배포 시 인스턴스별로 카운트된다" 항목은 **그대로 유지**한다(여전히 남는 한계). 만약 README에 "무기한 누적" 같은 약점 표현이 있으면 해소됨으로 정리하되, 없으면 변경하지 않는다(추측으로 항목을 만들지 말 것 — 실제 문구를 Read로 확인 후 판단).

- [ ] **Step 3: 문서 일관성 확인**

Run: `grep -n "시도 제한\|윈도우\|인메모리\|다중 인스턴스" README.md docs/ROADMAP.md`
Expected: "인메모리 고정 윈도우 개선 완료"와 "공유 저장소(다중 인스턴스)는 조건부 보류"가 두 문서에서 모순 없이 일치.

- [ ] **Step 4: Commit**

```bash
git add docs/ROADMAP.md README.md
git commit -m "docs: 시도 제한 고정 윈도우 개선 반영·공유 저장소 조건부 보류 명시

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 최종 검증 (Definition of Done)

- [ ] `cd backend && ./gradlew test` 전체 통과(LoginAttemptServiceTest 6개 + AuthControllerTest 429 회귀 없음)
- [ ] 무기한 누적 약점 제거: 윈도우 경계를 넘는 실패는 카운트가 리셋됨(테스트로 고정)
- [ ] 설정 키가 `login.window-seconds`로 변경됨(코드 기본값 900)
- [ ] ROADMAP/README에서 "인메모리 개선 완료 + 공유 저장소 조건부 보류"가 일치
