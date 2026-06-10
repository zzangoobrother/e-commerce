# refresh 재사용 탐지 롤백 결함 수정 — 설계 문서

**날짜:** 2026-06-10
**상태:** 승인됨
**범위:** 롤백 결함 수정만 (지난 사이클의 Minor 항목 — 로그아웃 GET 핸들러 제거, register 레이트리밋, revoke() ownerType 검증 — 은 이번 사이클에서 제외)

---

## 1. 문제

`RefreshTokenService.rotate()`의 **재사용 탐지(theft response)가 프로덕션에서 동작하지 않는다.**

폐기된 refresh 토큰이 재제출되면 `rotate()`는 `repository.revokeAllByOwner(...)`(해당 owner의 살아있는 토큰 전부 폐기)를 실행한 직후 `UnauthorizedException`을 던진다(`RefreshTokenService.java:62-66`). `rotate()`와 호출자(`AuthService.refresh` / `CustomerAuthService.refresh`)가 모두 `@Transactional`(전파 REQUIRED, 동일 물리 트랜잭션)이라, 이 RuntimeException이 트랜잭션을 rollback-only로 만들어 **직전의 일괄 폐기 UPDATE까지 함께 롤백**된다.

결과: 공격자가 탈취한 토큰을 재사용해도 사용자에겐 401이 나가지만, "탈취 정황 시 형제 토큰 전부 무효화"라는 핵심 보안 조치는 DB에 남지 않는다.

### 왜 기존 테스트가 못 잡았나

`RefreshTokenServiceTest`는 `@DataJpaTest`라 각 테스트 메서드가 바깥 테스트 트랜잭션으로 감싸인다. 서비스 호출은 그 트랜잭션에 **참여**만 하므로, `rotate()` 내부에서 예외가 나도 테스트 트랜잭션 자체는 살아있어 폐기 UPDATE가 후속 assert에서 보인다. 프로덕션엔 그 바깥 트랜잭션이 없다 — 테스트 인프라가 트랜잭션 경계를 왜곡해 실제와 다른 결과를 보여준 것이다.

### 범위와 이력

PR #8(토큰 수명 주기)부터의 사전 결함. 고객 인증 사이클(PR #10)의 다형 소유 일반화로 어드민·고객 양 경로에 동일 구조가 복제됐다. 이번 수정으로 양 경로가 동시에 고쳐진다(공유 서비스 한 곳만 수정).

---

## 2. 해결 설계 — REQUIRES_NEW 분리 빈

### 2.1 신규: `TokenTheftResponder` (`com.ecommerce.auth`)

```java
// 탈취 정황 대응 — 폐기를 호출자 트랜잭션과 분리해 즉시 커밋한다.
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

### 2.2 수정: `RefreshTokenService`

- `rotate()`의 재사용 탐지 분기에서 `repository.revokeAllByOwner(...)` 직접 호출을 `theftResponder.revokeAllFor(...)`로 교체.
- 생성자 2개(DI용 `@Autowired` 생성자, 테스트용 패키지 생성자) 모두 `TokenTheftResponder` 파라미터 추가.
- 그 외 로직(발급·회전·만료·revoke) 불변.

### 2.3 데이터 흐름 (재사용 탐지 시)

```
rotate(폐기된 토큰)                       [트랜잭션 T1]
  → theftResponder.revokeAllFor(owner)   ← T1 일시 중단, 새 트랜잭션 T2에서 UPDATE 후 커밋
  → throw UnauthorizedException          ← T1 롤백 (T2 커밋은 이미 확정)
  → 사용자에겐 기존과 동일한 401 + 동일 메시지
