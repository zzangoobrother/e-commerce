package com.ecommerce.address;

import com.ecommerce.address.dto.AddressResponse;
import com.ecommerce.address.dto.CreateAddressRequest;
import com.ecommerce.address.dto.UpdateAddressRequest;
import com.ecommerce.common.BadRequestException;
import com.ecommerce.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 배송지 주소록 — 기본배송지 불변식(항상 0/1개)·상한(10개)·소유권을 책임진다.
@Service
@Transactional(readOnly = true)
public class CustomerAddressService {

    // 고객당 배송지 상한
    static final int MAX_ADDRESSES = 10;

    private final CustomerAddressRepository repository;

    public CustomerAddressService(CustomerAddressRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AddressResponse create(Long customerId, CreateAddressRequest req) {
        long count = repository.countByCustomerId(customerId);
        if (count >= MAX_ADDRESSES) {
            throw new BadRequestException("배송지는 최대 " + MAX_ADDRESSES + "개까지 등록할 수 있습니다.");
        }
        // 첫 주소이거나 명시적으로 기본 요청 시 기본배송지로 — 기존 기본은 해제
        boolean makeDefault = req.isDefault() || count == 0;
        if (makeDefault) {
            clearDefault(customerId);
        }
        CustomerAddress saved = repository.save(new CustomerAddress(
                customerId, req.label(), req.recipientName(), req.phone(),
                req.zipCode(), req.address1(), req.address2(), makeDefault));
        return AddressResponse.from(saved);
    }

    public List<AddressResponse> getAddresses(Long customerId) {
        return repository.findByCustomerIdOrderByIsDefaultDescCreatedAtDesc(customerId)
                .stream().map(AddressResponse::from).toList();
    }

    @Transactional
    public AddressResponse setDefault(Long customerId, Long id) {
        CustomerAddress target = repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("배송지를 찾을 수 없습니다."));
        clearDefault(customerId);     // 기존 기본 해제
        target.markDefault(true);     // 대상 기본 지정 — 한 트랜잭션 안에서 불변식 유지
        return AddressResponse.from(target);
    }

    @Transactional
    public AddressResponse update(Long customerId, Long id, UpdateAddressRequest req) {
        CustomerAddress target = repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("배송지를 찾을 수 없습니다."));
        target.update(req.label(), req.recipientName(), req.phone(),
                req.zipCode(), req.address1(), req.address2());
        return AddressResponse.from(target);
    }

    @Transactional
    public void delete(Long customerId, Long id) {
        CustomerAddress target = repository.findByIdAndCustomerId(id, customerId)
                .orElseThrow(() -> new NotFoundException("배송지를 찾을 수 없습니다."));
        boolean wasDefault = target.isDefault();
        repository.delete(target);
        if (wasDefault) {
            // 삭제 행을 제외하고 조회하려면 먼저 flush — 그 후 남은 최신을 기본으로 승격
            repository.flush();
            repository.findFirstByCustomerIdOrderByCreatedAtDesc(customerId)
                    .ifPresent(a -> a.markDefault(true));
        }
    }

    // 현재 기본배송지가 있으면 해제(dirty checking으로 flush). 불변식 유지의 핵심 헬퍼.
    private void clearDefault(Long customerId) {
        repository.findByCustomerIdAndIsDefaultTrue(customerId)
                .ifPresent(a -> a.markDefault(false));
    }
}
