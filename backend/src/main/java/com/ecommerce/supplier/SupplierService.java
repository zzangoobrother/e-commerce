package com.ecommerce.supplier;

import com.ecommerce.common.NotFoundException;
import com.ecommerce.supplier.dto.SupplierRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class SupplierService {

    private final SupplierRepository supplierRepository;

    public SupplierService(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    public List<Supplier> findAll() {
        return supplierRepository.findAll();
    }

    public Supplier findById(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("공급사를 찾을 수 없습니다: " + id));
    }

    @Transactional
    public Supplier create(SupplierRequest request) {
        Supplier supplier = new Supplier(request.name(), request.contactEmail());
        supplier.update(request.name(), request.contactEmail(), request.status());
        return supplierRepository.save(supplier);
    }

    @Transactional
    public Supplier update(Long id, SupplierRequest request) {
        Supplier supplier = findById(id);
        supplier.update(request.name(), request.contactEmail(), request.status());
        return supplier;
    }

    @Transactional
    public void delete(Long id) {
        if (!supplierRepository.existsById(id)) {
            throw new NotFoundException("공급사를 찾을 수 없습니다: " + id);
        }
        supplierRepository.deleteById(id);
    }
}
