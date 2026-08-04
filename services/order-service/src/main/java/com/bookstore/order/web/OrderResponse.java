package com.bookstore.order.web;

import com.bookstore.order.domain.Order;
import com.bookstore.order.domain.OrderStatus;

import java.time.Instant;

public record OrderResponse(Long id, Long productId, int quantity, OrderStatus status, Instant createdAt) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getProductId(), order.getQuantity(), order.getStatus(), order.getCreatedAt());
    }
}
