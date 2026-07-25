# blog-app → Boundary 1.0.0-beta-1 Migration Design (Sub-project 2)

**Date:** 2026-07-25
**Status:** Design (spec-review pending)
**Parent:** `2026-07-25-boundary-beta-1-upgrade-design.md`

## Goal

Migrate `blog-app` (currently vanilla Clojure: Integrant + Reitit + next.jdbc SQLite +
Aero + Hiccup/HTMX) onto the Boundary framework `1.0.0-beta-1`, adopting
**platform + user + admin + ui-style**. Authoring moves to the admin auto-CRUD UI;
public read pages stay custom HTMX. Comments are **deferred** (table kept, unimplemented).

## Method (locked): scaffold fresh + port

Per the master design, blog-app is re-scaffolded with `boundary new`, then domain code
ported in. `ecommerce-api`'s in-place pattern is the reference for framework wiring.

### CLI precondition (blocking)

The installed `boundary` CLI is `1.0.1-alpha-20` (m2 has no beta-1 `boundary-cli`); its
catalogue emits alpha versions. So after `boundary new` / `boundary add`, **bump the
generated `deps.edn` to `1.0.0-beta-1`**. All beta-1 artifacts are already in `~/.m2`.
NOTE: the root `bb bump-boundary` task currently rewrites **only** `ecommerce-api/deps.edn`
(`bb.edn:13`). Before relying on it, **extend that task's file list to include
`blog-app/deps.edn`** (and later `notification-service/deps.edn`); otherwise bump
blog-app's `deps.edn` by hand.

### Scaffold command + directory naming

