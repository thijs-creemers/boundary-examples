(ns ecommerce.shared.http.middleware
  "HTTP middleware for the e-commerce API."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [ring.util.response :as response]))

;; =============================================================================
;; JSON Body Parsing
;; =============================================================================

(defn wrap-json-body
  "Parse JSON request body into :json-body key.
   Only activates when Content-Type is application/json."
  [handler]
  (fn [request]
    (let [content-type (get-in request [:headers "content-type"] "")]
      (if (and (str/includes? content-type "application/json")
               (:body request))
        (try
          (let [body-str (slurp (:body request))
                parsed   (when-not (str/blank? body-str)
                           (json/parse-string body-str true))]
            (handler (assoc request :json-body parsed)))
          (catch Exception _
            (-> (response/response
                 (json/generate-string
                  {:error {:code    "invalid_json"
                           :message "Invalid JSON in request body"}}))
                (response/status 400)
                (response/content-type "application/json"))))
        (handler request)))))

;; =============================================================================
;; Session ID (for cart)
;; =============================================================================

(defn wrap-session-id
  "Ensure request has a session ID for cart management.
   Uses X-Session-ID header or generates one."
  [handler]
  (fn [request]
    (let [session-id (or (get-in request [:headers "x-session-id"])
                         (str (random-uuid)))
          response (handler (assoc request :session-id session-id))]
      (-> response
          (assoc-in [:headers "X-Session-ID"] session-id)))))

;; =============================================================================
;; Error Handling
;; =============================================================================

(defn wrap-exception-handler
  "Catch unhandled exceptions and return JSON error response."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println "Unhandled exception:" (.getMessage e))
        (.printStackTrace e)
        (-> (response/response
             (json/generate-string
              {:error {:code "internal_error"
                       :message "An unexpected error occurred"}}))
            (response/status 500)
            (response/content-type "application/json"))))))

;; =============================================================================
;; CORS (for development)
;; =============================================================================

(defn wrap-cors
  "Add CORS headers for development."
  [handler]
  (fn [request]
    (if (= :options (:request-method request))
      (-> (response/response nil)
          (response/status 204)
          (response/header "Access-Control-Allow-Origin" "*")
          (response/header "Access-Control-Allow-Methods" "GET, POST, PUT, PATCH, DELETE, OPTIONS")
          (response/header "Access-Control-Allow-Headers" "Content-Type, X-Session-ID"))
      (-> (handler request)
          (response/header "Access-Control-Allow-Origin" "*")))))

;; =============================================================================
;; Method Override (HTML form PUT/DELETE support)
;; =============================================================================

(def ^:private allowed-override-methods
  "HTTP verbs an HTML form may request via the _method override param."
  #{"put" "delete" "patch"})

(defn wrap-method-override
  "On a POST carrying a `_method` form/query param, rewrite :request-method to
   that verb (PUT/DELETE/PATCH) so HTML forms can drive non-POST routes.
   Only allow-listed verbs are honored (guards against unbounded keyword
   interning from arbitrary client input).
   Replaces boundary.admin.shell.http/wrap-method-override (removed in beta-1)."
  [handler]
  (fn [request]
    (if (= :post (:request-method request))
      (let [method (some-> (or (get-in request [:form-params "_method"])
                               (get-in request [:params "_method"]))
                           str/lower-case)]
        (if (contains? allowed-override-methods method)
          (handler (assoc request :request-method (keyword method)))
          (handler request)))
      (handler request))))

;; =============================================================================
;; Request Logging
;; =============================================================================

(defn wrap-request-logging
  "Log incoming requests."
  [handler]
  (fn [request]
    (let [start (System/currentTimeMillis)
          response (handler request)
          duration (- (System/currentTimeMillis) start)]
      (println (format "%s %s - %d (%dms)"
                       (-> request :request-method name str/upper-case)
                       (:uri request)
                       (:status response)
                       duration))
      response)))
