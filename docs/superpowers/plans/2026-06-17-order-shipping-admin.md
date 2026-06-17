# 주문 배송 상태 · 어드민 주문 관리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주문에 배송 진행 상태(ORDERED→SHIPPING→DELIVERED)를 도입하고, 어드민이 전체 주문을 조회·상태 전이(배송 시작/완료/취소)할 수 있는 API·화면을 추가한다.

**Architecture:** 상태 전이 규칙은 `Order` 엔티티의 메서드(`ship()`/`deliver()`/`cancel()`)가 출발 상태를 자체 검증해 어떤 호출 경로로 와도 불법 전이를 차단한다. 모든 전이는 Order 행을 `PESSIMISTIC_WRITE`로 잠근 뒤 수행해 동시 전이 경합 시 한쪽만 성공시킨다(취소는 추가로 Product를 productId 오름차순 잠금 후 재고 복원). 어드민 기능은 새 서비스를 만들지 않고 기존 `OrderService`를 확장한다(ProductService가 admin+store를 겸하는 관례).

**Tech Stack:** Java 25 · Spring Boot 4.0.6 · Spring Security(JWT) · JPA/Hibernate · H2(test) · JUnit5/MockMvc · Next.js 16.2.6(Server Action) · React 19

설계 문서: `docs/superpowers/specs/2026-06-17-order-shipping-admin-design.md`

> **Next.js 16 필독:** `cookies()`/`searchParams`는 async(`await` 필수). Server Action에서 `redirect()`는 예외를 던지므로 **try 블록 밖에서만** 호출한다. proxy의 non-GET 통과 분기(사이클 9 교훈) 덕에 `/admin/*` 보호 경로의 Server Action POST는 가로채지지 않는다 — 제거하지 말 것. proxy/SecurityConfig는 이 사이클에서 변경하지 않는다(`/api/admin/**`·`/admin/*`가 이미 보호됨).

---

## 파일 구조

**백엔드 신규**
- `backend/src/main/java/com/ecommerce/order/AdminOrderController.java`
- `backend/src/main/java/com/ecommerce/order/dto/AdminOrderResponse.java`
- `backend/src/test/java/com/ecommerce/order/AdminOrderControllerTest.java`

**백엔드 수정**
- `order/OrderStatus.java` — enum 값 2개 추가
- `order/Order.java` — `ship()`/`deliver()` 추가, `cancel()` 강화
- `order/OrderRepository.java` — 잠금/목록 조회 3개
- `order/OrderService.java` — 어드민 메서드 4개 + 재고 복원 추출
- `backend/src/test/java/com/ecommerce/order/OrderControllerTest.java` — 보강 테스트 1개

**프론트 신규**
- `frontend/src/app/admin/orders/page.tsx`, `frontend/src/app/admin/orders/actions.ts`

**프론트 수정**
- `frontend/src/lib/api.ts` — OrderStatus 확장 · AdminOrder 타입 · 어드민 함수 4개
- `frontend/src/app/orders/page.tsx` — STATUS_LABEL 2개 추가
- `frontend/src/app/admin/page.tsx` — 주문 관리 링크

**문서**: `README.md`, `docs/ROADMAP.md`

---

## Task 1: 플러밍 — 상태 머신 확장 + 리포지토리 보강

**Files:**
- Modify: `backend/src/main/java/com/ecommerce/order/OrderStatus.java`
- Modify: `backend/src/main/java/com/ecommerce/order/Order.java`
- Modify: `backend/src/main/java/com/ecommerce/order/OrderRepository.java`

- [ ] **Step 1: OrderStatus에 배송 상태 추가**

`OrderStatus.java` 전체를 교체:
```java
package com.ecommerce.order;

// 주문 상태 — 결제 없이 주문 즉시 확정(ORDERED) 후 배송 진행.
// 전이: ORDERED → SHIPPING → DELIVERED (어드민), ORDERED → CANCELLED (고객·어드민, 재고 복원).
// DELIVERED·CANCELLED는 종료 상태. 결제 상태는 다음 사이클의 책임.
public enum OrderStatus {
    ORDERED, SHIPPING, DELIVERED, CANCELLED
}
```

- [ ] **Step 2: Order에 전이 메서드 추가 + cancel 강화**

