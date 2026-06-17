package com.ecommerce.order;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.cart.CartItem;
import com.ecommerce.cart.CartItemRepository;
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
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;

    // 테스트 내 supplier 고유명 생성용 카운터 — JUnit5 인스턴스당 0에서 시작
    private int supplierSeq = 0;

    @AfterEach
    void cleanup() {
        // FK 역순: order_items는 orders cascade로, cart_items는 products보다 먼저
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        productRepository.deleteAll();
        supplierRepository.deleteAll();
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

    // 호출마다 고유한 공급사를 생성해 name unique 제약 위반 방지
    private Product product(String name, String price, int stock, ProductStatus status) {
        String supplierName = "공급사" + (++supplierSeq);
        Supplier supplier = supplierRepository.save(new Supplier(supplierName, "supplier" + supplierSeq + "@example.com"));
        return productRepository.save(new Product(supplier, name, "설명",
                new BigDecimal(price), stock, status));
    }

    private void putInCart(Customer customer, Product product, int quantity) {
        cartItemRepository.save(new CartItem(customer.getId(), product, quantity));
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    @Test
    void 주문하면_201_스냅샷과_합계_재고차감_장바구니비움() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(customer, apple, 2);

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("ORDERED"))
                .andExpect(jsonPath("$.order.items.length()").value(1))
                .andExpect(jsonPath("$.order.items[0].productName").value("사과"))
                .andExpect(jsonPath("$.order.items[0].price").value(3000))
                .andExpect(jsonPath("$.order.items[0].quantity").value(2))
                .andExpect(jsonPath("$.order.items[0].lineTotal").value(6000))
                .andExpect(jsonPath("$.order.totalPrice").value(6000))
                .andExpect(jsonPath("$.excludedItems.length()").value(0));

        assertThat(stockOf(apple)).isEqualTo(8);
        assertThat(cartItemRepository.findAllByCustomerId(customer.getId())).isEmpty();
    }

    @Test
    void 가격_스냅샷은_주문_후_가격_변경에_불변이다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(customer, apple, 2);

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated());

        // 주문 후 가격 인상 — 주문 금액은 스냅샷이라 그대로여야 한다
        Product reloaded = productRepository.findById(apple.getId()).orElseThrow();
        reloaded.update(reloaded.getName(), reloaded.getDescription(),
                new BigDecimal("9999"), reloaded.getStockQuantity(), reloaded.getStatus());
        productRepository.save(reloaded);

        mockMvc.perform(get("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].items[0].price").value(3000))
                .andExpect(jsonPath("$[0].totalPrice").value(6000));
    }

    @Test
    void 판매중지_항목은_제외하고_나머지만_주문한다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        Product hidden = product("숨김상품", "5000", 10, ProductStatus.HIDDEN);
        putInCart(customer, apple, 2);
        putInCart(customer, hidden, 1);

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.items.length()").value(1))
                .andExpect(jsonPath("$.order.items[0].productName").value("사과"))
                .andExpect(jsonPath("$.excludedItems.length()").value(1))
                .andExpect(jsonPath("$.excludedItems[0].productName").value("숨김상품"))
                .andExpect(jsonPath("$.excludedItems[0].reason").exists());

        // 제외 항목은 장바구니에 남고, 주문된 항목만 제거된다
        assertThat(cartItemRepository.findAllByCustomerId(customer.getId()))
                .hasSize(1)
                .allSatisfy(item -> assertThat(item.getProduct().getId()).isEqualTo(hidden.getId()));
        // 제외 항목 재고는 차감되지 않는다
        assertThat(stockOf(hidden)).isEqualTo(10);
    }

    @Test
    void 재고부족_항목은_제외되고_재고가_차감되지_않는다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        Product pear = product("배", "5000", 1, ProductStatus.ON_SALE);
        putInCart(customer, apple, 2);
        putInCart(customer, pear, 5); // 5 > 재고 1

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.items.length()").value(1))
                .andExpect(jsonPath("$.excludedItems[0].productName").value("배"));

        assertThat(stockOf(pear)).isEqualTo(1);
    }

    @Test
    void 전부_구매불가면_400_주문과_차감이_없다() throws Exception {
        Customer customer = customer("user@example.com");
        Product hidden = product("숨김상품", "5000", 10, ProductStatus.HIDDEN);
        putInCart(customer, hidden, 1);

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());

        assertThat(orderRepository.findAll()).isEmpty();
        assertThat(stockOf(hidden)).isEqualTo(10);
        assertThat(cartItemRepository.findAllByCustomerId(customer.getId())).hasSize(1);
    }

    @Test
    void 빈_장바구니로_주문하면_400을_반환한다() throws Exception {
        customer("user@example.com");

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 취소하면_상태가_변경되고_재고가_복원된다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(customer, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated());
        Long orderId = orderRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/store/orders/" + orderId + "/cancel")
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(stockOf(apple)).isEqualTo(10);
    }

    @Test
    void 이미_취소된_주문을_다시_취소하면_400을_반환한다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(customer, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated());
        Long orderId = orderRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/store/orders/" + orderId + "/cancel")
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/store/orders/" + orderId + "/cancel")
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isBadRequest());

        // 중복 취소가 재고를 이중 복원하지 않는다
        assertThat(stockOf(apple)).isEqualTo(10);
    }

    @Test
    void 타인의_주문은_취소할_수_없다_404() throws Exception {
        Customer owner = customer("a@example.com");
        customer("b@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(owner, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("a@example.com")))
                .andExpect(status().isCreated());
        Long orderId = orderRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/store/orders/" + orderId + "/cancel")
                        .with(customerJwt("b@example.com")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 삭제된_상품이_포함된_주문도_취소된다_복원은_스킵() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(customer, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated());
        Long orderId = orderRepository.findAll().get(0).getId();

        // 주문 후 상품 삭제 — order_items는 FK 없는 스냅샷이라 삭제 가능
        productRepository.deleteById(apple.getId());

        mockMvc.perform(post("/api/store/orders/" + orderId + "/cancel")
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void 목록은_최신_주문부터_항목을_포함해_반환한다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(customer, apple, 1);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated());
        putInCart(customer, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].items[0].quantity").value(2)) // 최신(두 번째 주문) 먼저
                .andExpect(jsonPath("$[1].items[0].quantity").value(1));
    }

    @Test
    void 인증_없이_접근하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/store/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 어드민_토큰으로_접근하면_403을_반환한다() throws Exception {
        mockMvc.perform(get("/api/store/orders")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_고객의_주문은_보이지_않는다() throws Exception {
        Customer a = customer("a@example.com");
        customer("b@example.com");
        Product apple = product("사과", "3000", 10, ProductStatus.ON_SALE);
        putInCart(a, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("a@example.com")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/store/orders").with(customerJwt("b@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
