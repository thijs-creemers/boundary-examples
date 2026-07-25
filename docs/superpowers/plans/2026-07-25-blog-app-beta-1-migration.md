# blog-app → Boundary 1.0.0-beta-1 Migration Plan (Sub-project 2)

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-platform `blog-app` onto Boundary `1.0.0-beta-1` — scaffold a fresh Boundary app, relocate it into `blog-app/`, and port the blog domain: posts managed via boundary-admin auto-CRUD, public post pages served by a custom HTMX module, author auth via boundary-user. Comments deferred.

**Architecture:** Scaffold provides system/config/db/user/admin/ui-style/JWT. Because the platform HTTP handler owns `/` and has no app-route seam, blog registers its own `:blog/http-server` init-key (ecommerce pattern) combining public HTMX routes (`/`, `/posts/:slug`) with boundary user/admin normalized routes under `/web`. SQLite for parity. Posts/comments tables via **platform migratus** (`bb migrate`); user/admin tables come from the user module's `:boundary/user-db-schema` at boot. Pure `core/post` + `core/ui` + `layout` are reused; a read-only `:blog/post-repository` (on the platform db-context datasource) + public HTTP handlers remain custom (admin owns writes).

**Tech Stack:** Clojure, tools.deps, Integrant, Aero, Reitit, next.jdbc + SQLite, migratus, Hiccup/HTMX, Kaocha, Babashka; Boundary `platform`/`user`/`admin`/`ui-style` at `1.0.0-beta-1`.

**Spec:** `docs/superpowers/specs/2026-07-25-blog-app-beta-1-migration-design.md`
**Reference app:** `ecommerce-api/` (custom `:*/http-server`, `normalized->reitit`, sqlite config, admin entity EDN, smoke harness) — on beta-1 and green. NOTE: ecommerce applies migrations via its own `run-migrations!` (system.clj:61) and ragtime-style filenames — blog does NOT copy that; blog uses platform `bb migrate` (migratus) instead.

**Working dir:** repo root `/Users/thijscreemers/work/worktrees/boundary-examples/main`; app dir `blog-app/`. Branch: `feat/upgrade-boundary-beta-1` (do NOT switch).

---

## Key facts (verified against source)

- **No `--base-ns` flag** (`boundary-cli/.../new.clj:123-127`). `boundary new blog` → dir `blog/`, ns `blog`. Scaffold to temp `blog/`, then relocate into `blog-app/`.
- **Migrations use migratus** (`platform/.../database/migrations.clj:12,102-103`): format `{id}-{name}.up.sql` + `.down.sql`, `migration-dir "migrations/"`, `:store :database`. The existing `001-create-posts.sql` is ragtime-style and will be **silently ignored**. Generate correctly with `bb migrate create <name>` then paste SQL.
- **User/admin tables** are created by the user module (`:boundary/user-db-schema` init-key) at boot — migratus only needs `posts` (+ deferred `comments`).
- **Generated `db-spec`** (`config.clj.tmpl:48-67`) handles only `:boundary/h2`/`:boundary/postgresql`, else throws → must add a `:boundary/sqlite` branch (else `bb create-admin` throws; `bb migrate` uses a separate path and is fine). The scaffold derives `:boundary/db-context` from `(db-spec config)`, NOT from a `:boundary/db-context` key in `:active` — so only add `:boundary/sqlite` to config, not a `:boundary/db-context` map.
- **No root `create-admin` task** (root `bb.edn` has only `test-all`, `bump-boundary`, `run-ecommerce`). The scaffold's app-level `bb.edn` DOES define `migrate` + `create-admin` (`bb.edn.tmpl:61,81`) → run them from inside `blog-app/`.
- Platform `db-context` value is `{:adapter … :datasource …}` (`factory.clj:141`) → get the datasource via `(:datasource ctx)`.
- Platform `:boundary/http-handler` owns `/` and has no app-route seam → build a custom `:blog/http-server` instead (do NOT redefine the platform defmethod).

---

## Preserve first (ported forward; in git history but keep handy)

