(ns smoke
  "Boot smoke test: build the Integrant config and start/stop the full system.
   Catches missing init-keys and (via the user module) the JWT_SECRET fail-fast
   guard. Exits non-zero on any boot failure."
  (:require [integrant.core :as ig]
            [boundary.config :as config]))

(defn -main [& _]
  (println "[smoke] starting system…")
  (let [sys (try
              (ig/init (config/ig-config (config/load-config)))
              (catch Throwable t
                (println "[smoke] BOOT FAILED:" (.getMessage t))
                (when-let [c (.getCause t)]
                  (println "[smoke] cause:" (.getMessage c)))
                (System/exit 1)))]
    (println "[smoke] started OK — components:" (sort (keys sys)))
    (ig/halt! sys)
    (println "[smoke] stopped OK")
    (println "[smoke] BOOT SMOKE PASSED")
    (shutdown-agents)
    (System/exit 0)))
