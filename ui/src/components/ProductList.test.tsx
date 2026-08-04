import { render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { server } from "../test/mswServer";
import { ProductList } from "./ProductList";

const API_BASE_URL = "http://localhost:8080/api";

describe("ProductList", () => {
  it("renders products returned by the gateway", async () => {
    server.use(
      http.get(`${API_BASE_URL}/products`, () =>
        HttpResponse.json([
          { id: 1, name: "Effective Java", description: "", price: 45, stockQuantity: 3 },
        ]),
      ),
    );

    render(<ProductList refreshToken={0} onSelectProduct={vi.fn()} />);

    expect(await screen.findByText(/Effective Java/)).toBeInTheDocument();
    expect(screen.getByText(/3 in stock/)).toBeInTheDocument();
  });

  it("shows an empty state when there are no products", async () => {
    server.use(http.get(`${API_BASE_URL}/products`, () => HttpResponse.json([])));

    render(<ProductList refreshToken={0} onSelectProduct={vi.fn()} />);

    expect(await screen.findByText(/No products available/)).toBeInTheDocument();
  });

  it("shows an error message when the gateway call fails", async () => {
    server.use(
      http.get(`${API_BASE_URL}/products`, () =>
        HttpResponse.json({ message: "downstream unavailable" }, { status: 503 }),
      ),
    );

    render(<ProductList refreshToken={0} onSelectProduct={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("downstream unavailable");
    });
  });

  it("disables ordering for out-of-stock products", async () => {
    server.use(
      http.get(`${API_BASE_URL}/products`, () =>
        HttpResponse.json([
          { id: 2, name: "Sold Out Book", description: "", price: 10, stockQuantity: 0 },
        ]),
      ),
    );

    render(<ProductList refreshToken={0} onSelectProduct={vi.fn()} />);

    const button = await screen.findByRole("button", { name: "Order" });
    expect(button).toBeDisabled();
  });
});
