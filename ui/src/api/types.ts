export interface Product {
  id: number;
  name: string;
  description: string;
  price: number;
  stockQuantity: number;
}

export interface Order {
  id: number;
  productId: number;
  quantity: number;
  status: "PENDING" | "CONFIRMED" | "REJECTED";
  createdAt: string;
}

export interface ApiError {
  status: number;
  message: string;
}
