import { useState } from "react";
import "./App.css";
import { OrderForm } from "./components/OrderForm";
import { ProductList } from "./components/ProductList";
import type { Product } from "./api/types";

function App() {
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);

  return (
    <main className="app">
      <h1>Bookstore</h1>

      <ProductList refreshToken={refreshToken} onSelectProduct={setSelectedProduct} />

      {selectedProduct && (
        <OrderForm
          product={selectedProduct}
          onCancel={() => setSelectedProduct(null)}
          onOrderPlaced={() => {
            setSelectedProduct(null);
            setRefreshToken((token) => token + 1);
          }}
        />
      )}
    </main>
  );
}

export default App;
