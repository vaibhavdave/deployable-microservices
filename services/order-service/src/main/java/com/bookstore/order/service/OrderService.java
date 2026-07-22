package com.bookstore.order.service;

import com.bookstore.order.client.ProductClientException;
import com.bookstore.order.client.ProductServiceClient;
import com.bookstore.order.domain.Order;
import com.bookstore.order.domain.OrderNotFoundException;
import com.bookstore.order.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductServiceClient productServiceClient;

    public OrderService(OrderRepository orderRepository, ProductServiceClient productServiceClient) {
        this.orderRepository = orderRepository;
        this.productServiceClient = productServiceClient;
    }

    @Transactional(readOnly = true)
    public List<Order> findAll() {
        return orderRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Order findById(Long id) {
        return orderRepository.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    /**
     * Each database write is its own short transaction (the default for a single
     * CrudRepository.save call) rather than one transaction spanning the whole method —
     * the call to product-service is a network round-trip with its own retry/circuit-breaker
     * policy, and holding a DB connection open for its duration would starve the pool under
     * concurrent load exactly when the system is already under stress.
     */
    public Order create(Long productId, int quantity) {
        Order order = orderRepository.save(new Order(productId, quantity));

        try {
            productServiceClient.reserveStock(productId, quantity);
            order.markConfirmed();
        } catch (ProductClientException ex) {
            order.markRejected();
            orderRepository.save(order);
            throw ex;
        }

        return orderRepository.save(order);
    }
}
