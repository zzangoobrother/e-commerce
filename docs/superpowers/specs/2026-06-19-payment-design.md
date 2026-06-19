# 카드 결제(모의 PG) 설계 문서

> **날짜:** 2026-06-19
> **상태:** 승인됨
> **사이클:** 12 (후보 6 — 결제. MVP: 카드 결제만, 모의 PG. 반품·교환·배송지·실 PG 연동은 후속 후보로 분리)

설계 선행: 사이클 10(주문), 사이클 11(배송 상태·어드민 주문 관리). 분기점은 `main`(`fdb8467`, 사이클 11 머지 완료).

---

## 1. 목표와 범위

**목표:** 주문 생성 시 카드 결제를 도입한다. 모의 PG(MockPaymentGateway)가 카드 승인/거절을 판정하고, 승인 시에만 주문이 확정된다. 결제 정보는 별도 `Payment` 엔티티에 기록한다.

**핵심 결정 (브레인스토밍에서 사용자 확정):**
- **MVP 범위** — 카드 결제만. 다른 결제 수단(계좌이체·간편결제)·반품·교환·배송지/청구 주소는 제외.
- **결제 시점 = 주문 생성 시.** 주문 생성과 결제 승인을 **단일 트랜잭션**으로 묶는다(모의 PG라 동기 호출 가능).
- **결제 실패 = 트랜잭션 롤백.** 승인 실패 시 예외를 던져 주문 insert·재고 차감·장바구니 삭제가 한꺼번에 되돌려진다 → 주문 자체가 생성되지 않고 재고·장바구니가 원복된다.
- **별도 `Payment` 엔티티** (Order와 1:1). `OrderStatus`는 변경하지 않는다(주문 존재 = 결제 완료, 동기 결제라 중간 PENDING 상태 불필요). 결제 상태는 `PaymentStatus`(PAID/REFUNDED)가 독립적으로 책임진다.
- **모의 PG는 테스트 카드번호로 판정.** 특정 카드번호는 거절, 나머지는 승인. 거절 경로(롤백·재고 복원)를 실제 주문 흐름으로 시연·테스트 가능.
- **취소 시 모의 환불.** 결제된 주문을 취소(ORDERED 단계, 고객·어드민)하면 모의 환불 후 Payment를 REFUNDED로. 주문(CANCELLED)·결제(REFUNDED) 상태 정합성 유지.
- **`PaymentGateway` 인터페이스 + `MockPaymentGateway` 구현** — 미래 실 PG 교체 seam을 명시적으로 둔다.

**범위 제외 (후속 후보):**
- 실 PG 연동(Toss/PortOne 등) — `PaymentGateway` 인터페이스 뒤로 교체 가능하게만 설계.
- 반품·교환 — 배송완료(DELIVERED) 이후 흐름. 현재 취소는 ORDERED 단계만.
- 배송지/청구 주소 모델 — `Customer`·`Order`에 주소 필드 없음. 별도 사이클.
- 부분 환불·결제 재시도·웹훅·멱등키 — 동기 단일 트랜잭션 모델에서는 불필요.

**전제:** `createOrder`가 이미 한 트랜잭션에서 재고 차감 + `ORDERED` 확정 + (productId 오름차순 PESSIMISTIC_WRITE 잠금)을 수행한다. 결제는 이 트랜잭션 안에 끼워 넣는다.

---

## 2. 도메인 모델 — `payment` 패키지 신규

신규 패키지 `com.ecommerce.payment`:

```
com.ecommerce.payment
├─ Payment.java               엔티티 (테이블 payments)
├─ PaymentStatus.java         enum: PAID, REFUNDED
├─ PaymentRepository.java
├─ PaymentGateway.java        인터페이스 (approve / refund)
├─ MockPaymentGateway.java    구현 (@Component)
├─ PaymentDeclinedException.java   거절 → 402
└─ dto/CardPaymentRequest.java     카드 입력 DTO
```

### `Payment` 엔티티