Copy to `/tmp/blog-port/` before relocation:
- `blog-app/src/blog/post/core/post.clj` (pure) — reused as-is
- `blog-app/src/blog/post/core/ui.clj` (pure Hiccup) — reused
- `blog-app/src/blog/shared/ui/layout.clj` — reused
- `blog-app/src/blog/post/schema.clj` — reused
- `blog-app/src/blog/post/ports.clj` + `shell/persistence.clj` — slimmed to read-only later
- `blog-app/migrations/001-create-posts.sql`, `002-create-comments.sql` — source SQL, re-formatted for migratus later
- `blog-app/test/blog/post/core/post_test.clj`

---

## Task 1: Scaffold + relocate into `blog-app/`

**Files:** whole `blog-app/` tree.

- [ ] **Step 1: Preserve** the domain files above into `/tmp/blog-port/` (keep relative paths).
- [ ] **Step 2: Scaffold** from repo root: `boundary new blog --skip-git 2>&1 | tail -20` (via `~/.babashka/bbin/bin/boundary` if not on PATH). Produces `./blog/` (ns `blog`).
- [ ] **Step 3: Inspect** — `find blog -type f | sort`; read `blog/src/boundary/config.clj`, `blog/src/blog/system.clj`, `blog/deps.edn`, `blog/resources/conf/dev/config.edn`, `blog/bb.edn`, `blog/dev/user.clj`. Note exact contents (later tasks edit them).
- [ ] **Step 4: Relocate** — remove old vanilla infra from `blog-app/` (`src/blog/system.clj`, `src/blog/main.clj`, old `deps.edn`, old `resources/config/`); copy all scaffold files from `blog/` into `blog-app/`; restore the preserved domain files into their `blog-app/src/...`, `blog-app/test/...` locations (migrations handled in Task 3). Delete temp `blog/`.
- [ ] **Step 5: Sanity** — `ls -R blog-app/src blog-app/resources/conf`; confirm scaffold infra + ported domain coexist.
- [ ] **Step 6: Commit** — stage everything EXCEPT `.env`:
```bash
git add -A blog-app/
git reset -q blog-app/.env 2>/dev/null || true      # never stage the real secret
git status --short blog-app/ | grep -q '\.env$' && { echo "ERROR: .env staged"; exit 1; } || true
git commit -m "feat(blog): scaffold Boundary app, relocate into blog-app/, preserve domain core"
```
Confirm scaffold `.gitignore` lists `.env`; if not, add it.

---

## Task 2: Bump deps to beta-1 + extend bump task

**Files:** `blog-app/deps.edn`, repo-root `bb.edn`.

