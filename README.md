# Boundary Examples

Example applications built with the [Boundary Framework](https://boundary-app.org). Each example is a self-contained Clojure project demonstrating the **Functional Core / Imperative Shell** (FC/IS) architecture pattern.

## Prerequisites

- Java 11+
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- [Babashka](https://babashka.org) (optional, for root-level tasks)

## Examples

| Example | Complexity | Port | Description |
|---------|-----------|------|-------------|
| [blog-app](./blog-app/) | ⭐⭐ Intermediate | 3001 | Server-rendered blog with HTMX and SQLite |
| [ecommerce-api](./ecommerce-api/) | ⭐⭐⭐ Advanced | 3002 | REST API with cart, orders, and mock Stripe payments |
| [notification-service](./notification-service/) | ⭐⭐ Intermediate | 3003 | Event-driven pub/sub notification microservice |

---

## Blog App

A full-stack blog with HTMX-powered UI and SQLite persistence.

```bash
cd blog-app
clojure -M:run
```

Open [http://localhost:3001](http://localhost:3001). Go to `/dashboard` to create posts.

**Run tests:**
```bash
clojure -M:test
```

---

## E-commerce API

A JSON REST API with products, a session-based cart, order state machine, and mock Stripe payment integration. Includes a `boundary-admin` UI for managing products, orders, and users.

```bash
cd ecommerce-api
clojure -M:run
```

API available at [http://localhost:3002/api](http://localhost:3002/api).

**Create an admin user** (required for the admin UI at `/web/admin/`):
```bash
# From the repo root
bb create-admin --dir ecommerce-api
```

**Simulate a purchase:**
```bash
# Add a product to the cart
curl -X POST http://localhost:3002/api/cart/items \
  -H "Content-Type: application/json" \
  -H "X-Session-ID: demo" \
  -d '{"product-id": "11111111-1111-1111-1111-111111111111", "quantity": 1}'

# Checkout
ORDER=$(curl -s -X POST http://localhost:3002/api/checkout \
  -H "Content-Type: application/json" \
  -H "X-Session-ID: demo" \
  -d '{"email":"demo@example.com","name":"Demo","shipping-address":{"line1":"123 Main St","city":"Amsterdam","postal-code":"1234AB","country":"NL"}}')
ORDER_ID=$(echo $ORDER | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

# Simulate payment success
curl -X POST http://localhost:3002/api/payments/simulate \
  -H "Content-Type: application/json" \
  -d "{\"order-id\": \"$ORDER_ID\", \"success\": true}"
```

**Run tests:**
```bash
clojure -M:test
```

---

## Notification Service

An event-driven microservice that listens for domain events (orders, payments, shipments) over an in-memory pub/sub bus and dispatches mock email/SMS/push notifications with exponential-backoff retry.

```bash
cd notification-service
clojure -M:run
```

**Publish an event:**
```bash
curl -X POST http://localhost:3003/api/events \
  -H "Content-Type: application/json" \
  -d '{
    "type": "order/placed",
    "payload": {
      "order-number": "ORD-001",
      "customer-name": "Jane Smith",
      "customer-email": "jane@example.com",
      "total-cents": 4999,
      "currency": "EUR"
    }
  }'
```

**Check delivered notifications:**
```bash
curl http://localhost:3003/api/notifications
```

**Run tests:**
```bash
clojure -M:test
```

---

## Running All Tests

From the repo root (requires Babashka):

```bash
bb test-all
```

## Architecture

All examples follow the same module structure:

```
<module>/
├── schema.clj        # Malli validation schemas
├── ports.clj         # Protocol (interface) definitions
├── core/<thing>.clj  # Pure business logic — no I/O, no side effects
└── shell/
    ├── persistence.clj  # Database adapter
    ├── service.clj      # Orchestration layer
    └── http.clj         # HTTP handlers
```

The **core** contains only pure functions — easy to test without mocks. The **shell** wires them together with databases, HTTP, and other external services. **Ports** (`defprotocol`) define the boundary between the two, making adapters swappable.

## REPL Development

Each app exposes an nREPL server via `clojure -M:repl-clj`:

| App | nREPL port |
|-----|-----------|
| blog-app | 7889 |
| ecommerce-api | 7890 |
| notification-service | 7891 |

From the REPL:
```clojure
(require '[<app>.system :as sys])
(def system (sys/start!))
;; make changes, then reload namespaces and restart as needed
(sys/stop! system)
```
