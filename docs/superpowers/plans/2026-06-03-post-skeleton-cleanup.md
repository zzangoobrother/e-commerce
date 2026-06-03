# 골격 후속 정리(Post-Skeleton Cleanup) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 골격 사이클 최종 코드 리뷰에서 식별된 Minor 이슈 4건(메타데이터, create() 이중 세팅, DB 설정 하드코딩, 시드 멱등성)을 해소한다.

**Architecture:** 기존 3계층 구조(Next.js → Spring Boot → MySQL)는 변경하지 않는다. 백엔드는 도메인 생성자 오버로드와 DB 유니크 제약으로 견고함을 높이고, 설정은 환경변수 플레이스홀더로 외부화한다. 프론트는 메타데이터만 정리한다.

**Tech Stack:** 기존과 동일 — Java 25, Spring Boot 4.x, Gradle(Kotlin DSL), H2(테스트), Next.js 16(App Router, TypeScript).

**참고 스펙:** `docs/superpowers/specs/2026-06-03-post-skeleton-cleanup-design.md`

**작업 브랜치:** `refactor/post-skeleton-cleanup` (이미 생성되어 체크아웃됨)

---

## ⚠️ 실행자 필독: 이 프로젝트의 import 경로

이 프로젝트는 Spring Boot 4.x로, 테스트 관련 패키지 경로가 일반적으로 알려진 것과 다르다.
**아래 경로를 그대로 사용할 것** (기존 테스트 파일들이 이미 이 경로를 사용 중):

