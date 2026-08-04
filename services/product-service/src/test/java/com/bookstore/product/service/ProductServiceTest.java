package com.bookstore.product.service;

import com.bookstore.product.domain.InsufficientStockException;
import com.bookstore.product.domain.Product;
import com.bookstore.product.domain.ProductNotFoundException;
import com.bookstore.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository);
    }

    @Test
    void reserveStock_decrementsExactAvailableQuantity() {
        Product product = new Product("Effective Java", "Joshua Bloch", BigDecimal.valueOf(45.00), 10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product result = productService.reserveStock(1L, 10);

        assertThat(result.getStockQuantity()).isZero();
    }

    @Test
    void reserveStock_throwsWhenRequestedExceedsAvailable() {
        Product product = new Product("Effective Java", "Joshua Bloch", BigDecimal.valueOf(45.00), 3);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.reserveStock(1L, 4))
                .isInstanceOf(InsufficientStockException.class);

        verify(productRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void reserveStock_throwsWhenProductMissing() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.reserveStock(99L, 1))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void create_persistsNewProduct() {
        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        when(productRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        productService.create("Clean Code", "Robert Martin", BigDecimal.valueOf(38.50), 25);

        assertThat(captor.getValue().getName()).isEqualTo("Clean Code");
        assertThat(captor.getValue().getStockQuantity()).isEqualTo(25);
    }
}