`Order.java`의 기존 `cancel()` 메서드를 찾아 다음으로 교체하고, 바로 아래에 `ship()`·`deliver()`를 추가:
```java
    // 취소 — ORDERED일 때만 (배송 시작 후/이미 취소면 거부). 재고 복원은 호출부(OrderService) 책임.
    public void cancel() {
        if (status != OrderStatus.ORDERED) {
            throw new BadRequestException("배송이 시작되었거나 이미 취소된 주문은 취소할 수 없습니다.");
        }
        this.status = OrderStatus.CANCELLED;
    }

    // 배송 시작 — ORDERED → SHIPPING (어드민)
    public void ship() {
        if (status != OrderStatus.ORDERED) {
            throw new BadRequestException("주문 완료 상태에서만 배송을 시작할 수 있습니다.");
        }
        this.status = OrderStatus.SHIPPING;
    }

    // 배송 완료 — SHIPPING → DELIVERED (어드민)
    public void deliver() {
        if (status != OrderStatus.SHIPPING) {
            throw new BadRequestException("배송 중 상태에서만 배송 완료할 수 있습니다.");
        }
        this.status = OrderStatus.DELIVERED;
    }
```
(`BadRequestException`은 이미 import되어 있다 — 기존 `cancel()`이 사용 중.)

- [ ] **Step 3: OrderRepository에 잠금/목록 조회 추가**

`OrderRepository.java`의 `findByIdAndCustomerIdForUpdate` 선언 아래에 추가:
```java
    // 어드민 전이용 — Order 행을 PESSIMISTIC_WRITE로 잠가 상태 전이를 직렬화(동시 전이 경합 방지).
    // 고객 취소와 동일 패턴: @Query + @Lock에서 @EntityGraph는 무시될 수 있어 items는 같은 트랜잭션 내 lazy 초기화.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // 어드민 목록 — 항목을 함께 로딩(N+1 방지), 최신 주문 먼저
    @EntityGraph(attributePaths = "items")
    List<Order> findAllByOrderByIdDesc();

    // 어드민 목록(상태 필터)
    @EntityGraph(attributePaths = "items")
    List<Order> findAllByStatusOrderByIdDesc(OrderStatus status);
```
(`OrderStatus`는 같은 패키지라 import 불필요. `List`·`Optional`·`@Lock`·`LockModeType`·`@Query`·`@Param`·`@EntityGraph`는 이미 import되어 있다.)

- [ ] **Step 4: 컴파일 확인**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ecommerce/order/OrderStatus.java \
        backend/src/main/java/com/ecommerce/order/Order.java \
        backend/src/main/java/com/ecommerce/order/OrderRepository.java
git commit -m "feat: 주문 배송 상태 머신 추가(ship·deliver 전이, cancel을 ORDERED만으로 강화, 행 잠금 조회)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 어드민 주문 API + 고객 취소 강화 검증 (TDD)

프로젝트 관행대로 service는 컨트롤러 테스트(MockMvc)로 검증한다. 어드민 테스트의 주문 사전 구성은 API 대신 리포지토리로 직접 저장한다(테스트 단순화).

**Files:**
- Create: `backend/src/test/java/com/ecommerce/order/AdminOrderControllerTest.java`
- Modify: `backend/src/test/java/com/ecommerce/order/OrderControllerTest.java`
- Create: `backend/src/main/java/com/ecommerce/order/dto/AdminOrderResponse.java`
- Modify: `backend/src/main/java/com/ecommerce/order/OrderService.java`
- Create: `backend/src/main/java/com/ecommerce/order/AdminOrderController.java`

- [ ] **Step 1: AdminOrderControllerTest 작성(실패하는 테스트)**

