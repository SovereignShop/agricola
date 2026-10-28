;; Use the build API: cljs.main's browser REPL preloads depend on document and
;; cannot run in a Web Worker. Production bundles do not need those preloads.
(require '[cljs.build.api :as build] '[clojure.edn :as edn])
(doseq [[source config] [["src/cljs/eurozone/browser/core.cljs" "build.cljs.edn"]
                         ["src/cljs/eurozone/browser/bot_worker.cljs" "build.bot.cljs.edn"]]]
  (build/build (build/inputs source) (assoc (edn/read-string (slurp config)) :preloads [])))
