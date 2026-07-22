package com.bookstore.product.service;

import com.bookstore.product.domain.Product;
import com.bookstore.product.domain.ProductNotFoundException;
import com.bookstore.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Transactional
    public Product create(String name, String description, java.math.BigDecimal price, int stockQuantity) {
        Product product = new Product(name, description, price, stockQuantity);
        return productRepository.save(product);
    }

    /**
     * Optimistic locking (@Version on Product) makes this safe under concurrent
     * reservations for the same product; a lost-update retries at the caller (order-service)
     * via its own resilience policy rather than being masked here.
     */
    @Transactional
    public Product reserveStock(Long id, int quantity) {
        Product product = findById(id);
        product.reserve(quantity);
        return productRepository.save(product);
    }
}