`backend/src/test/java/com/ecommerce/order/AdminOrderControllerTest.java`:
```java
package com.ecommerce.order;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.product.ProductStatus;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;

    private int supplierSeq = 0;

    @AfterEach
    void cleanup() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        supplierRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // role=ADMIN 모의 JWT — 어드민 주문 API는 hasRole('ADMIN')로 보호된다
    private RequestPostProcessor adminJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor customerJwt(String email) {
        return jwt().jwt(j -> j.subject(email))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private Customer customer(String email) {
        return customerRepository.save(new Customer(email, "encoded-password"));
    }

    private Product product(String name, String price, int stock) {
        Supplier supplier = supplierRepository.save(
                new Supplier("공급사" + supplierSeq++, "s" + supplierSeq + "@example.com"));
        return productRepository.save(new Product(supplier, name, "설명",
                new BigDecimal(price), stock, ProductStatus.ON_SALE));
    }

    // ORDERED 주문 생성 — createOrder와 동일하게 재고 차감 + 스냅샷
    private Order placeOrder(Customer customer, Product product, int qty) {
        product.decreaseStock(qty);
        productRepository.save(product);
        Order order = new Order(customer.getId());
        order.addItem(product.getId(), product.getName(), product.getPrice(), qty);
        return orderRepository.save(order);
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    private String status(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow().getStatus().name();
    }

    @Test
    void 전체_목록은_고객_이메일을_포함해_최신순으로_반환한다() throws Exception {
        Customer a = customer("a@example.com");
        Customer b = customer("b@example.com");
        placeOrder(a, product("사과", "3000", 10), 1);
        placeOrder(b, product("배", "5000", 10), 2);

        mockMvc.perform(get("/api/admin/orders").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].customerEmail").value("b@example.com")) // 최신 먼저
                .andExpect(jsonPath("$[0].items[0].productName").value("배"))
                .andExpect(jsonPath("$[1].customerEmail").value("a@example.com"));
    }

    @Test
    void 상태_필터로_조회한다() throws Exception {
        Customer a = customer("a@example.com");
        Order ordered = placeOrder(a, product("사과", "3000", 10), 1);
        Order toShip = placeOrder(a, product("배", "5000", 10), 1);
        mockMvc.perform(post("/api/admin/orders/" + toShip.getId() + "/ship").with(adminJwt()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/orders?status=ORDERED").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ordered.getId()));
    }

    @Test
    void 배송_시작하면_SHIPPING으로_바뀐다() throws Exception {
        Order order = placeOrder(customer("a@example.com"), product("사과", "3000", 10), 1);

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/ship").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPING"));

        assertThat(status(order.getId())).isEqualTo("SHIPPING");
    }

    @Test
    void 배송_완료는_SHIPPING에서만_가능하다() throws Exception {
        Order order = placeOrder(customer("a@example.com"), product("사과", "3000", 10), 1);

        // ORDERED에서 바로 배송 완료 → 400
        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/deliver").with(adminJwt()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/ship").with(adminJwt()));
        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/deliver").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void 주문완료가_아니면_배송_시작할_수_없다() throws Exception {
        Order order = placeOrder(customer("a@example.com"), product("사과", "3000", 10), 1);
        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/ship").with(adminJwt()));

        // 이미 SHIPPING인데 다시 배송 시작 → 400
        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/ship").with(adminJwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 배송중_주문은_취소할_수_없다() throws Exception {
        Product apple = product("사과", "3000", 10);
        Order order = placeOrder(customer("a@example.com"), apple, 2);
        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/ship").with(adminJwt()));

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/cancel").with(adminJwt()))
                .andExpect(status().isBadRequest());

        // 취소 거부 — 재고 복원되지 않음 (주문 시 차감된 8 유지)
        assertThat(stockOf(apple)).isEqualTo(8);
    }

    @Test
    void 어드민_취소는_상태를_바꾸고_재고를_복원한다() throws Exception {
        Product apple = product("사과", "3000", 10);
        Order order = placeOrder(customer("a@example.com"), apple, 2);

        mockMvc.perform(post("/api/admin/orders/" + order.getId() + "/cancel").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(stockOf(apple)).isEqualTo(10);
    }

    @Test
    void 없는_주문을_전이하면_404() throws Exception {
        mockMvc.perform(post("/api/admin/orders/99999/ship").with(adminJwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 고객_토큰으로_접근하면_403() throws Exception {
        mockMvc.perform(get("/api/admin/orders").with(customerJwt("a@example.com")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 인증_없이_접근하면_401() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: OrderControllerTest에 고객 취소 강화 검증 추가**

`OrderControllerTest.java`의 마지막 `@Test` 아래(클래스 닫는 `}` 직전)에 추가:
```java
    @Test
    void 배송이_시작된_주문은_고객이_취소할_수_없다_400() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(customer, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated());

        // 어드민이 배송을 시작했다고 가정 — 엔티티 전이를 직접 적용 후 저장
        Order order = orderRepository.findAll().get(0);
        order.ship();
        orderRepository.save(order);

        mockMvc.perform(post("/api/store/orders/" + order.getId() + "/cancel")
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isBadRequest());

        // 배송 시작 후 취소 불가 — 재고는 차감된 8 그대로
        assertThat(stockOf(apple)).isEqualTo(8);
    }
