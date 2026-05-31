# 이커머스 골격(Skeleton) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공급사별 상품 관리 어드민과 일반 스토어프론트를 갖춘 이커머스 사이트의 실행 가능한 골격(모노레포: Spring Boot 백엔드 + Next.js 프론트 + MySQL)을 만든다.

**Architecture:** 3계층 분리 — Next.js(화면, :3000) → Spring Boot REST API(:8080) → MySQL(:3306, Docker). 백엔드는 도메인별 패키지(supplier, product) 구조. 스토어용/어드민용 API 컨트롤러를 분리해 이후 인증 부착이 쉽도록 한다. 이번 범위에 인증은 없다(개방 API).

**Tech Stack:** Java 25(Temurin), Spring Boot(Initializr 최신 안정), Spring Web, Spring Data JPA, Gradle(Kotlin DSL), MySQL 8(Docker), 테스트는 H2. 프론트는 Next.js(App Router)+TypeScript, Node 22.12 LTS.

**참고 스펙:** `docs/superpowers/specs/2026-05-31-ecommerce-skeleton-design.md`

---

## 파일 구조 (최종 형태)

```
e-commerce/
├── .gitignore
├── README.md
├── docker-compose.yml
├── backend/
│   ├── (Spring Initializr 생성물: gradlew, build.gradle.kts, settings.gradle.kts, gradle/)
│   └── src/
│       ├── main/java/com/ecommerce/
│       │   ├── EcommerceApplication.java
│       │   ├── common/
│       │   │   ├── WebConfig.java                 # CORS
│       │   │   ├── GlobalExceptionHandler.java     # 404/400
│       │   │   ├── NotFoundException.java
│       │   │   └── DataSeeder.java                 # CommandLineRunner 시드
│       │   ├── supplier/
│       │   │   ├── Supplier.java
│       │   │   ├── SupplierStatus.java
│       │   │   ├── SupplierRepository.java
│       │   │   ├── SupplierService.java
│       │   │   ├── SupplierController.java          # /api/admin/suppliers
│       │   │   └── dto/{SupplierRequest, SupplierResponse}.java
│       │   └── product/
│       │       ├── Product.java
│       │       ├── ProductStatus.java
│       │       ├── ProductRepository.java
│       │       ├── ProductService.java
│       │       ├── ProductController.java           # /api/products
│       │       ├── AdminProductController.java        # /api/admin/products
│       │       └── dto/{ProductRequest, ProductResponse}.java
│       ├── main/resources/application.yml
│       ├── main/resources/application-test.yml
│       └── test/java/com/ecommerce/...               # 스모크/단위 테스트
└── frontend/
    ├── (create-next-app 생성물)
    └── src/
        ├── app/
        │   ├── page.tsx                  # 스토어 상품 목록
        │   ├── products/[id]/page.tsx    # 상품 상세
        │   └── admin/
        │       ├── page.tsx              # 어드민 대시보드
        │       ├── suppliers/page.tsx    # 공급사 목록
        │       └── products/page.tsx     # 공급사별 상품 목록
        └── lib/api.ts                    # 백엔드 호출 래퍼 + 타입
```

---

## Task 1: 루트 모노레포 스캐폴딩

**Files:**
- Create: `.gitignore`
- Create: `docker-compose.yml`
- Create: `README.md`

- [ ] **Step 1: 루트 `.gitignore` 작성**

Create `.gitignore`:

```gitignore
# Java / Gradle
backend/.gradle/
backend/build/
*.class

# Node / Next.js
frontend/node_modules/
frontend/.next/
frontend/out/

# IDE / OS
.idea/
.DS_Store
*.log

# Env
.env
.env.local
```

- [ ] **Step 2: `docker-compose.yml` 작성 (MySQL 8)**

Create `docker-compose.yml`:

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: ecommerce-mysql
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: rootpw
      MYSQL_DATABASE: ecommerce
      MYSQL_USER: ecommerce
      MYSQL_PASSWORD: ecommerce
    volumes:
      - ecommerce-mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-prootpw"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  ecommerce-mysql-data:
```

- [ ] **Step 3: README 초안 작성**

Create `README.md`:

```markdown
# E-commerce (Skeleton)

공급사별 상품 관리 어드민과 스토어프론트를 갖춘 이커머스 골격.

## 구성
- `backend/` — Spring Boot REST API (Java 25, Gradle)
- `frontend/` — Next.js (App Router, TypeScript)
- `docker-compose.yml` — MySQL 8

