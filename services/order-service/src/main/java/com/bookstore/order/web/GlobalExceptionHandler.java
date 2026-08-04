package com.bookstore.order.web;

import com.bookstore.order.client.ProductClientException;
import com.bookstore.order.domain.OrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(OrderNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ProductClientException.class)
    public ResponseEntity<ErrorResponse> handleProductClient(ProductClientException ex) {
        HttpStatus status = ex.isStockConflict() ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
        return build(status, ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message));
    }
}
