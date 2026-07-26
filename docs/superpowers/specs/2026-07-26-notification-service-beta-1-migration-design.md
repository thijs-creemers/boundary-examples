# notification-service → Boundary 1.0.0-beta-1 Migration Design (Sub-project 3)

**Date:** 2026-07-26
**Status:** Design (spec-review pending)
**Parent:** `2026-07-25-boundary-beta-1-upgrade-design.md`

## Goal

Migrate `notification-service` (vanilla Clojure: Integrant + Reitit + `core.async`
pub/sub bus + in-memory stores) onto Boundary `1.0.0-beta-1`, adopting
**platform + user**. **Keep the `core.async` event bus and in-memory stores as-is** —
`boundary-realtime` is NOT adopted here (it is a client/WebSocket push system, not a
server-side event bus; the server-side-connection gap is tracked in **BOU-233**).

## Decisions (locked)

- Adopt **boundary-platform + boundary-user** (core-4). No admin, no realtime.
- **Keep** the `core.async` bus (`shared/bus.clj`) + in-memory event/notification stores
  + retry logic + the 9 event→handler→notification mappings — unchanged in behavior.
- Method: **scaffold fresh + port** (same as blog-app).
- Realtime deferred to BOU-233.

## Why user + a DB in a DB-less service

The **scaffold-generated `boundary.config`** wires the user component chain
(user-repository, session/audit repositories, auth-service, user-db-schema) into its
`ig-config` unconditionally — an app-level convention, NOT enforced by platform (platform
is decoupled from user per BOU-171; a developer could strip user from `boundary.config`,
but that deviates from the scaffold). Since we use the standard scaffold, user is present
and needs a relational DB. So this migration introduces a **SQLite** database purely to
back the user module — its tables are **auto-created at boot** by the
`:boundary/user-db-schema` init-key (`initialize-user-schema!`), so **no `bb migrate` is
needed**. The **domain stores stay in-memory** (event-store, notification-store) — no
domain DB, no domain migrations. This keeps the app a pure event/notification demo while
satisfying platform+user.

## Reuse of the blog-app playbook

This mirrors sub-project 2 exactly; the same scaffold gotchas apply (all verified during
blog-app):

1. **CLI precondition** — installed CLI is `1.0.1-alpha-20`; after `boundary new` bump the
   generated `deps.edn` to `1.0.0-beta-1`. Extend root `bb bump-boundary` to include
   `notification-service/deps.edn`.
2. **Scaffold command** — `boundary new notification` (no `--base-ns`; dir `notification/`,
   ns `notification`), then relocate generated files into `notification-service/`.
3. **config.clj module-wiring fix** — the scaffold's `boundary.config` omits feature-module
   wiring loads; on beta-1 the app must require `[boundary.user.shell.module-wiring]`
   (core, always) or boot fails at `:boundary/audit-repository` with
   `No such namespace: boundary`. (Root-caused in blog-app; see memory
   `boundary-beta1-scaffold-wiring-defect`.) No admin/tenant/etc. needed here.
4. **SQLite** — add `org.xerial/sqlite-jdbc` to base `:deps`; config `:boundary/sqlite`;
   patch `db-spec` in `config.clj` for the `:boundary/sqlite` branch.
5. **Custom `:notification/http-server`** — platform `:boundary/http-handler` owns `/` and
   has no app-route seam; remove `:boundary/http-handler`/`:boundary/http-server` from
   `ig-config` and add a custom `:notification/http-server` (ecommerce/blog pattern)
   mounting the REST API + health (NOT `/web` — API-only). Match blog's `config.clj`
   handling of the scaffold's other component refs (metrics/email/etc.) — blog boots clean
   with them, so follow it verbatim rather than re-deriving.
6. **Run entry** — scaffold has no `:run`; add `notification.main` + `:run` alias +
   `bb run-notification` (with dev `JWT_SECRET`).
