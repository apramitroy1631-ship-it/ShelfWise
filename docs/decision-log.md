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

## DL-04 File storage: S3-compatible (MinIO locally, Cloudflare R2 deployed)
*Locked 2026-11-10*
- **Options:** object storage (S3 API), files in the database.
- **Chosen:** the S3 API through the AWS SDK's async (non-blocking) client. **MinIO** runs in Docker locally, and **Cloudflare R2** is used when deployed.
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
  - It supports D20 (a deactivated user is cut off at the next token refresh) and D25 (hashed passwords, a first-login-change switch, the demo password from config).
- **Trade-offs:** we own the security code, so it needs careful tests (expired, tampered and revoked tokens; role escalation). Keycloak was rejected as a heavy extra container, Auth0/Cognito as external accounts.
- **At 10×:** a dedicated identity provider for single sign-on and multi-factor login.

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

---

## Still to lock
- Mobile framework (proposed: React Native with Expo).
- Web tooling (proposed: React + TypeScript + Vite).
- CI/CD (GitHub Actions needs a GitHub repository, so nothing is pushed until approved).
- Logging, monitoring and tracing across services.
