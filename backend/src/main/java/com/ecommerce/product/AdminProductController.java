package com.ecommerce.product;

import com.ecommerce.product.dto.ProductRequest;
import com.ecommerce.product.dto.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 어드민용 — 공급사별 상품 관리
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) Long supplierId) {
        return productService.findForAdmin(supplierId).stream()
                .map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(productService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse body = ProductResponse.from(productService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(productService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
