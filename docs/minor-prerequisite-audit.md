# Minor prerequisite audit

Run `clojure -M scripts/audit_minor_prerequisites.clj` (optionally pass an EDN report path).

The audit covers all 422 implemented minors, comparing declared requirements to their executable predicates. It exercises 721 occupation-count boundary cases. Twenty-six special predicates have exact text/rule snapshots with review explanations in `resources/rules/minor-prerequisite-reviews.edn`; new or changed exceptions fail the audit. Paddocks has an intentional metadata override that supplies its printed pasture/occupation requirement missing from the source catalogue.

This is a consistency and boundary audit, not OCR or independent visual verification of every card. Run `clojure -M scripts/check_card_prints.clj` for separate print coverage/checksum verification.

## Corrections

- **Brushwood Roof (Original K136):** the English print in `original-k-minors.jpg`, sprite 19, visibly requires **3 occupations**. The older [publisher list](https://lookout-spiele.de/upload/archive/agricola-cards-completelist-v7.pdf) and compendium say two. Eurozone now follows the displayed English print, per the requested policy. Both the source catalogue and native definition use three. Already played cards are not removed retroactively.
- **Straw-Thatched Hut (FR046):** the local English `cards/original-fr046.jpg` says **2 plowed fields**. The predicate previously counted card fields too; it now requires two actual plowed farm tiles. Its construction effect is unchanged.

Review **Card test tables → Brushwood Roof - 3 occupations**. Two occupations block the card; play the third at Lessons, take the other player's turn, then play the Roof at Meeting Place. The existing **Straw-Thatched Hut** table demonstrates the qualified construction effect. Regression tests cover native rejection/acceptance, no mutation on rejection, save reload, and recording playback.

## Verification and deployment

The clean release passed 34 focused tests / 4,108 assertions, including native legality, scenario preparation, persistence and recording replay. Desktop (1440px) and mobile (390px) browser checks passed the two-to-three occupation transition and reload. Print coverage passed (914/914 implemented hand cards). The audit reports no failures; deliberately restoring Brushwood Roof's two-occupation predicate makes it fail both consistency and boundary checks.

Deployed 2026-11-07 from the isolated checkout `/home/john/.local/share/eurozone/releases/minor-prerequisites-c0b455e`, preserving unrelated unfinished workspace edits. The checkout uses the original `.env`, data directory and review recordings; saves were backed up before restarting. Public health and main/browser-bot/Ascension bundle checksums passed. For this release the running server's working directory is that checkout, not the main development workspace. Reopen the game to load the new browser rules; all connected peers should reload. Existing played cards are retained.
