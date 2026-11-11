// Shapes returned by the core service (see the README's API sections). Own copy: no code shared with web (DL-08).

export type Role = 'ADMIN' | 'STORE_MANAGER' | 'FINANCE_APPROVER';

export type UserSummary = { id: string; name: string; role: Role; storeId: string | null };

export type Session = { accessToken: string; refreshToken: string; expiresInSeconds: number; user: UserSummary };

export type Page<T> = { items: T[]; page: number; size: number; totalItems: number; totalPages: number };

export type Product = {
  sku: string;
  name: string;
  category: string;
  priceInr: number | null;
  supplierId: string;
  status: 'ACTIVE' | 'ARCHIVED';
  reorderLevel: number;
  sellable: boolean;
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
