# Boundary 1.0.0-beta-1 Upgrade — Master Design

**Date:** 2026-07-25
**Status:** Approved (design), specs pending per sub-project
**Scope:** Bring all three example apps onto Boundary Framework `1.0.0-beta-1`.

## Goal

Boundary bumped `1.0.1-alpha-42` → `1.0.0-beta-1` (branch `chore/bump-to-beta-1`,
commit `2d0ea514`). Update the example mono-repo so every app runs on beta-1 and
demonstrates the framework. `1.0.0-beta-1` artifacts are already resolvable (present
in `~/.m2/repository/org/boundary-app/*`, `maven-metadata-clojars.xml` present).

## Current state

| App | Boundary today | Notes |
|-----|----------------|-------|
| `ecommerce-api` (:3002) | `1.0.1-alpha-13` (user + admin + platform db-context) | Wiring isolated to `src/ecommerce/system.clj`; custom modules (product/cart/order/payment) roll their own next.jdbc + HTTP. |
| `blog-app` (:3001) | none — vanilla Clojure | Integrant + Reitit + next.jdbc SQLite + Aero + Hiccup/HTMX. `post` module FC/IS; `comment` schema-only. Demo auth in handlers. |
| `notification-service` (:3003) | none — vanilla Clojure | Integrant + Reitit + `core.async` pub/sub bus (`shared/bus.clj`) + in-memory stores. `event` + `notification` modules. |

## Decisions (locked)

- **blog-app:** adopt platform + `boundary-user` (real author auth) + `boundary-admin`
  (posts/comments CRUD via auto admin UI) + `boundary-ui-style`.
- **notification-service:** adopt platform + `boundary-realtime` (WebSocket pub/sub
  replaces the `core.async` bus) + `boundary-user` (realtime WS needs JWT auth).
- **method:** `blog-app` and `notification-service` are re-scaffolded with
  `boundary new` then domain code ported in. `ecommerce-api` is upgraded **in place**
  (already on Boundary; re-scaffolding a working reference is pointless churn).
- **version pinning:** mvn coords `{:mvn/version "1.0.0-beta-1"}` (matches the
  examples' style; `boundary new` also emits mvn coords).

## Breaking changes (alpha-13 → beta-1)

Source: `boundary/main:UPGRADING.md` (documents alpha-30→alpha-42) plus
`CHANGELOG.md [Unreleased]` (the beta-1 "huge upgrade").

1. **App owns feature `module-wiring` requires** (BOU-171/192/198). Platform no longer
   requires user/admin/tenant/workflow/search `module-wiring`. **Failure mode:** loud —
   Integrant init fails on a missing `init-key` defmethod.
   *ecommerce already requires `boundary.user.shell.module-wiring` +
   `boundary.admin.shell.module-wiring`.*
2. **Tenant HTTP middleware relocated** to the tenant lib; wired by the app via
   `:extra-middleware` (BOU-198/200). **N/A** — no example uses tenant.
3. **`boundary-shared-ui` split out** as its own artifact (BOU-193/194/202). beta-1
   POMs declare inter-Boundary deps, so the closure resolves transitively → the
   hand-enumerated deps list can be **trimmed** to directly-used modules.
4. **JWT hardening** (BOU-163): JWT algorithm pinned; **`JWT_SECRET` ≥32 chars
   required + a separate CSRF secret**; **fail-fast at boot** on a weak/missing secret.
5. **User-route authz hardening** (BOU-190/191/197): `GET /api/v1/users` (list) and
   `PUT`/`DELETE /users/:id` admin-only; `GET /users/:id` self-or-admin; non-admin
   cross-user → **403**. Sessions rotate on password/role change. Behavior change —
   may break existing tests/clients.
6. Perf / FC-IS-purity / internal refactors — no consumer action.

**Undocumented gap:** `UPGRADING.md` starts at alpha-30; alpha-13→30 is not
documented. Mitigation: empirical verification loop (below) on every sub-project.

## Verification loop (every sub-project)

```bash
bb doctor            # config + stale-wiring checks (upgrade-wiring, wiring-requires)
# boot the system, confirm no init-key/secret failures
clojure -M:test      # the app's suite
```

## Canonical target shape (from `boundary-starter`)

- `deps.edn`: core-4 (`core`, `observability`, `platform`, `user`) + optional
  pre-wired modules, all mvn coords at `1.0.0-beta-1`.
- `src/boundary/config.clj`: `load-config` (Aero) + `ig-config` with conditional
  `module-wiring` requires driven by `:active` config.
- `resources/conf/{env}/config.edn`: `:active {:boundary/settings :boundary/http
  :boundary/router :boundary/logging :boundary/<db> ...}`.
- `.env`: auto-generated `JWT_SECRET` (base64 32 bytes).
- App custom modules register `ig/init-key` in `src/<ns>/system.clj`.

## Sub-projects

### 1. ecommerce-api — in-place bump + fix (do first)

- `deps.edn`: bump all 7 boundary libs `1.0.1-alpha-13` → `1.0.0-beta-1`; trim the
  hand-enumerated closure to directly-used (`admin`, `platform`, `observability`,
  `user`); add `boundary-shared-ui` only if boot complains.
- Config: add `JWT_SECRET` (≥32) + separate CSRF secret to config/env + test config;
  `.env` for dev.
- Verify `module-wiring` requires still resolve under beta-1 namespace names.
- Fix test/assertion fallout from user-route authz 403 hardening.
- No tenant → UPGRADING steps 1–3 N/A.
- **Deliverable:** own spec → plan → implement.

### 2. blog-app — scaffold + port (platform + user + admin + ui-style)

- `boundary new blog` → canonical shape; `boundary add` user/admin/ui-style.
- Port `post` (+ implement `comment`, currently schema-only) as custom FC/IS modules
  wired on platform; keep pure `core/post.clj` + `core/ui.clj`.
- Author auth: `boundary-user` replaces demo handler auth.
- Authoring UI: `boundary-admin` auto CRUD for posts/comments — **removes** the old
  custom dashboard/HTMX authoring. Public HTMX read views (`/`, `/posts/:slug`) stay
  custom.
- Migrations `001-posts` / `002-comments` fold into the platform migration flow.
- **Deliverable:** own spec → plan → implement.

### 3. notification-service — scaffold + port (platform + realtime + user)

- `boundary new notification` → canonical shape; `boundary add` realtime/user.
- Replace `shared/bus.clj` core.async bus with `boundary-realtime` WebSocket topic
  pub/sub. Rewire order/payment/shipment handlers onto realtime topics.
- Port `event` + `notification` FC/IS modules; keep pure `core/*` + `shared/retry.clj`.
- Keep REST endpoints (`/api/events`, `/api/notifications`) and in-memory stores
  (DB out of scope unless realtime forces it).
- **Risk:** `boundary-realtime` is "in development" + single-server. Before committing,
  verify topic pub/sub + role routing express all 9 event→notification mappings.
  **Fallback:** keep the core.async bus if realtime can't.
- **Deliverable:** own spec → plan → implement.

## Out of scope

- Migrating blog/notification to a DB backend beyond what a chosen module requires.
- Non-Boundary dependency refresh (ring/jetty/etc.) beyond what beta-1 pulls.
- Publishing beta-1 (already resolvable).