```

- **사용자 가시 동작 불변**: 401 상태·메시지 동일. 달라지는 것은 폐기의 persist 여부뿐.
- **락 경합 없음**: T1은 이 시점까지 SELECT(`findByTokenHash`)만 수행해 행 잠금이 없고, 폐기 대상(살아있는 형제 토큰)은 T1이 건드리지 않은 행들이다.
- **실패 모드**: T2 커밋이 DB 오류로 실패하면 예외가 그대로 전파돼 T1도 롤백 — 폐기 실패가 조용히 삼켜지지 않는다.
- **커넥션 점유**: 탐지 순간에만 커넥션 2개 일시 점유 — 재사용 탐지는 예외적 경로라 무시 가능.

---

## 3. 검토한 대안 (기각)

- **B안 — 예외 제거, 결과 반환**: `rotate()`가 던지지 않고 결과 객체를 반환, 401 변환은 트랜잭션 밖에서. 함정: 호출자 `refresh()`도 `@Transactional`(같은 물리 트랜잭션)이라 호출자가 예외를 던지면 똑같이 롤백된다. 예외 변환을 컨트롤러까지 끌어올려야 해 시그니처·호출자 2곳·컨트롤러 2곳·테스트 전반이 바뀌는 큰 수술 → "작은 보안 사이클" 목표와 불일치로 기각.
- **C안 — 트랜잭션 이벤트 리스너(AFTER_ROLLBACK)**: 동작은 가능하나 제어 흐름이 암묵적이고 리스너 실패 추적이 어려움 → 기각.

---

## 4. 테스트 전략 — 결함 재현 후 수정 (TDD)

### 4.1 신규: `RefreshTokenReuseDetectionTest`

- `@SpringBootTest` + `@ActiveProfiles("test")`, **테스트 트랜잭션 없음**(`@Transactional` 미사용) — 각 서비스 호출이 프로덕션과 동일하게 실제 커밋된다.
- 실제 스프링 빈(프록시 적용된 `RefreshTokenService`·`TokenTheftResponder`)을 주입받아 사용.
- 핵심 시나리오:
  1. owner로 토큰 A 발급 → `rotate(A)` 성공(→ B 발급, A 폐기 커밋)
  2. `rotate(A)` 재제출 → `UnauthorizedException`(401 경로) 확인
  3. 별도 트랜잭션 조회로 **B(형제 토큰)가 `revoked=true`로 커밋되어 있는지** 검증
  4. `rotate(B)`도 거부되는지 확인
  5. 다른 owner의 토큰은 영향받지 않는지 확인
- 이 테스트는 **수정 전 main에서 실패**(롤백으로 B가 살아있어 3·4 실패)하고 수정 후 통과한다 — 결함을 재현하는 진짜 회귀 테스트. TDD 순서로 구현보다 먼저 작성한다.
- `@AfterEach`에서 `refreshTokenRepository.deleteAll()` — 커밋된 데이터라 수동 정리 필수.

### 4.2 기존: `RefreshTokenServiceTest` (`@DataJpaTest`)

- 유지하되, 테스트용 생성자에 `new TokenTheftResponder(repository)`를 직접 생성해 전달.
- 프록시가 아니므로 REQUIRES_NEW 없이 같은 트랜잭션으로 동작 — 이 클래스는 **로직 검증 전용**이고, 커밋 경계 검증은 `RefreshTokenReuseDetectionTest`가 담당한다는 역할 분담을 클래스 주석에 명시한다(이번 결함의 교훈을 코드에 남김).

### 4.3 회귀

- 기존 전체 테스트(`./gradlew test`)가 계속 통과해야 한다 — 사용자 가시 동작이 불변이므로 컨트롤러 테스트는 수정 없이 통과 예상.

---

## 5. 문서 동기화

- `README.md`: 보안 절에 재사용 탐지 폐기가 별도 트랜잭션으로 확정 커밋됨을 반영(기존 서술 형식에 맞춰).
- `docs/ROADMAP.md`: 완료 사이클 표에 사이클 8(재사용 탐지 롤백 결함 수정) 행 추가, 사이클 7(고객 인증) 상태를 `main 머지됨 (PR #10)`으로 정정.

---

## 6. 작업 방식

- 브랜치: `feature/refresh-reuse-rollback-fix` (main에서 분기)
- 스펙 → 플랜(`docs/superpowers/plans/`) → Task 단위 실행 → 전체 테스트 → PR(머지 커밋 방식)
