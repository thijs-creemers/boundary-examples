# BOU-16: FC/IS Pure Core Migration Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all `random-uuid` and `rand-int` calls from core (pure) functions by accepting generated values as parameters from the imperative shell.

**Architecture:** Every core function that currently generates a UUID or random value internally becomes a pure function by accepting those values as explicit parameters. The shell layer (service.clj) generates UUIDs and timestamps and passes them in — it already does this for `now`, this plan extends the same pattern to IDs.

**Tech Stack:** Clojure, Kaocha (tests), Integrant, FC/IS pattern

---

## Violations Found

| File | Function | Violation |
|------|----------|-----------|
| `blog-app/src/blog/post/core/post.clj:75` | `create-post` | `(random-uuid)` |
| `ecommerce-api/src/ecommerce/product/core/product.clj:44` | `create-product` | `(random-uuid)` |
| `ecommerce-api/src/ecommerce/cart/core/cart.clj:20` | `create-cart` | `(random-uuid)` |
| `ecommerce-api/src/ecommerce/cart/core/cart.clj:42` | `create-item` | `(random-uuid)` |
| `ecommerce-api/src/ecommerce/order/core/order.clj:26` | `generate-order-number` | `(rand-int 100000)` |
| `ecommerce-api/src/ecommerce/order/core/order.clj:78` | `create-order-item` | `(random-uuid)` |
| `ecommerce-api/src/ecommerce/order/core/order.clj:110` | `create-order` | `(random-uuid)` × 2 + calls `generate-order-number` |
| `notification-service/src/notification/event/core/event.clj:21,26` | `create-event` | `(random-uuid)` × 2 |
| `notification-service/src/notification/notification/core/notification.clj:76` | `create-notification` | `(random-uuid)` |

**Shell usage that is fine (already in imperative shell layer):**
- `*/shell/service.clj` — `(Instant/now)` ✓
- `payment/shell/provider.clj`, `payment/shell/http.clj` — `(random-uuid)` ✓
- `shared/http/middleware.clj` — `(random-uuid)`, `(System/currentTimeMillis)` ✓
- `notification/shared/bus.clj` — `(random-uuid)`, `(Instant/now)` ✓

## Fix Pattern

```
;; BEFORE (violation — core generates side-effecting value)
(defn create-foo [input now]
  {:id (random-uuid)
   ...})

;; AFTER (pure — shell provides the id)
(defn create-foo [id input now]
  {:id id
   ...})

;; Shell (service.clj) — no change to structure, just pass the UUID
(let [result (core/create-foo (random-uuid) input (now))]
  ...)
```

---

## Task 1: blog-app — post/core/post.clj

**Files:**
- Modify: `blog-app/src/blog/post/core/post.clj:75`
- Modify: `blog-app/src/blog/post/shell/service.clj` (one call site: line 68)
- Test: `blog-app/test/blog/post/core/post_test.clj`

- [ ] **Step 1: Update test to expect new signature**

In `blog-app/test/blog/post/core/post_test.clj`, change every call to `post/create-post` to pass an explicit id as first argument:

```clojure
;; All occurrences of (post/create-post sample-input ...) become:
(post/create-post (random-uuid) sample-input author-id test-instant)

;; In create-post-test, add a fixed id to verify it's used:
(deftest create-post-test
  (testing "creates post with required fields"
    (let [author-id (random-uuid)
          post-id   (random-uuid)
          post (post/create-post post-id sample-input author-id test-instant)]
      (is (= post-id (:id post)))   ;; assert exact id, not just uuid?
      ...))
  (testing "defaults to unpublished"
    (let [post (post/create-post (random-uuid) sample-input nil test-instant)]
      ...))
  (testing "sets published-at when published is true"
    (let [input (assoc sample-input :published true)
          post (post/create-post (random-uuid) input nil test-instant)]
      ...))
  (testing "auto-generates excerpt from long content"
    (let [long-content (apply str (repeat 300 "x"))
          input {:title "Test" :content long-content}
          post (post/create-post (random-uuid) input nil test-instant)]
      ...)))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd blog-app && clojure -M:test --focus blog.post.core.post-test
```
Expected: ArityException or similar — `create-post` doesn't accept 4 args yet.

- [ ] **Step 3: Update `create-post` to accept `id` as first parameter**

In `blog-app/src/blog/post/core/post.clj` change:

