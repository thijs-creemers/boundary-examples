(ns blog.shared.http.middleware
  "HTTP middleware for the blog application."
  (:require [clojure.string :as str]
            [ring.util.response :as response]))

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
   interning from arbitrary client input)."
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
;; Exception Handling
;; =============================================================================

(defn wrap-exception-handler
  "Catch unhandled exceptions and return a plain-text 500 response."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println "Unhandled exception:" (.getMessage e))
        (.printStackTrace e)
        (-> (response/response "Internal Server Error")
            (response/status 500)
            (response/content-type "text/plain"))))))

;; =============================================================================
;; Request Logging
;; =============================================================================

(defn wrap-request-logging
  "Log incoming requests to stdout."
  [handler]
  (fn [request]
    (let [start    (System/currentTimeMillis)
          response (handler request)
          duration (- (System/currentTimeMillis) start)]
      (println (format "%s %s - %d (%dms)"
                       (-> request :request-method name str/upper-case)
                       (:uri request)
                       (:status response)
                       duration))
      response)))
