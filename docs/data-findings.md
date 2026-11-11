# Data findings: decisions needed

Source: `C:\shelfwise-candidate-data` (read-only, nothing modified).
Profiled on 2026-11-05. **All 32 decisions (D1–D32) were made by 2026-11-09**, one at a time, each checked against the full dataset and against every earlier decision.
These rules become the import code's handling and the import report.

**Guiding principles (from the decisions):**
- **Keep the original:** every record is kept exactly as received; corrections sit alongside, never overwriting.
- **The past stays as it is; rules protect the future** (D30, D17–D19, D22, D32).
- **Never guess:** fill a value only from evidence (D11), otherwise flag it for an admin (D10).
- **Re-runs add, never overwrite** (D28).

Legend: **Suggest** = my recommendation; **Decision** = yours.

---

## Products (`products.csv`, 800 rows)

### D1. 12 products listed twice with a "dirty" SKU
Each pair has the same name, supplier and cost; one SKU has a trailing space or is lowercase.
Price differs in 8 of 12 pairs.

| Clean SKU | Variant | Price clean / variant |
|---|---|---|
| SW-SNK-00091 | `SW-SNK-00091 ` | 25 / 28 |
| SW-GRO-00088 | `SW-GRO-00088 ` | 54 / 57 |
| SW-HOM-00033 | `sw-hom-00033` | 96 / 99 |
| SW-HOM-00080 | `sw-hom-00080` | 248 / 248 |
| SW-GRO-00035 | `SW-GRO-00035 ` | 93 / 91 |
| SW-ELE-00079 | `SW-ELE-00079 ` | 296 / 296 |
| SW-PER-00099 | `sw-per-00099` | 149 / 149 |
| SW-BEV-00022 | `SW-BEV-00022 ` | 108 / 106 |
| SW-BEV-00091 | `sw-bev-00091` | 57 / 60 |
| SW-GRO-00055 | `sw-gro-00055` | 31 / 34 |
| SW-PER-00101 | `sw-per-00101` | 162 / 162 |
| SW-HOM-00059 | `SW-HOM-00059 ` | 143 / 141 |

- Verify: search products.csv for `SW-SNK-00091`; two rows appear (lines 2 and 498).
- Facts checked before deciding:
  - Stock is held **separately**: every one of the 800 product rows, variants included, has its own stock row in all 5 stores (800 × 5 = 4,000). Clean and variant counts differ and were counted on different dates.
  - Variants are almost never used: 2 order lines spell a variant's SKU, but both were sold at the clean price. No purchase order uses a variant. Clean and variant always have the same cost price.
  - 30 order lines use a messy SKU. All 30 have a clean product in products.csv and were sold at that clean product's exact price. Only 2 of the 30 belong to these 12 pairs.