```clojure
;; OLD
(defn create-post
  [input author-id now]
  (let [title (:title input)
        published? (boolean (:published input))]
    {:id (random-uuid)
     ...}))

;; NEW
(defn create-post
  [id input author-id now]
  (let [title (:title input)
        published? (boolean (:published input))]
    {:id id
     ...}))
```

- [ ] **Step 4: Run tests — verify core tests pass**

```bash
cd blog-app && clojure -M:test --focus blog.post.core.post-test
```
Expected: All tests PASS.

- [ ] **Step 5: Update shell service to generate UUID**

In `blog-app/src/blog/post/shell/service.clj`, find the two call sites for `post-core/create-post` and add `(random-uuid)` as first arg:

```clojure
;; In (create-post [_this author-id input] ...)
(let [post (post-core/create-post (random-uuid) input author-id (now))
      ...])
```

- [ ] **Step 6: Run all blog-app tests**

```bash
cd blog-app && clojure -M:test
```
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
cd blog-app
git add src/blog/post/core/post.clj src/blog/post/shell/service.clj test/blog/post/core/post_test.clj
git commit -m "fix(blog): make create-post pure by accepting id from shell"
```

---

## Task 2: ecommerce-api — product/core/product.clj

**Files:**
- Modify: `ecommerce-api/src/ecommerce/product/core/product.clj:44`
- Modify: `ecommerce-api/src/ecommerce/product/shell/service.clj`
- Test: `ecommerce-api/test/ecommerce/product/core/product_test.clj`

- [ ] **Step 1: Update test to expect new signature**

In `ecommerce-api/test/ecommerce/product/core/product_test.clj`, update `create-product-test`:

```clojure
(deftest create-product-test
  (testing "creates product with all fields"
    (let [product-id (random-uuid)
          input {:name "Test Product"
                 :description "A test product"
                 :price-cents 2999
                 :currency "USD"
                 :stock 100
                 :active true}
          product (product/create-product product-id input test-instant)]
      (is (= product-id (:id product)))
      ...))
  (testing "uses defaults for optional fields"
    (let [input {:name "Minimal Product" :price-cents 999}
          product (product/create-product (random-uuid) input test-instant)]
      ...)))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd ecommerce-api && clojure -M:test --focus ecommerce.product.core.product-test
```
Expected: ArityException on `create-product`.

- [ ] **Step 3: Update `create-product` to accept `id` as first parameter**

In `ecommerce-api/src/ecommerce/product/core/product.clj`:

```clojure
;; OLD
(defn create-product [input now]
  (let [name (:name input)]
    {:id (random-uuid)
     ...}))

;; NEW
(defn create-product [id input now]
  (let [name (:name input)]
    {:id id
     ...}))
```

- [ ] **Step 4: Run tests — verify core tests pass**

```bash
cd ecommerce-api && clojure -M:test --focus ecommerce.product.core.product-test
```
Expected: All PASS.

- [ ] **Step 5: Update shell service**

In `ecommerce-api/src/ecommerce/product/shell/service.clj`:

```clojure
;; In (create-product [_ input] ...)
(let [product (product-core/create-product (random-uuid) input (now))
      ...])
```

- [ ] **Step 6: Run all ecommerce-api tests**

```bash
cd ecommerce-api && clojure -M:test
```
Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
cd ecommerce-api
git add src/ecommerce/product/core/product.clj src/ecommerce/product/shell/service.clj test/ecommerce/product/core/product_test.clj
git commit -m "fix(ecommerce): make create-product pure by accepting id from shell"
```

---

## Task 3: ecommerce-api — cart/core/cart.clj

**Files:**
- Modify: `ecommerce-api/src/ecommerce/cart/core/cart.clj:20,42,54`
- Modify: `ecommerce-api/src/ecommerce/cart/shell/service.clj`
- Test: `ecommerce-api/test/ecommerce/cart/core/cart_test.clj`

Note: `create-item` is called inside `add-item` — so `add-item` also needs an `item-id` parameter.

- [ ] **Step 1: Update test to expect new signatures**

In `ecommerce-api/test/ecommerce/cart/core/cart_test.clj`, update all calls to `cart/create-cart` and `cart/add-item`.

**Exact call sites to update** (verified by grep):

`cart/create-cart` — 8 call sites: lines 16, 27, 50, 62, 73, 88, 101, 139
`cart/add-item` — 14 call sites: lines 30, 38, 39, 45, 46, 51, 63, 74, 75, 89, 90, 102, 103, 140