| 클래스 | 이 프로젝트의 import |
|--------|---------------------|
| `ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `@AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |
| `@DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |

---

## 파일 구조 (변경 대상)

```
e-commerce/
├── README.md                                              # Task 5: DB 재생성 안내
├── backend/src/
│   ├── main/java/com/ecommerce/
│   │   ├── common/DataSeeder.java                         # Task 2: 멱등성 체크 변경
│   │   ├── supplier/
│   │   │   ├── Supplier.java                              # Task 1: 생성자 / Task 2: name 유니크
│   │   │   ├── SupplierRepository.java                    # Task 2: existsByName 추가
│   │   │   └── SupplierService.java                       # Task 1: create() 정리
│   │   └── product/
│   │       ├── Product.java                               # Task 1: 생성자 오버로드
│   │       └── ProductService.java                        # Task 1: create() 정리
│   ├── main/resources/application.yml                     # Task 3: 환경변수 외부화
│   └── test/java/com/ecommerce/
│       ├── supplier/SupplierControllerTest.java           # Task 1: 동작 고정 테스트
│       ├── supplier/SupplierRepositoryTest.java           # Task 2: 유니크 제약 테스트
│       └── product/ProductApiTest.java                    # Task 1: 동작 고정 테스트
└── frontend/src/app/layout.tsx                            # Task 4: 메타데이터
```

---

## Task 1: create() 이중 세팅 정리 (동작 고정 → 리팩토링)

현재 `SupplierService.create()`/`ProductService.create()`는 생성자가 status를 기본값으로
하드코딩한 직후 `update()`로 요청 status를 덮어쓴다(이중 세팅). status를 받는 생성자를
추가해 한 번에 올바른 상태로 생성한다. **동작은 변하지 않으므로**, 먼저 동작을 고정하는
테스트를 추가한 뒤(현재 코드에서도 통과해야 함) 리팩토링한다.

**Files:**
- Modify: `backend/src/test/java/com/ecommerce/supplier/SupplierControllerTest.java`
- Modify: `backend/src/test/java/com/ecommerce/product/ProductApiTest.java`
- Modify: `backend/src/main/java/com/ecommerce/supplier/Supplier.java`
- Modify: `backend/src/main/java/com/ecommerce/supplier/SupplierService.java`
- Modify: `backend/src/main/java/com/ecommerce/product/Product.java`
- Modify: `backend/src/main/java/com/ecommerce/product/ProductService.java`

- [ ] **Step 1: SupplierControllerTest에 동작 고정 테스트 + 데이터 정리 추가**

기존 테스트가 `$[0].name`으로 목록 순서를 검증하므로, 테스트를 추가하면 실행 순서에 따라
기존 테스트가 깨질 수 있다. ProductApiTest와 동일한 `@AfterEach` 정리 패턴을 함께 추가한다.

Replace `backend/src/test/java/com/ecommerce/supplier/SupplierControllerTest.java` (전체 교체):

```java
package com.ecommerce.supplier;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SupplierRepository supplierRepository;

    @AfterEach
    void cleanup() {
        // 각 테스트 후 데이터 정리 — 다른 테스트와의 격리
        supplierRepository.deleteAll();
    }

    @Test
    void 공급사를_생성하고_목록에서_조회한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "신선식품", "contactEmail", "fresh@example.com",
                       "status", "ACTIVE"));

        mockMvc.perform(post("/api/admin/suppliers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("신선식품"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/admin/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("신선식품"));
    }

    @Test
    void 비활성_상태로_공급사를_생성하면_요청한_상태가_반영된다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "휴면공급사", "contactEmail", "rest@example.com",
                       "status", "INACTIVE"));

        mockMvc.perform(post("/api/admin/suppliers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }
}
```

**주의:** 기존 테스트(`공급사를_생성하고_목록에서_조회한다`)의 본문은 그대로 유지한다.
변경되는 것은 import(`AfterEach`), 필드(`supplierRepository`), `cleanup()` 메서드,
신규 테스트 메서드 추가뿐이다.

- [ ] **Step 2: ProductApiTest에 동작 고정 테스트 추가**

`backend/src/test/java/com/ecommerce/product/ProductApiTest.java`의 기존
`스토어에서_판매중_상품_목록을_조회한다` 테스트 메서드 아래에 다음 테스트를 추가한다
(이 클래스에는 이미 `@AfterEach` 정리가 있으므로 그대로 둔다):

```java
    @Test
    void 숨김_상태로_상품을_생성하면_요청한_상태가_반영된다() throws Exception {
        Supplier supplier = supplierRepository.save(
                new Supplier("공급사C", "c@example.com"));

        String body = objectMapper.writeValueAsString(Map.of(
                "supplierId", supplier.getId(),
                "name", "비공개 상품",
                "description", "아직 공개 전",
                "price", 1000,
                "stockQuantity", 5,
                "status", "HIDDEN"));

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HIDDEN"));
    }
```

- [ ] **Step 3: 동작 고정 테스트가 현재 코드에서 통과하는지 확인**

리팩토링 전에 통과해야 한다(현재 이중 세팅 코드도 기능상으로는 올바르므로).

Run: `cd backend && ./gradlew test --tests "com.ecommerce.supplier.SupplierControllerTest" --tests "com.ecommerce.product.ProductApiTest"`
Expected: PASS (전체). 실패하면 테스트 코드 자체의 문제이므로 리팩토링 전에 수정한다.

- [ ] **Step 4: Supplier 생성자 오버로드 추가 + SupplierService.create() 정리**

Modify `backend/src/main/java/com/ecommerce/supplier/Supplier.java` — 기존 생성자(30~34행)를
다음으로 교체:

```java
    public Supplier(String name, String contactEmail) {
        this(name, contactEmail, SupplierStatus.ACTIVE);
    }

    // 상태를 명시해 생성 (어드민 생성 요청 등)
    public Supplier(String name, String contactEmail, SupplierStatus status) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.status = status;
    }
```

Modify `backend/src/main/java/com/ecommerce/supplier/SupplierService.java` — `create()` 메서드를
다음으로 교체 (이중 세팅 제거):

```java
    @Transactional
    public Supplier create(SupplierRequest request) {
        Supplier supplier = new Supplier(
                request.name(), request.contactEmail(), request.status());
        return supplierRepository.save(supplier);
    }
