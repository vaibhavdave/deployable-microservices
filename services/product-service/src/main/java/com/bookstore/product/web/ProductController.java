package com.bookstore.product.web;

import com.bookstore.product.domain.Product;
import com.bookstore.product.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductResponse> getAll() {
        return productService.findAll().stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable Long id) {
        return ProductResponse.from(productService.findById(id));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        Product created = productService.create(request.name(), request.description(), request.price(), request.stockQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(created));
    }

    @PatchMapping("/{id}/reserve")
    public ProductResponse reserveStock(@PathVariable Long id, @Valid @RequestBody StockReservationRequest request) {
        return ProductResponse.from(productService.reserveStock(id, request.quantity()));
    }
}
