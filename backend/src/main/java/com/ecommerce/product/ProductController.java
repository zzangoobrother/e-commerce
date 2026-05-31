package com.ecommerce.product;

import com.ecommerce.product.dto.ProductResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 스토어프론트용 (공개)
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return productService.findOnSale().stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(productService.findById(id));
    }
}
