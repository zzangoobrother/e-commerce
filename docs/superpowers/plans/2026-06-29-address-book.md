# 배송지 관리(주소록 CRUD) 구현 플랜 (사이클 14)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 고객이 여러 배송지를 등록·조회·수정·삭제하고 기본배송지를 지정하는 주소록 CRUD API와 화면을 추가한다.

**Architecture:** 기존 `Order`/`OrderItem`이 쓰는 스칼라 `customerId: Long` 참조 패턴을 따르는 신규 `CustomerAddress` 엔티티 + `JpaRepository`. Controller(`@AuthenticationPrincipal Jwt`) → Service(`@Transactional`) → Repository 3계층. "기본배송지 항상 0/1개" 불변식은 단일 트랜잭션 안에서 "기존 기본 해제 → 신규 지정"으로 보장. 프론트는 `/orders` 페이지 패턴을 그대로 따른 Server Component + Server Action.

**Tech Stack:** Spring Boot 3(Java 17+, JPA/Hibernate, Spring Security OAuth2 Resource Server, Bean Validation), JUnit5 + `@SpringBootTest` + MockMvc + H2, Next.js(App Router, 비표준 — `node_modules/next/dist/docs/` 우선 확인).

## Global Constraints

- **언어:** 코드 주석·커밋 메시지·문서는 한국어, 식별자는 영어.
- **새 인프라 없음:** Redis 등 금지. 기존 `GlobalExceptionHandler`·커스텀 예외(`BadRequestException`·`NotFoundException`·`UnauthorizedException`)·DTO 검증·인증 패턴 재사용.
- **스키마:** Flyway/Liquibase 미사용. `ddl-auto`가 `customer_addresses` 테이블 자동 생성(운영 `update`, 테스트/로컬 `create-drop`).
- **연관 매핑:** Customer와의 관계는 `@ManyToOne`이 아닌 스칼라 `customerId: Long`.
- **인증:** OrderController와 동일 — `@AuthenticationPrincipal Jwt jwt` → `jwt.getSubject()`(email) → `customerRepository.findByEmail()` → `customerId`.
- **기본배송지 불변식:** 항상 0개 또는 1개. 첫 주소는 `isDefault` 값과 무관하게 자동 기본.
- **소유권:** 모든 `{id}` 연산은 `findByIdAndCustomerId`로 소유권 강제, 불일치/미존재 모두 **404**(존재 노출 금지).
- **상한:** 고객당 배송지 최대 10개.
- **브랜치:** `feature/address-book`(이미 생성됨, `origin/main`에서 분기).

---

## File Structure