```
(`Order`는 같은 패키지라 import 불필요. `customer`/`product`/`putInCart`/`stockOf` 헬퍼와 `assertThat`은 이미 존재.)

- [ ] **Step 3: 테스트 실행으로 실패(RED) 확인**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.order.AdminOrderControllerTest"`
Expected: 컴파일 실패 또는 전부 실패 — `AdminOrderResponse`·`AdminOrderController`·`OrderService` 어드민 메서드가 없다. 하나라도 통과하면 멈추고 원인을 확인할 것.

- [ ] **Step 4: AdminOrderResponse DTO 작성**

`backend/src/main/java/com/ecommerce/order/dto/AdminOrderResponse.java`:
```java
package com.ecommerce.order.dto;

import com.ecommerce.order.Order;
import com.ecommerce.order.OrderStatus;
import com.ecommerce.order.dto.OrderResponse.OrderItemResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 어드민 주문 응답 — 고객 이메일을 포함(어드민은 주문 주체를 식별해야 한다). 항목 응답은 고객용과 재사용.
public record AdminOrderResponse(Long id, String customerEmail, OrderStatus status,
                                 BigDecimal totalPrice, LocalDateTime createdAt,
                                 List<OrderItemResponse> items) {

    public static AdminOrderResponse from(Order order, String customerEmail) {
        return new AdminOrderResponse(order.getId(), customerEmail, order.getStatus(),
                order.getTotalPrice(), order.getCreatedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}
```

- [ ] **Step 5: OrderService에 어드민 메서드 + 재고 복원 추출**

`OrderService.java`를 다음과 같이 수정한다.

(1) import 추가(기존 import 블록에). `OrderStatus`는 같은 패키지라 import하지 않는다:
```java
import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.order.dto.AdminOrderResponse;
```

(2) 필드·생성자에 `CustomerRepository` 추가:
```java
    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;

    public OrderService(OrderRepository orderRepository,
                        CartItemRepository cartItemRepository,
                        ProductRepository productRepository,
                        CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
    }
```

(3) 기존 `cancelOrder(...)`의 재고 복원 블록을 `restoreStock(order)` 호출로 교체(메서드 전체를 아래로 교체):
```java
    // 고객 취소 — 본인 주문만, ORDERED일 때만(엔티티가 검증). Order 행 잠금으로 이중 취소 방지 + 재고 복원.
    @Transactional
    public OrderResponse cancelOrder(Long customerId, Long orderId) {
        Order order = orderRepository.findByIdAndCustomerIdForUpdate(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다: " + orderId));
        order.cancel();
        restoreStock(order);
        return OrderResponse.from(order);
    }
```

(4) `summarize(...)` private 메서드 위(또는 클래스 끝 적당한 위치)에 어드민 메서드 4개 + `restoreStock` + `adminResponse` 추가:
```java
    // === 어드민 ===

    // 전체 주문 목록(상태 필터 옵션) — 고객 이메일은 배치 조회로 enrich(N+1 회피)
    public List<AdminOrderResponse> getAllOrders(OrderStatus statusFilter) {
        List<Order> orders = (statusFilter == null)
                ? orderRepository.findAllByOrderByIdDesc()
                : orderRepository.findAllByStatusOrderByIdDesc(statusFilter);
        List<Long> customerIds = orders.stream().map(Order::getCustomerId).distinct().toList();
        Map<Long, String> emailById = customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getEmail));
        return orders.stream()
                .map(o -> AdminOrderResponse.from(o, emailById.getOrDefault(o.getCustomerId(), "(삭제된 고객)")))
                .toList();
    }

    @Transactional
    public AdminOrderResponse shipOrder(Long orderId) {
        Order order = lockOrder(orderId);
        order.ship();
        return adminResponse(order);
    }

    @Transactional
    public AdminOrderResponse deliverOrder(Long orderId) {
        Order order = lockOrder(orderId);
        order.deliver();
        return adminResponse(order);
    }

    @Transactional
    public AdminOrderResponse cancelOrderByAdmin(Long orderId) {
        Order order = lockOrder(orderId);
        order.cancel();
        restoreStock(order);
        return adminResponse(order);
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다: " + orderId));
    }

    private AdminOrderResponse adminResponse(Order order) {
        String email = customerRepository.findById(order.getCustomerId())
                .map(Customer::getEmail).orElse("(삭제된 고객)");
        return AdminOrderResponse.from(order, email);
    }

    // 취소 시 재고 복원 — 잠금은 productId 오름차순(데드락 예방). 삭제된 상품은 잠금 조회에 빠져 자연 스킵.
    private void restoreStock(Order order) {
        List<Long> productIds = order.getItems().stream()
                .map(OrderItem::getProductId).sorted().toList();
        Map<Long, Integer> quantityByProductId = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        for (Product product : productRepository.findAllForUpdate(productIds)) {
            product.increaseStock(quantityByProductId.get(product.getId()));
        }
    }
```

