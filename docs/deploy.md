# Deploying ShelfWise (M30)

All on free plans (DL-07, amended 2026-11-16): **Render** runs the two services and the web app, **Neon** is PostgreSQL,
**CloudAMQP** is RabbitMQ, **Cloudflare R2** stores invoice files, **Expo EAS** builds the Android app. Everything is
built from this repository (`render.yaml`); nothing is copied by hand.

```
 browser / phone ──https──▶ shelfwise-web (nginx) ──https──▶ shelfwise-core ──▶ Neon PostgreSQL
                              │  /api/notifications              │ outbox relay
                              └──────https──▶ shelfwise-notifications ◀── CloudAMQP (RabbitMQ)
                                              shelfwise-core ──▶ Cloudflare R2 (invoices)
```

The **web service is the one address people use**: it serves the app and forwards `/api` to the core service and
`/api/notifications` to the notification service. The Android app uses the same address.

**What "free" costs:** each Render service sleeps after 15 idle minutes. The first call after a pause wakes it, which
takes a few minutes for the two Java services. Before a demo, open the web address and wait until sign-in works.

## 1. Neon (PostgreSQL)

1. neon.com → sign up → **Create project**: name `shelfwise`, PostgreSQL 17, region **AWS Singapore**.
2. On the project page, **Connect** → switch **Connection pooling off** → note the four parts of the connection string:
   host (`ep-…​.ap-southeast-1.aws.neon.tech`, without `-pooler`), database (`neondb`), user and password.

The provided data needs about 35 MB (the free plan has 500 MB).

## 2. CloudAMQP (RabbitMQ)

1. cloudamqp.com → sign up → **Create new instance**: plan **Little Lemur (free)**, region **AWS ap-southeast-1 (Singapore)**.
2. Open the instance → copy the **AMQP URL** that starts with `amqps://`.

## 3. Cloudflare R2 (invoice files)

1. Cloudflare dashboard → R2 → **Create bucket** `shelfwise-invoices`. (Cloudflare may ask for a card to switch R2 on;
   the free allowance of 10 GB is far more than this needs.)
2. R2 → **Manage API tokens** → Create token: **Object Read & Write**, limited to that bucket.
3. Note the **Access Key ID**, **Secret Access Key** and the **S3 endpoint** `https://<account-id>.r2.cloudflarestorage.com`.

Any S3-compatible storage works with the same five `STORAGE_*` variables (e.g. Backblaze B2, which needs no card).

## 4. Render (the three services)

1. render.com → sign up with GitHub → **New → Blueprint** → pick the `ShelfWise` repository. Render reads `render.yaml`
   and lists three services: `shelfwise-core`, `shelfwise-notifications`, `shelfwise-web`.
2. Fill in the values it asks for (the same database and broker values go into both Java services):

| Asked for | Value |
|---|---|
| `DB_HOST` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | from Neon (step 1) |
| `RABBITMQ_URI` | the `amqps://` URL from CloudAMQP (step 2) |
| `STORAGE_ENDPOINT` / `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | from R2 (step 3) |
| `DEMO_PASSWORD` | the starting password for the imported demo accounts (shared with reviewers) |

   Everything else is set by `render.yaml`: `JWT_SECRET` is generated and copied to the notification service,
   the web service gets the two services' addresses, and the core service gets the web address for
   `CORS_ALLOWED_ORIGINS` (without it the browser's sign-in gets 403).
3. **Apply.** The first build takes about 10 minutes per Java service. The core service then imports the provided
   data on its first start (re-runs only add new records, D28).

If a name is taken, Render adds letters to the address; the references in `render.yaml` follow automatically.

## 5. Check it

1. Open `https://shelfwise-web.onrender.com` (the address Render shows for `shelfwise-web`) → sign in as a demo account
   (README → Demo accounts) with `DEMO_PASSWORD`.
2. `https://<core address>/actuator/health` shows `"status":"UP"` with `outbox: UP`.
3. Approve a PO as finance; the store manager's bell shows the decision. Search both services' logs (Render → service →
   Logs) for the `X-Request-Id` from the browser's response headers: the call, the publish and the handling all
   appear (DL-14).
4. Attach an invoice to a PO and open it again (R2).

## 6. Android app (Expo EAS)

1. `npm install -g eas-cli`, then in `mobile/`: `eas login` (your Expo account) and `eas init` (links the project; adds
   its id to `app.json`).
2. In `mobile/eas.json`, set `EXPO_PUBLIC_API_URL` to the web address (the bell uses the same address).
3. `eas build --platform android --profile preview` → builds an installable **APK** in Expo's cloud (about 15 minutes)
   and prints a link and QR code to download it on a phone.

## Rolling back

- **Code:** Render → service → Events → **Rollback** to an earlier deploy.
- **Database:** migrations only add (each file says how to undo it); Neon can restore the database to an earlier point in time.
- **Settings:** remote config changes are audited and can be set back in the web app's Settings screen (DL-06).
