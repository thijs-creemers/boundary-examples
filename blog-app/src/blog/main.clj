(ns blog.main
  "Blog application entry point. Starts the Integrant system and blocks.
   Requires a JWT_SECRET env var (>=32 chars) — boot fails fast without it."
  (:require [boundary.config :as config]
            [integrant.core :as ig])
  (:gen-class))

(defn -main [& _args]
  (println "Starting blog…")
  (let [system (ig/init (config/ig-config (config/load-config)))
        port   (get-in (config/load-config) [:active :boundary/http :port] 3001)]
    (println (str "Blog running on http://localhost:" port))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (println "\nShutting down…")
                                 (ig/halt! system))))
    @(promise)))
