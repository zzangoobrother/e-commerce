package com.ecommerce.supplier;

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
class SupplierControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired SupplierRepository supplierRepository;

    @AfterEach
    void cleanup() {
        // 각 테스트 후 데이터 정리 — 다른 테스트와의 격리
        supplierRepository.deleteAll();
    }

    @Test
    void 공급사를_생성하고_목록에서_조회한다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "신선식품", "contactEmail", "fresh@example.com",
                       "status", "ACTIVE"));

        mockMvc.perform(post("/api/admin/suppliers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("신선식품"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/admin/suppliers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("신선식품"));
    }

    @Test
    void 비활성_상태로_공급사를_생성하면_요청한_상태가_반영된다() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("name", "휴면공급사", "contactEmail", "rest@example.com",
                       "status", "INACTIVE"));

        mockMvc.perform(post("/api/admin/suppliers")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }
}
