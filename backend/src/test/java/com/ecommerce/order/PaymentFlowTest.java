package com.ecommerce.order;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.cart.CartItem;
import com.ecommerce.cart.CartItemRepository;
import com.ecommerce.payment.Payment;
import com.ecommerce.payment.PaymentRepository;
import com.ecommerce.payment.PaymentStatus;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentFlowTest {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;

    private int supplierSeq = 0;

    // 테스트 카드 — 승인(VISA), 거절(끝 0002), 형식오류(Luhn 불통과)
    private static final String APPROVED_CARD =
            "{\"cardNumber\":\"4242424242424242\",\"expiryMonth\":12,\"expiryYear\":2999,\"cvc\":\"123\",\"cardholderName\":\"HONG\"}";
    private static final String DECLINED_CARD =
            "{\"cardNumber\":\"4000000000000002\",\"expiryMonth\":12,\"expiryYear\":2999,\"cvc\":\"123\",\"cardholderName\":\"HONG\"}";
    private static final String INVALID_CARD =
            "{\"cardNumber\":\"4242424242424241\",\"expiryMonth\":12,\"expiryYear\":2999,\"cvc\":\"123\",\"cardholderName\":\"HONG\"}";

    @AfterEach
    void cleanup() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        productRepository.deleteAll();
        supplierRepository.deleteAll();
        customerRepository.deleteAll();
    }

    private RequestPostProcessor customerJwt(String email) {
        return jwt().jwt(j -> j.subject(email))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private RequestPostProcessor adminJwt() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private Customer customer(String email) {
        return customerRepository.save(new Customer(email, "encoded-password"));
    }

    private Product product(String name, String price, int stock) {
        Supplier supplier = supplierRepository.save(
                new Supplier("공급사" + (++supplierSeq), "s" + supplierSeq + "@example.com"));
        return productRepository.save(new Product(supplier, name, "설명",
                new BigDecimal(price), stock, ProductStatus.ON_SALE));
    }

    private void putInCart(Customer customer, Product product, int quantity) {
        cartItemRepository.save(new CartItem(customer.getId(), product, quantity));
    }

    private int stockOf(Product product) {
        return productRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    @Test
    void 승인_카드로_주문하면_결제가_기록되고_재고가_차감된다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10);
        putInCart(customer, apple, 2);

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVED_CARD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order.status").value("ORDERED"))
                .andExpect(jsonPath("$.order.payment.status").value("PAID"))
                .andExpect(jsonPath("$.order.payment.cardBrand").value("VISA"))
                .andExpect(jsonPath("$.order.payment.cardLast4").value("4242"))
                .andExpect(jsonPath("$.order.payment.amount").value(6000));

        assertThat(paymentRepository.findAll()).hasSize(1)
                .first().satisfies(p -> assertThat(p.getStatus()).isEqualTo(PaymentStatus.PAID));
        assertThat(stockOf(apple)).isEqualTo(8);
        assertThat(cartItemRepository.findAllByCustomerId(customer.getId())).isEmpty();
    }

    @Test
    void 거절_카드로_주문하면_402_주문도_결제도_없고_재고가_원복된다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10);
        putInCart(customer, apple, 2);

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(DECLINED_CARD))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.message").exists());

        assertThat(orderRepository.findAll()).isEmpty();
        assertThat(paymentRepository.findAll()).isEmpty();
        assertThat(stockOf(apple)).isEqualTo(10); // 롤백 → 차감 원복
        assertThat(cartItemRepository.findAllByCustomerId(customer.getId())).hasSize(1); // 장바구니 유지
    }

    @Test
    void 형식오류_카드로_주문하면_400_주문도_결제도_없다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10);
        putInCart(customer, apple, 2);

        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(INVALID_CARD))
                .andExpect(status().isBadRequest());

        assertThat(orderRepository.findAll()).isEmpty();
        assertThat(paymentRepository.findAll()).isEmpty();
        assertThat(stockOf(apple)).isEqualTo(10);
    }

    @Test
    void 고객_취소는_환불하고_재고를_복원한다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10);
        putInCart(customer, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVED_CARD))
                .andExpect(status().isCreated());
        Long orderId = orderRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/store/orders/" + orderId + "/cancel")
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"));

        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(stockOf(apple)).isEqualTo(10);
    }

    @Test
    void 어드민_취소도_환불하고_재고를_복원한다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10);
        putInCart(customer, apple, 2);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVED_CARD))
                .andExpect(status().isCreated());
        Long orderId = orderRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/cancel").with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"));

        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(stockOf(apple)).isEqualTo(10);
    }

    @Test
    void 주문_목록은_결제_정보를_포함한다() throws Exception {
        Customer customer = customer("user@example.com");
        Product apple = product("사과", "3000", 10);
        putInCart(customer, apple, 1);
        mockMvc.perform(post("/api/store/orders").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(APPROVED_CARD))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/store/orders").with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payment.cardLast4").value("4242"))
                .andExpect(jsonPath("$[0].payment.status").value("PAID"));
    }
}
