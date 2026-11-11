# Event contract (core service → notification service)

The two services share no code (DL-08), so this document is the contract. The core service writes each event to
the `outbox_events` table **in the same transaction** as the change it describes (transactional outbox, DL-03).
A relay publishes unpublished rows to RabbitMQ and marks them published (M21). Consumers must accept the same
event more than once (at-least-once delivery) and use the event `id` to ignore repeats.

Every event row has: `id` (uuid), `aggregate_type`, `aggregate_id`, `event_type`, `payload` (JSON), `created_at`.

## Purchase orders (`aggregate_type` = `PURCHASE_ORDER`, `aggregate_id` = PO number)

Common payload fields: `poId`, `storeId`, `supplierId`, `createdBy`, `totalAmount`, `status` (status after the change).

| `event_type` | When | Extra payload fields | Who is told (D7) |
|---|---|---|---|
| `PurchaseOrderSubmitted` | A draft is submitted | `submittedBy` | Finance approvers (grouped: "N purchase orders waiting") |
| `PurchaseOrderDecided` | Finance approves or rejects | `decision` (`APPROVED`/`REJECTED`), `decidedBy`, `reason` (empty if none), `reapproval` (bool), `validApprovals`, `approvalsNeeded` | The store's managers; if `validApprovals < approvalsNeeded`, the other finance approvers ("needs a second approval") |
| `PurchaseOrderReceived` | Goods are recorded | `receivedBy`, `qtyOrdered`, `qtyReceived` | The store's managers (only if useful; not required by the brief) |

Planned with M21/M23: `LowStockDetected` (nightly recompute and on stock changes).
