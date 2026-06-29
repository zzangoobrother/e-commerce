package com.ecommerce.address;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerAddressControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomerRepository customerRepository;
    @Autowired CustomerAddressRepository addressRepository;

    @AfterEach
    void cleanup() {
        // FK 역순: 배송지 먼저, 그다음 고객
        addressRepository.deleteAll();
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

    // 표준 배송지 JSON 본문 — label/isDefault만 가변, 나머지는 고정값
    private String addressJson(String label, boolean isDefault) {
        return """
                {"label":"%s","recipientName":"홍길동","phone":"010-1234-5678","zipCode":"12345","address1":"서울시 강남구","address2":"101동 202호","isDefault":%b}
                """.formatted(label, isDefault);
    }

    // 등록 후 생성된 배송지 id 반환 — set-default/delete/update/소유권 테스트에서 사용
    private long register(String email, String label, boolean isDefault) throws Exception {
        String body = mockMvc.perform(post("/api/store/addresses").with(customerJwt(email))
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson(label, isDefault)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void 첫_배송지를_등록하면_201과_자동_기본배송지() throws Exception {
        customer("user@example.com");

        // isDefault=false로 보내도 첫 주소이므로 기본배송지가 된다
        mockMvc.perform(post("/api/store/addresses").with(customerJwt("user@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(addressJson("집", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.label").value("집"))
                .andExpect(jsonPath("$.recipientName").value("홍길동"))
                .andExpect(jsonPath("$.isDefault").value(true))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void 목록은_기본배송지가_먼저_이후_최신순() throws Exception {
        customer("user@example.com");
        register("user@example.com", "집", false);      // 첫 등록 → 기본
        register("user@example.com", "회사", false);     // 둘째
        register("user@example.com", "부모님댁", false);  // 셋째(최신)

        // 기대 순서: 집(기본) → 부모님댁(최신) → 회사
        mockMvc.perform(get("/api/store/addresses").with(customerJwt("user@example.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].label").value("집"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].label").value("부모님댁"))
                .andExpect(jsonPath("$[2].label").value("회사"));
    }
}
