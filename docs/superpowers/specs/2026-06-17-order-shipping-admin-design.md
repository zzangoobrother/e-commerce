# 주문 배송 상태 · 어드민 주문 관리 — 설계 문서

**날짜:** 2026-06-17
**상태:** 승인됨
**사이클:** 11 (주문 후속 — 후보 5의 1단계. 결제는 다음 사이클로 분리)

---

## 1. 목표와 범위

사이클 10에서 주문의 핵심(생성·부분 주문·취소·재고)이 완료됐다. 이번 사이클은 주문에 **배송 진행 상태**를 도입하고, 어드민이 **전체 주문을 조회·상태 전이**할 수 있는 화면/API를 추가한다. 이는 어드민의 첫 쓰기(상태 변경) UI다 — 기존 어드민 화면(상품·공급사)은 조회 전용이었다.

**핵심 결정 (브레인스토밍에서 사용자 확정):**
- **상태 집합 = `ORDERED → SHIPPING → DELIVERED` + `CANCELLED`.** 최소 의미 단위. 준비중(PREPARING) 같은 중간 단계는 두지 않는다(YAGNI).
- **상태 전이 규칙은 `Order` 엔티티에 집중.** 각 전이 메서드가 출발 상태를 자체 검증해 어떤 호출 경로(고객/어드민)로 와도 불법 전이가 차단된다.
- **취소는 `ORDERED`일 때만, 고객·어드민 둘 다 가능.** 재고 복원. `SHIPPING` 이후는 취소 불가.
- **어드민 상태 전이 = 명시적 액션 엔드포인트.** 제네릭 status PATCH 대신 `/ship`·`/deliver`·`/cancel`. 기존 `POST /cancel` 패턴과 일관, 부수효과(취소=재고복원)가 엔드포인트로 분명.
- **모든 전이는 Order 행을 PESSIMISTIC_WRITE로 잠근 뒤 수행.** 동시 전이(배송 시작 ↔ 취소) 경합 시 먼저 잠근 쪽이 이기고 두 번째는 엔티티 검증으로 막힌다.
- **어드민 서비스를 새로 만들지 않고 `OrderService` 확장.** ProductService가 admin+store를 겸하는 기존 관례를 따른다.

**범위 제외:**
- **결제·PG 연동** — 다음 사이클(후보 5의 2단계). 결제 상태(`PAID` 등)는 이번에 두지 않는다.
- **반품·교환·환불** — `DELIVERED` 이후 흐름. 별도 후보.
- 준비중(PREPARING) 중간 상태, 배송 추적번호·운송장, 부분 배송.
- 어드민 주문 검색(고객 이메일/주문번호) — 상태 필터까지만.
- 주문 상세 페이지 — 목록에 항목을 펼쳐 표시.

