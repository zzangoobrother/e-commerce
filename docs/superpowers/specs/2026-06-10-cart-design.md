# 장바구니 + 고객 보호 인프라 — 설계 문서

**날짜:** 2026-06-10
**상태:** 승인됨
**사이클:** 9 (주문/장바구니 도메인의 1단계 — 분해 결정에 따라 장바구니만, 주문은 사이클 10)

---

## 1. 목표와 범위

스토어 고객이 로그인 후 상품을 장바구니에 담고(같은 상품은 수량 가산), 수량 변경·삭제·조회할 수 있게 한다. 이를 위해 사이클 7에서 YAGNI로 보류했던 **고객 보호 인프라**(고객 JWT 보호 API 매처, 프론트 customerRefresh·proxy 보호 경로)를 함께 도입한다 — 장바구니가 첫 번째 고객 전용 보호 기능이다.

**범위 제외 (사이클 10 이후):**
- 주문 생성·재고 차감·주문 상태 관리 — 장바구니는 재고를 차감하지 않는다
- 게스트 장바구니 — 비로그인 담기는 로그인 페이지로 리다이렉트 (담기 = 로그인 필수)
- 가격 스냅샷 — 장바구니는 현재가를 표시하고, 가격 고정은 주문 생성 시점의 책임

**전제:** 고객 인증(사이클 7, PR #10 main 머지됨). 사이클 8(PR #11, refresh 결함 수정)과는 파일이 겹치지 않아 머지 순서 무관 — main에서 `feature/cart` 분기.

---

## 2. 도메인 모델 — CartItem 플랫 (검토 후 채택)

`Cart` 엔티티 없이 `CartItem` 단일 테이블. "고객의 장바구니" = `customer_id`로 조회한 CartItem 목록.

```
cart_items
- id            PK
- customer_id   BIGINT NOT NULL          ← 스칼라 (Customer 로딩 불필요)
- product_id    FK → products, NOT NULL  ← @ManyToOne(LAZY) Product (응답에 상품명·가격 필요)
- quantity      INT NOT NULL (≥1)
- created_at    NOT NULL
- UNIQUE (customer_id, product_id)       ← 같은 상품 재담기는 행 추가가 아니라 수량 가산
- INDEX (customer_id)
```

**검토한 대안 (기각):**
- **Cart aggregate (Cart 1:N CartItem)**: 현재 장바구니에 항목 외 상태(쿠폰·배송지 등)가 없어 Cart는 빈 조인 테이블 + 전 쿼리 조인 1단계 추가일 뿐. 메타데이터가 생기는 시점에 도입해도 마이그레이션 비용이 작다 → YAGNI 기각.
- **JWT에 customerId 클레임 추가**: 요청당 email 인덱스 조회 1회를 아끼려고 토큰 발급부를 변경하는 것은 비용 대비 파급이 큼 → 기각.

---

## 3. 백엔드 — `com.ecommerce.cart` 패키지 (신규)

### 3.1 컴포넌트

- **`CartItem`** — 위 스키마의 엔티티. `addQuantity(int)` 같은 수량 가산 메서드 제공.
- **`CartItemRepository`** — `findByCustomerIdAndProductId`, `findAllByCustomerId`(상품 fetch join으로 N+1 방지), `deleteByCustomerIdAndProductId`.
- **`CartService`** — 담기/수량 변경/삭제/조회. `@Transactional`.
- **`CartController`** — `/api/store/cart`, 고객 식별은 `@AuthenticationPrincipal Jwt`의 subject(email) → `CustomerRepository.findByEmail` (없으면 401).
- **DTO** — `AddCartItemRequest(productId, quantity)`, `UpdateCartItemRequest(quantity)`, `CartResponse(items[], totalPrice)`, `CartItemResponse(productId, productName, price, quantity, lineTotal)`.

### 3.2 API

| 메서드 | 경로 | 동작 | 응답 |
|--------|------|------|------|
| GET | `/api/store/cart` | 내 장바구니 조회 (항목 + 합계) | 200 CartResponse |
| POST | `/api/store/cart/items` | 담기 — 기존 항목이면 수량 가산 | 201 CartResponse |
| PATCH | `/api/store/cart/items/{productId}` | 수량 변경 (절대값 설정) | 200 CartResponse |
| DELETE | `/api/store/cart/items/{productId}` | 항목 삭제 (미존재여도 멱등) | 204 |

변경 계열(POST/PATCH)이 CartResponse를 반환해 프론트가 재조회 없이 갱신 결과를 쓸 수 있게 한다.

### 3.3 검증 규칙

- `quantity ≥ 1` (`@Min(1)`, Bean Validation 400)
- 상품 미존재 → 404 (기존 NotFound 처리 관행을 플랜 작성 시 확인해 따름)
- 상품이 `ON_SALE`이 아님 → 400 (담기·수량 변경 시)
- **합산/변경 후 수량 ≤ `stockQuantity`** → 초과 시 400. 재고를 차감하지는 않는다(상한 검증만)
- 담은 후 상품이 품절·판매중지돼도 장바구니엔 남고 조회 시 그대로 노출 — 구매 가능 여부 최종 재검증은 주문 생성(사이클 10)의 책임

### 3.4 보안

`SecurityConfig`에 한 줄 추가 (어드민 매처 아래):
```java
.requestMatchers("/api/store/cart/**").hasRole("CUSTOMER")
```
- 비인증 → 401 (기존 entryPoint JSON)
- 어드민 토큰(role=ADMIN) → 403 — role 격리가 양방향으로 완성됨 (사이클 7은 고객→어드민 차단만 있었음)

---

## 4. 프론트 — 고객 보호 인프라 + 장바구니 UI

### 4.1 고객 보호 인프라 (사이클 7 보류분 도입)

- **`lib/api.ts`**: `customerRefresh(refreshToken)` 추가(보류했던 wrapper — 이제 호출자가 생김) + `getCart(token)`, `addCartItem(token, productId, quantity)`, `updateCartItemQuantity(token, productId, quantity)`, `removeCartItem(token, productId)`.
- **`app/refresh/route.ts`** (신규): 어드민 `/admin/refresh` 패턴 복제 — `customer_refresh` 쿠키로 `customerRefresh` 호출, 성공 시 고객 쿠키 2개 재설정 후 `next`로 303, 실패 시 `/logout`. 오픈 리다이렉트 방지: `next`는 `/`로 시작하고 `//`로 시작하지 않는 상대 경로만 허용(아니면 `/`).
- **`proxy.ts`**: matcher를 `["/admin/:path*", "/cart/:path*"]`로 확장. `/cart` 경로는 고객 쿠키로 판정 — `customer_token` 있으면 통과, 없고 `customer_refresh` 있으면 `/refresh?next=...`, 둘 다 없으면 `/login`. 어드민 분기는 기존 그대로. access 쿠키 maxAge가 만료시각과 일치하므로 만료 시 쿠키가 자동 소멸 → proxy 판정만으로 자동 갱신 동작(어드민과 동일 메커니즘).

### 4.2 장바구니 UI

- **상품 상세(`products/[id]/page.tsx`)**: 수량 입력 + "장바구니 담기" 버튼(Server Action). 액션에서 `customer_token` 쿠키 없으면 `redirect("/login")` — 상세 페이지 자체는 공개 유지. 담기 성공 시 `/cart`로 redirect.
- **`app/cart/page.tsx`** (신규): 서버 컴포넌트 — 항목 목록(상품명·단가·수량·소계)과 합계, 수량 변경·삭제 Server Action. proxy가 보호하므로 페이지 진입 시점엔 access 쿠키 존재 가정(Server Action 중 401 경계는 `/login` redirect).
- **홈 헤더(`app/page.tsx`)**: 로그인 상태일 때 장바구니 링크 추가.

---

## 5. 테스트 전략

- **`CartControllerTest`** (@SpringBootTest + MockMvc + @ActiveProfiles("test"), 프로젝트 관행 — service 단위 테스트 없이 컨트롤러 테스트로 검증):
  - 담기 201 + 응답에 항목·합계
  - 같은 상품 재담기 → 수량 가산(행 1개 유지)
  - 재고 초과 담기/수량 변경 → 400
  - 미판매(`ON_SALE` 아님) 상품 담기 → 400
  - 미존재 상품 담기 → 404
  - 조회 — 항목·lineTotal·totalPrice 정확성
  - 수량 변경(절대값) 200, 삭제 204(미존재여도 멱등)
  - **비인증 401, 어드민 토큰 403** (role 격리)
  - **고객 A의 장바구니가 고객 B에게 보이지 않음** (격리)
- **프론트**: `npm run build && npm run lint` — `/cart`·`/refresh` 라우트 등록, 타입 에러 없음.

---

## 6. 문서 동기화

- `README.md`: 기능 설명에 장바구니(고객 전용, 담기·수량변경·삭제·합계) 추가, 고객 보호 인프라(자동 갱신 `/refresh`, proxy `/cart` 보호) 반영.
- `docs/ROADMAP.md`: 완료 사이클 표에 사이클 9 행 추가, "후보 4: 주문/장바구니"를 "장바구니 완료, 주문(재고 차감·상태 관리)은 사이클 10 후보"로 갱신.

---

## 7. 작업 방식

- 브랜치: `feature/cart` (main에서 분기 — PR #11과 파일 비겹침으로 머지 순서 무관)
- 스펙 → 플랜(`docs/superpowers/plans/`) 작성. **이번 사이클 결정: 플랜 작성까지만 진행, 실행은 별도 지시 후**
