package com.ecommerce.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

// 주문 항목 — 상품명·단가는 주문 시점 스냅샷.
// productId는 FK 없는 스칼라: 주문은 영구 이력이라 상품 삭제에 막히면 안 되고,
// 표시용 데이터는 스냅샷이 가진다 (장바구니는 임시 데이터라 FK + 삭제 전파 — 수명에 따른 참조 전략 분리).
@Entity
@Table(name = "order_items",
        indexes = @Index(name = "idx_order_items_order", columnList = "order_id"))
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {
    }

    // Order.addItem 전용 — 외부에서 직접 생성하지 않는다
    OrderItem(Order order, Long productId, String productName, BigDecimal price, int quantity) {
        this.order = order;
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.quantity = quantity;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getPrice() { return price; }
    public int getQuantity() { return quantity; }
}
