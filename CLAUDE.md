# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

부스락 (BoothLock) — a QR table-order system for temporary festival/booth vendors who have no
business registration and no POS. Customers scan a table QR, order from their phone, and pay by
bank transfer (order number embedded in the depositor name) or cash; staff manually confirm payment
from a dashboard. No card/PG payment, no multi-booth central management — those are explicitly out
of scope for the pilot (first pilot target: 창원대학교 festival).

The repo is `backend/` (Spring Boot API) + `frontend/` (React SPA), deployed together as two Docker
containers on a single EC2 instance, with nginx in the frontend container reverse-proxying `/api` and
`/uploads` to the API container over the Docker network (no public 8080, no CORS needed in that
topology — CORS only matters for local dev / split-domain deploys).

The canonical specs live in the team Notion (기능명세서 and API 명세서), mirrored into
`backend/docs/API명세서_v0.6.md` and `backend/docs/DB스키마_v1.4.md` — **when code and docs disagree,
the spec wins**. Older `v0.5`/`v1.2`/`v1.3` doc versions are kept only as change history.

**Note on `README.md`**: it currently describes an early "API skeleton" stage (28 stub endpoints
returning 501). The codebase has moved well past that — most parts have real services, JPA entities,
JWT auth, and substantial test suites (see architecture below). Treat the README's team-process
sections (branch/commit rules, per-owner part boundaries) as current, but don't trust its
"current status" description of the code.

## Commands

### Backend (`backend/`)

```bash
./gradlew bootRun                       # run the API on :8080 (H2 file DB at backend/data/boothlock)
./gradlew build                         # full build incl. tests — this is what CI runs on every PR
./gradlew test                          # run all tests
./gradlew test --tests "*.OrderCreateServiceTests"          # single test class
./gradlew test --tests "*.OrderCreateServiceTests.createsOrder"  # single test method
```

- JDK 21 is fetched automatically via the Gradle toolchain — no local JDK install needed.
- Sanity check the server is up: `curl http://localhost:8080/api/v1/menus` (an implemented endpoint
  now returns real data/errors, not 501 — 501 only remains for genuinely unbuilt stubs).
- H2 console: `http://localhost:8080/h2-console`, JDBC URL `jdbc:h2:file:./data/boothlock;MODE=MySQL`,
  user `sa`, no password.
- Timezone is force-set to `Asia/Seoul` for `bootRun`/`test` JVM args in `build.gradle` — business-day
  logic (see below) assumes KST regardless of host/container timezone.
- Swagger/OpenAPI UI is available in dev (`springdoc`); disabled in prod unless
  `BOOTLOCK_SWAGGER_ENABLED=true`.

### Frontend (`frontend/`)

```bash
npm run dev        # Vite dev server on :5173, proxies /api and /uploads to localhost:8080
npm run build       # tsc -b && vite build
npm run lint         # oxlint
npm run test         # vitest run
npm run preview      # preview a production build
```

### Docker / deployment

```bash
docker compose -f docker-compose.local-mysql.yml up   # local dev against MySQL instead of H2
docker compose -f docker-compose.prod.yml up -d --build  # prod: api + frontend containers on one EC2 host
```

Production runs `SPRING_PROFILES_ACTIVE=prod` (`application-prod.properties` overlays the base
properties). Required env vars with **no default** — startup fails without them:
`BOOTLOCK_JWT_SECRET`, `BOOTLOCK_CUSTOMER_BASE_URL`, `BOOTLOCK_CORS_ALLOWED_ORIGINS`. See
`.env.prod.example` and `backend/docs/배포_운영절차.md` for the full deploy runbook (currently H2
file DB on EC2, not RDS — a deliberate pilot-scale tradeoff; data loss on instance failure is
accepted).

## Architecture

### Backend: package-per-domain, layered within each

