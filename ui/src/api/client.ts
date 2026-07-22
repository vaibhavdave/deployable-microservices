import type { ApiError, Order, Product } from "./types";

declare global {
  interface Window {
    __ENV__?: { API_BASE_URL?: string };
  }
}

// Runtime config (window.__ENV__, injected by docker-entrypoint.sh from an env var at
// container start) takes precedence over the build-time Vite env var, so one built image
// can be promoted across dev/staging/prod instead of rebuilding per environment.
const API_BASE_URL =
  window.__ENV__?.API_BASE_URL ?? import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });

  if (!response.ok) {
    let message = response.statusText;
    try {
      const body = await response.json();
      message = body.message ?? message;
    } catch {
      // response had no JSON body; fall back to statusText
    }
    const error: ApiError = { status: response.status, message };
    throw error;
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

export function fetchProducts(): Promise<Product[]> {
  return request<Product[]>("/products");
}

export function placeOrder(productId: number, quantity: number): Promise<Order> {
  return request<Order>("/orders", {
    method: "POST",
    body: JSON.stringify({ productId, quantity }),
  });
}
