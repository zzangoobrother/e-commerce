package com.ecommerce.order;

import com.ecommerce.common.BadRequestException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 주문 애그리거트 — 항목은 주문과 함께 생성·조회되고 개별 수정이 없어 cascade로 묶는다
// (장바구니 CartItem이 플랫인 것과 대조적 — 항목 독립 추가·삭제 유무의 차이).
// 테이블명 orders: "order"는 SQL 예약어.
@Entity
@Table(name = "orders",
        indexes = @Index(name = "idx_orders_customer", columnList = "customer_id"))
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 고객 참조 — Customer 엔티티 로딩이 불필요해 스칼라로 보관 (CartItem과 동일 패턴)
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    // 주문 시점 합계 스냅샷 — 이후 상품 가격이 변해도 불변
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalPrice;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ALL + orphanRemoval: 항목의 생명주기가 주문에 완전히 종속 (저장·삭제 함께)
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
    }

    public Order(Long customerId) {
        this.customerId = customerId;
        this.status = OrderStatus.ORDERED;
        this.totalPrice = BigDecimal.ZERO;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // 항목 추가 — 합계를 함께 갱신해 totalPrice가 항상 항목 합과 일치
    public void addItem(Long productId, String productName, BigDecimal price, int quantity) {
        items.add(new OrderItem(this, productId, productName, price, quantity));
        totalPrice = totalPrice.add(price.multiply(BigDecimal.valueOf(quantity)));
    }

    public void cancel() {
        if (status == OrderStatus.CANCELLED) {
            throw new BadRequestException("이미 취소된 주문입니다.");
        }
        this.status = OrderStatus.CANCELLED;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalPrice() { return totalPrice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    // 항목의 외부 변경(remove/clear 등)으로 totalPrice 불변식이 깨지지 않도록 읽기 전용 뷰로 반환
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
}
