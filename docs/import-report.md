# Import report

**What we found in the provided data, and what we did about it.**

- The findings and the reasoning behind every decision are in [data-findings.md](data-findings.md) (D1–D32).
- This report records **what the import actually did**, taken from the import's own log in the database (`import_runs`, `import_issues`, `raw_import_records`) after the local import of 2026-11-10.
- Every number here is also checked by an automated test that imports the real files (`DataImportTest`).

## 1. Summary

| File | Records in file | Imported | Skipped | Rejected |
|---|---:|---:|---:|---:|
| `stores.csv` | 5 | 5 | 0 | 0 |
| `users.csv` | 12 | 12 | 0 | 0 |
| suppliers (no file; built from IDs in `products.csv`, D24) | 25 IDs | 25 | 0 | 0 |
| `products.csv` | 800 | 800 | 0 | 0 |
| `inventory.csv` | 4,000 | 4,000 | 0 | 0 |
| `orders.json` | 15,000 | **14,960** | **40** identical duplicate copies (D8) | 0 |
| `purchase_orders.json` | 300 | 300 | 0 | 0 |

- **Nothing was rejected.** Every problem in the data had a decided, evidence-based handling.
- **The original of every imported record is kept unchanged**: 20,077 raw records, including spelling such as a trailing space in a SKU. Corrections sit alongside it and never overwrite it.

**Revenue after all decisions** (DELIVERED orders, excluding the admin review queue): **₹1,45,38,055**.

| Step | Delivered revenue |
|---|---:|
| All 15,000 records as they are in the file | ₹1,45,62,704 |
| D8: count each duplicated order once (−31 orders) | ₹1,45,30,337 |
| D10: hold the 15 negative-quantity orders for admin review | ₹1,45,32,611 |
| D11: fill 20 missing prices from sales history | **₹1,45,38,055** |

## 2. What was found and what was done

"Outcome" is how each decision appears in the import log: **CORRECTED** (a value was transformed, original kept), **FLAGGED** (kept as received but marked for a person to act on), **SKIPPED** (not imported again), **INFO** (handled as decided, noted for the record).

### Stores and users

| Found | Done | Decision | Logged |
|---|---|---|---|
| No passwords in `users.csv` | Every user gets the demo starting password, stored as a BCrypt hash | D25 | 12 INFO |
| U12 (finance approver) is inactive | Imported as inactive: cannot sign in or approve | D27, D20 | 1 INFO |
| S01 and S02 each have two managers; `stores.csv` names one | Both are full managers; the named one is a "main contact" label | D26 | 2 INFO |

### Suppliers and products

| Found | Done | Decision | Logged |
|---|---|---|---|
| No suppliers file; only IDs SUP01–SUP25 | 25 suppliers created, named after their IDs, for admins to complete | D24 | 25 INFO |
| 12 products listed twice with a messy SKU (trailing space or lowercase) | Kept as separate products; variant stored trimmed + lowercase (e.g. `SW-SNK-00091 ` → `sw-snk-00091`) | D1 | 6 CORRECTED (spaces), 6 INFO (already lowercase) |
| 10 products with no selling price | Imported without a price; blocked from sale until an admin sets one | D2 | 10 FLAGGED |
| 25 archived products still holding stock | Status, stock and history kept; no new orders or purchase orders | D3 | 25 INFO |
| Products sold before their `created_at` | Kept as is; `created_at` read as "added to catalogue on" | D30 | — |

### Inventory

| Found | Done | Decision | Logged |
|---|---|---|---|
| Stock rows for the 12 variant products | Each row belongs to its exact spelling, so the variant has its own stock | D1, D4 | — |
| 15 rows with negative stock (total −72) | Imported as 0, flagged "recount needed", with a history entry | D5 | 15 CORRECTED |
| `last_counted_at` in 6 formats | One date reader: no timezone = IST, day-first proven, epoch exact | D6 | — (all 4,000 read) |
| 1,073 rows already at or below reorder level | No alert burst on import; grouped notifications later | D7 | — |

