# 주문 도메인 — 설계 문서

**날짜:** 2026-06-12
**상태:** 승인됨
**사이클:** 10 (주문/장바구니 도메인의 2단계 — 사이클 9 장바구니에 이어 주문)

---

## 1. 목표와 범위

로그인한 고객이 장바구니 전체를 주문으로 전환하고(주문 시점에 재고 차감·가격 고정), 주문 목록을 조회하고, 주문을 취소(재고 복원)할 수 있게 한다. 사이클 9 인계 사항인 **어드민 상품 삭제 시 cart_items FK 위반(부정확한 409)** 문제를 상품 참조 전략 정리와 함께 해결한다.

**핵심 결정:**
- **결제 없음 — 주문 즉시 확정.** 주문 생성 = 재고 차감 + 가격 스냅샷 + 상태 `ORDERED`. 결제는 별도 사이클로 미룬다.
- **상태는 `ORDERED` → `CANCELLED` 단일 전이만.** 배송 상태(SHIPPED 등)는 어드민 주문 관리 사이클로 미룬다.
- **장바구니 전체 주문만.** 부분 선택·바로 구매 경로는 도입하지 않는다.
- **가능한 것만 주문(부분 주문).** 주문 시점에 판매중지·재고 부족 항목은 제외하고 나머지로 주문을 생성하며, 제외 항목은 사유와 함께 응답에 포함하고 장바구니에 남긴다. 전 항목 구매 불가면 400(주문 미생성).

**범위 제외:**
- 결제·PG 연동, 배송 상태 관리, 어드민 주문 조회/관리 UI
- 주문 상세 페이지(`/orders/[id]`) — 목록에 항목을 펼쳐 표시하므로 불필요
- 부분 선택 주문, 바로 구매

