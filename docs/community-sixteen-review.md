# Community batch sixteen review

Thirty Original-edition cards: ten G, ten WM/G2, ten FR/G3. This batch continues the printed-card, native-rule, graphical-interaction and prepared-position requirements. Detailed print sources and interaction reviews are recorded in the deck-specific ledgers; artwork URLs and SHA-256 checksums are in `resources/public/art/agricola/manifest.json`.

## Planned cohort

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

In progress. Do not treat this ledger as a completed release until the native, print, browser and deployment results below are filled in.