Pattern:
```clojure
;; create-cart: add id as first arg
(cart/create-cart (random-uuid) "session-123" test-instant)

;; add-item: add item-id as last arg (nil is safe on the update path)
(cart/add-item empty-cart product-id-1 2 test-instant (random-uuid))
```

Update `create-cart-test` to assert the id is preserved:
```clojure
(deftest create-cart-test
  (testing "creates empty cart"
    (let [cart-id (random-uuid)
          cart (cart/create-cart cart-id "session-123" test-instant)]
      (is (= cart-id (:id cart)))
      (is (= "session-123" (:session-id cart)))
      (is (empty? (:items cart)))
      (is (= test-instant (:created-at cart))))))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd ecommerce-api && clojure -M:test --focus ecommerce.cart.core.cart-test
```
Expected: ArityException.

- [ ] **Step 3: Update `create-cart`, `create-item`, and `add-item`**

In `ecommerce-api/src/ecommerce/cart/core/cart.clj`:

```clojure
;; create-cart: id as first param
(defn create-cart
  "Create a new empty cart.
   Args:
     id         - Cart UUID (generated by shell)
     session-id - Session identifier
     now        - Current timestamp"
  [id session-id now]
  {:id id
   :session-id session-id
   :items []
   :created-at now
   :updated-at now})

;; create-item: id as first param
(defn create-item
  "Create a new cart item.
   Args:
     id         - Item UUID (generated by shell)
     cart-id    - Cart UUID
     product-id - Product UUID
     quantity   - Quantity to add
     now        - Current timestamp"
  [id cart-id product-id quantity now]
  {:id id
   :cart-id cart-id
   :product-id product-id
   :quantity quantity
   :created-at now
   :updated-at now})

;; add-item: item-id as last param — only consumed on the insert path
(defn add-item
  "Add or update item in cart.
   If product already in cart, increases quantity.
   item-id is required when adding a new item; nil is safe on the update path.
   Returns: Updated cart"
  [cart product-id quantity now item-id]
  (let [existing (find-item cart product-id)]
    (if existing
      ;; Update existing item — item-id not used
      (let [updated-items (mapv (fn [item]
                                  (if (= product-id (:product-id item))
                                    (-> item
                                        (update :quantity + quantity)
                                        (assoc :updated-at now))
                                    item))
                                (:items cart))]
        (-> cart
            (assoc :items updated-items)
            (assoc :updated-at now)))
      ;; Add new item — use provided item-id
      (let [new-item (create-item item-id (:id cart) product-id quantity now)]
        (-> cart
            (update :items conj new-item)
            (assoc :updated-at now))))))
```

- [ ] **Step 4: Run tests — verify core tests pass**

```bash
cd ecommerce-api && clojure -M:test --focus ecommerce.cart.core.cart-test
```
Expected: All PASS.

- [ ] **Step 5: Update shell service**

In `ecommerce-api/src/ecommerce/cart/shell/service.clj`:

```clojure
;; In get-or-create-cart — pass UUID to create-cart
(defn- get-or-create-cart [cart-repo session-id]
  (if-let [cart (ports/find-by-session cart-repo session-id)]
    cart
    (let [new-cart (cart-core/create-cart (random-uuid) session-id (now))]
      (ports/save-cart! cart-repo new-cart)
      new-cart)))

;; In (add-item [_ session-id product-id quantity] ...)
;; Only generate item-id when we're actually inserting a new item.
;; find-item tells us which path we're on before calling the core.
(let [existing    (cart-core/find-item cart product-id)
      item-id     (when-not existing (random-uuid))
      updated-cart (cart-core/add-item cart product-id quantity (now) item-id)
      item        (cart-core/find-item updated-cart product-id)]
  ...)
```

- [ ] **Step 6: Run all ecommerce-api tests**