**전제:** 장바구니(사이클 9, PR #13 main 머지됨). 고객 보호 인프라(`/refresh` 라우트, proxy 고객 분기) 재사용 — 주문 페이지 보호는 matcher 확장만. main에서 `feature/order` 분기.

---

## 2. 도메인 모델 — Order 애그리거트 + 스냅샷

```
orders                                  ← "order"는 SQL 예약어라 테이블명 복수형
- id            PK
- customer_id   BIGINT NOT NULL         ← 스칼라 (CartItem과 동일 패턴)
- status        VARCHAR NOT NULL        ← enum OrderStatus: ORDERED | CANCELLED
- total_price   DECIMAL NOT NULL        ← 주문 시점 합계 스냅샷
- created_at    NOT NULL
- INDEX (customer_id)

order_items
- id            PK
- order_id      FK → orders, NOT NULL   ← @ManyToOne(LAZY), Order의 @OneToMany(cascade=ALL, orphanRemoval)
- product_id    BIGINT NOT NULL         ← 스칼라, FK 없음 (아래 참조 전략)
- product_name  VARCHAR NOT NULL        ← 스냅샷
- price         DECIMAL NOT NULL        ← 주문 시점 단가 스냅샷
- quantity      INT NOT NULL (≥1)
```

- `Order.cancel()` 도메인 메서드: 이미 `CANCELLED`면 `BadRequestException`.
- `lineTotal`은 저장하지 않고 DTO에서 `price × quantity` 계산(중복 저장 배제).

**장바구니(플랫)와 달리 Order는 애그리거트로 간다:** CartItem은 항목이 독립적으로 추가·삭제되므로 플랫이 맞았지만, OrderItem은 주문과 함께 생성되고 함께 조회되며 개별 수정이 없다. `Order`에 `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true) List<OrderItem>` — 항목 생명주기가 주문에 완전히 종속(저장·삭제 함께).

**상품 참조 전략 — 수명에 따라 분리:**
- **주문(영구 이력)**: `product_id`에 FK를 걸지 않는다. FK가 있으면 상품 삭제가 주문 이력에 영원히 막힌다. 스냅샷(이름·가격)이 있어 상품이 사라져도 주문 표시는 온전하다.
- **장바구니(임시 데이터)**: FK 유지 + 상품 삭제 시 `cart_items` 함께 삭제. 어드민 상품 삭제 흐름에 `cartItemRepository.deleteByProductId(productId)` 선행 — 사이클 9 인계 사항(부정확한 409) 해결.

**검토한 대안 (기각):**
- **OrderItem에 Product FK + 스냅샷 병행**: 이력 무결성에 FK가 기여하는 바 없이 상품 삭제만 막음 → 기각.
- **소프트 삭제(Product.deleted 플래그)로 FK 문제 회피**: 전 도메인 조회에 삭제 필터가 전파되는 큰 변경 → YAGNI 기각.

---

## 3. 동시성 — 비관적 락 (검토 후 채택)

주문 생성·취소 트랜잭션에서 대상 Product를 `PESSIMISTIC_WRITE`(`SELECT ... FOR UPDATE`)로 잠근다.

- **데드락 예방 규칙: 항상 productId 오름차순으로 잠금.** 모든 트랜잭션이 같은 순서로 잠그면 순환 대기가 생기지 않는다. 이 규칙은 코드 리뷰로 고정한다(동시성 자체는 테스트하지 않음 — §6).
- 검증(판매중·재고)→차감 사이에 다른 트랜잭션이 끼어들 수 없어, "가능한 것만 주문"의 항목별 판정과 자연스럽게 결합된다.

**검토한 대안 (기각):**
- **조건부 UPDATE 원자 차감**(`UPDATE ... SET stock = stock - ? WHERE stock >= ?`): 락 없이 원자적이지만, 벌크 쿼리가 영속성 컨텍스트를 우회하고 판매 상태 검증은 별도 SELECT가 필요하며 항목별 실패 회수 로직이 꼬임 → 기각.
- **낙관적 락(@Version + 재시도)**: Product 스키마 변경이 타 도메인에 전파되고 재시도 루프가 부분 주문 의미론과 결합 시 가장 복잡. 단일 인스턴스·저경합에서 이득 없음 → 기각.

---

## 4. 백엔드 — `com.ecommerce.order` 패키지 (신규)

### 4.1 컴포넌트

- **`Order`/`OrderItem`/`OrderStatus`** — §2의 엔티티·enum.
- **`OrderRepository`** — `findAllByCustomerIdOrderByIdDesc`(items·fetch join), `findByIdAndCustomerId`.
- **`ProductRepository` 보강** — `PESSIMISTIC_WRITE` 잠금 조회(`findAllByIdInOrderById` + `@Lock`).
- **`CartItemRepository` 보강** — `deleteByProductId`(상품 삭제 전파), 주문된 항목 일괄 삭제.
- **`OrderService`** — 주문 생성/목록/취소. 흐름은 §4.3.
- **`OrderController`** — `/api/store/orders`, 고객 식별은 JWT subject(email) → `CustomerRepository`(CartController와 동일 패턴).
- **어드민 상품 삭제 수정** — 기존 삭제 흐름(서비스/컨트롤러 구조는 플랜 작성 시 확인)에서 `cartItemRepository.deleteByProductId` 선행 호출.
- **DTO** — `OrderResponse(id, status, totalPrice, createdAt, items[])`, `OrderItemResponse(productId, productName, price, quantity, lineTotal)`, `CreateOrderResponse(order, excludedItems[{productId, productName, reason}])`.

### 4.2 API

| 메서드 | 경로 | 동작 | 응답 |
|--------|------|------|------|
| POST | `/api/store/orders` | 장바구니 전체 주문(가능한 것만) | 201 CreateOrderResponse |
| GET | `/api/store/orders` | 내 주문 목록(최신순, 항목 포함) | 200 OrderResponse[] |
| POST | `/api/store/orders/{orderId}/cancel` | 취소 + 재고 복원 | 200 OrderResponse |

- 취소가 DELETE가 아닌 이유: 주문은 삭제가 아니라 상태 전이(이력 보존).
- `excludedItems`는 201 응답의 일부 — 부분 성공은 실패가 아니므로 4xx로 표현하지 않는다.

### 4.3 주문 생성 트랜잭션 (단일 @Transactional)

1. 장바구니 로드(product fetch join) — 비었으면 400
2. 후보 productId **오름차순 정렬** 후 `PESSIMISTIC_WRITE` 일괄 잠금 로드
3. 항목별 판정: `ON_SALE` 아님 → 제외(판매중지) / `stockQuantity < quantity` → 제외(재고 부족) / 통과 → 재고 차감 + OrderItem 스냅샷 생성
4. 통과 0개 → `BadRequestException`(사유 요약) — 롤백이므로 차감 없음 보장
5. Order 저장(cascade), **주문된 항목만** CartItem 삭제(제외 항목 잔존)
6. 201: 주문 + 제외 목록

### 4.4 취소 트랜잭션

1. `findByIdAndCustomerId` — 없으면 404(타인 주문 존재 노출 방지)
2. `order.cancel()` — 중복 취소 400
3. 항목 productId 오름차순 정렬 → **존재하는 상품만** 잠금 로드 → `stockQuantity += quantity` 복원(삭제된 상품은 스킵)

### 4.5 검증·보안

- 새 예외 없음 — 기존 `NotFoundException`(404)·`BadRequestException`(400) 재사용.
- `SecurityConfig`에 한 줄 추가(cart 매처 아래): `.requestMatchers("/api/store/orders/**").hasRole("CUSTOMER")` — 비인증 401, 어드민 토큰 403.

---

## 5. 프론트 — 주문 UI (장바구니 패턴 복제)

- **`app/cart/page.tsx`**: 합계 아래 "주문하기" 버튼(POST 폼) — `cart/actions.ts`에 `createOrderAction` 추가. 성공 시 `redirect("/orders")`, 제외 항목 있으면 `/orders?notice=` 쿼리로 안내(장바구니 `?error=` 패턴 재사용). 401은 `/refresh?next=/cart` 경유. 검증 실패(빈 장바구니·전부 불가)는 `/cart?error=`.
- **`app/orders/page.tsx`** (신규): 서버 컴포넌트 — 주문 카드 목록(일시·상태·항목 스냅샷·합계), `ORDERED`면 취소 버튼. `?notice=` 표시. 401은 `/refresh?next=/orders`.
- **`app/orders/actions.ts`** (신규): `cancelOrderAction` — 실패 메시지는 `/orders?error=`.
- **`proxy.ts`**: 고객 보호 분기를 `/cart` 단일에서 `/cart`·`/orders`로 확장, matcher에 `/orders/:path*` 추가. **non-GET 통과 분기가 이미 있어 Server Action POST 405 함정 없음**(사이클 9 교훈 — 코드 주석 참조).
- **홈 헤더(`app/page.tsx`)**: 로그인 시 "주문 내역" 링크 추가 — `prefetch={false}`(사이클 9 교훈: 프리페치가 refresh 토큰 회전과 경합).
- **`lib/api.ts`**: `Order`/`OrderItem`/`CreateOrderResult`/`ExcludedItem` 타입 + `createOrder(token)`, `getOrders(token)`, `cancelOrder(token, orderId)` — 기존 `getJson`/`sendJson` 재사용.

---

## 6. 테스트 전략

- **`OrderControllerTest`** (@SpringBootTest + MockMvc + @ActiveProfiles("test"), CartControllerTest 패턴):
  - 주문 생성 201 — 항목 스냅샷·합계·재고 차감·장바구니 비움
  - **가격 스냅샷 불변** — 주문 후 상품 가격을 바꿔도 주문 금액 유지
  - **부분 주문** — 일부 판매중지/재고부족 시: 가능 항목만 주문·차감, 제외 목록(사유 포함) 반환, 제외 항목 장바구니 잔존
  - 전 항목 불가 400 — **재고 차감 없음(롤백) 검증**
  - 빈 장바구니 400
  - 취소 200 — 재고 복원 / 중복 취소 400 / 타인 주문 404 / 삭제된 상품 항목은 복원 스킵
  - 비인증 401, 어드민 토큰 403, 고객 간 주문 격리
- **`ProductApiTest` 보강** (어드민 상품 API의 기존 테스트 파일) — 장바구니에 담긴 상품 삭제 시 cart_items 함께 삭제(409 아님), 주문 이력은 영향 없음.
- **동시성은 테스트하지 않는다** — 스레드 경쟁 테스트는 플레이키 위험이 커서 제외. 락 순서 규칙(productId 오름차순)은 본 스펙과 코드 리뷰로 고정.
- **프론트**: `npm run build && npm run lint` — `/orders` 라우트 등록, 타입 에러 없음.

---

## 7. 문서 동기화

- `README.md`: 기능 설명에 주문(고객 전용 — 장바구니 전체 주문, 부분 주문·제외 사유, 재고 차감·가격 스냅샷, 취소·재고 복원) 추가, API 표에 주문 3개 추가.
- `docs/ROADMAP.md`: 완료 사이클 표에 사이클 10 행 추가, "후보 4"를 완료로 갱신(잔여: 결제·배송·어드민 주문 관리는 신규 후보로).

---

## 8. 작업 방식

- 브랜치: `feature/order` (main에서 분기)
- 스펙 → 플랜(`docs/superpowers/plans/`) → Subagent-Driven Development로 Task 단위 실행(사이클 9와 동일: Sonnet 구현자 + 스펙/품질 2단계 리뷰 + 최종 전체 리뷰)
