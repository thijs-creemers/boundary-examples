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
generated `deps.edn` to `1.0.0-beta-1`** (mechanical — reuse `bb bump-boundary`, whose
regex now matches alpha|beta). All beta-1 artifacts are already in `~/.m2`.

## Key facts (verified against beta-1 source)

- `boundary new blog-app --base-ns blog` → app namespace `blog` (the `--base-ns` flag
  parameterizes it; without it the ns would be `blog_app`). Generates: `deps.edn` (full
  module closure), `src/boundary/config.clj` (Aero `load-config` + `ig-config` with
  conditional `module-wiring` requires driven by `:active`), `src/blog/system.clj` (app
  `ig/init-key`s), `resources/conf/{dev,test}/config.edn`, `.env` (auto-generated
  `JWT_SECRET`), `bb.edn` (incl. `migrate` task), `dev/user.clj`.
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

### Routing — custom handler (ecommerce-proven)

`blog.system` **overrides `:boundary/http-handler`** to build the app's own reitit router
that combines:
- **Public HTMX routes** (custom, unauthenticated): `GET /` (published post list),
  `GET /posts/:slug` (post detail).
- **Boundary normalized routes** under `/web`: user auth (`/web/login`, sessions) and
  admin CRUD (`/web/admin/...`), converted normalized→reitit exactly as ecommerce does
  (`ecommerce/system.clj` route-assembly is the template).

This is the same approach `ecommerce-api` uses (it builds its own router rather than the
platform handler). It sidesteps the `/`-hijack and the missing app-route seam. The rest
of the scaffold (config.clj, db-context, user/admin/ui-style wiring, JWT) is used as
generated.

### Database — SQLite (parity)

Use the SQLite adapter (blog-app is a SQLite example; matches ecommerce's
`:db-context {:adapter :sqlite ...}` + `:boundary/sqlite`). Override the scaffold's
default `:boundary/h2` dev config with SQLite. Port the existing migrations:
`001-create-posts.sql`, `002-create-comments.sql` into the platform migration flow
(`migrations/` + `bb migrate`). Comments table is created but unused (deferred).

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
- `:readonly-fields #{:id :created-at :updated-at}` (slug can be readonly or editable)
- `:fields` — `:published` boolean, `:content`/`:excerpt` textarea widgets.
Wire under `:boundary/admin :entity-discovery {:mode :allowlist :allowlist #{:posts}}`.

### Auth model

- boundary-user provides login + sessions. The author logs in and gets `:admin` role to
  reach `/web/admin`. Seed an admin user (repo already has `bb create-admin` for
  ecommerce; blog gets the same `:cli` alias / `create-admin` flow).
- Public read pages require no auth.

## Verification loop

```bash
# JWT_SECRET must be set (fail-fast at boot). Use bb run task w/ dev secret.
bb migrate            # create posts/comments tables (SQLite)
clojure -M:smoke      # (add smoke harness like ecommerce) — system boots green
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
