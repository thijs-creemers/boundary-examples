(ns notification.system
  (:require [integrant.core :as ig]
            [notification.shared.bus :as bus]
            [notification.event.shell.store :as event-store]
            [notification.event.shell.service :as event-service]
            [notification.notification.shell.store :as notif-store]
            [notification.notification.shell.sender :as notif-sender]
            [notification.notification.shell.service :as notif-service]
            [notification.handler.order :as order-handler]
            [notification.handler.payment :as payment-handler]
            [notification.handler.shipment :as shipment-handler]
            [notification.event.shell.http :as event-http]
            [notification.notification.shell.http :as notif-http]
            [reitit.ring :as ring]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [cheshire.core :as json]
            [ring.util.response :as response]))

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
;; Message Bus
;; =============================================================================

(defmethod ig/init-key :notification/bus [_ config]
  (println "Starting message bus...")
  (bus/create-bus config))

(defmethod ig/halt-key! :notification/bus [_ b]
  (println "Stopping message bus...")
  (bus/stop! b))

;; =============================================================================
;; Event Store
;; =============================================================================

(defmethod ig/init-key :notification/event-store [_ _]
  (println "Starting event store...")
  (event-store/create-store))

(defmethod ig/halt-key! :notification/event-store [_ _]
  (println "Stopping event store..."))

;; =============================================================================
;; Notification Store
;; =============================================================================

(defmethod ig/init-key :notification/notification-store [_ _]
  (println "Starting notification store...")
  (notif-store/create-store))

(defmethod ig/halt-key! :notification/notification-store [_ _]
  (println "Stopping notification store..."))

;; =============================================================================
;; Notification Sender
;; =============================================================================

(defmethod ig/init-key :notification/sender [_ {:keys [config]}]
  (println "Starting notification sender...")
  (notif-sender/create-sender config))

(defmethod ig/halt-key! :notification/sender [_ _]
  (println "Stopping notification sender..."))

;; =============================================================================
;; Event Service
;; =============================================================================

(defmethod ig/init-key :notification/event-service [_ {:keys [store bus]}]
  (println "Starting event service...")
  (event-service/create-service store bus))

(defmethod ig/halt-key! :notification/event-service [_ _]
  (println "Stopping event service..."))

;; =============================================================================
;; Notification Service
;; =============================================================================

(defmethod ig/init-key :notification/notification-service [_ {:keys [store sender config]}]
  (println "Starting notification service...")
  (notif-service/create-service store sender config))

(defmethod ig/halt-key! :notification/notification-service [_ _]
  (println "Stopping notification service..."))

;; =============================================================================
;; Event Handlers (bus subscribers)
;; =============================================================================

(defmethod ig/init-key :notification/handlers [_ {:keys [bus notification-service]}]
  (println "Registering event handlers...")
  (order-handler/register-handlers bus notification-service)
  (payment-handler/register-handlers bus notification-service)
  (shipment-handler/register-handlers bus notification-service)
  {:registered [:order :payment :shipment]})

(defmethod ig/halt-key! :notification/handlers [_ _]
  (println "Unregistering event handlers..."))

;; =============================================================================
;; HTTP Server — API-only (no /web or boundary routes)
;; =============================================================================

(defn- wrap-json-body
  "Parse JSON request body and associate it as :json-body."
  [handler]
  (fn [request]
    (let [body-str  (some-> request :body slurp)
          json-body (when (seq body-str)
                      (json/parse-string body-str true))]
      (handler (assoc request :json-body json-body)))))

(defmethod ig/init-key :notification/http-server
  [_ {:keys [event-service notification-service config]}]
  (let [port    (get-in config [:active :boundary/http :port] 3003)
        routes  (concat
                 (event-http/routes event-service)
                 (notif-http/routes notification-service)
                 [["/health" {:get {:handler (fn [_]
                                               (-> (response/response
                                                    (json/generate-string {:status "ok"}))
                                                   (response/content-type "application/json")))}}]])
        router  (ring/router routes)
        handler (-> (ring/ring-handler router)
                    wrap-keyword-params
                    wrap-params
                    wrap-json-body)
        server  (jetty/run-jetty handler {:port port :join? false})]
    (println (str "Starting notification HTTP server on port " port "..."))
    (println (str "Server running at http://localhost:" port))
    server))

(defmethod ig/halt-key! :notification/http-server [_ server]
  (println "Stopping notification HTTP server...")
  (.stop server))
