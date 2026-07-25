# ecommerce-api → Boundary 1.0.0-beta-1 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade `ecommerce-api` from Boundary `1.0.1-alpha-13` to `1.0.0-beta-1`, booting green with the new fail-fast `JWT_SECRET` guard and passing its test suite.

**Architecture:** In-place version bump (no re-scaffold — the app already runs on Boundary). A boot-smoke harness is added FIRST as the safety net, run green on alpha-13, then the deps are bumped. Two beta-1 breaks then surface: (1) `boundary.admin.shell.http/wrap-method-override` was **removed** — the app must own an equivalent middleware; (2) `JWT_SECRET` fail-fast at boot (triggered by `boundary.user.shell.module-wiring` calling `validate-jwt-secret!` during `:boundary/auth-service` init). The removed var is a load-time failure, so it is fixed first (Task 3) before the JWT prediction can be observed (Task 4). Then supply the secret, confirm tests + full boot.

**Tech Stack:** Clojure, tools.deps, Integrant, Kaocha, Babashka; Boundary `boundary-admin`/`boundary-platform`/`boundary-user`/etc.

**Spec:** `docs/superpowers/specs/2026-07-25-boundary-beta-1-upgrade-design.md`

**Working dir:** all paths relative to `ecommerce-api/` unless noted. Run commands from `ecommerce-api/`.

---

## Key facts (verified)

- **API removal:** `boundary.admin.shell.http/wrap-method-override` (used at `src/ecommerce/system.clj:37` require + `:240` call) **does not exist in beta-1**. beta-1's `boundary.admin.shell.http` exposes only `normalized-web-routes` + `admin-routes-normalized`; the method-override logic moved to an inline fn inside `boundary.platform.shell.system.wiring` (which this app doesn't use — it builds its own reitit router). Loading `ecommerce.system` under beta-1 therefore throws `No such var` at load time → the smoke fails before reaching the JWT check. Must be fixed first. `admin-http` is referenced ONLY here (verified), so the require is removed too.
- **Other `boundary.*` var refs in `src/` all survive beta-1** (verified): `db-factory/db-context` + `close-db-context!` (platform `factory.clj`), `no-op-logging/create-logging-component` + `no-op-errors/create-error-reporting-component` (observability). No other code fixes needed.
- `JWT_SECRET` is read via `System/getenv "JWT_SECRET"` (pure env var — NOT Aero/config), min length 32, in `boundary.user.shell.auth`. The guard `validate-jwt-secret!` is invoked by `boundary.user.shell.module_wiring:99` during `:boundary/user-*` init — so **boot fails fast** on a missing/short secret in beta-1. alpha-13 has no such guard.
- Boundary's own convention: supply `JWT_SECRET` as an env var (its `bb.edn` uses `"dev-secret-32-chars-minimum"`, CI uses `"ci-test-secret-minimum-32-characters"`).
- ecommerce test suite is **pure-core only** (no system boot, never touches `/users`) — so `clojure -M:test` needs no `JWT_SECRET`, and the authz-403 hardening (BOU-190/191/197) cannot break it.
- CSRF is opt-in and inherits `JWT_SECRET`; ecommerce does not enable it → **no CSRF work**.
- `bb bump-boundary <ver>` (repo root `bb.edn`) replaces `1.\d+.\d+-alpha-\d+` in `ecommerce-api/deps.edn` → works for this bump.
- `system/start!` / `system/stop!` exist in `src/ecommerce/system.clj` (used by `src/ecommerce/main.clj`); dev profile is the default.

---

## Task 1: Boot-smoke harness (safety net, green on alpha-13)

**Files:**
- Create: `dev/smoke.clj`
- Modify: `deps.edn` (add `:smoke` alias)

- [ ] **Step 1: Write the smoke harness**

Create `dev/smoke.clj`:

