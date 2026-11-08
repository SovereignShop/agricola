# Original G-deck batch sixteen

Actual English prints were visually checked as a contact sheet, installed locally and registered with source URLs and SHA-256 checksums in the artwork manifest. Printed rules and the [Agricola compendium](https://www.reedstonefood.com/agricola/compendium) inform implementation. Source files are hosted under `http://playagricola.com/Agricola/Images/` (the HTTPS endpoint was unavailable).

| Card test table | Interaction and rules |
|---|---|
| Family Counselor | Automatic reward at Returning Home, based on workers' final physical board segment. Guests count; newborns only count after becoming workers. Card-created spaces belong to no board. |
| Job Seeker | Optional card-face occupation selection with a one-food payment. Job Seeker counts as zero occupations; newly played occupation records that it cannot return to hand. |
| Late Sleeper | Optional worker/guest exchange graphic before work. All awake players place first. Sleeping prevents Taster priority. |
| Master Baker’s Apprentice | Click the actual revealed action board to mark a baking space; graphical baking exchange. Other players baking there pay the owner one food from supply. |
| Oldest Occupation | Optional graphical family growth and payment. Owner grows free; another player pays owner two food. Room required, round five onward. |
| Tax Collector | Click building resources actually paid for a major; optional second resource costs one food. |
| Harvest Feast | Click actual planted farm/card fields, then crop quantity icons. Each player may collect any amount, without triggering Harvest. |
| Hired Helpers | Optional guest now/one fewer worker next round. A final prompt follows the last placement; declining creates no debt. A future-round worker marker shows the debt. |
| Pelt Roof | Graphical cooked animals→stored reed. Sheep qualify in pairs. Optional stored-reed payment icons use the actual discounted construction cost; only rooms and renovation qualify. |
| Private School | Public card action space, occupation card faces. Owner free, other players pay owner one food. Print has no occupation prerequisite; its “1 Occupation” illustration describes the action. |

All ten prepared positions trigger native effects and appear in Card test tables. Native tests verify resolution, optional declines, native save/load, recording playback, and bot decision candidates. Final targeted native verification: 21 tests / 331 assertions passed, including the existing round-marker suite. Print coverage: 894 / 894 implemented hand cards.

`test/browser_community_g_sixteen.py` covers desktop web, desktop Discord and mobile Discord, including actual action-board and farm clicks. It is prepared for the parent's combined advanced bundle; browser success is not claimed until that run completes.

Combined release verification: all ten cards passed desktop web, desktop Discord and mobile Discord browser reviews (30 per deck); the combined suite passed 1,040 tests / 27,999 assertions, plus 17 follow-up tests / 121 assertions for Ram/Shepherd and cross-deck combinations. All 914 implemented hand cards have verified local prints. See `community-sixteen-review.md` for the combined release record.