`backend/src/main/java/com/boothlock/boothlock_server/` is split into **owner-scoped domain
packages** (`order/`, `dashboard/`, `tableqr/`, `menu/`, `settle/`, `booth/`, `event/`), each with the
same internal shape:

```
{domain}/controller/   HTTP layer, thin
{domain}/service/      validation + business rules
{domain}/repository/   Spring Data JPA repositories
{domain}/domain/       @Entity classes + domain-local enums
{domain}/dto/          request/response records
```

Plus `global/` (shared error types, `OrderStatus`/`PaymentStatus` enums, `SeatIdlePolicy`) and `seed/`
(one-shot event/booth data seeding, gated by `boothlock.seed.enabled`).

CONTRIBUTING.md (Korean, team process doc) enforces per-part file ownership: each package above
belongs to one contributor and cross-part edits require a heads-up + PR reviewed by that owner. The
`Order`/`OrderItem` entities are the one deliberate exception — a cross-cutting join point touched by
four parts (order, dashboard, tableqr, settle), so their full field set was created upfront by the
order owner rather than grown incrementally. `global/error/`, `global/domain/`, and build/config files
(`build.gradle`, `application*.properties`, `.github/`) are shared and require team notice before
editing.

### Two independent auth mechanisms

There is no Spring Security filter chain — auth is done by hand in service helpers:

- **Customers**: `X-Session-Token` header → `TableSessionAuthService.authenticate()` →
  `AuthenticatedSession(sessionId, boothId, tableLabel)`. Missing header = 401, expired/unknown token
  = 410 `SESSION_EXPIRED`. Any request that reads/writes a session also **touches** it (extends
  `last_activity_at`), which feeds into seat-idle accounting (see below) — polling counts as activity.
- **Staff**: `Authorization: Bearer <jwt>` → `BoothJwtProvider` (HS256, `spring-security-oauth2-jose`
  used purely as a JWT codec, not as a security framework) → `BoothStaffAuthenticator.authenticate()` →
  `StaffAccountEntity`. Roles are `SUPER_ADMIN` (no booth, platform-level — forbidden from
  booth-scoped dashboard endpoints), `ADMIN`, `STAFF`. **Booth scope always comes from the verified JWT
  claim, never from a request body/param boothId** — this is a deliberate anti-IDOR rule repeated in
  several service comments. Tokens embed `pwdAt` (password-changed-at) so changing a password
  invalidates previously issued tokens; JWT secret must be ≥32 bytes, and the default dev secret logs
  a warning if used.

`CorsConfig` only matters when frontend and API are on different origins (local dev, or a future
split-domain deploy) — in the current single-EC2 deploy, nginx makes both same-origin so
`boothlock.cors.allowed-origins` is left empty and the CORS mapping never registers. It rejects `*`
and malformed origins at startup on purpose (booth/admin APIs must not be reachable from arbitrary
origins with a stolen token); `allowCredentials` is always false because auth is header-based, not
cookies.

### Order domain model — the cross-cutting core

- **Two independent status axes** (`global/domain/OrderStatus`, `PaymentStatus`) — never conflate
  them: `OrderStatus` is `RECEIVED → DONE / CANCELED`; `PaymentStatus` is
  `UNPAID → PAID → REFUND_NEEDED → REFUNDED`.
- **Order numbering** (`OrderNumberingService`): human-facing order numbers like `A3-17` (table label
  + daily sequence) are assigned from a `daily_counter` row locked with `FOR UPDATE` inside the same
  transaction as the order insert — never auto-increment, and `nextSeq()` throws if called from a
  read-only transaction (Hibernate would silently drop the counter flush otherwise).
- **Business day boundary is 06:00 KST**, not midnight — `businessDateOf()` subtracts 6 hours before
  taking the date. This affects daily counters, settlement, and "unpaid order" seat-idle exemptions.
  Assume KST everywhere in date/time logic; this is why the JVM timezone is force-pinned in Gradle and
  Docker.