```

- [ ] **Step 5: Product 생성자 오버로드 추가 + ProductService.create() 정리**

Modify `backend/src/main/java/com/ecommerce/product/Product.java` — 기존 생성자(44~52행)를
다음으로 교체:

```java
    public Product(Supplier supplier, String name, String description,
                   BigDecimal price, int stockQuantity) {
        this(supplier, name, description, price, stockQuantity, ProductStatus.ON_SALE);
    }

    // 상태를 명시해 생성 (어드민 생성 요청 등)
    public Product(Supplier supplier, String name, String description,
                   BigDecimal price, int stockQuantity, ProductStatus status) {
        this.supplier = supplier;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.status = status;
    }
```

Modify `backend/src/main/java/com/ecommerce/product/ProductService.java` — `create()` 메서드를
다음으로 교체 (이중 세팅 제거):

```java
    @Transactional
    public Product create(ProductRequest request) {
        Supplier supplier = loadSupplier(request.supplierId());
        Product product = new Product(supplier, request.name(), request.description(),
                request.price(), request.stockQuantity(), request.status());
        return productRepository.save(product);
    }
```

- [ ] **Step 6: 전체 테스트 통과 확인 (리팩토링 후 동작 불변)**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, 전체 테스트 11개(기존 9개 + 이번에 추가한 2개) 통과.

- [ ] **Step 7: 커밋**

```bash
cd .. && git add backend && git commit -m "refactor: create() 이중 세팅 제거(상태 포함 생성자 오버로드)"
```

---

## Task 2: Supplier.name 유니크 제약 + 시드 멱등성 보강 (TDD)

공급사명 중복을 DB 레벨에서 차단하고, DataSeeder의 멱등성 체크를 `count()` 기반에서
이름 존재 여부 기반으로 바꾼다. 새 동작(제약 위반)이 생기므로 TDD로 진행한다.

**Files:**
- Test: `backend/src/test/java/com/ecommerce/supplier/SupplierRepositoryTest.java`
- Modify: `backend/src/main/java/com/ecommerce/supplier/Supplier.java`
- Modify: `backend/src/main/java/com/ecommerce/supplier/SupplierRepository.java`
- Modify: `backend/src/main/java/com/ecommerce/common/DataSeeder.java`

- [ ] **Step 1: 실패하는 유니크 제약 테스트 작성**

Replace `backend/src/test/java/com/ecommerce/supplier/SupplierRepositoryTest.java` (전체 교체):

```java
package com.ecommerce.supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class SupplierRepositoryTest {

    @Autowired
    SupplierRepository supplierRepository;

    @Test
    void 공급사를_저장하고_조회한다() {
        Supplier saved = supplierRepository.save(
                new Supplier("바삭공급사", "snack@example.com"));

        Supplier found = supplierRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("바삭공급사");
        assertThat(found.getContactEmail()).isEqualTo("snack@example.com");
        assertThat(found.getStatus()).isEqualTo(SupplierStatus.ACTIVE);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void 동일한_이름의_공급사는_저장할_수_없다() {
        supplierRepository.saveAndFlush(
                new Supplier("중복공급사", "a@example.com"));

        assertThatThrownBy(() -> supplierRepository.saveAndFlush(
                new Supplier("중복공급사", "b@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 이름으로_공급사_존재_여부를_확인한다() {
        supplierRepository.save(new Supplier("존재공급사", "exist@example.com"));

        assertThat(supplierRepository.existsByName("존재공급사")).isTrue();
        assertThat(supplierRepository.existsByName("없는공급사")).isFalse();
    }
}
```

**주의:** 기존 테스트(`공급사를_저장하고_조회한다`)는 그대로 유지. `saveAndFlush`를 쓰는 이유는
JPA가 INSERT를 지연시키므로 flush 시점에 제약 위반이 발생하기 때문이다.

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.supplier.SupplierRepositoryTest"`
Expected: FAIL —
- `동일한_이름의_공급사는_저장할_수_없다`: 유니크 제약이 없어 예외 미발생으로 실패
- `이름으로_공급사_존재_여부를_확인한다`: `existsByName` 메서드 미존재로 컴파일 실패

(컴파일 에러가 먼저 나면 그것이 곧 실패 확인이다.)

- [ ] **Step 3: Supplier.name에 유니크 제약 추가**

Modify `backend/src/main/java/com/ecommerce/supplier/Supplier.java` — `name` 필드 선언(14~15행)을
다음으로 교체:

```java
    // 공급사명 — 중복 불가 (시드 멱등성의 DB 레벨 방어선)
    @Column(nullable = false, unique = true)
    private String name;
```

- [ ] **Step 4: SupplierRepository에 existsByName 추가**

Replace `backend/src/main/java/com/ecommerce/supplier/SupplierRepository.java` (전체 교체):

```java
package com.ecommerce.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    boolean existsByName(String name);
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.supplier.SupplierRepositoryTest"`
Expected: PASS (3개 테스트 모두).

- [ ] **Step 6: DataSeeder 멱등성 체크 변경**

Modify `backend/src/main/java/com/ecommerce/common/DataSeeder.java` — 클래스 본문 중
시드 로직 부분을 다음으로 교체 (import·필드·생성자는 그대로 유지):

```java
    // 시드의 기준이 되는 첫 번째 공급사명 (존재하면 이미 시드된 것으로 간주)
    private static final String FIRST_SUPPLIER_NAME = "신선식품 주식회사";

    // 공급사·상품 시드를 한 트랜잭션으로 묶어 원자적으로 커밋/롤백한다
    // (이름 존재 여부로 멱등성 판단 — 동시 기동 race는 name 유니크 제약이 최종 방어)
    @Override
    @Transactional
    public void run(String... args) {
        if (supplierRepository.existsByName(FIRST_SUPPLIER_NAME)) {
            return; // 이미 시드됨
        }

        Supplier fresh = supplierRepository.save(
                new Supplier(FIRST_SUPPLIER_NAME, "fresh@example.com"));
        Supplier snack = supplierRepository.save(
                new Supplier("바삭과자 주식회사", "snack@example.com"));

        productRepository.save(new Product(fresh, "유기농 사과 1kg",
                "당도 높은 유기농 사과", new BigDecimal("8900"), 50));
        productRepository.save(new Product(fresh, "제철 딸기 500g",
                "신선한 제철 딸기", new BigDecimal("12000"), 30));
        productRepository.save(new Product(snack, "감자칩 오리지널",
                "바삭한 감자칩", new BigDecimal("1500"), 200));
        productRepository.save(new Product(snack, "초코쿠키 12개입",
                "달콤한 초코쿠키", new BigDecimal("3500"), 120));
    }
```

- [ ] **Step 7: 전체 테스트 통과 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 전체 테스트 13개(Task 1 완료 시점 11개 + 이번에 추가한 2개) 통과.

- [ ] **Step 8: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: 공급사명 유니크 제약 및 시드 멱등성 보강"
```

---

## Task 3: DB 접속 정보 환경변수 외부화

**Files:**
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: datasource를 환경변수 플레이스홀더로 변경**

Replace `backend/src/main/resources/application.yml` (전체 교체):

```yaml
spring:
  application:
    name: backend
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/ecommerce?serverTimezone=UTC&characterEncoding=UTF-8}
    username: ${DB_USERNAME:ecommerce}
    password: ${DB_PASSWORD:ecommerce}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        format_sql: true
    open-in-view: false
  jackson:
    serialization:
      indent-output: true

server:
  port: 8080
```

**변경점:** `url`/`username`/`password` 3개 항목만 `${환경변수:기본값}` 형태로 바뀐다.
기본값은 기존 로컬 값과 동일하므로 환경변수가 없으면 동작이 완전히 같다.

- [ ] **Step 2: 컨텍스트 로딩 테스트로 문법 검증**

플레이스홀더 문법 오류가 있으면 컨텍스트 로딩이 실패한다.

Run: `cd backend && ./gradlew test --tests "com.ecommerce.BackendApplicationTests"`
Expected: PASS.

- [ ] **Step 3: 커밋**

```bash
cd .. && git add backend && git commit -m "refactor: DB 접속 정보 환경변수 외부화"
```

---

## Task 4: 프론트 메타데이터 정리

**Files:**
- Modify: `frontend/src/app/layout.tsx`

- [ ] **Step 1: metadata와 lang 수정**

Modify `frontend/src/app/layout.tsx` — `metadata` 상수와 `<html>` 태그의 `lang` 속성만 변경한다.
폰트 설정(Geist), className, 나머지 구조는 그대로 유지한다.

`metadata` 상수를 다음으로 교체:

```tsx
export const metadata: Metadata = {
  title: "이커머스 스토어",
  description: "공급사별 상품을 판매하는 이커머스 스토어",
};
```

`<html>` 태그의 `lang` 속성을 교체:

```tsx
    <html
      lang="ko"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
```

- [ ] **Step 2: 빌드 검증**

Run: `cd frontend && npm run build`
Expected: 빌드 성공, 7개 라우트 등록(기존과 동일).

- [ ] **Step 3: 커밋**

```bash
cd .. && git add frontend && git commit -m "feat: 사이트 메타데이터 한국어화(제목/설명/lang)"
```

---

## Task 5: README 안내 추가 + 최종 검증

**Files:**
- Modify: `README.md`

- [ ] **Step 1: README 실행 방법 섹션에 DB 재생성 안내 추가**

`README.md`의 실행 방법 섹션에서 기존 인용문(`> 이번 골격에는 인증이 없다(개방 API).`) 바로 아래에
다음 인용문을 추가한다:

```markdown
> **기존 로컬 DB 볼륨이 있는 경우:** 공급사명 유니크 제약이 추가되어 기존 볼륨에는 자동
> 적용되지 않을 수 있다. `docker compose down -v && docker compose up -d`로 볼륨을
> 재생성하면 새 스키마로 시작한다.
```

- [ ] **Step 2: 백엔드 전체 테스트 + 프론트 빌드 최종 확인**

Run: `cd backend && ./gradlew test && cd ../frontend && npm run build && cd ..`
Expected: 백엔드 BUILD SUCCESSFUL(테스트 13개), 프론트 빌드 성공.

- [ ] **Step 3: (선택) 메타데이터 수동 확인**

전체 스택을 기동할 수 있는 환경이라면 브라우저 탭 제목을 확인한다 (스펙 DoD 3):

```bash
( cd frontend && npm run dev & )
# 브라우저에서 http://localhost:3000 접속 → 탭 제목 "이커머스 스토어" 확인 후 프로세스 종료
```

확인이 어려우면 `frontend/src/app/layout.tsx`의 `metadata.title` 값 검토로 갈음한다.

- [ ] **Step 4: 커밋**

```bash
git add README.md
git commit -m "docs: README에 DB 볼륨 재생성 안내 추가"
```

---

## Self-Review 메모

- **스펙 커버리지:** ① 메타데이터(Task 4), ② create() 이중 세팅(Task 1), ③ DB 외부화(Task 3),
  ④ 유니크 제약+시드(Task 2), README 마이그레이션 안내(Task 5) — 스펙 전 항목 매핑됨.
- **테스트 개수 추적:** 골격 완료 시점 9개 → Task 1에서 +2(동작 고정) → Task 2에서 +2(유니크/exists)
  = 최종 13개. Task 1 Step 6(11개), Task 2 Step 7(13개), Task 5 Step 2(13개)의 기대 개수와 일치.
- **타입 일관성:** Task 1에서 추가한 `Supplier(String, String, SupplierStatus)` /
  `Product(Supplier, String, String, BigDecimal, int, ProductStatus)` 생성자 시그니처가
  각 Service.create()의 호출부와 일치. Task 2의 `existsByName(String)`이 DataSeeder 호출부와 일치.
- **import 경로:** 모든 테스트 코드가 이 프로젝트의 Spring Boot 4.x 경로
  (`tools.jackson...`, `boot.webmvc.test...`, `boot.data.jpa.test...`)를 사용함 — 문서 상단 표 참조.
- **플레이스홀더:** 없음 — 모든 단계에 실제 코드 포함.
- **실행 순서 의존성:** Task 2는 Task 1의 생성자 변경과 무관(기존 2-인자 생성자 사용 유지).
  Task 1~5는 명시된 순서대로 실행하되, 서로 다른 파일을 다루는 Task 3·4는 순서가 바뀌어도 무방.
