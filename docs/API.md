# TaskHub API — reference

Base URL: `{host}/api/v1` · Content type: `application/json` · Timestamps: ISO-8601 UTC

Interactive docs: `/swagger-ui.html` · Machine-readable: `/v3/api-docs`

---

## Authentication

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/auth/register` | public | Self-service sign-up: creates a `MEMBER`, returns tokens immediately. Rate limited. Returns `403 REGISTRATION_DISABLED` when `TASKHUB_REGISTRATION_ENABLED=false`. |
| POST | `/auth/login` | public | Returns `accessToken` (15 min) + `refreshToken` (7 days). Rate limited. |
| POST | `/auth/refresh` | refresh token | Rotates the refresh token. Reuse revokes the whole family. |
| POST | `/auth/logout` | bearer | Revokes the refresh-token family of the session. |
| GET | `/auth/me` | bearer | Current profile. |

Access tokens are sent as `Authorization: Bearer <token>`. They are JWTs (HS256) carrying
`sub`, `email`, `role`, `jti`, `iss`, `aud`, `iat`, `exp`.

```bash
TOKEN=$(curl -sS -X POST "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@example.com","password":"..."}' | jq -r .accessToken)
```

### Refresh-token semantics

1. `POST /auth/refresh` with the current refresh token returns a **new** access token **and a new
   refresh token**; the old one is revoked.
2. Presenting an already-rotated token returns `401 REFRESH_TOKEN_REUSED` **and revokes every token
   issued in that family** — treat it as a stolen-session signal and force a re-login.
3. `401 TOKEN_EXPIRED` on an access token means: refresh, then retry once.

---

## Projects

| Method | Path | Who | Description |
|---|---|---|---|
| GET | `/projects` | any user | Only owned + member projects (ADMIN sees all). |
| POST | `/projects` | any user | Caller becomes owner and `OWNER` member. Code is unique, upper-case. |
| GET | `/projects/{id}` | member | |
| PATCH | `/projects/{id}` | owner/admin | Partial update; send `version` for optimistic concurrency. |
| DELETE | `/projects/{id}` | owner/admin | Cascades to tasks and memberships. |
| GET | `/projects/{id}/members` | member | |
| POST | `/projects/{id}/members` | owner/admin | `{"userId":"…","role":"CONTRIBUTOR"}` |
| PATCH | `/projects/{id}/members/{userId}` | owner/admin | Change role (`OWNER` cannot be demoted). |
| DELETE | `/projects/{id}/members/{userId}` | owner/admin | Owner cannot be removed. |

Roles: `ADMIN` (global) · `MANAGER` (global, can create projects) · `MEMBER` (global).
Per-project: `OWNER`, `CONTRIBUTOR` (can mutate tasks), `VIEWER` (read-only).

---

## Tasks

| Method | Path | Who | Description |
|---|---|---|---|
| GET | `/tasks` | any user | Paged, searchable, filterable, sortable. |
| POST | `/tasks` | contributor | Create in a project you contribute to. |
| GET | `/tasks/{id}` | member | |
| PATCH | `/tasks/{id}` | contributor | Partial update; null = unchanged. |
| DELETE | `/tasks/{id}` | contributor | |

### Query parameters

| Parameter | Type | Default | Rules |
|---|---|---|---|
| `page` | int | `0` | ≥ 0 |
| `size` | int | `20` | 1–100 (values above 100 are clamped) |
| `sort` | string | `createdAt` | allow-list: `title`, `status`, `priority`, `dueDate`, `createdAt`, `updatedAt` |
| `direction` | `asc`/`desc` | `desc` | anything else is treated as `desc` |
| `search` | string | – | case-insensitive contains over title + description; < 2 chars ignored; `%`/`_` treated literally |
| `status` | enum | – | `TODO`, `IN_PROGRESS`, `BLOCKED`, `DONE` |
| `priority` | enum | – | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `projectId` | uuid | – | |
| `assigneeId` | uuid | – | |
| `overdue` | bool | – | due date in the past and not `DONE` |

### Create

```bash
curl -sS -X POST "$API/api/v1/tasks" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
        "projectId": "6d1c…",
        "title": "Ship the release",
        "description": "Cut and publish 1.0",
        "status": "TODO",
        "priority": "HIGH",
        "dueDate": "2026-03-14",
        "assigneeId": "9f0a…",
        "estimatedHours": 6.5
      }'
