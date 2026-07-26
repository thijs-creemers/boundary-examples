(ns notification.main
  "Notification service entry point. Starts the Integrant system and blocks.
   Requires a JWT_SECRET env var (>=32 chars) — boot fails fast without it."
  (:require [boundary.config :as config]
            [integrant.core :as ig])
  (:gen-class))

(defn -main [& _args]
  (println "Starting notification service…")
  (let [cfg    (config/load-config)
        system (ig/init (config/ig-config cfg))
        port   (get-in cfg [:active :boundary/http :port] 3003)]
    (println (str "Notification service running on http://localhost:" port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (println "\nShutting down…")
                                 (ig/halt! system)
                                 ;; the core.async bus uses agent-pool threads;
                                 ;; release them so the JVM exits promptly.
                                 (shutdown-agents))))
    @(promise)))
