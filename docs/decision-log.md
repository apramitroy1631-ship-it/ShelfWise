# Decision log

Each major choice: the options considered, the trade-offs, and what I would change at 10× the load.
Data-handling decisions (D1–D32) are in [data-findings.md](data-findings.md).

Fixed by the brief: **Java + Spring Boot WebFlux (used reactively)** for the backend, **React** for the web app.

---

## DL-01 Build tool: Maven (with wrapper)
*Locked 2026-11-10*
- **Options:** Maven, Gradle.
- **Chosen:** Maven with `mvnw`, multi-module (core service, notification service, shared code).
- **Why:**
  - `pom.xml` is declarative and predictable, so it's easy to explain line by line in a live review.
  - It's Spring Initializr's default.
  - The wrapper means reviewers need nothing installed.
- **Trade-off:** Gradle builds faster (incremental builds, build cache) and is more flexible.
- **At 10×:** with a bigger codebase and team, move to Gradle for build caching and faster CI.

## DL-02 Database: PostgreSQL + R2DBC + Flyway
*Locked 2026-11-10*
- **Options:** PostgreSQL, MySQL, MongoDB.
- **Chosen:** PostgreSQL, accessed through **R2DBC** (non-blocking, matching WebFlux). Schema changes go through **Flyway** migrations.
- **Why:**
  - The data is relational (stores, products, stock, orders, POs, approvals) and needs transactions plus database-enforced rules:
    - stock ≥ 0 (D5, D29)
    - unique (PO, approver) and (PO, approval step) (D31)
    - unique order ID (D8)
  - JSON columns hold the untouched raw copy of every imported record ("keep the original").
  - SERIALIZABLE isolation is available as a fallback (D31).
- **Trade-offs:**
  - Flyway runs over JDBC (blocking), but only once at start-up, never on request paths.
  - R2DBC is less feature-rich than JPA (no lazy relations); queries are written explicitly.
  - MongoDB was rejected for weaker multi-record rules; MySQL for a less mature reactive driver.
- **At 10×:** read replicas for the dashboard and reports; partition orders by month; tune connection pools.

## DL-03 Messaging: RabbitMQ + transactional outbox
*Locked 2026-11-10*
- **Options:** RabbitMQ, Kafka, Postgres outbox polled directly.
- **Chosen:** the core service writes each event to an **outbox table in the same transaction** as the business change. A relay publishes it to **RabbitMQ**, and the notification service consumes it.
- **Why:**
  - No event is lost if a service crashes between "save" and "publish".
  - A broker gives a clear "notification service reacts to events" boundary, as the brief asks.
  - It suits D7's grouped notifications (the consumer updates one record per person + type).
- **Trade-offs:**
  - Delivery is at-least-once, so consumers must be idempotent (handling the same event twice has no extra effect).
  - It's one more piece of infrastructure to run.
  - Kafka was rejected as heavy for 5 stores.
- **At 10×:** Kafka if event replay or very high throughput is needed; partition by store.

## DL-04 File storage: S3-compatible (SeaweedFS locally, Cloudflare R2 deployed)
*Locked 2026-11-10. **Amended 2026-11-10:** the original local choice was MinIO, but MinIO no longer publishes free container images (none on Docker Hub or Quay), so the local store is now **SeaweedFS**: free, open source, S3-compatible and actively published. The application code is unchanged, because it only uses the S3 API.*
- **Options:** object storage (S3 API), files in the database.
- **Chosen:** the S3 API through the AWS SDK's async (non-blocking) client. **SeaweedFS** runs in Docker locally, and **Cloudflare R2** is used when deployed.
- **Why:**
  - Invoices up to 20 MB stay out of the database and its backups.
  - One code path works in both environments.
  - R2's free tier is 10 GB with no download (egress) fees.
  - The file type is checked by its **contents**, not its name: the upload samples include an `.exe` renamed to `.pdf`, a truncated PDF and a 22 MB file.