7. **Boot-smoke harness** — copy blog's `dev/smoke.clj` + `:smoke` alias.
8. **JWT_SECRET** — required at boot (user module guard).

## Architecture

### Custom `:notification/http-server`

Mounts (via reitit, like blog) the **REST API** (custom, unchanged):
`POST/GET /api/events`, `GET /api/events/:id`, `GET /api/notifications`,
`GET /api/notifications/:id`, `POST /api/notifications/:id/retry`, `GET /health`.

**Do NOT mount boundary `/web` user routes** — notification-service is an API service with
no auth need, and mounting login UI is noise. The user module's components still
initialize (and `validate-jwt-secret!` still fires at boot, so **`JWT_SECRET` is required
regardless** of whether `/web` is served). We simply don't add `:boundary/user-routes` to
the router. (If a future need arises, mounting `/web` is a one-line addition.)

Middleware: JSON (the existing custom `wrap-json-body` + keyword/params), the existing
stack. Jetty on `:boundary/http` port **3003**.

### Integrant components (ported, custom keys)

Keep the domain wiring, renamed onto the scaffold's config (`ig-config` gets these added):
- `:notification/bus` — `InMemoryBus` (core.async), unchanged.
- `:notification/event-store`, `:notification/notification-store` — in-memory, unchanged.
- `:notification/sender` — `MockNotificationSender`, unchanged.
- `:notification/event-service` (store + bus), `:notification/notification-service`
  (store + sender + config) — unchanged.
- `:notification/handlers` — registers order/payment/shipment subscribers on the bus at
  init, unchanged.
- `:notification/http-server` — new (above), refs the event-service + notification-service
  (NOT `:boundary/user-routes` — API-only).

The pure core (`event/core`, `notification/core`, `shared/retry`) + schemas port as-is.

**Port the domain wiring EXACTLY as today** — do not restructure it. In the current
`system.clj`, `:notification/bus` is passed `{}` (its `create-bus` uses built-in defaults;
the `:bus` values in the old `dev.edn` are currently dead config), and the notification
service's `{:channels … :retry …}` config is **hardcoded inline in the ig-config map**,
not read from a file. Preserve that: keep the bus config and the notification `:config`
map inline in `ig-config` exactly as they are now. Do NOT invent new `:active`-reading
wiring. (If desired later, folding into `:active` is a separate change — out of scope.)

### Config

`resources/conf/{dev,test}/config.edn` `:active`:
- `:boundary/settings` (name "notification-dev"), `:boundary/http` (port 3003),
  `:boundary/router`, `:boundary/logging`, `:boundary/sqlite` (dev file / test `:memory:`).
- The domain (`bus`, `notification` channels/retry) config stays inline in `ig-config` as
  today — not in `:active` (see above).

## Verification loop

```bash
# JWT_SECRET required at boot (user module guard). No bb migrate — user tables are
# auto-created at boot by :boundary/user-db-schema; the domain has no migrations.
clojure -M:smoke      # system boots green (bus + stores + services + http-server + user)
clojure -M:test       # ported pure-core tests (event/notification/retry) pass
```
Manual: `POST /api/events` routes through the bus → a handler creates a notification;
`GET /api/notifications` lists it; `/health` 200. (No `/web` routes — API-only.)

## Out of scope

- boundary-realtime (BOU-233).
- Moving domain stores to a DB (kept in-memory).
- admin UI.

## Risks

- **user module needs a DB in a DB-less app** — mitigated by SQLite for user only; domain
  stays in-memory. User tables are auto-created at boot by `:boundary/user-db-schema`
  (`initialize-user-schema!`); no `bb migrate` required (verified in blog-app).
- **core.async bus under the platform system lifecycle** — ensure `:notification/bus`
  `halt-key!` stops workers cleanly on shutdown (it already has `stop!`).
- Same scaffold-wiring + sqlite db-spec pitfalls as blog — already solved there; reuse.
