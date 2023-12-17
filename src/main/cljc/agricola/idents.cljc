(ns agricola.idents)

(defn make-ident [name]
  [:agricola.bit/name name])

;; Squares
(def take-one-grain (make-ident :square-names/take-one-grain))
(def take-one-building-resource (make-ident :square-names/take-one-building-resource))
(def plow-one-field (make-ident :square-names/plow-one-field))
(def play-one-occupation (make-ident :square-names/play-occupation))
(def play-one-occupation-expensive (make-ident :square-names/play-occupation-expensive))
(def take-two-wood (make-ident :square-names/take-two-wood))
(def take-three-wood (make-ident :square-names/take-three-wood))
(def take-one-clay-board-one (make-ident :square-names/take-one-clay-board-one))
(def take-one-clay-board-two (make-ident :square-names/take-one-clay-board-two))
(def take-one-reed (make-ident :square-names/take-one-reed))
(def fishing (make-ident :square-names/fishing))
(def build-rooms (make-ident :square-names/build-rooms))
(def day-laborer (make-ident :square-names/day-laborer))
(def take-sheep (make-ident :square-names/take-sheep))
(def take-stone-round-2 (make-ident :square-names/take-stone-round-2))
(def renovate (make-ident :square-names/renovate))
(def family-growth (make-ident :square-names/family-growth))
(def starting-player (make-ident :square-names/starting-player))
(def build-fences (make-ident :square-names/build-fences))
(def major-or-minor (make-ident :square-names/major-or-minor))
(def sow-bake (make-ident :square-names/sow-bake))

;; Cards
(def grain-elevator (make-ident :card-names/grain-elevator))
(def field-watchman (make-ident :card-names/field-watchman))
(def family-counseler (make-ident :card-names/family-counseler))

;; Test
(def test-game [:agricola.game/name :test-game])
