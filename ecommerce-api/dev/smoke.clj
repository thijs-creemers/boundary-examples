(ns smoke
  "Boot smoke test: start the full Integrant system and stop it.
   Catches missing Integrant init-keys and (on beta-1) the JWT_SECRET
   fail-fast guard. Exits non-zero on any boot failure."
  (:require [ecommerce.system :as system]))

(defn -main [& _]
  (println "[smoke] starting system…")
  (let [sys (try
              (system/start!)
              (catch Throwable t
                (println "[smoke] BOOT FAILED:" (.getMessage t))
                (System/exit 1)))]
    (println "[smoke] started OK — components:" (sort (keys sys)))
    (system/stop! sys)
    (println "[smoke] stopped OK")
    (println "[smoke] BOOT SMOKE PASSED")
    (shutdown-agents)
    (System/exit 0)))
