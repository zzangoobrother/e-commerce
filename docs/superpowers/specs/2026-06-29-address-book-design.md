# 배송지 관리(주소록 CRUD) 설계 (사이클 14)

> 마지막 갱신: 2026-06-29

## 배경

고객이 여러 배송지를 저장·관리하는 주소록(address book) CRUD를 신규로 추가한다. 현재 코드에는 주소 개념이 전혀 없다 — `Order` 엔티티에 배송 주소 필드가 없고, `Address` 값 객체도 존재하지 않는다.

이번 사이클은 **주소록 CRUD만** 범위로 한다. 주문(Order) 생성 시 저장된 배송지를 사용해 주소 스냅샷을 남기는 "주문 연동"은 별도 사이클(15)로 분리한다. 두 관심사가 독립적이고, 각 스펙을 작고 검증 가능하게 유지하기 위함이다(YAGNI).

## 전역 제약

- **언어:** 코드 주석·커밋 메시지·문서는 한국어, 식별자는 영어(프로젝트 규약).
- **새 인프라 없음:** Redis 등 도입 금지. 기존 `GlobalExceptionHandler`·커스텀 예외·DTO 검증·인증 패턴을 재사용한다.
- **스키마:** Flyway/Liquibase 미사용. `ddl-auto`가 `customer_addresses` 테이블을 자동 생성한다(운영 `update`, 테스트/로컬 `create-drop`).
- **인증:** OrderController와 동일하게 `@AuthenticationPrincipal Jwt jwt` → `jwt.getSubject()`(email) → `customerRepository.findByEmail()` → `customerId`로 식별.
- **연관 매핑:** Customer와의 관계는 `@ManyToOne` 객체가 아닌 **스칼라 `customerId: Long`**(Order·OrderItem 패턴 동일). FK 객체 로딩을 피하고 경계를 느슨하게 둔다.

---

## 도메인 모델

### 엔티티 `CustomerAddress` (신규 패키지 `com.ecommerce.address`)

| 필드 | 타입 | 제약 |
|------|------|------|
| `id` | Long | PK, `@GeneratedValue(IDENTITY)` |
| `customerId` | Long | `@Column(nullable=false)`, 스칼라 참조 |
| `label` | String | 별칭(집/회사 등), `@NotBlank` |
| `recipientName` | String | 수령인, `@NotBlank` |
| `phone` | String | 전화번호, `@NotBlank` |
| `zipCode` | String | 우편번호, `@NotBlank` |
| `address1` | String | 기본주소, `@NotBlank` |
| `address2` | String | 상세주소, nullable 허용 |
| `isDefault` | boolean | 기본배송지 여부 |
| `createdAt` | LocalDateTime | `@PrePersist`로 생성 시점 설정 |

> DTO의 검증 어노테이션이 1차 방어선이며, 엔티티 `@Column` 제약은 DB 무결성 보강이다.

### Repository `CustomerAddressRepository extends JpaRepository<CustomerAddress, Long>`

- `List<CustomerAddress> findByCustomerIdOrderByIsDefaultDescCreatedAtDesc(Long customerId)` — 목록(기본 먼저, 이후 최신순).
- `long countByCustomerId(Long customerId)` — 상한 검사.
- `Optional<CustomerAddress> findByIdAndCustomerId(Long id, Long customerId)` — 소유권 포함 단건 조회.
- `Optional<CustomerAddress> findFirstByCustomerIdOrderByCreatedAtDesc(Long customerId)` — 삭제 후 자동 승격 대상.

단순 CRUD라 커스텀 `@Query`는 사용하지 않는다.

---

## API

`CustomerAddressController`, `@RequestMapping("/api/store/addresses")`, 모든 엔드포인트 인증 필요(`ROLE_CUSTOMER`).

| 메서드 | 경로 | 동작 | 성공 상태 |
|--------|------|------|-----------|
| POST | `/` | 배송지 등록 | 201 Created |
| GET | `/` | 배송지 목록(기본 먼저, 이후 최신순) | 200 OK |
| PUT | `/{id}` | 필드 수정(기본여부 제외) | 200 OK |
| DELETE | `/{id}` | 배송지 삭제 | 204 No Content |
| POST | `/{id}/default` | 기본배송지 지정 | 200 OK |

### DTO (record, 기존 컨벤션)

```java
public record CreateAddressRequest(
    @NotBlank String label,
    @NotBlank String recipientName,
    @NotBlank String phone,
    @NotBlank String zipCode,
    @NotBlank String address1,
    String address2,
    boolean isDefault) {}

public record UpdateAddressRequest(   // isDefault 제외 — 기본 변경은 전용 엔드포인트로 일원화
    @NotBlank String label,
    @NotBlank String recipientName,
    @NotBlank String phone,
    @NotBlank String zipCode,
    @NotBlank String address1,
    String address2) {}

public record AddressResponse(
    Long id, String label, String recipientName, String phone,
    String zipCode, String address1, String address2,
    boolean isDefault, LocalDateTime createdAt) {}
```

---

## Service 규칙 (`CustomerAddressService`, `@Transactional`)

기본 `@Transactional(readOnly = true)`, 쓰기 메서드만 `@Transactional`.

