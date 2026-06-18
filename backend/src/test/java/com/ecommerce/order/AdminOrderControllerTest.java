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

    // status()는 MockMvcResultMatchers.status()와 충돌하므로 statusOf로 명명
    private String statusOf(Long orderId) {
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

        assertThat(statusOf(order.getId())).isEqualTo("SHIPPING");
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
