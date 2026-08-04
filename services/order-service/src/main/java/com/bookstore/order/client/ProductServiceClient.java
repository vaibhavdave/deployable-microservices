package com.bookstore.order.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

@Component
public class ProductServiceClient {

    private final WebClient webClient;

    public ProductServiceClient(WebClient productServiceWebClient) {
        this.webClient = productServiceWebClient;
    }

    @Retry(name = "productService")
    @CircuitBreaker(name = "productService", fallbackMethod = "reserveStockFallback")
    public ProductDto reserveStock(Long productId, int quantity) {
        try {
            return webClient.patch()
                    .uri("/products/{id}/reserve", productId)
                    .bodyValue(new StockReservationRequestDto(quantity))
                    .retrieve()
                    .bodyToMono(ProductDto.class)
                    .block(Duration.ofSeconds(3));
        } catch (WebClientResponseException.Conflict ex) {
            throw new ProductClientException("Insufficient stock for product " + productId, true, ex);
        } catch (WebClientResponseException.NotFound ex) {
            throw new ProductClientException("Product not found: " + productId, false, ex);
        }
    }

    @SuppressWarnings("unused")
    private ProductDto reserveStockFallback(Long productId, int quantity, Throwable throwable) {
        if (throwable instanceof ProductClientException pce) {
            throw pce;
        }
        throw new ProductClientException(
                "product-service unavailable while reserving stock for product " + productId, false, throwable);
    }
}
