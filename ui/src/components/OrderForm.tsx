import { useState } from "react";
import { placeOrder } from "../api/client";
import type { ApiError, Product } from "../api/types";

interface OrderFormProps {
  product: Product;
  onOrderPlaced: () => void;
  onCancel: () => void;
}

export function OrderForm({ product, onOrderPlaced, onCancel }: OrderFormProps) {
  const [quantity, setQuantity] = useState(1);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await placeOrder(product.id, quantity);
      setSuccess(true);
      onOrderPlaced();
    } catch (err) {
      const apiError = err as ApiError;
      setError(apiError.message ?? "Failed to place order");
    } finally {
      setSubmitting(false);
    }
  }

  if (success) {
    return <p role="status">Order placed for {product.name}.</p>;
  }

  return (
    <form onSubmit={handleSubmit} className="order-form">
      <h3>Order {product.name}</h3>
      <label>
        Quantity
        <input
          type="number"
          min={1}
          max={product.stockQuantity}
          value={quantity}
          onChange={(e) => setQuantity(Number(e.target.value))}
          required
        />
      </label>
      {error && <p role="alert">{error}</p>}
      <div className="order-form__actions">
        <button type="submit" disabled={submitting}>
          {submitting ? "Placing order…" : "Place order"}
        </button>
        <button type="button" onClick={onCancel} disabled={submitting}>
          Cancel
        </button>
      </div>
    </form>
  );
}
