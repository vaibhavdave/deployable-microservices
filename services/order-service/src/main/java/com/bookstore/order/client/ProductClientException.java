package com.bookstore.order.client;

/**
 * Raised for any non-2xx / unreachable outcome from product-service, after
 * Resilience4j retry/circuit-breaker have already had their chance.
 */
public class ProductClientException extends RuntimeException {

    private final boolean stockConflict;

    public ProductClientException(String message, boolean stockConflict, Throwable cause) {
        super(message, cause);
        this.stockConflict = stockConflict;
    }

    public boolean isStockConflict() {
        return stockConflict;
    }
}