- [ ] **Step 6: AdminOrderController 작성**

`backend/src/main/java/com/ecommerce/order/AdminOrderController.java`:
```java
package com.ecommerce.order;

import com.ecommerce.order.dto.AdminOrderResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 어드민 주문 관리 API — /api/admin/**는 SecurityConfig에서 hasRole('ADMIN')로 보호된다(별도 매처 불필요).
// 상태 전이가 POST /ship·/deliver·/cancel인 이유: 주문은 상태 전이(이력 보존), 부수효과(취소=재고복원)를 엔드포인트로 명시.
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<AdminOrderResponse> list(@RequestParam(required = false) OrderStatus status) {
        return orderService.getAllOrders(status);
    }

    @PostMapping("/{orderId}/ship")
    public AdminOrderResponse ship(@PathVariable Long orderId) {
        return orderService.shipOrder(orderId);
    }

    @PostMapping("/{orderId}/deliver")
    public AdminOrderResponse deliver(@PathVariable Long orderId) {
        return orderService.deliverOrder(orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public AdminOrderResponse cancel(@PathVariable Long orderId) {
        return orderService.cancelOrderByAdmin(orderId);
    }
}
```

- [ ] **Step 7: 테스트 통과 확인 + 전체 회귀**

Run: `cd backend && ./gradlew test --tests "com.ecommerce.order.AdminOrderControllerTest"`
Expected: PASS (10개 모두)

Run: `cd backend && ./gradlew test`
Expected: PASS — 기존 테스트 포함 전부 통과(AdminOrderControllerTest 10 + OrderControllerTest 보강 1 + 기존 전부).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/ecommerce/order/AdminOrderController.java \
        backend/src/main/java/com/ecommerce/order/dto/AdminOrderResponse.java \
        backend/src/main/java/com/ecommerce/order/OrderService.java \
        backend/src/test/java/com/ecommerce/order/AdminOrderControllerTest.java \
        backend/src/test/java/com/ecommerce/order/OrderControllerTest.java
git commit -m "feat: 어드민 주문 관리 API 추가(목록·상태 필터·배송 시작/완료·취소, 어드민 전용)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 프론트 — 어드민 주문 관리 화면 + 고객 상태 라벨

**Files:**
- Modify: `frontend/src/lib/api.ts`
- Modify: `frontend/src/app/orders/page.tsx`
- Create: `frontend/src/app/admin/orders/actions.ts`
- Create: `frontend/src/app/admin/orders/page.tsx`
- Modify: `frontend/src/app/admin/page.tsx`

- [ ] **Step 1: api.ts — OrderStatus 확장 · AdminOrder 타입 · 어드민 함수**

(1) `OrderStatus` 타입을 다음으로 교체:
```ts
export type OrderStatus = "ORDERED" | "SHIPPING" | "DELIVERED" | "CANCELLED";
```

(2) `CreateOrderResult` 인터페이스 아래에 `AdminOrder` 추가:
```ts
export interface AdminOrder {
  id: number;
  customerEmail: string;
  status: OrderStatus;
  totalPrice: number;
  createdAt: string;
  items: OrderItem[];
}
```

