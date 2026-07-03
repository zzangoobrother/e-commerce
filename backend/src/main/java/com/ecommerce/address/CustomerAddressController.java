package com.ecommerce.address;

import com.ecommerce.address.dto.AddressResponse;
import com.ecommerce.address.dto.CreateAddressRequest;
import com.ecommerce.address.dto.UpdateAddressRequest;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.common.UnauthorizedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 배송지 주소록 API — /api/store/addresses/**는 SecurityConfig에서 hasRole('CUSTOMER')로 보호.
// 고객 식별: 고객 access JWT의 subject(email) → Customer 조회 (OrderController와 동일 패턴).
@RestController
@RequestMapping("/api/store/addresses")
public class CustomerAddressController {

    private final CustomerAddressService service;
    private final CustomerRepository customerRepository;

    public CustomerAddressController(CustomerAddressService service,
                                     CustomerRepository customerRepository) {
        this.service = service;
        this.customerRepository = customerRepository;
    }

    @GetMapping
    public List<AddressResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.getAddresses(customerId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@AuthenticationPrincipal Jwt jwt,
                                  @RequestBody @Valid CreateAddressRequest req) {
        return service.create(customerId(jwt), req);
    }

    @PutMapping("/{id}")
    public AddressResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                  @RequestBody @Valid UpdateAddressRequest req) {
        return service.update(customerId(jwt), id, req);
    }

    @PostMapping("/{id}/default")
    public AddressResponse setDefault(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return service.setDefault(customerId(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.delete(customerId(jwt), id);
    }

    // 토큰은 유효하지만 고객 행이 없는 경우(탈퇴 등) 401
    private Long customerId(Jwt jwt) {
        return customerRepository.findByEmail(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("고객 정보를 찾을 수 없습니다."))
                .getId();
    }
}
