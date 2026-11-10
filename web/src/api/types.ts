// Shapes returned by the core service (see the README's API sections).

export type Page<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };

export type Product = {
  sku: string;
  name: string;
  category: string;
  priceInr: number | null;
  costPriceInr: number;
  gstRate: number;
  supplierId: string;
  status: 'ACTIVE' | 'ARCHIVED';
  reorderLevel: number;
  cataloguedOn: string;
  sellable: boolean;
  version: number;
};

export type StockItem = {
  storeId: string;
  sku: string;
  productName: string;
  category: string;
  productStatus: string;
  priceInr: number | null;
  quantity: number;
  reorderLevel: number;
  low: boolean;
  recountNeeded: boolean;
  lastCountedAt: string | null;
  version: number;
};

export type Movement = {
  id: number;
  delta: number;
  quantityAfter: number;
  reason: string;
  note: string | null;
  referenceType: string | null;
  referenceId: string | null;
  actorUserId: string | null;
  at: string;
};

export type OrderStatus = 'PENDING' | 'PACKED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';

export type OrderSummary = {
  id: string;
  storeId: string;
  status: OrderStatus;
  customerName: string;
  customerPhone: string;
  totalAmount: number;
  createdAt: string;
  reviewStatus: string | null;
  stuck: boolean;
  version: number;
};

export type OrderLine = { sku: string; productSku: string | null; productName: string | null; qty: number; unitPrice: number; lineTotal: number };

export type OrderDetail = Omit<OrderSummary, never> & { placedBy: string; source: string; lines: OrderLine[] };
