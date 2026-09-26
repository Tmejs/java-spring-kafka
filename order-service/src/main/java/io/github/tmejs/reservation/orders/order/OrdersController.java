package io.github.tmejs.reservation.orders.order;

import io.github.tmejs.reservation.api.orders.OrdersApi;
import io.github.tmejs.reservation.api.orders.model.CreateOrderRequest;
import io.github.tmejs.reservation.api.orders.model.Order;
import io.github.tmejs.reservation.api.orders.model.OrderItem;
import io.github.tmejs.reservation.api.orders.model.OrderStatus;
import io.github.tmejs.reservation.api.orders.model.RejectionReason;
import io.github.tmejs.reservation.orders.order.OrderService.CreateOrderCommand;
import io.github.tmejs.reservation.orders.order.OrderService.Line;
import io.github.tmejs.reservation.orders.order.OrderService.OrderView;
import io.github.tmejs.reservation.orders.security.CurrentOwner;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrdersController implements OrdersApi {

    private final OrderService orderService;
    private final CurrentOwner currentOwner;

    OrdersController(OrderService orderService, CurrentOwner currentOwner) {
        this.orderService = orderService;
        this.currentOwner = currentOwner;
    }

    @Override
    public ResponseEntity<Order> createOrder(String idempotencyKey, CreateOrderRequest request) {
        var command = new CreateOrderCommand(request.getItems().stream()
                .map(item -> new Line(item.getProductId(), item.getQuantity()))
                .toList());
        var result = orderService.create(currentOwner.subject(), idempotencyKey, command);
        return ResponseEntity.accepted()
                .location(URI.create(result.location()))
                .body(toApi(result.response()));
    }

    @Override
    public ResponseEntity<Order> getOrder(UUID id) {
        return ResponseEntity.ok(toApi(orderService.get(currentOwner.subject(), id)));
    }

    private static Order toApi(OrderView view) {
        var items = new LinkedHashSet<OrderItem>();
        view.items().forEach(line -> items.add(new OrderItem(line.productId(), line.quantity())));
        var response = new Order(view.id(), items, OrderStatus.fromValue(view.status()));
        if (view.rejectionReason() != null) {
            response.setRejectionReason(RejectionReason.fromValue(view.rejectionReason()));
        }
        return response;
    }
}