- **Order creation is idempotent**: `POST /orders` requires an `Idempotency-Key` header; replaying the
  same key returns the existing order with `200` instead of creating a duplicate (`201`).
- Order creation re-validates sold-out state and recomputes price server-side — client-submitted
  prices/availability are never trusted.

### Seat/idle accounting — one policy, several consumers

Whether a table counts as "occupied" is **not** derived from `booth_table.status` (that column exists
but is documentation-only per DB schema design principle 14) — it's computed by `SeatIdlePolicy` from
whether the table has an open session (`ended_at IS NULL`) that's either recently active
(`last_activity_at` within `boothlock.event.seat-idle-minutes`, default 180) **or** has an unpaid order
in the current business day. This single policy backs customer QR re-scan behavior (idle sessions are
ended and reissued rather than resumed), the staff dashboard's seat/cleanup view, and the public event
booth-list occupancy count — so a change here has three consumers to check, and aggregation queries
must put the session-open condition in the `JOIN ON` clause, not `WHERE` (otherwise tables with no
session at all get excluded entirely).

### Global error contract

All business errors flow through one `@RestControllerAdvice`
(`global/error/GlobalExceptionHandler`) mapping ~13 shared exception types (`SoldOutException`,
`InvalidStateException`, `SessionExpiredException`, `AlreadyPaidException`, `CallCooldownException`,
`LoginLockedException`, etc.) to the spec's fixed `{code, message, details}` error body and HTTP
status. New error cases should reuse one of these existing exceptions rather than inventing a new one
(this is an explicit team-shared-file rule); a generic `Exception` handler is the last-resort 500
safety net. `NotImplementedException` → 501 is reserved for genuinely-unbuilt stub endpoints, kept
distinct from `UnsupportedOperationException` (a real bug elsewhere) so the two aren't confused.

### Frontend

React 19 + TypeScript + Vite + Tailwind v4, client-routed with `react-router-dom` (`src/App.tsx`
lists every route — two audiences share one SPA):

- **Customer flow** (no login): `/t/:tableToken` (QR landing) → `/party-size` → `/order` → `/cart` →
  `/order-confirm` → `/payment-info` → `/order-history`; session expiry has its own route
  (`/session-expired`). Uses `X-Session-Token`, stored/managed via `lib/customerSession.ts` and
  fetched via `lib/customerApiFetch.ts`.
- **Staff flow** (JWT login): `/` (login) → `/home`, `/tables`, `/settings/*` (menu CRUD, account,
  table/QR management). Uses `lib/apiFetch.ts` with `Authorization: Bearer`, managed via `lib/auth.ts`.
- `lib/idempotencyKey.ts` generates the client-side idempotency key for order submission;
  `lib/orderActions.ts` / `lib/sessionOrders.ts` wrap the polling-based order status flow described in
  the README (customer screen refetches every 5–10s, dashboard every 3–5s — no WebSockets, deemed
  unnecessary at pilot scale).
- In dev, Vite proxies `/api` and `/uploads` to `localhost:8080` (`vite.config.ts`) so no CORS setup
  is needed locally either.

## Team process (from CONTRIBUTING.md)

This is a student team project with hard collaboration rules that apply to AI-assisted work too:

- No direct push to `main`; every change goes through a branch → PR → 1 reviewer approval → squash
  merge. No force-push, no rebase-to-resolve-conflicts (merge `main` in instead).
- One branch = one stub/bug fix; PRs are capped at ~300 changed lines (first-PR entity/repo/service
  scaffolding for a part is the documented exception).
- Never edit another contributor's owned package (`order/`, `dashboard/`, `tableqr/`, `menu/`,
  `settle/`, `booth/`) without prior chat notice and a PR reviewed by that owner; same for the shared
  files listed under "Global error contract" above plus build/config files.
- CI (`.github/workflows/build.yml`) runs `./gradlew build` on every PR — a red build blocks merge, so
  run it locally before pushing.
