package com.bookstore.order.web;

import com.bookstore.order.domain.Order;
import com.bookstore.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public List<OrderResponse> getAll() {
        return orderService.findAll().stream().map(OrderResponse::from).toList();
    }

    @GetMapping("/{id}")
    public OrderResponse getById(@PathVariable Long id) {
        return OrderResponse.from(orderService.findById(id));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequest request) {
        Order order = orderService.create(request.productId(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }
}
