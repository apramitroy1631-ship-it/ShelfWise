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

export type OrderDetail = OrderSummary & { placedBy: string; source: string; lines: OrderLine[] };

// Served by the notification service (DL-12), grouped the D7 way.
export type NotificationKind = 'OUT_OF_STOCK' | 'RUNNING_LOW' | 'NEEDS_APPROVAL' | 'NEEDS_SECOND_APPROVAL' | 'PO_DECISION'
  | 'STUCK_ORDERS' | 'STUCK_POS' | 'NEEDS_REVIEW';

export type NotificationItem = { id: number; kind: NotificationKind; text: string; count: number | null; ref: string | null; unread: boolean; at: string };

export type NotificationFeed = { unreadCount: number; items: NotificationItem[] };

export type PoStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'PARTIALLY_RECEIVED' | 'RECEIVED' | 'EXPIRED' | 'CLOSED';

export type PoSummary = {
  id: string; storeId: string; supplierId: string; status: PoStatus; totalAmount: number; createdAt: string; stuck: boolean; version: number;
};

export type PoLine = { sku: string; productName: string | null; qtyOrdered: number; qtyReceived: number; unitCost: number; lineTotal: number };

export type PoDetail = { summary: PoSummary; createdByName: string | null; closedReason: string | null; lines: PoLine[] };

/** A supplier invoice file on a PO (M20). Newest first; exactly one is current, older ones are kept. */
export type Invoice = {
  id: string; fileName: string; contentType: 'application/pdf' | 'image/png' | 'image/jpeg'; sizeBytes: number;
  current: boolean; uploadedBy: string; uploadedByName: string; uploadedAt: string;
};
