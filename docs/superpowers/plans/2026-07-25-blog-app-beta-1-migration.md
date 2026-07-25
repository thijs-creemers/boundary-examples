# blog-app → Boundary 1.0.0-beta-1 Migration Plan (Sub-project 2)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-platform `blog-app` onto Boundary `1.0.0-beta-1` — scaffold a fresh Boundary app, relocate it into `blog-app/`, and port the blog domain: posts managed via boundary-admin auto-CRUD, public post pages served by a custom HTMX module, author auth via boundary-user. Comments deferred.

**Architecture:** Scaffold provides system/config/db/user/admin/ui-style/JWT. Because the platform HTTP handler owns `/` and has no app-route seam, blog registers its own `:blog/http-server` init-key (ecommerce pattern) combining public HTMX routes (`/`, `/posts/:slug`) with boundary user/admin normalized routes under `/web`. SQLite for parity. Pure `core/post` + `core/ui` + `layout` are reused; only read-side persistence + public HTTP handlers remain custom (admin owns writes).

**Tech Stack:** Clojure, tools.deps, Integrant, Aero, Reitit, next.jdbc + SQLite, Hiccup/HTMX, Kaocha, Babashka; Boundary `platform`/`user`/`admin`/`ui-style` at `1.0.0-beta-1`.

**Spec:** `docs/superpowers/specs/2026-07-25-blog-app-beta-1-migration-design.md`
**Reference app:** `ecommerce-api/` (custom `:*/http-server`, sqlite config, admin entity EDN, `normalized->reitit`, smoke harness) — already on beta-1 and green.

**Working dir:** repo root `/Users/thijscreemers/work/worktrees/boundary-examples/main`; app dir `blog-app/`. Branch: `feat/upgrade-boundary-beta-1` (do NOT switch).

---

## Preserve first (do NOT lose these during scaffold relocation)

Before overwriting, copy these existing files somewhere safe (git history has them, but keep them handy) — they are ported forward:
- `blog-app/src/blog/post/core/post.clj` (pure) — reused as-is
- `blog-app/src/blog/post/core/ui.clj` (pure Hiccup) — reused, minor tweaks
- `blog-app/src/blog/shared/ui/layout.clj` — reused
- `blog-app/src/blog/post/schema.clj` — reused
- `blog-app/src/blog/post/ports.clj` — trim to read-only later
- `blog-app/src/blog/post/shell/persistence.clj` — slim to read-only later
- `blog-app/migrations/001-create-posts.sql`, `002-create-comments.sql`
- `blog-app/test/blog/post/core/post_test.clj`

---

## Task 1: Scaffold a fresh Boundary app and relocate into `blog-app/`

**Files:** whole `blog-app/` tree (replace infra, keep ported domain).

- [ ] **Step 1: Preserve the domain files** — copy the eight files above to `/tmp/blog-port/` (preserving relative paths).

- [ ] **Step 2: Scaffold** into a temp dir. From repo root:
```bash
boundary new blog --skip-git 2>&1 | tail -20   # creates ./blog/, ns "blog"
```
If `boundary` isn't on PATH, run via the installed release wrapper (`~/.babashka/bbin/bin/boundary`). Expected: a `blog/` dir with `deps.edn`, `src/boundary/config.clj`, `src/blog/system.clj`, `resources/conf/{dev,test}/config.edn`, `.env`, `bb.edn`, `dev/user.clj`.

- [ ] **Step 3: Inspect the generated tree** — `find blog -type f -not -path '*/.git/*' | sort`. Read `blog/src/boundary/config.clj`, `blog/src/blog/system.clj`, `blog/deps.edn`, `blog/resources/conf/dev/config.edn`, `blog/bb.edn`. Note exact contents — later steps edit them.

