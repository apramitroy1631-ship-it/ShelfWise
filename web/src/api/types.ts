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
  actorName: string | null;
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

export type OrderDetail = Omit<OrderSummary, never> & { placedBy: string; placedByName: string; source: string; lines: OrderLine[] };

export type PoStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'PARTIALLY_RECEIVED' | 'RECEIVED' | 'CLOSED';

export type PoSummary = {
  id: string;
  storeId: string;
  supplierId: string;
  status: PoStatus;
  totalAmount: number;
  createdAt: string;
  validApprovals: number;
  approvalsNeeded: number;
  complianceFlag: string | null;
  requiresReapproval: boolean;
  stuck: boolean;
  version: number;
};

export type PoLine = { sku: string; productName: string | null; qtyOrdered: number; qtyReceived: number; unitCost: number; lineTotal: number };

export type PoApproval = { approverId: string; approverName: string; decision: 'APPROVED' | 'REJECTED'; decidedAt: string; counts: boolean; step: number | null };

export type PoDetail = { summary: PoSummary; createdBy: string; createdByName: string; closedReason: string | null; lines: PoLine[]; approvals: PoApproval[] };

/** A supplier invoice file on a PO (M20). Newest first; exactly one is current, older ones are kept. */
export type Invoice = {
  id: string; fileName: string; contentType: 'application/pdf' | 'image/png' | 'image/jpeg'; sizeBytes: number;
  current: boolean; uploadedBy: string; uploadedByName: string; uploadedAt: string;
};

export type PoSuggestion = { sku: string; productName: string; supplierId: string; quantity: number; reorderLevel: number; suggestedQty: number; unitCost: number };

// Dashboard (M22). Revenue = delivered orders only; dates are IST days.
export type TrendPoint = { period: string; storeId: string; revenue: number; orders: number };

export type DashboardSummary = {
  storeId: string | null;
  from: string | null;
  to: string | null;
  revenue: number;
  deliveredOrders: number;
  orderCount: number;
  ordersByStatus: Record<string, number>;
  lowStockCount: number;
  outOfStockCount: number;
  topProducts: { sku: string; name: string; units: number; revenue: number }[];
  unknownProducts: { units: number; revenue: number };
  trendBy: 'day' | 'month';
  trend: TrendPoint[];
};

// Served by the notification service (DL-12), grouped the D7 way.
export type NotificationKind = 'OUT_OF_STOCK' | 'RUNNING_LOW' | 'NEEDS_APPROVAL' | 'NEEDS_SECOND_APPROVAL' | 'PO_DECISION';

export type NotificationItem = {
  id: number;
  kind: NotificationKind;
  text: string;
  count: number | null;
  ref: string | null;
  unread: boolean;
  at: string;
};

export type NotificationFeed = { unreadCount: number; items: NotificationItem[] };