### 등록 `create(customerId, CreateAddressRequest)`
1. `countByCustomerId` 확인 → **10개 초과면 `BadRequestException`(400)**.
2. 첫 주소(`count == 0`)이거나 `isDefault == true`이면, 기존 기본을 모두 해제하고 이 주소를 기본으로 설정.
3. 저장 후 `AddressResponse` 반환.

### 목록 `getAddresses(customerId)`
- `findByCustomerIdOrderByIsDefaultDescCreatedAtDesc` 결과를 응답으로 매핑.

### 수정 `update(customerId, id, UpdateAddressRequest)`
1. `findByIdAndCustomerId` → 없으면 `NotFoundException`(404).
2. 필드만 갱신(기본여부 불변).

### 기본 지정 `setDefault(customerId, id)`
1. `findByIdAndCustomerId` → 없으면 `NotFoundException`(404).
2. 해당 고객의 기존 기본 주소 해제 → 대상 주소 `isDefault = true`.

### 삭제 `delete(customerId, id)`
1. `findByIdAndCustomerId` → 없으면 `NotFoundException`(404).
2. 삭제.
3. 삭제한 주소가 기본이었으면, 남은 주소 중 **최신(`findFirstByCustomerIdOrderByCreatedAtDesc`)을 자동 승격**. 남은 주소가 0개면 기본 없음.

### 불변식
- **기본배송지는 항상 0개 또는 1개.** "기존 기본 해제 → 신규 지정"을 단일 트랜잭션 안에서 수행해 보장한다. 한 고객 주소록의 동시 수정 빈도가 낮아 별도 락은 두지 않는다.
- **소유권:** 모든 `{id}` 연산은 `findByIdAndCustomerId`로 소유권을 강제하며, 불일치/미존재 모두 **404**를 반환해 타인 자원의 존재 여부를 노출하지 않는다.

### 예외 매핑(기존 `GlobalExceptionHandler` 재사용)
| 상황 | 예외 | 상태 |
|------|------|------|
| 10개 초과 등록 | `BadRequestException` | 400 |
| 미존재/타인 주소 | `NotFoundException` | 404 |
| 필수 필드 누락 | (검증) `MethodArgumentNotValidException` | 400 |
| 미인증 | (SecurityConfig) | 401 |

---

## 프론트엔드 (Next.js App Router)

기존 `/orders` 패턴을 그대로 따른다.

### 페이지 `/addresses` (Server Component)
- `cookies()`에서 `customer_token` 읽기 → 없으면 `redirect("/login")`.
- `getAddresses(token)` 호출. `ApiError 401` → `redirect("/refresh?next=/addresses")`(기존 401 처리 패턴).
- 목록 렌더: 기본배송지 배지, 각 항목에 수정/삭제/기본지정 동작.

### 등록·수정 폼
- 기존 코드의 폼 + Server Action 혼용 패턴을 따라 **Server Action 방식**으로 통일(`addresses/actions.ts`).
- 등록·수정·삭제·기본지정을 Server Action으로 처리하고, 완료 후 `/addresses`로 복귀.

### `lib/api.ts` 추가
- `Address` 인터페이스 타입.
- 기존 `getJson`/`sendJson` 래퍼 재사용:
  - `getAddresses(token)` → `GET /api/store/addresses`
  - `createAddress(token, body)` → `POST /api/store/addresses`
  - `updateAddress(token, id, body)` → `PUT /api/store/addresses/{id}`
  - `deleteAddress(token, id)` → `DELETE /api/store/addresses/{id}`
  - `setDefaultAddress(token, id)` → `POST /api/store/addresses/{id}/default`

---

## 테스트 (TDD, 백엔드 우선)

`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`(H2), `jwt()` 헬퍼로 인증 구성.

> 기본배송지 불변식(시나리오 2·3)은 **여러 행의 상태 전이 + 트랜잭션 경계**가 핵심이므로 `@DataJpaTest`가 아닌 `@SpringBootTest`로 검증한다(이전 사이클 교훈: 커밋 경계가 중요한 부수효과는 실제 트랜잭션에서 검증).

핵심 시나리오:
1. 첫 등록 → 201, 자동 기본배송지(`isDefault=true`).
2. 둘째를 기본 지정 → 기존 기본 해제, 항상 정확히 1개만 기본.
3. 기본배송지 삭제 → 남은 최신이 자동 승격.
4. 11번째 등록 시도 → 400(상한).
5. **소유권 격리:** 다른 고객의 주소 id로 수정/삭제/기본지정 → 404.
6. 목록 정렬: 기본 먼저, 이후 최신순.
7. 유효성: 필수 필드 누락 → 400.
8. 미인증 요청 → 401.

`@AfterEach`에서 `customerAddressRepository.deleteAll()` → `customerRepository.deleteAll()` 순으로 정리(FK 역순).

---

## 범위 밖 (다음 사이클)

- **주문 연동(사이클 15 후보):** 주문 생성 시 저장된 배송지(또는 기본배송지)를 선택해 `Order`에 주소 스냅샷으로 저장. `CardPaymentRequest`·체크아웃 UI·`OrderResponse`에 주소 반영.
- 우편번호 검색 API(다음 주소 등) 연동 — 이번엔 값을 받아 저장까지만.
