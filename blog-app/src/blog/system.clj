(ns blog.system
  (:require [integrant.core :as ig]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [reitit.ring :as ring]
            [blog.post.shell.persistence :as persistence]
            [blog.post.shell.http :as post-http]
            [blog.shared.http.middleware :as middleware]))

;; System configuration is loaded from resources/conf/{env}/config.edn
;; via Aero. Add module configs with `boundary add <module>`.

(defmethod ig/init-key :boundary/settings [_ config] config)

;; Config-only modules — no lifecycle component, init-key exposes config as an Integrant ref.
;; Added by `boundary add <module>`; ig-config wires them in automatically when present.
(defmethod ig/init-key :boundary/storage        [_ cfg] cfg)
(defmethod ig/init-key :boundary/jobs           [_ cfg] cfg)
(defmethod ig/init-key :boundary/realtime       [_ cfg] cfg)
(defmethod ig/init-key :boundary/reports        [_ cfg] cfg)
(defmethod ig/init-key :boundary/calendar       [_ cfg] cfg)
(defmethod ig/init-key :boundary/ui-style       [_ cfg] cfg)

;; =============================================================================
;; Post Repository
;; =============================================================================

(defmethod ig/init-key :blog/post-repository
  [_ {:keys [db-context]}]
  (persistence/create-post-repository (:datasource db-context)))

;; =============================================================================
;; HTTP Server — public blog HTMX routes + boundary /web (user) + /web/admin
;; =============================================================================

(defn- normalized->reitit
  "Convert normalized boundary route specs to Reitit route vectors,
   prefixing all paths with base-path.

   Promotes :meta :middleware to route-level :middleware.
   Handles per-method :middleware for routes like logout.

   Input:  {:path \"/...\" :meta {:middleware [...]} :methods {:get {:handler fn} ...}}
   Output: [\"/...\" {:middleware [...] :get {:handler fn} ...}]"
  [base-path routes]
  (when (seq routes)
    (mapv (fn [{:keys [path meta methods]}]
            (let [full-path    (str base-path path)
                  route-mw     (:middleware meta)
                  methods-data (reduce-kv
                                (fn [acc method {:keys [handler middleware]}]
                                  (assoc acc method
                                         (cond-> {:handler handler}
                                           (seq middleware) (assoc :middleware middleware))))
                                {}
                                methods)
                  route-data   (cond-> methods-data
                                 (seq route-mw) (assoc :middleware route-mw))]
              [full-path route-data]))
          routes)))

(defmethod ig/init-key :blog/http-server
  [_ {:keys [post-repository user-routes admin-routes config]}]
  (let [http-cfg     (get-in config [:active :boundary/http] {:port 3001 :host "0.0.0.0" :join? false})
        raw-port     (:port http-cfg 3001)
        ;; #env HTTP_PORT arrives as a string via Aero; Jetty's .setPort needs an int.
        port         (if (string? raw-port) (Integer/parseInt raw-port) raw-port)

        ;; Public HTMX blog routes
        public-routes (post-http/routes post-repository)

        ;; Boundary web routes — convert normalized format to Reitit
        user-web-routes  (or (:web user-routes) [])
        admin-web-routes (or (:web admin-routes) [])

        web-routes (vec (concat
                         (normalized->reitit "/web" user-web-routes)
                         ;; Convenience redirect: /web/admin → /web/admin/
                         (when (seq admin-web-routes)
                           [["/web/admin" {:get {:handler (fn [_]
                                                            {:status  302
                                                             :headers {"Location" "/web/admin/"}
                                                             :body    ""})}}]])
                         (normalized->reitit "/web/admin" admin-web-routes)))

        all-routes (vec (concat public-routes web-routes))

        router  (ring/router all-routes {:conflicts nil})
        handler (-> (ring/ring-handler router (ring/create-default-handler))
                    middleware/wrap-method-override   ; HTML form PUT/DELETE override
                    wrap-params                       ; parse form + query params
                    wrap-cookies                      ; parse cookies (session auth)
                    (wrap-resource "public")          ; serve CSS/JS/assets from resources/public/
                    middleware/wrap-exception-handler
                    middleware/wrap-request-logging)]
    (println "Starting blog HTTP server on port" port)
    (println "Blog: http://localhost:" port "/")
    (when (seq user-web-routes)
      (println "User web: http://localhost:" port "/web/"))
    (when (seq admin-web-routes)
      (println "Admin web: http://localhost:" port "/web/admin/"))
    (jetty/run-jetty handler {:port  port
                              :host  (:host http-cfg "0.0.0.0")
                              :join? false})))

(defmethod ig/halt-key! :blog/http-server [_ server]
  (println "Stopping blog HTTP server")
  (.stop server))
