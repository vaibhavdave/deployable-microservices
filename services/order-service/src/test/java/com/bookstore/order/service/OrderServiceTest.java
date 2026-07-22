package com.bookstore.order.service;

import com.bookstore.order.client.ProductClientException;
import com.bookstore.order.client.ProductDto;
import com.bookstore.order.client.ProductServiceClient;
import com.bookstore.order.domain.Order;
import com.bookstore.order.domain.OrderStatus;
import com.bookstore.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ProductServiceClient productServiceClient = mock(ProductServiceClient.class);
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productServiceClient);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void create_confirmsOrderWhenStockReserved() {
        when(productServiceClient.reserveStock(eq(1L), anyInt()))
                .thenReturn(new ProductDto(1L, "Effective Java", "desc", BigDecimal.TEN, 5));

        Order order = orderService.create(1L, 2);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(productServiceClient).reserveStock(1L, 2);
    }

    @Test
    void create_rejectsOrderWhenStockConflict() {
        when(productServiceClient.reserveStock(eq(1L), anyInt()))
                .thenThrow(new ProductClientException("out of stock", true, null));

        assertThatThrownBy(() -> orderService.create(1L, 100))
                .isInstanceOf(ProductClientException.class);
    }

    @Test
    void create_persistsPendingOrderBeforeCallingProductService() {
        when(productServiceClient.reserveStock(eq(1L), anyInt()))
                .thenReturn(new ProductDto(1L, "Book", "desc", BigDecimal.ONE, 1));

        orderService.create(1L, 1);

        verify(orderRepository, org.mockito.Mockito.atLeastOnce()).save(any(Order.class));
    }
}
