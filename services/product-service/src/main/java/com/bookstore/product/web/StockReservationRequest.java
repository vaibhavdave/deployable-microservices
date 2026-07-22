package com.bookstore.product.web;

import jakarta.validation.constraints.Min;

public record StockReservationRequest(@Min(1) int quantity) {
}
