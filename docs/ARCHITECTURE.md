# TaskHub — Architecture & Design Decisions

> Status: **finalized before implementation**. Every decision below is reflected in code,
> configuration and tests. Deviations found during implementation are recorded in
> [ADR log](#adr-log) at the bottom.

---

## 1. Problem statement

Deliver a **production-ready CRUD application** for project/task tracking with:

- a **JavaFX desktop client** (Windows / Linux / macOS),
- a **Spring Boot REST backend** over HTTPS,
- **PostgreSQL** persistence with migrations,
- JWT auth, RBAC + row-level authorization, validation, pagination, search, filtering, sorting,
- containerization, CI/CD, observability, backups, documentation.

A JavaFX application is a **native desktop process**; it cannot run in a browser. The
architecture therefore treats the backend as the single source of truth and exposes the
same REST contract to three independent consumers:

```
┌────────────────────┐   ┌──────────────────────┐
│  JavaFX Desktop    │   │  Web SPA (optional)  │
│  Win / Linux / macOS│  │  any modern browser  │
└─────────┬──────────┘   └──────────┬───────────┘
          │  HTTPS + JSON + JWT     │
          └───────────┬─────────────┘
                      ▼
        ┌──────────────────────────────┐
        │  Spring Boot 3.5 REST API    │  stateless, horizontally scalable
        │  Security · Validation · JPA │
        └──────────────┬───────────────┘
                       │ JDBC (pooled, TLS in prod)
                       ▼
                ┌─────────────┐
                │ PostgreSQL  │  managed instance, automated backups
                └─────────────┘
```

The desktop client holds **no database driver and no credentials** — only a base URL.
That is what makes the same binary work on any OS against a remote deployment.

---

## 2. Technology matrix (pinned)

| Layer | Choice | Version | Rationale |
|---|---|---|---|
| Language | Java | **21 LTS** | Virtual threads, records, pattern matching, long support window |
| Backend | Spring Boot | **3.5.16** | Latest 3.5.x; mature OSS support; Boot 4 migration path documented |
| Web | Spring MVC (servlet) | via Boot | Server-side validation + OpenAPI integration is more mature than WebFlux here |
| Security | Spring Security 6.5 | via Boot | Method security, stateless filter chain |
| JWT | `jjwt` | **0.12.7** | Actively maintained, explicit key/parser API |
| Persistence | Spring Data JPA / Hibernate 6.6 | via Boot | Auditable entities, optimistic locking |
| Migrations | Flyway | via Boot (11.x) | Versioned, reproducible schema; same SQL runs on H2-PG-mode for tests |
| DB | PostgreSQL | **16/17** | JSON-free relational model, strong constraints, `timestamptz` |
| API docs | springdoc-openapi | **2.8.17** | OpenAPI 3 + Swagger UI |
| Desktop UI | JavaFX | **21.0.13** | LTS line matched to JDK 21 |
| HTTP client | `java.net.http.HttpClient` | JDK built-in | HTTP/2, TLS, no extra dependency |
| JSON (client) | Jackson | 2.19.x | Same mapper family as backend |
| Tests | JUnit 5, Mockito, AssertJ, MockMvc | via Boot | Unit + slice + full integration |
| Build | Maven (multi-module) | 3.9.9 | Reproducible, one command for all modules |
| Runtime image | Eclipse Temurin JRE 21 (Alpine) | — | Small, non-root, distroless-adjacent |
| CI/CD | GitHub Actions | — | `ci.yml` (verify) + `deploy.yml` (Fly.io/Render) |

**Why not Spring Boot 4.x / JavaFX 25?** Boot 4 (GA) targets Spring Framework 7 and
Jakarta EE 11 and moves several starter coordinates. It is a sound 2026 target, but for a
system that must be handed to an ops team today, the 3.5.x line has the widest managed-platform
support (Render/Railway/Fly buildpacks, Flyway, springdoc 2.x) and the most predictable
upgrade story. The upgrade is isolated: no `javax.*` imports, no deprecated security DSL,
so moving to Boot 4 is a parent-version bump plus springdoc 3.x. Recorded in ADR-001.

---

## 3. Module structure

```
taskhub/
├── backend/                        Spring Boot service
│   ├── src/main/java/com/taskhub/
│   │   ├── TaskHubApplication.java
│   │   ├── config/                 Security, OpenAPI, CORS, Jackson, rate limit, audit
│   │   ├── common/                 ApiError, PageResponse, exceptions, GlobalExceptionHandler
│   │   ├── security/               JwtService, filters, principal, TokenStore
│   │   ├── auth/                   AuthController, AuthService, refresh tokens
│   │   ├── user/                   User entity, repo, service, admin controller
│   │   ├── project/                Project + membership, service, controller
│   │   └── task/                   Task entity, service, controller, specifications
│   ├── src/main/resources/
│   │   ├── application.yml / -dev / -prod / -test
│   │   ├── db/migration/           V1__schema.sql, V2__seed.sql
│   │   └── logback-spring.xml
│   └── src/test/java/...           unit + integration tests
├── desktop/                        JavaFX client (thin, no JPA)
│   └── src/main/java/com/taskhub/desktop/
│       ├── TaskHubClient.java      entry point
│       ├── api/                    ApiClient, ApiException, DTO records, services
│       ├── auth/                   TokenStore, SessionManager (refresh + retry)
│       ├── ui/                     controllers, cells, dialogs, toasts, theme
│       └── util/                   async helpers, formatters
├── web/                            optional browser SPA (same REST API)
├── docker-compose.yml              postgres + api + web
├── .github/workflows/              ci.yml, deploy.yml
├── scripts/                        backup/restore/smoke-test
└── docs/                           ARCHITECTURE, API, DEPLOYMENT, RUNBOOK, SECURITY
```

**Layering rule (enforced by review):** Controller → Service → Repository. Controllers
never touch repositories; entities never leave the service layer (DTO mapping at the
boundary). The desktop client mirrors the DTOs as Java `record`s and has zero knowledge of
JPA.

---

## 4. Domain model

```
users ──1:N──> refresh_tokens
  │
  ├──1:N──> projects.owner_id
  │
  └──N:M──> project_members <── projects
                                   │
                                   └──1:N──> tasks
                                                └──assignee_id──> users
```

| Table | Key columns | Notes |
|---|---|---|
| `users` | `id uuid pk`, `email` (unique, lowercased), `password_hash`, `display_name`, `role`, `enabled`, `version`, `created_at`, `updated_at` | `role ∈ {ADMIN, MANAGER, MEMBER}` |
| `refresh_tokens` | `id`, `user_id fk cascade`, `token_hash` unique, `expires_at`, `revoked`, `created_at` | Only SHA-256 of the token is stored |
| `projects` | `id`, `code` unique, `name`, `description`, `status`, `owner_id fk`, `version` | `status ∈ {PLANNED, ACTIVE, ON_HOLD, COMPLETED, ARCHIVED}` |
| `project_members` | `(project_id, user_id)` pk, `member_role`, `added_at` | Explicit membership for row-level authz |
| `tasks` | `id`, `project_id fk`, `title`, `description`, `status`, `priority`, `due_date`, `assignee_id fk null`, `estimated_hours numeric(6,2)`, `version` | `status ∈ {TODO, IN_PROGRESS, BLOCKED, DONE}`, `priority ∈ {LOW, MEDIUM, HIGH, CRITICAL}` |

Design points:

- **UUIDv4 primary keys generated in Java** (`@UuidGenerator`), not by a DB function — the
  same migration then runs unchanged on PostgreSQL and on H2 in `MODE=PostgreSQL` for tests,
  and IDs are available before flush (no round-trip).
- **`version` column + `@Version`** → optimistic locking; concurrent edits return `409`
  instead of silently overwriting.
- **Enums stored as `VARCHAR` + `CHECK`**, not native PG enums: adding a value is a plain
  migration, and H2 compatibility is preserved.
- **`TIMESTAMP WITH TIME ZONE`** everywhere, application writes UTC.
- Foreign keys on delete: `project_members`/`tasks` → `CASCADE` from project;
  `assignee_id` → `SET NULL`; `refresh_tokens` → `CASCADE` from user.
- Indexes: `tasks(project_id, status)`, `tasks(assignee_id)`, `tasks(due_date)`,
  `projects(owner_id)`, trigram-free `lower(title)` index for search, `users(lower(email))`.
- **Soft delete is deliberately not used.** Hard delete keeps the data model honest, and
  `ON DELETE CASCADE` is explicit. (If an ops team needs legal retention, add `deleted_at`
  + `@SQLRestriction` — noted in the runbook.)

---

## 5. API contract

Base path `/api/v1`. JSON only. All timestamps ISO-8601 UTC.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/register` | public (rate-limited) | Self-service signup → `MEMBER` |
| POST | `/auth/login` | public (rate-limited) | Access + refresh token |
| POST | `/auth/refresh` | refresh token | Rotate refresh, issue new access |
| POST | `/auth/logout` | bearer | Revoke refresh token(s) for the session |
| GET | `/auth/me` | bearer | Current principal |
| GET | `/projects` | bearer | Paged list, `search`, `status`, `sort`, `direction` |
| POST | `/projects` | bearer | Create (creator becomes owner + member) |
| GET/PATCH/DELETE | `/projects/{id}` | owner/admin/member* | Read / partial update / delete |
| GET/POST/DELETE | `/projects/{id}/members[/{userId}]` | owner/admin | Membership management |
| GET | `/tasks` | bearer | Paged, `search`, `status`, `priority`, `projectId`, `assigneeId`, `overdue`, `sort`, `direction` |
| POST | `/tasks` | member of project | Create |
| GET/PATCH/DELETE | `/tasks/{id}` | member of project | Read / partial update / delete |
| GET | `/users` | bearer | Assignee picker (`search`, limited fields) |
| GET | `/users/{id}` | ADMIN | Admin read |
| PATCH | `/users/{id}/role` | ADMIN | Role change |

\* read for members, mutate for owner/admin.

**Pagination envelope** (stable, client-friendly — Spring's raw `Page` JSON is *not* used
because its shape changes between versions):

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 137, "totalPages": 7,
  "first": true, "last": false, "sort": "createdAt", "direction": "desc" }
```

**Error envelope** (RFC-7807-inspired, stable `code` for client logic):

```json
{ "timestamp": "2026-02-11T09:12:33Z", "status": 400, "code": "VALIDATION_FAILED",
  "message": "Request validation failed", "path": "/api/v1/tasks",
  "requestId": "8f2c…", "fieldErrors": [{"field":"title","message":"must not be blank"}] }
```

Rules:
- `size` clamped to `1..100`, default `20`; `page ≥ 0`.
- `sort` accepted only from a **per-endpoint allow-list** (prevents `ORDER BY` injection and
  full-table sorts on unindexed columns).
- Search term is trimmed, `< 2` chars is ignored, max 120 chars, `LIKE` escaped.
- `PATCH` performs partial update: only non-null fields applied (`JsonNullable`-style is
  overkill; null = "leave unchanged", clearing a value is explicit via empty string / dedicated
  semantics documented per field).

---

## 6. Security design

1. **Passwords**: BCrypt, strength 12, `DelegatingPasswordEncoder` so the hash prefix allows
   future algorithm migration. Login performs a **dummy hash comparison** for unknown emails to
   equalize response time.
2. **Access token**: JWT HS256, 15 min TTL, claims `sub` (user id), `email`, `role`, `jti`,
   `iss`, `aud`, `iat`, `exp`. Secret from `TASKHUB_JWT_SECRET` (Base64, ≥ 32 bytes). Startup
   fails fast if the secret is missing/short in `prod`.
3. **Refresh token**: 256-bit random, **stored only as SHA-256**, 7-day TTL, **rotated on every
   use**; reuse of a rotated token revokes the whole family (theft detection) and returns `401`.
4. **Filter chain**: stateless, `csrf` disabled *because no cookies are used* (documented
   threat-model consequence: bearer token is held in client memory, not `localStorage`),
   `frameOptions deny`, HSTS in prod, CORS allow-list from `TASKHUB_CORS_ORIGINS`.
5. **Authorization**: `@EnableMethodSecurity`; URL rules for `/auth/**`, `/actuator/health`,
   `/v3/api-docs/**`; everything else authenticated. **Row-level checks live in the service
   layer** (`ProjectAccess` guard) and are covered by tests — a controller-level check alone
   would be bypassable by any new endpoint.
6. **Rate limiting**: in-process sliding-window on `/auth/login` + `/auth/register`
   (10 req / 5 min / IP+email) returning `429` with `Retry-After`. In-process is correct for a
   single instance; the runbook documents Redis for multi-instance.
7. **Input safety**: Bean Validation at the DTO boundary, JPA bound parameters (no string SQL),
   search wildcards escaped, `sort` allow-listed, response DTOs never expose hashes.
8. **Transport**: HTTPS terminated by the platform (Fly/Render) — `server.forward-headers-strategy=framework`
   so `X-Forwarded-Proto` is honored; HTTP requests are redirected in prod config.
9. **Secrets**: never in the repo. `.env.example` documents every variable; `.gitignore`
   blocks `.env`; `docker-compose` reads from environment.

---

## 7. Frontend design (JavaFX)

- **Layered**: `ApiClient` (transport, retry, token) → `*Service` (typed calls) → `SessionManager`
  (auth state, refresh) → controllers (view state only). Controllers contain no HTTP code.
- **Threading**: every call runs on a daemon `ExecutorService` wrapped in `javafx.concurrent.Task`;
  results marshalled back on the FX thread. The UI never blocks, and there is a hard 20 s
  request timeout with a toast on failure.
- **Auto-refresh**: `ApiClient` retries a `401` exactly once by refreshing the token, with a
  single-flight lock so 5 concurrent requests trigger 1 refresh.
- **UX**: login → workspace; debounced search (300 ms), server-side sort by clicking column
  headers, status/priority filter chips, page size selector, empty/loading/error states,
  keyboard shortcuts (`Ctrl+N`, `Ctrl+F`, `F5`, `Esc`), inline validation before submit,
  optimistic-free (server-confirmed) updates so the table always matches the DB.
- **Styling**: one CSS file with looked-up color variables; `.theme-dark` on the root toggles the
  palette. Responsive behavior via `GridPane`/`BorderPane` growth + constrained table columns;
  a `minWidth` guard collapses the sidebar into a filter bar under 900 px.
- **No DB access, no credentials** except the access/refresh tokens; the base URL is read from
  `TASKHUB_API_URL`, a `--api=` argument, or `desktop.properties`.

---

## 8. Testing strategy

| Level | What | Tooling |
|---|---|---|
| Unit | JWT issue/parse/expiry/tamper, password encoding, `ApiClient` retry + auth, pagination param building | JUnit 5 + Mockito + stub HTTP server |
| Service | Authorization matrix (owner/member/outsider/admin), validation, optimistic-lock conflict, search escaping | Mockito |
| Integration (backend) | Full HTTP: register→login→refresh→reuse-detection, CRUD, filters, paging, 401/403/409/429 | `@SpringBootTest` + MockMvc + H2 (PostgreSQL mode) + Flyway |
| Smoke (deployed) | `scripts/smoke-test.sh` hits the live URL: health, login, create→read→update→delete | curl + jq |

Integration tests run the **real Flyway migrations** against H2 with
`MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`, so schema bugs surface in CI, not in production.
CI runs `mvn verify`; JaCoCo reports are produced and uploaded as build artefacts (a coverage
floor is deliberately not enforced yet — the suite is behaviour-first, and a threshold that low
enough to pass today would not protect anything).

---

## 9. Operations

- **Health**: `/actuator/health` (public, includes DB probe), `/actuator/health/liveness`,
  `/actuator/health/readiness` for orchestrators; `/actuator/metrics` restricted to ADMIN.
- **Logs**: console + optional JSON via Boot's native structured logging
  (`logging.structured.format.console=ecs`) in `prod`; every request carries `X-Request-Id`
  echoed in logs and error bodies.
- **Migrations**: Flyway runs at startup (`flyway.validate-on-migrate=true`). Render/Fly release
  command runs it explicitly before switching traffic.
- **Backups**: `scripts/backup.sh` → `pg_dump -Fc` to object storage, retention 14 daily /
  8 weekly; managed providers additionally offer PITR (documented).
- **Scaling**: stateless API → add instances behind the platform LB; DB connection pool sized
  `max_pool = 2 × vCPU + 1` with `maxLifetime` below the provider's idle timeout.
- **Deploy targets**: Fly.io (`fly.toml`), Render (`render.yaml`), Railway (`railway.json`),
  plus a generic `docker-compose.prod.yml` for a VPS.

---

## 10. Explicit non-goals / known limits

- No browser-hosted JavaFX. If browser access is required, the included SPA consumes the same
  API (and is the recommended path for external users).
- No file/attachment storage, no email delivery, no multi-tenant billing. The schema leaves room
  (`projects.owner_id`) but does not pretend to implement them.
- Single-region deployment; HA PostgreSQL is a provider concern (documented knobs).

---

## ADR log

| # | Decision | Alternative considered | Why |
|---|---|---|---|
| 001 | Spring Boot 3.5.16 + JDK 21 | Boot 4.1 | Ecosystem/ops support maturity; clean upgrade path |
| 002 | Stateless JWT + rotating refresh tokens in DB | Server sessions | Desktop client across NAT/proxies; horizontal scale |
| 003 | Service-layer row authorization | Controller annotations only | Every future endpoint inherits the guard |
| 004 | H2 `MODE=PostgreSQL` for integration tests | Testcontainers | CI without Docker-in-Docker; same Flyway SQL, no dialect drift |
| 005 | Manual DTO mappers | MapStruct/Lombok | Fewer processors, explicit code, faster builds |
| 006 | UUID PKs generated in Java | `gen_random_uuid()` | Portable migrations across PG/H2 |
| 007 | Spring `Page` wrapped in `PageResponse` | Return `Page` directly | Stable JSON contract across Boot upgrades |