```bash
cd ecommerce-api && clojure -M:test
```
Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
cd ecommerce-api
git add src/ecommerce/cart/core/cart.clj src/ecommerce/cart/shell/service.clj test/ecommerce/cart/core/cart_test.clj
git commit -m "fix(ecommerce): make create-cart and create-item pure by accepting ids from shell"
```

---

## Task 4: ecommerce-api — order/core/order.clj

**Files:**
- Modify: `ecommerce-api/src/ecommerce/order/core/order.clj:26,78,110`
- Modify: `ecommerce-api/src/ecommerce/order/shell/service.clj`
- Test: `ecommerce-api/test/ecommerce/order/core/order_test.clj`

Note: Three functions need fixing. `create-order` internally calls `create-order-item` for each cart item, so it needs a vector of item-ids.

- [ ] **Step 1: Update tests to expect new signatures**

In `ecommerce-api/test/ecommerce/order/core/order_test.clj`:

```clojure
;; generate-order-number: add suffix as second param
(deftest generate-order-number-test
  (testing "generates valid order number format"
    (let [order-num (order/generate-order-number test-instant 42)]
      (is (clojure.string/starts-with? order-num "ORD-"))
      (is (re-matches #"ORD-\d{8}-\d{5}" order-num)))))

;; create-order-item: add id as first param
(deftest create-order-item-test
  (let [order-id (random-uuid)
        item-id  (random-uuid)
        cart-item {:product-id product-id :quantity 2}
        product {:name "Test Product" :price-cents 1000}
        item (order/create-order-item item-id order-id cart-item product test-instant)]
    (testing "creates item with correct fields"
      (is (= item-id (:id item)))
      ...)))

;; create-order: add order-id, item-ids vector, order-number as extra params
(deftest create-order-test
  (let [cart-items [{:product-id product-id :quantity 2}]
        products {product-id {:id product-id :name "Test" :price-cents 1000}}
        customer {:email "test@example.com"
                  :name "Test User"
                  :shipping-address {:line1 "123 Main St"
                                     :city "Amsterdam"
                                     :postal-code "1234AB"
                                     :country "NL"}}
        order-id   (random-uuid)
        item-ids   [(random-uuid)]
        order-num  "ORD-20260117-00042"
        order (order/create-order cart-items products customer test-instant
                                  order-id item-ids order-num)]
    (testing "creates order with correct fields"
      (is (= order-id (:id order)))
      (is (= order-num (:order-number order)))
      ...)))
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd ecommerce-api && clojure -M:test --focus ecommerce.order.core.order-test
```
Expected: ArityException on multiple functions.

- [ ] **Step 3: Update all three order core functions**

In `ecommerce-api/src/ecommerce/order/core/order.clj`:

```clojure
;; generate-order-number: accept suffix instead of generating it
(defn generate-order-number
  "Generate unique order number.
   Format: ORD-YYYYMMDD-XXXXX
   suffix: integer 0-99999, provided by shell"
  [now suffix]
  (let [date-str (-> now
                     .toString
                     (subs 0 10)
                     (clojure.string/replace "-" ""))]
    (str "ORD-" date-str "-" (format "%05d" suffix))))

;; create-order-item: id as first param
(defn create-order-item
  "Create order item from cart item with product info.
   id: UUID generated by shell"
  [id order-id cart-item product now]
  {:id id
   :order-id order-id
   :product-id (:product-id cart-item)
   :product-name (:name product)
   :product-price-cents (:price-cents product)
   :quantity (:quantity cart-item)
   :total-cents (* (:quantity cart-item) (:price-cents product))
   :created-at now})

;; create-order: accept order-id, item-ids vector, order-number from shell
(defn create-order
  "Create a new order from cart and customer info.
   Args:
     cart-items    - Vector of cart items
     products      - Map of product-id -> product
     customer-info - Map with :email, :name, :shipping-address
     now           - Current timestamp
     order-id      - UUID for the order (generated by shell)
     item-ids      - Vector of UUIDs for order items, one per cart-item
     order-number  - Pre-generated order number string"
  [cart-items products customer-info now order-id item-ids order-number]
  (let [order-items (mapv (fn [item id]
                            (let [product (get products (:product-id item))]
                              (create-order-item id order-id item product now)))
                          cart-items item-ids)
        totals (calculate-totals order-items 0 0.0)]
    (merge
     {:id order-id
      :order-number order-number
      :status :pending
      :customer-email (:email customer-info)
      :customer-name (:name customer-info)
      :shipping-address (:shipping-address customer-info)
      :items order-items
      :payment-intent-id nil
      :payment-status nil
      :currency "EUR"
      :created-at now
      :updated-at now
      :paid-at nil
      :shipped-at nil
      :delivered-at nil
      :cancelled-at nil}
     totals)))
```

- [ ] **Step 4: Run tests — verify core tests pass**

```bash
cd ecommerce-api && clojure -M:test --focus ecommerce.order.core.order-test
```
Expected: All PASS.

- [ ] **Step 5: Update shell service**

In `ecommerce-api/src/ecommerce/order/shell/service.clj`, update the `create-order` call site.

Note: `generate-order-number` stays in `order.core` — after the fix it is a pure formatting function with no side effects (it just formats a date string with a provided integer suffix). This is correct.

```clojure
;; In (create-order [_ session-id customer-info] ...) — generate all IDs in shell
;; Bind ts once so order-number and order timestamps are consistent.
(let [ts         (now)
      order-id   (random-uuid)
      item-ids   (mapv (fn [_] (random-uuid)) (:items cart))
      order-num  (order-core/generate-order-number ts (rand-int 100000))
      order      (order-core/create-order (:items cart) products customer-info ts
                                          order-id item-ids order-num)]
  ...)
```

- [ ] **Step 6: Run all ecommerce-api tests**

```bash
cd ecommerce-api && clojure -M:test
```
Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
cd ecommerce-api
git add src/ecommerce/order/core/order.clj src/ecommerce/order/shell/service.clj test/ecommerce/order/core/order_test.clj
git commit -m "fix(ecommerce): make order core functions pure by accepting ids and order-number from shell"
```

---

## Task 5: notification-service — event/core/event.clj

**Files:**
- Modify: `notification-service/src/notification/event/core/event.clj:21,26`
- Modify: `notification-service/src/notification/event/shell/service.clj`
- Test: `notification-service/test/notification/event_test.clj`

Note: `create-event` has two UUID calls — one for `:id` and one for the fallback `:correlation-id`. The fix is to add `id` as a parameter and require callers to always provide `correlation-id` (removing the internal fallback). The shell generates both.

- [ ] **Step 1: Update test to expect new signature**

In `notification-service/test/notification/event_test.clj`, update calls to `event-core/create-event` to pass an explicit `id` as fourth parameter.

There are three call sites in the test file: lines 15–17, 28, and 29.

**Replace the uniqueness test** (lines 28–30) — after migration the function no longer generates IDs, so the old test only proves `random-uuid` is unique (tautological). Replace it with an identity-preservation test:

```clojure
;; OLD (lines 28-31) — DELETE
(let [event1 (event-core/create-event {:type :order/placed :payload {}} nil (Instant/now))
      event2 (event-core/create-event {:type :order/placed :payload {}} nil (Instant/now))]
  (is (not= (:id event1) (:id event2))))

;; NEW — assert the supplied id is preserved exactly
(testing "uses supplied id"
  (let [id    (random-uuid)
        event (event-core/create-event {:type :order/placed :payload {}} "corr-1" (Instant/now) id)]
    (is (= id (:id event)))))
```

**Update existing call** (lines 15–17) — pass a real correlation-id (not nil) and an id:
```clojure
;; OLD
(let [now   (Instant/now)
      event (event-core/create-event
             {:type :order/placed ...} nil now)]

;; NEW
(let [now   (Instant/now)
      event (event-core/create-event
             {:type :order/placed ...} "corr-abc" now (random-uuid))]
```

After the migration `correlation-id` is no longer optional in the core — the internal `(or correlation-id (random-uuid))` fallback is removed. All tests must pass a non-nil string or UUID string, not `nil`. The shell guarantees this; tests should mirror the shell's contract.

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd notification-service && clojure -M:test --focus notification.event-test
```
Expected: ArityException.

- [ ] **Step 3: Update `create-event` to accept `id` as fourth parameter**

In `notification-service/src/notification/event/core/event.clj`:

```clojure
(defn create-event
  "Create a new domain event.
   Args:
     event-data     - Map with :type, :aggregate-id, :aggregate-type, :payload
     correlation-id - Correlation ID; pass (random-uuid) from shell if none available
     now            - Current timestamp
     id             - UUID for this event (generated by shell)"
  [event-data correlation-id now id]
  {:id id
   :type (:type event-data)
   :aggregate-id (:aggregate-id event-data)
   :aggregate-type (:aggregate-type event-data)
   :payload (:payload event-data)
   :metadata {:correlation-id correlation-id
              :causation-id (:causation-id event-data)
              :timestamp now
              :source (:source event-data "notification-service")}
   :created-at now})
```

The internal `(or correlation-id (random-uuid))` is removed — callers must always provide a non-nil correlation-id. Update the shell to pass `(or (:correlation-id event-data) (random-uuid))`.

- [ ] **Step 4: Run tests — verify core tests pass**

```bash
cd notification-service && clojure -M:test --focus notification.event-test
```
Expected: All PASS.

- [ ] **Step 5: Update shell service**

In `notification-service/src/notification/event/shell/service.clj`:

```clojure
;; In (publish-event [_ event-data] ...)
(let [corr-id (or (:correlation-id event-data) (random-uuid))
      event   (event-core/create-event event-data corr-id (now) (random-uuid))]
  ...)
```

- [ ] **Step 6: Run all notification-service tests**

```bash
cd notification-service && clojure -M:test
```
Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
cd notification-service
git add src/notification/event/core/event.clj src/notification/event/shell/service.clj test/notification/event_test.clj
git commit -m "fix(notification): make create-event pure by accepting id and correlation-id from shell"
```

---

## Task 6: notification-service — notification/core/notification.clj

**Files:**
- Modify: `notification-service/src/notification/notification/core/notification.clj:76`
- Modify: `notification-service/src/notification/notification/shell/service.clj`
- Test: `notification-service/test/notification/notification_test.clj`

- [ ] **Step 1: Update test to expect new signature**

In `notification-service/test/notification/notification_test.clj`, update all calls to `notif-core/create-notification`:

```clojure
;; OLD: (notif-core/create-notification event channel template recipient now)
;; NEW: (notif-core/create-notification (random-uuid) event channel template recipient now)

;; Example:
(let [notif-id (random-uuid)
      notif (notif-core/create-notification notif-id event :email :order-confirmation "user@example.com" now)]
  (is (= notif-id (:id notif)))
  ...)
```

- [ ] **Step 2: Run tests — verify they fail**

```bash
cd notification-service && clojure -M:test --focus notification.notification-test
```
Expected: ArityException.

- [ ] **Step 3: Update `create-notification` to accept `id` as first parameter**

In `notification-service/src/notification/notification/core/notification.clj`:

```clojure
(defn create-notification
  "Create a new notification.
   Args:
     id        - UUID for this notification (generated by shell)
     event     - Source event
     channel   - Notification channel (:email, :sms, :push)
     template  - Template to use
     recipient - Recipient address/number
     now       - Current timestamp"
  [id event channel template recipient now]
  {:id id
   :event-id (:id event)
   :channel channel
   :recipient recipient
   :template template
   :context (build-context event template)
   :status :pending
   :attempts 0
   :last-attempt-at nil
   :sent-at nil
   :error nil
   :created-at now
   :updated-at now})
```

- [ ] **Step 4: Run tests — verify core tests pass**

```bash
cd notification-service && clojure -M:test --focus notification.notification-test
```
Expected: All PASS.

- [ ] **Step 5: Update shell service**

In `notification-service/src/notification/notification/shell/service.clj`:

```clojure
;; In (create-notification [_ event channel template] ...)
(let [recipient (event-core/extract-recipient event)]
  (if recipient
    (let [notification (notif-core/create-notification
                        (random-uuid) event channel template recipient (now))]
      ...)
    ...))
```

- [ ] **Step 6: Run all notification-service tests**

```bash
cd notification-service && clojure -M:test
```
Expected: All PASS.

- [ ] **Step 7: Commit**

```bash
cd notification-service
git add src/notification/notification/core/notification.clj src/notification/notification/shell/service.clj test/notification/notification_test.clj
git commit -m "fix(notification): make create-notification pure by accepting id from shell"
```

---

## Task 7: Final Verification

- [ ] **Step 1: Run all tests across all three apps**

```bash
cd /path/to/boundary-examples && bb test-all
```
Expected: All suites PASS with zero failures.

- [ ] **Step 2: Verify no `random-uuid`/`rand-int` remain in any core namespace**

```bash
grep -rn "random-uuid\|rand-int" \
  blog-app/src/blog/post/core \
  ecommerce-api/src/ecommerce/cart/core \
  ecommerce-api/src/ecommerce/product/core \
  ecommerce-api/src/ecommerce/order/core \
  notification-service/src/notification/event/core \
  notification-service/src/notification/notification/core
```
Expected: **zero matches**.

- [ ] **Step 3: Run FC/IS compliance check (once tooling available)**

```bash
bb check:fcis
```
Expected: zero violations reported.

- [ ] **Step 4: Create PR for BOU-16**

This plan covers the "downstream migration of example code" acceptance criterion from BOU-16. Create a PR against `main` referencing BOU-16.
