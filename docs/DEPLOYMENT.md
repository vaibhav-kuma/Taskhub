# Deployment guide

Three supported paths: **Docker Compose** (self-hosted / VPS), **Fly.io**, and **Render**.
Railway works identically to Render (both build the same Dockerfile) — see
[Railway](#railway).

---

## Live deployment (this repository)

| Item | Value |
|---|---|
| Public API | **https://crud-app-metbxg.fly.dev/api/v1** |
| Browser client | **https://crud-app-metbxg.fly.dev/** (bundled in the API image under `classpath:/static`) |
| Swagger UI | https://crud-app-metbxg.fly.dev/swagger-ui.html |
| Health | https://crud-app-metbxg.fly.dev/actuator/health |
| Fly app / org | `crud-app-metbxg` / `personal` (`ticyb`) |
| Region | `iad` · 1 Machine, `shared-cpu-1x` 1 GB |
| Database | PostgreSQL 16 **in the same Machine**, data on volume `pgdata` mounted at `/data`, reachable only on `127.0.0.1:5432` |
| Image | `backend/Dockerfile.fly` (all-in-one), entrypoint `backend/docker/fly-entrypoint.sh` |
| SPA hosting | Static assets served by Spring Boot; only `/`, `/index.html`, `/app.js`, `/styles.css` are public — every `/api/**` path still requires a bearer token |
| Verified | `scripts/smoke-test.sh` fully green · checks 2/2 · stop/start cycle preserved data |

### Why all-in-one instead of `fly postgres`

The token available for this org can operate the existing app but **cannot create apps**
(`fly apps create` → `unauthorized`, org `EnablePaidHobby: false`). `fly postgres create` provisions a
new app, so it was not possible. The fallback keeps a real PostgreSQL 16 with the same schema,
migrations and credentials — just co-located:

- API and Postgres run under one supervisor (`fly-entrypoint.sh`); Postgres listens on loopback only.
- `pgdata` volume holds `/data/pgdata`; the API talks to `jdbc:postgresql://127.0.0.1:5432/taskhub`
  (set in `[env]`, **not** as a secret — a stale `TASKHUB_DB_URL` secret silently overrides `[env]`
  and cost one debugging cycle here).
- Trade-offs: single Machine ⇒ no horizontal scale, no failover; a restart restarts the DB too
  (≈10 s, verified). Volume snapshots are the backup mechanism until a managed cluster is available.
- Migrating later is a dump/restore: `pg_dump` from the Machine, then point `TASKHUB_DB_URL` at
  `fly postgres`/Managed Postgres and redeploy with `backend/Dockerfile` (see §2).

Reproduce with `scripts/fly-deploy-allinone.sh <existing-app>`.

---

## 0. What a deployment needs

| Requirement | Value / notes |
|---|---|
| Java runtime | Provided by the image (Temurin 21 JRE). No host JDK needed. |
| PostgreSQL | 14+ (16 recommended). TLS required if the DB is not on the same private network. |
| Env vars | `TASKHUB_JWT_SECRET` (**required**), `TASKHUB_DB_URL`, `TASKHUB_DB_USER`, `TASKHUB_DB_PASSWORD` |
| Port | The app listens on `$PORT` (default `8080`). |
| Health check | `GET /actuator/health/readiness` → 200 when ready |
| HTTPS | Terminated by the platform. `server.forward-headers-strategy=framework` honours `X-Forwarded-Proto`. |

Optional: `TASKHUB_CORS_ORIGINS` (browser origins), `TASKHUB_OPENAPI_ENABLED=false` (hide Swagger),
`TASKHUB_BOOTSTRAP_ADMIN_EMAIL` + `TASKHUB_BOOTSTRAP_ADMIN_PASSWORD` (first admin),
`TASKHUB_LOG_LEVEL`, `TASKHUB_DB_POOL_SIZE`.

Generate the JWT secret once and keep it stable — rotating it invalidates every existing access
token (users simply refresh/re-login):

```bash
openssl rand -base64 48
```

---

## 1. Docker Compose (5 minutes, any Linux host)

```bash
git clone <your-repo> taskhub && cd taskhub
cp .env.example .env
# edit .env: set TASKHUB_JWT_SECRET and POSTGRES_PASSWORD, and the bootstrap admin
docker compose up -d --build
docker compose ps
curl -fsS http://localhost:8080/actuator/health | jq
BASE_URL=http://localhost:8080 EMAIL=you@example.com PASSWORD='...' ./scripts/smoke-test.sh
```

What you get:

| URL | Service |
|---|---|
| `http://localhost:8080` | API (used by the JavaFX desktop client) |
| `http://localhost:8081` | Browser SPA (nginx proxies `/api` → API, so no CORS) |
| `localhost:5432` | PostgreSQL (published for convenience; remove the port mapping in production) |

For a public HTTPS deployment on a VPS, put Caddy or nginx in front:

```caddy
api.example.com {
    reverse_proxy 127.0.0.1:8080
}
app.example.com {
    reverse_proxy 127.0.0.1:8081
}
```

Then set `TASKHUB_CORS_ORIGINS=https://app.example.com` and redeploy the API.

---

## 2. Fly.io

Automated:

```bash
fly auth login
FLY_APP=taskhub-api TASKHUB_CORS_ORIGINS=https://app.example.com ./scripts/fly-deploy.sh
```

Manual equivalent:

```bash
fly launch --no-deploy --name taskhub-api --region iad
fly postgres create --name taskhub-db --region iad
fly postgres attach taskhub-db --app taskhub-api
# `attach` sets DATABASE_URL=postgres://…; Spring needs a JDBC URL, so set:
fly secrets set --app taskhub-api \
  TASKHUB_JWT_SECRET="$(openssl rand -base64 48)" \
  TASKHUB_DB_URL="jdbc:postgresql://<host>:5432/<db>?sslmode=require" \
  TASKHUB_DB_USER="<user>" TASKHUB_DB_PASSWORD="<password>" \
  TASKHUB_CORS_ORIGINS="https://app.example.com"
fly deploy --app taskhub-api
curl -fsS https://taskhub-api.fly.dev/actuator/health
```

`fly.toml` already defines the health checks, HTTPS redirect, 512 MB VM and auto-stop behaviour.
Scale horizontally when needed: `fly scale count 3` (the API is stateless).

---

## 3. Render (Blueprint)

1. Push the repository to GitHub.
2. Render → **New → Blueprint** → select the repo. `render.yaml` provisions PostgreSQL and the
   API container, sets `SPRING_PROFILES_ACTIVE=prod`, and generates `TASKHUB_JWT_SECRET`.
3. Set `TASKHUB_CORS_ORIGINS` when prompted.
4. Copy the service URL (`https://taskhub-api.onrender.com`) into the desktop client.

Free/starter instances sleep when idle; the first request afterwards takes ~30 s. Use a paid
instance for latency-sensitive use, or keep it awake with a scheduled health ping.

---

## 4. Railway

```bash
railway login
railway init
railway add --plugin postgresql
railway variables --set SPRING_PROFILES_ACTIVE=prod
railway variables --set TASKHUB_JWT_SECRET="$(openssl rand -base64 48)"
# point TASKHUB_DB_URL at the plugin's JDBC URL (Railway exposes PGHOST/PGPORT/... or DATABASE_URL)
railway up
```

`railway.json` sets the Dockerfile path and the readiness health check.

---

## 5. First administrator

Public registration grants `MEMBER` only. Create the first admin with environment variables
(checked on every start, idempotent, skipped once any ADMIN exists):

```bash
TASKHUB_BOOTSTRAP_ADMIN_EMAIL=you@example.com
TASKHUB_BOOTSTRAP_ADMIN_PASSWORD=<12+ chars, letter + digit>
TASKHUB_BOOTSTRAP_ADMIN_NAME=Your Name
```

Remove the two password variables after the first successful start. To promote an existing user
instead, sign in as an admin and call `PATCH /api/v1/users/{id}/role {"role":"ADMIN"}`.

Demo data (`admin@taskhub.local` etc.) exists **only** in the `dev` profile and never in `prod`.

---

## 6. Database lifecycle

- **Migrations** live in `backend/src/main/resources/db/migration` and run automatically at
  startup. Flyway takes a database lock, so rolling deploys cannot double-apply. Validation
  (`validate-on-migrate`) fails fast if a checksum changed.
- **Never** edit an applied migration; add `V2__…sql`.
- `spring.jpa.hibernate.ddl-auto=validate` in every profile except tests — Hibernate will not
  change the schema, it only verifies that the entities match it.

### Backups

Managed PostgreSQL: enable the provider's automated backups + PITR (Fly: `fly pg backups`,
Render: daily backups on paid plans).