(3) 파일 끝의 고객 주문 함수(`cancelOrder`) 아래에 어드민 함수 추가:
```ts
// 어드민 주문 관리 (어드민 Bearer 토큰 필요)
export const getAdminOrders = (token: string, status?: OrderStatus) =>
  getJson<AdminOrder[]>(
    `/api/admin/orders${status ? `?status=${status}` : ""}`,
    token,
  );
export const shipOrder = (token: string, orderId: number) =>
  sendJson<AdminOrder>(`/api/admin/orders/${orderId}/ship`, "POST", token);
export const deliverOrder = (token: string, orderId: number) =>
  sendJson<AdminOrder>(`/api/admin/orders/${orderId}/deliver`, "POST", token);
export const adminCancelOrder = (token: string, orderId: number) =>
  sendJson<AdminOrder>(`/api/admin/orders/${orderId}/cancel`, "POST", token);
```

- [ ] **Step 2: orders/page.tsx — STATUS_LABEL에 배송 상태 추가**

`frontend/src/app/orders/page.tsx`의 `STATUS_LABEL` 객체를 다음으로 교체:
```ts
const STATUS_LABEL: Record<Order["status"], string> = {
  ORDERED: "주문 완료",
  SHIPPING: "배송중",
  DELIVERED: "배송완료",
  CANCELLED: "취소됨",
};
```
(취소 버튼은 이미 `order.status === "ORDERED"` 조건이라 변경 불필요 — 배송 시작 시 자동으로 사라진다.)

- [ ] **Step 3: admin/orders/actions.ts — 전이 Server Action 3개**

`frontend/src/app/admin/orders/actions.ts`:
```ts
"use server";

import { revalidatePath } from "next/cache";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, adminCancelOrder, deliverOrder, shipOrder } from "@/lib/api";
import { ACCESS_COOKIE } from "@/lib/auth-cookies";

// 주의: redirect()는 예외를 던지므로 try 블록 안에서 호출하지 않는다.
// 상태 전이 실패(불법 전이 등) 메시지는 ?error= 쿼리로 전달해 페이지가 표시한다.

type Transition = (token: string, orderId: number) => Promise<unknown>;

// 전이 공통 처리 — ship/deliver/cancel이 토큰·401·에러·리다이렉트 흐름을 공유.
// status(현재 필터)를 폼에서 받아 전이 후 같은 필터 화면으로 복귀한다.
async function runTransition(formData: FormData, transition: Transition) {
  const orderId = Number(formData.get("orderId"));
  const status = String(formData.get("status") ?? "");
  const back = status ? `/admin/orders?status=${status}` : "/admin/orders";

  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token) {
    redirect(`/admin/refresh?next=${encodeURIComponent(back)}`);
  }

  let unauthorized = false;
  let errorMessage: string | null = null;
  try {
    await transition(token, orderId);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
    } else {
      errorMessage = err instanceof Error ? err.message : "상태 변경에 실패했습니다.";
    }
  }
  if (unauthorized) {
    redirect(`/admin/refresh?next=${encodeURIComponent(back)}`);
  }
  if (errorMessage !== null) {
    const sep = back.includes("?") ? "&" : "?";
    redirect(`${back}${sep}error=${encodeURIComponent(errorMessage)}`);
  }

  revalidatePath("/admin/orders");
  redirect(back);
}

export async function shipOrderAction(formData: FormData) {
  await runTransition(formData, shipOrder);
}

export async function deliverOrderAction(formData: FormData) {
  await runTransition(formData, deliverOrder);
}

export async function cancelOrderAction(formData: FormData) {
  await runTransition(formData, adminCancelOrder);
}
```

- [ ] **Step 4: admin/orders/page.tsx — 목록 + 필터 + 전이 버튼**

