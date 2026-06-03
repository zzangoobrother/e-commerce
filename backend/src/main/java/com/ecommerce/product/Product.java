package com.ecommerce.product;

import com.ecommerce.supplier.Supplier;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 공급사 N:1 — 지연 로딩
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stockQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Product() {
    }

    public Product(Supplier supplier, String name, String description,
                   BigDecimal price, int stockQuantity) {
        this(supplier, name, description, price, stockQuantity, ProductStatus.ON_SALE);
    }

    // 상태를 명시해 생성 (어드민 생성 요청 등)
    public Product(Supplier supplier, String name, String description,
                   BigDecimal price, int stockQuantity, ProductStatus status) {
        this.supplier = supplier;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void update(String name, String description, BigDecimal price,
                       int stockQuantity, ProductStatus status) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.stockQuantity = stockQuantity;
        this.status = status;
    }

    public void changeSupplier(Supplier supplier) {
        this.supplier = supplier;
    }

    public Long getId() { return id; }
    public Supplier getSupplier() { return supplier; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public int getStockQuantity() { return stockQuantity; }
    public ProductStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
