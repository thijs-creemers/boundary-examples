(ns notification.handler.payment
  "Event handler for payment-related events.
   
   Subscribes to payment events and creates appropriate notifications."
  (:require [notification.notification.ports :as notif-ports]
            [notification.shared.bus :as msg-bus]))

;; =============================================================================
;; Payment Event Handlers
;; =============================================================================

(defn handle-payment-received
  "Handle payment.received event.
   Creates payment receipt notification."
  [notification-service event]
  (println "[Handler] Processing payment.received:" (:id event))
  (let [result (notif-ports/create-notification
                notification-service
                event
                :email
                :payment-receipt)]
    (when (:ok result)
      (notif-ports/send-and-update notification-service (get-in result [:ok :id])))))

(defn handle-payment-failed
  "Handle payment.failed event.
   Creates payment failed notification - important to notify quickly."
  [notification-service event]
  (println "[Handler] Processing payment.failed:" (:id event))
  ;; Send via multiple channels for payment failures
  (doseq [channel [:email :push]]
    (let [result (notif-ports/create-notification
                  notification-service
                  event
                  channel
                  :payment-failed)]
      (when (:ok result)
        (notif-ports/send-and-update notification-service (get-in result [:ok :id]))))))

(defn handle-payment-refunded
  "Handle payment.refunded event.
   Creates refund confirmation notification."
  [notification-service event]
  (println "[Handler] Processing payment.refunded:" (:id event))
  (let [result (notif-ports/create-notification
                notification-service
                event
                :email
                :refund-confirmation)]
    (when (:ok result)
      (notif-ports/send-and-update notification-service (get-in result [:ok :id])))))

;; =============================================================================
;; Handler Registry
;; =============================================================================

(defn register-handlers
  "Register payment event handlers with the message bus."
  [bus notification-service]
  (msg-bus/subscribe! bus :notification/payment-receipt
                      (fn [event] (handle-payment-received notification-service event)))
  (msg-bus/subscribe! bus :notification/payment-failed
                      (fn [event] (handle-payment-failed notification-service event)))
  (msg-bus/subscribe! bus :notification/refund-confirmation
                      (fn [event] (handle-payment-refunded notification-service event))))