- **Trade-off:** one more service; files and the database must be kept consistent (upload first, then record).
- **At 10×:** add virus scanning, lifecycle rules for old invoices, and short-lived signed download links.

## DL-05 Authentication: own, with Spring Security (reactive)
*Locked 2026-11-10*
- **Options:** own Spring Security, Keycloak, Auth0/Cognito.
- **Chosen:**
  - Short-lived access tokens and **refresh tokens stored (hashed) in the database**; passwords hashed with BCrypt.
  - Role and store checks are enforced **on the server** for every request.
  - Mobile keeps tokens in secure device storage.
- **Why:**
  - Nothing extra to run or explain.
  - It supports D20 (a deactivated user is cut off on their **next request**) and D25 (hashed passwords, a first-login-change switch, the demo password from config).
- **As built (2026-11-10):**
  - Access tokens last 15 minutes and refresh tokens 7 days. Refresh tokens are single-use (rotation), and reusing a revoked one ends all of that user's sessions.
  - The access token only identifies the user: **every request re-reads the user**, so deactivation and role changes apply at once. *Amended: this entry first said "at the next refresh"; D20 promised "next request", so the stronger rule was built.* Cost: one primary-key lookup per request.
  - Every sign-in failure gets the same message, and unknown emails still run a password check, so a failure reveals neither which emails exist nor (by timing) whether the email was right.
  - BCrypt runs off the request threads to keep the service non-blocking.
- **Trade-offs:** we own the security code, so it needs careful tests (expired, tampered and revoked tokens; role escalation). Keycloak was rejected as a heavy extra container, Auth0/Cognito as external accounts.
- **At 10×:** a dedicated identity provider for single sign-on and multi-factor login; cache the per-request user lookup for a few seconds; rate-limit sign-in attempts per email and IP.

## DL-06 Remote configuration: own settings table + `/config` endpoint
*Locked 2026-11-10*
- **Options:** own settings table, Firebase Remote Config, Unleash/Flagsmith.
- **Chosen:**
  - A versioned settings table, edited from an admin screen, with every change audited (who, when, old → new).
  - The services read it with a short cache.
  - The web and mobile apps fetch `/config` about every 60 s and when the app opens.
- **Why:** the brief requires behaviour changes **without a redeploy or an app-store release**. It covers:
  - the low-stock threshold, the maintenance banner and feature flags
  - our own limits: draft expiry 14 days (D22), stuck PO 30 days (D22), stuck order 7 days (D32)
  - the first-login password switch (D25)
- **Trade-off:** changes reach apps within about a minute, not instantly. Firebase was rejected because it's mobile-centric and adds a vendor.
- **At 10×:** a dedicated feature-flag service with percentage rollouts; push config changes instead of polling.

## DL-07 Hosting: Railway + Cloudflare R2 + Expo EAS
*Locked 2026-11-10*
- **Options:** Railway, Render free tier, a cloud provider's container service.
- **Chosen:**
  - **Railway** runs the Docker images: core service, notification service, PostgreSQL, RabbitMQ and the web app.
  - **Cloudflare R2** stores files.
  - **Expo EAS** builds the installable Android app.
- **Why:**
  - It's always on, with logs in one dashboard, which matters for the live troubleshooting exercise.
  - It deploys the same containers we run locally.
- **Trade-offs:**
  - The cost is small but not zero: the Hobby plan includes $5 of usage a month, and two Java services may need slightly more.
  - Render's free tier was rejected because services sleep after 15 minutes idle, with a 30–60 s cold start.
