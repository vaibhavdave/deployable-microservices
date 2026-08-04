import { useEffect, useState } from "react";
import { fetchProducts } from "../api/client";
import type { ApiError, Product } from "../api/types";

interface ProductListProps {
  onSelectProduct: (product: Product) => void;
  refreshToken: number;
}

export function ProductList({ onSelectProduct, refreshToken }: ProductListProps) {
  const [products, setProducts] = useState<Product[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetchProducts()
      .then((data) => {
        if (!cancelled) {
          setProducts(data);
          setError(null);
        }
      })
      .catch((err: ApiError) => {
        if (!cancelled) {
          setError(err.message ?? "Failed to load products");
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [refreshToken]);

  if (loading) {
    return <p>Loading products…</p>;
  }

  if (error) {
    return <p role="alert">Error loading products: {error}</p>;
  }

  if (products.length === 0) {
    return <p>No products available.</p>;
  }

  return (
    <ul className="product-list">
      {products.map((product) => (
        <li key={product.id} className="product-list__item">
          <div>
            <strong>{product.name}</strong> — ${product.price.toFixed(2)} ({product.stockQuantity} in stock)
          </div>
          <button
            type="button"
            disabled={product.stockQuantity === 0}
            onClick={() => onSelectProduct(product)}
          >
            Order
          </button>
        </li>
      ))}
    </ul>
  );
}