Self-hosted: run the included script from cron:

```cron
15 2 * * * TASKHUB_DB_URL=... TASKHUB_DB_USER=... TASKHUB_DB_PASSWORD=... BACKUP_DIR=/var/backups/taskhub BACKUP_S3=s3://bucket/taskhub /opt/taskhub/scripts/backup.sh
```

Restore (destructive, prompts for confirmation):

```bash
TASKHUB_DB_URL=... TASKHUB_DB_USER=... TASKHUB_DB_PASSWORD=... ./scripts/restore.sh backups/taskhub-<stamp>.dump
```

Test a restore into a scratch database at least once per quarter. A backup you have never
restored is a hypothesis.

---

## 7. Monitoring

| Signal | Where |
|---|---|
| Liveness / readiness | `/actuator/health/liveness`, `/actuator/health/readiness` (wired into Fly/Render/Docker) |
| DB connectivity | Component `db` inside `/actuator/health` |
| JVM + HTTP metrics | `/actuator/metrics` (ADMIN bearer token) |
| Request correlation | `X-Request-Id` header + `requestId` field in logs and errors |
| Logs | `prod` profile emits ECS-structured JSON (`logging.structured.format.console=ecs`) — ship stdout to your log platform |

Alert suggestions: readiness failing > 2 min, `5xx` rate > 1 %, p95 latency > 500 ms,
DB connections > 80 % of pool, disk > 80 %.