- [ ] **Step 4: Relocate into `blog-app/`.** Replace `blog-app/`'s vanilla infra with the scaffold, then restore ported domain:
  - Remove old vanilla files: `blog-app/src/blog/system.clj`, `blog-app/src/blog/main.clj`, old `blog-app/deps.edn`, `blog-app/resources/config/` (old aero), keeping `blog-app/.git`-tracked domain.
  - Copy scaffold files from `blog/` into `blog-app/` (deps.edn, bb.edn, dev/user.clj, src/boundary/config.clj, src/blog/system.clj, resources/conf/**, .env, .env.example, .gitignore, tests.edn, CLAUDE.md/AGENTS.md if desired).
  - Restore the eight preserved domain files into their `blog-app/src/...` + `blog-app/migrations/` + `blog-app/test/...` locations.
  - Delete the temp `blog/` dir.

- [ ] **Step 5: Sanity** — `ls -R blog-app/src blog-app/resources/conf blog-app/migrations`. Confirm both the scaffold infra AND the ported domain coexist.

- [ ] **Step 6: Commit**
```bash
git add -A blog-app/ && git commit -m "feat(blog): scaffold Boundary app, relocate into blog-app/, preserve domain core"
```
(Do NOT commit `.env` — confirm scaffold `.gitignore` ignores it; if `blog-app/.env` is staged, unstage it.)

---

## Task 2: Bump deps to beta-1 + extend bump task

**Files:** `blog-app/deps.edn`, repo-root `bb.edn`.

- [ ] **Step 1: Extend `bb bump-boundary`** — in repo-root `bb.edn`, change the file vector from `["ecommerce-api/deps.edn"]` to `["ecommerce-api/deps.edn" "blog-app/deps.edn"]`.

- [ ] **Step 2: Bump** — `bb bump-boundary 1.0.0-beta-1`. Expected: `Bumped to 1.0.0-beta-1`.

- [ ] **Step 3: Verify** — `grep -n org.boundary-app blog-app/deps.edn` shows all boundary libs at `1.0.0-beta-1`, no `alpha`.

- [ ] **Step 4: Resolve** — `cd blog-app && clojure -Spath > /dev/null && echo RESOLVED`.

- [ ] **Step 5: Commit**
```bash
git add blog-app/deps.edn bb.edn && git commit -m "build(blog): bump Boundary deps to 1.0.0-beta-1; add blog-app to bump task"
```

---

## Task 3: SQLite config + `db-spec` patch + migrations

**Files:** `blog-app/resources/conf/dev/config.edn`, `.../test/config.edn`, `blog-app/src/boundary/config.clj`, `blog-app/migrations/`.

- [ ] **Step 1: Dev config** — in `resources/conf/dev/config.edn` `:active`, remove the `:boundary/h2` block and add (mirror `ecommerce-api/resources/conf/dev/config.edn`):
```clojure
:boundary/db-context {:adapter :sqlite :database-path "blog-dev.db"}
:boundary/sqlite     {:db "blog-dev.db"
                      :pool {:minimum-idle 1 :maximum-pool-size 3 :connection-timeout-ms 10000}}
```
Keep `:boundary/settings`, `:boundary/http`, `:boundary/router`, `:boundary/logging`. Set `:boundary/settings :name "blog-dev"`.

- [ ] **Step 2: Test config** — in `resources/conf/test/config.edn`, use in-memory sqlite: `:boundary/db-context {:adapter :sqlite :database-path ":memory:"}` + matching `:boundary/sqlite {:db ":memory:"}`.

- [ ] **Step 3: Patch `db-spec`** — in `src/boundary/config.clj`, add a `:boundary/sqlite` branch to the `db-spec` `cond` (before `:else`):
```clojure
(:boundary/sqlite active)
{:adapter :sqlite
 :database-path (get-in active [:boundary/sqlite :db])
 :pool          (get-in active [:boundary/sqlite :pool])}
```
Also confirm `ig-config`'s `:boundary/db-context` uses `(db-spec config)` (or add sqlite handling wherever it maps the db). Cross-check against `ecommerce-api` for the exact db-context value shape.

- [ ] **Step 4: Migrations** — ensure `blog-app/migrations/001-create-posts.sql` + `002-create-comments.sql` are present and named as the platform migration runner expects (check `bb migrate` / `ecommerce-api` migration naming). Adjust filenames if the runner needs a specific pattern.

- [ ] **Step 5: Run migrations**
```bash
cd blog-app && JWT_SECRET=dev-secret-change-me-min-32-characters bb migrate 2>&1 | tail -20
```
Expected: posts + comments tables created in `blog-dev.db`, no error. Verify: `sqlite3 blog-app/blog-dev.db '.tables'` shows `posts` and `comments` (plus boundary user/admin tables if the user module migrates on boot — note where those come from).

- [ ] **Step 6: Commit**
```bash
git add blog-app/resources/conf blog-app/src/boundary/config.clj blog-app/migrations
git commit -m "feat(blog): SQLite config + db-spec patch; port posts/comments migrations"
```

---

## Task 4: Custom `:blog/http-server` routing (public + boundary /web)

**Files:** `blog-app/src/boundary/config.clj` (remove platform HTTP keys), `blog-app/src/blog/system.clj` (add `:blog/http-server` + `normalized->reitit`).

- [ ] **Step 1: Remove platform HTTP keys** — in `src/boundary/config.clj` `ig-config`, delete the `:boundary/http-handler` and `:boundary/http-server` entries from the returned Integrant map. Leave `:boundary/user-routes`, `:boundary/admin-routes`, `:boundary/router`, `:boundary/db-context`, etc. intact.

- [ ] **Step 2: Port `normalized->reitit`** — copy the `normalized->reitit` helper and the route-assembly logic from `ecommerce-api/src/ecommerce/system.clj:163-231` into `blog-app/src/blog/system.clj`, adapting namespaces. It converts boundary normalized web routes → reitit vectors under base paths `/web` (user) and `/web/admin` (admin).

- [ ] **Step 3: Define `:blog/http-server`** — add a `defmethod ig/init-key :blog/http-server` in `blog/system.clj` that:
  - builds a reitit ring-handler from: public routes (`GET /` home, `GET /posts/:slug` detail — from the public post module, Task 5) + converted `/web` user routes + `/web/admin` admin routes;
  - applies the middleware stack (session, params, cookies, ui-style static resources, method-override if admin forms need it — reuse the app-owned `wrap-method-override` pattern from ecommerce, exception handler);
  - starts Jetty on `:boundary/http` port; add `ig/halt-key!` to stop it.
  Model the whole thing on `ecommerce-api/src/ecommerce/system.clj` `:ecommerce/http-server` (`:188`+).

- [ ] **Step 4: Wire it** — in `src/boundary/config.clj` `ig-config`, add the `:blog/http-server` entry with `ig/ref`s to `:boundary/user-routes`, `:boundary/admin-routes`, the public post handlers, `:boundary/db-context`, config. Ensure `blog/system.clj` is required so its defmethods load (the scaffold already requires the app system ns — verify).

- [ ] **Step 5: Commit** (compiles; full boot verified in Task 8)
```bash
git add blog-app/src && git commit -m "feat(blog): custom :blog/http-server combining public HTMX + boundary /web routes"
```

---

## Task 5: Port the public post read module

**Files:** `blog-app/src/blog/post/core/post.clj` (keep), `.../core/ui.clj` (keep/tweak), `.../shared/ui/layout.clj` (keep), `.../post/ports.clj` (trim), `.../post/shell/persistence.clj` (slim read-only), `.../post/shell/http.clj` (public handlers), `test/blog/post/core/post_test.clj` (keep passing).

- [ ] **Step 1: Confirm pure core still compiles** — `core/post.clj`, `core/ui.clj`, `schema.clj`, `layout.clj` need no framework coupling. Adjust only if they referenced the old system/config.

- [ ] **Step 2: Slim the read repository** — reduce `post/ports.clj` to read ops (`find-post-by-slug`, `list-published-posts`) and `post/shell/persistence.clj` to those SELECTs, built on the platform db-context datasource. Get the datasource from the injected `:boundary/db-context` via `(:datasource ctx)` (confirmed shape `{:adapter … :datasource …}` in platform `factory.clj`). Drop `save-post!`/`delete-post!`/`update`.

- [ ] **Step 3: Public HTTP handlers** — rewrite `post/shell/http.clj` to expose only `home-handler` (list published) and `post-handler` (by slug), rendering via `core/ui` + `layout`. Remove login/dashboard/create/edit/delete handlers. Export a routes fn returning reitit route vectors (consumed by `:blog/http-server`).

- [ ] **Step 4: Run the pure-core tests**
```bash
cd blog-app && clojure -M:test 2>&1 | tail -5
```
Expected: the ported `post_test.clj` passes. Fix ports/persistence signature drift if the tests touch them (keep tests meaningful; use superpowers:test-driven-development for any new behavior).

- [ ] **Step 5: Commit**
```bash
git add blog-app/src blog-app/test && git commit -m "feat(blog): port public post read module (core reused, read-only persistence, HTMX views)"
```

---

## Task 6: Posts admin auto-CRUD entity

**Files:** `blog-app/resources/conf/admin/posts.edn` (create), `blog-app/resources/conf/dev/config.edn` (+ test) `:boundary/admin`.

- [ ] **Step 1: Entity config** — create `resources/conf/admin/posts.edn` (wrap under the entity key, like `ecommerce-api/resources/conf/admin/products.edn`):
```clojure
{:posts
 {:label           "Posts"
  :list-fields     [:title :slug :published :published-at :created-at]
  :search-fields   [:title]
  :hide-fields     #{:author-id}
  :readonly-fields #{:id :author-id :created-at :updated-at}
  :fields
  {:title     {:type :string  :label "Title" :required true}
   :slug      {:type :string  :label "Slug"}
   :content   {:type :text    :label "Content" :widget :textarea}
   :excerpt   {:type :text    :label "Excerpt" :widget :textarea}
   :published {:type :boolean :label "Published" :filterable true}}
  :default-sort     :created-at
  :default-sort-dir :desc}}
```

- [ ] **Step 2: Wire admin** — in `resources/conf/dev/config.edn` `:active`, add/adjust `:boundary/admin` (mirror ecommerce):
```clojure
:boundary/admin
{:enabled?         true
 :base-path        "/web/admin"
 :require-role     :admin
 :entity-discovery {:mode :allowlist :allowlist #{:posts}}
 :entities         #merge [#include "../admin/posts.edn"]
 :pagination       {:default-page-size 20 :max-page-size 200}}
```
Add the same (minimal) to `test/config.edn` if tests need admin.

- [ ] **Step 3: Verify introspection** — boot (Task 8) then load `/web/admin`; confirm the Posts entity lists + the create/edit form renders without a phantom `authors` relation. If the `author_id` FK relation still appears, add a relationship override in `posts.edn` to disable/redirect it (see spec FK caveat).

- [ ] **Step 4: Commit**
```bash
git add blog-app/resources/conf && git commit -m "feat(blog): posts admin auto-CRUD entity config"
```

---

## Task 7: Boot-smoke harness

**Files:** `blog-app/dev/smoke.clj` (create), `blog-app/deps.edn` (`:smoke` alias).

- [ ] **Step 1: Copy ecommerce's harness** — create `blog-app/dev/smoke.clj` identical to `ecommerce-api/dev/smoke.clj` but requiring `blog.system` (or whichever ns exposes `start!`/`stop!`). If the scaffold uses `integrant.repl`/`boundary.config` instead of a `system/start!`, write the harness to call the scaffold's start path (inspect `dev/user.clj`).

- [ ] **Step 2: Add `:smoke` alias** to `blog-app/deps.edn` (`{:extra-paths ["dev"] :main-opts ["-m" "smoke"]}`).

- [ ] **Step 3: Commit**
```bash
git add blog-app/dev/smoke.clj blog-app/deps.edn && git commit -m "test(blog): add boot-smoke harness"
```

---

## Task 8: Full verification + admin seed

**Files:** none (verification); may add repo-root `bb.edn` `run-blog` + `create-admin` convenience.

- [ ] **Step 1: Boot smoke**
```bash
cd blog-app && JWT_SECRET=dev-secret-change-me-min-32-characters clojure -M:smoke
```
Expected: `BOOT SMOKE PASSED`, exit 0, components include `:boundary/user-*`, `:boundary/admin-*`, `:blog/http-server`. Fix wiring issues (missing init-key, dangling ref) before proceeding.

- [ ] **Step 2: Seed an admin user** — run the create-admin flow (confirm the `db-spec` sqlite patch from Task 3 makes it work):
```bash
JWT_SECRET=dev-secret-change-me-min-32-characters bb create-admin --dir blog-app --env dev --email admin@example.com --name "Author"
```
(If the root `bb create-admin` task is ecommerce-specific, add a blog equivalent or run the app's `:user-cli` alias directly.) Expected: admin user created, no `db-spec` throw.

- [ ] **Step 3: Live routes** — start server (`JWT_SECRET=… clojure -M:run` or a `bb run-blog` task with `:extra-env`), then:
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3001/            # home → 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3001/posts/does-not-exist  # → 404 (not 500)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3001/web/admin   # → 302/401/403 (not 500)
```
Insert a published post via `/web/admin` (after login) or a SQL insert, then confirm `/` lists it and `/posts/:slug` renders. Confirm ui-style CSS loads.

- [ ] **Step 4: Tests** — `clojure -M:test` green.

- [ ] **Step 5: Commit** any convenience tasks
```bash
git add bb.edn && git commit -m "chore(blog): add run-blog/create-admin convenience tasks"
```

---

## Done criteria

- `blog-app/` runs on Boundary `1.0.0-beta-1` (deps pinned, resolves).
- `clojure -M:smoke` passes with `JWT_SECRET`; boots `:blog/http-server` + boundary user/admin.
- `bb migrate` creates posts/comments; `bb create-admin` works (sqlite db-spec patched).
- Public `/` + `/posts/:slug` render (custom HTMX); `/web/admin` posts CRUD works after login; ui-style loads.
- `clojure -M:test` green (pure-core post tests ported).
- Comments table exists, unimplemented (deferred, documented).

## Follow-ups (out of scope)

- Comments feature (public + admin).
- Sub-project 3: notification-service (own spec + plan).
- Update root `CLAUDE.md` blog-app row if its description drifts (blog now uses Boundary).
