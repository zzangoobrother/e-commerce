# refresh 재사용 탐지 롤백 결함 수정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** refresh 재사용 탐지(theft response)의 일괄 폐기가 같은 트랜잭션의 401 예외로 롤백돼 프로덕션에서 유실되던 잠복 결함을, 폐기를 별도 트랜잭션(REQUIRES_NEW)으로 분리해 수정한다.

**Architecture:** 신규 빈 `TokenTheftResponder`에 `@Transactional(REQUIRES_NEW)` 메서드를 두고, `RefreshTokenService.rotate()`의 재사용 탐지 분기가 이를 호출해 폐기를 **예외 전에 독립 커밋**한다. 검증은 `@DataJpaTest`(테스트 트랜잭션이 결함을 가림)가 아닌 `@SpringBootTest`(트랜잭션 없음, 실제 커밋 경계)로 한다 — 수정 전 실패하는 회귀 테스트를 먼저 작성한다(TDD).

**Tech Stack:** Java 25 · Spring Boot 4.0.6 · JPA/Hibernate · H2(test) · JUnit5/@SpringBootTest

설계 문서: `docs/superpowers/specs/2026-06-10-refresh-reuse-rollback-fix-design.md`

---

## 파일 구조

**신규**
- `backend/src/main/java/com/ecommerce/auth/TokenTheftResponder.java` — 탈취 대응 폐기를 REQUIRES_NEW로 커밋하는 빈
- `backend/src/test/java/com/ecommerce/auth/RefreshTokenReuseDetectionTest.java` — 커밋 경계 회귀 테스트(@SpringBootTest)

**수정**
- `backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java` — 탐지 분기에서 responder 호출, 생성자 2개에 파라미터 추가
- `backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java` — 헬퍼 생성자 갱신 + 역할 분담 주석
- `README.md`, `docs/ROADMAP.md` — 문서 동기화

---

## Task 1: 결함 재현 회귀 테스트 작성 (RED)

새 프로덕션 타입을 참조하지 않으므로 현재 코드에서 **컴파일은 되고 assert에서 실패**해야 한다 — 결함이 실재함을 증명하는 단계다. 이 Task에서는 커밋하지 않는다(실패 테스트 단독 커밋은 CI를 깨뜨림 — Task 2에서 구현과 함께 커밋).

**Files:**
- Create: `backend/src/test/java/com/ecommerce/auth/RefreshTokenReuseDetectionTest.java`

- [ ] **Step 1: RefreshTokenReuseDetectionTest 작성**

`backend/src/test/java/com/ecommerce/auth/RefreshTokenReuseDetectionTest.java`:
```java
package com.ecommerce.auth;

import com.ecommerce.auth.RefreshTokenService.IssuedToken;
import com.ecommerce.auth.RefreshTokenService.RotationResult;
import com.ecommerce.auth.RefreshTokenService.TokenOwner;
import com.ecommerce.common.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 재사용 탐지의 "커밋 경계" 검증 — @DataJpaTest는 각 테스트를 바깥 트랜잭션으로 감싸
// 같은-트랜잭션 롤백 결함을 가리므로(RefreshTokenServiceTest 주석 참조),
// 여기서는 테스트 트랜잭션 없이 실제 스프링 빈(프록시)으로 호출해
// 각 서비스 호출이 프로덕션과 동일하게 커밋되는 환경에서 폐기가 persist되는지 본다.
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenReuseDetectionTest {

    private static final TokenOwner ADMIN_1 = new TokenOwner(OwnerType.ADMIN, 1L);
    private static final TokenOwner CUSTOMER_1 = new TokenOwner(OwnerType.CUSTOMER, 1L);

    @Autowired RefreshTokenService refreshTokenService;
    @Autowired RefreshTokenRepository refreshTokenRepository;

    @AfterEach
    void cleanup() {
        // 테스트 트랜잭션이 없어 자동 롤백이 없다 — 커밋된 행을 직접 정리
        refreshTokenRepository.deleteAll();
    }

    @Test
    void 재사용_탐지의_일괄_폐기는_예외_롤백과_무관하게_커밋된다() {
        IssuedToken first = refreshTokenService.issue(ADMIN_1);
        RotationResult rotated = refreshTokenService.rotate(first.token());
        String sibling = rotated.refresh().token();

        // 폐기된 토큰 재제출 = 탈취 정황 → 401 경로
        assertThatThrownBy(() -> refreshTokenService.rotate(first.token()))
                .isInstanceOf(UnauthorizedException.class);

        // 핵심: 형제 토큰 폐기가 커밋되어 남아야 한다
        // (결함: 같은 트랜잭션이 예외로 롤백되며 폐기 UPDATE가 유실)
        assertThat(refreshTokenRepository.findAll())
                .isNotEmpty()
                .allMatch(RefreshToken::isRevoked);

        // 폐기가 남았으므로 형제 토큰으로도 회전 불가
        assertThatThrownBy(() -> refreshTokenService.rotate(sibling))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void 재사용_탐지_커밋은_다른_owner의_토큰에_영향을_주지_않는다() {
        IssuedToken customerToken = refreshTokenService.issue(CUSTOMER_1);
        IssuedToken adminFirst = refreshTokenService.issue(ADMIN_1);
        refreshTokenService.rotate(adminFirst.token());

        // ADMIN_1 재사용 탐지 발동
        assertThatThrownBy(() -> refreshTokenService.rotate(adminFirst.token()))
                .isInstanceOf(UnauthorizedException.class);

        // 다른 owner(CUSTOMER_1) 토큰은 여전히 정상 회전된다
        RotationResult result = refreshTokenService.rotate(customerToken.token());
        assertThat(result.owner()).isEqualTo(CUSTOMER_1);
    }
}
```