`frontend/src/app/admin/orders/page.tsx`:
```tsx
import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { ApiError, getAdminOrders } from "@/lib/api";
import type { AdminOrder, OrderStatus } from "@/lib/api";
import { ACCESS_COOKIE } from "@/lib/auth-cookies";
import LogoutButton from "../LogoutButton";
import { cancelOrderAction, deliverOrderAction, shipOrderAction } from "./actions";

// 상태 표시용 한국어 라벨
const STATUS_LABEL: Record<OrderStatus, string> = {
  ORDERED: "주문 완료",
  SHIPPING: "배송중",
  DELIVERED: "배송완료",
  CANCELLED: "취소됨",
};

// 상태 필터 탭 (전체 = value 없음)
const FILTERS: { label: string; value?: OrderStatus }[] = [
  { label: "전체" },
  { label: "주문 완료", value: "ORDERED" },
  { label: "배송중", value: "SHIPPING" },
  { label: "배송완료", value: "DELIVERED" },
  { label: "취소됨", value: "CANCELLED" },
];

// 어드민 주문 관리 — proxy가 보호하지만 쿠키 부재 시 이중 방어로 로그인으로 보낸다
export default async function AdminOrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string; error?: string }>;
}) {
  const { status, error } = await searchParams;
  const token = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!token) {
    redirect("/admin/login");
  }

  // status 쿼리를 유효한 OrderStatus로 좁힘 (아니면 전체)
  const validStatus = FILTERS.some((f) => f.value === status)
    ? (status as OrderStatus)
    : undefined;

  let orders: AdminOrder[];
  let unauthorized = false;
  try {
    orders = await getAdminOrders(token, validStatus);
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      unauthorized = true;
      orders = [];
    } else {
      return (
        <main style={{ padding: 24 }}>
          <h1>주문 관리</h1>
          <p>백엔드에 연결할 수 없습니다.</p>
        </main>
      );
    }
  }
  if (unauthorized) {
    const next = validStatus ? `/admin/orders?status=${validStatus}` : "/admin/orders";
    redirect(`/admin/refresh?next=${encodeURIComponent(next)}`);
  }

  return (
    <main style={{ padding: 24 }}>
      <header style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <Link href="/admin">← 어드민</Link>
        <LogoutButton />
      </header>
      <h1>주문 관리</h1>

      <nav style={{ display: "flex", gap: 12, margin: "12px 0" }}>
        {FILTERS.map((f) => (
          <Link key={f.label}
                href={f.value ? `/admin/orders?status=${f.value}` : "/admin/orders"}>
            {f.label}
          </Link>
        ))}
      </nav>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      {orders.length === 0 ? (
        <p>주문이 없습니다.</p>
      ) : (
        <ul style={{ display: "grid", gap: 12, listStyle: "none", padding: 0 }}>
          {orders.map((order) => (
            <li key={order.id}
                style={{ border: "1px solid #ddd", padding: 16, borderRadius: 8 }}>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                <strong>주문 #{order.id} · {STATUS_LABEL[order.status]}</strong>
                <small>{order.customerEmail} · {new Date(order.createdAt).toLocaleString("ko-KR")}</small>
              </div>
              <ul style={{ listStyle: "none", padding: 0, marginTop: 8 }}>
                {order.items.map((item) => (
                  <li key={item.productId}>
                    {item.productName} — {item.price.toLocaleString()}원 × {item.quantity}개 ={" "}
                    {item.lineTotal.toLocaleString()}원
                  </li>
                ))}
              </ul>
              <div style={{ display: "flex", justifyContent: "space-between",
                            alignItems: "center", marginTop: 8 }}>
                <strong>합계: {order.totalPrice.toLocaleString()}원</strong>
                <div style={{ display: "flex", gap: 8 }}>
                  {order.status === "ORDERED" && (
                    <>
                      <form action={shipOrderAction} style={{ margin: 0 }}>
                        <input type="hidden" name="orderId" value={order.id} />
                        <input type="hidden" name="status" value={status ?? ""} />
                        <button type="submit" style={{ cursor: "pointer" }}>배송 시작</button>
                      </form>
                      <form action={cancelOrderAction} style={{ margin: 0 }}>
                        <input type="hidden" name="orderId" value={order.id} />
                        <input type="hidden" name="status" value={status ?? ""} />
                        <button type="submit" style={{ cursor: "pointer" }}>취소</button>
                      </form>
                    </>
                  )}
                  {order.status === "SHIPPING" && (
                    <form action={deliverOrderAction} style={{ margin: 0 }}>
                      <input type="hidden" name="orderId" value={order.id} />
                      <input type="hidden" name="status" value={status ?? ""} />
                      <button type="submit" style={{ cursor: "pointer" }}>배송 완료</button>
                    </form>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
```

- [ ] **Step 5: admin/page.tsx — 주문 관리 링크 추가**

`frontend/src/app/admin/page.tsx`의 `<nav>` 안, 상품 관리 링크 아래에 추가:
```tsx
        <Link href="/admin/orders">주문 관리</Link>
```

