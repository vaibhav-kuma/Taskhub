# Operations runbook

Day-2 tasks for whoever runs TaskHub. All commands assume the repository root.

---

## Daily / weekly checks

| Cadence | Check | Command / signal |
|---|---|---|
| Daily | Readiness green | `curl -fsS $API/actuator/health/readiness` |
| Daily | Error rate | platform logs / metrics; alert on 5xx > 1 % |
| Daily | Backup completed | `ls -lh $BACKUP_DIR` (or provider backup list) |
| Weekly | Disk / DB growth | `SELECT pg_size_pretty(pg_database_size(current_database()));` |
| Monthly | Restore drill into scratch DB | `scripts/restore.sh` |
| Quarterly | Dependency updates | `mvn versions:display-dependency-updates` |

## Deploy

```bash
# Fly
fly deploy --app taskhub-api
# Docker Compose
git pull && docker compose up -d --build
# Render / Railway: push to main (auto-deploy) or trigger the dashboard/hook
```

Migrations run at startup under a Flyway lock; the platform keeps traffic off the new instance
until `/actuator/health/readiness` is UP. Always follow with:

```bash
BASE_URL=$API EMAIL=... PASSWORD=... ./scripts/smoke-test.sh
```

## Rollback

1. **Application only** (schema unchanged — the usual case):
   ```bash
   fly releases list --app taskhub-api
   fly deploy --image <previous-image-ref> --app taskhub-api
   ```
2. **Broken migration**: Flyway refuses to start with a checksum mismatch. Do **not** edit an
   applied migration. Write a compensating `V{n+1}__fix.sql`, or (last resort, data loss risk)
   restore the pre-deploy backup and deploy the previous image.

## Rotate the JWT signing secret

Effects: all access tokens become invalid immediately; users transparently re-login.

```bash
fly secrets set TASKHUB_JWT_SECRET="$(openssl rand -base64 48)" --app taskhub-api
```

Refresh tokens are unaffected (independent secret), so no user is logged out of a session they can
still refresh — but their next refresh will issue tokens signed with the new secret.

## Rotate database credentials

1. Create a new role/password with identical grants.
2. Update `TASKHUB_DB_USER` / `TASKHUB_DB_PASSWORD` secrets.
3. Deploy; confirm readiness.
4. Drop/disable the old role after the old instances are gone.

## Incident playbooks

### API returns 5xx / restarts

1. `fly logs --app taskhub-api` (or `docker compose logs api`) — look for `OutOfMemoryError`,
   `Connection is not available`, Flyway errors.
2. Check readiness: if `db` is DOWN → database incident.
3. OOM under load → raise memory (`fly scale memory 1024`) and reduce pool size.
4. Connection exhaustion → verify `instances × pool ≤ max_connections − 5`.

### Suspected credential stuffing

Signals: many `401 INVALID_CREDENTIALS`, `429 RATE_LIMITED`, spikes in `/auth/*`.
Actions: confirm the limiter is enabled; add an edge rate-limit rule; notify affected users;
consider forcing a global refresh-token revocation:

```sql
UPDATE refresh_tokens SET revoked = true WHERE revoked = false;
```

### Suspected token theft (`REFRESH_TOKEN_REUSED` in logs)

The family is already revoked automatically. Correlate `user=` and `family=` in logs, and advise
the user to change their password.

### Database restore

```bash
docker compose stop api           # stop writers
TASKHUB_DB_URL=... TASKHUB_DB_USER=... TASKHUB_DB_PASSWORD=... \
  ./scripts/restore.sh backups/taskhub-<stamp>.dump
docker compose start api
BASE_URL=$API ... ./scripts/smoke-test.sh
```

### Disk full on the database host

`VACUUM FULL` is not a routine operation. First find the growth: `pg_total_relation_size` per
table; the usual culprit is `refresh_tokens` (purged nightly at 03:30) or an unbounded audit table
you added. Prune, then revisit retention.

---

## Capacity reference

| Deployment | Comfortable load |
|---|---|
| 512 MB / 1 vCPU, pool 10 | ~50 req/s of paged reads, small team |
| 1 GB / 2 vCPU, pool 15 | ~150 req/s |
| 3 instances behind LB | ~450 req/s; DB becomes the limit |

Paging is the dominant query. Watch p95 on `GET /api/v1/tasks?search=…`; enable the trigram index
before it degrades.

## Useful SQL

```sql
-- Largest tables
SELECT relname, pg_size_pretty(pg_total_relation_size(c.oid)) AS size
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
ORDER BY pg_total_relation_size(c.oid) DESC LIMIT 10;

-- Stale refresh tokens (should be pruned)
SELECT count(*) FROM refresh_tokens WHERE expires_at < now();

-- Project membership overview
SELECT p.code, u.email, m.member_role
FROM project_members m
JOIN projects p ON p.id = m.project_id
JOIN users u ON u.id = m.user_id
ORDER BY p.code, u.email;
```

## Ownership

| Area | Owner |
|---|---|
| API & schema | Backend team |
| Desktop client | Desktop team |
| Deployment & secrets | Platform/DevOps |
| Security reviews | Security lead |