```clojure
(ns smoke
  "Boot smoke test: start the full Integrant system and stop it.
   Catches missing Integrant init-keys and (on beta-1) the JWT_SECRET
   fail-fast guard. Exits non-zero on any boot failure."
  (:require [ecommerce.system :as system]))

(defn -main [& _]
  (println "[smoke] starting system…")
  (let [sys (try
              (system/start!)
              (catch Throwable t
                (println "[smoke] BOOT FAILED:" (.getMessage t))
                (System/exit 1)))]
    (println "[smoke] started OK — components:" (sort (keys sys)))
    (system/stop! sys)
    (println "[smoke] stopped OK")
    (println "[smoke] BOOT SMOKE PASSED")
    (shutdown-agents)
    (System/exit 0)))
```

- [ ] **Step 2: Add the `:smoke` alias**

In `deps.edn`, inside `:aliases`, add:

```clojure
  :smoke
  {:extra-paths ["dev"]
   :main-opts   ["-m" "smoke"]}
```

- [x] **Step 3: Run smoke on the CURRENT (alpha-13) app — DEVIATION: baseline not achievable**

Run: `clojure -M:smoke`
Actual (alpha-13): **boot FAILS** — `Could not locate boundary/i18n/shell/middleware…` — `boundary-admin` alpha-13 requires `boundary.i18n.shell.middleware` but its POM does not declare `boundary-i18n` (undeclared transitive dep; `boundary-i18n` is not in the app's alpha-13 deps). `clojure -M:run` fails identically → pre-existing alpha-13 defect, NOT a harness bug.
Resolution: this is exactly the undeclared-transitive class beta-1 fixes (CHANGELOG BOU-196). Verified: the beta-1 `boundary-admin` POM **declares** `boundary-i18n` and `boundary-i18n:1.0.0-beta-1` is in `~/.m2`, so the require resolves after Task 2's bump. The alpha-13 green baseline is therefore skipped by design; the harness is instead validated on beta-1 at Task 5 (green boot). Harness code is verbatim from the reviewed plan.

- [ ] **Step 4: Commit**

```bash
git add ecommerce-api/dev/smoke.clj ecommerce-api/deps.edn
git commit -m "test(ecommerce): add boot-smoke harness (baseline green on alpha-13)"
```

---

## Task 2: Bump Boundary deps to 1.0.0-beta-1

**Files:**
- Modify: `deps.edn` (7 `org.boundary-app/*` version strings)

- [ ] **Step 1: Bump via the repo task**

From repo root:
Run: `bb bump-boundary 1.0.0-beta-1`
Expected: prints `Bumped to 1.0.0-beta-1`.

- [ ] **Step 2: Verify all 7 boundary deps are beta-1**

Run: `grep -n "org.boundary-app" ecommerce-api/deps.edn`
Expected: all 7 lines show `{:mvn/version "1.0.0-beta-1"}` (admin, platform, observability, core, user, cache, ui-style). No `alpha` remaining.

- [ ] **Step 3: Resolve the classpath (confirm beta-1 artifacts fetch)**

From `ecommerce-api/`:
Run: `clojure -Spath -M:smoke > /dev/null && echo RESOLVED`
Expected: `RESOLVED` (beta-1 already in `~/.m2`, so no network needed). If it errors on a missing artifact, that artifact isn't published at beta-1 — surface it.

- [ ] **Step 4: Commit**

```bash
git add ecommerce-api/deps.edn
git commit -m "build(ecommerce): bump Boundary deps 1.0.1-alpha-13 -> 1.0.0-beta-1"
```

---

## Task 3: Replace the removed `wrap-method-override`

**Files:**
- Modify: `src/ecommerce/shared/http/middleware.clj` (add app-owned middleware)
- Modify: `src/ecommerce/system.clj:37` (drop `admin-http` require), `:240` (use local middleware)

- [ ] **Step 1: Audit every `boundary.*` var reference resolves under beta-1**

From `ecommerce-api/`:
Run: `grep -rn "[a-z-]*/[a-z-]" src/ | grep -oE "(admin-http|db-factory|no-op-logging|no-op-errors)/[a-z!-]+" | sort -u`
Expected list: `admin-http/wrap-method-override`, `db-factory/close-db-context!`, `db-factory/db-context`, `no-op-errors/create-error-reporting-component`, `no-op-logging/create-logging-component`.
All except `admin-http/wrap-method-override` are confirmed present in beta-1 (see Key facts). Only the method-override needs replacing.

- [ ] **Step 2: Add an app-owned `wrap-method-override` middleware**

In `src/ecommerce/shared/http/middleware.clj`, add (ported from the platform inline pattern — reads the already-parsed `:form-params`/`:params`, so it must sit inside `wrap-params` in the stack, which it does):

```clojure
(defn wrap-method-override
  "On a POST carrying a `_method` form/query param, rewrite :request-method to
   that verb (PUT/DELETE/PATCH) so HTML forms can drive non-POST routes.
   Replaces boundary.admin.shell.http/wrap-method-override (removed in beta-1)."
  [handler]
  (fn [request]
    (if (= :post (:request-method request))
      (if-let [method (or (get-in request [:form-params "_method"])
                          (get-in request [:params "_method"]))]
        (handler (assoc request :request-method (keyword (clojure.string/lower-case method))))
        (handler request))
      (handler request))))
```

Ensure the ns `:require` includes `[clojure.string]` (or use an existing alias if present).

- [ ] **Step 3: Point system.clj at the local middleware**

In `src/ecommerce/system.clj`:
- Remove the require line `:37` `[boundary.admin.shell.http :as admin-http]` (and its comment `:36`).
- Change line `:240` from `admin-http/wrap-method-override` to `middleware/wrap-method-override`.

- [ ] **Step 4: Confirm no dangling `admin-http` references**

Run: `grep -n "admin-http" src/ecommerce/system.clj`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add ecommerce-api/src/ecommerce/shared/http/middleware.clj ecommerce-api/src/ecommerce/system.clj
git commit -m "fix(ecommerce): replace removed admin-http/wrap-method-override with app-owned middleware"
```

---

## Task 4: Observe the predicted boot failure (JWT_SECRET fail-fast)

No file changes — this step proves the breaking change and drives Task 5.

- [ ] **Step 1: Run smoke WITHOUT `JWT_SECRET` — expect FAIL**

From `ecommerce-api/`:
Run: `unset JWT_SECRET; clojure -M:smoke`
Expected: `[smoke] BOOT FAILED: JWT_SECRET environment variable not configured. Set JWT_SECRET before starting the application.`, exit 1.
(With `JWT_SECRET` unset the guard hits the blank branch — the "not configured" message. A present-but-short secret would instead give the "at least 32 characters" message.)
This confirms beta-1's `validate-jwt-secret!` fires during `:boundary/auth-service` init AND that the Task 3 fix let the system load far enough to reach it. If boot instead fails with `No such var: admin-http/wrap-method-override`, Task 3 is incomplete. If it PASSES, the guard isn't wired as expected — investigate.

---

## Task 5: Supply JWT_SECRET for dev + boot green

**Files:**
- Create: `.env.example`
- Modify: `.gitignore` (ignore `.env`)
- Modify: root `bb.edn` (add a `run-ecommerce` task that injects the dev secret) — optional convenience

- [ ] **Step 1: Document the required secret**

Create `ecommerce-api/.env.example`:

```
# Boundary requires a JWT signing secret of at least 32 characters.
# Copy to .env and set a strong value for real use. Boot fails fast without it.
JWT_SECRET=dev-secret-change-me-min-32-characters
```

- [ ] **Step 2: Ignore local `.env`**

Append to `ecommerce-api/.gitignore` (create if absent):

```
.env
```

- [ ] **Step 3: Run smoke WITH the secret — expect PASS**

From `ecommerce-api/`:
Run: `JWT_SECRET=dev-secret-change-me-min-32-characters clojure -M:smoke`
Expected: `[smoke] BOOT SMOKE PASSED`, exit 0, and the component list includes the `:boundary/user-*` and `:boundary/admin-*` keys.

- [ ] **Step 4: Add a convenience run task (repo root `bb.edn`)**

In root `bb.edn` `:tasks`, add:

```clojure
  run-ecommerce
  {:doc "Run ecommerce-api with a dev JWT_SECRET"
   :task (babashka.process/shell
           {:dir "ecommerce-api"
            :extra-env {"JWT_SECRET" "dev-secret-change-me-min-32-characters"}}
           "clojure" "-M:run")}
```

- [ ] **Step 5: Commit**

```bash
git add ecommerce-api/.env.example ecommerce-api/.gitignore bb.edn
git commit -m "feat(ecommerce): supply dev JWT_SECRET; document env + run task"
```

---

## Task 6: Verify the test suite on beta-1

**Files:** none (verification only)

- [ ] **Step 1: Run the suite**

From `ecommerce-api/`:
Run: `clojure -M:test`
Expected: all tests PASS. (Pure-core suite; no boot, no `JWT_SECRET` needed. authz-403 hardening does not affect it.)

- [ ] **Step 2: If any test fails**

Read the failure. If it is a genuine beta-1 behavior change in pure core logic, use superpowers:systematic-debugging and superpowers:test-driven-development to fix. Do NOT weaken assertions to force green. Commit each fix separately.

- [ ] **Step 3: Commit (only if fixes were made)**

```bash
git add -A ecommerce-api/
git commit -m "fix(ecommerce): adapt tests to Boundary 1.0.0-beta-1"
```

---

## Task 7: Full boot verification (routes respond)

**Files:** none (verification only). Confirms the running server serves public + admin surfaces on beta-1.

- [ ] **Step 1: Start the server**

From repo root:
Run: `bb run-ecommerce` (or from `ecommerce-api/`: `JWT_SECRET=dev-secret-change-me-min-32-characters clojure -M:run`)
Expected: `E-commerce API running on http://localhost:3002`.

- [ ] **Step 2: Hit a public API route**

In another shell:
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3002/api/products`
Expected: `200`.

- [ ] **Step 3: Hit the admin UI (unauthenticated)**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3002/web/admin`
Expected: a redirect/`302`/`401`/`403` (admin requires role) — NOT `500`. Any `500` means a beta-1 wiring regression; investigate.

- [ ] **Step 4: Stop the server** (Ctrl-C) and note results.

No commit (verification only).

---

## Task 8 (optional, gated): trim the deps closure

Only attempt if Tasks 1–7 are green. beta-1 POMs declare inter-Boundary deps, so the hand-enumerated closure can shrink. `cache`/`ui-style` are NOT directly required in `src/` but platform may load them unconditionally — so this is gated on a re-boot.

**Files:** Modify `deps.edn`

- [ ] **Step 1: Trim to directly-referenced modules**

Reduce the `org.boundary-app/*` block to the modules whose namespaces `src/` requires: `boundary-admin`, `boundary-platform`, `boundary-observability`, `boundary-user`. Remove `boundary-core`, `boundary-cache`, `boundary-ui-style` (let POMs resolve them). Keep the explanatory comments.

- [ ] **Step 2: Re-run smoke**

Run: `JWT_SECRET=dev-secret-change-me-min-32-characters clojure -M:smoke`
Expected: `BOOT SMOKE PASSED`. If it fails on a missing namespace/class, RESTORE the removed dep(s) — the trim is not worth a boot failure.

- [ ] **Step 3: Re-run tests + commit (only if green)**

Run: `clojure -M:test` → PASS.
```bash
git add ecommerce-api/deps.edn
git commit -m "build(ecommerce): trim Boundary deps closure (POM-resolved transitives)"
```

If the trim caused any failure and was reverted, skip the commit and note that the full closure is retained by design.

---

## Done criteria

- `ecommerce-api/deps.edn` pins Boundary at `1.0.0-beta-1`.
- `clojure -M:smoke` passes with `JWT_SECRET` set; fails fast without it.
- `clojure -M:test` passes.
- Server boots and serves `/api/products` (200) and `/web/admin` (non-500).
- `.env.example` documents `JWT_SECRET`; `.env` gitignored; `bb run-ecommerce` works.

## Follow-ups (out of scope for this plan)

- Extend root `bb.edn` `bump-boundary` to also touch `blog-app/` + `notification-service/` deps once those adopt Boundary (sub-projects 2 & 3).
- Sub-project 2 (blog-app) and 3 (notification-service) get their own specs + plans.
