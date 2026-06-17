package com.ecommerce.cart;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.product.ProductStatus;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import tools.jackson.databind.ObjectMapper;
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
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CartControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;

    @AfterEach
    void cleanup() {
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

    private Product product(String name, int stock, ProductStatus status) {
        Supplier supplier = supplierRepository.save(new Supplier("공급사A", "a@example.com"));
        return productRepository.save(new Product(supplier, name, "설명",
                new BigDecimal("3000"), stock, status));
    }

    private String addBody(Long productId, int quantity) throws Exception {
        return objectMapper.writeValueAsString(Map.of("productId", productId, "quantity", quantity));
    }

    @Test
    void 담으면_201과_장바구니를_반환한다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 10, ProductStatus.ON_SALE);

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(apple.getId()))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(6000))
                .andExpect(jsonPath("$.totalPrice").value(6000));
    }

    @Test
    void 같은_상품을_다시_담으면_수량이_가산된다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 10, ProductStatus.ON_SALE);

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5));
    }

    @Test
    void 가산_결과가_재고를_초과하면_400을_반환한다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 5, ProductStatus.ON_SALE);

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 4)))
                .andExpect(status().isCreated());

        // 4 + 2 = 6 > 재고 5
        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 판매중이_아닌_상품은_담을_수_없다() throws Exception {
        customer("user@example.com");
        Product hidden = product("숨김상품", 10, ProductStatus.HIDDEN);

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(hidden.getId(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 존재하지_않는_상품을_담으면_404를_반환한다() throws Exception {
        customer("user@example.com");

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(999999L, 1)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 수량_0으로_담으면_400을_반환한다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 10, ProductStatus.ON_SALE);

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 조회하면_항목과_합계를_반환한다() throws Exception {
        customer("user@example.com");
        Supplier supplier = supplierRepository.save(new Supplier("공급사B", "b@example.com"));
        Product apple = productRepository.save(new Product(supplier, "사과", "설명",
                new BigDecimal("3000"), 10, ProductStatus.ON_SALE));
        Product pear = productRepository.save(new Product(supplier, "배", "설명",
                new BigDecimal("5000"), 10, ProductStatus.ON_SALE));

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(pear.getId(), 1)))
                .andExpect(status().isCreated());

        // 3000×2 + 5000×1 = 11000
        mockMvc.perform(get("/api/store/cart").with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPrice").value(11000));
    }

    @Test
    void 수량을_변경하면_절대값으로_설정된다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 10, ProductStatus.ON_SALE);
        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isCreated());

        String body = objectMapper.writeValueAsString(Map.of("quantity", 7));
        mockMvc.perform(patch("/api/store/cart/items/" + apple.getId())
                        .with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(7));
    }

    @Test
    void 수량_변경이_재고를_초과하면_400을_반환한다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 5, ProductStatus.ON_SALE);
        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isCreated());

        String body = objectMapper.writeValueAsString(Map.of("quantity", 6));
        mockMvc.perform(patch("/api/store/cart/items/" + apple.getId())
                        .with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 장바구니에_없는_상품의_수량_변경은_404를_반환한다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 10, ProductStatus.ON_SALE);

        String body = objectMapper.writeValueAsString(Map.of("quantity", 3));
        mockMvc.perform(patch("/api/store/cart/items/" + apple.getId())
                        .with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void 삭제하면_204_미존재여도_멱등하다() throws Exception {
        customer("user@example.com");
        Product apple = product("사과", 10, ProductStatus.ON_SALE);
        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/store/cart/items/" + apple.getId())
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isNoContent());

        // 같은 삭제 재요청도 204 (멱등)
        mockMvc.perform(delete("/api/store/cart/items/" + apple.getId())
                        .with(customerJwt("user@example.com")))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/store/cart").with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void 인증_없이_접근하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/store/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 어드민_토큰으로_접근하면_403을_반환한다() throws Exception {
        mockMvc.perform(get("/api/store/cart")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 다른_고객의_장바구니는_보이지_않는다() throws Exception {
        customer("a@example.com");
        customer("b@example.com");
        Product apple = product("사과", 10, ProductStatus.ON_SALE);

        mockMvc.perform(post("/api/store/cart/items").with(customerJwt("a@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody(apple.getId(), 2)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/store/cart").with(customerJwt("b@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