- [ ] **Step 2: 테스트 실행으로 실패(RED) 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.RefreshTokenReuseDetectionTest"`
Expected: FAIL — `재사용_탐지의_일괄_폐기는_예외_롤백과_무관하게_커밋된다`가 `allMatch(isRevoked)` 또는 `rotate(sibling)` 단계에서 실패한다(롤백으로 형제 토큰이 살아있음). 두 번째 테스트는 통과할 수 있다(다른 owner 영향 없음은 현재도 성립). **첫 테스트가 실패하지 않으면 멈추고 원인을 보고할 것** — 결함 재현이 안 되면 수정 근거가 무너진다.

---

## Task 2: TokenTheftResponder 도입 + rotate() 수정 (GREEN)

**Files:**
- Create: `backend/src/main/java/com/ecommerce/auth/TokenTheftResponder.java`
- Modify: `backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java`
- Modify: `backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java`

- [ ] **Step 1: TokenTheftResponder 작성**

`backend/src/main/java/com/ecommerce/auth/TokenTheftResponder.java`:
```java
package com.ecommerce.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 탈취 정황 대응 — 일괄 폐기를 호출자 트랜잭션과 분리해 즉시 커밋한다.
// 별도 빈인 이유: 같은 클래스 내 호출(self-invocation)은 프록시를 우회해 REQUIRES_NEW가 무시된다.
@Component
public class TokenTheftResponder {

    private final RefreshTokenRepository repository;

    public TokenTheftResponder(RefreshTokenRepository repository) {
        this.repository = repository;
    }

    // 해당 owner의 살아있는 토큰 전부 폐기 — 호출자가 이후 예외로 롤백돼도 이 커밋은 남는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllFor(OwnerType type, Long id) {
        repository.revokeAllByOwner(type, id);
    }
}
```

- [ ] **Step 2: RefreshTokenService 수정 — 생성자 2개 + 탐지 분기**

`RefreshTokenService.java`에서 세 군데 수정한다.

(1) 필드 추가 — `private final RefreshTokenRepository repository;` 바로 아래:
```java
    private final TokenTheftResponder theftResponder;
```

(2) 생성자 2개 교체 (기존 30~42행의 생성자 둘을 다음으로):
```java
    // Spring DI용 — 생성자가 2개이므로 @Autowired로 명시
    @Autowired
    public RefreshTokenService(RefreshTokenRepository repository,
                               TokenTheftResponder theftResponder,
                               @Value("${refresh.expiration-seconds:604800}") long refreshSeconds) {
        this(repository, theftResponder, refreshSeconds, Instant::now);
    }

    // 테스트용 — 시각 공급자 주입
    RefreshTokenService(RefreshTokenRepository repository, TokenTheftResponder theftResponder,
                        long refreshSeconds, Supplier<Instant> clock) {
        this.repository = repository;
        this.theftResponder = theftResponder;
        this.refreshSeconds = refreshSeconds;
        this.clock = clock;
    }
```

(3) `rotate()`의 재사용 탐지 분기(기존 62~66행)를 다음으로 교체:
```java
        if (stored.isRevoked()) {
            // 이미 폐기된 토큰 재제출 = 탈취 정황 → 형제 토큰 일괄 폐기.
            // 별도 트랜잭션(REQUIRES_NEW)으로 즉시 커밋 — 아래 예외로 이 트랜잭션이 롤백돼도 폐기는 남는다.
            theftResponder.revokeAllFor(stored.getOwnerType(), stored.getOwnerId());
            throw new UnauthorizedException(INVALID_REFRESH);
        }
```

- [ ] **Step 3: RefreshTokenServiceTest 헬퍼 갱신 + 역할 분담 주석**

`RefreshTokenServiceTest.java`에서 두 군데 수정한다.