- [ ] **Step 1:** In repo-root `bb.edn`, change `bump-boundary`'s file vector `["ecommerce-api/deps.edn"]` → `["ecommerce-api/deps.edn" "blog-app/deps.edn"]`.
- [ ] **Step 2:** `bb bump-boundary 1.0.0-beta-1` → `Bumped to 1.0.0-beta-1`.
- [ ] **Step 3: Add the SQLite JDBC driver to base `:deps`** — the scaffold puts only `com.h2database/h2` in base `:deps` and `org.xerial/sqlite-jdbc` only in the `:user-cli`/`:mcp` aliases. A SQLite blog needs the driver on EVERY classpath (the running app's `:boundary/db-context` and, critically, `bb migrate`'s `:migrate` alias which has no extra-deps). Add to `blog-app/deps.edn` base `:deps` (mirroring `ecommerce-api/deps.edn:24`):
```clojure
org.xerial/sqlite-jdbc {:mvn/version "3.53.0.0"}
```
(The `bump-boundary` regex won't touch this line.) Without this, Task 3's `bb migrate` throws `No suitable driver` / ClassNotFoundException before writing/running anything.
- [ ] **Step 4:** `grep -n org.boundary-app blog-app/deps.edn` → all beta-1, no `alpha`; and `grep sqlite-jdbc blog-app/deps.edn` shows it in base `:deps`.
- [ ] **Step 5:** `cd blog-app && clojure -Spath > /dev/null && echo RESOLVED`.
- [ ] **Step 6: Commit** `git add blog-app/deps.edn bb.edn && git commit -m "build(blog): bump Boundary deps to 1.0.0-beta-1; add sqlite-jdbc + blog-app to bump task"`.

---

## Task 3: SQLite config + `db-spec` patch + migratus migrations

**Files:** `blog-app/resources/conf/{dev,test}/config.edn`, `blog-app/src/boundary/config.clj`, `blog-app/migrations/*`.

- [ ] **Step 1: Dev config** — in `resources/conf/dev/config.edn` `:active`: remove the `:boundary/h2` block; add ONLY `:boundary/sqlite` (do NOT add a `:boundary/db-context` map — the scaffold derives it from `db-spec`):
```clojure
:boundary/sqlite {:db "blog-dev.db"
                  :pool {:minimum-idle 1 :maximum-pool-size 3 :connection-timeout-ms 10000}}
```
Set `:boundary/settings :name "blog-dev"`. Keep `:boundary/http` (set `:port 3001`), `:boundary/router`, `:boundary/logging`.
- [ ] **Step 2: Test config** — `resources/conf/test/config.edn`: `:boundary/sqlite {:db ":memory:"}`.
- [ ] **Step 3: Patch `db-spec`** — in `src/boundary/config.clj`, add before `:else` in the `db-spec` `cond`:
```clojure
(:boundary/sqlite active)
{:adapter :sqlite
 :database-path (get-in active [:boundary/sqlite :db])
 :pool          (get-in active [:boundary/sqlite :pool])}
```
- [ ] **Step 4: Generate migratus migration files** — from `blog-app/`:
```bash
bb migrate create create-posts
bb migrate create create-comments
```
This creates correctly-named+located `{id}-create-posts.up.sql`/`.down.sql` (and comments) where migratus expects them. Confirm the path (`bb migrate status` or inspect the created files).
- [ ] **Step 5: Fill the SQL** — paste the CREATE TABLE (+ indexes) from the old `/tmp/blog-port/migrations/001-create-posts.sql` into the generated `*-create-posts.up.sql`. The old file separates statements with plain `;`+newline; migratus separates statements with a `\n--;;\n` line, so **join the multiple statements (table + each index) with `--;;`** in the `.up.sql`. Add the reverse in `.down.sql` (`DROP TABLE posts;`). Repeat for comments (from `002-create-comments.sql`). Delete the old ragtime-style `001-create-posts.sql` / `002-create-comments.sql` files.
- [ ] **Step 6: Run migrations** — from `blog-app/`: `bb migrate` then `bb migrate status`. Verify: `sqlite3 blog-app/blog-dev.db '.tables'` shows `posts` + `comments` (+ migratus `schema_migrations`). If tables are missing, the files weren't in the migratus dir/format — fix and re-run. (No `JWT_SECRET` needed for migration.)
- [ ] **Step 7: Commit** `git add blog-app/resources/conf blog-app/src/boundary/config.clj blog-app/migrations && git commit -m "feat(blog): SQLite config + db-spec patch; posts/comments migratus migrations"`.

---

## Task 4: Port the public post read module (BEFORE http-server wiring)

**Files:** keep `blog/post/core/post.clj`, `core/ui.clj`, `schema.clj`, `shared/ui/layout.clj`; slim `post/ports.clj` + `post/shell/persistence.clj`; rewrite `post/shell/http.clj`; keep `test/.../post_test.clj`.

- [ ] **Step 1: Confirm pure core compiles** — `core/post.clj`, `core/ui.clj`, `schema.clj`, `layout.clj` need no framework coupling; adjust only if they referenced the old system/config.
- [ ] **Step 2: Read-only repository** — trim `post/ports.clj` to `IPostRepository` with `find-post-by-slug` + `list-published-posts` only. Slim `post/shell/persistence.clj` to those two SELECTs; `defrecord SQLitePostRepository [datasource]` taking a plain datasource (mirror ecommerce's `product/shell/persistence.clj` record shape). Drop all write ops.
- [ ] **Step 3: Public handlers call the repository directly (no service layer)** — rewrite `post/shell/http.clj` to expose `home-handler` (list published) + `post-handler` (by slug), each taking the repository, rendering via `core/ui` + `layout`. Return 404 (not 500) for an unknown slug. Remove login/dashboard/create/edit/delete handlers and any `IPostService` usage. Export a `routes` fn returning reitit route vectors given the repository.
- [ ] **Step 4: Tests** — `cd blog-app && clojure -M:test 2>&1 | tail -5`. The ported `post_test.clj` (pure core) passes. Fix only genuine signature drift; keep tests meaningful (superpowers:test-driven-development for any new behavior).
- [ ] **Step 5: Commit** `git add blog-app/src blog-app/test && git commit -m "feat(blog): port public post read module (core reused, read-only repo, HTMX views)"`.

---

## Task 5: Custom `:blog/post-repository` + `:blog/http-server` wiring

**Files:** `blog-app/src/boundary/config.clj` (remove platform HTTP keys, add blog keys), `blog-app/src/blog/system.clj` (init-keys + `normalized->reitit`).

- [ ] **Step 1: Remove platform HTTP keys** — in `src/boundary/config.clj` `ig-config`, delete the `:boundary/http-handler` and `:boundary/http-server` entries from the returned map. Keep `:boundary/user-routes`, `:boundary/admin-routes`, `:boundary/router`, `:boundary/db-context`.
- [ ] **Step 2: Add `:blog/post-repository`** — in `src/boundary/config.clj` `ig-config`, add `:blog/post-repository {:db-context (ig/ref :boundary/db-context)}`. In `blog/system.clj`, `defmethod ig/init-key :blog/post-repository` that extracts `(:datasource db-context)` and returns a `SQLitePostRepository`.
- [ ] **Step 3: Port `normalized->reitit`** — copy the `normalized->reitit` helper (`ecommerce/system.clj:162-186`) and the web/admin route-assembly (`:188-253` region) into `blog/system.clj`, adapting namespaces; base paths `/web` (user), `/web/admin` (admin).
- [ ] **Step 4: Add `:blog/http-server`** — `defmethod ig/init-key :blog/http-server` (+ `halt-key!`) building a reitit ring-handler from: public post routes (Task 4 `routes` fn, given `:blog/post-repository`) + converted `/web` user routes + `/web/admin` admin routes; middleware stack per ecommerce (`wrap-method-override` app-owned copy if admin forms need it, params, cookies, session, ui-style static resources, exception handler); start Jetty on `:boundary/http` port. Model on `ecommerce/system.clj` `:ecommerce/http-server`.
- [ ] **Step 5: Wire it** — in `ig-config`, add `:blog/http-server` with `ig/ref`s to `:blog/post-repository`, `:boundary/user-routes`, `:boundary/admin-routes`, config. Ensure `blog/system.clj` is required so its defmethods load (scaffold requires the app system ns — verify).
- [ ] **Step 6: Compile check** — `cd blog-app && clojure -Spath > /dev/null && echo OK` (all namespaces load).
- [ ] **Step 7: Commit** `git add blog-app/src && git commit -m "feat(blog): :blog/post-repository + :blog/http-server (public HTMX + boundary /web routes)"`.

---

## Task 6: Posts admin auto-CRUD entity

**Files:** `blog-app/resources/conf/admin/posts.edn` (create), `resources/conf/dev/config.edn` (+ test) `:boundary/admin`.

- [ ] **Step 1: Entity config** — create `resources/conf/admin/posts.edn` (wrap under entity key, like `ecommerce/.../products.edn`):
```clojure
{:posts
 {:label           "Posts"
  :list-fields     [:title :slug :published :published-at :created-at]
  :search-fields   [:title]
  :hide-fields     #{:author-id}
  :readonly-fields #{:id :author-id :created-at :updated-at}
  :fields
  {:title        {:type :string   :label "Title" :required true}
   :slug         {:type :string   :label "Slug"}
   :content      {:type :text     :label "Content" :widget :textarea}
   :excerpt      {:type :text     :label "Excerpt" :widget :textarea}
   :published    {:type :boolean  :label "Published" :filterable true}
   :published-at {:type :datetime :label "Published at"}}
  :default-sort     :created-at
  :default-sort-dir :desc}}
```
- [ ] **Step 2: Wire admin** — in `resources/conf/dev/config.edn` `:active` (mirror ecommerce):
```clojure
:boundary/admin
{:enabled?         true
 :base-path        "/web/admin"
 :require-role     :admin
 :entity-discovery {:mode :allowlist :allowlist #{:posts}}
 :entities         #merge [#include "../admin/posts.edn"]
 :pagination       {:default-page-size 20 :max-page-size 200}}
```
- [ ] **Step 3: Commit** `git add blog-app/resources/conf && git commit -m "feat(blog): posts admin auto-CRUD entity config"`.
- [ ] **Step 4:** (verification of introspection/FK happens in Task 8 once booted.)

---

## Task 7: Boot-smoke harness

**Files:** `blog-app/dev/smoke.clj`, `blog-app/deps.edn` (`:smoke` alias).

- [ ] **Step 1: Harness** — inspect `blog/dev/user.clj` for the scaffold's start path. Create `blog-app/dev/smoke.clj` that starts the full system (via the scaffold's `boundary.config`/integrant start, or a `blog.system/start!` if present) and stops it, catching `Throwable`, printing `BOOT SMOKE PASSED` / `BOOT FAILED`, exiting 0/1 (model on `ecommerce-api/dev/smoke.clj`).
- [ ] **Step 2: Alias** — add `:smoke {:extra-paths ["dev"] :main-opts ["-m" "smoke"]}` to `blog-app/deps.edn`.
- [ ] **Step 3: Commit** `git add blog-app/dev/smoke.clj blog-app/deps.edn && git commit -m "test(blog): add boot-smoke harness"`.

---

## Task 8: Full verification + admin seed

**Files:** none (verification); optionally repo-root `bb.edn` `run-blog`.

- [ ] **Step 1: Boot smoke** — `cd blog-app && JWT_SECRET=dev-secret-change-me-min-32-characters clojure -M:smoke`. Expect `BOOT SMOKE PASSED`, components incl. `:boundary/user-*`, `:boundary/admin-*`, `:blog/post-repository`, `:blog/http-server`. Fix wiring (missing init-key/dangling ref) before proceeding.
- [ ] **Step 2: Seed admin** (from inside `blog-app/`; needs the Task 3 db-spec patch):
```bash
cd blog-app && JWT_SECRET=dev-secret-change-me-min-32-characters bb create-admin --env dev --email admin@example.com --name "Author"
```
Expect an admin user created, no `db-spec` throw.
- [ ] **Step 3: Live routes** — start (`cd blog-app && JWT_SECRET=… clojure -M:run` or add a `bb run-blog` task with `:extra-env`):
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3001/                       # 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3001/posts/does-not-exist    # 404
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3001/web/admin               # 302/401/403, not 500
```
Log in at `/web/login`, open `/web/admin`, confirm the **Posts** entity lists + create/edit form renders with **no phantom `authors` relation** (if it appears, add a relationship override in `posts.edn` per spec). Create a published post; confirm `/` lists it and `/posts/:slug` renders; ui-style CSS loads.
- [ ] **Step 4: Tests** — `cd blog-app && clojure -M:test` green.
- [ ] **Step 5: Commit** any convenience task `git add bb.edn && git commit -m "chore(blog): add run-blog convenience task"`.

---

## Done criteria

- `blog-app/` runs on Boundary `1.0.0-beta-1` (deps pinned, resolves).
- `clojure -M:smoke` passes with `JWT_SECRET`; boots `:blog/post-repository` + `:blog/http-server` + boundary user/admin.
- `bb migrate` (migratus) creates `posts`/`comments`; `bb create-admin` works (db-spec patched).
- Public `/` + `/posts/:slug` render (custom HTMX, 200/404); `/web/admin` posts CRUD works after login; ui-style loads.
- `clojure -M:test` green (pure-core post tests ported).
- Comments table exists, unimplemented (deferred, documented).

## Follow-ups (out of scope)

- Comments feature (public + admin).
- Sub-project 3: notification-service (own spec + plan).
- Update root `CLAUDE.md` blog-app row (blog now uses Boundary).
