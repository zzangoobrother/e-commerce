package com.ecommerce.product;

import com.ecommerce.cart.CartItemRepository;
import com.ecommerce.common.NotFoundException;
import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.supplier.Supplier;
import com.ecommerce.supplier.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final CartItemRepository cartItemRepository;

    public ProductService(ProductRepository productRepository,
                          SupplierRepository supplierRepository,
                          CartItemRepository cartItemRepository) {
        this.productRepository = productRepository;
        this.supplierRepository = supplierRepository;
        this.cartItemRepository = cartItemRepository;
    }

    // 스토어: 판매중 상품만
    public List<Product> findOnSale() {
        return productRepository.findByStatus(ProductStatus.ON_SALE);
    }

    public Product findById(Long id) {
        return productRepository.findWithSupplierById(id)
                .orElseThrow(() -> new NotFoundException("상품을 찾을 수 없습니다: " + id));
    }

    // 어드민: 전체 또는 공급사별
    public List<Product> findForAdmin(Long supplierId) {
        if (supplierId == null) {
            return productRepository.findAll();
        }
        return productRepository.findBySupplierId(supplierId);
    }

    @Transactional
    public Product create(ProductRequest request) {
        Supplier supplier = loadSupplier(request.supplierId());
        Product product = new Product(supplier, request.name(), request.description(),
                request.price(), request.stockQuantity(), request.status());
        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, ProductRequest request) {
        Product product = findById(id);
        if (!product.getSupplier().getId().equals(request.supplierId())) {
            product.changeSupplier(loadSupplier(request.supplierId()));
        }
        product.update(request.name(), request.description(), request.price(),
                request.stockQuantity(), request.status());
        return product;
    }

    @Transactional
    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw new NotFoundException("상품을 찾을 수 없습니다: " + id);
        }
        // 장바구니는 임시 데이터라 상품과 함께 제거 — FK 위반(부정확한 409) 방지.
        // 주문 이력(order_items)은 FK 없는 스냅샷이라 영향 없다.
        cartItemRepository.deleteByProductId(id);
        productRepository.deleteById(id);
    }

    private Supplier loadSupplier(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new NotFoundException("공급사를 찾을 수 없습니다: " + supplierId));
    }
}