There is **no `--base-ns` flag** — the CLI (`boundary-cli/src/boundary/cli/new.clj:123-127`)
accepts only `--force` / `--skip-git`, and derives the namespace from the project name
(`name->ns`: `-`→`_`). So `boundary new blog` → directory `blog/`, namespace `blog`
(matching the existing ported `blog.*` core code). Since the mono-repo keeps the app in
`blog-app/`, scaffold to a temp `blog/` then **relocate the generated files into
`blog-app/`** (keeping the repo's `blog-app/` directory name but the `blog` namespace).
Do NOT run `boundary new blog-app` (that would force namespace `blog_app` and require
rewriting every `ns` form).

## Key facts (verified against beta-1 source)

- `boundary new blog` → app namespace `blog` (see "Scaffold command" above; no
  `--base-ns` flag exists). Generates: `deps.edn` (full module closure),
  `src/boundary/config.clj` (Aero `load-config` + `ig-config` with conditional
  `module-wiring` requires driven by `:active`), `src/blog/system.clj` (app
  `ig/init-key`s), `resources/conf/{dev,test}/config.edn`, `.env` (auto-generated
  `JWT_SECRET`), `bb.edn` (incl. `migrate` task — confirmed present), `dev/user.clj`.
- **Generated `src/boundary/config.clj` `db-spec` handles only `:boundary/h2` and
  `:boundary/postgresql`, else throws** (`config.clj.tmpl:48-67`). For SQLite it must be
  patched to add a `:boundary/sqlite` branch (see DB section). `bb migrate` uses a
  different path (`database/config.clj` handles `:boundary/sqlite`) and is fine; but
  `bb create-admin` → `boundary.config/db-spec` will throw for SQLite until patched.
- `boundary add admin` / `ui-style` patch `deps.edn` + inject config snippets into
  `:active`. `user` + `platform` + `observability` + `core` are always present (core-4).
- **Admin auto-CRUD requires pre-existing tables** — it introspects DB schema + merges
  the entity EDN config; it does NOT create tables or generate migrations. So posts must
  be created by a migration run via `bb migrate` before admin can manage them.
- **Routing constraint (decisive):** the platform `:boundary/http-handler`
  (`libs/platform/.../system/wiring.clj:151`) assembles user/admin/tenant/workflow/search
  routes + platform routes, and **owns `/`** (redirects to `/web/users`). It has **no
  app-route seam** — only `:extra-middleware`. blog-app's primary surface is a public
  site at `/`. Therefore blog-app will **not** use the generated platform http-handler
  as-is.

## Architecture

### Routing — custom http-server key (ecommerce-proven), NOT a defmethod override

Follow ecommerce exactly: it does **not** redefine the platform's
`(defmethod ig/init-key :boundary/http-handler …)`. Instead it registers its **own**
init-key (`:ecommerce/http-server`, `ecommerce/system.clj:188`) that assembles the router
and Jetty independently, and it simply **does not include `:boundary/http-handler` /
`:boundary/http-server` in its Integrant config map**.

For blog: in `src/boundary/config.clj`'s `ig-config`, **remove the `:boundary/http-handler`
and `:boundary/http-server` entries** and add a custom `:blog/http-server` entry
(defmethod in `blog/system.clj`) that builds a reitit router combining:
- **Public HTMX routes** (custom, unauthenticated): `GET /` (published post list),
  `GET /posts/:slug` (post detail).
- **Boundary normalized routes** under `/web`: user auth and admin CRUD, converted
  normalized→reitit. **Copy/adapt** the `normalized->reitit` helper + base-path prefixing
  (`/web`, `/web/admin`) from `ecommerce/system.clj:163-231` into `blog/system.clj`.

Do **not** add a competing `defmethod ig/init-key :boundary/http-handler` — that relies on
load-order shadowing and is fragile. This sidesteps the platform handler's `/`-hijack
(redirect to `/web/users`) and its missing app-route seam. Everything else in the scaffold
(config.clj, db-context, user/admin/ui-style wiring, JWT) is used as generated.

### Database — SQLite (parity)

Use the SQLite adapter (blog-app is a SQLite example). Required post-scaffold edits:
1. **`config.edn` `:active`** must have BOTH (mirroring ecommerce):
   - `:boundary/db-context {:adapter :sqlite :database-path "blog-dev.db"}` — used by the
     running app (platform `:boundary/db-context` init-key).
   - `:boundary/sqlite {:db "blog-dev.db" :pool {…}}` — used by CLI tools
     (`bb migrate`, `bb create-admin`).
   Remove the scaffold's default `:boundary/h2` block.
2. **Patch `src/boundary/config.clj` `db-spec`** to add a `:boundary/sqlite` branch
   (else `bb create-admin` throws — see Key facts):
   ```clojure
   (:boundary/sqlite active)
   {:adapter :sqlite
    :database-path (get-in active [:boundary/sqlite :db])
    :pool          (get-in active [:boundary/sqlite :pool])}
   ```
   and ensure `ig-config`'s db-context mapping covers sqlite too.
Port the existing migrations `001-create-posts.sql`, `002-create-comments.sql` into the
platform migration flow (`migrations/` + `bb migrate`). Comments table is created but
unused (deferred).

### Modules & responsibilities

| Concern | Owner |
|---|---|
| System lifecycle, config, HTTP server, DB pool | boundary-platform (scaffold) |
| Author authentication / sessions | boundary-user (login as `:admin` role) |
| Post + comment **write/management** UI | boundary-admin auto-CRUD (`resources/conf/admin/posts.edn`) |
| Public post **read** views (`/`, `/posts/:slug`) | custom `blog.post` module (below) |
| Styling | boundary-ui-style |

### Custom `blog.post` module (public read side)

Ported, FC/IS preserved:
- **Keep pure:** `blog/post/core/post.clj` (slug, formatting, published filter) and
  `blog/post/core/ui.clj` (Hiccup for home + detail) — reused nearly as-is.
- **Keep:** `blog/shared/ui/layout.clj` (page shell, HTMX, ui-style CSS link).
- **Slim read persistence:** a read-only repository (`find-post-by-slug`,
  `list-published-posts`) built on the platform `db-context` datasource (port the SELECT
  parts of the existing `shell/persistence.clj`; drop the write paths — admin owns writes).
- **Public HTTP handlers:** `blog/post/shell/http.clj` → home + detail, rendering via
  `core/ui` + `layout`.
- **Drop:** the old custom `dashboard`/authoring handlers + `save-post!`/`delete-post!`
  (superseded by admin CRUD), the old Integrant `datasource`/`server` wiring
  (superseded by platform), and the demo auth (superseded by boundary-user).

### Posts admin entity (`resources/conf/admin/posts.edn`)

Entity config mapping the `posts` table (id, author_id, title, slug, content, excerpt,
published, published_at, created_at, updated_at):
- `:list-fields [:title :slug :published :published-at :created-at]`
- `:search-fields [:title]`
- `:readonly-fields #{:id :author-id :created-at :updated-at}` (include `:author-id` —
  see FK note) `:hide-fields #{:author-id}` optional.
- `:fields` — `:published` boolean, `:content`/`:excerpt` textarea widgets.
Wire under `:boundary/admin :entity-discovery {:mode :allowlist :allowlist #{:posts}}`.

**FK auto-detection caveat:** admin introspection (`schema_introspection.clj:718-744`,
`detect-foreign-keys`) treats any `*_id` column as a foreign key and pluralizes —
`author_id` → a `:belongs-to {:entity :authors}` relation to a non-existent `authors`
table. Suppress it: mark `:author-id` readonly/hidden and, if the detected relation still
surfaces, add an explicit relationship override in `posts.edn` to disable it (or point it
at the users entity). Verify in the admin UI during implementation.

### Auth model

- boundary-user provides login + sessions. The author logs in and gets `:admin` role to
  reach `/web/admin`. Seed an admin user (repo already has `bb create-admin` for
  ecommerce; blog gets the same `:cli` alias / `create-admin` flow).
- Public read pages require no auth.

## Verification loop

Create the boot-smoke harness as an **explicit deliverable** (copy ecommerce's
`dev/smoke.clj` + `:smoke` alias, adapted to `blog.system`) — not an afterthought.

```bash
# JWT_SECRET must be set (fail-fast at boot). Use a bb run task w/ dev secret.
bb migrate            # create posts/comments tables (SQLite)
clojure -M:smoke      # system boots green (custom :blog/http-server)
clojure -M:test       # ported pure-core post tests pass
```
Manual: `/` lists published posts; `/posts/:slug` renders; `/web/admin` posts CRUD works
after admin login; styling loads.

## Out of scope

- Comments implementation (table kept, unimplemented — deferred).
- Migrating away from SQLite.
- Non-Boundary dependency changes beyond what beta-1 pulls.

## Risks

- **Scaffold churn vs. custom handler:** overriding `:boundary/http-handler` means we
  don't use part of the generated wiring. Mitigation: it's the proven ecommerce pattern;
  keep everything else generated.
- **SQLite vs H2 default:** scaffold defaults to H2; we swap to SQLite. Verify platform's
  sqlite adapter + migrations run under beta-1 (ecommerce confirms sqlite works).
- **Admin introspection of the posts table:** confirm admin renders CRUD from the ported
  schema (types: TEXT/INTEGER). Adjust entity EDN if inference is off.
