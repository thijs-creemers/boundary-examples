# notification-service → Boundary 1.0.0-beta-1 Migration Plan (Sub-project 3)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-platform `notification-service` onto Boundary `1.0.0-beta-1` (platform + user), keeping the `core.async` event bus, in-memory stores, retry logic, and REST API unchanged in behavior. No admin, no realtime (deferred → BOU-233).

**Architecture:** Scaffold provides system/config/db/user/JWT. The domain (event bus, stores, services, handlers, REST endpoints) is ported and wired via a custom `:notification/http-server` (platform's handler owns `/` and has no app-route seam). SQLite backs the user module only (tables auto-created at boot); domain stays in-memory.

**Tech Stack:** Clojure, tools.deps, Integrant, Aero, Reitit, core.async, next.jdbc + SQLite (user only), Kaocha, Babashka; Boundary platform + user @ `1.0.0-beta-1`.

**Spec:** `docs/superpowers/specs/2026-07-26-notification-service-beta-1-migration-design.md`
**Reference:** the completed `blog-app` migration (same scaffold fixes) + `ecommerce-api` (custom `:*/http-server`). This plan reuses those proven patterns.

**Working dir:** repo root `/Users/thijscreemers/work/worktrees/boundary-examples/main`; app dir `notification-service/`. Branch `feat/upgrade-boundary-beta-1` (do NOT switch).

---

## Known-good facts (from blog-app; do not re-derive)

- `boundary new notification` → dir `notification/`, ns `notification` (no `--base-ns`).
- Scaffold `config.clj` MUST add `[boundary.user.shell.module-wiring]` to its ns `:require` or boot fails at `:boundary/audit-repository` (`No such namespace: boundary`). (memory: `boundary-beta1-scaffold-wiring-defect`.) No admin/tenant/etc. needed here.
- Scaffold `db-spec` handles only h2/postgresql → add a `:boundary/sqlite` branch. `:boundary/sqlite` config key needed; scaffold derives db-context from `db-spec` (no `:boundary/db-context` key in `:active`).
- `org.xerial/sqlite-jdbc` must be in base `:deps` (not just aliases) for the running app.
- User tables auto-create at boot (`:boundary/user-db-schema`); **no `bb migrate`** (domain is in-memory, no domain migrations).
- Platform `:boundary/http-handler` owns `/` → build a custom `:notification/http-server`; remove `:boundary/http-handler` + `:boundary/http-server` from `ig-config`.
- Scaffold has no `:run` alias → add a `notification.main` + `:run`.
- `JWT_SECRET` (≥32) required at boot regardless of routes.

---

## Preserve first (ported forward; keep handy)

Copy to `/tmp/notif-port/` before relocation:
- `src/notification/event/**` (core/event.clj, ports.clj, schema.clj, shell/{http,service,store}.clj)
- `src/notification/notification/**` (core/notification.clj, ports.clj, schema.clj, shell/{http,service,store,sender}.clj)
- `src/notification/handler/{order,payment,shipment}.clj`
- `src/notification/shared/{bus,retry}.clj`
- `src/notification/system.clj` (source of the domain init-key defmethods + ig-config entries — will be split)
- `test/**` (all tests)
- `resources/config/{dev,test}.edn` (FYI only — these are **dead config**, never read by the
  current `system.clj`. The **source of truth for inline domain config values (bus args,
  notification `:config` channels/retry) is `system.clj` itself**, NOT these files. E.g.
  the old `dev.edn` says `max-delay-ms 300000` but `system.clj` inlines `60000` — use
  `system.clj`. Do not port values from the config files.)

---

## Task 1: Scaffold + relocate into `notification-service/`

**Files:** whole `notification-service/` tree.

- [ ] **Step 1:** Copy the preserve-list to `/tmp/notif-port/` (keep relative paths).
- [ ] **Step 2:** `boundary new notification --skip-git` from repo root (via `~/.babashka/bbin/bin/boundary`). Creates `./notification/` (ns `notification`).
- [ ] **Step 3:** Inspect + report contents of `notification/{deps.edn,bb.edn,dev/user.clj,src/boundary/config.clj,src/notification/system.clj,resources/conf/dev/config.edn}`.
- [ ] **Step 4:** Relocate — delete old vanilla infra in `notification-service/` (`src/notification/main.clj`, old `deps.edn`, old `resources/config/`); copy scaffold files into `notification-service/`; restore preserved domain files into `src/notification/...` + `test/...`. Delete temp `notification/`. NOTE: the old `src/notification/system.clj` is preserved in /tmp for reference but the scaffold's `src/notification/system.clj` (app init-keys) replaces it — Task 4 merges the domain defmethods into it.
- [ ] **Step 5:** Sanity `ls -R notification-service/src`.
- [ ] **Step 6:** Commit, NOT staging `.env`:
```bash
git add -A notification-service/
git reset -q notification-service/.env 2>/dev/null || true
git diff --cached --name-only | grep -qx 'notification-service/.env' && { echo "ERROR .env staged"; exit 1; } || echo ok
git commit -m "feat(notification): scaffold Boundary app, relocate, preserve domain"
```

---

## Task 2: Bump deps to beta-1 + sqlite-jdbc + bump task

**Files:** `notification-service/deps.edn`, repo-root `bb.edn`.

- [ ] **Step 1:** In root `bb.edn`, add `"notification-service/deps.edn"` to `bump-boundary`'s file vector.
- [ ] **Step 2:** `bb bump-boundary 1.0.0-beta-1`; verify all `org.boundary-app/*` in notification-service/deps.edn are beta-1, no alpha.
- [ ] **Step 3:** Add `org.xerial/sqlite-jdbc {:mvn/version "3.53.0.0"}` to base `:deps` (mirror blog-app). Keep `org.clojure/core.async` (verify it's present — the bus needs it; if the scaffold dropped it, add `org.clojure/core.async {:mvn/version "1.9.865"}`).
- [ ] **Step 4:** Align the REPL alias to the mono-repo convention (root CLAUDE.md: notification-service uses `clojure -M:repl-clj` on **port 7891**). The scaffold generates `:repl` on port 7888 — rename it to `:repl-clj` and set `--port 7891` (mirror blog-app's `:repl-clj`).
- [ ] **Step 5:** `cd notification-service && clojure -Spath > /dev/null && echo RESOLVED`.
- [ ] **Step 6:** Commit `git add notification-service/deps.edn bb.edn && git commit -m "build(notification): bump Boundary to 1.0.0-beta-1; add sqlite-jdbc + bump task; :repl-clj@7891"`.

---

## Task 3: SQLite config + db-spec patch + module-wiring fix

**Files:** `notification-service/resources/conf/{dev,test}/config.edn`, `src/boundary/config.clj`.

- [ ] **Step 1:** Dev config `:active`: remove `:boundary/h2`; add `:boundary/sqlite {:db "notification-dev.db" :pool {:minimum-idle 1 :maximum-pool-size 3 :connection-timeout-ms 10000}}`. Set `:boundary/settings :name "notification-dev"`, `:boundary/http :port 3003`. Keep router/logging.
- [ ] **Step 2:** Test config `:active`: `:boundary/sqlite {:db ":memory:"}`.
- [ ] **Step 3:** In `src/boundary/config.clj` ns `:require`, add `[boundary.user.shell.module-wiring]` (comment: beta-1 app owns feature-module wiring loads; user is core).
- [ ] **Step 4:** In `src/boundary/config.clj` `db-spec`, add the `:boundary/sqlite` branch before `:else` (copy from blog-app: `{:adapter :sqlite :database-path (get-in active [:boundary/sqlite :db]) :pool (get-in active [:boundary/sqlite :pool])}`).
- [ ] **Step 5:** Commit `git add notification-service/resources/conf notification-service/src/boundary/config.clj && git commit -m "feat(notification): SQLite config + db-spec patch + user module-wiring require"`.

---

## Task 4: Port domain + custom `:notification/http-server`

**Files:** `src/notification/system.clj` (merge domain init-keys), `src/boundary/config.clj` (ig-config domain entries + remove platform HTTP keys). Domain namespaces (event/notification/handler/shared) restored in Task 1 — keep as-is.

- [ ] **Step 1:** Confirm pure/domain namespaces compile unchanged: `event/core`, `notification/core`, `shared/retry`, `shared/bus`, schemas, ports, stores, services, sender, handlers. Adjust only if they referenced the old `notification.system`/config.
- [ ] **Step 2:** Merge the domain `ig/init-key`/`halt-key!` defmethods from the OLD system.clj (`/tmp/notif-port/system.clj`) into the scaffold's `src/notification/system.clj`: `:notification/bus` (+ halt stops workers via `bus/stop!`), `:notification/event-store`, `:notification/notification-store`, `:notification/sender`, `:notification/event-service`, `:notification/notification-service`, `:notification/handlers`, and a `:notification/http-server`. Keep the notification service `:config` map (channels/retry) and the bus arg inline exactly as the old code had them (do not fold into `:active`).
- [ ] **Step 3:** The `:notification/http-server` init-key: build the reitit ring-handler from the ported event + notification REST routes (`/api/events*`, `/api/notifications*`, `/health`) with the existing middleware (wrap-json-body, wrap-keyword-params, wrap-params). Start Jetty on the `:boundary/http` port (read from config). Add `halt-key!` to stop it. (No `/web`, no normalized->reitit — API only.) Model the lifecycle on blog's `:blog/http-server`.
- [ ] **Step 4:** In `src/boundary/config.clj` `ig-config`: the scaffold **already generates** all the `:boundary/*` entries (logging, metrics, error-reporting, i18n, router, user-db-schema, user-repository, session/audit-repository, mfa/auth/user-service, user-routes, http-handler, http-server). **Keep those as generated** — do NOT re-derive or copy them from blog-app. Only:
  - **REMOVE** `:boundary/http-handler` + `:boundary/http-server` (replaced by the custom server).
  - **LEAVE** `:boundary/user-routes` in place as generated — it's a terminal component (nothing refs it; the custom API-only server just doesn't mount it). Harmless; don't delete it.
  - **ADD** the domain component entries (`:notification/bus`, `-event-store`, `-notification-store`, `-sender`, `-event-service`, `-notification-service`, `-handlers`, `-http-server`) with their `ig/ref`s exactly mirroring the OLD `system.clj`'s `config` map.
  - `:notification/http-server` refs event-service + notification-service + `config` (for the port). Ensure `notification.system` is required so its defmethods load (scaffold requires the app system ns — verify).
- [ ] **Step 5:** Compile check `cd notification-service && clojure -e "(require 'boundary.config 'notification.system :reload)"` → no error. And `clojure -M:test` stays green.
- [ ] **Step 6:** Commit `git add notification-service/src && git commit -m "feat(notification): port domain (bus/stores/services/handlers) + custom :notification/http-server"`.

---

## Task 5: Smoke harness + run entry

**Files:** `notification-service/dev/smoke.clj`, `notification-service/src/notification/main.clj`, `deps.edn` (`:smoke` + `:run`), root `bb.edn` (`run-notification`).

- [ ] **Step 1:** Create `dev/smoke.clj` (copy blog's — requires `boundary.config`, `(ig/init (config/ig-config (config/load-config)))` then `ig/halt!`, catch Throwable, print PASSED/FAILED, exit 0/1).
- [ ] **Step 2:** Create `src/notification/main.clj` as a **clean replacement** (the old `main.clj` was deleted in Task 1; do NOT adapt it — its old signature passed a flat `{:port …}` map to a `start-system` that no longer exists). Copy blog's `blog.main` shape → `notification.main`: bind `cfg` once via `config/load-config`, `ig/init (config/ig-config cfg)`, print port from `(get-in cfg [:active :boundary/http :port])`, shutdown hook `ig/halt!`, block on `@(promise)`.
- [ ] **Step 3:** Ensure `deps.edn` has `:smoke {:extra-paths ["dev"] :main-opts ["-m" "smoke"]}` and `:run {:main-opts ["-m" "notification.main"]}` (the scaffold may already include `:run` — verify it points to `notification.main`).
- [ ] **Step 4:** Add `run-notification` task to root `bb.edn` (`:dir "notification-service"`, `:extra-env {"JWT_SECRET" "dev-secret-change-me-min-32-characters"}`, `clojure -M:run`).
- [ ] **Step 5:** Commit `git add notification-service/dev notification-service/src/notification/main.clj notification-service/deps.edn bb.edn && git commit -m "feat(notification): boot-smoke harness + notification.main run entry"`.

---

## Task 6: Full verification

**Files:** none.

- [ ] **Step 1: Boot smoke** — `cd notification-service && JWT_SECRET=dev-secret-change-me-min-32-characters clojure -M:smoke`. Expect `BOOT SMOKE PASSED`, components incl. `:notification/bus`, `:notification/http-server`, `:notification/handlers`, `:boundary/user-*`. Debug wiring if it fails.
- [ ] **Step 2: Tests** — `clojure -M:test` green (ported pure-core event/notification/retry tests).
- [ ] **Step 3: Live API** — start (`bb run-notification` or `cd notification-service && JWT_SECRET=… clojure -M:run`):
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3003/health         # 200
# publish an event, expect 201 + it routes through the bus to a handler:
curl -s -X POST http://localhost:3003/api/events -H 'Content-Type: application/json' \
  -d '{"type":"order/placed","aggregate-id":"11111111-1111-1111-1111-111111111111","aggregate-type":"order","payload":{"customer-email":"a@b.com"}}' \
  -w "\n%{http_code}\n"
sleep 1
curl -s "http://localhost:3003/api/notifications" -w "\n%{http_code}\n"        # 200 + a notification created by the handler
```
Confirm a notification was created (the order/placed handler fired via the bus). Stop the server.
- [ ] **Step 4: Commit** any convenience task tweaks.

---

## Done criteria

- `notification-service/` runs on Boundary `1.0.0-beta-1` (deps pinned, resolves).
- `clojure -M:smoke` passes with `JWT_SECRET`; boots bus + stores + services + handlers + http-server + user.
- `clojure -M:test` green.
- `/health` 200; `POST /api/events` (201) routes through the core.async bus → handler → notification; `GET /api/notifications` shows it.
- core.async bus + in-memory stores retained; user tables auto-created (SQLite); no domain DB; no realtime.

## Follow-ups (out of scope)

- BOU-233 (realtime server-side connection) → then notification can push notifications to clients / optionally replace the bus.
- Root `CLAUDE.md` update (all three apps now on Boundary) — do at end of the whole upgrade.
