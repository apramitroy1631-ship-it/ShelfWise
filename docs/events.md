# Event contract (core service → notification service)

The two services share no code (DL-08), so this document is the contract. The core service writes each event to
the `outbox_events` table **in the same transaction** as the change it describes (transactional outbox, DL-03).
A relay in the core service publishes unpublished rows to RabbitMQ every 2 seconds and marks them published (M21).

## Delivery

- **Exchange:** `shelfwise.events` (topic, durable). **Routing key:** the event type, e.g. `PurchaseOrderDecided`.
- **Message properties:** `message_id` = event id, `type` = event type, `content_type` = `application/json`, persistent.
- **At-least-once:** an event can arrive more than once (e.g. the relay crashed after sending but before marking it
  published). Consumers skip an event id they have already handled.
- **Order:** not guaranteed between relays. Snapshot events carry `asOf`; consumers keep only the newest state.
- **Notification service queue:** `notification-service.events` (durable, bound with `#`). Messages that still fail
  after 3 retries go to `notification-service.dead` via the `shelfwise.events.dead` exchange.

## Message body

```json
{
  "id": "6f1c…",                     // uuid, unique per event
  "type": "PurchaseOrderDecided",
  "aggregateType": "PURCHASE_ORDER",
  "aggregateId": "PO-10003",
  "occurredAt": "2026-11-11T04:50:18.828562Z",
  "payload": { … }                   // per type, below
}
```

## Purchase orders (`aggregateType` = `PURCHASE_ORDER`, `aggregateId` = PO number)

Common payload fields: `poId`, `storeId`, `supplierId`, `createdBy`, `totalAmount`, `status` (status after the change).

| `type` | When | Extra payload fields | Used by the notification service |
|---|---|---|---|
| `PurchaseOrderSubmitted` | A draft is submitted | `submittedBy` | No (finance counts come from `ApprovalQueueChanged`) |
| `PurchaseOrderDecided` | Finance approves or rejects | `decision` (`APPROVED`/`REJECTED`), `decidedBy`, `reason` (empty if none), `reapproval` (bool), `validApprovals`, `approvalsNeeded` | Yes: final outcomes become "PO-0195 was approved" for the store's managers (D7) |
| `PurchaseOrderReceived` | Goods are recorded | `receivedBy`, `qtyOrdered`, `qtyReceived` | No |

## Snapshots (current counts for grouped notifications, D7)

These start as **markers** in the outbox ("this changed"). The relay replaces each with the **current** situation at
publish time and sends only one per store (or one for finance) per batch, however many markers piled up. Because a
snapshot carries the whole count, applying it twice or late can't make the number wrong.

| `type` | `aggregateId` | Written when | Payload |
|---|---|---|---|
| `StockHealthChanged` | store id | By a **database trigger** whenever an item crosses in stock / running low / out of stock (orders, cancellations, adjustments, recounts, PO receipts); one per store at core start-up | `storeId`, `outOfStock: {count, latest}`, `runningLow: {count, latest}`, `asOf`. Archived products don't count (D3). `latest` = the product name most recently changed in that group |
| `ApprovalQueueChanged` | `FINANCE` | On PO submit and decision; once at core start-up | `needsApproval: {count, latest, oldestSince}`, `needsSecondApproval: {count, latest, oldestSince}`, `asOf`. Waiting = submitted, or flagged for re-approval (D17-D19). Second = has one valid approval and is above the remote-config limit. `latest` = the oldest waiting PO |

"Low" = at or below the reorder level; "out of stock" = 0 (D7). Each item is in one group only.

Planned with M23 (nightly jobs): stuck orders (D32) and stuck POs (D22) groups for managers, daily admin summaries.