Order와 1:1. 코드베이스 관례(Order가 `customerId`를 스칼라로 보유, 애그리거트 간 FK 회피)를 따라 **`orderId`를 스칼라**로 보유하고 **unique 제약**을 건다(JPA 연관관계 없음).

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long | PK |
| `orderId` | Long | unique. 어느 주문의 결제인지 |
| `amount` | BigDecimal(precision=14, scale=2) | 결제 금액 스냅샷 = 결제 시점 주문 합계 |
| `status` | PaymentStatus (EnumType.STRING) | PAID / REFUNDED |
| `cardBrand` | String | "VISA", "MASTERCARD" 등 (카드번호 prefix로 판정) |
| `cardLast4` | String | 카드번호 끝 4자리만 |
| `approvalNo` | String | 모의 승인번호 ("MOCK-…") |
| `paidAt` | LocalDateTime | @PrePersist |
| `refundedAt` | LocalDateTime | nullable. 환불 시각 |

테이블명 `payments`, 인덱스 `uk_payments_order(orderId)` unique.

**보안 불변식: 카드 전체번호·CVC·만료일 원본은 절대 저장하지 않는다.** Payment에는 last4 + brand만 남긴다.

**팩토리·전이 메서드** (엔티티 자체 검증 — Order 패턴 답습):
- `Payment.of(orderId, amount, Approval)` — PAID 상태로 생성.
- `refund()` — `status == PAID`일 때만 → REFUNDED + `refundedAt` 기록. 아니면 `BadRequestException`("이미 환불되었거나 결제되지 않은 주문입니다.").

### `PaymentStatus`

```
PAID, REFUNDED
```
주석: 동기 결제라 결제 대기(PENDING) 상태 없음 — 승인 실패는 롤백되어 Payment 자체가 생성되지 않는다. 환불은 취소에 동반.

### `OrderStatus` — 변경 없음

`ORDERED → SHIPPING → DELIVERED`, `CANCELLED` 그대로. 주문이 존재한다는 것 자체가 결제 완료를 의미한다.

---

## 3. 모의 PG — `PaymentGateway` / `MockPaymentGateway`

### 인터페이스 `PaymentGateway`

```
Approval approve(CardPaymentRequest card, BigDecimal amount);  // 승인 결과(brand·last4·approvalNo)
void refund(Payment payment);                                  // 환불
```
- `Approval`은 `record Approval(String cardBrand, String cardLast4, String approvalNo)` (payment 패키지 내 중첩 또는 별도 record).
- 실 PG 도입 시 이 인터페이스의 새 구현으로 교체한다(나머지 코드 무변경).

### 구현 `MockPaymentGateway` (`@Component`)

**`approve` 판정 순서:**
1. **형식 검증** — Luhn 체크섬 실패 / 만료일 과거 / CVC 형식(3자리 숫자) 위반 → `BadRequestException` → **HTTP 400**.
2. **거절 카드** — 약속된 테스트 카드번호(끝 `0002` = 한도초과)면 `PaymentDeclinedException` → **HTTP 402 Payment Required**.
3. **승인** — 그 외 Luhn 통과 카드 → `Approval` 반환.
   - `cardBrand`: 카드번호 prefix로 판정(`4`→VISA, `5`→MASTERCARD, 그 외→"CARD").
   - `cardLast4`: 카드번호 끝 4자리.
   - `approvalNo`: `"MOCK-" + UUID` 또는 난수 기반(앱 코드에서 생성).

**테스트 카드(문서·테스트 공유):**
| 카드번호 | 결과 |
|----------|------|
| 4242 4242 4242 4242 | 승인(VISA) |
| 4000 0000 0000 0002 | 거절(한도초과) → 402 |
| Luhn 불통과 / 만료일 과거 | 형식오류 → 400 |

**`refund`:** 모의 — MVP는 항상 성공(로그만). 실 PG 도입 시 실제 환불 호출로 교체.

### `CardPaymentRequest` DTO

```
record CardPaymentRequest(
    String cardNumber,    // @NotBlank, 숫자
    Integer expiryMonth,  // @Min(1) @Max(12)
    Integer expiryYear,   // @NotNull
    String cvc,           // @NotBlank, 3자리
    String cardholderName // @NotBlank
)
```
Bean Validation으로 1차 형식 검증, 게이트웨이가 Luhn·만료일 등 2차 검증. **이 DTO는 어디에도 영속화하지 않는다**(요청 처리 중에만 존재).

---

## 4. 동시성·트랜잭션

기존 패턴을 그대로 재사용한다 — 새 잠금 전략 없음.

### 주문 생성 (단일 `@Transactional`)

