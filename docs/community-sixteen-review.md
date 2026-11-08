# Community batch sixteen review

Thirty Original-edition cards: ten G, ten WM/G2, ten FR/G3. This batch continues the printed-card, native-rule, graphical-interaction and prepared-position requirements. Detailed print sources and interaction reviews are recorded in the deck-specific ledgers; artwork URLs and SHA-256 checksums are in `resources/public/art/agricola/manifest.json`.

## Implemented cohort

| G | WM/G2 | FR/G3 |
|---|---|---|
| Family Counselor | Branch Collector | Prefect |
| Job Seeker | Contractor | Prosecutor |
| Late Sleeper | Crop Rotator | Tower Builder |
| Master Baker's Apprentice | Forest Farmer | Necklace |
| Oldest Occupation | Godfather | Park Cemetery |
| Tax Collector | Animal Nursery | Sunday School |
| Harvest Feast | Back Door | Timetable |
| Hired Helpers | Donkey | Cat Lover |
| Pelt Roof | Fish Hook | Fencing Master |
| Private School | Ram | Pear Peeler |

## Shared board geometry

`agricola.action-board` is the shared source for the displayed three-board layout, physical board identity and orthogonal adjacency. Rule checks use fixed logical rectangles rather than viewer pan, zoom, mobile reflow, or pixel tolerances. They resolve revealed round-card identities from the actual game. Five-player linked action cards share the existing displayed subdivision. Card-created action spaces do not belong to a printed board.

For example, Fishing adjoins Reed, Day Laborer and the currently revealed round-four card; unrevealed card identities are never needed. This agrees with the neighborhood described for Net Fisherman in the [Agricola compendium](https://www.reedstonefood.com/agricola/compendium). Family Counselor uses board identity rather than adjacency and excludes virtual card actions. The [official Revised appendix](https://www.lookout-spiele.de/upload/en_agricolare.html_Appendix_Agricola-RE_EN.pdf) clarifies shared-edge adjacency across gaps; visual spacing does not change adjacency.

## Verification

- Combined targeted card tests: **45 tests / 308 assertions**, zero failures or errors. Includes native prepared positions, optional choices, store reload, recording playback and cross-deck combinations.
- Cross-deck checks cover Cat Lover + Contractor + Pelt Roof, Timetable + Branch Collector (paid stock versus discounted-away materials), and Harvest Feast + Crop Rotator without harvest income.
- Artwork audit: **914 / 914 implemented hand cards**, including all thirty new cards.
- Coverage: G **107/119**, WM **106/115**, FR **109/120**; **322/354** combined, **32 remain**.
- Full regression: **1,040 tests / 27,999 assertions**, zero failures or errors.
- Follow-up Ram/Shepherd and cross-deck verification: **17 tests / 121 assertions**, zero failures or errors; Ram counts for breeding thresholds, Nursery animals do not.
- Both browser and bot-worker bundles compile with **advanced** Closure optimizations.
- Browser review: **90 reviews passed**: every new card in desktop web, desktop Discord and 390px mobile Discord. Includes direct farm/action-board clicks, optional declines, no resolution dropdowns, width checks, persistence/reload, and Park Cemetery’s full three-round collection → discard → plow workflow. Forest Farmer uses an ordinary click after centering its target clear of the mobile review header.

Browser review also corrected three interaction issues: growth-only actions show a read-only farm; Park Cemetery offers its clickable plowing farm only once all stone is gone; Donkey's ability dismisses card inspection to expose the queued relocation decision.

Every card in the table above has a namesake entry in **Card test tables**. Start with **Family Counselor**, **Branch Collector**, or **Necklace** and use **Next card** through that deck's ten-card review group. Each review opens a fresh creator-controlled table.
