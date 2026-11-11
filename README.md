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
| File storage | S3-compatible: SeaweedFS locally, Cloudflare R2 deployed |
| Auth | Spring Security (reactive), access + refresh tokens, BCrypt |
| Remote config | Own settings table + `/config` endpoint |
| Web app | React + TypeScript + Vite, tested with Vitest (all roles) |
| Mobile app | React Native with Expo, store managers *(to confirm)* |
| Hosting | Railway (containers), Cloudflare R2, Expo EAS for the Android build |

Why each was chosen, the alternatives, and what would change at 10× load: **[Decision log](docs/decision-log.md)**.

How the web and mobile apps look (colours, status chips, layouts, light and dark themes): **[Design system](docs/design-system.md)**, with mockups for the [light](docs/design/ui-v3-light.html) and [dark](docs/design/ui-v3-dark.html) themes.

## Roles

- **Admin (head office):** all stores; manages products, suppliers, users; dashboard and exports.
- **Store manager:** one store; stock, customer orders, draft purchase orders, receiving.
- **Finance approver (head office):** approves or rejects purchase orders; orders over ₹50,000 need two different approvers.

## Data

The provided data files are profiled and every issue found has an explicit handling decision:

- **[Data findings and decisions (D1–D32)](docs/data-findings.md)**: what's wrong in the data and why each handling was chosen.
- **[Import report](docs/import-report.md)**: what the import actually did, with counts from the import log. Delivered revenue after all decisions: ₹1,45,38,055.

Guiding principles from those decisions:
- Keep the original: imported records are stored as received; corrections sit alongside, never overwriting.
- The past stays as it is; rules protect the future.
- Never guess: a value is filled only from evidence, otherwise it is flagged for an admin.
- Re-running the import adds new records and never overwrites changes made in the app.

## How to run