**백엔드 (신규 패키지 `com.ecommerce.address`)**
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddress.java` — 엔티티
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddressRepository.java` — 리포지토리
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddressService.java` — 서비스(불변식·상한·소유권)
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddressController.java` — REST 컨트롤러
- Create: `backend/src/main/java/com/ecommerce/address/dto/CreateAddressRequest.java`
- Create: `backend/src/main/java/com/ecommerce/address/dto/UpdateAddressRequest.java`
- Create: `backend/src/main/java/com/ecommerce/address/dto/AddressResponse.java`
- Modify: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java` — `/api/store/addresses/**` 인가 규칙 추가
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**프론트엔드**
- Modify: `frontend/src/lib/api.ts` — `Address`/`AddressInput` 타입 + 5개 함수
- Create: `frontend/src/app/addresses/page.tsx` — 목록 Server Component
- Create: `frontend/src/app/addresses/actions.ts` — 등록/수정/삭제/기본지정 Server Action

---

## Task 1: 배송지 등록 + 자동 기본배송지 (백엔드 수직 슬라이스)

이 태스크가 엔티티·리포지토리·DTO·서비스·컨트롤러·보안 설정·테스트 클래스 골격을 모두 세운다. 이후 태스크는 여기에 메서드와 테스트를 더한다.

**Files:**
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddress.java`
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddressRepository.java`
- Create: `backend/src/main/java/com/ecommerce/address/dto/CreateAddressRequest.java`
- Create: `backend/src/main/java/com/ecommerce/address/dto/AddressResponse.java`
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddressService.java`
- Create: `backend/src/main/java/com/ecommerce/address/CustomerAddressController.java`
- Modify: `backend/src/main/java/com/ecommerce/common/SecurityConfig.java`
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**Interfaces:**
- Produces:
  - `CustomerAddress(Long customerId, String label, String recipientName, String phone, String zipCode, String address1, String address2, boolean isDefault)` + `void update(String,String,String,String,String,String)` + `void markDefault(boolean)` + getters(`getId,getCustomerId,getLabel,getRecipientName,getPhone,getZipCode,getAddress1,getAddress2,isDefault,getCreatedAt`)
  - `CustomerAddressRepository`: `long countByCustomerId(Long)`, `Optional<CustomerAddress> findByCustomerIdAndIsDefaultTrue(Long)` (Task 2~5에서 메서드 추가)
  - `CustomerAddressService.create(Long customerId, CreateAddressRequest) : AddressResponse`
  - `AddressResponse.from(CustomerAddress) : AddressResponse`
  - 테스트 헬퍼: `customer(email)`, `customerJwt(email)`, `addressJson(label, isDefault)`, `register(email, label, isDefault) : long`

- [ ] **Step 1: 엔티티와 리포지토리 생성 (테스트 컴파일에 필요한 골격)**

`backend/src/main/java/com/ecommerce/address/CustomerAddress.java`:

```java
package com.ecommerce.address;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 고객 배송지(주소록) — Customer와의 관계는 스칼라 customerId로 둔다(Order 패턴).
@Entity
@Table(name = "customer_addresses")
public class CustomerAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String zipCode;

    @Column(nullable = false)
    private String address1;

    // 상세주소는 선택 입력
    private String address2;

    @Column(nullable = false)
    private boolean isDefault;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // JPA 기본 생성자
    protected CustomerAddress() {
    }

    public CustomerAddress(Long customerId, String label, String recipientName,
                           String phone, String zipCode, String address1,
                           String address2, boolean isDefault) {
        this.customerId = customerId;
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.zipCode = zipCode;
        this.address1 = address1;
        this.address2 = address2;
        this.isDefault = isDefault;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // 필드 수정 — 기본배송지 여부는 여기서 바꾸지 않는다(전용 경로로 일원화)
    public void update(String label, String recipientName, String phone,
                       String zipCode, String address1, String address2) {
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.zipCode = zipCode;
        this.address1 = address1;
        this.address2 = address2;
    }

    public void markDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public String getLabel() { return label; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getZipCode() { return zipCode; }
    public String getAddress1() { return address1; }
    public String getAddress2() { return address2; }
    public boolean isDefault() { return isDefault; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

`backend/src/main/java/com/ecommerce/address/CustomerAddressRepository.java`:

```java
package com.ecommerce.address;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    // 상한(10개) 검사용
    long countByCustomerId(Long customerId);

    // 기본배송지 해제(불변식 유지)용 — 현재 기본 1건 조회
    Optional<CustomerAddress> findByCustomerIdAndIsDefaultTrue(Long customerId);
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (테스트 클래스 골격 + 첫 시나리오)**

`backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`:

```java
package com.ecommerce.address;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerAddressControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomerRepository customerRepository;
    @Autowired CustomerAddressRepository addressRepository;

    @AfterEach
    void cleanup() {
        // FK 역순: 배송지 먼저, 그다음 고객
        addressRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // role=CUSTOMER 모의 JWT — 컨트롤러는 subject(email)로 고객을 식별한다
    private RequestPostProcessor customerJwt(String email) {
        return jwt().jwt(j -> j.subject(email))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private Customer customer(String email) {
        return customerRepository.save(new Customer(email, "encoded-password"));
    }

    // 표준 배송지 JSON 본문 — label/isDefault만 가변, 나머지는 고정값
    private String addressJson(String label, boolean isDefault) {
        return """
                {"label":"%s","recipientName":"홍길동","phone":"010-1234-5678","zipCode":"12345","address1":"서울시 강남구","address2":"101동 202호","isDefault":%b}
                """.formatted(label, isDefault);
    }

    // 등록 후 생성된 배송지 id 반환 — set-default/delete/update/소유권 테스트에서 사용
    private long register(String email, String label, boolean isDefault) throws Exception {
        String body = mockMvc.perform(post("/api/store/addresses").with(customerJwt(email))
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson(label, isDefault)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void 첫_배송지를_등록하면_201과_자동_기본배송지() throws Exception {
        customer("user@example.com");

        // isDefault=false로 보내도 첫 주소이므로 기본배송지가 된다
        mockMvc.perform(post("/api/store/addresses").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("집", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.label").value("집"))
                .andExpect(jsonPath("$.recipientName").value("홍길동"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: FAIL — `POST /api/store/addresses` 핸들러가 없어 201이 아닌 404 응답(`Status expected:<201> but was:<404>`).

- [ ] **Step 4: DTO·서비스·컨트롤러·보안 설정 구현**

`backend/src/main/java/com/ecommerce/address/dto/CreateAddressRequest.java`:

```java
package com.ecommerce.address.dto;

import jakarta.validation.constraints.NotBlank;

// 배송지 등록 요청 — address2(상세주소)만 선택 입력
public record CreateAddressRequest(
        @NotBlank String label,
        @NotBlank String recipientName,
        @NotBlank String phone,
        @NotBlank String zipCode,
        @NotBlank String address1,
        String address2,
        boolean isDefault) {
}
```

`backend/src/main/java/com/ecommerce/address/dto/AddressResponse.java`:

```java
package com.ecommerce.address.dto;

import com.ecommerce.address.CustomerAddress;

import java.time.LocalDateTime;

public record AddressResponse(
        Long id,
        String label,
        String recipientName,
        String phone,
        String zipCode,
        String address1,
        String address2,
        boolean isDefault,
        LocalDateTime createdAt) {

    public static AddressResponse from(CustomerAddress a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getRecipientName(),
                a.getPhone(), a.getZipCode(), a.getAddress1(), a.getAddress2(),
                a.isDefault(), a.getCreatedAt());
    }
}
```

`backend/src/main/java/com/ecommerce/address/CustomerAddressService.java`:

```java
package com.ecommerce.address;

import com.ecommerce.address.dto.AddressResponse;
import com.ecommerce.address.dto.CreateAddressRequest;
import com.ecommerce.common.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 배송지 주소록 — 기본배송지 불변식(항상 0/1개)·상한(10개)·소유권을 책임진다.
@Service
@Transactional(readOnly = true)
public class CustomerAddressService {

    // 고객당 배송지 상한
    static final int MAX_ADDRESSES = 10;

    private final CustomerAddressRepository repository;

    public CustomerAddressService(CustomerAddressRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AddressResponse create(Long customerId, CreateAddressRequest req) {
        long count = repository.countByCustomerId(customerId);
        if (count >= MAX_ADDRESSES) {
            throw new BadRequestException("배송지는 최대 " + MAX_ADDRESSES + "개까지 등록할 수 있습니다.");
        }
        // 첫 주소이거나 명시적으로 기본 요청 시 기본배송지로 — 기존 기본은 해제
        boolean makeDefault = req.isDefault() || count == 0;
        if (makeDefault) {
            clearDefault(customerId);
        }
        CustomerAddress saved = repository.save(new CustomerAddress(
                customerId, req.label(), req.recipientName(), req.phone(),
                req.zipCode(), req.address1(), req.address2(), makeDefault));
        return AddressResponse.from(saved);
    }

    // 현재 기본배송지가 있으면 해제(dirty checking으로 flush). 불변식 유지의 핵심 헬퍼.
    private void clearDefault(Long customerId) {
        repository.findByCustomerIdAndIsDefaultTrue(customerId)
                .ifPresent(a -> a.markDefault(false));
    }
}
```

`backend/src/main/java/com/ecommerce/address/CustomerAddressController.java`:

```java
package com.ecommerce.address;

import com.ecommerce.address.dto.AddressResponse;
import com.ecommerce.address.dto.CreateAddressRequest;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.common.UnauthorizedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

// 배송지 주소록 API — /api/store/addresses/**는 SecurityConfig에서 hasRole('CUSTOMER')로 보호.
// 고객 식별: 고객 access JWT의 subject(email) → Customer 조회 (OrderController와 동일 패턴).
@RestController
@RequestMapping("/api/store/addresses")
public class CustomerAddressController {

    private final CustomerAddressService service;
    private final CustomerRepository customerRepository;

    public CustomerAddressController(CustomerAddressService service,
                                     CustomerRepository customerRepository) {
        this.service = service;
        this.customerRepository = customerRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@AuthenticationPrincipal Jwt jwt,
                                  @RequestBody @Valid CreateAddressRequest req) {
        return service.create(customerId(jwt), req);
    }

    // 토큰은 유효하지만 고객 행이 없는 경우(탈퇴 등) 401
    private Long customerId(Jwt jwt) {
        return customerRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("고객 정보를 찾을 수 없습니다."))
                .getId();
    }
}
```

`backend/src/main/java/com/ecommerce/common/SecurityConfig.java` — 주문 매처 아래에 배송지 매처 추가:

```java
                        // 주문은 고객 전용 (어드민 토큰 403)
                        .requestMatchers("/api/store/orders/**").hasRole("CUSTOMER")
                        // 배송지(주소록)는 고객 전용 (어드민 토큰 403)
                        .requestMatchers("/api/store/addresses/**").hasRole("CUSTOMER")
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: PASS (1개 테스트 통과).

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/com/ecommerce/address backend/src/main/java/com/ecommerce/common/SecurityConfig.java backend/src/test/java/com/ecommerce/address
git commit -m "feat: 배송지 등록 API(자동 기본배송지·고객 전용 인가)"
```

---

## Task 2: 배송지 목록 조회 (기본 먼저, 이후 최신순)

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressRepository.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressService.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressController.java`
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**Interfaces:**
- Consumes: Task 1의 `register(email, label, isDefault)`, `customerJwt`, `CustomerAddress`.
- Produces: `CustomerAddressService.getAddresses(Long) : List<AddressResponse>`, repo `findByCustomerIdOrderByIsDefaultDescCreatedAtDesc(Long) : List<CustomerAddress>`.

- [ ] **Step 1: 실패하는 테스트 작성** (테스트 클래스에 메서드 추가)

```java
    @Test
    void 목록은_기본배송지가_먼저_이후_최신순() throws Exception {
        customer("user@example.com");
        register("user@example.com", "집", false);      // 첫 등록 → 기본
        register("user@example.com", "회사", false);     // 둘째
        register("user@example.com", "부모님댁", false);  // 셋째(최신)

        // 기대 순서: 집(기본) → 부모님댁(최신) → 회사
        mockMvc.perform(get("/api/store/addresses").with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].label").value("집"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].label").value("부모님댁"))
                .andExpect(jsonPath("$[2].label").value("회사"));
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: FAIL — `GET /api/store/addresses` 핸들러가 없어 404.

- [ ] **Step 3: 구현 (repo·service·controller)**

`CustomerAddressRepository`에 메서드 추가:

```java
import java.util.List;

    // 목록: 기본배송지 먼저, 이후 최신순
    List<CustomerAddress> findByCustomerIdOrderByIsDefaultDescCreatedAtDesc(Long customerId);
```

`CustomerAddressService`에 메서드 추가:

```java
import com.ecommerce.address.dto.AddressResponse;
import java.util.List;

    public List<AddressResponse> getAddresses(Long customerId) {
        return repository.findByCustomerIdOrderByIsDefaultDescCreatedAtDesc(customerId)
                .stream().map(AddressResponse::from).toList();
    }
```

`CustomerAddressController`에 핸들러 추가:

```java
import java.util.List;

    @GetMapping
    public List<AddressResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.getAddresses(customerId(jwt));
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: PASS (2개 통과).

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ecommerce/address backend/src/test/java/com/ecommerce/address
git commit -m "feat: 배송지 목록 조회(기본 먼저·최신순 정렬)"
```

---

## Task 3: 기본배송지 지정 (기존 기본 자동 해제)

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressRepository.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressService.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressController.java`
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `register`, Task 1 `clearDefault`(private).
- Produces: `CustomerAddressService.setDefault(Long customerId, Long id) : AddressResponse`, repo `findByIdAndCustomerId(Long id, Long customerId) : Optional<CustomerAddress>`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
    @Test
    void 다른_배송지를_기본으로_지정하면_기존_기본은_해제된다() throws Exception {
        customer("user@example.com");
        register("user@example.com", "집", false);              // 첫 등록 → 기본
        long office = register("user@example.com", "회사", false); // 둘째(비기본)

        mockMvc.perform(post("/api/store/addresses/" + office + "/default")
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("회사"))
                .andExpect(jsonPath("$.isDefault").value(true));

        // 목록: 회사(기본) 먼저, 집은 기본 해제됨 — 정확히 1개만 기본
        mockMvc.perform(get("/api/store/addresses").with(customerJwt("user@example.com")))
                .andExpect(jsonPath("$[0].label").value("회사"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].label").value("집"))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: FAIL — `POST /api/store/addresses/{id}/default` 핸들러 없어 404.

- [ ] **Step 3: 구현**

`CustomerAddressRepository`에 추가:

```java
    // 소유권 포함 단건 조회 — 모든 {id} 연산의 소유권 가드
    Optional<CustomerAddress> findByIdAndCustomerId(Long id, Long customerId);
```

`CustomerAddressService`에 추가:

```java
import com.ecommerce.common.NotFoundException;

    @Transactional
    public AddressResponse setDefault(Long customerId, Long id) {
        CustomerAddress target = repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("배송지를 찾을 수 없습니다."));
        clearDefault(customerId);     // 기존 기본 해제
        target.markDefault(true);     // 대상 기본 지정 — 한 트랜잭션 안에서 불변식 유지
        return AddressResponse.from(target);
    }
```

`CustomerAddressController`에 추가:

```java
    @PostMapping("/{id}/default")
    public AddressResponse setDefault(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.setDefault(customerId(jwt), id);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: PASS (3개 통과).

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ecommerce/address backend/src/test/java/com/ecommerce/address
git commit -m "feat: 기본배송지 지정 API(기존 기본 자동 해제)"
```

---

## Task 4: 배송지 수정 (기본여부 불변)

**Files:**
- Create: `backend/src/main/java/com/ecommerce/address/dto/UpdateAddressRequest.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressService.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressController.java`
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `register`, `CustomerAddress.update(...)`, Task 3 `findByIdAndCustomerId`.
- Produces: `CustomerAddressService.update(Long customerId, Long id, UpdateAddressRequest) : AddressResponse`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
    @Test
    void 배송지를_수정하면_필드가_갱신되고_기본여부는_불변() throws Exception {
        customer("user@example.com");
        long id = register("user@example.com", "집", false);  // 첫 등록 → 기본

        String updateBody = """
                {"label":"본가","recipientName":"김철수","phone":"010-9999-8888","zipCode":"54321","address1":"부산시 해운대구","address2":"302호"}
                """;
        mockMvc.perform(put("/api/store/addresses/" + id).with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("본가"))
                .andExpect(jsonPath("$.recipientName").value("김철수"))
                .andExpect(jsonPath("$.zipCode").value("54321"))
                .andExpect(jsonPath("$.isDefault").value(true));   // 수정해도 기본 유지
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: FAIL — `PUT /api/store/addresses/{id}` 핸들러 없어 404.

- [ ] **Step 3: 구현**

`backend/src/main/java/com/ecommerce/address/dto/UpdateAddressRequest.java` 생성 (isDefault 제외):

```java
package com.ecommerce.address.dto;

import jakarta.validation.constraints.NotBlank;

// 배송지 수정 요청 — 기본배송지 여부는 전용 엔드포인트로 일원화하므로 여기에 없다
public record UpdateAddressRequest(
        @NotBlank String label,
        @NotBlank String recipientName,
        @NotBlank String phone,
        @NotBlank String zipCode,
        @NotBlank String address1,
        String address2) {
}
```

`CustomerAddressService`에 추가:

```java
import com.ecommerce.address.dto.UpdateAddressRequest;

    @Transactional
    public AddressResponse update(Long customerId, Long id, UpdateAddressRequest req) {
        CustomerAddress target = repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("배송지를 찾을 수 없습니다."));
        target.update(req.label(), req.recipientName(), req.phone(),
                req.zipCode(), req.address1(), req.address2());
        return AddressResponse.from(target);
    }
```

`CustomerAddressController`에 추가:

```java
import com.ecommerce.address.dto.UpdateAddressRequest;

    @PutMapping("/{id}")
    public AddressResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                  @RequestBody @Valid UpdateAddressRequest req) {
        return service.update(customerId(jwt), id, req);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: PASS (4개 통과).

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ecommerce/address backend/src/test/java/com/ecommerce/address
git commit -m "feat: 배송지 수정 API(기본여부 불변·소유권 가드)"
```

---

## Task 5: 배송지 삭제 + 기본 자동 승격

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressRepository.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressService.java`
- Modify: `backend/src/main/java/com/ecommerce/address/CustomerAddressController.java`
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `register`, Task 3 `findByIdAndCustomerId`.
- Produces: `CustomerAddressService.delete(Long customerId, Long id) : void`, repo `findFirstByCustomerIdOrderByCreatedAtDesc(Long) : Optional<CustomerAddress>`.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
    @Test
    void 기본배송지를_삭제하면_남은_최신이_자동_기본승격() throws Exception {
        customer("user@example.com");
        long home = register("user@example.com", "집", false);  // 첫 등록 → 기본
        register("user@example.com", "회사", false);             // 둘째(최신, 비기본)

        mockMvc.perform(delete("/api/store/addresses/" + home).with(customerJwt("user@example.com")))
                .andExpect(status().isNoContent());

        // 남은 1개(회사)가 기본으로 승격
        mockMvc.perform(get("/api/store/addresses").with(customerJwt("user@example.com")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("회사"))
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: FAIL — `DELETE /api/store/addresses/{id}` 핸들러 없어 404.

- [ ] **Step 3: 구현**

`CustomerAddressRepository`에 추가:

```java
    // 삭제 후 기본 자동 승격 대상 — 남은 주소 중 최신
    Optional<CustomerAddress> findFirstByCustomerIdOrderByCreatedAtDesc(Long customerId);
```

`CustomerAddressService`에 추가:

```java
    @Transactional
    public void delete(Long customerId, Long id) {
        CustomerAddress target = repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("배송지를 찾을 수 없습니다."));
        boolean wasDefault = target.isDefault();
        repository.delete(target);
        if (wasDefault) {
            // 삭제 행을 제외하고 조회하려면 먼저 flush — 그 후 남은 최신을 기본으로 승격
            repository.flush();
            repository.findFirstByCustomerIdOrderByCreatedAtDesc(customerId)
                    .ifPresent(a -> a.markDefault(true));
        }
    }
```

`CustomerAddressController`에 추가:

```java
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.delete(customerId(jwt), id);
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: PASS (5개 통과).

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/ecommerce/address backend/src/test/java/com/ecommerce/address
git commit -m "feat: 배송지 삭제 API(기본 삭제 시 최신 자동 승격)"
```

---

## Task 6: 등록 상한 10개

**Files:**
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `create`(상한 로직 이미 구현됨), `register`, `addressJson`.
- Produces: 없음(이미 구현된 상한 동작을 검증).

> Task 1의 `create`가 이미 `count >= MAX_ADDRESSES` 검사를 포함한다. 이 태스크는 그 동작을 고정하는 회귀 테스트만 추가한다.

- [ ] **Step 1: 실패 가능성 확인용 테스트 작성**

```java
    @Test
    void 열한번째_배송지_등록은_400() throws Exception {
        customer("user@example.com");
        for (int i = 1; i <= 10; i++) {
            register("user@example.com", "주소" + i, false);
        }

        mockMvc.perform(post("/api/store/addresses").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("주소11", false)))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: 테스트 실행**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: PASS (6개 통과). 상한이 이미 구현돼 있으므로 바로 green이어야 한다. 만약 FAIL이면 Task 1의 `create` 상한 검사를 점검한다.

- [ ] **Step 3: 커밋**

```bash
git add backend/src/test/java/com/ecommerce/address
git commit -m "test: 배송지 등록 상한 10개 회귀 테스트"
```

---

## Task 7: 소유권 격리·미인증·유효성 (보안/검증 회귀)

**Files:**
- Test: `backend/src/test/java/com/ecommerce/address/CustomerAddressControllerTest.java`

**Interfaces:**
- Consumes: 모든 엔드포인트(이미 구현됨), `register`, `customerJwt`, `addressJson`.
- Produces: 없음(소유권 404·미인증 401·유효성 400 동작 검증).

> 소유권 404는 각 서비스 메서드의 `findByIdAndCustomerId`로 이미 강제된다. 미인증 401은 Task 1의 SecurityConfig 매처로, 유효성 400은 `@Valid` + `@NotBlank`로 보장된다. 이 태스크는 세 보안/검증 불변식을 회귀 테스트로 고정한다.

- [ ] **Step 1: 실패 가능성 확인용 테스트 3종 작성**

```java
    @Test
    void 타인의_배송지에_접근하면_404() throws Exception {
        customer("owner@example.com");
        customer("attacker@example.com");
        long id = register("owner@example.com", "집", false);

        String body = """
                {"label":"탈취","recipientName":"공격자","phone":"010-0000-0000","zipCode":"00000","address1":"어딘가","address2":""}
                """;
        // 수정 시도
        mockMvc.perform(put("/api/store/addresses/" + id).with(customerJwt("attacker@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        // 삭제 시도
        mockMvc.perform(delete("/api/store/addresses/" + id).with(customerJwt("attacker@example.com")))
                .andExpect(status().isNotFound());
        // 기본지정 시도
        mockMvc.perform(post("/api/store/addresses/" + id + "/default")
                        .with(customerJwt("attacker@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 미인증_요청은_401() throws Exception {
        mockMvc.perform(get("/api/store/addresses"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 필수_필드가_비면_400() throws Exception {
        customer("user@example.com");
        String invalid = """
                {"label":"","recipientName":"","phone":"","zipCode":"","address1":"","address2":""}
                """;
        mockMvc.perform(post("/api/store/addresses").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: 테스트 실행**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.address.CustomerAddressControllerTest"`
Expected: PASS (9개 통과). 모두 이미 구현된 동작이라 green이어야 한다. FAIL 시 해당 가드(`findByIdAndCustomerId`/SecurityConfig 매처/`@NotBlank`)를 점검한다.

- [ ] **Step 3: 백엔드 전체 테스트 회귀 확인**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL (기존 테스트 포함 전체 통과 — 배송지 추가가 기존 기능을 깨지 않음 확인).

- [ ] **Step 4: 커밋**

```bash
git add backend/src/test/java/com/ecommerce/address
git commit -m "test: 배송지 소유권 격리·미인증·유효성 회귀 테스트"
```

---

## Task 8: 프론트엔드 API 래퍼 추가

**Files:**
- Modify: `frontend/src/lib/api.ts`

**Interfaces:**
- Consumes: 기존 `getJson<T>(path, token)`, `sendJson<T>(path, method, token, body)`.
- Produces: `Address`/`AddressInput` 타입, `getAddresses`/`createAddress`/`updateAddress`/`deleteAddress`/`setDefaultAddress` 함수.

- [ ] **Step 1: 타입과 함수 추가**

`frontend/src/lib/api.ts` — 기존 `Order` 인터페이스 근처(공유 타입 영역)에 타입 추가:

```typescript
export interface Address {
  id: number;
  label: string;
  recipientName: string;
  phone: string;
  zipCode: string;
  address1: string;
  address2: string | null;
  isDefault: boolean;
  createdAt: string;
}

export interface AddressInput {
  label: string;
  recipientName: string;
  phone: string;
  zipCode: string;
  address1: string;
  address2: string;
  isDefault?: boolean;
}
```

파일 하단 "고객 Bearer 토큰 필요" 함수 영역(주문 함수들 아래)에 API 함수 추가:

```typescript
// 배송지 주소록 (고객 Bearer 토큰 필요)
export const getAddresses = (token: string) =>
  getJson<Address[]>("/api/store/addresses", token);
export const createAddress = (token: string, body: AddressInput) =>
  sendJson<Address>("/api/store/addresses", "POST", token, body);
export const updateAddress = (
  token: string,
  id: number,
  body: Omit<AddressInput, "isDefault">,
) => sendJson<Address>(`/api/store/addresses/${id}`, "PUT", token, body);
export const deleteAddress = (token: string, id: number) =>
  sendJson<void>(`/api/store/addresses/${id}`, "DELETE", token);
export const setDefaultAddress = (token: string, id: number) =>
  sendJson<Address>(`/api/store/addresses/${id}/default`, "POST", token);
```

- [ ] **Step 2: 타입체크·린트 확인**

Run: `cd frontend && npm run lint && npx tsc --noEmit`
Expected: 에러 없음(exit 0). `sendJson`/`getJson` 시그니처와 일치하는지 확인.

- [ ] **Step 3: 커밋**

```bash
git add frontend/src/lib/api.ts
git commit -m "feat: 프론트 배송지 API 래퍼 추가(Address 타입·CRUD 5종)"
```

---

## Task 9: 배송지 관리 페이지 + Server Action

> **선행 필수:** 이 프로젝트의 Next.js는 비표준이다(`frontend/AGENTS.md`). 코드 작성 전에 ① `frontend/src/app/orders/page.tsx`와 `frontend/src/app/orders/actions.ts`를 읽어 쿠키 처리·401 리다이렉트·Server Action 패턴을 그대로 따르고, ② 변경하는 API(`cookies()`, `redirect()`, Server Action, `revalidatePath`)는 `node_modules/next/dist/docs/` 해당 문서로 시그니처를 확인한다. 아래 코드는 `/orders` 패턴 기준 골격이며, 실제 쿠키 상수명·헬퍼는 기존 파일과 일치시킨다.

**Files:**
- Create: `frontend/src/app/addresses/page.tsx`
- Create: `frontend/src/app/addresses/actions.ts`

**Interfaces:**
- Consumes: Task 8의 `getAddresses`/`createAddress`/`updateAddress`/`deleteAddress`/`setDefaultAddress`, `Address` 타입; 기존 `auth-cookies`의 `CUSTOMER_ACCESS_COOKIE`, `ApiError`.

- [ ] **Step 1: 기존 주문 페이지 패턴 확인**

Run: `cat frontend/src/app/orders/page.tsx frontend/src/app/orders/actions.ts`
목적: 쿠키 읽기(`cookies()` await), 토큰 없을 때 `redirect("/login")`, `ApiError 401` 시 `redirect("/refresh?next=...")`, Server Action의 `"use server"`·`revalidatePath`/`redirect` 사용법을 그대로 모사하기 위함.

- [ ] **Step 2: 목록 페이지 작성**

`frontend/src/app/addresses/page.tsx` (Server Component, `/orders/page.tsx` 패턴 기준):

```tsx
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";
import { getAddresses, ApiError, type Address } from "@/lib/api";
import {
  createAddressAction,
  updateAddressAction,
  deleteAddressAction,
  setDefaultAddressAction,
} from "./actions";

export default async function AddressesPage() {
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }

  let addresses: Address[];
  try {
    addresses = await getAddresses(token);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      redirect("/refresh?next=/addresses");
    }
    throw err;
  }

  return (
    <main>
      <h1>배송지 관리</h1>

      <ul>
        {addresses.map((a) => (
          <li key={a.id}>
            <span>
              {a.label} {a.isDefault && <strong>[기본배송지]</strong>}
            </span>
            <div>
              {a.recipientName} · {a.phone}
            </div>
            <div>
              ({a.zipCode}) {a.address1} {a.address2 ?? ""}
            </div>
            {!a.isDefault && (
              <form action={setDefaultAddressAction}>
                <input type="hidden" name="id" value={a.id} />
                <button type="submit">기본배송지로 지정</button>
              </form>
            )}
            <form action={deleteAddressAction}>
              <input type="hidden" name="id" value={a.id} />
              <button type="submit">삭제</button>
            </form>
          </li>
        ))}
      </ul>

      <h2>새 배송지 등록</h2>
      <form action={createAddressAction}>
        <input name="label" placeholder="별칭(집/회사)" required />
        <input name="recipientName" placeholder="수령인" required />
        <input name="phone" placeholder="전화번호" required />
        <input name="zipCode" placeholder="우편번호" required />
        <input name="address1" placeholder="기본주소" required />
        <input name="address2" placeholder="상세주소" />
        <label>
          <input type="checkbox" name="isDefault" value="true" /> 기본배송지로 설정
        </label>
        <button type="submit">등록</button>
      </form>
    </main>
  );
}
```

- [ ] **Step 3: Server Action 작성**

`frontend/src/app/addresses/actions.ts` (`/orders/actions.ts` 패턴 기준 — 쿠키 상수·`revalidatePath` 사용법은 기존 파일과 일치시킬 것):

```ts
"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { CUSTOMER_ACCESS_COOKIE } from "@/lib/auth-cookies";
import {
  createAddress,
  updateAddress,
  deleteAddress,
  setDefaultAddress,
  type AddressInput,
} from "@/lib/api";

async function requireToken(): Promise<string> {
  const token = (await cookies()).get(CUSTOMER_ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/login");
  }
  return token;
}

function readInput(formData: FormData): AddressInput {
  return {
    label: String(formData.get("label") ?? ""),
    recipientName: String(formData.get("recipientName") ?? ""),
    phone: String(formData.get("phone") ?? ""),
    zipCode: String(formData.get("zipCode") ?? ""),
    address1: String(formData.get("address1") ?? ""),
    address2: String(formData.get("address2") ?? ""),
    isDefault: formData.get("isDefault") === "true",
  };
}

export async function createAddressAction(formData: FormData) {
  const token = await requireToken();
  await createAddress(token, readInput(formData));
  revalidatePath("/addresses");
}

export async function updateAddressAction(formData: FormData) {
  const token = await requireToken();
  const id = Number(formData.get("id"));
  const { isDefault, ...fields } = readInput(formData);
  void isDefault; // 수정에서는 기본여부를 바꾸지 않는다(전용 액션 사용)
  await updateAddress(token, id, fields);
  revalidatePath("/addresses");
}

export async function deleteAddressAction(formData: FormData) {
  const token = await requireToken();
  await deleteAddress(token, Number(formData.get("id")));
  revalidatePath("/addresses");
}

export async function setDefaultAddressAction(formData: FormData) {
  const token = await requireToken();
  await setDefaultAddress(token, Number(formData.get("id")));
  revalidatePath("/addresses");
}
```

- [ ] **Step 4: 빌드·린트 확인**

Run: `cd frontend && npm run lint && npm run build`
Expected: 성공(exit 0). 빌드 에러 시 `node_modules/next/dist/docs/`에서 `cookies`/`revalidatePath`/Server Action 시그니처를 재확인하고 기존 `/orders` 파일과 대조해 수정.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/app/addresses
git commit -m "feat: 배송지 관리 페이지·Server Action 추가"
```

---

## Task 10: 문서 갱신

**Files:**
- Modify: `README.md`(기능/보안 섹션에 배송지 관리 추가), `docs/ROADMAP.md`(또는 해당 로드맵 문서) 사이클 14 반영.

**Interfaces:** 없음.

- [ ] **Step 1: README·ROADMAP 갱신**

배송지 관리 기능(주소록 CRUD, 고객당 10개 상한, 기본배송지 불변식, 고객 전용 인가)을 README 기능 목록에 추가하고, ROADMAP에 "사이클 14: 배송지 관리" 완료로 기록한다. (기존 문서의 어조·구조를 그대로 따른다.)

- [ ] **Step 2: 커밋**

```bash
git add README.md docs/ROADMAP.md
git commit -m "docs: 배송지 관리(주소록 CRUD) 사이클 14 반영"
```

---

## 완료 기준

- `cd backend && ./gradlew test` → BUILD SUCCESSFUL (배송지 9개 + 기존 전체 통과).
- `cd frontend && npm run lint && npm run build` → exit 0.
- API 5종 동작: 등록(자동 기본)·목록(기본 먼저·최신순)·수정(기본 불변)·삭제(최신 자동 승격)·기본지정(기존 해제).
- 불변식: 기본배송지 항상 0/1개, 소유권 404, 상한 10개, 미인증 401, 유효성 400.
- main으로 PR 머지(사용자 직접 — GitHub PR 워크플로 선호).