```

The assignee must already be a member of the project (`ASSIGNEE_NOT_PROJECT_MEMBER` otherwise).

### Partial update

```bash
curl -sS -X PATCH "$API/api/v1/tasks/$TASK_ID" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"DONE","version":3}'
```

- Omitted/null fields are left untouched.
- `clearDueDate: true` / `clearAssignee: true` explicitly remove those values.
- `version` is optional but recommended: a stale value returns `409 STALE_VERSION`.

---

## Users

| Method | Path | Who | Description |
|---|---|---|---|
| GET | `/users?search=&page=&size=&enabled=` | any user | Directory for assignee pickers. |
| GET | `/users/{id}` | ADMIN | |
| PATCH | `/users/{id}/role` | ADMIN | `{"role":"ADMIN"}`; the last admin cannot be demoted. |
| PATCH | `/users/{id}/status` | ADMIN | `{"enabled":false}`; self-disable and last-admin disable are blocked. |

---

## Pagination envelope

```json
{
  "content": [],
  "page": 0, "size": 20,
  "totalElements": 137, "totalPages": 7,
  "first": true, "last": false,
  "sort": "createdAt", "direction": "desc"
}
```

## Error envelope

```json
{
  "timestamp": "2026-02-11T09:12:33Z",
  "status": 400,
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "path": "/api/v1/tasks",
  "requestId": "8f2c…",
  "fieldErrors": [{"field": "title", "message": "must not be blank"}]
}
```

`requestId` is also returned in the `X-Request-Id` header and appears in server logs — quote it
when reporting a problem.

### Stable error codes

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean-validation failure; see `fieldErrors` |
| 400 | `INVALID_SORT` / `INVALID_PAGE` / `INVALID_SIZE` | Bad pagination parameter |
| 400 | `ASSIGNEE_NOT_PROJECT_MEMBER` | Assignee has no access to the project |
| 401 | `UNAUTHENTICATED` | Missing token |
| 401 | `INVALID_TOKEN` / `TOKEN_EXPIRED` | Bad or expired access token |
| 401 | `INVALID_CREDENTIALS` | Login failed (same message for unknown email) |
| 401 | `REFRESH_TOKEN_REUSED` | Rotation replay detected; all sessions in the family revoked |
| 403 | `FORBIDDEN` | Authenticated but not allowed (row-level access or role) |
| 404 | `NOT_FOUND` | Unknown id |
| 403 | `REGISTRATION_DISABLED` | Public sign-up is switched off on this server |
| 409 | `EMAIL_ALREADY_REGISTERED` / `PROJECT_CODE_TAKEN` / `ALREADY_A_MEMBER` | Uniqueness |
| 409 | `STALE_VERSION` | Someone else saved first — reload and retry |
| 409 | `OWNER_CANNOT_BE_REMOVED` / `OWNER_ROLE_LOCKED` / `LAST_ADMIN_PROTECTED` | Invariants |
| 429 | `RATE_LIMITED` | Credential endpoint burst limit; honour `Retry-After` |
| 500 | `INTERNAL_ERROR` | Unexpected; message is generic by design |

## Operational endpoints

| Path | Access | Purpose |
|---|---|---|
| `/actuator/health` | public | Aggregate health (includes DB) |
| `/actuator/health/liveness` | public | Process is alive |
| `/actuator/health/readiness` | public | Ready to serve traffic (DB reachable, migrations done) |
| `/actuator/metrics` | ADMIN | JVM/HTTP metrics |
| `/actuator/info` | public | Build info |
