(ns eurozone.browser.board-layout (:require [agricola.action-board :as board]))
;; Rendering and card rules share immutable board geometry. Viewer layout does
;; not affect the rules; tiny image margins do not break physical adjacency.
(def width board/width)
(def height board/height)
(def segments board/segments)
(def actions board/actions)
(def rounds board/rounds)
(def player-count-area board/player-count-area)
(def player-count-groups board/player-count-groups)
(defn position [[x y w h]]
  {:left (str (* 100 (/ x width)) "%") :top (str (* 100 (/ y height)) "%")
   :width (str (* 100 (/ w width)) "%") :height (str (* 100 (/ h height)) "%")})
(defn round-position [round]
  (position (into (nth rounds (dec round)) [340 510])))
