(ns notification.handler.shipment
  "Event handler for shipment-related events.
   
   Subscribes to shipment events and creates appropriate notifications."
  (:require [notification.notification.ports :as notif-ports]
            [notification.shared.bus :as msg-bus]))

;; =============================================================================
;; Shipment Event Handlers
;; =============================================================================

(defn handle-shipment-sent
  "Handle shipment.sent event.
   Creates shipping update notification."
  [notification-service event]
  (println "[Handler] Processing shipment.sent:" (:id event))
  (let [result (notif-ports/create-notification
                notification-service
                event
                :email
                :shipping-update)]
    (when (:ok result)
      (notif-ports/send-and-update notification-service (get-in result [:ok :id])))))

(defn handle-shipment-delivered
  "Handle shipment.delivered event.
   Creates delivery confirmation notification."
  [notification-service event]
  (println "[Handler] Processing shipment.delivered:" (:id event))
  ;; Send via both email and push for delivery
  (doseq [channel [:email :push]]
    (let [result (notif-ports/create-notification
                  notification-service
                  event
                  channel
                  :delivery-confirmation)]
      (when (:ok result)
        (notif-ports/send-and-update notification-service (get-in result [:ok :id]))))))

(defn handle-shipment-returned
  "Handle shipment.returned event.
   Creates return received notification."
  [notification-service event]
  (println "[Handler] Processing shipment.returned:" (:id event))
  (let [result (notif-ports/create-notification
                notification-service
                event
                :email
                :return-received)]
    (when (:ok result)
      (notif-ports/send-and-update notification-service (get-in result [:ok :id])))))

;; =============================================================================
;; Handler Registry
;; =============================================================================

(defn register-handlers
  "Register shipment event handlers with the message bus."
  [bus notification-service]
  (msg-bus/subscribe! bus :notification/shipping-update
                      (fn [event] (handle-shipment-sent notification-service event)))
  (msg-bus/subscribe! bus :notification/delivery-confirmation
                      (fn [event] (handle-shipment-delivered notification-service event)))
  (msg-bus/subscribe! bus :notification/return-received
                      (fn [event] (handle-shipment-returned notification-service event))))
