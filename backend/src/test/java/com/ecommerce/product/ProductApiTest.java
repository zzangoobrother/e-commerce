package com.ecommerce.product;

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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductApiTest {

    @Autowired ProductRepository productRepository;

    @AfterEach
    void cleanup() {
        // 각 테스트 후 데이터 정리 — 다른 테스트와의 격리
        productRepository.deleteAll();
        supplierRepository.deleteAll();
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

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("사과"))
                .andExpect(jsonPath("$.supplierId").value(supplier.getId()))
                .andExpect(jsonPath("$.supplierName").value("공급사A"));

        // 공급사별 조회
        mockMvc.perform(get("/api/admin/products")
                        .param("supplierId", String.valueOf(supplier.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("사과"));
    }

    @Test
    void 스토어에서_판매중_상품_목록을_조회한다() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());
    }
}