---

## 8. Scaling

- **API**: stateless; add replicas. Session state lives in the refresh-token table, so no sticky
  sessions are needed.
- **Pool**: `TASKHUB_DB_POOL_SIZE` per instance; keep `instances × pool ≤ max_connections − 5`.
  Default 10 is right for 1–2 instances on a small database.
- **Rate limiting** is per instance. With multiple instances, add Redis-backed limiting or move
  the limit to the edge (documented in `docs/SECURITY.md`).
- **Search performance**: the search is a `LIKE '%term%'`, which is a sequential scan. Up to a few
  hundred thousand tasks this is fine (index-backed filters still apply). Beyond that, add:

  ```sql
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
  CREATE INDEX CONCURRENTLY idx_tasks_title_trgm ON tasks USING gin (lower(title) gin_trgm_ops);
  ```

  and switch the search predicate in `TaskSpecifications` to `similarity()`/trigram `LIKE`. Keep
  the migration separate from `V1`.

---

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| App exits at startup: `taskhub.jwt.secret must …` | Missing/short/non-Base64 secret | `fly secrets set TASKHUB_JWT_SECRET="$(openssl rand -base64 48)"` |
| `Schema validation: missing table` | Migrations did not run | Check `TASKHUB_DB_URL` / network / Flyway logs; never set `ddl-auto=update` |
| 401 on every call after deploy | JWT secret changed | Expected — users refresh/re-login |
| `CORS preflight` failures in the browser | Origin not allow-listed | Add it to `TASKHUB_CORS_ORIGINS` (comma-separated, scheme included, no wildcard with credentials) |
| Desktop cannot connect | Wrong URL / HTTP vs HTTPS / corporate proxy | Test `curl <url>/actuator/health`; the client shows the exact transport error |
| `429 RATE_LIMITED` in tests | Login burst limit | Wait for the window or set `TASKHUB_RATE_LIMIT_ENABLED=false` locally |
| Fly machine restarts under load | 512 MB too small for the JVM + pool | `fly scale memory 1024` |

---

## 10. About a "public live URL"

Deploying to a *specific* account requires that account's credentials, which this project cannot
contain. Everything needed to produce the URL is committed:

| Target | Command | Resulting URL |
|---|---|---|
| Fly.io | `./scripts/fly-deploy.sh` | `https://<app>.fly.dev` |
| Render | Blueprint import | `https://<service>.onrender.com` |
| Railway | `railway up` | `https://<service>.up.railway.app` |
| Docker Compose + Caddy | `docker compose up -d` | `https://api.<your-domain>` |

After deployment, verify with the committed smoke test — the same script that passed locally:

```bash
BASE_URL=https://<your-url> EMAIL=<admin email> PASSWORD=<password> ./scripts/smoke-test.sh
```
