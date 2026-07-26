# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

A mono-repo of three Clojure example applications demonstrating the [Boundary Framework](https://boundary-app.org) using the **Functional Core / Imperative Shell (FC/IS)** architecture pattern.

All three apps run on Boundary **`1.0.0-beta-1`** (pinned in each app's `deps.edn`).
`blog-app` and `notification-service` were scaffolded with `boundary new` and their
domain code ported in; `ecommerce-api` was upgraded in place. Each requires a
`JWT_SECRET` env var (≥32 chars) at boot — use the `bb run-<app>` tasks (which inject a
dev secret) or export your own; see each app's `.env.example`.

| App | Port | Boundary modules | Description |
|-----|------|------------------|-------------|
| `blog-app/` | 3001 | platform, user, admin, ui-style | HTMX server-rendered blog with SQLite; posts managed via the admin auto-CRUD UI, public pages served by a custom `:blog/http-server` |
| `ecommerce-api/` | 3002 | platform, user, admin | REST API with cart, orders, and mock Stripe payments |
| `notification-service/` | 3003 | platform, user | Event-driven pub/sub with a custom `core.async` bus (kept); SQLite backs the user module only, domain stores stay in-memory. Realtime push is a future enhancement (BOU-233) |

## Commands

All commands are run from within each app's directory unless noted.

### Run tests
```bash
# All apps (from repo root, requires Babashka)
bb test-all

# Single app
cd <app-dir> && clojure -M:test

# With documentation reporter (verbose)
clojure -M:test --reporter documentation

# Watch mode (notification-service)
clojure -M:test --watch
```

### Start an app
```bash
clojure -M -m <app-ns>.main   # e.g. blog.main, ecommerce.main, notification.main
# or
clojure -M:run
```

### Start a REPL (nREPL / CIDER)
```bash
clojure -M:repl-clj
# blog-app → port 7889 | ecommerce-api → port 7890 | notification-service → port 7891
```

Then connect from the REPL:
```clojure
(require '[<app>.system :as sys])
(def system (sys/start!))
```

### Utility tasks (from repo root, requires Babashka)
```bash
bb bump-boundary <version>   # Update Boundary library version across deps.edn files
```

### Admin UI (ecommerce-api only)
```bash
# From repo root — interactive wizard
bb create-admin --dir ecommerce-api

# Non-interactive
bb create-admin --env dev --email admin@example.com --name "Admin"
```

## Architecture: Functional Core / Imperative Shell

Every module follows the same layout:

```
<module>/
├── schema.clj        # Malli schemas for validation
├── ports.clj         # Protocol (interface) definitions
├── core/
│   └── <thing>.clj   # Pure business logic — no I/O, easy to unit test
└── shell/
    ├── persistence.clj  # Database adapter implementing the port
    ├── service.clj      # Orchestration: validation → core → persistence → result
    └── http.clj         # Ring/reitit handlers
```

**Core** functions take data and return data. No DB calls, no HTTP, no side effects — test them directly without mocks.

**Shell** functions coordinate: call a core function to build a value, then persist/send/log it.

**Ports** (`defprotocol`) decouple the shell from its adapters — swap SQLite for Postgres by replacing only `persistence.clj`.

## Key Libraries

| Library | Purpose |
|---------|---------|
| Integrant | System lifecycle (start/stop components) |
| Reitit | HTTP routing |
| Malli | Schema definition and validation |
| next.jdbc + HikariCP | Database (SQLite in examples) |
| Aero | EDN configuration with env interpolation |
| Kaocha | Test runner |
| Ring + Jetty | HTTP server |
| core.async | Pub/sub message bus (notification-service) |
| Hiccup | HTML templates as Clojure data (blog-app) |
| HTMX | Browser interactivity without a JS build step (blog-app) |

## ecommerce-api: Boundary Framework Libraries

`ecommerce-api` uses the actual Boundary framework packages (from Clojars),
pinned at `1.0.0-beta-1`. Since beta-1 the published POMs declare their
inter-Boundary dependencies, so `deps.edn` lists only the four modules whose
namespaces the app requires directly and lets the rest resolve transitively:
- `boundary-admin` — admin UI with auth; transitively brings `boundary-user`, `boundary-core`, `boundary-shared-ui`, `boundary-ui-style`, `boundary-i18n`, `hiccup`, `buddy-hashers/sign`
- `boundary-platform` — transitively brings `boundary-cache`, `next.jdbc`, `honeysql`, `HikariCP`, `reitit`, `muuntaja`, `integrant`, `aero`, `cheshire`
- `boundary-observability` — transitively brings `tools.logging`, `logback`
- `boundary-user` — auth/JWT/MFA/session + user management (also required directly)

> **Boot requirement:** beta-1's `boundary-user` fails fast at startup unless a
> `JWT_SECRET` env var of ≥32 characters is set (read via `System/getenv`, not
> config). Use `bb run-ecommerce` (injects a dev secret) or export it yourself;
> see `ecommerce-api/.env.example`.

## Configuration

Each app reads config from `resources/config/<env>.edn` via Aero. The active profile defaults to `dev`. Tests use `resources/config/test.edn` (SQLite `:memory:` database).

## Test Layout

Tests live in `test/` and mirror the `src/` structure. Test namespaces end in `-test`. Kaocha is configured by `tests.edn` (ecommerce-api) or by `:test` alias main-opts. Only pure core logic is unit tested; shell/I/O code relies on integration tests or is left untested by design.
