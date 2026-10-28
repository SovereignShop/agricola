.PHONY: build run test dev
build:
	python3 scripts/build_browser.py
run:
	clojure -M:run
test:
	clojure -M:test
dev:
	clojure -M:cljs -w src/cljs:src/main/cljc -c eurozone.browser.core