(1) 클래스 선언 위 주석 추가 (`@DataJpaTest` 바로 위):
```java
// 회전·만료·폐기 "로직" 검증 전용 — @DataJpaTest는 각 테스트를 바깥 트랜잭션으로 감싸
// 커밋 경계가 프로덕션과 다르다(내부 예외 후에도 변경이 보임).
// 재사용 탐지 폐기가 실제로 "커밋"되는지는 RefreshTokenReuseDetectionTest(@SpringBootTest)가 검증한다.
```

(2) `service(...)` 헬퍼(기존 29~31행)를 다음으로 교체:
```java
    private RefreshTokenService service(Supplier<Instant> clock) {
        // 프록시 없는 직접 생성 — REQUIRES_NEW가 적용되지 않고 테스트 트랜잭션에 참여한다(로직 검증엔 충분)
        return new RefreshTokenService(refreshTokenRepository,
                new TokenTheftResponder(refreshTokenRepository), SEVEN_DAYS, clock);
    }
```

- [ ] **Step 4: 회귀 테스트 통과(GREEN) 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.auth.RefreshTokenReuseDetectionTest"`
Expected: PASS (2개 모두)

- [ ] **Step 5: 전체 백엔드 테스트로 회귀 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — `RefreshTokenServiceTest` 9개 포함 전부 통과(사용자 가시 동작 불변이라 컨트롤러 테스트 수정 없음).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/ecommerce/auth/TokenTheftResponder.java \
        backend/src/main/java/com/ecommerce/auth/RefreshTokenService.java \
        backend/src/test/java/com/ecommerce/auth/RefreshTokenServiceTest.java \
        backend/src/test/java/com/ecommerce/auth/RefreshTokenReuseDetectionTest.java
git commit -m "fix: 재사용 탐지 일괄 폐기가 예외 롤백으로 유실되던 결함 수정(REQUIRES_NEW 분리)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: 문서 동기화

**Files:**
- Modify: `README.md`
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: README 보안 한계 절 갱신**

`README.md`의 "### 해결됨 (2026-06-06 토큰 수명 주기 사이클)" 섹션(90~93행)과 "### 남은 한계 (다음 사이클)" 사이에 새 섹션을 추가한다:
```markdown
### 해결됨 (2026-06-10 재사용 탐지 롤백 결함 수정)

- ~~재사용 탐지의 일괄 폐기가 같은 트랜잭션의 401 예외로 롤백돼 실제로는 저장되지 않았다 (잠복 결함 — @DataJpaTest의 테스트 트랜잭션이 결함을 가림)~~ → 폐기를 별도 트랜잭션(REQUIRES_NEW)으로 분리해 예외 전에 커밋 확정, @SpringBootTest 커밋 경계 회귀 테스트 추가
```

- [ ] **Step 2: ROADMAP 갱신**

`docs/ROADMAP.md`에서:
- 3행 `> 마지막 갱신: 2026-06-07 (고객 인증 사이클 문서 동기화)`을 `> 마지막 갱신: 2026-06-10 (재사용 탐지 롤백 결함 수정 사이클)`으로 교체.
- 15행 사이클 7 행의 상태 `` `feature/customer-auth` 브랜치 (머지 대기) ``를 `main 머지됨 (PR #10)`으로 교체.
- 사이클 7 행 바로 아래에 행 추가:
```markdown
| 8. 재사용 탐지 롤백 결함 수정 | 2026-06-10 | refresh 재사용 탐지의 일괄 폐기가 같은 트랜잭션 예외 롤백으로 유실되던 잠복 결함 수정(TokenTheftResponder, REQUIRES_NEW 분리), @SpringBootTest 커밋 경계 회귀 테스트 | `feature/refresh-reuse-rollback-fix` 브랜치 (머지 대기) |
```

- [ ] **Step 3: 문서 일관성 확인**

Run: `grep -n "재사용\|REQUIRES_NEW\|롤백" README.md docs/ROADMAP.md`
Expected: 결함 해결 서술이 두 문서에서 모순 없이 일치(README는 해결됨 항목, ROADMAP은 사이클 8 행).

- [ ] **Step 4: Commit**

```bash
git add README.md docs/ROADMAP.md
git commit -m "docs: 재사용 탐지 롤백 결함 수정 반영·사이클 7 머지 상태 정정

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 최종 검증 (Definition of Done)

- [ ] `cd backend && ./gradlew test` 전체 통과 (RefreshTokenReuseDetectionTest 2 + RefreshTokenServiceTest 9 + 기존 전부)
- [ ] Task 1에서 회귀 테스트가 수정 전 코드 기준 **실패했음**이 확인됨 (결함 재현 증거)
- [ ] 사용자 가시 동작 불변 — 재사용 제출 시 동일한 401 + 동일 메시지
- [ ] README 해결됨 항목·ROADMAP 사이클 8 행이 모순 없이 동기화됨
