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
| `ecommerce-api` (:3002) | `1.0.1-alpha-13` (user + admin + platform db-context) | Wiring isolated to `src/ecommerce/system.clj`; custom modules (product/cart/order/payment) roll their own next.jdbc + HTTP. Config at `resources/conf/{dev,test}/config.edn`. Test suite is pure-core only (no boot). |
| `blog-app` (:3001) | none — vanilla Clojure | Integrant + Reitit + next.jdbc SQLite + Aero + Hiccup/HTMX. `post` module FC/IS; `comment` schema-only. Demo auth in handlers. Config at `resources/config/<env>.edn`. |
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

## PRECONDITION (blocking) — beta-1-aware CLI

The installed `boundary` CLI is **`1.0.1-alpha-20`**
(`~/.boundary/releases/1.0.1-alpha-20/...`; `boundary-cli` in `~/.m2` stops at
alpha-20 — no beta-1 published). Its `modules-catalogue.edn` pins module versions to
alpha. So `boundary new` / `boundary add` **emit alpha coords, not beta-1**.

Resolve before scaffolding (sub-projects 2 & 3), pick one:
- **(a) Build/run the CLI from `boundary/main` `chore/bump-to-beta-1`** so its
  catalogue emits beta-1, then scaffold. Preferred if quick.
- **(b) Scaffold with the alpha-20 CLI, then bump the generated `deps.edn` to
  `1.0.0-beta-1` by hand** (mechanical, one edit). Simplest, always works.

Each sub-project spec states which path it takes. ecommerce-api (in-place, no
scaffold) is unaffected.

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
   required**, enforced by `validate-jwt-secret!`
   (`libs/user/.../shell/auth.clj`). *Caveat:* the guard is an explicit startup check
   and `jwt-secret` is a lazy `delay` — an app that never calls the guard and never
   signs/verifies a token won't actually fail at boot. The verification loop must
   exercise it (below).
   **CSRF is NOT a separate required secret.** Per
   `libs/platform/.../shell/system/wiring.clj`: CSRF is `:enabled? false` by default
   and its secret **falls back to `JWT_SECRET`**; boot only throws if an app
   *explicitly enables* CSRF with a blank secret. So no CSRF work is required unless an
   app opts in — then set `CSRF_SECRET` (or reuse `JWT_SECRET`).
5. **User-route authz hardening** (BOU-190/191/197): `GET /api/v1/users` (list) and
   `PUT`/`DELETE /users/:id` admin-only; `GET /users/:id` self-or-admin; non-admin
   cross-user → **403**. Sessions rotate on password/role change. Behavior change —
   may break existing tests/clients.
6. Perf / FC-IS-purity / internal refactors — no consumer action.

**Undocumented gap:** `UPGRADING.md` starts at alpha-30; alpha-13→30 is not
documented. Mitigation: empirical verification loop (below) on every sub-project.

## Verification loop (every sub-project)

The suites are **pure-core only** (no boot) — they do NOT cover the risky beta-1
changes, which are all boot-time / shell-layer (missing `module-wiring` init-key, JWT
secret, the undocumented alpha-13→30 gap). So the boot smoke is mandatory and must be
concrete, not a manual REPL afterthought:

```bash
bb doctor            # config + stale-wiring checks (upgrade-wiring, wiring-requires)
clojure -M:test      # pure-core suite
```
Plus a scripted boot smoke per app (add a tiny `-M:smoke` or REPL script):
```clojure
(let [sys (sys/start!)]                       ; real ig/init — catches missing init-keys
  (boundary.user.shell.auth/validate-jwt-secret!)  ; force the JWT guard (lazy otherwise)
  ;; hit one auth'd + one public route, assert 200/expected
  (sys/stop! sys))
```

## Canonical target shape (from `boundary new` template)

Reference is the CLI `deps.edn.tmpl`, NOT `boundary-starter/my-app` (that uses old
git/sha coords from a deprecated wizard).

- `deps.edn`: `boundary new` emits the **full closure** (core, observability, platform,
  user, cache, admin, ui-style, tenant, workflow, search, external, payments, i18n + h2)
  — cache/admin/ui-style/etc. are "required by boundary-platform's system wiring
  (unconditional module loads)", not truly optional. Trim only after confirming boot.