**You need:** [Docker Desktop](https://www.docker.com/products/docker-desktop/) (on Windows, with WSL). Nothing else; Java and Maven run inside the containers.

```bash
docker compose up -d --build
```

The first run builds the images and takes a few minutes. On the first start the core service also imports all the provided data (about 50 seconds, ~48,000 order rows) before it reports healthy; later starts skip what's already imported and take seconds.

| What | Where |
|---|---|
| Core service health | http://localhost:8080/actuator/health |
| Notification service health | http://localhost:8081/actuator/health |
| PostgreSQL | `localhost:5432`, database `shelfwise` (local development credentials in `docker-compose.yml`) |
| RabbitMQ management | http://localhost:15672 (`guest` / `guest`, local only) |

On start-up the core service applies the database migrations (Flyway) and imports the provided data from `data/`. Re-starting is safe: the import only adds records that aren't there yet and never overwrites changes made in the app (D28).

### Demo accounts

All imported users share the local demo password **`ShelfWise-Demo-2026`** (set by `DEMO_PASSWORD` in `docker-compose.yml`, stored hashed; D25).

| Email | Role | Store |
|---|---|---|
| `rohit.menon@shelfwise.example` | Admin | all |
| `arjun.rao@shelfwise.example` | Store manager | S01 |
| `imran.sheikh@shelfwise.example` | Store manager | S01 (second manager, D26) |
| `sneha.reddy@shelfwise.example` | Store manager | S02 |
| `meera.joshi@shelfwise.example` | Finance approver | all |
| `anil.gupta@shelfwise.example` | Finance approver | all |
| `pooja.bansal@shelfwise.example` | Finance approver, **inactive**: sign-in is refused (D20, D27) | — |

The other users in `data/users.csv` work the same way.

Stop everything with `docker compose down`, or `docker compose down -v` to also delete the local database.

### Web app (development)

Needs Node.js 22 or newer, and the backend running (`docker compose up -d --build`).

```bash
cd web
npm install
npm run dev
```

Open http://localhost:5173. `/api` calls are forwarded to the core service on port 8080.
Tests: `npm test`. Type check: `npm run typecheck`. Production build: `npm run build`.

Where each role lands, what is in each menu, and the steps of every workflow: **[Navigation and workflows](docs/navigation-and-workflows.md)**.

Screens follow the [design system](docs/design-system.md) (UI v3, light and dark):
- **Orders, Inventory, Catalogue:** KPI cards that double as quick filters, search and filters, numbered pages. Orders also has a date range and bulk status moves (each order is sent with its own version, so a conflict is reported per order).
- **Purchase orders:** a list with KPIs; a detail page with the approval tracker (Submitted → Approver 1 → Approver 2 above ₹50,000 → Receiving); finance approves or rejects with a reason; managers draft from low-stock items (one supplier per PO), submit, receive in parts and close a stuck PO.
- **Store switcher** (Admin and Finance: all 5 stores or one store; managers see their own store) applies to every screen.
- **Theme:** follows the device setting; the moon/sun button next to your name switches it, and the choice is remembered on that device.

### Sign-in API

| Endpoint | Purpose |
|---|---|
| `POST /api/auth/login` | `{ "email", "password" }` → access token (15 min), refresh token (7 days), user's id, name, role, store |
| `POST /api/auth/refresh` | `{ "refreshToken" }` → a new pair; the old refresh token stops working |
| `POST /api/auth/logout` | `{ "refreshToken" }` → revokes it (204) |
| `GET /api/me` | The signed-in user (header `Authorization: Bearer <access token>`) |

### Catalogue API

All need `Authorization: Bearer <access token>`. Changes are admin-only.

| Endpoint | Who | Purpose |
|---|---|---|
| `GET /api/products?q=&category=&status=&sort=&direction=&page=&size=` | all | Search name or SKU, filter, sort (`name`, `sku`, `category`, `price`, `reorderLevel`), page (max 100). Default: active products only |
| `GET /api/products/categories` | all | Category list for filters |
| `GET /api/products/{sku}` | all | One product. SKUs are case-sensitive (D1) |
| `POST /api/products` | admin | Create. Needs a price (D2) and a clean SKU like `SW-ABC-12345` |
| `PUT /api/products/{sku}` | admin | Edit. Send the `version` you loaded; if someone saved since, the answer is 409 |
| `POST /api/products/{sku}/archive` · `/unarchive` | admin | `{ "version", "reason" }`. Archived = no new orders or POs; stock and history kept (D3) |

Every change is written to the audit log (who, when, before and after).

### Inventory API

Store managers see and change only their own store; admins and finance see all stores; only admins and the store's managers change stock.

| Endpoint | Purpose |
|---|---|
| `GET /api/inventory?storeId=&q=&lowOnly=&recountOnly=&sort=&direction=&page=&size=` | Stock list; `lowOnly` = at or below reorder level |
| `GET /api/inventory/{storeId}/{sku}` · `/history` | One item, and every change to it with its reason |
| `POST /api/inventory/{storeId}/{sku}/adjustments` | `{ "type": "DAMAGED" \| "RECOUNT" \| "OTHER", "quantity", "note" }`. Damaged removes N; recount sets the counted number and clears "recount needed"; other is ± N with a required note. Never below 0 (409) |

### Orders API

| Endpoint | Purpose |
|---|---|
| `GET /api/orders?storeId=&status=&q=&from=&to=&page=&size=` | Orders, newest first; `q` matches order ID or customer name; `from`/`to` are IST dates. Phones masked; `stuck` = open longer than 7 days (remote config, D32) |
| `GET /api/orders/{id}` | One order with its lines. Full phone only for the store's own managers (D16) |
| `POST /api/orders` | Store managers, own store. `{ "customerName", "customerPhone": "+91 9876543210", "lines": [{ "sku", "qty" }] }` plus header `Idempotency-Key`. Stock is taken at creation; if any line is short, nothing is saved (409) |
| `POST /api/orders/{id}/status` | `{ "to", "version" }`. Pending → Packed → Shipped → Delivered; Cancelled only from Pending or Packed, and it returns the stock (D29) |

What can go wrong and how it's handled: [edge-case register](docs/edge-cases.md).

### Purchase orders API

| Endpoint | Who | Purpose |
|---|---|---|
| `GET /api/purchase-orders?storeId=&status=&supplierId=&q=&page=&size=` | all (managers: own store) | POs, newest first, with `validApprovals`, `approvalsNeeded`, `requiresReapproval` and `stuck` |
| `GET /api/purchase-orders/{id}` | all (managers: own store) | One PO with its lines and approvals (in time order, D31) |
| `GET /api/purchase-orders/suggestions?storeId=` | all | Low-stock items of one store with a suggested quantity (back up to twice the reorder level) |
| `POST /api/purchase-orders` | store manager | Draft for own store: `{ "supplierId", "lines": [{ "sku", "qty" }] }`. One supplier, active products only; unit cost = catalogue cost today |
| `POST /api/purchase-orders/{id}/submit` | manager of that store, admin | `{ "version" }`. Draft → Submitted (an expired draft can't be submitted, D22) |
| `POST /api/purchase-orders/{id}/decision` | finance | `{ "decision": "APPROVED" or "REJECTED", "reason", "version" }`. Above ₹50,000 (remote config) two **different** approvers; never the PO's creator; a rejection needs a reason. Also re-approves imported POs flagged by D17-D19 |
| `POST /api/purchase-orders/{id}/receive` | manager of that store, admin | `{ "lines": [{ "sku", "qty" }], "version" }`. Any part of the order; never above what was ordered. Stock goes up; status becomes Partially received / Received (D21). Blocked while re-approval is needed |
| `POST /api/purchase-orders/{id}/close` | manager of that store, admin | `{ "reason", "version" }`. Only a stuck PO (approved, waiting more than 30 days, D22). Goods already received stay |

Every change sends the PO's `version`; if someone else changed it first the answer is 409 (reload and try again). Each step writes an event to the outbox for the notification service ([event contract](docs/events.md)).

### Dashboard and CSV export API

| Endpoint | Purpose |
|---|---|
| `GET /api/dashboard?storeId=&from=&to=&trendBy=` | Revenue, order count (split by status), top 10 products, low-stock count and the trend per store and day (or month). Dates are IST days; no dates = everything. Ranges over 92 days default to a monthly trend. Managers get their own store |
| `GET /api/orders/export.csv?storeId=&status=&q=&from=&to=` | The filtered order list as a CSV file (same filters as `GET /api/orders`, every page). Phones masked; opens in Excel |

What the figures mean (decided 2026-11-11):
- **Revenue** = delivered orders only (pending, packed and shipped can still be cancelled). On the provided data: **₹1,45,38,055**.
- **Order count** = every order placed in the range, with a split by status, so cancellations stay visible.
- **Top products** = ranked by revenue from delivered orders, units shown too. Lines with unknown product codes (D12) appear separately (₹15,994).
- **Low-stock count** = items at or below their reorder level right now (1,046, of which 190 out of stock); archived products don't count. It isn't date-ranged.
- Orders waiting for admin review (D10, 15 imported orders) are left out of every figure and the export until reviewed.

### Notifications API (notification service, port 8081)

The core service publishes events through RabbitMQ ([event contract](docs/events.md)); the notification service keeps grouped notifications the D7 way and serves the bell (DL-12). Send the same access token as for the core service.

| Endpoint | Purpose |
|---|---|
| `GET /api/notifications` | Your notifications, newest change first, with `unreadCount`. Store managers: their store's "out of stock", "running low" and PO decisions. Finance: "need your approval" and "need a second approval", with the oldest waiting age |
| `POST /api/notifications/{id}/read` | Mark one as read (it becomes unread again only if its count goes up) |
| `POST /api/notifications/read-all` | Mark all as read |

On day one (after the import) each store manager sees 2 grouped notifications instead of about 1,400 single ones; e.g. S01: "... and 39 other items are out of stock in S01", "... and 143 other items are running low in S01". RabbitMQ's own screen is at http://localhost:15672 (guest / guest, local only).

### Mobile app (store managers)

React Native + Expo SDK 58 (DL-13). Store managers sign in and see their own store's stock (search, "low stock only",
adjustments with a reason: damaged, recount, other) and the catalogue. Orders come next.

```bash
cd mobile
npm ci
EXPO_PUBLIC_API_URL=http://<your-laptop-ip>:8080 npx expo start   # scan the QR code with Expo Go on the phone
```

- On a phone, `EXPO_PUBLIC_API_URL` must be your laptop's address on the same Wi-Fi (not `localhost`).
- Browser preview: `npx expo start --web --port 8082`, with the core service started with `CORS_ALLOWED_ORIGINS=http://localhost:8082`.
- Tests: `npm test`; type check: `npm run typecheck`. CI: `.github/workflows/mobile.yml` (runs only when `mobile/` changes).

### Running the backend tests

Needs Java 21 and Docker running (tests start a throwaway PostgreSQL):

```bash
cd backend
./mvnw verify
```

Each service can also be built and tested on its own, e.g. `./mvnw -pl core-service -am verify`.

## How AI tools were used

- Claude (Anthropic) was used to profile the provided data files with throwaway scripts, to surface problems and their evidence (record IDs, file line numbers), and to draft explanations and options.
- Every data-handling decision (D1–D32) was made by me, one at a time, after checking the evidence; several rules are my own (e.g. grouped notifications, the admin review queue, the purchase-order progress rule).
- All generated code and text is reviewed before it is committed. More detail will be added as the build progresses.

## Known issues and next steps

_To be added._
