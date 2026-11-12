# Original G, WM/G2, and FR/G3: final cards

The remaining scope was **32 cards**, bringing these three decks to **354/354**:
G 119/119, WM/G2 115/115, FR/G3 120/120. All 32 use native engine commands,
persisted decisions and real English card prints. The complete artwork audit covers
956/956 implemented hand cards and major improvements.

The prints were checked for card identity, Original edition, costs, prerequisites,
player count and full rules. WM and FR prints come from the archived original
[G2](http://playagricola.com/Agricola/Board1/allcardsg2.php?s=chris-G2) and
[G3](http://playagricola.com/Agricola/Board1/allcardsg3.php?s=chris-G3) deck pages,
not similarly named later prototypes. Each local JPG is registered in
`src/cljs/eurozone/browser/card_art.cljs` and
`resources/public/art/agricola/manifest.json` with source URL and SHA256.
The [compendium](https://agricola.no/wp-content/uploads/2024/07/compendium.pdf)
was used to check interactions. Actual prints take precedence: Stone Crane costs
2 wood and 2 stone; Unicycle Driver requires four players.

## Prepared tables and interaction review

Open **Card test tables** in the browser or Discord Activity lobby. Select a name
below, then **Open test table**. Each opens a new creator-controlled table titled
**Test · <card name>**. **Next card** visits this batch in the order below.
Positions prepare prerequisites and trigger native effects, pausing at the decision;
automatic effects wait immediately before their triggering action. Existing tables
are never overwritten. Printed card text remains available throughout.

Resource choices and payments use graphical controls. Spatial choices use the
actual farm, physical action board, or round track. Mobile board pickers preserve
the board geometry and pan horizontally; review navigation scrolls away so it
cannot cover decision controls. Auctions use sealed food bids;
Writing Maniac and Village School keep private card choices out of public facts.
Witches' Dance Ground extends the real farm geometry used by building, sowing,
fencing, scoring and playback. Viewer-relative tabletop placement preserves player
identities, seat order, tabs and ownership.

| Card test table | Verified English print | What to try |
|---|---|---|
| Astronomer | [G007](http://playagricola.com/Agricola/Images/occg3astronomer_v2.jpg) | Two guests have taken Grain Seeds and Farmland. Place the last adult at Lessons to complete three equal-sized spaces in a straight line; observe one bonus point at Returning Home. |
| Food Critic | [G019](http://playagricola.com/Agricola/Images/occg1foodcritic_v2.jpg) | A sheep was cooked through the native conversion action. Store its token or decline. Later take Fishing, bake, and use other conversions to collect different items for points. |
| Badger | [FR006](http://playagricola.com/Agricola/Cards/Cards/xminorBadger_2851_11.jpg) | Click a revealed action space to place Badger. Anyone using it gets one food. At the next round, move the marker to a revealed space sharing an edge. |
| Bathroom | [FR009](http://playagricola.com/Agricola/Cards/Cards/xminorBathroom_2569_13.jpg) | One grain and one vegetable have just been converted to food. Store either or both on Bathroom, or decline. Sow the two fields, then click them to add their matching stored crops. |
| Agrarian | [FR061](http://playagricola.com/Agricola/Cards/Cards/occx3Agrarian_2311_7.jpg) | A new round offers a one-food reservation on Farmland. Accept or decline. If nobody uses Farmland, the food returns at round end and you may plow directly on your farm. |
| Card Player | [FR068](http://playagricola.com/Agricola/Cards/Cards/occx1CardPlayer_3408_14.jpg) | Card Player has wood on top and stone at the bottom. Take Forest to receive one extra wood. At round end the top resource moves to the bottom; inspect the stack on the card. |
| Late Bloomer | [G036](http://playagricola.com/Agricola/Images/occg4latebloomer.jpg) | Every opponent has a larger family, and you have a spare room. Accept free growth to add an adult who can work immediately, or decline and later use the played card's ability while eligible. |
| Shared Wall | [G106](http://playagricola.com/Agricola/Images/gminorsharedwall.jpg) | Use Fencing to surround the unused space above your home with three fences. The room wall closes the fourth side, at no fence or wood cost. Your home cannot later extend into this pasture. |
| Master Cook | [WM036](http://playagricola.com/Agricola/Cards/Cards/occx3MasterCook_505_7.jpg) | Before your first placement, open Master Cook and use its ability. One adult stays on the card, you get two food and a guest, and another player acts next. Taking an ordinary action instead declines this round's opportunity. |
| Early Riser | [G014](http://playagricola.com/Agricola/Images/occg1earlyriser_v3.jpg) | Take Grain Seeds with the final worker to begin harvest. Breed before collecting your field and feeding. The sheep pasture is full and the boar occupies your home; optionally cook the newborn sheep using your Fireplace. |
| Almanac | [G061](http://playagricola.com/Agricola/Images/gminoralmanac.jpg) | Play Almanac at Meeting Place. Observe your grain harvest, then breed your sheep. No food is charged for feeding, and the card passes to your neighbor after its harvest finishes. |
| Stone Crane | [G112](http://playagricola.com/Agricola/Images/gminorstonecrane.jpg) | A neighbor has built a room. Choose a building-resource discount and click an adjacent farm space for your room, or decline. When a neighbor renovates, the card instead offers discounted renovation. |
| Baking Field | [G063](http://playagricola.com/Agricola/Images/gminorbakingfield.jpg) | Take Grain Seeds with the last worker to begin harvest. Baking Field's grain must immediately become two food through your Fireplace. Its field stack shrinks, and ordinary supply grain is not baked. |
| Kitchen Table | [FR034](http://playagricola.com/Agricola/Cards/Cards/xminorKitchenTable_2794_26.jpg) | Use Grain Utilization and choose the pictured option to store one food on Kitchen Table instead of baking. Open the played card to withdraw it and take a Bake Bread action. The Clay Oven can bake one grain for five food. |
| Downsizing | [WM075](http://playagricola.com/Agricola/Cards/Cards/xminorDownsizing_250_12.jpg) | A first pasture has just been fenced. Return the wooden room you built above your home, or decline. After returning it, click up to seven additional free fence edges. The two original rooms cannot be returned. |
| Expert Builder | [WM018](http://playagricola.com/Agricola/Cards/Cards/occx3ExpertBuilder_563_9.jpg) | A neighbor has paid wood and reed for a room. That player chooses one paid resource for your Expert Builder, with no additional charge. Later build or renovate and edit the pictured stored-resource payment. |
| Musketeer | [FR095](http://playagricola.com/Agricola/Cards/Cards/occx4Musketeer_3357_5.jpg) | Place the arrow directly at a four-space intersection on the action board. Try the corner shared by Grain Seeds, Farmland, Forest, and Clay Pit. Occupying two, three, or four marked spaces at Returning Home gives a sheep, boar, or cattle. |
| Mailman | [WM035](http://playagricola.com/Agricola/Cards/Cards/occx3Mailman_1068_19.jpg) | Place four pictured packages on different revealed action spaces. Use a marked space and accept or decline its one-food purchase before the action. Try placing reed on Farm Expansion so it can help pay for a room. |
| Resource Lender | [WM043](http://playagricola.com/Agricola/Cards/Cards/occx3ResourceLender_1065_9.jpg) | Forest has just supplied three wood. Choose the extra wood, then click the wood icons to schedule four deliveries on the next four round cards, or decline to keep the three wood now. |
| Ground Pickaxe Plow | [FR027](http://playagricola.com/Agricola/Cards/Cards/xminorGroundPickaxePlow_2854_7.jpg) | Farmland has just plowed your first field. Click one or two adjacent action spaces to place one wood on each, then click the actual farm to plow that many extra fields. Declining preserves the once-per-game use. |
| Winter Garden | [G118](http://playagricola.com/Agricola/Images/gminorwintergarden.jpg) | Use Grain Utilization and sow directly in one of your rooms. It harvests normally and at the end of rounds 6, 8, 10, and 12. Taking a pet into your home clears its crops; the room never counts as a field tile. |
| Permaculturist | [WM040](http://playagricola.com/Agricola/Cards/Cards/occx1Permaculturist_2126_6.jpg) | Use Grain Utilization and sow grain directly on any unused farm spaces, even far apart. Open Permaculturist to clear selected temporary fields. Their grain and spaces return before final scoring and do not satisfy field prerequisites. |
| Irrigation Canal | [G085](http://playagricola.com/Agricola/Images/gminorirrigationcanal.jpg) | Farmland has just plowed beside a grain field. Click the new field and then the planted neighbor to move and sow one grain with one fewer supply crop, or decline. Cultivation also offers sowing before plowing. |
| Sunday Worker | [WM048](http://playagricola.com/Agricola/Cards/Cards/occx3SundayWorker_548_10.jpg) | Round seven's Forest action has just collected its pile. Pay one food to collect the same amount again, or decline. For a non-accumulation action, the card instead opens a second native action with its own choices and costs. |
| Debt Collector | [FR076](http://playagricola.com/Agricola/Cards/Cards/occx4DebtCollector_2680_7.jpg) | Click a future round at least three higher to reserve your guest. At that round's start, pay the starting player one food and place the guest before regular starting-player order resumes. |
| Remodeler | [WM042](http://playagricola.com/Agricola/Cards/Cards/occx1Remodeler_180_12.jpg) | House Redevelopment is about to renovate your wooden hut. Return the stable above your home, then click that space or another legal space for a free wooden room, or decline. Renovation pays for all three rooms. |
| Auctioneer | [G008](http://playagricola.com/Agricola/Images/occg4auctioneer_v2.jpg) | The round-seven auction is ready. Each player clicks food icons to seal a bid. All bids reveal together; highest wins two reed and pays the Auctioneer, with ties following this round's starting-player order. A winning owner pays the supply. |
| Writing Maniac | [FR120](http://playagricola.com/Agricola/Cards/Cards/occx3WritingManiac_2363_4.jpg) | Choose occupation faces and place them on future round cards. At their scheduled round, pay two food to play the assigned card or return it to your hand. Opponents see only face-down assignments. |
| Unicycle Driver | [FR115](http://playagricola.com/Agricola/Cards/Cards/occx4UnicycleDriver_2821_5.jpg) | At round start, pay a stone and click two adjacent action spaces for a road, or decline. Try Grain Seeds and Farmland. End work with a worker on Grain Seeds and Farmland empty, then use Farmland through the road without moving the worker. |
| Mooch | [G041](http://playagricola.com/Agricola/Images/occg3mooch_v2.jpg) | Choose the neighbor with a Cooking Hearth, Clay Oven, and Joinery. Your cooking and baking controls immediately borrow those rates; feeding also offers their workshop. The neighbor keeps their cards, and your access expires after the next harvest. |
| Village School | [WM112](http://playagricola.com/Agricola/Cards/Cards/xminorVillageSchool_639_3.jpg) | You have four wood and two reed, one wood short of a room. At Farm Expansion, choose a hand card under Village School, click wood, and place the room above your home. The discarded card stays private and no resource is received. Cancel the discount to keep your card. |
| Witches’ Dance Ground | [FR059](http://playagricola.com/Agricola/Cards/Cards/xminorWitchesDanceGround_2815_7.jpg) | Choose to extend or cover your farm, then click two adjacent squares on the actual farm. Extend beside it for two usable spaces and one point, or cover two unused squares. Try plowing, rooms, or fences on the extension and inspect scoring; restart to try the other placement. |

## Verification

Native coverage in `test/agricola/community_final_test.clj` exercises acceptance,
optional decline, invalid choices, timing, actual payments, card combinations,
private data, store reload, and exact recording frames for all 32 cards.
`test/browser_community_final.py` exercises fresh prepared tables, direct spatial
clicks, graphical exchanges, no-dropdown checks, viewport fit, reload, and native
recording imports/seeking in standalone desktop web and desktop/mobile Discord
Activity. `test/eurozone/community_final_browser_fixture.clj` generates those
recordings through the same native commands and store export used in play.

Final native verification: `clojure -M:test` passed **1,089 tests / 28,853
assertions**, with zero failures and zero errors. `python3 scripts/build_browser.py`
compiled the browser and bot bundles and verified **956/956** required English
prints. `git diff --check` passed.

The clean final browser run passed **96 prepared-table reviews** (32 cards ×
standalone web, desktop Discord Activity, and 390 × 844 mobile Discord Activity)
and **96 native recording playbacks** in those same contexts, with no browser
errors. The run includes Writing Maniac's later round-start decision, Unicycle
Driver's end-of-work extra action, both Witches' Dance Ground placements, and
reload after committed changes. Spatial board geometry and mobile horizontal
panning are asserted; the mobile review header no longer covers controls.
Desktop and mobile screenshots were inspected for the spatial flows.

Reproduction commands:

```sh
clojure -M:test
python3 scripts/build_browser.py
# Requires Playwright and EUROZONE_BROWSER pointing to Chromium.
python3 test/browser_community_final.py
```

These are local implementation and verification results; the separately hosted
release has not been deployed or restarted.

The seven older bundled sample recordings already fail replay verification on the
unchanged repository HEAD. This was checked in a separate baseline checkout.
Their historical mismatch is pre-existing; these new prepared-position recordings
are verified against the current engine in both JVM and browser runtimes.