- `src/boundary/config.clj`: `load-config` (Aero) + `ig-config` with conditional
  `module-wiring` requires driven by `:active` config.
- `resources/conf/{env}/config.edn`: `:active {:boundary/settings :boundary/http
  :boundary/router :boundary/logging :boundary/<db> ...}`.
- `.env`: auto-generated `JWT_SECRET` (base64 32 bytes).
- App custom modules register `ig/init-key` in `src/<ns>/system.clj`.

## Sub-projects

### 1. ecommerce-api — in-place bump + fix (do first)

- `deps.edn`: bump all 7 boundary libs `1.0.1-alpha-13` → `1.0.0-beta-1` **in place**.
  Do NOT trim the closure in the same step — platform loads cache/ui-style/admin
  unconditionally, and neither is directly required in `src/` (verified), so trimming
  risks a boot failure for no benefit. Trimming to the transitive-resolving set is a
  separate, optional follow-up gated on a green boot.
- Config: add `JWT_SECRET` (≥32 chars) to `resources/conf/dev/config.edn` +
  `resources/conf/test/config.edn`, and a dev `.env`. **No CSRF secret** (CSRF is
  opt-in and inherits `JWT_SECRET`; ecommerce doesn't enable it).
- Verify `module-wiring` requires still resolve under beta-1 namespace names (already
  present: `boundary.user.shell.module-wiring`, `boundary.admin.shell.module-wiring`).
- Authz 403 hardening (BOU-190/191/197): **verify no impact** — the suite is pure-core
  and never exercises `/users` (grep confirms). No fix expected; note if a boot smoke
  hitting user routes surfaces anything.
- No tenant → UPGRADING steps 1–3 N/A.
- **Deliverable:** own spec → plan → implement.

### 2. blog-app — scaffold + port (platform + user + admin + ui-style)

- `boundary new blog` → canonical shape; `boundary add` user/admin/ui-style. **Apply
  the CLI PRECONDITION** (bump generated `deps.edn` to beta-1, or use a beta-1 CLI).
- Port `post` (+ implement `comment`, currently schema-only) as custom FC/IS modules
  wired on platform; keep pure `core/post.clj` + `core/ui.clj`.
- Author auth: `boundary-user` replaces demo handler auth.
- Authoring UI: `boundary-admin` auto CRUD for posts/comments — **removes** the old
  custom dashboard/HTMX authoring. Public HTMX read views (`/`, `/posts/:slug`) stay
  custom.
- Migrations `001-posts` / `002-comments` fold into the platform migration flow.
- **Deliverable:** own spec → plan → implement.

### 3. notification-service — scaffold + port (platform + realtime + user)

- `boundary new notification` → canonical shape; `boundary add` realtime/user. **Apply
  the CLI PRECONDITION** (bump generated `deps.edn` to beta-1, or use a beta-1 CLI).
- Replace `shared/bus.clj` core.async bus with `boundary-realtime` WebSocket topic
  pub/sub. Rewire order/payment/shipment handlers onto realtime topics.
- Port `event` + `notification` FC/IS modules; keep pure `core/*` + `shared/retry.clj`.
- Keep REST endpoints (`/api/events`, `/api/notifications`) and in-memory stores
  (DB out of scope unless realtime forces it).
- **Risk (reframed):** `boundary-realtime` DOES support topic pub/sub + role-based
  routing (`libs/realtime` core/pubsub) — the real constraint is **single-server / no
  cross-server pub/sub** (no Redis fan-out; sticky-sessions workaround). Fine for a
  single-instance demo. The 9 event→notification mappings are expressible; core.async
  fallback is a last resort only if a concrete blocker appears during porting.
- **Deliverable:** own spec → plan → implement.

## Out of scope

- Migrating blog/notification to a DB backend beyond what a chosen module requires.
- Non-Boundary dependency refresh (ring/jetty/etc.) beyond what beta-1 pulls.
- Publishing beta-1 (already resolvable).
