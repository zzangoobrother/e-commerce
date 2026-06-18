package com.ecommerce.order;

import com.ecommerce.auth.Customer;
import com.ecommerce.auth.CustomerRepository;
import com.ecommerce.cart.CartItem;
import com.ecommerce.cart.CartItemRepository;
import com.ecommerce.common.BadRequestException;
import com.ecommerce.common.NotFoundException;
import com.ecommerce.order.dto.AdminOrderResponse;
import com.ecommerce.order.dto.CreateOrderResponse;
import com.ecommerce.order.dto.CreateOrderResponse.ExcludedItemResponse;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.product.Product;
import com.ecommerce.product.ProductRepository;
import com.ecommerce.product.ProductStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 주문 서비스 — 생성(부분 주문)/목록/취소(재고 복원).
// 재고 변경 구간은 Product를 PESSIMISTIC_WRITE로 잠그되 항상 productId 오름차순(데드락 예방).
@Service
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;

    public OrderService(OrderRepository orderRepository,
                        CartItemRepository cartItemRepository,
                        ProductRepository productRepository,
                        CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.customerRepository = customerRepository;
    }

    public List<OrderResponse> getOrders(Long customerId) {
        return orderRepository.findAllByCustomerIdOrderByIdDesc(customerId)
                .stream().map(OrderResponse::from).toList();
    }

    // 주문 생성 — 장바구니 전체를 전환하되 구매 가능한 항목만(부분 주문).
    // 제외 항목은 사유와 함께 반환하고 장바구니에 남긴다. 전부 불가면 400(예외 → 롤백 → 차감 없음).
    @Transactional
    public CreateOrderResponse createOrder(Long customerId) {
        List<CartItem> cartItems = cartItemRepository.findAllByCustomerId(customerId);
        if (cartItems.isEmpty()) {
            throw new BadRequestException("장바구니가 비어 있습니다.");
        }

        // 잠금은 항상 productId 오름차순 — 모든 트랜잭션이 같은 순서로 잠가 데드락을 예방한다
        List<Long> productIds = cartItems.stream()
                .map(item -> item.getProduct().getId()).sorted().toList();
        Map<Long, Product> lockedById = productRepository.findAllForUpdate(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Order order = new Order(customerId);
        List<ExcludedItemResponse> excluded = new ArrayList<>();
        List<Long> orderedProductIds = new ArrayList<>();

        for (CartItem item : cartItems) {
            Product product = lockedById.get(item.getProduct().getId());
            if (product == null) {
                // 조회와 잠금 사이에 상품이 삭제된 경우 — 제외 처리
                excluded.add(new ExcludedItemResponse(
                        item.getProduct().getId(), "알 수 없는 상품", "상품이 존재하지 않습니다."));
                continue;
            }
            if (product.getStatus() != ProductStatus.ON_SALE) {
                excluded.add(new ExcludedItemResponse(
                        product.getId(), product.getName(), "판매 중인 상품이 아닙니다."));
                continue;
            }
            if (item.getQuantity() > product.getStockQuantity()) {
                excluded.add(new ExcludedItemResponse(product.getId(), product.getName(),
                        "재고가 부족합니다. (재고: " + product.getStockQuantity() + "개)"));
                continue;
            }
            product.decreaseStock(item.getQuantity());
            order.addItem(product.getId(), product.getName(), product.getPrice(), item.getQuantity());
            orderedProductIds.add(product.getId());
        }

        if (orderedProductIds.isEmpty()) {
            // 예외로 트랜잭션이 롤백되므로 위에서의 차감은 반영되지 않는다
            throw new BadRequestException("주문 가능한 상품이 없습니다: " + summarize(excluded));
        }

        orderRepository.save(order);
        // 주문된 항목만 장바구니에서 제거 — 제외 항목은 고객이 직접 처리하도록 남긴다
        cartItemRepository.deleteByCustomerIdAndProductIdIn(customerId, orderedProductIds);
        return new CreateOrderResponse(OrderResponse.from(order), excluded);
    }

    // 취소 — 상태 전이 + 재고 복원. 삭제된 상품은 잠금 조회에 빠지므로 자연히 복원 스킵.
    @Transactional
    public OrderResponse cancelOrder(Long customerId, Long orderId) {
        // 동시 이중 취소 방지 — Order 행을 잠근 뒤 상태를 검사·전이한다.
        // items는 같은 트랜잭션 내에서 lazy 초기화되어 안전하게 접근된다.
        Order order = orderRepository.findByIdAndCustomerIdForUpdate(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다: " + orderId));
        order.cancel(); // 이미 취소된 주문이면 400
        restoreStock(order);
        return OrderResponse.from(order);
    }

    // === 어드민 ===

    // 전체 주문 목록(상태 필터 옵션) — 고객 이메일은 배치 조회로 enrich(N+1 회피)
    public List<AdminOrderResponse> getAllOrders(OrderStatus statusFilter) {
        List<Order> orders = (statusFilter == null)
                ? orderRepository.findAllByOrderByIdDesc()
                : orderRepository.findAllByStatusOrderByIdDesc(statusFilter);
        List<Long> customerIds = orders.stream().map(Order::getCustomerId).distinct().toList();
        Map<Long, String> emailById = customerRepository.findAllById(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getEmail));
        return orders.stream()
                .map(o -> AdminOrderResponse.from(o, emailById.getOrDefault(o.getCustomerId(), "(삭제된 고객)")))
                .toList();
    }

    @Transactional
    public AdminOrderResponse shipOrder(Long orderId) {
        Order order = lockOrder(orderId);
        order.ship();
        return adminResponse(order);
    }

    @Transactional
    public AdminOrderResponse deliverOrder(Long orderId) {
        Order order = lockOrder(orderId);
        order.deliver();
        return adminResponse(order);
    }

    @Transactional
    public AdminOrderResponse cancelOrderByAdmin(Long orderId) {
        Order order = lockOrder(orderId);
        order.cancel();
        restoreStock(order);
        return adminResponse(order);
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new NotFoundException("주문을 찾을 수 없습니다: " + orderId));
    }

    private AdminOrderResponse adminResponse(Order order) {
        String email = customerRepository.findById(order.getCustomerId())
                .map(Customer::getEmail).orElse("(삭제된 고객)");
        return AdminOrderResponse.from(order, email);
    }

    // 취소 시 재고 복원 — 잠금은 productId 오름차순(데드락 예방). 삭제된 상품은 잠금 조회에 빠져 자연 스킵.
    private void restoreStock(Order order) {
        List<Long> productIds = order.getItems().stream()
                .map(OrderItem::getProductId).sorted().toList();
        Map<Long, Integer> quantityByProductId = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
        for (Product product : productRepository.findAllForUpdate(productIds)) {
            product.increaseStock(quantityByProductId.get(product.getId()));
        }
    }

    private String summarize(List<ExcludedItemResponse> excluded) {
        return excluded.stream()
                .map(e -> e.productName() + " — " + e.reason())
                .collect(Collectors.joining(", "));
    }
}