- [ ] **Step 6: 프로덕션 빌드 + 린트 검증**

Run: `cd frontend && npm run build && npm run lint`
Expected: PASS — `/admin/orders` 라우트 등록, 타입 에러 없음.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/lib/api.ts \
        frontend/src/app/orders/page.tsx \
        frontend/src/app/admin/orders/actions.ts \
        frontend/src/app/admin/orders/page.tsx \
        frontend/src/app/admin/page.tsx
git commit -m "feat: 어드민 주문 관리 화면·상태 전이 버튼·고객 배송 상태 라벨 추가

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: 문서 동기화

**Files:**
- Modify: `README.md`
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: README 갱신**

`README.md`를 Read로 확인한 뒤:
- 주문 기능 설명에 배송 상태(주문 완료 → 배송중 → 배송완료)와 어드민 주문 관리(전체 주문 조회·상태 필터·배송 시작/완료·취소)를 기존 형식에 맞춰 추가한다. 취소는 ORDERED일 때만 가능(고객·어드민)임을 명시.
- 주요 API 표의 주문 3행 아래에 어드민 주문 4행 추가: `GET /api/admin/orders`(전체·상태 필터), `POST /api/admin/orders/{id}/ship`(배송 시작), `POST /api/admin/orders/{id}/deliver`(배송 완료), `POST /api/admin/orders/{id}/cancel`(취소·재고 복원).
- 실제 문구는 Read로 확인 후 기존 형식에 맞춰 작성한다(추측으로 항목을 만들지 말 것).

- [ ] **Step 2: ROADMAP 갱신**

`docs/ROADMAP.md`에서:
- `> 마지막 갱신:` 행을 `> 마지막 갱신: 2026-06-17 (배송 상태·어드민 주문 관리 사이클)`로 교체.
- 완료된 사이클 표에 행 추가(사이클 10 행 아래):
```markdown
| 11. 배송·어드민 주문 관리 | 2026-06-17 | 주문 배송 상태(ORDERED→SHIPPING→DELIVERED) 상태 머신, 어드민 전체 주문 조회·상태 필터·배송 시작/완료·취소(재고 복원), 취소는 ORDERED만(고객·어드민), 전이 시 Order 행 잠금 | `feature/order-shipping` 브랜치 (머지 대기) |
```
- "후보 5: 주문 후속 — 결제 · 배송 · 어드민 주문 관리" 섹션을 갱신: 배송 상태·어드민 주문 관리는 사이클 11로 완료. 잔여 범위(결제·PG 연동, 반품·교환)는 새 후보(후보 6: 결제)로 명시.

- [ ] **Step 3: 문서 일관성 확인**

Run: `grep -n "배송\|SHIPPING\|DELIVERED\|admin/orders" README.md docs/ROADMAP.md`
Expected: 배송 상태·어드민 주문 관리 서술이 두 문서에서 모순 없이 일치.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/ROADMAP.md
git commit -m "docs: 배송 상태·어드민 주문 관리 사이클 반영(README 기능·ROADMAP 사이클 11)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 최종 검증 (Definition of Done)

- [ ] `cd backend && ./gradlew test` 전체 통과 (AdminOrderControllerTest 10 + OrderControllerTest 보강 1 + 기존 전부)
- [ ] `cd frontend && npm run build && npm run lint` 통과 (`/admin/orders` 라우트 등록)
- [ ] 상태 머신: ORDERED→SHIPPING→DELIVERED 전이, 불법 전이 400(ORDERED 배송완료·SHIPPING 재배송·SHIPPING 취소) (테스트로 고정)
- [ ] 취소는 ORDERED일 때만(고객·어드민), 재고 복원; 배송 시작 후 취소 400·재고 미복원 (테스트로 고정)
- [ ] 어드민 목록에 고객 이메일 포함, 상태 필터 동작 (테스트로 고정)
- [ ] 어드민 전용 보호: 고객 토큰 403, 무토큰 401 (테스트로 고정)
- [ ] 어드민 화면에서 현재 상태에 맞는 전이 버튼만 노출, 필터 탭 동작
- [ ] 고객 주문 목록이 배송중/배송완료 라벨을 표시
- [ ] README/ROADMAP 동기화, 결제는 다음 사이클(후보 6)로 명시