- Options: (a) merge into the clean SKU and keep the clean row's price; (b) merge and keep the higher/lower price; (c) keep both as separate products; (d) reject the variants.
- **Decision (2026-11-05): keep both, with a fixed SKU matching rule.**
  - **Product rule (stored SKU):** the clean row keeps its UPPERCASE SKU. The variant row becomes a separate product with a trimmed, lowercase SKU. All 12 pairs → 24 products, each with its own price and stock. The 6 trailing-space variants get a new lowercase SKU (e.g. `SW-SNK-00091 ` → `sw-snk-00091`); the import report records this.
  - **Matching rule (how any row in any file finds its product).** Compare SKUs trimmed and uppercased, then:
    1. No product found → reject the row and report it (only the 25 `SW-ZZZ` order lines, see D12).
    2. Exactly one product → use it, whatever the spelling.
    3. A clean/variant pair, from an inventory or purchase-order row → the exact spelling decides. From an order line with the clean spelling → the clean product.
    4. A pair, from an order line with a messy spelling → the product whose price equals the sold price.
    5. Still undecided (same price, no price, or no price match) → the clean product, flagged in the import report.
  - Proven on the full dataset: orders 32,816 lines → 32,289 one product, 500 clean spelling, 2 by price, 25 rejected, 0 undecided. Inventory 4,000 → 3,880 one product, 120 exact spelling (60 are variant stock rows). Purchase orders 1,231 → 1,211 one product, 20 exact spelling. No order line is matched to a product whose price differs from the price it was sold at.
  - Rejected alternative: matching order lines by price for every line breaks 252 lines (171 where the pair has the same price, 60 sold at an older price, 21 with no price).
  - Consequences accepted:
    - The app must allow lowercase SKUs (12 don't fit `SW-XXX-00000`), and SKU lookups are case-sensitive.
    - The catalogue shows 12 product names twice.
    - 30 of the 60 variant stock rows are already at or below their reorder level, so they will raise low-stock alerts.

### D2. 10 products have no selling price
SW-BEV-00005, SW-PER-00014, SW-BEV-00081, SW-PER-00094, SW-BEV-00008, SW-SNK-00082, SW-GRO-00172, SW-BEV-00030, SW-BEV-00078, SW-ELE-00062. All are ACTIVE and have a cost price.
- Facts checked before deciding:
  - All 10 are ACTIVE and none is part of a D1 pair.
  - Each has a cost price and GST rate, and stock in all 5 stores: 50 rows, 1,983 units, none negative, 11 at or below reorder level.
  - **Zero** customer order lines in any spelling.
  - 6 of them appear in 11 PO lines across 11 POs. Of those POs, 5 are RECEIVED, 2 APPROVED, 2 DRAFT, 1 SUBMITTED, 1 REJECTED. POs use cost price, so they need no selling price.
- Options: (a) import with no price and block them from new orders until an admin sets one; (b) skip them; (c) set price = cost (or cost + a margin).
- Considered: letting the store manager set the price. Rejected because:
  - The brief gives product management to admins ("admins create, edit and archive").
  - The data has one chain-wide `price_inr` per product, so a store manager would be changing the price for all 5 stores.
  - An unreviewed price change is a money-control risk.
- **Decision (2026-11-05): import with no price; an admin sets it; managers see it.**
  - The product imports with an empty price and is blocked from customer orders until an admin sets one. The price then applies chain-wide.
  - Store managers see the product with "Price not set: ask head office". Stock, adjustments, low-stock alerts and purchase orders work normally.
  - Nothing is invented. The 10 products are listed in the import report.
  - Cross-check on the full dataset: nothing breaks. There are no historical orders to re-price, all 50 stock rows and 11 PO lines import as they are, and there is no overlap with D1.
  - Consequences accepted:
    - 5 open POs (PO-0063, PO-0114, PO-0136, PO-0172, PO-0195) can still be received, adding stock that can't be sold until it's priced.
    - 11 low-stock rows will still raise alerts.
    - New products an admin creates must have a price (the empty price is allowed only for imported rows).

### D3. 25 ARCHIVED products still have stock and appear in old orders
There are 125 inventory rows and 60 order lines for archived products.
- Facts checked before deciding:
  - None of the 25 is part of a D1 pair or among the D2 unpriced products. Created 2024-01-05 to 2025-12-02.
  - Stock: 125 rows, **4,077 units**. 118 rows have stock, none negative, 27 at or below reorder level.
  - Orders: 60, one line each. 58 DELIVERED, 1 CANCELLED, **1 open: ORD-011534 (S04, SHIPPED)**, which has archived SW-HOM-00078 plus an active product.
  - All 60 lines were sold at a price different from today's catalogue (see D15).
  - Purchase orders: none uses an archived product.
  - Example: products.csv line 671, SW-SNK-00064 Fresh Basket Chocolate Bar 100g, ARCHIVED. Stock S01 0, S02 56, S03 81, S04 23, S05 69.
- Options: (a) keep stock and history, hide from catalogue/new orders; (b) zero their stock.
- Rejected:
  - **Block everywhere:** ORD-011534 could never be marked Delivered.
  - **Allow everywhere:** managers could sell or reorder discontinued products, and 27 alerts would fire for items nobody buys.
  - **Zero the stock:** 4,077 physical units would disappear from the system.
- **Decision (2026-11-05): keep stock, block new use. Rule: archiving stops new business without breaking existing records; unarchiving restores new business without touching stock or history.**
  - **Archived product:**
    - Stock and history are imported and kept as they are.
    - It can't be added to a **new** order or a **new** purchase order.
    - Existing orders can still move through their statuses (ORD-011534 → Delivered).
    - It's excluded from low-stock alerts and PO suggestions.
    - Stock adjustments are still allowed (damaged, returned to supplier), so leftover stock can be cleared properly.
    - Managers can see it in stock views with an "Archived" label.
  - **Archive/unarchive:** admins only; each change is recorded with who did it and when.
  - **Unarchive (our addition; the brief only mentions archive, so this is noted in the README as an assumption):**
    - Stock is unchanged and simply counts again.
    - New orders and POs are allowed again, unless the price is empty (the D2 rule still applies).
    - The admin sees the current price and confirms it, because the stored price may be stale (SW-SNK-00064 sold at ₹166 against ₹52 today).
    - Low-stock is rechecked for that product immediately, and an alert fires once if it's below reorder level (subject to D7).
    - Old orders and history are untouched.
    - Unarchiving an already-active product is rejected with a clear message.
  - **Edge cases to prove with tests:**
    - The product is archived while a manager is mid-way through the order form: the server re-checks status on submit and rejects that line with a clear message.
    - Archive or unarchive is requested twice.
    - ORD-011534 can still move from SHIPPED to DELIVERED.
  - Re-running the import after an admin archived or unarchived something is decided in **D28**.

---

## Inventory (`inventory.csv`, 4,000 rows)

### D4. 60 store+SKU pairs have two rows once the SKU is cleaned
These are the D1 variants. Quantities differ in 59 of 60 pairs.
> Update after D1: because the variants are now separate products, these 60 rows are no longer duplicates. Each one is that variant's own stock row. D4 may be closed by D1; confirm when we reach it.
Example: S04 `SW-ELE-00079` qty 18 (counted 14-Oct) vs `SW-ELE-00079 ` qty 6 (counted 17-Oct).
- Options: (a) keep the row with the latest `last_counted_at`; (b) add them together; (c) import neither and flag for a recount.
- **Decision (2026-11-06): closed, covered by D1.** No merge, pick or discard. Each of the 60 rows is the stock of its own product (the clean product or its lowercase variant). Verified when D1 was tested: all 4,000 rows map to exactly one product, every store+SKU pair is unique, and every product has exactly 5 store rows.

### D5. 15 rows have negative stock
Examples: S03 SW-STA-00036 = −10, S05 SW-BEV-00050 = −11, S02 SW-ELE-00014 = −10.
- All 15 rows (inventory.csv line, store, SKU, qty): 196 S03 SW-STA-00036 −10 · 308 S04 SW-SNK-00048 −3 · 694 S02 SW-GRO-00002 −5 · 730 S02 SW-GRO-00155 −1 · 754 S01 SW-BEV-00037 −3 · 863 S03 SW-GRO-00113 −1 · 1276 S03 `SW-HOM-00059 ` (the D1 variant `sw-hom-00059`) −1 · 1811 S01 SW-SNK-00094 −2 · 1958 S01 SW-STA-00018 −9 · 2271 S04 SW-PER-00072 −1 · 2319 S01 SW-PER-00046 −2 · 2845 S05 SW-GRO-00129 −6 · 2903 S05 SW-BEV-00050 −11 · 2922 S02 SW-ELE-00014 −10 · 3633 S02 SW-PER-00079 −7. Total −72 units. All are ACTIVE and all are below reorder level.
- Options: (a) import as 0 and record an adjustment "import correction"; (b) import negative as-is; (c) skip.
- Rejected:
  - **Import as-is:** breaks the database rule that stock never goes below 0, which is the strongest guarantee for PIP objective 2 (stock stays correct under overlapping changes).
  - **Skip:** those products would have no stock row in that store, so orders and PO receipts for them would fail.
- **Decision (2026-11-06): set to 0 and flag for recount.**
  - Each row imports as 0. The stock history gets an entry "Import correction: −N → 0, recount needed", made by the system/import user with reason code `IMPORT_CORRECTION`.
  - The row carries a **recount needed** flag, because 182 rows are legitimately 0 in the file and the corrected ones must be distinguishable. The flag clears when a manager records a counted adjustment.
  - The 15 rows are listed in the import report.
  - The database enforces stock ≥ 0 from day one.
  - Re-running the import must not add a second correction entry (see D28).
  - Cross-check on the full dataset:
    - The import doesn't break.
    - 2 SHIPPED orders (ORD-007913, ORD-013404) have already left the store, so they're unaffected.
    - PO receipts work: PO-0284's remaining 30 units take S02 SW-GRO-00155 from 0 to 30, and PO-0242 (SUBMITTED, 150) adds stock if approved.
  - Dependency: 3 open orders contain these items (ORD-012861 PACKED 1 unit, ORD-004863 PENDING 2, ORD-009538 PENDING 2). Whether they can still progress at 0 stock depends on **D29**. If stock does not already subtract them, they stay blocked until a recount or delivery adds stock (correct behaviour: you can't ship what isn't there).
  - All 15 are below reorder level, so whether they raise low-stock alerts on import depends on **D7**.

### D6. `last_counted_at` uses 6 date formats
ISO with offset 1,710 · ISO no timezone 592 · `dd-Mon-yyyy HH:MM` 580 · `dd/mm/yyyy` 550 · `yyyy-mm-dd` 387 · epoch milliseconds 181.
`dd/mm` vs `mm/dd` is settled by the data: 322 values have a first part over 12 and none have a second part over 12, so it is day-first.
- Options for values with no timezone: (a) treat as IST; (b) treat as UTC.
- Facts checked before deciding:
  - All 4,000 values parse. Range 2026-09-01 → 2026-10-31. None is in the future, and none is before its store opened.
  - Format examples (inventory.csv line): ISO with offset (line 3), ISO with no timezone (line 13), `dd-Mon-yyyy HH:MM` (line 29), `dd/mm/yyyy` (line 2), `yyyy-mm-dd` (line 7), epoch ms (line 4, `1792477631000` = 2026-10-20 11:57 IST).
  - Evidence for IST: all 1,710 values that carry a timezone use `+05:30`. Read as IST, the no-timezone values cover exactly the same date range.
  - 937 values are date-only (no time).
- **Decision (2026-11-06): IST rule.**
  - A missing timezone means IST (`+05:30`).
  - Date-only values mean 00:00 IST that day; the import report notes that the time wasn't recorded.
  - `dd/mm` is day-first (proven: 322 values have a first part over 12, none a second part over 12).
  - Epoch values are absolute moments and need no assumption.
  - Every timestamp is stored as an absolute moment and displayed in IST.
  - Any value that matches none of the 6 formats is rejected and reported, never guessed (none in the current data).
  - The same rule is proposed for order dates (D9), which gets its own check.

### D7. 1,073 rows are already at or below reorder level, which risks a notification flood on day one
*Corrected 2026-11-06: the first version said 1,058. That check skipped the 15 negative rows.*
- Facts:
  - 1,073 rows are at or below reorder level. 27 are archived (excluded by D3), which leaves **1,046 rows across 622 products**.
  - **190** of them are out of stock (including the 15 set to 0 in D5), and 109 are exactly at the level. 30 are D1 variants and 11 are D2 unpriced. *(Corrected 2026-11-11: the first version said 175. The raw file has 182 rows at 0 plus 15 negative rows set to 0 by D5 = 197; minus 7 archived = 190. Found when the live notifications showed 190 across the 5 stores; the 1,046 total was right.)*
  - Per store: S01 184 · S02 197 · S03 215 · S04 235 · S05 215.
  - One notification per row per manager would send **about 1,427** on day one (S01 and S02 have two managers each).
- Options considered: (a) no alerts on import, only on new drops; (b) the same plus one summary per store; (c) alert every row; (d) **grouped notifications (the user's idea, chosen)**.
- **Decision (2026-11-06): grouped notifications, Instagram style.**
  ShelfWise groups notifications by type, the way Instagram groups likes ("Arjun and 23 others liked your post"). Each person has **one notification per type**, showing a live count and the latest item. When something new happens, that notification updates, moves to the top and becomes unread again; it doesn't create a new message. Opening it leads to the full list, where the person can act. Each role only gets the types it can act on, at the level it acts.

  | Role | Notification types | Example | Updates |
  |---|---|---|---|
  | Store manager (own store) | Out of stock · Running low · Stuck orders *(D32)* · Stuck POs *(D22)* | "Smart Buy Chips and 39 other items are out of stock in S04" · "42 orders are stuck in S04" · "12 purchase orders are waiting too long" | Live |
  | | Purchase-order decision (**not grouped**: few, each needs action) | "PO-0195 was approved" | Live |
  | Finance approver (all stores) | Needs approval · Needs second approval (over ₹50,000) | "6 purchase orders need your approval · oldest waiting 3 days" | Live |
  | Admin (all stores) | Stock health · Needs head-office action | "190 items out of stock across 5 stores" · "10 products have no price" | Once a day (nightly job) |

  - **Low** means at or below the reorder level; **out of stock** means 0. Each item counts in one group only.
  - The count shows the **current** situation, not a pile of past events: it goes down when items are restocked, and the notification clears at 0.
  - A notification becomes unread again only when its count goes **up**.
  - The finance notification shows the oldest waiting item, so that grouping never hides how long money decisions have been waiting.
  - Day one: each store manager gets one notification per type with the imported counts. With D32's stuck-orders group, that's **14 notifications across 7 managers**, instead of about 1,427 messages. The full lists are one tap away.
  - Technical shape: ~~one record per **person + type**~~ **one record per store + type (per role for finance), with each person's own "seen up to" time** (changed 2026-11-11 with the user's approval, DL-12; what people see is unchanged). The notification handler (the backend piece the brief requires) receives every event and updates that record. Only the grouping and the timing differ by role.
  - Target screen (to finalise in design): a low-stock list for the store, worst first, with "Draft purchase order from selected" (links to brief feature 6).
  - Scope: the store-manager groups and individual PO decisions are **required** by the brief and come first. The finance and admin groups reuse the same mechanism; if time runs short they go to "known issues / next".
  - The README records these as assumptions: who receives what, admin daily timing, and "at the level counts as low".

---

## Orders (`orders.json`, 15,000 records)

### D8. 40 order IDs appear twice; each pair is identical
e.g. ORD-000212, ORD-000486, ORD-001327.
- Facts checked before deciding:
  - 40 IDs appear exactly twice, so 15,000 records hold **14,960 real orders**.
  - All 40 pairs are identical in every field: lines, prices, date, customer and status.
  - Order IDs form a perfect sequence with no gaps (ORD-000001 → ORD-014960), so no real order lost its ID to a copy.
  - Each duplicate's customer phone appears on no other order, and no identical order hides under a different ID. There are 14,960 distinct customer phones, one per real order.
  - All 40 pass the internal checks: the total equals the sum of the lines, and the person who placed it belongs to that store.
  - No other file (inventory, purchase orders, products, users, stores) refers to any order ID.
  - The copies are scattered through the file (e.g. ORD-000212 at array positions 5,120 and 10,475). Statuses: 31 DELIVERED, 3 CANCELLED, 2 PACKED, 2 PENDING, 2 SHIPPED.
- **Decision (2026-11-06): keep one copy of identical duplicates; flag conflicting ones.**
  - Identical copies: import one, skip the rest, and count the skips in the import report (40).
  - Same ID but different content: import **neither** and flag the conflict for review, because we can't know which copy is true. This doesn't happen in the current data; the rule protects against new data.
  - The database keeps the order ID unique, so a duplicate can never get in.
  - **Revenue effect** (no stored revenue figure exists; it's always calculated from orders): DELIVERED revenue ₹1,45,62,704 → **₹1,45,30,337** (−₹32,367 across 31 orders); delivered count 13,036 → 13,005. The import report shows this before-and-after. *(Revenue = DELIVERED orders only: **confirmed by the user on 2026-11-11** when the dashboard was built. Order count = all orders split by status; top products ranked by revenue with units.)*
  - Dependencies:
    - ORD-006289 also has a missing price, which is handled by D11.
    - The 4 open duplicates have their stock counted once; when it's subtracted depends on D29.
  - Related, for the edge-case register: new orders send a request ID (idempotency key) on submit, so a double tap or a network retry from web or mobile returns the same order instead of creating a second one. This is the common e-commerce practice, and it matters most for mobile on the shop floor.

### D9. `created_at` uses 5 formats
Counts over the 14,960 real orders (after D8): ISO with `+05:30` 9,158 (e.g. ORD-000995) · ISO with no timezone 2,711 (ORD-013675) · `dd/mm/yyyy HH:MM` 1,192 (ORD-005142) · `Mon dd, yyyy hh:mm AM/PM` 1,240 (ORD-009788) · epoch ms stored as a JSON **number** 659 (ORD-012704).
- Facts checked before deciding:
  - All 14,960 parse. Range 2025-11-05 → 2026-10-31. None is in the future, and none is before its store opened.
  - All dates that carry a timezone use `+05:30`, and the no-timezone formats cover the identical range.
  - **Day-first proof** (orders, with inventory/D6 in brackets):
    - The first number is >12 in 720 (322) values; the second number is never >12.
    - Read as month/day, 720 (322) would be impossible dates, and 131 (189) more would fall outside the range every other format uses.
    - Read as day/month, 1,192 of 1,192 (550 of 550) fall inside it.
    - The 472 (228) values that look ambiguous on their own follow the file's single format.
  - 12-hour trap: `12:55 AM` = 00:55 and `12:57 PM` = 12:57; tested.
  - Informational: order times are spread evenly over 24 hours (about a third between 23:00 and 07:00 in every format), which suggests generated data. No fix; noted in the import report. It also means order hours can't be used to double-check the timezone.
- **Decision (2026-11-06): the same rule as D6, with one shared date reader for all files.**
  1. Timezone present → use it.
  2. No timezone → IST (`+05:30`).
  3. `dd/mm/yyyy` → day-first.
  4. AM/PM → 12-hour clock (12 AM = midnight hour, 12 PM = noon hour).
  5. Epoch number → exact moment, no assumption.
  6. Anything else → reject the order and report it. Never guess (none today; a safety net for new data).
  - Every timestamp is stored as an exact moment and displayed in IST. Dashboard date filters use IST days (00:00–23:59 IST).
  - Assumption for the README: all 5 stores are in India. If a store outside India is ever added (e.g. by the change request), "no timezone" must become "that store's timezone".
  - Future inputs: dates created in the app are stamped by the server, so this rule only applies to imported files, including any extra data the reviewers release.

### D10. 15 DELIVERED orders have a line with a negative quantity
*Corrected 2026-11-06: the first version said all had negative totals. In fact 11 are negative and 4 positive.*
- Facts checked before deciding:
  - 15 orders, one negative line each (qty −1 ×8, −2 ×4, −3 ×3). All DELIVERED.
  - **7** consist only of the negative line, e.g. ORD-011525: −1 × ₹1,073 = −₹1,073.
  - **8** mix it with normal lines, e.g. ORD-007191: 4 normal lines + (−2 × ₹113), total +₹453.
  - All IDs: ORD-007286, 005547, 003557, 013417, 007191, 009363, 002264, 009494, 005620, 008184, 013241, 004581, 000163, 011525, 012972.
  - Negative lines total −₹5,660; their normal lines +₹3,386; net **−₹2,274**.
  - **Not returns:** none has an earlier sale of the same item to the same customer in the same store, and no customer appears twice anywhere in the 14,960 orders. Most likely data-entry errors; the true quantity is unknown.
- Revenue under each option (DELIVERED, after D8 = ₹1,45,30,337):

  | Option | Revenue |
  |---|---|
  | Skip | ₹1,45,32,611 |
  | Flip the sign | ₹1,45,41,657 |
  | Drop the negative line (leaves 7 empty ₹0 orders) | ₹1,45,35,997 |
  | As-is | ₹1,45,30,337 |
- **Decision (2026-11-06): the admin review queue (the user's idea).**
  1. The 15 orders are imported exactly as in the file, carrying a **"Needs review"** flag. Until reviewed, they're excluded from revenue, order counts, top products and the CSV export. The dashboard starts at **₹1,45,32,611**.
  2. The admin is notified through the D7 group "Needs head-office action": "15 imported orders need review".
  3. On the review list, each order shows the original data with the problem highlighted. The admin either:
     - **Corrects** it (enters the right quantity and a reason; the total is recalculated and the order joins revenue), or
     - **Rejects** it (it stays excluded, with a reason).

     "Accept as negative" is not offered, because the system has no returns.
  4. Every decision is recorded (who, when, what changed, why). The original values are kept, so the before-and-after is always visible.
  - It's a review **flag**, not a new order status. The order lifecycle stays as the brief defines it.
  - Correcting an old order does **not** change stock. Stock comes from the inventory count, not from replaying delivered orders.
  - Reuse: the same queue is proposed for D11, D12, D17–D19 and D30, each confirmed in its own decision.
  - Fallback if time runs short: excluded and listed in the import report. The revenue is the same; only the review screen is missing.
  - Edge cases to test: review the same order twice, or two admins at once (only one decision wins); a non-admin tries to review (forbidden); a corrected quantity of 0 or below (rejected); a reason is required.

### D11. 20 orders have one item with no price
*Corrected 2026-11-06: the first version said 21. That count included ORD-006289 twice, because it's also a D8 duplicate. "Total = 0" applies to only 10 of them.*
- Facts checked before deciding:
  - 20 orders, exactly **one** unpriced line each.
    - **10** have only that line, so the bill is ₹0 (e.g. ORD-009964, power bank × 1).
    - **10** mix it with priced lines (e.g. ORD-001422: priced lines ₹5,508 + chips × 1, unpriced).
  - Statuses: 17 DELIVERED, 1 CANCELLED, 2 SHIPPED (ORD-014730, ORD-014704).
  - In **every** order the total equals the priced lines exactly, so the source counted the item as ₹0. The total carries no information about the missing price.
  - **Sales-history evidence:** each of the 20 SKUs sold **33–56 other times, always at one single price**, including within ±30 days of the order, and that price equals today's catalogue price. e.g. SW-SNK-00007 chips: 49 other sales, all ₹24.
  - No overlap with D10, D12, D2 or archived products.
- Ideas considered:
  - **Skip:** loses real sales.
  - **Treat as a free item** (the user's idea): rejected after checking. 10 orders have nothing else on them, and in 7 of the other 10 the "free" item is worth about as much as, or more than, what was paid (up to 236%).
  - **Match by product name:** the SKU is more exact, and names repeat (D1).
  - **Admin review only:** kept as the last step of the ladder.
- **Decision (2026-11-06): fill the price from evidence, the strongest first.**

  In one line: *if an item's price is missing but it always sold at the same price, fill in that price; otherwise the admin decides.*
  1. **From the bill total:** if the total is more than the priced lines and exactly one line is unpriced, price = (total − priced lines) ÷ qty. It's used only if it matches the sales history; otherwise → step 3.
  2. **From the sales history:** if every sale of that SKU within ±30 days of the order was at one single price, and it equals the catalogue price, use it.
  3. **Otherwise → the admin review queue (D10):** set the price (with a reason) or reject.
  - On today's data: step 1 = 0, **step 2 = all 20**, step 3 = 0.
  - Effects:
    - Totals are recalculated (e.g. ORD-001422 ₹5,508 → ₹5,532).
    - DELIVERED revenue ₹1,45,32,611 → **₹1,45,38,055** (+₹5,444).
    - Each order is marked "price filled from sales history", and the import report lists all 20 with before and after.
    - The 2 SHIPPED orders carry on as normal. Stock doesn't change.
  - **Stated assumption (README):** the price is certain, and we assume the customer was charged and the bill was missing a line.
  - Test cases: one unpriced line with total > priced lines; two unpriced lines; a history with mixed prices; no sales history at all; total < priced lines.

### D12. 25 orders reference SKUs that don't exist (`SW-ZZZ-9xxxx`)
23 DELIVERED, 2 CANCELLED. e.g. ORD-004223, ORD-010196.
- Facts checked before deciding:
  - 25 orders, one unknown line each, 25 **distinct** codes, all `SW-ZZZ-9xxxx`.
  - **Not real products in disguise:**
    - "ZZZ" isn't a category (real: BEV, ELE, GRO, HOM, PER, SNK, STA).
    - The numbers run 90242–99632 against real 1–212, and none matches a real SKU number.
  - **Price matching rejected as guesswork:** 13 of 25 happen to equal exactly one real product's price, which is coincidence (e.g. ₹22 `SW-ZZZ-97515` ≠ cream biscuits). Unlike D11, the SKU is unknown.
  - Every line has a price and qty, and each order total includes it. 11 orders contain only the unknown line; 14 are mixed.
  - Statuses: **all closed**, 23 DELIVERED and 2 CANCELLED. Stores: S02 8, S01 7, S04 6, S03 2, S05 2.
  - DELIVERED value: unknown lines ₹15,994 + real lines ₹11,500 = ₹27,494.
- Options: (a) skip the whole order and report; (b) import, dropping the unknown line; (c) create placeholder products; (d) match by price; (e) count it and flag it for admin review.
- **Decision (2026-11-06): count it, no review; be strict for the future (the user's reasoning).**
  - **Historical (these 25):**
    - Import each order in full. The line keeps its code and shows as **"Unknown product (SW-ZZZ-…)"**, and it counts in revenue because the money was recorded as received.
    - It has no stock effect (there's no stock row) and appears under its own heading in "top products" (₹15,994, about 0.1% of revenue).
    - **No admin review:** the orders are closed, the money is known, and an admin has no better information to identify the product. All 25 are listed in the import report.
    - Revenue stays **₹1,45,38,055**.
  - **New orders in the app:** an unknown product is impossible. The form picks from the catalogue, and the server rejects any line whose product doesn't exist, is archived (D3) or is unpriced (D2). This is an edge-case test.
  - **Future imports** (if more data is released): unknown products are **flagged for admin review** instead of being accepted automatically.
  - Why it's consistent with D10: D10 needs review because the **money** is wrong (negative quantities). D12 doesn't, because the money is right and only the product's identity is missing.
  - Technical note: an order line stores the SKU text exactly as received, plus an optional link to a product. Unknown lines have no link.

### D13. 30 order lines use a messy SKU (lowercase or trailing space)
*Corrected 2026-11-05: the first version said all 30 were D1 variants. Only 2 of them involve a D1 pair.*
- 28 are messy spellings of products that exist only once, e.g. ORD-010060 `sw-snk-00078` → SW-SNK-00078, ORD-006821 `SW-GRO-00160 ` → SW-GRO-00160.
- 2 spell a D1 pair: ORD-007387 `sw-hom-00033` and ORD-014354 `sw-bev-00022`. Both were sold at the clean price.
- All 30 were sold at their matched product's exact price.
- Handled by the D1 matching rule: 28 by step 2, 2 by step 4. All 30 go to the clean product.
- **Decision (2026-11-06): closed, covered by D1.** All 30 lines match the clean product, each sold at exactly that product's price, and none is left undecided. The import report lists each line with its original SKU and the product it was matched to.

### D14. 35 orders have the same SKU on two lines
*Corrected 2026-11-06: the first version said 36. That count included a D8 duplicate.*
e.g. ORD-006345 has SW-HOM-00055 ×1 twice.
- Facts checked before deciding:
  - 35 orders, one repeated product each, exactly 2 lines per group.
  - In **all 35**, the two lines have the same spelling and the same unit price; the quantity differs in 21 (e.g. ORD-012701: 6 × ₹40 and 1 × ₹40).
  - All 35 totals already equal the sum of lines.
  - Statuses: 27 DELIVERED, 5 CANCELLED, 2 SHIPPED, 1 PACKED.
  - Overlap: only ORD-006289 (also D8 and D11).
  - Most likely the item was added or scanned twice; no money issue.
- Options: (a) merge into one line with summed qty; (b) keep as separate lines.
- **Decision (2026-11-06): merge and add up the quantities.**
  - One line per product per order, with qty = sum and the same unit price (e.g. 6 × ₹40 + 1 × ₹40 → 7 × ₹40).
  - Totals and revenue **don't change**. The import report lists all 35 with their original lines.
  - Merging happens only when the repeated lines have the **same unit price**. If a future file has the same product at **different** prices in one order, the prices are never averaged. *Amended 2026-11-10 while building the import: because the database allows one line per product per order (the rule above), such an order is **rejected and listed for review** rather than kept with two lines. It does not occur in the current data.*
  - Order of rules: matching (D1) → missing-price fill (D11) → merge (D14).
  - Future app orders: adding the same product twice in the order form combines it into one line with the summed quantity. The database allows one line per product per order. This is an edge-case test.

### D15. 61 order lines have a price different from today's catalogue (informational)
*Corrected 2026-11-05: the first version said 303 lines (243 from D1 SKUs). That was a bug in my check, which mixed up clean and variant prices. The real number is 61 lines across 25 SKUs, mostly archived products (e.g. ORD-009419 SW-SNK-00064 sold at ₹166 vs ₹52 today).*
- Facts checked before deciding (on 14,960 real orders):
  - **Active products:** 32,591 of 32,592 priced lines sold at exactly today's catalogue price. The 1 exception (ORD-007387 `sw-hom-00033`, ₹96) is resolved by D1, which matches it to the clean product at ₹96, so after D1 it's 100%.
  - **Archived products:** all 60 lines (59 DELIVERED, 1 SHIPPED = ORD-011534 from D3) are off-catalogue, and **erratic**.
    - The same SKU sold at very different prices (e.g. SW-ELE-00047: ₹471, ₹107, ₹14 against ₹259 in the catalogue).
    - Sold/catalogue ranges from 0.05 (ORD-000169, ₹14 against ₹259) to 7.92 (ORD-004916, ₹752 against ₹95). Only 8 of 24 archived SKUs have a single consistent price.
    - DELIVERED value as recorded ₹23,740; at today's catalogue it would be ₹49,621.
- **Decision (2026-11-06): keep the recorded prices as snapshots, with no review.**
  - An order line's price is what the customer paid at that moment. Later catalogue changes never rewrite old bills (standard practice).
  - Revenue unchanged: **₹1,45,38,055**.
  - No admin review, by the same logic as D12: the orders are closed apart from ORD-011534, which carries on as normal; the money was recorded; and an admin has no better information.
  - The import report lists all 60 lines, noting that archived products have erratic historical prices.
  - Rule for the app: each order line stores its own unit price when it's created. Later price changes (admin edits, D2 pricing, D3 unarchive) never alter existing orders. This is an edge-case test.

### D16. No customer master
14,960 distinct phone numbers but only 192 distinct names.
- Facts checked before deciding:
  - 14,960 real orders, 14,960 distinct phones; **no phone is used twice**. All are valid `+91 [6-9]xxxxxxxxx`.
  - Only **192 names**, which are every combination of 16 first names × 12 surnames (generated). e.g. "Ishita Iyer" is on 104 orders with 104 different phones, across stores.
  - The customer record holds only `name` and `phone` (no email or address).
  - So customer identity can't be built: matching by phone means nobody ever returns, and matching by name merges different people.
- **Decision (2026-11-06): customer on the order only; phones masked.**
  - There's no separate customer list. Each order keeps the customer name and phone as recorded, like a printed bill. The brief doesn't ask for customer management.
  - Phone numbers are personal data:
    - **Masked** in order lists and the CSV export (e.g. `+91 98XXXXX191`).
    - Shown in full only on the order's own page, to staff of that store.
    - **Never** written to logs.
  - No money or revenue change.
  - New app orders: a name is required, and the phone must match `+91 [6-9]` + 9 digits. This is an edge-case test.
  - The import report notes that the names look generated.

Order totals all match the sum of their lines. No issue there.

---

### D30. 201 order lines are dated before their product's `created_at`
*Added 2026-11-06, found while checking date order for D9.*
- 201 lines in 193 orders across 56 products are dated 1–53 days **before** the product's `created_at` (median 17 days).
  - e.g. ORD-000131 sold SW-BEV-00026 on 2025-11-07, but the product was created 2025-11-18.
  - Statuses: 181 DELIVERED, 12 CANCELLED, 4 SHIPPED, 3 PENDING, 1 PACKED.
- Of the 66 products created after the first order date (2025-11-05), 58 have sales before their creation date.
- Likely meaning: `created_at` is when the catalogue entry was made, not when the product first went on sale.
- Future inputs: none. In the app, a product must exist before it can be ordered.
- **Decision (2026-11-06): the past is kept as it is; rules protect the future (the user's principle for D30).**
  - **Past:** the 201 lines, the orders and the product dates are imported **unchanged**. `created_at` is read as "added to the catalogue on". No money or revenue change. Listed in the import report.
  - **Future: the "can this be sold?" rule for every new order line.** All of these must be true, otherwise the app blocks the line with a clear message (e.g. "Only 3 left in stock", "This product is archived"):
    1. The product is in the catalogue (D12).
    2. It is active, not archived (D3).
    3. It has a price (D2).
    4. The store has enough stock; stock never goes below 0 (D5). *When* stock is taken is decided in D29.
    5. The quantity is 1 or more (D10).
    6. The manager is ordering for their own store.
  - Each point gets an automated test (edge-case register). The server enforces all 6, not just the screens.

### D31. Purchase-order approvals are not listed in time order
*Added 2026-11-06, found while checking date order for D9.*
- Of the 128 purchase orders with two approvals:
  - **66** list the oldest approval first and **59** list the latest first. It's not sorted by user ID either (fits 59 of 128). **List position means nothing.**
  - e.g. PO-0003 lists U10 (approved 11:56) before U11 (approved 00:56).
- **3 have both approvals at the identical second:** PO-0070, PO-0139, PO-0177 (each U10 + U11). Who was first can't be known. The "two different finance approvers" rule is still met.
- The approval time must decide first versus second (matters for D17 and D19).
- Future inputs: history only, but it exposes a live risk. Two approvers clicking Approve at the same moment must not create two "first" approvals or a double approval; the server must handle it safely (PIP objective 2, concurrency). It goes in the edge-case register with a test.
- **Decision (2026-11-09): time decides the order; concurrent approvals use optimistic concurrency, not a queue.**
  - **Past:** first and second approvals are decided by `decided_at`, never by list position. PO-0070, PO-0139 and PO-0177 (same second) are valid: 2 different finance approvers. Noted in the report; nothing to fix.
  - **Future: three layers, with no queue and no waiting (the user rejected "one at a time" as inefficient, 2026-11-09):**
    1. **Optimistic version check on the PO** (Spring Data R2DBC `@Version`). Everyone works in parallel. A save succeeds only if the PO hasn't changed since it was read. On a clash, the second request **re-reads and re-checks**: it becomes the valid 2nd approval, or gets "already fully approved". This is non-blocking and fits the brief's reactive WebFlux rule.
    2. **Database constraints as the final guard:** unique (PO, approver), so the same person can't approve twice, and unique (PO, approval step), so there can't be two "1st" approvals. These hold even if the application code has a bug.
    3. **Idempotency key on the Approve request:** a double-click or network retry counts once (same mechanism as D8 for orders).
  - **Same technique for stock (D29):** taking stock is one atomic conditional update ("take N only if at least N are there"), so overlapping orders can't oversell. PIP objective 2.
  - **Why optimistic:** conflicts are rare (3 of 300 POs had same-second approvals), and optimistic methods are efficient when conflicts are rare. Nothing is locked; the only cost is one re-read on a real clash.
  - **Risk named:** *write skew* (Berenson et al. 1995). Two approvers each see "1 approval" and both write "the 2nd". Layers 1 and 2 prevent it. Postgres SERIALIZABLE (SSI, Ports & Grittner 2012, under 7% overhead) is the documented fallback.
  - **References (for the decision log):**
    - Kung & Robinson, "On Optimistic Methods for Concurrency Control", ACM TODS 6(2), 1981.
    - Berenson et al., "A Critique of ANSI SQL Isolation Levels", ACM SIGMOD 1995.
    - Ports & Grittner, "Serializable Snapshot Isolation in PostgreSQL", PVLDB 5(12), 2012.
    - AWS DynamoDB optimistic locking documentation.
    - Stripe, "Designing robust and predictable APIs with idempotency".
  - Consistency check: D17–D20 ✅ (first and second by time), D29 ✅ (same pattern for stock), D8 ✅ (same idempotency idea).
  - Tests:
    - Two approvers approve the same PO concurrently: exactly 2 approvals, correct order, no duplicate.
    - The same approver twice: rejected.
    - A retried request with the same idempotency key: counted once.
    - Two orders for the last unit: exactly one succeeds.

### D32. About 1,010 open orders are very old (up to 365 days)
*Added 2026-11-06, found while checking date order for D9.*
- As of 2026-11-06:
  - PENDING: 317, median age 156 days, oldest 363, 248 over 30 days.
  - PACKED: 332, median 145 days, oldest 363, 265 over 30 days.
  - SHIPPED: 361, median 157 days, oldest 365, 305 over 30 days.
  - Compare: the youngest DELIVERED order is 5 days old.
- Unrealistic for a shop (a customer waiting 5+ months).
- Future inputs: **yes**.
  - These orders arrive open in the app, and managers will see them and have to close them.
  - They affect stock (D29) and the dashboard's order counts by status.
- **Decision (2026-11-06): keep the past as it is; flag stuck orders from now on.**
  - **Past:** all open orders are imported exactly as they are. Nothing is auto-cancelled or auto-closed (the D30 principle).
  - **Future rule:**
    - An order still open (Pending, Packed or Shipped) more than **7 days** after it was created is marked **"Stuck"**. Age is counted in IST (D9).
    - The store manager gets **one grouped notification** (D7 style): e.g. "42 orders are stuck in S04". It opens the stuck list, where the manager decides on each order: move it forward, or cancel it.
    - The **7-day limit is in remote config**, so head office can change it without a release (PIP objective 7).
  - No money or revenue change. Stuck orders aren't delivered, so they're not in revenue.
  - Consistency check (2026-11-06):
    - D30: no auto-changes to the past. ✅
    - D3: ORD-011534 can still be delivered. ✅
    - D8 and D11: imported once, with prices. ✅
    - D5: the 3 stuck orders needing zero-stock items depend on D29 if cancelled. ✅
    - D7: updated with a third store-manager group. Day one is now 14 notifications.
  - Tests: an order exactly at the limit; changing the limit in remote config changes which orders are stuck; a non-manager can't act on another store's stuck orders.

### D29. Does imported stock already subtract the open orders?
*Added 2026-11-06, raised while deciding D5.*
*Corrected 2026-11-06: the first version counted the D8 duplicate copies.* There are **1,010 open orders**: 317 PENDING, 332 PACKED and 361 SHIPPED (649 of them can still be cancelled). `inventory.csv` doesn't say whether its quantities already exclude the units in those orders.
- Why it matters:
  - **Cancelling** an imported open order: do its units go back on the shelf? If stock never subtracted them, adding them back would create stock that doesn't exist.
  - **Packing or shipping** one: do we subtract stock now? If stock already subtracted them, subtracting again would count the sale twice.
- **Decision (2026-11-06): stock is taken when an order is created and returned when it's cancelled.**
  - **Past:** `inventory.csv` is today's true stock and already includes all 1,010 open orders. The import never replays old orders against stock (consistent with the D30 principle).
  - **The rule for every order, old or new:**

    | Moment | Stock |
    |---|---|
    | Order created | ⬇️ down (new orders only; imported ones were already counted) |
    | Pending → Packed → Shipped → Delivered | no change |
    | Cancelled (allowed **only from Pending or Packed**) | ⬆️ back up |
  - Example: a new order for 3 chips takes stock from 50 to 47, and it stays 47 through delivery. Cancelling the imported ORD-004863 (Pending, 2 packets) adds 2 back.
  - **Why at creation:** overlapping orders can't both take the last unit. The first wins and the second gets "out of stock" (PIP objective 2). The stock check (D30 point 4) and the stock reduction happen in **one** step, so there's no gap between them.
  - Other stock movements (not part of D29): receiving a purchase order (up) and manual adjustments with a reason (up or down).
  - Consistency check (2026-11-06): D3 ✅ (cancelling returns stock even for an archived product), D5 ✅ (zero-stock open orders can still progress), D7 ✅ (returned stock can lift an item off "running low"), D10 and D11 ✅ (fixing old orders never touches stock), D14 ✅ (cancelling returns the summed quantity), D30 ✅, D32 ✅ (cancelling a stuck order returns stock).
  - Tests:
    - Two orders for the last unit at the same time: exactly one succeeds.
    - Cancelling a Shipped order is rejected.
    - Cancelling twice returns stock only once.
    - Cancelling an imported Pending order returns its stock.

---

## Purchase orders (`purchase_orders.json`, 300 records)

### D17. 6 POs over ₹50,000 were approved/received with only one approval
PO-0038, PO-0116, PO-0125, PO-0145, PO-0148, PO-0186.
- Facts checked before deciding (ages as of 2026-11-06):

  | PO | Status | Amount | Store | Created | Approved by | Received |
  |---|---|---|---|---|---|---|
  | PO-0116 | APPROVED | ₹81,900 | S03 | 2026-02-13 (265 d) | U10 | 0 / 180 |
  | PO-0145 | PARTIALLY_RECEIVED | ₹1,15,660 | S05 | 2026-04-12 (207 d) | U11 | 532 / 840 |
  | PO-0186 | RECEIVED | ₹54,910 | S03 | 2026-05-15 (174 d) | U10 | 370 / 370 |
  | PO-0148 | RECEIVED | ₹1,49,190 | S04 | 2026-07-13 (115 d) | U10 | 490 / 490 |
  | PO-0125 | RECEIVED | ₹68,660 | S03 | 2026-07-15 (113 d) | U10 | 520 / 520 |
  | PO-0038 | PARTIALLY_RECEIVED | ₹51,750 | S05 | 2026-08-31 (66 d) | U10 | 195 / 400 |
  - The single approval was always a valid, active finance approver; only the second is missing. Each was approved within 1–2 days of creation.
  - Approval never changes stock; only receiving does. ShelfWise tracks store stock only, not supplier stock.
- Options: (a) import as historical and flag "policy breach" (read-only); (b) move back to SUBMITTED awaiting second approval; (c) skip.
- **Decision (2026-11-06): keep the past; apply the rule to anything that happens next.**
  - **Past:** all 6 are imported unchanged and flagged in the import report as **"rule broken: only 1 approval over ₹50,000"**. What was already received stays.
  - **The 3 still open, updated 2026-11-06 by the user's progress rule (see D19):**
    - **PO-0038** (59% received, ₹21,240 to come) and **PO-0145** (70%, ₹34,352 to come) are partly received, so they **can finish without re-approval**. Their approval came from finance and only a second signature was missing.
    - **PO-0116** (nothing received, 265 days old, ₹81,900) needs **re-approval** by 2 different finance approvers before any goods are received. It appears in finance's "Needs second approval" group (D7). Whether to close it instead is decided in D22.
  - **New POs:** a PO over ₹50,000 can't reach Approved without 2 approvals from 2 **different** finance approvers (and never its creator).
  - **Past receipts are already in today's stock file (same principle as D29).** The import never adds imported `qty_received` to stock again. Only goods received from now on increase stock.
  - Noted for D22: PO-0116 was approved 9 months ago with nothing received. "Stuck approved POs" will be decided with D22.
  - Consistency check (2026-11-06): D30 ✅ (past unchanged), D7 ✅ (uses the existing finance group), D29 ✅ (receipts not replayed), D31 ✅ (single approval, ordering irrelevant).
  - Tests:
    - A PO over ₹50,000 with one approval can't be received.
    - The same approver can't give both approvals.
    - Two approvers approving at the same moment (see D31).
    - Receiving more than was ordered is rejected.

### D18. 8 approvals were made by store managers, not finance
PO-0062 (U08), PO-0097 (U08), PO-0117 (U09), PO-0135 (U08), PO-0225 (U08), PO-0258 (U05), PO-0264 (U03), PO-0283 (U09).
- Facts checked before deciding:
  - In **all 8**, the manager's approval was needed: without it, the PO lacks enough valid finance approvals.
  - **4 had no finance approval at all** and were fully received: PO-0062 (₹33,060), PO-0097 (₹33,210), PO-0117 (₹39,810), PO-0283 (₹40,460).
  - 4 are over ₹50k with only 1 valid finance approval: PO-0225, PO-0264 (both received), **PO-0135** (partly received 230/500, also D19), **PO-0258** (approved, 0/240 received).
  - Several managers approved **another store's** PO (e.g. U08 of S01 approved S04's PO-0062).
- **Decision (2026-11-06): a store manager's approval never counts.**
  - **Past (6 finished POs):** kept as they are, flagged "invalid approval by a store manager" in the import report.
  - **Open, updated 2026-11-06 by the user's progress rule (see D19):**
    - **PO-0258** (nothing received, 56 days old, ₹57,000) needs **re-approval** by finance before any goods are received. It appears in "Needs second approval" (D7).
    - **PO-0135** (partly received) is handled by D19's self-approval exception, so it needs re-approval.
  - **Future:** only finance approvers can approve; the server rejects anyone else (the Approve button is also hidden).
  - Consistency check (2026-11-06): D17 ✅ (same pattern), D30 ✅, D7 ✅, role hierarchy ✅ (separation of duties).
  - Tests: a store manager or admin tries to approve and is rejected; receiving on PO-0135 or PO-0258 is blocked until approved.

### D19. PO-0135 was approved by its own creator (U08)
- Facts: U08 (store manager, S01) created PO-0135 (₹65,320) on 2026-04-17 and approved it **the same day**. Finance (U10) approved 2 days later. 230 of 500 units have been received; the rest hasn't arrived in **202 days**.
- **Decision (2026-11-06): covered by D18, plus a self-approval flag and a future rule.**
  - U08's approval doesn't count (D18). PO-0135 needs **2 valid finance approvals** before more goods are received.
  - The import report marks it **"self-approval"** (more serious than "invalid approval").
  - **Future:** nobody can approve a PO they created, **finance included**. The server blocks it.
  - Its age (stuck for 6+ months) is handled in D22.
  - **Principle for wrongly approved POs (D17–D19), the user's rule, updated 2026-11-06.** Past approvals that broke the rules stay as they are and are flagged; every new approval must follow the rules. For the 14 wrongly approved POs (₹9,24,550, of which 73% has been received), what happens depends on progress:

    | Progress | POs | Rule |
    |---|---|---|
    | Completed | 9 | Let them be (flagged) |
    | Partly received | PO-0038, PO-0145 | Let them complete (₹55,592 to come) |
    | Nothing received | PO-0116, PO-0258 | Re-approval by finance before any goods (₹1,38,900) |
    | **Exception: self-approved** | PO-0135 (19% received) | **Always re-approval**, even when partly received (₹53,100) |
  - Why: it doesn't disrupt deliveries already under way (avoids supplier disputes), re-checks before new money is committed, and treats self-approval as the most serious breach. This matches common purchasing practice. The usual stronger control, matching PO, goods received and invoice before **payment**, is out of scope (no payments in ShelfWise) and goes in "known issues / next".
  - The superseded version (all 5 open POs need re-approval) was replaced the same day.
  - Consistency check: D17 ✅, D18 ✅, D30 ✅.
  - Test: the creator (any role) tries to approve their own PO and is rejected.

### D20. 3 approvals by U12, who is now inactive
PO-0001, PO-0080, PO-0136. U12 may have been active at the time.
- Facts:

  | PO | Amount | Approved | Status |
  |---|---|---|---|
  | PO-0001 | ₹20,610 | 2026-03-26 (U12 only) | RECEIVED |
  | PO-0080 | ₹48,520 | 2026-04-28 (U12 only) | RECEIVED |
  | PO-0136 | ₹85,190 | 2026-05-19 (U12 + U11) | APPROVED, 0 of 1,020 received, 170 days old |
  - U12 has the **correct role** (finance approver). The data has no deactivation date, so she was most likely active when she approved.
- **Decision (2026-11-06): U12's past approvals are valid; inactive users can't act from now on.**
  - **Not a rule break**, unlike D17–D19, where the wrong person approved at the time. The import report notes them as "approver since deactivated", not as a breach.
  - **PO-0136** has 2 valid finance approvals, so it can be received normally. Being old with nothing received, it's reviewed in D22.
  - **Future:** an inactive user can't log in or approve. Existing sessions end when a user is deactivated.
  - The progress rule from D17–D19 doesn't apply (it's for wrongly approved POs only).
  - Analogy for the review: a cheque signed by a manager who later leaves is still valid; they just can't sign new ones.
  - Consistency check: D17–D19 ✅, D27 ✅ (U12 can't sign in), D2 ✅ (PO-0136 includes unpriced SW-SNK-00082; it can be received but not sold), D22 (PO-0136 is stuck).
  - Tests: an inactive user can't log in; a deactivated user's open session is rejected on the next request; an inactive user can't approve.

### D21. PO-0210 says PARTIALLY_RECEIVED but nothing was received
- Facts: PO-0210 (S01, created by U08 on 2026-06-18, 140 days old, ₹21,380). Approved by U10 (finance, valid; under ₹50k so 1 approval is enough). Lines: SW-BEV-00022 0/40, SW-SNK-00059 0/300.
- Options: (a) import as APPROVED (status derived from quantities); (b) keep the status and flag it.
- **Decision (2026-11-09): trust the quantities, not the label.**
  - PO-0210 imports as **APPROVED, nothing received**. The original label `PARTIALLY_RECEIVED` is kept in the raw copy and noted in the import report.
  - **Future:** a PO's receiving status (Approved / Partly received / Received) is **always calculated from the received quantities**, never typed in, so it can't contradict them.
  - Consistency check: D17–D19 ✅ (a valid approval, and the progress rule correctly sees "nothing received"), D30 ✅ (quantities unchanged; only the derived label is corrected, with the original kept), D22 (140 days with nothing received, so it's stuck).
  - Test: receiving goods moves Approved → Partly received → Received automatically.

### D22. All 20 DRAFT POs are older than 14 days
e.g. PO-0011 created 2026-03-08. The nightly job would expire every one on its first run.
- Facts (as of 2026-11-09):
  - **20 drafts**, aged 27–303 days, total value ₹14,64,530.
  - **Stuck POs:** 42 approved with nothing received (21–301 days, 41 over 30 days, ₹31,43,030) and 42 partly received (20–291 days, 41 over 30 days). The youngest RECEIVED PO is 17 days old.
  - 32 POs are SUBMITTED and waiting for finance (17–294 days, median 159).
- **Decision (2026-11-09): expire old drafts; flag stuck POs.**
  - **1. Draft expiry (required by the brief, nightly job):** a draft untouched for more than **14 days** becomes **EXPIRED**. The 14 days is **remote config**. All 20 imported drafts expire on the first run (a live demo of the job). An expired draft can't be submitted, and re-running the import must not bring it back to DRAFT (D28).
  - **2. Stuck POs (same pattern as D32):**
    - **Past:** nothing is closed automatically; POs are imported as they are.
    - **From now on:** an approved or partly received PO waiting **more than 30 days** for goods (remote config) is marked **"Stuck"**. The store manager gets **one grouped notification** ("12 purchase orders are waiting too long").
    - The manager can **close** a stuck PO ("stop expecting the rest") with a reason. Received goods stay; the rest is cancelled. This is audited.
  - Waiting-for-finance age is already visible through D7's "oldest waiting N days" on finance notifications.
  - Consistency check:
    - D32 ✅ (same pattern), D30 ✅ (no auto-close).
    - D7 ✅ (adds a 4th store-manager group, "Stuck POs").
    - D17–D19 ✅ (PO-0116 and PO-0258 can be closed instead of re-approved). D20 ✅ (PO-0136 stuck). D21 ✅ (PO-0210 stuck).
    - D2 ✅ (open POs with unpriced products can be closed).
  - Tests: a draft at exactly the limit; changing the limit in remote config changes which drafts expire; the job runs twice the same night (no double effect); closing a partly received PO keeps the received stock; an expired draft can't be submitted.

### D23. 23 SUBMITTED POs over ₹50,000 are awaiting approval (informational)
Good real data for demoing the two-approver flow.
- **Decision (2026-11-09): closed, information only.** Imported as they are. They appear in finance's "Needs approval" group (D7, with the oldest wait shown) and are used to demo submitted → 1st approval → 2nd approval → approved.

---

## Users and stores

### D24. No suppliers file
Only the IDs SUP01–SUP25 exist (in products and POs); no names or contacts.
- Facts: all 25 IDs are used by both products (21–40 products each; 22 suppliers span all 7 categories, 3 span 6) and POs. **Every PO buys from exactly one supplier** (0 POs mix suppliers). No supplier name or contact exists anywhere in the data.
- **Decision (2026-11-09): create 25 suppliers from the IDs; invent nothing.**
  - 25 supplier records named "Supplier SUP01" … "Supplier SUP25", with contact fields empty. Admins edit the details from the Suppliers screen (the brief's "manages suppliers").
  - **Future:** every new product needs a supplier, and a PO buys from **one** supplier only (matching the data). Admins can add, edit and deactivate suppliers; a deactivated supplier can't get new POs, and its history stays.
  - Consistency check: D30 ✅ (no invented data), D22 ✅, D2/D3 ✅ (unrelated).
  - Tests: a PO mixing products from two suppliers is rejected; a product without a supplier is rejected.

### D25. No passwords in `users.csv`
- Options: (a) one shared starting password for demo, listed in the README; (b) a random per-user password printed once by the import; (c) a set-password link by email.
- **Decision (2026-11-09): a shared demo password from configuration; first-login change is a remote-config switch.**
  - Every imported user gets one **demo starting password**. It's set by environment configuration (not hard-coded) and listed in the README, so reviewers can sign in as any role within the 15-minute setup.
  - Passwords are stored **hashed** (BCrypt or similar), never in plain text, and **never logged**.
  - **"Change password on first login"** is a **remote-config switch**: **off** for the shared demo environment (otherwise the first reviewer would lock out the others), **on** for real use. It's another live example of remote config changing behaviour (PIP objective 7).
  - Inactive users (U12) can't sign in (D20, D27).
  - Consistency check: D20 ✅, D27 ✅, D16 ✅ (no personal data in logs).
  - Tests: wrong password rejected; inactive user rejected; the password never appears in logs or API responses; with the switch on, a first login forces a change before anything else.

### D26. S01 and S02 have two managers
U03 + U08 for S01, U04 + U09 for S02. `stores.csv` names only U03 and U04.
- Facts: both managers share the work almost equally. S01 orders: U03 1,974 vs U08 1,989; POs 29 vs 28. S02 orders: U04 1,621 vs U09 1,602; POs 24 vs 22.
- **Decision (2026-11-09): both are full, equal managers.**
  - Both get full access to their store, with the same screens and permissions.
  - `stores.manager_user_id` is shown as the store's **main contact**, a label only, with no extra permission.
  - Both receive the store's grouped notifications (D7, already counted in the 14 day-one notifications).
  - **Future:** a store can have one or more managers, assigned by an admin. A manager is always tied to exactly one store.
  - Consistency check: D7 ✅, D18/D19 ✅ (managers still can't approve POs), D30 ✅.
  - Test: both S01 managers see S01 data and neither sees S02 data.

### D27. U12 (finance approver) is inactive
That leaves 2 active finance approvers (U10, U11), which is just enough for second approvals.
- **Decision (2026-11-09): no data change; the risk is noted.**
  - U12 can't sign in or approve (D20, D25). Her past approvals stay valid (D20).
  - **Risk:** with only U10 and U11 active, every PO over ₹50,000 needs **both**. If one is unavailable, big POs wait. Noted in the import report and in "known issues".
  - Mitigation: finance notifications show "oldest waiting N days" (D7), and an admin can add or reactivate a finance user.
  - Consistency check: D7 ✅, D17–D20 ✅, D25 ✅.

---

## Import behaviour

### D28. When the import runs again, does the file or the app win?
*Added 2026-11-06, raised while deciding D3.*
The brief requires a **repeatable** import. After the first import, people change data in the app: an admin unarchives SW-SNK-00064, sets a price for a D2 product, or a manager adjusts stock. If the import runs again, the CSV/JSON still holds the old values, so a careless import would silently undo those changes.
- Options:
  - (a) **Insert only:** re-runs add records that don't exist yet and never overwrite existing ones.
  - (b) **File always wins:** re-runs overwrite everything from the files.
  - (c) **File wins unless the app changed it:** overwrite only records nobody has edited in the app since the last import.
  - (d) **Import only into an empty database:** a re-run resets everything (useful for demos and tests only).
- **Decision (2026-11-09): add new records only and never overwrite, plus a separate reset command.**
  - **Normal re-run (insert-only, idempotent):** records that already exist (by their natural key: SKU, store+SKU, order ID, PO ID, user ID, store ID) are **left untouched**. New records are added through all the same rules (D1–D32). Unknown products in new data are flagged for the admin (D12). Running it twice gives the same result as running it once.
  - Example: an admin sets a price for a D2 product; a re-run doesn't wipe it.
  - **Each run writes a short report:** added / skipped (already present) / rejected, with reasons. The import is safe to repeat and to re-start after a failure.
  - **"Reset demo data"** is a separate, explicit command for demos and tests: it wipes and re-imports from the files. It never runs as part of a normal start-up.
  - Consistency check: protects D2 (prices), D3 (archive/unarchive), D5 (no second correction entry), D10 (review decisions), D22 (expired drafts stay expired), D12 (unknowns flagged), D8 (duplicates still kept once). ✅ No conflicts.
  - Tests: import twice gives identical counts; change a price, re-run, and the price is kept; a new orders file adds only the new orders; reset restores the original state.

---

## Invoice samples (`upload_samples/`): test cases, no decision needed

| File | What it really is | Expected result |
|---|---|---|
| invoice_valid.pdf | Valid PDF | Accept |
| invoice_scan.png | Valid PNG 300×200 | Accept |
| invoice_too_large.pdf | Valid PDF, 22.0 MB | Reject: over 20 MB |
| invoice_truncated.pdf | Starts as PDF, cut off (no `%%EOF`) | Reject: corrupt |
| invoice_renamed.pdf | A Windows `.exe` (starts with `MZ`) renamed to `.pdf` | Reject: type checked by content, not name |
