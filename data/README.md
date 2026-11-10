# Provided data

These files are an **exact copy** of the dataset supplied with the assignment (byte-identical, checked with SHA-256 on 2026-11-10). They are **never edited**. All handling of problems in the data happens in the import code, following the decisions in [docs/data-findings.md](../docs/data-findings.md) (D1–D32).

| File | Contents |
|---|---|
| `stores.csv` | 5 stores |
| `users.csv` | 12 users with roles and store assignments |
| `products.csv` | 800 product rows (includes 12 messy-SKU variants, see D1) |
| `inventory.csv` | 4,000 stock rows (5 stores × 800 products) |
| `orders.json` | 15,000 order records (14,960 real orders + 40 duplicates, see D8) |
| `purchase_orders.json` | 300 purchase orders with approvals |
| `upload_samples/` | Invoice upload test files (see below) |

## Upload samples

| File | What it really is | Expected result |
|---|---|---|
| `invoice_valid.pdf` | Valid PDF | Accepted |
| `invoice_scan.png` | Valid PNG image | Accepted |
| `invoice_truncated.pdf` | PDF cut off part-way | Rejected: corrupt |
| `invoice_renamed.pdf` | A Windows executable renamed to `.pdf` | Rejected: type is checked by content, not name |

**Not copied:** `invoice_too_large.pdf` (22 MB, a valid PDF over the 20 MB limit). Anything committed to Git stays in its history forever, so instead the upload test **generates** a file just over 20 MB at run time and checks that it's rejected. The behaviour proved is the same.