- **At 10×:** a managed container platform (e.g. a cloud provider's Kubernetes or container service) with autoscaling, a managed database and a managed broker.

## DL-08 Repository: one private GitHub repo, three independent folders
*Locked 2026-11-10. Requirement from my manager: mobile, web and backend must build independently, with no interdependency.*
- **Options:** one repository with independent folders (monorepo), three separate repositories (polyrepo).
- **Chosen:** one **private** GitHub repository, with `backend/`, `web/` and `mobile/` as **self-contained** folders.
  - Each folder has its own build tool, lock file, Dockerfile and **CI workflow**, and each workflow is triggered **only by changes in its own folder** (GitHub Actions path filters).
  - **No code is shared** between folders; nothing imports across them. The apps agree through **documents** only (the API description and the event contract), never through shared build artifacts.
  - Inside `backend/`, the core and notification services also build and test **separately**: one CI workflow each. They share only the Maven parent file (build settings), not code. The planned "shared code" module is dropped; the event format is a documented contract.
- **Why not three repositories:** the brief asks for everything in or linked from **one README**, a **one-command** local start and a single **v1.0 tag**. Three repositories would mean three READMEs and tags, and a harder local run, with no extra independence, because path-filtered CI already gives it.
- **Trade-off:** the folder boundary is a rule, not a physical wall, so CI will check that no folder references another.
- **At 10×:** with separate teams, split into separate repositories with independent release cycles and publish the API contract as a versioned package.

## DL-09 Service architecture: modular monolith core + one event-driven notification service
*Locked 2026-11-10*
- **Options:** a single monolith; a modular monolith plus a separate notification service; full microservices (catalogue, inventory, orders, purchasing… each separate).
- **Chosen:**
  - **Core service = modular monolith.** One deployable application, organised by **feature folders** (`auth/`, `catalogue/`, `inventory/`, `orders/`, `purchasing/`, `files/`, `dashboard/`, `config/`). Each folder holds that feature's API, logic and data access together.
  - **Notification service = separate microservice.** It consumes events from RabbitMQ (DL-03) and maintains the grouped notifications (D7).
- **Why not full microservices:**
  - Creating an order and taking stock must happen in **one database transaction** (D29); that's what guarantees the last unit can't be sold twice (PIP objective 2).
  - Splitting orders and inventory would need distributed coordination (sagas, compensation) and would bring back exactly the race conditions we're designing out.
  - At about 41 orders a day across 5 stores, the extra deployment, monitoring and debugging cost brings no benefit.
- **Why not a single monolith:**
  - The brief requires "a notification service that reacts to events from the core service".
  - Isolation matters: if notifications fail or slow down, sales and stock keep working, and the service catches up from the queue afterwards.
- **Why feature folders, not layer folders:** one feature's code sits together, so it's easier to read, explain and change live, and the folders can be split out later as services if ever needed.
- **At 10×:** extract the busiest feature (likely reporting/dashboard, reading from a replica) into its own service first. Keep orders and inventory together for as long as they share a transaction.

## DL-10 Web tooling: React + TypeScript + Vite, tested with Vitest
*Locked 2026-11-10*
- **Options:** Vite, Next.js, Create React App (retired).
- **Chosen:** React 19 with TypeScript, built by **Vite**, with unit tests in **Vitest** + Testing Library.
  - Exact versions are pinned and locked (`package-lock.json`); each was released at least two weeks before adoption.
  - In development, Vite forwards `/api` to the core service, so the browser sees one origin and no CORS setup is needed.
- **Why:**
  - The web app is a signed-in internal tool (no SEO, no server rendering needed), so a static single-page app built by Vite is the simplest fit.
  - TypeScript catches API-shape mistakes at build time.
  - Vitest shares Vite's config, so tests run with no extra tooling.
- **Rejected:** Next.js, whose server rendering and routing conventions add complexity this app doesn't need; Create React App, which is no longer maintained.
- **Trade-offs:**
  - The session is held **in memory**, so reloading the page signs the user out. That's safe (nothing is readable from storage) but less convenient. To revisit with the token-storage choice: an httpOnly cookie for the refresh token, which needs a same-origin deployment.
  - Deployment needs either same-origin hosting or CORS on the core service.
- **At 10×:** a component library and a design system, route-based code splitting, and end-to-end tests in a real browser on every change.

---

## Still to lock
- Mobile framework (proposed: React Native with Expo).
- CI/CD (GitHub Actions needs a GitHub repository, so nothing is pushed until approved).
- Logging, monitoring and tracing across services.
