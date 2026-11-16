# Deploying ShelfWise (M30)

Railway runs the two services, PostgreSQL, RabbitMQ and the web app; Cloudflare R2 stores invoice files; Expo EAS builds
the Android app (DL-07). Everything is built from this repository; nothing is copied by hand.

```
 browser / phone ──https──▶ web (nginx) ──private──▶ core-service :8080 ──▶ PostgreSQL
                              │  /api/notifications                │ outbox relay
                              └──────private──▶ notification-service :8081 ◀── RabbitMQ
                                                core-service ──▶ Cloudflare R2 (invoices)
```

The **web service is the one public address**: it serves the app and forwards `/api` to the core service and
`/api/notifications` to the notification service over Railway's private network. The Android app uses the same address.
The core service can also get its own public domain (handy for `/actuator/health`), but it isn't required.

## 1. Cloudflare R2 (invoice files)

1. Cloudflare dashboard → R2 → **Create bucket** `shelfwise-invoices` (location: automatic).
2. R2 → **Manage API tokens** → Create token: permission **Object Read & Write**, limited to that bucket.
3. Note the **Access Key ID**, **Secret Access Key** and the **S3 endpoint** `https://<account-id>.r2.cloudflarestorage.com`.
   Keys go only into Railway variables, never into the repository.

## 2. Railway project

1. Railway → **New project** → **Deploy from GitHub repo** → `ShelfWise`. Railway asks to install its GitHub app on the repo.
2. Add **PostgreSQL** (New → Database → PostgreSQL) and **RabbitMQ** (New → Template → RabbitMQ).
3. Add three services from the same repo, each with its settings:

| Service name | Root directory | Config file (Settings → Config-as-code) | Public domain |
|---|---|---|---|
| `core-service` | `/` | `/deploy/railway/core-service.json` | optional, port 8080 |
| `notification-service` | `/backend` | `/deploy/railway/notification-service.json` | none needed |
| `web` | `/web` | `/deploy/railway/web.json` | **yes**: Settings → Networking → Generate domain |

The config files set the Dockerfile, the health check (`/actuator/health/readiness`) and which folders trigger a
redeploy (the same split as CI, DL-08). The core service's first start imports the provided data, so its health check
waits up to 10 minutes.

## 3. Variables

Railway variable references like `${{Postgres.PGHOST}}` fill in values from the other services. Generate the secret with
`openssl rand -base64 48` (or any password manager); it must be **the same** in both services.

**core-service**

| Variable | Value |
|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `${{Postgres.PGHOST}}` / `${{Postgres.PGPORT}}` / `${{Postgres.PGDATABASE}}` / `${{Postgres.PGUSER}}` / `${{Postgres.PGPASSWORD}}` |
| `JWT_SECRET` | the generated secret (at least 32 characters) |
| `DEMO_PASSWORD` | the starting password for the imported demo accounts (shared with reviewers) |
| `IMPORT_ON_STARTUP` | `true` (re-runs only add new records, D28) |
| `RABBITMQ_URI` | the RabbitMQ service's **private** AMQP URL, e.g. `${{RabbitMQ.RABBITMQ_PRIVATE_URL}}` (name depends on the template) |
| `STORAGE_ENDPOINT` | `https://<account-id>.r2.cloudflarestorage.com` |
| `STORAGE_REGION` | `auto` |
| `STORAGE_BUCKET` | `shelfwise-invoices` |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | the R2 token's keys |
| `CORS_ALLOWED_ORIGINS` | `https://<web domain>`. **Required:** without it the browser's sign-in gets 403, because requests forwarded by nginx arrive with the web address as their origin |
| `SERVER_ADDRESS` | `::` (listen on Railway's private network) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` (JSON log lines, DL-14) |

**notification-service**

| Variable | Value |
|---|---|
| `DB_HOST` … `DB_PASSWORD` | same references as the core service (own `notifications` schema, DL-12) |
| `JWT_SECRET` | **the same** secret as the core service |
| `RABBITMQ_URI` | same as the core service |
| `SERVER_ADDRESS` | `::` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` |

**web**

| Variable | Value |
|---|---|
| `CORE_URL` | `http://${{core-service.RAILWAY_PRIVATE_DOMAIN}}:8080` |
| `NOTIFICATIONS_URL` | `http://${{notification-service.RAILWAY_PRIVATE_DOMAIN}}:8081` |

(`PORT` is set by Railway; nginx listens on it.)

## 4. Check it

1. `https://<web domain>` → sign in as a demo account (README → Demo accounts) with `DEMO_PASSWORD`.
2. Core health: core logs show `Started CoreServiceApplication` and the import report; or, with a core public domain,
   `https://<core domain>/actuator/health` shows `outbox: UP`.
3. Approve a PO as finance; the store manager's bell shows the decision. Search both services' logs for the request id
   from the browser's `X-Request-Id` response header: the call, the publish and the handling all appear (DL-14).
4. Attach an invoice to a PO and open it again (R2).

## 5. Android app (Expo EAS)

1. `npm install -g eas-cli`, then in `mobile/`: `eas login` (your Expo account) and `eas init` (links the project; adds
   its id to `app.json`).
2. In `mobile/eas.json`, set `EXPO_PUBLIC_API_URL` to `https://<web domain>` (the bell uses the same address).
3. `eas build --platform android --profile preview` → builds an installable **APK** in Expo's cloud (about 15 minutes) and
   prints a link and QR code to download it on a phone.

## Rolling back

- **Code:** Railway → service → Deployments → **Redeploy** an earlier deployment.
- **Database:** migrations only add (each file says how to undo it); restore from Railway's PostgreSQL backups if needed.
- **Settings:** remote config changes are audited and can be set back in the web app's Settings screen (DL-06).
