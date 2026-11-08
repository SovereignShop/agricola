# Minor prerequisite audit

Run `clojure -M scripts/audit_minor_prerequisites.clj` (optionally pass an EDN report path).

The audit covers all 422 implemented minors, comparing declared requirements to their executable predicates. It exercises 721 occupation-count boundary cases. Twenty-six special predicates have exact text/rule snapshots with review explanations in `resources/rules/minor-prerequisite-reviews.edn`; new or changed exceptions fail the audit. Paddocks has an intentional metadata override that supplies its printed pasture/occupation requirement missing from the source catalogue.

This is a consistency and boundary audit, not OCR or independent visual verification of every card. Run `clojure -M scripts/check_card_prints.clj` for separate print coverage/checksum verification.

## Corrections

- **Brushwood Roof (Original K136):** the English print in `original-k-minors.jpg`, sprite 19, visibly requires **3 occupations**. The older [publisher list](https://lookout-spiele.de/upload/archive/agricola-cards-completelist-v7.pdf) and compendium say two. Eurozone now follows the displayed English print, per the requested policy. Both the source catalogue and native definition use three. Already played cards are not removed retroactively.
- **Straw-Thatched Hut (FR046):** the local English `cards/original-fr046.jpg` says **2 plowed fields**. The predicate previously counted card fields too; it now requires two actual plowed farm tiles. Its construction effect is unchanged.

Review **Card test tables → Brushwood Roof - 3 occupations**. Two occupations block the card; play the third at Lessons, take the other player's turn, then play the Roof at Meeting Place. The existing **Straw-Thatched Hut** table demonstrates the qualified construction effect. Regression tests cover native rejection/acceptance, no mutation on rejection, save reload, and recording playback.
