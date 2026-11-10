# ShelfWise

Inventory, order and purchasing system for a retail chain of 5 stores.
Staff use it to run their shops: store managers handle stock, customer orders and deliveries (mostly on mobile); head office manages products, suppliers, approvals and reports (web).

> **Status:** early setup. This README grows with the project; everything required by the brief will live here or be linked from here.

## What it is made of

| Part | Technology |
|---|---|
| Core service | Java, Spring Boot WebFlux (reactive), Maven |
| Notification service | Java, Spring Boot WebFlux, Maven |
| Database | PostgreSQL via R2DBC, Flyway migrations |
| Messaging | RabbitMQ with a transactional outbox |
| File storage | S3-compatible: MinIO locally, Cloudflare R2 deployed |
| Auth | Spring Security (reactive), access + refresh tokens, BCrypt |
| Remote config | Own settings table + `/config` endpoint |
| Web app | React (all roles) |
| Mobile app | React Native with Expo, store managers *(to confirm)* |
| Hosting | Railway (containers), Cloudflare R2, Expo EAS for the Android build |

Why each was chosen, the alternatives, and what would change at 10× load: **[Decision log](docs/decision-log.md)**.

## Roles

- **Admin (head office):** all stores; manages products, suppliers, users; dashboard and exports.
- **Store manager:** one store; stock, customer orders, draft purchase orders, receiving.
- **Finance approver (head office):** approves or rejects purchase orders; orders over ₹50,000 need two different approvers.

## Data

The provided data files are profiled and every issue found has an explicit handling decision:

- **[Data findings and decisions (D1–D32)](docs/data-findings.md)**. This is the basis of the import report.

Guiding principles from those decisions:
- Keep the original: imported records are stored as received; corrections sit alongside, never overwriting.
- The past stays as it is; rules protect the future.
- Never guess: a value is filled only from evidence, otherwise it is flagged for an admin.
- Re-running the import adds new records and never overwrites changes made in the app.

## How to run

_To be added._ Goal: one command locally, running in under 15 minutes from this README.

## How AI tools were used

- Claude (Anthropic) was used to profile the provided data files with throwaway scripts, to surface problems and their evidence (record IDs, file line numbers), and to draft explanations and options.
- Every data-handling decision (D1–D32) was made by me, one at a time, after checking the evidence; several rules are my own (e.g. grouped notifications, the admin review queue, the purchase-order progress rule).
- All generated code and text is reviewed before it is committed. More detail will be added as the build progresses.

## Known issues and next steps

_To be added._
