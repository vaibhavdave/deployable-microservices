package com.bookstore.order.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(@NotNull Long productId, @Min(1) int quantity) {
}
