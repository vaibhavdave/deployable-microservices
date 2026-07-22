import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "../test/mswServer";
import { OrderForm } from "./OrderForm";
import type { Product } from "../api/types";

const API_BASE_URL = "http://localhost:8080/api";

const product: Product = { id: 1, name: "Effective Java", description: "", price: 45, stockQuantity: 5 };

describe("OrderForm", () => {
  it("calls onOrderPlaced and shows confirmation on success", async () => {
    server.use(
      http.post(`${API_BASE_URL}/orders`, () =>
        HttpResponse.json({ id: 1, productId: 1, quantity: 2, status: "CONFIRMED", createdAt: "" }, { status: 201 }),
      ),
    );

    const onOrderPlaced = vi.fn();
    render(<OrderForm product={product} onOrderPlaced={onOrderPlaced} onCancel={vi.fn()} />);

    await userEvent.click(screen.getByRole("button", { name: /place order/i }));

    await waitFor(() => expect(onOrderPlaced).toHaveBeenCalled());
    expect(await screen.findByRole("status")).toHaveTextContent("Order placed for Effective Java");
  });

  it("shows an error and does not call onOrderPlaced when stock is unavailable", async () => {
    server.use(
      http.post(`${API_BASE_URL}/orders`, () =>
        HttpResponse.json({ message: "Insufficient stock for product 1" }, { status: 409 }),
      ),
    );

    const onOrderPlaced = vi.fn();
    render(<OrderForm product={product} onOrderPlaced={onOrderPlaced} onCancel={vi.fn()} />);

    await userEvent.click(screen.getByRole("button", { name: /place order/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Insufficient stock for product 1");
    expect(onOrderPlaced).not.toHaveBeenCalled();
  });

  it("calls onCancel when cancel is clicked", async () => {
    const onCancel = vi.fn();
    render(<OrderForm product={product} onOrderPlaced={vi.fn()} onCancel={onCancel} />);

    await userEvent.click(screen.getByRole("button", { name: /cancel/i }));

    expect(onCancel).toHaveBeenCalled();
  });
});
