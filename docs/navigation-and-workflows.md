# Navigation and workflows

*Decided 2026-11-11.* How people move through the web and mobile apps, and the steps of each workflow. The look (colours, chips, layouts) is in the [design system](design-system.md); this document is about **where things are and in what order they happen**.

---

## 1. Decisions

| # | Question | Decision |
|---|---|---|
| N1 | Where does each role land after sign-in? | **By role.** Admin → Dashboard (Orders until the dashboard exists). Store manager → Orders. Finance → Purchase orders, filtered to *Awaiting approval*. |
| N2 | What does Finance see in the menu? | **A focused menu:** Purchase orders, Dashboard, Suppliers, plus Inventory **read-only** (to judge whether a PO is needed). Orders and Catalogue are hidden. |
| N3 | How does a record open from a list? | **By size.** Quick records (an order, a stock item) open in a **side panel**, so the list stays visible. Purchase orders open as a **full page** (tracker, lines, receiving, invoice), with a Back button to the list. |
| N4 | Where do notifications lead? | **Bell → grouped notifications → list of records → record.** The bell shows an unread count. It opens the notifications grouped as in D7. Opening a group shows the list of records behind it (e.g. stock filtered to those items, or those POs); opening a row opens the record. Opening a group marks it read. |

Menus are a convenience, not security: every action is still checked on the server for role and store.

## 2. Web navigation by role

| Menu item | Admin | Store manager | Finance |
|---|---|---|---|
| Dashboard *(M22, not built yet)* | ✓ (landing) | — | ✓ |
| Orders | ✓ | ✓ (landing) | — |
| Inventory | ✓ | ✓ | ✓ read-only |
| Catalogue | ✓ (create, edit, archive) | ✓ browse | — |
| Purchase orders | ✓ | ✓ | ✓ (landing: awaiting approval) |
| Suppliers *(not built yet)* | ✓ manage | — | ✓ view |
| Settings: remote config *(M27, not built yet)* | ✓ | — | — |

- **Store switcher** (top of the sidebar): Admin and Finance pick *All 5 stores* or one store, and every screen follows it. Managers see their own store, fixed.
- **Top bar:** the notification bell (N4). Global search comes later, if time allows.
- **Sidebar footer:** name, role, light/dark toggle, sign out.
- **Menu counts:** Purchase orders shows how many POs wait for this person (finance: awaiting approval; managers: approved and waiting for goods).

## 3. Workflows

Each workflow lists who does it, the steps and screens, and where it ends.

### 3.1 Sell to a customer (store manager)
1. Orders → **New order** (a three-step form: customer → items → review).
2. Items list only sellable products (active, priced, in stock at this store); the server checks again (D30).
3. **Place order** → the order is *Pending* and stock goes down in the same transaction (D29). One order per attempt, even on a double click (D8).
4. Next status moves happen from the order's side panel, or in bulk from the list: *Pending → Packed → Shipped → Delivered*. *Cancel* only before shipping; stock goes back.
5. Ends: *Delivered* or *Cancelled*.

### 3.2 Correct stock (store manager, admin)
1. Inventory → pick an item → side panel.
2. Choose a reason: *Damaged* (remove), *Recount* (set the counted number), *Other* (+/−, note required).
3. Save → stock changes and a history row records who, when, why. The low-stock flag updates.

### 3.3 Restock a store (purchase order lifecycle)
| Step | Who | Where | Result |
|---|---|---|---|
| 1. Draft from low stock | Store manager | Purchase orders → *New draft from low stock* | Pick a supplier (one per PO, D24), tick items, adjust quantities → **Draft** |
| 2. Submit | Manager of that store, admin | PO page → *Submit for approval* | **Awaiting approval** |
| 3. Approve or reject | Finance (never the PO's creator) | PO page | One approval, or **two different approvers above ₹50,000** (remote config) → **Approved**. Reject needs a reason → **Rejected** (ends) |
| 4. Receive goods | Manager of that store, admin | PO page → *Receive goods* | Any part of any line; stock goes up → **Partly received** / **Received** (ends) |
| 5. Attach the supplier invoice *(M20)* | Manager, admin | PO page | PDF or image up to 20 MB; view and replace |
| 6. Close a stuck PO | Manager, admin | PO page | Approved but waiting more than 30 days (D22) → **Closed** with a reason; goods already received stay |

Notifications fire on steps 3 and 4 and on low stock (§4). Imported POs flagged by D17–D19 show *Needs re-approval* and go back through step 3 before more goods can be received.

### 3.4 Look at the numbers (admin, finance) *(M22)*
Dashboard → filter by store and date range → revenue, order count, top products, low-stock count, trend. *Export CSV* downloads the **filtered orders**.

### 3.5 Change behaviour live (admin) *(M27)*
Settings → change the low-stock threshold, the second-approval limit, the maintenance banner or a feature flag → saved with an audit row; apps pick it up within about a minute (DL-06).

## 4. Notifications (N4)

```
Bell (unread count)
  └─ Grouped notifications (D7): one per person + type, newest change first
       e.g. "Smart Buy Chips and 39 other items are out of stock in S04" · "6 purchase orders need your approval · oldest 3 days"
         └─ List of records behind the group (stock filtered to those items, worst first / those POs)
              └─ The record (stock item side panel / PO page)
```

Who gets which group comes from **D7** ([data findings](data-findings.md)) and the [event contract](events.md); this table only adds where each one opens.

| Notification (D7) | Who | Opens (list of records) |
|---|---|---|
| Out of stock · Running low | Store managers (own store) | Inventory, filtered to those items, worst first, with "Draft purchase order from selected" |
| Stuck orders (D32) | Store managers | Orders, filtered to stuck orders |
| Stuck POs (D22) | Store managers | Purchase orders, filtered to stuck POs |
| PO approved / rejected (**not grouped**, D7) | The store's managers | That PO's page directly (a single record, so the list step is skipped) |
| Needs approval · Needs second approval | Finance approvers | Purchase orders → *Awaiting approval* (oldest waiting shown on the notification) |
| Stock health · Needs head-office action (nightly) | Admin | Inventory across stores / Catalogue filtered (e.g. products with no price) |

## 5. Mobile navigation (store managers)

- **Bottom tabs** (labelled, design system §7): **Home · Stock · Orders · Invoices**.
  - *Home:* today's work: low-stock count, POs waiting for goods, pending orders. Each card opens its filtered list.
  - *Stock:* inventory cards → item screen (adjust, history).
  - *Orders:* list → order screen (status moves); *New order* button.
  - *Invoices:* POs needing an invoice → attach from the camera or files; view and replace.
- **Header:** store name, the bell (N4) and search.
- Purchase-order drafting and approvals are web-only in v1 (managers draft at a desk; finance approves on the web).

## 6. Still open

- Global search in the top bar (orders, products, POs in one box): nice to have, after the core features.
- Showing people's names instead of user IDs (e.g. "created by U07") needs a small user-lookup API.
- Whether finance also gets mobile access (currently web only).