### Customer orders

| Found | Done | Decision | Logged |
|---|---|---|---|
| 40 order IDs appear twice, identical in every field | Imported once | D8 | 40 SKIPPED |
| `created_at` in 5 formats, some as JSON numbers | Same date reader as D6; 12 AM/PM handled | D9 | — (all 14,960 read) |
| 15 delivered orders with a negative quantity | Kept as received, placed in the admin review queue, excluded from revenue | D10 | 15 FLAGGED |
| 20 order lines with no price | Filled from sales history (each item had one consistent price matching the catalogue) | D11 | 20 CORRECTED |
| 25 lines with unknown products (`SW-ZZZ-…`) | Kept, labelled "Unknown product", counted in revenue, no review | D12 | 25 INFO |
| 30 lines with a messy SKU | Matched to the right product with the D1 ladder (sold price as tie-break) | D13 | 30 CORRECTED |
| 35 orders listing a product twice at the same price | Merged into one line with the quantities added | D14 | 35 CORRECTED |
| Prices differ from today's catalogue (mostly archived products) | Kept as recorded: the price at the time of sale | D15 | — |
| No customer master; names repeat, phones never do | Customer kept on the order; phones masked in lists and exports | D16 | — |
| 1,010 orders still open, many months old | Kept as is; flagged "stuck" after 7 days by the app | D32 | — |
| Does stock already include the open orders? | Yes: imported orders never move stock again | D29 | — |

### Purchase orders

| Found | Done | Decision | Logged |
|---|---|---|---|
| 6 POs over ₹50,000 approved by only one finance approver | Flagged `SINGLE_APPROVAL`; history kept | D17 | 6 FLAGGED |
| 7 POs approved by a store manager | Those approvals don't count; flagged `INVALID_APPROVER` | D18 | 7 FLAGGED |
| PO-0135 approved by its own creator | Doesn't count; flagged `SELF_APPROVAL` | D19 | 1 FLAGGED |
| What happens next to those 14 POs | **Progress rule:** completed = kept; partly received = may finish; nothing received or self-approved = re-approval before more goods. Result: **PO-0116, PO-0135, PO-0258** need re-approval | D17–D19 | — |
| 3 approvals by U12, now inactive | Still valid (correct role at the time) | D20 | 3 INFO |
| PO-0210 says "partly received" with nothing received | Status derived from quantities: APPROVED | D21 | 1 CORRECTED |
| Approvals listed in no consistent order; 3 POs approved twice in the same second | Order taken from the time; same-second pairs are both valid | D31 | 3 INFO |
| 20 old drafts; 84 approved POs waiting for goods | Imported as is; the nightly job expires drafts after 14 days and flags stuck POs after 30 | D22 | — |

## 3. Re-running the import

The import is **insert-only** (D28): a record that already exists is skipped, never overwritten, so changes made in the app survive a re-run. The log shows this in practice: three local runs were made on 2026-11-10 as slices were added, and each later run skipped everything already present and added only the new files:

| Run | Added | Skipped |
|---|---|---|
| 1 | stores 5, users 12 | — |
| 2 | suppliers 25, products 800, inventory 4,000 | stores, users |
| 3 | orders 14,960, purchase orders 300 | everything above |

`DataImportRerunTest` proves that an admin's changes (a set price, an unarchive, a recount) survive a re-run.

## 4. How to check these numbers yourself

```sql
-- decisions applied, by code and outcome
SELECT decision_code, outcome, count(*) FROM import_issues GROUP BY 1, 2 ORDER BY 1, 2;

-- what each run added and skipped
SELECT id, status, summary FROM import_runs ORDER BY id;

-- the untouched original of any record, e.g. a variant product
SELECT payload FROM raw_import_records WHERE source_file = 'products.csv' AND natural_key = 'SW-SNK-00091 ';
```

Run them with `docker compose exec postgres psql -U shelfwise -d shelfwise`.
