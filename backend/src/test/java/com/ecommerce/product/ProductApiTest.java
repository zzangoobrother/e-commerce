package com.ecommerce.product;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.cart.CartItem;
import com.ecommerce.cart.CartItemRepository;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductApiTest {

    @Autowired ProductRepository productRepository;
    @Autowired CartItemRepository cartItemRepository;
    @Autowired CustomerRepository customerRepository;

    @AfterEach
    void cleanup() {
        // 각 테스트 후 데이터 정리 — 다른 테스트와의 격리 (cart_items가 products FK를 가져 먼저 삭제)
        cartItemRepository.deleteAll();
        productRepository.deleteAll();
        supplierRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SupplierRepository supplierRepository;

    @Test
    void 어드민이_상품을_생성하고_공급사별로_조회한다() throws Exception {
        Supplier supplier = supplierRepository.save(
                new Supplier("공급사A", "a@example.com"));

        String body = objectMapper.writeValueAsString(Map.of(
                "supplierId", supplier.getId(),
                "name", "사과",
                "description", "맛있는 사과",
                "price", 3000,
                "stockQuantity", 10,
                "status", "ON_SALE"));

        mockMvc.perform(post("/api/admin/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("사과"))
                .andExpect(jsonPath("$.supplierId").value(supplier.getId()))
                .andExpect(jsonPath("$.supplierName").value("공급사A"));

        // 공급사별 조회
        mockMvc.perform(get("/api/admin/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .param("supplierId", String.valueOf(supplier.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("사과"));
    }

    @Test
    void 스토어에서_판매중_상품_목록을_조회한다() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }

    @Test
    void 숨김_상태로_상품을_생성하면_요청한_상태가_반영된다() throws Exception {
        Supplier supplier = supplierRepository.save(
                new Supplier("공급사C", "c@example.com"));

        String body = objectMapper.writeValueAsString(Map.of(
                "supplierId", supplier.getId(),
                "name", "비공개 상품",
                "description", "아직 공개 전",
                "price", 1000,
                "stockQuantity", 5,
                "status", "HIDDEN"));

        mockMvc.perform(post("/api/admin/products").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HIDDEN"));
    }

    @Test
    void 장바구니에_담긴_상품을_삭제하면_장바구니에서도_제거된다() throws Exception {
        Supplier supplier = supplierRepository.save(new Supplier("공급사D", "d@example.com"));
        Product product = productRepository.save(new Product(
                supplier, "삭제될상품", "설명", new BigDecimal("1000"), 5, ProductStatus.ON_SALE));
        Customer customer = customerRepository.save(new Customer("user@example.com", "encoded-password"));
        cartItemRepository.save(new CartItem(customer.getId(), product, 2));

        // 사이클 9 인계: 이전에는 cart_items FK 위반으로 부정확한 409가 났다 — 이제 204
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());

        assertThat(cartItemRepository.findAllByCustomerId(customer.getId())).isEmpty();
    }
}