**전제:** 주문(사이클 10, PR #14~#17 main 머지됨), 어드민 인증(admin_token, `/api/admin/**` `hasRole('ADMIN')`, proxy `/admin/*` 보호). main에서 `feature/order-shipping` 분기.

---

## 2. 도메인 모델 — 상태 머신

기존 `orders`/`order_items` 스키마는 그대로다. `status` 컬럼의 값 집합만 확장된다.

```
enum OrderStatus: ORDERED | SHIPPING | DELIVERED | CANCELLED   ← SHIPPING, DELIVERED 추가
```

**합법 전이 (그 외 전부 BadRequestException):**

```
ORDERED ──ship()────▶ SHIPPING ──deliver()──▶ DELIVERED   (어드민)
   │
   └──cancel()──▶ CANCELLED   (고객 또는 어드민, 재고 복원)

DELIVERED, CANCELLED = 종료 상태 (전이 없음)
```

**`Order` 엔티티 전이 메서드 (불변식을 엔티티가 방어):**

```java
// 기존 cancel()을 "ORDERED만"으로 강화 — 기존: "CANCELLED면 예외"
public void cancel() {
    if (status != OrderStatus.ORDERED) {
        throw new BadRequestException("배송이 시작되었거나 이미 취소된 주문은 취소할 수 없습니다.");
    }
    this.status = OrderStatus.CANCELLED;
}

public void ship() {
    if (status != OrderStatus.ORDERED) {
        throw new BadRequestException("주문 완료 상태에서만 배송을 시작할 수 있습니다.");
    }
    this.status = OrderStatus.SHIPPING;
}

public void deliver() {
    if (status != OrderStatus.SHIPPING) {
        throw new BadRequestException("배송 중 상태에서만 배송 완료할 수 있습니다.");
    }
    this.status = OrderStatus.DELIVERED;
}
```

> **동작 변경 주의:** `cancel()` 강화로 `SHIPPING`/`DELIVERED` 주문 취소는 400이 된다. 기존 테스트는 전부 `ORDERED`를 취소하므로 그대로 통과한다.

---

## 3. 동시성 — 전이 시 Order 행 잠금

사이클 10에서 "비관적 락은 재고뿐 아니라 **상태 전이 중복 방지**에도 필요"라는 교훈을 얻었다(`cancelOrder`가 Order 행을 안 잠가 이중 취소로 재고 이중 복원되던 결함). 같은 원칙을 모든 전이에 적용한다.

- **고객 취소** — 기존 `findByIdAndCustomerIdForUpdate`(@Lock PESSIMISTIC_WRITE) 유지.
- **어드민 ship/deliver/cancel** — 신규 `findByIdForUpdate`(@Lock PESSIMISTIC_WRITE)로 Order 행을 잠근 뒤 전이.
- **취소(고객·어드민)** — Order 잠금 + 재고 복원을 위해 Product를 productId 오름차순 PESSIMISTIC_WRITE 잠금(기존 `findAllForUpdate` 재사용). ship/deliver는 재고 변동이 없어 Product 잠금 불필요.

경합 예: 어드민 배송 시작 ↔ 고객 취소가 동시에 들어오면, 먼저 Order 행을 잠근 트랜잭션이 전이를 확정하고, 두 번째는 갱신된 상태(`SHIPPING` 또는 `CANCELLED`)를 읽어 엔티티 메서드가 예외를 던진다 → 400. 한쪽만 성공해 정합성이 유지된다.

---

## 4. API

**고객 (기존 `OrderController`, `/api/store/orders`) — 변경 없음.** `OrderResponse.status`로 배송 상태가 자연히 흘러나간다.

**어드민 (신규 `AdminOrderController`, `/api/admin/orders`):**

| 메서드 | 경로 | 동작 | 응답 |
|--------|------|------|------|
| GET | `/api/admin/orders?status=ORDERED` | 전체 주문 목록(상태 필터 옵션, 최신순) | `200` `List<AdminOrderResponse>` |
| POST | `/api/admin/orders/{id}/ship` | ORDERED → SHIPPING | `200` `AdminOrderResponse` |
| POST | `/api/admin/orders/{id}/deliver` | SHIPPING → DELIVERED | `200` `AdminOrderResponse` |
| POST | `/api/admin/orders/{id}/cancel` | ORDERED → CANCELLED (재고 복원) | `200` `AdminOrderResponse` |

- `status` 파라미터 부재 시 전체 조회, 있으면 해당 상태만.
- 없는 주문 id → `404`(NotFoundException). 불법 전이 → `400`. 고객 토큰 → `403`. 무토큰 → `401`.

**`OrderService` 확장 메서드:**
- `getAllOrders(OrderStatus statusFilter)` — `statusFilter == null`이면 전체, 아니면 필터. 항목은 @EntityGraph로 로딩. 고객 이메일은 `CustomerRepository.findAllById(customerIds)`로 배치 조회해 enrich(N+1 회피).
- `shipOrder(Long orderId)` / `deliverOrder(Long orderId)` — `findByIdForUpdate` → `order.ship()`/`deliver()`.
- `cancelOrderByAdmin(Long orderId)` — `findByIdForUpdate` → `order.cancel()` → Product 잠금 후 재고 복원. 고객 `cancelOrder`의 재고 복원 로직과 동일하므로 패키지 내 공유 가능(둘 다 `com.ecommerce.order`).

**리포지토리 추가 (`OrderRepository`):**
- `findByIdForUpdate(Long id)` — `@Lock(PESSIMISTIC_WRITE)`.
- `findAllByOrderByIdDesc()` · `findAllByStatusOrderByIdDesc(OrderStatus status)` — 둘 다 `@EntityGraph(attributePaths = "items")`.

**DTO (신규 `AdminOrderResponse`):**
```
AdminOrderResponse(Long id, String customerEmail, OrderStatus status,
                   BigDecimal totalPrice, LocalDateTime createdAt,
                   List<OrderItemResponse> items)   ← OrderItemResponse 재사용
```

**보안 (`SecurityConfig`):** **변경 없음.** 기존 `.requestMatchers("/api/admin/**").hasRole("ADMIN")`(SecurityConfig:56)가 `/api/admin/orders/**`를 이미 포함한다 — 새 매처 불필요. 고객 토큰 403·무토큰 401은 기존 설정으로 자동 보장.

---

## 5. 프론트엔드

**어드민 주문 관리 (신규)**
- `app/admin/orders/page.tsx` — 전체 주문 목록(고객 이메일·항목·상태·합계·일시). 상태 필터는 `?status=` 쿼리 탭(전체/주문완료/배송중/배송완료/취소). 각 주문에 **현재 상태에 맞는 전이 버튼만** 노출: `ORDERED` → [배송 시작][취소], `SHIPPING` → [배송 완료], `DELIVERED`/`CANCELLED` → 버튼 없음. `/admin/products` 조회 패턴 + 폼 추가.
- `app/admin/orders/actions.ts` — `shipOrderAction`·`deliverOrderAction`·`cancelOrderAction`. 패턴: admin_token 쿠키 → 401이면 `/admin/refresh?next=/admin/orders` → 실패는 `?error=` → `revalidatePath` + `redirect`. **redirect는 try 블록 밖**(사이클 9·10 관례 — redirect는 예외를 던짐).
- `app/admin/page.tsx` — 대시보드에 "주문 관리" 링크 추가.

**고객 측 (최소 변경)**
- `app/orders/page.tsx` — `STATUS_LABEL`에 `SHIPPING: "배송중"`, `DELIVERED: "배송완료"` 추가. 취소 버튼은 이미 `status === "ORDERED"` 조건이라 자동으로 올바르게 동작.
- `lib/api.ts` — `AdminOrder` 타입(+`customerEmail`) · 어드민 함수 4개(`getAdminOrders(token, status?)`·`shipOrder`·`deliverOrder`·`adminCancelOrder`).

**proxy — 변경 없음.** `/admin/*`는 이미 matcher(`/admin/:path*`)와 guard로 보호되고, `guard`의 non-GET 통과 분기(사이클 9 교훈)가 공유되어 어드민 Server Action POST도 가로채지지 않는다. **이 분기를 제거하지 말 것.**

---

## 6. 테스트

**백엔드 — 신규 `AdminOrderControllerTest` (@SpringBootTest + MockMvc, 프로젝트 관례):**
- 전체 주문 목록 조회(다중 고객, `customerEmail` 포함)
- 상태 필터(`?status=ORDERED` → 해당 상태만)
- 배송 시작(ORDERED → SHIPPING) 200·상태 반영
- 배송 완료(SHIPPING → DELIVERED) 200·상태 반영
- 불법 전이 400: ORDERED에서 배송 완료, DELIVERED에서 재배송, SHIPPING 주문 취소
- 어드민 취소(ORDERED → CANCELLED) 200·재고 복원
- 없는 주문 전이 → 404
- 인증: 고객 토큰 403, 무토큰 401

**백엔드 — 기존 `OrderControllerTest` 보강:**
- 배송 시작된 주문을 고객이 취소 → 400 (cancel "ORDERED만" 강화 검증). 기존 케이스는 그대로 통과.

**프론트:** `npm run build && npm run lint` — `/admin/orders` 라우트 등록, 타입 에러 없음.

---

## 7. 문서

- **README** — 주문 기능에 배송 상태(주문완료→배송중→배송완료) · 어드민 주문 관리 추가. API 표에 admin orders 4행. 상태값 설명.
- **ROADMAP** — 완료 사이클 표에 사이클 11 행 추가. 후보 5에서 배송·어드민 관리를 완료 처리하고 **결제만 새 후보로** 남긴다.

---

## 8. Definition of Done

- 백엔드 `./gradlew test` 전체 통과(AdminOrderControllerTest 신규 + OrderControllerTest 보강 + 기존 전부)
- 프론트 `npm run build && npm run lint` 통과(`/admin/orders` 라우트 등록)
- 상태 머신: ORDERED→SHIPPING→DELIVERED 전이, 불법 전이 400, 취소는 ORDERED만(재고 복원) — 테스트로 고정
- 동시 전이 경합 시 Order 행 잠금으로 한쪽만 성공 — 설계상 보장(잠금 코드 확인)
- 어드민 전용 보호: 고객 토큰 403, 무토큰 401 — 테스트로 고정
- 어드민 화면에서 상태 필터·전이 버튼 동작, 현재 상태에 맞는 버튼만 노출
- README/ROADMAP 동기화, 결제는 다음 사이클 후보로 명시