## 실행 방법
1. DB 기동: `docker compose up -d`
2. 백엔드: `cd backend && ./gradlew bootRun` (http://localhost:8080)
3. 프론트: `cd frontend && npm install && npm run dev` (http://localhost:3000)
4. 접속: 스토어 http://localhost:3000 · 어드민 http://localhost:3000/admin

> 이번 골격에는 인증이 없다(개방 API).
```

- [ ] **Step 4: docker-compose 검증**

Run: `docker compose config`
Expected: 에러 없이 파싱된 설정이 출력된다.

- [ ] **Step 5: 커밋**

```bash
git add .gitignore docker-compose.yml README.md
git commit -m "chore: 모노레포 루트 스캐폴딩(.gitignore, docker-compose, README)"
```

---

## Task 2: Spring Boot 백엔드 생성 및 기동 검증

**Files:**
- Create: `backend/` (Spring Initializr 생성물 전체)
- Modify: `backend/src/main/resources/application.properties` → `application.yml` 로 교체
- Create: `backend/src/main/resources/application-test.yml`

- [ ] **Step 1: Spring Initializr로 프로젝트 생성**

루트에서 실행. `javaVersion=25`로 시도하고, 만약 Initializr가 25를 거부하면 `javaVersion=24`로 재시도한다(생성 후 build.gradle.kts의 `JavaLanguageVersion`을 25로 수정).

```bash
curl -sf https://start.spring.io/starter.tgz \
  -d type=gradle-project-kotlin \
  -d language=java \
  -d javaVersion=25 \
  -d groupId=com.ecommerce \
  -d artifactId=backend \
  -d name=backend \
  -d packageName=com.ecommerce \
  -d dependencies=web,data-jpa,mysql,validation,h2 \
  -o /tmp/backend.tgz \
  && mkdir -p backend && tar -xzf /tmp/backend.tgz -C backend
```

- [ ] **Step 2: build.gradle.kts의 Java toolchain이 25인지 확인**

Read `backend/build.gradle.kts`. `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` 블록(또는 `sourceCompatibility`)이 25를 가리키는지 확인. 아니면 25로 수정.

- [ ] **Step 3: `application.properties`를 삭제하고 `application.yml` 작성**

Delete `backend/src/main/resources/application.properties` (있다면).
Create `backend/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: backend
  datasource:
    url: jdbc:mysql://localhost:3306/ecommerce?serverTimezone=UTC&characterEncoding=UTF-8
    username: ecommerce
    password: ecommerce
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

- [ ] **Step 4: 테스트 프로파일(H2) 작성**

Create `backend/src/main/resources/application-test.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
```

- [ ] **Step 5: 기본 컨텍스트 로딩 테스트를 test 프로파일로 실행**

Initializr가 만든 `BackendApplicationTests`에 `@ActiveProfiles("test")`를 추가한다.

Modify `backend/src/test/java/com/ecommerce/BackendApplicationTests.java`:

```java
package com.ecommerce;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 6: 빌드 및 컨텍스트 로딩 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL, `contextLoads` 통과 (H2로 컨텍스트 로딩됨, MySQL 불필요).

- [ ] **Step 7: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: Spring Boot 백엔드 생성 및 application.yml/테스트 프로파일 구성"
```

---

## Task 3: Supplier 엔티티 + 리포지토리 (TDD)

**Files:**
- Create: `backend/src/main/java/com/ecommerce/supplier/SupplierStatus.java`
- Create: `backend/src/main/java/com/ecommerce/supplier/Supplier.java`
- Create: `backend/src/main/java/com/ecommerce/supplier/SupplierRepository.java`
- Test: `backend/src/test/java/com/ecommerce/supplier/SupplierRepositoryTest.java`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

Create `backend/src/test/java/com/ecommerce/supplier/SupplierRepositoryTest.java`:

```java
package com.ecommerce.supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

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
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.supplier.SupplierRepositoryTest"`
Expected: FAIL — `Supplier`, `SupplierStatus`, `SupplierRepository` 컴파일 불가.

- [ ] **Step 3: `SupplierStatus` 작성**

Create `backend/src/main/java/com/ecommerce/supplier/SupplierStatus.java`:

```java
package com.ecommerce.supplier;

// 공급사 상태
public enum SupplierStatus {
    ACTIVE,   // 활성
    INACTIVE  // 비활성
}
```

- [ ] **Step 4: `Supplier` 엔티티 작성**

Create `backend/src/main/java/com/ecommerce/supplier/Supplier.java`:

```java
package com.ecommerce.supplier;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupplierStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected Supplier() {
    }

    public Supplier(String name, String contactEmail) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.status = SupplierStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // 도메인 수정 메서드
    public void update(String name, String contactEmail, SupplierStatus status) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.status = status;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getContactEmail() { return contactEmail; }
    public SupplierStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: `SupplierRepository` 작성**

Create `backend/src/main/java/com/ecommerce/supplier/SupplierRepository.java`:

```java
package com.ecommerce.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.supplier.SupplierRepositoryTest"`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: Supplier 엔티티/리포지토리 추가"
```

---

## Task 4: Supplier DTO + 서비스 + 어드민 컨트롤러 (TDD)

**Files:**
- Create: `backend/src/main/java/com/ecommerce/common/NotFoundException.java`
- Create: `backend/src/main/java/com/ecommerce/supplier/dto/SupplierRequest.java`
- Create: `backend/src/main/java/com/ecommerce/supplier/dto/SupplierResponse.java`
- Create: `backend/src/main/java/com/ecommerce/supplier/SupplierService.java`
- Create: `backend/src/main/java/com/ecommerce/supplier/SupplierController.java`
- Test: `backend/src/test/java/com/ecommerce/supplier/SupplierControllerTest.java`

- [ ] **Step 1: 실패하는 컨트롤러 통합 테스트 작성**

Create `backend/src/test/java/com/ecommerce/supplier/SupplierControllerTest.java`:

```java
package com.ecommerce.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.supplier.SupplierControllerTest"`
Expected: FAIL — 컨트롤러/서비스/DTO 미존재로 컴파일 불가.

- [ ] **Step 3: 공통 `NotFoundException` 작성**

Create `backend/src/main/java/com/ecommerce/common/NotFoundException.java`:

```java
package com.ecommerce.common;

// 리소스를 찾지 못했을 때 던지는 예외 (404 매핑)
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: DTO 작성 (record)**

Create `backend/src/main/java/com/ecommerce/supplier/dto/SupplierRequest.java`:

```java
package com.ecommerce.supplier.dto;

import com.ecommerce.supplier.SupplierStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 공급사 생성/수정 요청
public record SupplierRequest(
        @NotBlank String name,
        String contactEmail,
        @NotNull SupplierStatus status
) {
}
```

Create `backend/src/main/java/com/ecommerce/supplier/dto/SupplierResponse.java`:

```java
package com.ecommerce.supplier.dto;

import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierStatus;
import java.time.LocalDateTime;

// 공급사 응답
public record SupplierResponse(
        Long id,
        String name,
        String contactEmail,
        SupplierStatus status,
        LocalDateTime createdAt
) {
    public static SupplierResponse from(Supplier s) {
        return new SupplierResponse(
                s.getId(), s.getName(), s.getContactEmail(),
                s.getStatus(), s.getCreatedAt());
    }
}
```

- [ ] **Step 5: `SupplierService` 작성**

Create `backend/src/main/java/com/ecommerce/supplier/SupplierService.java`:

```java
package com.ecommerce.supplier;

import com.ecommerce.common.NotFoundException;
import com.ecommerce.supplier.dto.SupplierRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    public List<Supplier> findAll() {
        return supplierRepository.findAll();
    }

    public Supplier findById(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("공급사를 찾을 수 없습니다: " + id));
    }

    @Transactional
    public Supplier create(SupplierRequest request) {
        Supplier supplier = new Supplier(request.name(), request.contactEmail());
        supplier.update(request.name(), request.contactEmail(), request.status());
        return supplierRepository.save(supplier);
    }

    @Transactional
    public Supplier update(Long id, SupplierRequest request) {
        Supplier supplier = findById(id);
        supplier.update(request.name(), request.contactEmail(), request.status());
        return supplier;
    }

    @Transactional
    public void delete(Long id) {
        if (!supplierRepository.existsById(id)) {
            throw new NotFoundException("공급사를 찾을 수 없습니다: " + id);
        }
        supplierRepository.deleteById(id);
    }
}
```

- [ ] **Step 6: `SupplierController` 작성**

Create `backend/src/main/java/com/ecommerce/supplier/SupplierController.java`:

```java
package com.ecommerce.supplier;