```
(기존) cartItems 조회 → productId 오름차순 PESSIMISTIC_WRITE 잠금(findAllForUpdate)
(기존) 항목별 검증(삭제/비ON_SALE/재고부족 → ExcludedItem 제외, 장바구니 잔류)
(기존) 통과분 decreaseStock + order.addItem
(기존) 전부 제외면 BadRequestException → 400 (롤백)
(기존) orderRepository.save(order)            // ORDERED, totalPrice 확정
(신규) Approval ap = paymentGateway.approve(card, order.getTotalPrice())
        ├ 형식오류 → BadRequestException  → 400  (throw → 전체 롤백)
        └ 거절    → PaymentDeclinedException → 402  (throw → 전체 롤백)
(신규) paymentRepository.save(Payment.of(order.getId(), order.getTotalPrice(), ap))  // PAID
(기존) 주문된 productId만 장바구니 삭제
return CreateOrderResponse(order, excluded, paymentSummary)
```

> **핵심:** 결제 실패는 예외를 던져 트랜잭션 전체를 롤백한다 → 재고 차감·주문 insert·장바구니 삭제가 일괄 되돌려진다. 생성 경로에서는 별도 `restoreStock` 호출이 **불필요**하다(롤백이 곧 재고·장바구니 복원). 결제 금액은 부분 주문으로 제외가 반영된 **최종 totalPrice**로 청구한다.

### 취소 (고객 `cancelOrder` + 어드민 `cancelOrderByAdmin`)

기존 Order 행 PESSIMISTIC_WRITE 잠금(이중 취소 방지) 하에서:
```
order.cancel()                              (ORDERED → CANCELLED, 엔티티 검증)
Payment p = paymentRepository.findByOrderId(orderId).orElseThrow(...)
paymentGateway.refund(p)                    (모의)
p.refund()                                  (PAID → REFUNDED)
restoreStock(order)                         (기존, productId 오름차순 잠금 재고 복원)
```
Order 행 잠금이 취소를 직렬화하므로 Payment 업데이트도 같은 잠금 하에 안전(이중 환불 방지). Payment는 별도 잠금 불필요.

---

## 5. API

| 메서드·경로 | 변경 | 동작 |
|-------------|------|------|
| `POST /api/store/orders` | **요청 본문에 `CardPaymentRequest` 추가** | 주문 생성 + 결제. 성공 201(결제 요약 포함). 거절 **402**, 형식오류 **400**, 전부 제외 400 |
| `POST /api/store/orders/{id}/cancel` | 시그니처 무변경 | 내부에서 환불 + REFUNDED 처리 |
| `POST /api/admin/orders/{id}/cancel` | 시그니처 무변경 | 동일 |
| `GET /api/store/orders`, `GET /api/admin/orders` | 응답에 결제 요약 추가 | brand·last4·결제상태 노출 |

- **신규 컨트롤러 없음** — 결제는 주문 생성/취소에 흡수(별도 `PaymentController` 불필요, YAGNI).
- **`OrderService` 의존 추가**: `PaymentGateway`, `PaymentRepository`. 생성자 확장.
- **DTO**:
  - 주문 생성 요청 본문은 `@RequestBody CardPaymentRequest`를 직접 받는다(장바구니는 서버측 상태라 본문에 항목 없음 — 카드 정보가 유일한 본문). 컨트롤러가 인증 JWT에서 customerId를, 본문에서 카드를 받아 `orderService.createOrder(customerId, card)` 호출.
  - `OrderResponse`·`AdminOrderResponse`에 결제 요약(`PaymentSummary(cardBrand, cardLast4, status)`) 필드 추가 — Payment 조회로 enrich(주문 목록은 orderId 배치 조회로 N+1 회피).
- **보안: SecurityConfig·proxy 변경 없음.** `/api/store/orders/**`(hasRole CUSTOMER)·`/api/admin/**`(hasRole ADMIN)가 이미 보호.
- **HTTP 상태**: 거절은 `402 Payment Required`로 "카드 거절"을 "잘못된 요청(400)"과 구분. `GlobalExceptionHandler`에 `PaymentDeclinedException` → 402 매핑 추가.

---

## 6. 프론트엔드

