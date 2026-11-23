# Security design & threat model

Scope: the REST API, the JavaFX desktop client, the optional browser client, and the data at rest.

---

## 1. Assets

| Asset | Impact if compromised |
|---|---|
| Password hashes | Credential stuffing reuse across sites |
| Refresh tokens | Session hijacking (7-day window) |
| Access tokens | 15-minute API access |
| JWT signing secret | Forge tokens for any user, including ADMIN |
| Database credentials | Full data disclosure/modification |
| Project/task data | Business confidentiality breach |

## 2. Trust boundaries

```
[desktop client] --HTTPS--> [platform TLS edge] --> [API] --TLS--> [PostgreSQL]
[browser client] --HTTPS-->                        ^
                                                   |
                                        env vars / secrets manager
```

- The clients are **untrusted**: every rule is enforced server-side. Client-side validation is
  UX only.
- The API is the **only** component with database credentials.
- The database is assumed reachable only from the API's network.

---

## 3. Controls

### Authentication

| Control | Implementation |
|---|---|
| Password hashing | BCrypt cost 12, `{bcrypt}` prefix (`DelegatingPasswordEncoder`) → future algorithm migration without invalidating hashes |
| Password policy | ≥ 12 chars, at least one letter and one digit (server-side Bean Validation) |
| Credential enumeration | Identical `INVALID_CREDENTIALS` message for unknown email and wrong password |
| Timing attacks | A dummy BCrypt verification runs for unknown accounts |
| Brute force | Sliding-window limiter on `/auth/login`, `/auth/register`, `/auth/refresh` (10 per 5 min per IP+endpoint) → `429` + `Retry-After` |
| Access token | JWT HS256, 15 min, signed with a Base64 secret of ≥ 32 bytes checked at startup |
| Refresh token | 32 random bytes, stored **only** as SHA-256, 7 days, single-use, rotated on every refresh |
| Token theft | Replaying a rotated refresh token revokes the entire token family and returns `401 REFRESH_TOKEN_REUSED`; the revocation commits in its own transaction so the 401 cannot roll it back |
| Session termination | Logout revokes the refresh family; access tokens die within 15 minutes |
| Disabled accounts | Rejected at login **and** at refresh |

### Authorization

- URL rules: only `/auth/login`, `/auth/register`, `/auth/refresh`, health, and OpenAPI are public.
  `/actuator/**` (except health) requires `ROLE_ADMIN`.
- Method rules: `@PreAuthorize("hasRole('ADMIN')")` on user administration.
- **Row-level checks live in `ProjectAccess`** and are called by every service entry point:
  - read: ADMIN, owner, or any member (including VIEWER)
  - contribute (task mutations): ADMIN, owner, or CONTRIBUTOR
  - manage (project fields/members/delete): ADMIN or owner
- A non-member receives `403` for a known project and `404` for a non-existent one; list endpoints
  simply never return invisible rows.
- Invariants enforced server-side: the owner cannot be removed or demoted, the last ADMIN cannot be
  demoted or disabled, and you cannot change your own role or disable yourself.

### Input & output

- Bean Validation on every request DTO; `fieldErrors` returned per field.
- All SQL is parameterised (JPA Criteria/Specifications); no string concatenation.
- `sort` is allow-listed per endpoint → no `ORDER BY` injection, no unindexed sort.
- `page`/`size` are bounded (`size` clamped to 100).
- Search input is trimmed, length-capped, and `%`/`_`/`\` escaped before `LIKE`.
- Responses are DTOs; entities never leak. Password hashes cannot be serialized because
  `UserResponse` has no such field and `AppUserPrincipal` omits it.
- Errors never include stack traces or database messages; 5xx returns a generic message plus a
  request id.
- Browser client renders server data with `textContent` only (no `innerHTML`), and nginx enforces
  `script-src 'self'`.

### Transport

- HTTPS is terminated by the platform; `X-Forwarded-Proto` is honoured
  (`server.forward-headers-strategy=framework`) and Fly/Render force HTTPS.
- HSTS is emitted by Spring Security on secure requests.
- The Docker image runs as a non-root user with a read-only application directory.
- DB connections use `sslmode=require` in the managed examples.

### Headers (API and web)

`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`,
`Permissions-Policy`, and for the SPA a strict `Content-Security-Policy` with
`frame-ancestors 'none'`.

### Secrets

- No secret in source control. `.gitignore` excludes `.env*` (except the example) and keystores.
- The dev JWT secret in `application-dev.yml` is a deliberately public throwaway; `prod` has no
  default and fails fast.
- Production secrets come from the platform's secret store (`fly secrets`, Render env vars,
  `.env` in Compose, or a vault).
- Bootstrap admin credentials are read from the environment, used once, never logged.

### Auditability

Every request gets an id (`X-Request-Id`, echoed in logs and error bodies). Auth events
(registration, reuse detection, purges) are logged with structured fields and without credentials.

---

## 4. Accepted risks / deliberate limits

| Risk | Why accepted | Mitigation path |
|---|---|---|
| Access token is valid until expiry after logout (≤ 15 min) | Stateless verification avoids a per-request DB/Redis hit | Shorten TTL, or add a jti deny-list in Redis |
| Rate limiting is per instance and keys on IP (+ spoofable `X-Forwarded-For`) | Single-instance deployments are exact; edge limits exist on Fly/Render | Redis-backed limiter or edge WAF rules |
| `/api/v1/users` exposes the user directory (name + email) to any authenticated user | Required for assignee pickers | Restrict to shared-project users; add a dedicated search endpoint |
| Contains-search (`LIKE '%x%'`) is a sequential scan | Correct product behaviour at current scale | `pg_trgm` GIN index (see DEPLOYMENT §8) |
| No email verification or password reset flow | Out of scope for this deliverable | Add a signed one-time token flow |
| No MFA | Out of scope | TOTP with recovery codes |
| SPA keeps tokens in memory (lost on reload) | Removes the persistent-XSS token-theft path | Acceptable UX trade-off for an internal tool |

## 5. Dependency & build hygiene

- Versions pinned in the POMs; Dependabot/Renovate recommended.
- CI runs the full test suite on every push; failures block merges.
- `mvn dependency:tree` review before adding libraries; the runtime surface is deliberately small
  (Spring Boot, jjwt, Flyway, PostgreSQL driver, springdoc; client: JavaFX + Jackson).

## 6. Reporting a vulnerability

Email the maintainers with the request id (if available), a reproduction, and impact. Do not open
a public issue. Target response: 3 business days.