import com.ecommerce.supplier.dto.SupplierRequest;
import com.ecommerce.supplier.dto.SupplierResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/suppliers")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    @GetMapping
    public List<SupplierResponse> list() {
        return supplierService.findAll().stream().map(SupplierResponse::from).toList();
    }

    @GetMapping("/{id}")
    public SupplierResponse get(@PathVariable Long id) {
        return SupplierResponse.from(supplierService.findById(id));
    }

    @PostMapping
    public ResponseEntity<SupplierResponse> create(@Valid @RequestBody SupplierRequest request) {
        SupplierResponse body = SupplierResponse.from(supplierService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return SupplierResponse.from(supplierService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.supplier.SupplierControllerTest"`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: 공급사 어드민 API(DTO/서비스/컨트롤러) 추가"
```

---

## Task 5: Product 엔티티 + 리포지토리 (TDD)

**Files:**
- Create: `backend/src/main/java/com/ecommerce/product/ProductStatus.java`
- Create: `backend/src/main/java/com/ecommerce/product/Product.java`
- Create: `backend/src/main/java/com/ecommerce/product/ProductRepository.java`
- Test: `backend/src/test/java/com/ecommerce/product/ProductRepositoryTest.java`

- [ ] **Step 1: 실패하는 리포지토리 테스트 작성**

Create `backend/src/test/java/com/ecommerce/product/ProductRepositoryTest.java`:

```java
package com.ecommerce.product;

import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;

    @Test
    void 공급사별로_상품을_조회한다() {
        Supplier a = supplierRepository.save(new Supplier("공급사A", "a@example.com"));
        Supplier b = supplierRepository.save(new Supplier("공급사B", "b@example.com"));
        productRepository.save(new Product(a, "사과", "맛있는 사과", new BigDecimal("3000"), 10));
        productRepository.save(new Product(a, "배", "달콤한 배", new BigDecimal("5000"), 5));
        productRepository.save(new Product(b, "감자", "포슬포슬", new BigDecimal("2000"), 20));

        List<Product> aProducts = productRepository.findBySupplierId(a.getId());

        assertThat(aProducts).hasSize(2);
        assertThat(aProducts).extracting(Product::getName)
                .containsExactlyInAnyOrder("사과", "배");
    }

    @Test
    void 상태로_상품을_조회한다() {
        Supplier a = supplierRepository.save(new Supplier("공급사A", "a@example.com"));
        productRepository.save(new Product(a, "사과", "설명", new BigDecimal("3000"), 10));

        List<Product> onSale = productRepository.findByStatus(ProductStatus.ON_SALE);

        assertThat(onSale).hasSize(1);
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.product.ProductRepositoryTest"`
Expected: FAIL — `Product`, `ProductStatus`, `ProductRepository` 미존재.

- [ ] **Step 3: `ProductStatus` 작성**

Create `backend/src/main/java/com/ecommerce/product/ProductStatus.java`:

```java
package com.ecommerce.product;

// 상품 상태
public enum ProductStatus {
    ON_SALE,   // 판매중
    SOLD_OUT,  // 품절
    HIDDEN     // 숨김
}
```

- [ ] **Step 4: `Product` 엔티티 작성**

Create `backend/src/main/java/com/ecommerce/product/Product.java`:

```java
package com.ecommerce.product;

import com.ecommerce.supplier.Supplier;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 공급사 N:1 — 지연 로딩
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stockQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Product() {
    }

    public Product(Supplier supplier, String name, String description,
                   BigDecimal price, int stockQuantity) {
        this.supplier = supplier;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.status = ProductStatus.ON_SALE;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void update(String name, String description, BigDecimal price,
                       int stockQuantity, ProductStatus status) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.status = status;
    }

    public void changeSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public Long getId() { return id; }
    public Supplier getSupplier() { return supplier; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public int getStockQuantity() { return stockQuantity; }
    public ProductStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: `ProductRepository` 작성**

Create `backend/src/main/java/com/ecommerce/product/ProductRepository.java`:

```java
package com.ecommerce.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findBySupplierId(Long supplierId);

    List<Product> findByStatus(ProductStatus status);
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.product.ProductRepositoryTest"`
Expected: PASS.

- [ ] **Step 7: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: Product 엔티티/리포지토리 추가(공급사 N:1)"
```

---

## Task 6: Product DTO + 서비스 + 스토어/어드민 컨트롤러 (TDD)

**Files:**
- Create: `backend/src/main/java/com/ecommerce/product/dto/ProductRequest.java`
- Create: `backend/src/main/java/com/ecommerce/product/dto/ProductResponse.java`
- Create: `backend/src/main/java/com/ecommerce/product/ProductService.java`
- Create: `backend/src/main/java/com/ecommerce/product/ProductController.java`
- Create: `backend/src/main/java/com/ecommerce/product/AdminProductController.java`
- Test: `backend/src/test/java/com/ecommerce/product/ProductApiTest.java`

- [ ] **Step 1: 실패하는 API 통합 테스트 작성**

Create `backend/src/test/java/com/ecommerce/product/ProductApiTest.java`:

```java
package com.ecommerce.product;

import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
class ProductApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SupplierRepository supplierRepository;

    @Test
    void 어드민이_상품을_생성하고_공급사별로_조회한다() throws Exception {
        Supplier supplier = supplierRepository.save(
                new Supplier("공급사A", "a@example.com"));

        String body = objectMapper.writeValueAsString(Map.of(
                "supplierId", supplier.getId(),
                "name", "사과",
                "description", "맛있는 사과",
                "price", 3000,
                "stockQuantity", 10,
                "status", "ON_SALE"));

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("사과"))
                .andExpect(jsonPath("$.supplierId").value(supplier.getId()))
                .andExpect(jsonPath("$.supplierName").value("공급사A"));

        // 공급사별 조회
        mockMvc.perform(get("/api/admin/products")
                        .param("supplierId", String.valueOf(supplier.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("사과"));
    }

    @Test
    void 스토어에서_판매중_상품_목록을_조회한다() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.product.ProductApiTest"`
Expected: FAIL — DTO/서비스/컨트롤러 미존재.

- [ ] **Step 3: DTO 작성**

Create `backend/src/main/java/com/ecommerce/product/dto/ProductRequest.java`:

```java
package com.ecommerce.product.dto;

import com.ecommerce.product.ProductStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

// 상품 생성/수정 요청
public record ProductRequest(
        @NotNull Long supplierId,
        @NotBlank String name,
        String description,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero int stockQuantity,
        @NotNull ProductStatus status
) {
}
```

Create `backend/src/main/java/com/ecommerce/product/dto/ProductResponse.java`:

```java
package com.ecommerce.product.dto;

import com.ecommerce.product.Product;
import com.ecommerce.product.ProductStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 상품 응답 (공급사 정보 평탄화)
public record ProductResponse(
        Long id,
        Long supplierId,
        String supplierName,
        String name,
        String description,
        BigDecimal price,
        int stockQuantity,
        ProductStatus status,
        LocalDateTime createdAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(),
                p.getSupplier().getId(),
                p.getSupplier().getName(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getStockQuantity(),
                p.getStatus(),
                p.getCreatedAt());
    }
}
```

- [ ] **Step 4: `ProductService` 작성**

Create `backend/src/main/java/com/ecommerce/product/ProductService.java`:

```java
package com.ecommerce.product;

import com.ecommerce.common.NotFoundException;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;

    public ProductService(ProductRepository productRepository,
                          SupplierRepository supplierRepository) {
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
    }

    // 스토어: 판매중 상품만
    public List<Product> findOnSale() {
        return productRepository.findByStatus(ProductStatus.ON_SALE);
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("상품을 찾을 수 없습니다: " + id));
    }

    // 어드민: 전체 또는 공급사별
    public List<Product> findForAdmin(Long supplierId) {
        if (supplierId == null) {
            return productRepository.findAll();
        }
        return productRepository.findBySupplierId(supplierId);
    }

    @Transactional
    public Product create(ProductRequest request) {
        Supplier supplier = loadSupplier(request.supplierId());
        Product product = new Product(supplier, request.name(), request.description(),
                request.price(), request.stockQuantity());
        product.update(request.name(), request.description(), request.price(),
                request.stockQuantity(), request.status());
        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, ProductRequest request) {
        Product product = findById(id);
        if (!product.getSupplier().getId().equals(request.supplierId())) {
            product.changeSupplier(loadSupplier(request.supplierId()));
        }
        product.update(request.name(), request.description(), request.price(),
                request.stockQuantity(), request.status());
        return product;
    }

    @Transactional
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw new NotFoundException("상품을 찾을 수 없습니다: " + id);
        }
        productRepository.deleteById(id);
    }

    private Supplier loadSupplier(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new NotFoundException("공급사를 찾을 수 없습니다: " + supplierId));
    }
}
```

- [ ] **Step 5: 스토어용 `ProductController` 작성**

Create `backend/src/main/java/com/ecommerce/product/ProductController.java`:

```java
package com.ecommerce.product;

import com.ecommerce.product.dto.ProductResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 스토어프론트용 (공개)
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productService.findOnSale().stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(productService.findById(id));
    }
}
```

- [ ] **Step 6: 어드민용 `AdminProductController` 작성**

Create `backend/src/main/java/com/ecommerce/product/AdminProductController.java`:

```java
package com.ecommerce.product;

import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 어드민용 — 공급사별 상품 관리
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) Long supplierId) {
        return productService.findForAdmin(supplierId).stream()
                .map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(productService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse body = ProductResponse.from(productService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(productService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.product.ProductApiTest"`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: 상품 스토어/어드민 API(DTO/서비스/컨트롤러) 추가"
```

---

## Task 7: 공통 설정 — CORS + 전역 예외 처리

**Files:**
- Create: `backend/src/main/java/com/ecommerce/common/WebConfig.java`
- Create: `backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/ecommerce/common/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: 실패하는 404 테스트 작성**

Create `backend/src/test/java/com/ecommerce/common/GlobalExceptionHandlerTest.java`:

```java
package com.ecommerce.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void 존재하지_않는_상품_조회시_404와_메시지를_반환한다() throws Exception {
        mockMvc.perform(get("/api/products/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").exists());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.common.GlobalExceptionHandlerTest"`
Expected: FAIL — 핸들러가 없어 404가 아닌 500 반환.

- [ ] **Step 3: `GlobalExceptionHandler` 작성**

Create `backend/src/main/java/com/ecommerce/common/GlobalExceptionHandler.java`:

```java
package com.ecommerce.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// 전역 예외 처리 — 골격 수준(404/400)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("잘못된 요청입니다.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", message));
    }
}
```

- [ ] **Step 4: `WebConfig`(CORS) 작성**

Create `backend/src/main/java/com/ecommerce/common/WebConfig.java`:

```java
package com.ecommerce.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 프론트(localhost:3000) → 백엔드 CORS 허용
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests "com.ecommerce.common.GlobalExceptionHandlerTest"`
Expected: PASS.

- [ ] **Step 6: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: CORS 설정 및 전역 예외 처리(404/400) 추가"
```

---

## Task 8: 샘플 데이터 시드 (CommandLineRunner, 멱등)

**Files:**
- Create: `backend/src/main/java/com/ecommerce/common/DataSeeder.java`

- [ ] **Step 1: `DataSeeder` 작성**

`test` 프로파일에서는 동작하지 않도록 `@Profile("!test")`를 건다(테스트 격리).

Create `backend/src/main/java/com/ecommerce/common/DataSeeder.java`:

```java
package com.ecommerce.common;

import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// 앱 시작 시 샘플 데이터 시드 (이미 있으면 건너뜀 — 멱등)
@Component
@Profile("!test")
public class DataSeeder implements CommandLineRunner {

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;

    public DataSeeder(SupplierRepository supplierRepository,
                      ProductRepository productRepository) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        if (supplierRepository.count() > 0) {
            return; // 이미 시드됨
        }

        Supplier fresh = supplierRepository.save(
                new Supplier("신선식품 주식회사", "fresh@example.com"));
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
}
```

- [ ] **Step 2: 전체 테스트가 여전히 통과하는지 확인 (시드는 test에서 비활성)**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL — DataSeeder는 `!test`라 테스트에 영향 없음.

- [ ] **Step 3: 커밋**

```bash
cd .. && git add backend && git commit -m "feat: 샘플 데이터 시드(CommandLineRunner, 멱등) 추가"
```

---

## Task 9: MySQL 연동 전체 기동 검증

**Files:** (코드 변경 없음 — 통합 검증 단계)

- [ ] **Step 1: MySQL 컨테이너 기동**

Run: `docker compose up -d`
그리고 헬스체크 통과 대기:
Run: `docker compose ps`
Expected: `ecommerce-mysql` 상태가 `healthy`.

- [ ] **Step 2: 백엔드 빌드(전체 테스트 포함)**

Run: `cd backend && ./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: 백엔드 기동 후 시드/응답 확인**

Run (백그라운드): `./gradlew bootRun &` — 기동 로그에 Tomcat 8080, 시드 INSERT 확인.
기동 후:
Run: `curl -s http://localhost:8080/api/products | head -c 500`
Expected: 시드된 상품 4개가 담긴 JSON 배열.

Run: `curl -s "http://localhost:8080/api/admin/products?supplierId=1" | head -c 500`
Expected: 공급사 1의 상품만 담긴 JSON 배열.

- [ ] **Step 4: 백엔드 종료**

Run: `kill %1` (또는 해당 bootRun 프로세스 종료).

- [ ] **Step 5: (변경 없음) 커밋 생략**

검증만 수행. 코드 변경이 없으면 커밋하지 않는다.

---

## Task 10: 프론트엔드 생성 + API 래퍼

**Files:**
- Create: `frontend/` (create-next-app 생성물)
- Create: `frontend/src/lib/api.ts`
- Create: `frontend/.env.local`

- [ ] **Step 1: Next.js 앱 생성**

루트에서 실행(비대화형 플래그 명시):

```bash
npx create-next-app@latest frontend \
  --ts --app --src-dir --eslint \
  --tailwind --no-import-alias --use-npm
```

Expected: `frontend/` 에 App Router + TypeScript + src 디렉터리 구조 생성.

- [ ] **Step 2: 백엔드 베이스 URL 환경변수**

Create `frontend/.env.local`:

```
NEXT_PUBLIC_API_BASE=http://localhost:8080
```

- [ ] **Step 3: API 래퍼 + 타입 작성**

Create `frontend/src/lib/api.ts`:

```ts
// 백엔드 REST API 호출 래퍼 + 공유 타입

const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export type ProductStatus = "ON_SALE" | "SOLD_OUT" | "HIDDEN";
export type SupplierStatus = "ACTIVE" | "INACTIVE";

export interface Product {
  id: number;
  supplierId: number;
  supplierName: string;
  name: string;
  description: string | null;
  price: number;
  stockQuantity: number;
  status: ProductStatus;
  createdAt: string;
}

export interface Supplier {
  id: number;
  name: string;
  contactEmail: string | null;
  status: SupplierStatus;
  createdAt: string;
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, { cache: "no-store" });
  if (!res.ok) {
    throw new Error(`API 요청 실패 (${res.status}): ${path}`);
  }
  return res.json() as Promise<T>;
}

// 스토어
export const getProducts = () => getJson<Product[]>("/api/products");
export const getProduct = (id: string | number) =>
  getJson<Product>(`/api/products/${id}`);

// 어드민
export const getSuppliers = () => getJson<Supplier[]>("/api/admin/suppliers");
export const getAdminProducts = (supplierId?: number) =>
  getJson<Product[]>(
    `/api/admin/products${supplierId ? `?supplierId=${supplierId}` : ""}`,
  );
```

- [ ] **Step 4: 프론트 빌드 검증**

Run: `cd frontend && npm run build`
Expected: 빌드 성공(기본 페이지 기준). 경고는 허용.

- [ ] **Step 5: 커밋**

```bash
cd .. && git add frontend && git commit -m "feat: Next.js 프론트 생성 및 백엔드 API 래퍼 추가"
```

---

## Task 11: 스토어프론트 화면 (목록 + 상세)

**Files:**
- Modify/Create: `frontend/src/app/page.tsx`
- Create: `frontend/src/app/products/[id]/page.tsx`

- [ ] **Step 1: 상품 목록 페이지 작성**

Replace `frontend/src/app/page.tsx`:

```tsx
import Link from "next/link";
import { getProducts } from "@/lib/api";

export default async function HomePage() {
  let products;
  try {
    products = await getProducts();
  } catch {
    return (
      <main style={{ padding: 24 }}>
        <h1>상품 목록</h1>
        <p>백엔드에 연결할 수 없습니다. (http://localhost:8080)</p>
      </main>
    );
  }

  return (
    <main style={{ padding: 24 }}>
      <header style={{ display: "flex", justifyContent: "space-between" }}>
        <h1>스토어</h1>
        <Link href="/admin">어드민 →</Link>
      </header>
      <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
        {products.map((p) => (
          <li key={p.id} style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
            <Link href={`/products/${p.id}`}>
              <strong>{p.name}</strong>
            </Link>
            <div>{p.price.toLocaleString()}원</div>
            <small>{p.supplierName}</small>
          </li>
        ))}
      </ul>
    </main>
  );
}
```

- [ ] **Step 2: 상품 상세 페이지 작성**

Create `frontend/src/app/products/[id]/page.tsx`:

```tsx
import Link from "next/link";
import { getProduct } from "@/lib/api";

export default async function ProductDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  let product;
  try {
    product = await getProduct(id);
  } catch {
    return (
      <main style={{ padding: 24 }}>
        <p>상품을 찾을 수 없습니다.</p>
        <Link href="/">← 목록으로</Link>
      </main>
    );
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/">← 목록으로</Link>
      <h1>{product.name}</h1>
      <p>{product.description}</p>
      <p><strong>{product.price.toLocaleString()}원</strong></p>
      <p>재고: {product.stockQuantity}개</p>
      <p>공급사: {product.supplierName}</p>
    </main>
  );
}
```

- [ ] **Step 3: 빌드 검증**

Run: `cd frontend && npm run build`
Expected: 빌드 성공. `/`, `/products/[id]` 라우트가 잡힘.

- [ ] **Step 4: 커밋**

```bash
cd .. && git add frontend && git commit -m "feat: 스토어프론트 상품 목록/상세 화면 추가"
```

---

## Task 12: 어드민 화면 (대시보드 + 공급사 + 공급사별 상품)

**Files:**
- Create: `frontend/src/app/admin/page.tsx`
- Create: `frontend/src/app/admin/suppliers/page.tsx`
- Create: `frontend/src/app/admin/products/page.tsx`

- [ ] **Step 1: 어드민 대시보드 작성**

Create `frontend/src/app/admin/page.tsx`:

```tsx
import Link from "next/link";

export default function AdminHome() {
  return (
    <main style={{ padding: 24 }}>
      <h1>어드민</h1>
      <nav style={{ display: "flex", gap: 16 }}>
        <Link href="/admin/suppliers">공급사 관리</Link>
        <Link href="/admin/products">상품 관리(공급사별)</Link>
        <Link href="/">← 스토어</Link>
      </nav>
    </main>
  );
}
```

- [ ] **Step 2: 공급사 목록 화면 작성**

Create `frontend/src/app/admin/suppliers/page.tsx`:

```tsx
import Link from "next/link";
import { getSuppliers } from "@/lib/api";

export default async function AdminSuppliersPage() {
  let suppliers;
  try {
    suppliers = await getSuppliers();
  } catch {
    return <main style={{ padding: 24 }}><p>백엔드 연결 실패</p></main>;
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/admin">← 어드민</Link>
      <h1>공급사 관리</h1>
      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th style={cell}>ID</th><th style={cell}>이름</th>
            <th style={cell}>이메일</th><th style={cell}>상태</th>
          </tr>
        </thead>
        <tbody>
          {suppliers.map((s) => (
            <tr key={s.id}>
              <td style={cell}>{s.id}</td>
              <td style={cell}>{s.name}</td>
              <td style={cell}>{s.contactEmail}</td>
              <td style={cell}>{s.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}

const cell: React.CSSProperties = { border: "1px solid #ddd", padding: 8, textAlign: "left" };
```

- [ ] **Step 3: 공급사별 상품 화면 작성 (필터 포함)**

Create `frontend/src/app/admin/products/page.tsx`:

```tsx
import Link from "next/link";
import { getAdminProducts, getSuppliers } from "@/lib/api";

export default async function AdminProductsPage({
  searchParams,
}: {
  searchParams: Promise<{ supplierId?: string }>;
}) {
  const { supplierId } = await searchParams;
  const selectedId = supplierId ? Number(supplierId) : undefined;

  let suppliers, products;
  try {
    [suppliers, products] = await Promise.all([
      getSuppliers(),
      getAdminProducts(selectedId),
    ]);
  } catch {
    return <main style={{ padding: 24 }}><p>백엔드 연결 실패</p></main>;
  }

  return (
    <main style={{ padding: 24 }}>
      <Link href="/admin">← 어드민</Link>
      <h1>상품 관리 (공급사별)</h1>

      <nav style={{ display: "flex", gap: 12, margin: "12px 0" }}>
        <Link href="/admin/products">전체</Link>
        {suppliers.map((s) => (
          <Link key={s.id} href={`/admin/products?supplierId=${s.id}`}>
            {s.name}
          </Link>
        ))}
      </nav>

      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th style={cell}>ID</th><th style={cell}>상품명</th>
            <th style={cell}>공급사</th><th style={cell}>가격</th>
            <th style={cell}>재고</th><th style={cell}>상태</th>
          </tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.id}>
              <td style={cell}>{p.id}</td>
              <td style={cell}>{p.name}</td>
              <td style={cell}>{p.supplierName}</td>
              <td style={cell}>{p.price.toLocaleString()}원</td>
              <td style={cell}>{p.stockQuantity}</td>
              <td style={cell}>{p.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </main>
  );
}

const cell: React.CSSProperties = { border: "1px solid #ddd", padding: 8, textAlign: "left" };
```

- [ ] **Step 4: 빌드 검증**

Run: `cd frontend && npm run build`
Expected: 빌드 성공. `/admin`, `/admin/suppliers`, `/admin/products` 라우트가 잡힘.

- [ ] **Step 5: 커밋**

```bash
cd .. && git add frontend && git commit -m "feat: 어드민 대시보드/공급사/공급사별 상품 화면 추가"
```

---

## Task 13: 엔드투엔드 수동 검증 + README 마무리

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 전체 스택 기동**

```bash
docker compose up -d
( cd backend && ./gradlew bootRun & )
( cd frontend && npm run dev & )
```

- [ ] **Step 2: 스토어 흐름 확인**

브라우저로 `http://localhost:3000` 접속:
Expected: 시드된 상품 4개 목록 표시 → 상품 클릭 시 상세(이름/설명/가격/재고/공급사) 표시.

- [ ] **Step 3: 어드민 흐름 확인**

`http://localhost:3000/admin` 접속:
Expected: 공급사 관리에 2개 공급사 표시. 상품 관리에서 공급사 탭(전체/신선식품/바삭과자) 클릭 시 해당 공급사 상품만 필터링.

- [ ] **Step 4: 프로세스 종료**

백그라운드 bootRun/dev 프로세스 및 `docker compose down` 정리.

- [ ] **Step 5: README에 검증된 절차/구조 반영**

`README.md`에 도메인 모델 요약과 주요 API 표(스펙 6장 발췌)를 추가하고, 실행 절차가 실제와 일치하는지 확인 후 갱신.

- [ ] **Step 6: 커밋**

```bash
git add README.md
git commit -m "docs: README에 실행 절차·도메인·API 요약 보강"
```

---

## Self-Review 메모

- **스펙 커버리지:** 저장소 레이아웃(Task 1·2·10), Supplier/Product 도메인(Task 3·5), 스토어/어드민 API 분리(Task 4·6), CORS·예외(Task 7), 시드(Task 8), MySQL 연동(Task 2·9), 프론트 화면(Task 11·12), README/실행(Task 1·13) — 스펙 전 항목이 태스크에 매핑됨.
- **타입 일관성:** `ProductResponse`의 `supplierId/supplierName` 필드가 프론트 `Product` 타입(api.ts) 및 어드민 화면 사용처와 일치. `SupplierRequest.status`/`ProductRequest.status`는 enum 문자열로 직렬화되며 테스트의 `"ACTIVE"`/`"ON_SALE"`와 일치.
- **플레이스홀더:** 없음 — 모든 코드 단계에 실제 코드 포함.
- **주의(실행자용):** Task 2 Step 1에서 Initializr가 `javaVersion=25`를 거부하면 24로 생성 후 `build.gradle.kts`의 toolchain을 25로 수정한다. create-next-app/Initializr가 생성하는 보일러플레이트 파일은 커밋에 함께 포함한다.
```
