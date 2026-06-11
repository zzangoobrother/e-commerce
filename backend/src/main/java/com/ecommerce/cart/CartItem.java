package com.ecommerce.cart;

import com.ecommerce.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

// 장바구니 항목 — Cart aggregate 없이 (customer_id, product_id)당 1행, 재담기는 수량 가산.
// 재고는 차감하지 않는다(상한 검증만) — 차감·가격 고정은 주문 생성(사이클 10)의 책임.
@Entity
@Table(name = "cart_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_cart_customer_product",
                columnNames = {"customer_id", "product_id"}),
        indexes = @Index(name = "idx_cart_customer", columnList = "customer_id"))
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 고객 참조 — Customer 엔티티 로딩이 불필요해 스칼라로 보관
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    // 응답에 상품명·가격이 필요해 연관 매핑 (지연 로딩)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected CartItem() {
    }

    public CartItem(Long customerId, Product product, int quantity) {
        this.customerId = customerId;
        this.product = product;
        this.quantity = quantity;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