- **체크아웃 카드 입력 폼** — 현재 주문 생성("주문하기")이 일어나는 화면(장바구니 체크아웃)에 카드번호·만료일·CVC·소유자명 입력 추가. Server Action이 FormData로 카드 정보를 받아 `createOrder`에 전달.
  - *플랜 단계 확인 사항:* 주문 생성 트리거의 정확한 파일(`/cart` 또는 신규 `/checkout`). proxy의 non-GET 통과 분기(사이클 9 교훈) 덕에 보호 경로 Server Action POST는 가로채지지 않음 — 제거 금지.
- **거절/형식오류 처리** — 402/400 시 `?error=`로 메시지 표시(예: "카드가 거절되었습니다"), 장바구니 유지(롤백되어 그대로 남음). redirect는 try 블록 밖(Next 16: 예외를 던짐).
- **표시** — 주문 목록/상세에 `결제: VISA ****4242` + 상태 라벨(결제완료/환불됨). 어드민 주문 화면도 동일.
- **`api.ts`** — `CardPaymentRequest`·`PaymentSummary` 타입 추가, `createOrder` 시그니처에 카드 인자 추가, `Order`·`AdminOrder` 타입에 결제 요약 필드.
- **Next.js 16 유의:** `cookies()`/`searchParams` async(await), Server Action `redirect()`는 try 밖.

---

## 7. 테스트

**백엔드 단위** — `MockPaymentGatewayTest`:
- 승인 카드 → Approval(brand·last4·approvalNo) 반환
- 거절 카드(끝 0002) → PaymentDeclinedException
- Luhn 불통과 / 만료일 과거 / CVC 형식 위반 → BadRequestException
- brand 추출(4→VISA, 5→MASTERCARD, 기타→CARD)

**백엔드 통합(MockMvc)** — 주문 생성 경로로 결제 검증(`OrderControllerTest` 보강 또는 신규 `PaymentFlowTest`):
- 승인 카드 → 201 + Payment PAID 저장 + 재고 차감 + 장바구니 비움 + 응답에 결제 요약
- 거절 카드 → **402** + 주문·Payment 미생성 + **재고 원복** + 장바구니 유지
- 형식오류 카드 → 400 + 주문·Payment 미생성
- 결제 주문 취소(고객) → CANCELLED + Payment REFUNDED + 재고 복원
- 결제 주문 취소(어드민) → 동일
- 어드민 주문 목록 응답에 결제 요약 포함

**기존 테스트 보정** — `createOrder` 시그니처 변경으로, 기존 `OrderControllerTest`의 주문 생성 케이스에 **승인 테스트 카드(4242…)**를 추가해야 한다(컴파일·통과 유지).

**프론트** — `npm run build && npm run lint`(체크아웃 라우트·카드 폼 타입 확인).

**DB**: 신규 `payments` 테이블은 JPA ddl-auto로 생성(기존 관례, 마이그레이션 파일 없음).

---

## 8. Definition of Done

- [ ] `Payment` 엔티티·`PaymentStatus`·`PaymentRepository` 신규, `payments` 테이블(orderId unique)
- [ ] `PaymentGateway` 인터페이스 + `MockPaymentGateway` 구현(테스트 카드 판정·Luhn·brand·환불)
- [ ] 카드 전체번호·CVC 미저장(last4 + brand만) — 코드·테스트로 고정
- [ ] 주문 생성이 단일 트랜잭션에서 결제 수행: 승인 201, 거절 402, 형식오류 400
- [ ] 결제 실패 시 롤백 → 주문·Payment 미생성·재고 원복·장바구니 유지 (테스트로 고정)
- [ ] 결제 주문 취소(고객·어드민) → CANCELLED + Payment REFUNDED + 재고 복원 (테스트로 고정)
- [ ] 주문/어드민 응답에 결제 요약(brand·last4·status) 포함
- [ ] 체크아웃 화면 카드 입력 폼, 거절/형식오류 메시지 표시, 장바구니 유지
- [ ] 주문 목록에 결제 정보·상태 라벨 표시(고객·어드민)
- [ ] `cd backend && ./gradlew test` 전체 통과(신규 + 기존 보정 포함)
- [ ] `cd frontend && npm run build && npm run lint` 통과
- [ ] README·ROADMAP 동기화(후보 6 = 사이클 12 완료, 잔여는 후속 후보로)
- [ ] SecurityConfig·proxy 무변경 확인
