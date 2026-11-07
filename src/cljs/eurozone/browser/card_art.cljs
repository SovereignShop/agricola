(ns eurozone.browser.card-art)
(def available #{:revised-base/a002 :revised-base/a005 :revised-base/a009 :revised-base/a012 :revised-base/a016 :revised-base/a019 :revised-base/a024 :revised-base/a026 :revised-base/a032 :revised-base/a033 :revised-base/a038 :revised-base/a044 :revised-base/a050 :revised-base/a053 :revised-base/a055 :revised-base/a056 :revised-base/a063 :revised-base/a067 :revised-base/a069 :revised-base/a071 :revised-base/a075 :revised-base/a078 :revised-base/a080 :revised-base/a086 :revised-base/a087 :revised-base/a088 :revised-base/a090 :revised-base/a098 :revised-base/a102 :revised-base/a108 :revised-base/a110 :revised-base/a112 :revised-base/a114 :revised-base/a116 :revised-base/a119 :revised-base/a120 :revised-base/a123 :revised-base/a125 :revised-base/a143 :revised-base/a147 :revised-base/a155 :revised-base/a160 :revised-base/a165 :revised-base/b002 :revised-base/b008 :revised-base/b010 :revised-base/b013 :revised-base/b016 :revised-base/b019 :revised-base/b024 :revised-base/b025 :revised-base/b033 :revised-base/b036 :revised-base/b039 :revised-base/b045 :revised-base/b047 :revised-base/b050 :revised-base/b056 :revised-base/b057 :revised-base/b061 :revised-base/b062 :revised-base/b066 :revised-base/b068 :revised-base/b074 :revised-base/b077 :revised-base/b080 :revised-base/b084 :revised-base/b087 :revised-base/b089 :revised-base/b091 :revised-base/b095 :revised-base/b097 :revised-base/b098 :revised-base/b099 :revised-base/b102 :revised-base/b104 :revised-base/b107 :revised-base/b108 :revised-base/b109 :revised-base/b114 :revised-base/b121 :revised-base/b123 :revised-base/b126 :revised-base/b136 :revised-base/b142 :revised-base/b145 :revised-base/b156 :revised-base/b163 :revised-base/b164 :revised-base/b166})
;; Exact pack identities keep reprints and Original cards separate.
(def printed-files
  {:catalog/original-expansion-g-30-headmaster "original-g030.jpg"
:catalog/original-expansion-g-39-mathematician "original-g039.jpg"
:catalog/original-expansion-g-40-miner "original-g040.jpg"
:catalog/original-expansion-g-43-opportunist "original-g043.jpg"
:catalog/original-expansion-g-45-pilgrim "original-g045.jpg"
:catalog/original-expansion-g-46-pond-watchman "original-g046.jpg"
:catalog/original-expansion-g-62-animal-farm "original-g062.jpg"
:catalog/original-expansion-g-67-bountiful-harvest "original-g067.jpg"
:catalog/original-expansion-g-68-chronicle "original-g068.jpg"
:catalog/original-expansion-g-72-dunghill "original-g072.jpg"
   :catalog/original-expansion-fr-20-five-rings "original-fr020.jpg"
   :catalog/original-expansion-fr-25-grain-mill-oven "original-fr025.jpg"
   :catalog/original-expansion-fr-66-boatswain "original-fr066.jpg"
   :catalog/original-expansion-fr-81-fiddler "original-fr081.jpg"
   :catalog/original-expansion-fr-85-harvester "original-fr085.jpg"
   :catalog/original-expansion-fr-86-head-of-the-revolution "original-fr086.jpg"
   :catalog/original-expansion-fr-91-manual-labourer "original-fr091.jpg"
   :catalog/original-expansion-fr-93-mastermind "original-fr093.jpg"
   :catalog/original-expansion-fr-106-sailboat-constructor "original-fr106.jpg"
   :catalog/original-expansion-fr-110-stroller "original-fr110.jpg"
   :catalog/original-expansion-g-1-alternative-designer "original-g001.jpg"
   :catalog/original-expansion-g-17-fish-seller "original-g017.jpg"
   :catalog/original-expansion-g-53-stonepiler "original-g053.jpg"
   :catalog/original-expansion-g-57-vegetarian "original-g057.jpg"
   :catalog/original-expansion-g-64-bartering-hut "original-g064.jpg"
   :catalog/original-expansion-g-70-corral "original-g070.jpg"
   :catalog/original-expansion-g-77-gimlet "original-g077.jpg"
   :catalog/original-expansion-g-90-paddocks "original-g090.jpg"
   :catalog/original-expansion-g-103-scythe "original-g103.jpg"
   :catalog/original-expansion-g-108-sheepdog "original-g108.jpg"
   :catalog/original-expansion-wm-5-bee-keeper "original-wm005.jpg"
   :catalog/original-expansion-wm-13-cowboy "original-wm013.jpg"
   :catalog/original-expansion-wm-32-hunter "original-wm032.jpg"
   :catalog/original-expansion-wm-39-pawnbroker "original-wm039.jpg"
   :catalog/original-expansion-wm-53-village-fool "original-wm053.jpg"
   :catalog/original-expansion-wm-64-bonfire "original-wm064.jpg"
   :catalog/original-expansion-wm-103-raspberries "original-wm103.jpg"
   :catalog/original-expansion-wm-104-reed-nursery "original-wm104.jpg"
   :catalog/original-expansion-wm-110-storage-shed "original-wm110.jpg"
   :catalog/original-expansion-wm-114-wine-closet "original-wm114.jpg"
   :catalog/original-expansion-fr-2-absinthe "original-fr002.jpg"
   :catalog/original-expansion-fr-5-artists-garden "original-fr005.jpg"
   :catalog/original-expansion-fr-14-chocolate "original-fr014.jpg"
   :catalog/original-expansion-fr-46-straw-thatched-hut "original-fr046.jpg"
   :catalog/original-expansion-fr-62-animal-welfarist "original-fr062.jpg"
   :catalog/original-expansion-fr-65-benefactor "original-fr065.jpg"
   :catalog/original-expansion-fr-67-cabbage-lover "original-fr067.jpg"
   :catalog/original-expansion-fr-74-country-doctor "original-fr074.jpg"
   :catalog/original-expansion-fr-92-martial-artist "original-fr092.jpg"
   :catalog/original-expansion-fr-97-parquet-setter "original-fr097.jpg"
   :catalog/original-expansion-g-2-animal-farmer "original-g002.jpg"
   :catalog/original-expansion-g-6-artisan "original-g006.jpg"
   :catalog/original-expansion-g-47-resource-collector "original-g047.jpg"
   :catalog/original-expansion-g-56-understudy "original-g056.jpg"
   :catalog/original-expansion-g-65-beehive-oven "original-g065.jpg"
   :catalog/original-expansion-g-71-cow-bell "original-g071.jpg"
   :catalog/original-expansion-g-79-hammer "original-g079.jpg"
   :catalog/original-expansion-g-94-potter-s-wheel "original-g094.jpg"
   :catalog/original-expansion-g-101-remodeling "original-g101.jpg"
   :catalog/original-expansion-g-104-seasonal-plants "original-g104.jpg"
   :catalog/original-expansion-wm-21-fence-dealer "original-wm021.jpg"
   :catalog/original-expansion-wm-34-livestock-keeper "original-wm034.jpg"
   :catalog/original-expansion-wm-45-stable-deliveryman "original-wm045.jpg"
   :catalog/original-expansion-wm-49-sunrise-admirer "original-wm049.jpg"
   :catalog/original-expansion-wm-58-apple-tree "original-wm058.jpg"
   :catalog/original-expansion-wm-62-backyard-well "original-wm062.jpg"
   :catalog/original-expansion-wm-69-changing-plans "original-wm069.jpg"
   :catalog/original-expansion-wm-87-gold-medal "original-wm087.jpg"
   :catalog/original-expansion-wm-99-pig-wallow "original-wm099.jpg"
   :catalog/original-expansion-wm-113-wild-breeding "original-wm113.jpg"
   :catalog/original-expansion-fr-13-chameleon "original-fr013.jpg"
   :catalog/original-expansion-fr-23-goblet "original-fr023.jpg"
   :catalog/original-expansion-fr-29-hay-rake "original-fr029.jpg"
   :catalog/original-expansion-fr-42-rock-pyramid "original-fr042.jpg"
   :catalog/original-expansion-fr-54-tuileries-garden "original-fr054.jpg"
   :catalog/original-expansion-fr-58-winter-deadwood "original-fr058.jpg"
   :catalog/original-expansion-fr-64-award-winner "original-fr064.jpg"
   :catalog/original-expansion-fr-70-cattle-dealer "original-fr070.jpg"
   :catalog/original-expansion-fr-82-gardening-enthusiast "original-fr082.jpg"
   :catalog/original-expansion-fr-107-sculptors-son "original-fr107.jpg"
   :catalog/original-expansion-g-9-baker-s-daughter "original-g009.jpg"
   :catalog/original-expansion-g-11-carrot-cake-baker "original-g011.jpg"
   :catalog/original-expansion-g-20-freeloader "original-g020.jpg"
   :catalog/original-expansion-g-27-grain-lover "original-g027.jpg"
   :catalog/original-expansion-g-34-inventor "original-g034.jpg"
   :catalog/original-expansion-g-44-pearl-seeker "original-g044.jpg"
   :catalog/original-expansion-g-52-stone-trader "original-g052.jpg"
   :catalog/original-expansion-g-66-birthstone "original-g066.jpg"
   :catalog/original-expansion-g-88-meat-pie "original-g088.jpg"
   :catalog/original-expansion-g-92-pine-forest "original-g092.jpg"
   :catalog/original-expansion-wm-4-bargaining-baker "original-wm004.jpg"
   :catalog/original-expansion-wm-12-covetous-farmer "original-wm012.jpg"
   :catalog/original-expansion-wm-16-diplomat "original-wm016.jpg"
   :catalog/original-expansion-wm-20-farm-school-graduate "original-wm020.jpg"
   :catalog/original-expansion-wm-24-gem-hunter "original-wm024.jpg"
   :catalog/original-expansion-wm-54-wheelbarrow-pusher "original-wm054.jpg"
   :catalog/original-expansion-wm-55-wood-worker "original-wm055.jpg"
   :catalog/original-expansion-wm-60-attic "original-wm060.jpg"
   :catalog/original-expansion-wm-70-clockwork-man "original-wm070.jpg"
   :catalog/original-expansion-wm-90-honeymoon "original-wm090.jpg"
   :catalog/original-expansion-fr-19-evening-prayer "original-fr019.jpg"
   :catalog/original-expansion-fr-24-golden-rose "original-fr024.jpg"
   :catalog/original-expansion-fr-31-heatwave "original-fr031.jpg"
   :catalog/original-expansion-fr-33-kids-corner "original-fr033.jpg"
   :catalog/original-expansion-fr-36-marsh "original-fr036.jpg"
   :catalog/original-expansion-fr-52-trees-for-the-citizens "original-fr052.jpg"
   :catalog/original-expansion-fr-78-drawing-genius "original-fr078.jpg"
   :catalog/original-expansion-fr-90-lemon-trader "original-fr090.jpg"
   :catalog/original-expansion-fr-98-pasteurization-expert "original-fr098.jpg"
   :catalog/original-expansion-fr-105-reformer "original-fr105.jpg"
   :catalog/original-expansion-g-3-animal-holder "original-g003.jpg"
   :catalog/original-expansion-g-4-apprentice "original-g004.jpg"
   :catalog/original-expansion-g-23-glutton "original-g023.jpg"
   :catalog/original-expansion-g-32-hobbyist "original-g032.jpg"
   :catalog/original-expansion-g-48-ringmaster "original-g048.jpg"
   :catalog/original-expansion-g-60-wintercrafter "original-g060.jpg"
   :catalog/original-expansion-g-83-holiday-present "original-g083.jpg"
   :catalog/original-expansion-g-84-horse-barn "original-g084.jpg"
   :catalog/original-expansion-g-98-pumpkin-pie "original-g098.jpg"
   :catalog/original-expansion-g-115-fish-farm "original-g115.jpg"
   :catalog/original-expansion-wm-8-bread-carrier "original-wm008.jpg"
   :catalog/original-expansion-wm-17-engineer "original-wm017.jpg"
   :catalog/original-expansion-wm-27-heiress "original-wm027.jpg"
   :catalog/original-expansion-wm-30-hoarder "original-wm030.jpg"
   :catalog/original-expansion-wm-37-master-stableman "original-wm037.jpg"
   :catalog/original-expansion-wm-50-tapestry-weaver "original-wm050.jpg"
   :catalog/original-expansion-wm-71-cookies "original-wm071.jpg"
   :catalog/original-expansion-wm-72-crib "original-wm072.jpg"
   :catalog/original-expansion-wm-84-friendly-pastures "original-wm084.jpg"
   :catalog/original-expansion-wm-89-home-garden "original-wm089.jpg"
   :catalog/original-expansion-fr-4-apple-orchard "original-fr004.jpg"
   :catalog/original-expansion-fr-41-peasants-boutique "original-fr041.jpg"
   :catalog/original-expansion-fr-49-the-port-le-havre "original-fr049.jpg"
   :catalog/original-expansion-fr-56-watering-can "original-fr056.jpg"
   :catalog/original-expansion-fr-63-art-director "original-fr063.jpg"
   :catalog/original-expansion-fr-72-cocotte "original-fr072.jpg"
   :catalog/original-expansion-fr-77-dove-hunter "original-fr077.jpg"
   :catalog/original-expansion-fr-89-landscape-gardener "original-fr089.jpg"
   :catalog/original-expansion-fr-109-stage-star "original-fr109.jpg"
   :catalog/original-expansion-fr-117-wealthiest-european "original-fr117.jpg"
   :catalog/original-expansion-g-18-fisherman-s-apprentice "original-g018.jpg"
   :catalog/original-expansion-g-22-glass-blower "original-g022.jpg"
   :catalog/original-expansion-g-24-grafter "original-g024.jpg"
   :catalog/original-expansion-g-26-grain-farmer "original-g026.jpg"
   :catalog/original-expansion-g-28-grump "original-g028.jpg"
   :catalog/original-expansion-g-31-helper "original-g031.jpg"
   :catalog/original-expansion-g-55-truffle-digger "original-g055.jpg"
   :catalog/original-expansion-g-58-village-farmer "original-g058.jpg"
   :catalog/original-expansion-g-59-wheedler "original-g059.jpg"
   :catalog/original-expansion-g-102-sausage-grinder "original-g102.jpg"
   :catalog/original-expansion-wm-2-animal-buyer "original-wm002.jpg"
   :catalog/original-expansion-wm-10-cloister-dweller "original-wm010.jpg"
   :catalog/original-expansion-wm-31-humble-farmboy "original-wm031.jpg"
   :catalog/original-expansion-wm-44-silo-girl "original-wm044.jpg"
   :catalog/original-expansion-wm-46-stone-curator "original-wm046.jpg"
   :catalog/original-expansion-wm-52-tile-maker "original-wm052.jpg"
   :catalog/original-expansion-wm-63-birdcage "original-wm063.jpg"
   :catalog/original-expansion-wm-83-free-range-chickens "original-wm083.jpg"
   :catalog/original-expansion-wm-85-fruitcake "original-wm085.jpg"
   :catalog/original-expansion-wm-91-iron-plow "original-wm091.jpg"
   :catalog/original-expansion-g-100-reed-sale "original-g100.jpg"
   :catalog/original-expansion-g-109-sheepskin-rug "original-g109.jpg"
   :catalog/original-expansion-g-111-sickle "original-g111.jpg"
   :catalog/original-expansion-g-114-stonecutter-s-hall "original-g114.jpg"
   :catalog/original-expansion-g-116-tree-house "original-g116.jpg"
   :catalog/original-expansion-wm-65-boundary-stones "original-wm065.jpg"
   :catalog/original-expansion-wm-66-brazier "original-wm066.jpg"
   :catalog/original-expansion-wm-86-glassblowers-shop "original-wm086.jpg"
   :catalog/original-expansion-wm-94-lamp-oil "original-wm094.jpg"
   :catalog/original-expansion-wm-115-worm-bait "original-wm115.jpg"
   :catalog/original-expansion-fr-21-flat-hill "original-fr021.jpg"
   :catalog/original-expansion-fr-35-lighthouse "original-fr035.jpg"
   :catalog/original-expansion-fr-79-drinker-of-absinthe "original-fr079.jpg"
   :catalog/original-expansion-fr-87-immigrants-son "original-fr087.jpg"
   :catalog/original-expansion-fr-116-village-druid "original-fr116.jpg"
   :catalog/original-expansion-g-13-clay-starter "original-g013.jpg"
   :catalog/original-expansion-g-16-fence-helper "original-g016.jpg"
   :catalog/original-expansion-wm-1-amazon "original-wm001.jpg"
   :catalog/original-expansion-wm-59-apprenticeship "original-wm059.jpg"
   :catalog/original-expansion-fr-12-camembert "original-fr012.jpg"
   :catalog/original-expansion-fr-15-coffee-break "original-fr015.jpg"
   :catalog/original-expansion-g-95-private-market "original-g095.jpg"
   :catalog/original-expansion-g-78-grain-market "original-g078.jpg"
   :catalog/original-expansion-wm-88-grain-elevator "original-wm088.jpg"
   :catalog/original-expansion-wm-78-farm-school "original-wm078.jpg"
   :catalog/original-expansion-fr-11-brickyard "original-fr011.jpg"
   :catalog/original-expansion-fr-88-journeyman "original-fr088.jpg"
   :catalog/original-expansion-g-12-carrot-farmer "original-g012.jpg"
   :catalog/original-expansion-g-97-pulley "original-g097.jpg"
   :catalog/original-expansion-wm-22-food-connoisseur "original-wm022.jpg"
   :catalog/original-expansion-wm-96-oak-cask "original-wm096.jpg"
   :catalog/original-expansion-fr-71-child-care-worker "original-fr071.jpg"
   :catalog/original-expansion-fr-96-oceanographer "original-fr096.jpg"
   :catalog/original-expansion-g-107-sheep-market "original-g107.jpg"
   :catalog/original-expansion-g-119-wooden-bridge "original-g119.jpg"
   :catalog/original-expansion-wm-77-edible-roots "original-wm77.jpg"
   :catalog/original-expansion-wm-79-festival-hall "original-wm79.jpg"
   :catalog/original-expansion-fr-1-abandoned-willow "original-fr1.jpg"
   :catalog/original-expansion-fr-101-powerhouse "original-fr101.jpg"

   :catalog/revised-artifex-expansion-a-18-wheel-plow "revised-a018.jpg"
   :catalog/revised-artifex-expansion-a-21-family-friend-home "revised-a021.jpg"
   :catalog/revised-artifex-expansion-a-25-bassinet "revised-a025.jpg"
   :catalog/revised-artifex-expansion-a-27-oven-site "revised-a027.jpg"
   :catalog/revised-artifex-expansion-a-34-loppers "revised-a034.jpg"
   :catalog/revised-artifex-expansion-a-35-swimming-class "revised-a035.jpg"
   :catalog/revised-artifex-expansion-a-61-winnowing-fan "revised-a061.jpg"
   :catalog/revised-artifex-expansion-a-72-calcium-fertilizers "revised-a072.jpg"
   :catalog/revised-artifex-expansion-a-79-garden-hoe "revised-a079.jpg"
   :catalog/revised-artifex-expansion-a-82-work-certificate "revised-a082.jpg"
   :catalog/revised-artifex-expansion-a-85-homekeeper "revised-a085.jpg"
   :catalog/revised-artifex-expansion-a-107-catcher "revised-a107.jpg"
   :catalog/revised-artifex-expansion-a-109-small-trader "revised-a109.jpg"
   :catalog/revised-artifex-expansion-a-113-heresy-teacher "revised-a113.jpg"
   :catalog/revised-artifex-expansion-a-128-riparian-builder "revised-a128.jpg"
   :catalog/revised-artifex-expansion-a-129-swagman "revised-a129.jpg"
   :catalog/revised-artifex-expansion-a-130-mummy-s-boy "revised-a130.jpg"
   :catalog/revised-artifex-expansion-a-131-craft-teacher "revised-a131.jpg"
   :catalog/revised-artifex-expansion-a-132-publican "revised-a132.jpg"
   :catalog/revised-artifex-expansion-a-139-hollow-warden "revised-a139.jpg"
   :catalog/revised-artifex-expansion-a-144-sequestrator "revised-a144.jpg"
   :catalog/revised-artifex-expansion-a-149-house-artist "revised-a149.jpg"
   :catalog/revised-artifex-expansion-a-151-minstrel "revised-a151.jpg"
   :catalog/revised-artifex-expansion-a-161-patch-caretaker "revised-a161.jpg"
   :catalog/revised-artifex-expansion-a-162-forest-tallyman "revised-a162.jpg"
   :catalog/revised-artifex-expansion-a-163-building-expert "revised-a163.jpg"
   :catalog/original-expansion-g-105-seed-spreader "original-g105.jpg"
   :catalog/original-expansion-g-117-vegetable-scoop "original-g117.jpg"
   :catalog/original-expansion-wm-73-diamond-mine "original-wm073.jpg"
   :catalog/original-expansion-fr-18-encircling-wall "original-fr018.jpg"
   :catalog/original-expansion-fr-45-stone-house-reconstruction "original-fr045.jpg"
   :catalog/revised-artifex-expansion-a-1-shelter "revised-a001.png"
   :catalog/revised-artifex-expansion-a-3-paper-knife "revised-a003.png"
   :catalog/revised-artifex-expansion-a-4-baseboards "revised-a004.png"
   :catalog/revised-artifex-expansion-a-6-storage-barn "revised-a006.png"
   :catalog/revised-artifex-expansion-a-7-gardener-s-knife "revised-a007.png"
   :catalog/revised-artifex-expansion-a-8-food-basket "revised-a008.png"
   :catalog/revised-artifex-expansion-a-10-wooden-shed "revised-a010.png"
   :catalog/revised-artifex-expansion-a-11-mud-patch "revised-a011.png"
   :catalog/revised-artifex-expansion-a-13-renovation-company "revised-a013.png"
   :catalog/revised-artifex-expansion-a-14-carpenter-s-hammer "revised-a014.png"
   :catalog/revised-artifex-expansion-a-15-carpenter-s-axe "revised-a015.png"
   :catalog/revised-artifex-expansion-a-17-reclamation-plow "revised-a017.png"
   :catalog/revised-artifex-expansion-a-20-double-turn-plow "revised-a020.png"
   :catalog/revised-artifex-expansion-a-22-telegram "revised-a022.png"
   :catalog/revised-artifex-expansion-a-23-stone-company "revised-a023.png"
   :catalog/revised-artifex-expansion-a-28-forest-school "revised-a028.png"
   :catalog/revised-artifex-expansion-a-29-ale-benches "revised-a029.png"
   :catalog/revised-artifex-expansion-a-30-baking-sheet "revised-a030.png"
   :catalog/revised-artifex-expansion-a-31-debt-security "revised-a031.png"
   :catalog/revised-artifex-expansion-a-36-facades-carving "revised-a036.png"
   :catalog/revised-artifex-expansion-a-37-bucksaw "revised-a037.png"
   :catalog/revised-artifex-expansion-a-39-chapel "revised-a039.png"
   :catalog/revised-artifex-expansion-a-40-potter-s-yard "revised-a040.png"
   :catalog/revised-artifex-expansion-a-41-vegetable-slicer "revised-a041.png"
   :catalog/revised-artifex-expansion-a-42-forest-lake-hut "revised-a042.png"
   :catalog/revised-artifex-expansion-a-43-farmyard-manure "revised-a043.png"
   :catalog/revised-artifex-expansion-a-45-fire-protection-pond "revised-a045.png"
   :catalog/revised-artifex-expansion-a-46-claw-knife "revised-a046.png"
   :catalog/revised-artifex-expansion-a-47-trellises "revised-a047.png"
   :catalog/revised-artifex-expansion-a-48-shaving-horse "revised-a048.png"
   :catalog/revised-artifex-expansion-a-49-nest-site "revised-a049.png"
   :catalog/revised-artifex-expansion-a-51-drift-net-boat "revised-a051.png"
   :catalog/revised-artifex-expansion-a-52-throwing-axe "revised-a052.png"
   :catalog/revised-artifex-expansion-a-54-credit "revised-a054.png"
   :catalog/revised-artifex-expansion-a-57-milking-parlor "revised-a057.png"
   :catalog/revised-artifex-expansion-a-58-asparagus-knife "revised-a058.png"
   :catalog/revised-artifex-expansion-a-59-potato-ridger "revised-a059.png"
   :catalog/revised-artifex-expansion-a-60-oriental-fireplace "revised-a060.png"
   :catalog/revised-artifex-expansion-a-62-beer-keg "revised-a062.png"
   :catalog/revised-artifex-expansion-a-64-barley-mill "revised-a064.png"
   :catalog/revised-artifex-expansion-a-65-seed-pellets "revised-a065.png"
   :catalog/revised-artifex-expansion-a-66-feeding-dish "revised-a066.png"
   :catalog/revised-artifex-expansion-a-68-asparagus-gift "revised-a068.png"
   :catalog/revised-artifex-expansion-a-70-lifting-machine "revised-a070.png"
   :catalog/revised-artifex-expansion-a-73-agricultural-fertilizers "revised-a073.png"
   :catalog/revised-artifex-expansion-a-74-stable-tree "revised-a074.png"
   :catalog/revised-artifex-expansion-a-76-cob "revised-a076.png"
   :catalog/revised-artifex-expansion-a-77-hod "revised-a077.png"
   :catalog/revised-artifex-expansion-a-81-interim-storage "revised-a081.png"
   :catalog/revised-artifex-expansion-a-84-silage "revised-a084.png"
   :catalog/revised-artifex-expansion-a-89-stable-planner "revised-a089.png"
   :catalog/revised-artifex-expansion-a-91-shifting-cultivator "revised-a091.png"
   :catalog/revised-artifex-expansion-a-93-bed-maker "revised-a093.png"
   :catalog/revised-artifex-expansion-a-94-lazy-sowman "revised-a094.png"
   :catalog/revised-artifex-expansion-a-95-angler "revised-a095.png"
   :catalog/revised-artifex-expansion-a-96-task-artisan "revised-a096.png"
   :catalog/revised-artifex-expansion-a-97-freshman "revised-a097.png"
   :catalog/revised-artifex-expansion-a-99-fellow-grazer "revised-a099.png"
   :catalog/revised-artifex-expansion-a-100-curator "revised-a100.png"
   :catalog/revised-artifex-expansion-a-101-cookery-outfitter "revised-a101.png"
   :catalog/revised-artifex-expansion-a-103-portmonger "revised-a103.png"
   :catalog/revised-artifex-expansion-a-104-wood-harvester "revised-a104.png"
   :catalog/revised-artifex-expansion-a-105-barrow-pusher "revised-a105.png"
   :catalog/revised-artifex-expansion-a-106-slurry-spreader "revised-a106.png"
   :catalog/revised-artifex-expansion-a-115-chief-forester "revised-a115.png"
   :catalog/revised-artifex-expansion-a-117-wood-carrier "revised-a117.png"
   :catalog/revised-artifex-expansion-a-118-treegardener "revised-a118.png"
   :catalog/revised-artifex-expansion-a-121-clay-puncher "revised-a121.png"
   :catalog/revised-artifex-expansion-a-122-pan-baker "revised-a122.png"
   :catalog/revised-artifex-expansion-a-124-knapper "revised-a124.png"
   :catalog/revised-artifex-expansion-a-126-master-workman "revised-a126.png"
   :catalog/revised-artifex-expansion-a-127-lodger "revised-a127.png"
   :catalog/revised-artifex-expansion-a-134-full-farmer "revised-a134.png"
   :catalog/revised-artifex-expansion-a-135-animal-reeve "revised-a135.png"
   :catalog/revised-artifex-expansion-a-136-drudgery-reeve "revised-a136.png"
   :catalog/revised-artifex-expansion-a-137-riverine-shepherd "revised-a137.png"
   :catalog/revised-artifex-expansion-a-140-shovel-bearer "revised-a140.png"
   :catalog/revised-artifex-expansion-a-141-turnip-farmer "revised-a141.png"
   :catalog/revised-artifex-expansion-a-142-cordmaker "revised-a142.png"
   :catalog/revised-artifex-expansion-a-145-ropemaker "revised-a145.png"
   :catalog/revised-artifex-expansion-a-146-storehouse-steward "revised-a146.png"
   :catalog/revised-artifex-expansion-a-148-woolgrower "revised-a148.png"
   :catalog/revised-artifex-expansion-a-150-stagehand "revised-a150.png"
   :catalog/revised-artifex-expansion-a-152-night-school-student "revised-a152.png"
   :catalog/revised-artifex-expansion-a-153-pig-owner "revised-a153.png"
   :catalog/revised-artifex-expansion-a-154-paymaster "revised-a154.png"
   :catalog/revised-artifex-expansion-a-156-buyer "revised-a156.png"
   :catalog/revised-artifex-expansion-a-157-bohemian "revised-a157.png"
   :catalog/revised-artifex-expansion-a-158-culinary-artist "revised-a158.png"
   :catalog/revised-artifex-expansion-a-159-joiner-of-the-sea "revised-a159.png"
   :catalog/revised-artifex-expansion-a-164-wood-worker "revised-a164.png"
   :catalog/revised-artifex-expansion-a-166-haydryer "revised-a166.png"
   :catalog/revised-artifex-expansion-a-167-breeder-buyer "revised-a167.png"
   :catalog/revised-artifex-expansion-a-168-animal-teacher "revised-a168.png"
   :catalog/original-expansion-g-25-businesswoman "original-g025.jpg"
   :catalog/original-expansion-g-50-singer "original-g050.jpg"
   :catalog/original-expansion-g-76-gardening-tools "original-g076.jpg"
   :catalog/original-expansion-g-110-shovel "original-g110.jpg"
   :catalog/original-expansion-wm-15-debris-dealer "original-wm015.jpg"
   :catalog/original-expansion-wm-28-herdsman "original-wm028.jpg"
   :catalog/original-expansion-wm-106-ruins "original-wm106.jpg"
   :catalog/original-expansion-wm-108-steamer "original-wm108.jpg"
   :catalog/original-expansion-fr-7-baguette "original-fr007.jpg"
   :catalog/original-expansion-fr-8-barber-shop "original-fr008.jpg"
   :catalog/original-expansion-fr-32-homework "original-fr032.jpg"
   :catalog/original-expansion-fr-113-trailblazer "original-fr113.jpg"

   :catalog/original-expansion-g-33-hoe-maker "original-g033.jpg"
   :catalog/original-expansion-g-49-seed-master "original-g049.jpg"
   :catalog/original-expansion-g-73-errant-oven "original-g073.jpg"
   :catalog/original-expansion-g-74-extra-effort "original-g074.jpg"
   :catalog/original-expansion-g-86-itinerant-laborers "original-g086.jpg"
   :catalog/original-expansion-g-89-migrant-workers "original-g089.jpg"
   :catalog/original-expansion-g-99-reed-beds "original-g099.jpg"
   :catalog/original-expansion-g-113-stone-kitchen "original-g113.jpg"
   :catalog/original-expansion-wm-26-hedge-master "original-wm026.jpg"
   :catalog/original-expansion-wm-29-hill-farmer "original-wm029.jpg"
   :catalog/original-expansion-wm-38-minimalist "original-wm038.jpg"
   :catalog/original-expansion-wm-41-politician "original-wm041.jpg"
   :catalog/original-expansion-wm-51-tavern-keeper "original-wm051.jpg"
   :catalog/original-expansion-wm-92-keys "original-wm092.jpg"
   :catalog/original-expansion-wm-93-kindling "original-wm093.jpg"
   :catalog/original-expansion-wm-101-prize-winning-sheep "original-wm101.jpg"
   :catalog/original-expansion-fr-22-full-bottomed-wig "original-fr022.jpg"
   :catalog/original-expansion-fr-28-hammock "original-fr028.jpg"
   :catalog/original-expansion-fr-30-haystack "original-fr030.jpg"
   :catalog/original-expansion-fr-55-vegetable-harvest "original-fr055.jpg"
   :catalog/original-expansion-fr-84-grain-speculator "original-fr084.jpg"
   :catalog/original-expansion-fr-104-racing-stable-manager "original-fr104.jpg"
   :catalog/original-expansion-fr-108-shovel-worker "original-fr108.jpg"
   :catalog/original-expansion-fr-111-the-sun-farmer "original-fr111.jpg"

   :catalog/original-expansion-g-5-architect "original-g005.jpg"
   :catalog/original-expansion-g-10-bard "original-g010.jpg"
   :catalog/original-expansion-g-21-hermit "original-g021.jpg"
   :catalog/original-expansion-g-29-harbor-master "original-g029.jpg"
   :catalog/original-expansion-g-51-stone-deliveryman "original-g051.jpg"
   :catalog/original-expansion-g-69-clear-cutting "original-g069.jpg"
   :catalog/original-expansion-g-75-farmers-market "original-g075.jpg"
   :catalog/original-expansion-g-81-hayloft "original-g081.jpg"
   :catalog/original-expansion-g-87-magic-beans "original-g087.jpg"
   :catalog/original-expansion-g-93-plentiful-fields "original-g093.jpg"
   :catalog/original-expansion-wm-3-apple-picker "original-wm003.jpg"
   :catalog/original-expansion-wm-67-buried-treasure "original-wm067.jpg"
   :catalog/original-expansion-wm-76-drained-pond "original-wm076.jpg"
   :catalog/original-expansion-wm-82-flower-pots "original-wm082.jpg"
   :catalog/original-expansion-wm-95-mountain-cave "original-wm095.jpg"
   :catalog/original-expansion-wm-97-open-gate "original-wm097.jpg"
   :catalog/original-expansion-wm-98-petting-zoo "original-wm098.jpg"
   :catalog/original-expansion-wm-100-plum-patch "original-wm100.jpg"
   :catalog/original-expansion-wm-109-steel-plow "original-wm109.jpg"
   :catalog/original-expansion-wm-111-undisturbed-pond "original-wm111.jpg"
   :catalog/original-expansion-fr-3-amusement-park "original-fr003.jpg"
   :catalog/original-expansion-fr-10-breakfast-outdoors "original-fr010.jpg"
   :catalog/original-expansion-fr-16-cornrick "original-fr016.jpg"
   :catalog/original-expansion-fr-17-diary "original-fr017.jpg"
   :catalog/original-expansion-fr-38-orchard "original-fr038.jpg"
   :catalog/original-expansion-fr-39-par-force-hunting "original-fr039.jpg"
   :catalog/original-expansion-fr-43-sofa "original-fr043.jpg"
   :catalog/original-expansion-fr-44-star-classification-meal "original-fr044.jpg"
   :catalog/original-expansion-fr-48-swimming-studio "original-fr048.jpg"
   :catalog/original-expansion-fr-53-trip-to-the-lake "original-fr053.jpg"
   :catalog/original-expansion-fr-57-wild-game "original-fr057.jpg"
   :catalog/original-expansion-fr-100-pipe-smoker "original-fr100.jpg"

   :catalog/revised-wizkids-white-expansion-c-25-steam-machine "wizkids-white-c025.png"
   :catalog/revised-wizkids-white-expansion-c-30-half-timbered-house "wizkids-white-c030.png"
   :catalog/revised-wizkids-white-expansion-c-59-schnapps-distillery "wizkids-white-c059.png"
   :catalog/revised-wizkids-white-expansion-c-77-clay-supply "wizkids-white-c077.png"
   :catalog/revised-wizkids-white-expansion-c-78-toad "wizkids-white-c078.png"
   :catalog/revised-wizkids-white-expansion-c-89-stable-master "wizkids-white-c089.png"
   :catalog/revised-wizkids-white-expansion-c-113-winter-caretaker "wizkids-white-c113.png"
   :catalog/revised-wizkids-white-expansion-c-118-wood-collector "wizkids-white-c118.png"
   :catalog/revised-wizkids-white-expansion-c-126-excavator "wizkids-white-c126.png"
   :catalog/revised-wizkids-white-expansion-c-128-wooden-hut-extender "wizkids-white-c128.png"
   :catalog/revised-wizkids-white-expansion-c-152-puppeteer "wizkids-white-c152.png"
   :catalog/revised-wizkids-white-expansion-c-166-cattle-whisperer "wizkids-white-c166.png"
   :catalog/revised-wizkids-white-expansion-d-4-cross-cut-wood "wizkids-white-d004.png"
   :catalog/revised-wizkids-white-expansion-d-38-milking-stool "wizkids-white-d038.png"
   :catalog/revised-wizkids-white-expansion-d-41-horse-drawn-boat "wizkids-white-d041.png"
   :catalog/revised-wizkids-white-expansion-d-43-hutch "wizkids-white-d043.png"
   :catalog/revised-wizkids-white-expansion-d-90-plow-maker "wizkids-white-d090.png"
   :catalog/revised-wizkids-white-expansion-d-107-bellfounder "wizkids-white-d107.png"
   :catalog/revised-wizkids-white-expansion-d-121-clay-plasterer "wizkids-white-d121.png"
   :catalog/revised-wizkids-white-expansion-d-132-hide-farmer "wizkids-white-d132.png"})
(defn url [id]
  (when-let [file (or (printed-files id) (when (available id) (str (name id) ".png")))]
    (str "/art/agricola/cards/" file)))

;; The original English E, I and K decks are printed on unmodified 10-column, 7-row sheets.
;; Indices are visually checked against the card titles, not catalogue order.
(def original-sprites
  {
   :revised-five/a169 ["revised-five-occupations.jpg" 0 10 5]
   :revised-five/a170 ["revised-five-occupations.jpg" 1 10 5]
   :revised-five/a171 ["revised-five-occupations.jpg" 2 10 5]
   :revised-five/a172 ["revised-five-occupations.jpg" 3 10 5]
   :revised-five/a173 ["revised-five-occupations.jpg" 4 10 5]
   :revised-five/a174 ["revised-five-occupations.jpg" 5 10 5]
   :revised-five/a175 ["revised-five-occupations.jpg" 6 10 5]
   :revised-five/a176 ["revised-five-occupations.jpg" 7 10 5]
   :revised-five/a177 ["revised-five-occupations.jpg" 8 10 5]
   :revised-five/a178 ["revised-five-occupations.jpg" 9 10 5]
   :revised-five/a179 ["revised-five-occupations.jpg" 10 10 5]
   :revised-five/a180 ["revised-five-occupations.jpg" 11 10 5]
   :revised-five/b169 ["revised-five-occupations.jpg" 12 10 5]
   :revised-five/b170 ["revised-five-occupations.jpg" 13 10 5]
   :revised-five/b171 ["revised-five-occupations.jpg" 14 10 5]
   :revised-five/b172 ["revised-five-occupations.jpg" 15 10 5]
   :revised-five/b173 ["revised-five-occupations.jpg" 16 10 5]
   :revised-five/b174 ["revised-five-occupations.jpg" 17 10 5]
   :revised-five/b175 ["revised-five-occupations.jpg" 18 10 5]
   :revised-five/b176 ["revised-five-occupations.jpg" 19 10 5]
   :revised-five/b177 ["revised-five-occupations.jpg" 20 10 5]
   :revised-five/b178 ["revised-five-occupations.jpg" 21 10 5]
   :revised-five/b179 ["revised-five-occupations.jpg" 22 10 5]
   :revised-five/b180 ["revised-five-occupations.jpg" 23 10 5]
   :revised-five/c169 ["revised-five-occupations.jpg" 24 10 5]
   :revised-five/c170 ["revised-five-occupations.jpg" 25 10 5]
   :revised-five/c171 ["revised-five-occupations.jpg" 26 10 5]
   :revised-five/c172 ["revised-five-occupations.jpg" 27 10 5]
   :revised-five/c173 ["revised-five-occupations.jpg" 28 10 5]
   :revised-five/c174 ["revised-five-occupations.jpg" 29 10 5]
   :revised-five/c175 ["revised-five-occupations.jpg" 30 10 5]
   :revised-five/c176 ["revised-five-occupations.jpg" 31 10 5]
   :revised-five/c177 ["revised-five-occupations.jpg" 32 10 5]
   :revised-five/c178 ["revised-five-occupations.jpg" 33 10 5]
   :revised-five/c179 ["revised-five-occupations.jpg" 34 10 5]
   :revised-five/c180 ["revised-five-occupations.jpg" 35 10 5]
   :revised-five/d169 ["revised-five-occupations.jpg" 36 10 5]
   :revised-five/d170 ["revised-five-occupations.jpg" 37 10 5]
   :revised-five/d171 ["revised-five-occupations.jpg" 38 10 5]
   :revised-five/d172 ["revised-five-occupations.jpg" 39 10 5]
   :revised-five/d173 ["revised-five-occupations.jpg" 40 10 5]
   :revised-five/d174 ["revised-five-occupations.jpg" 41 10 5]
   :revised-five/d175 ["revised-five-occupations.jpg" 42 10 5]
   :revised-five/d176 ["revised-five-occupations.jpg" 43 10 5]
   :revised-five/d177 ["revised-five-occupations.jpg" 44 10 5]
   :revised-five/d178 ["revised-five-occupations.jpg" 45 10 5]
   :revised-five/d179 ["revised-five-occupations.jpg" 46 10 5]
   :revised-five/d180 ["revised-five-occupations.jpg" 47 10 5]
   :revised-base/a092 ["revised-base-occupations.jpg" 8 10 5]
   :revised-base/a111 ["revised-base-occupations.jpg" 13 10 5]
   :revised-base/a133 ["revised-base-occupations.jpg" 19 10 5]
   :revised-base/a138 ["revised-base-occupations.jpg" 20 10 5]
   :revised-base/b118 ["revised-base-occupations.jpg" 35 10 5]
   :revised-base/a083 ["revised-base-minors.jpg" 25 10 6]
   :catalog/original-base-e-22-raft ["original-e-minors.jpg" 0]
   :catalog/original-base-e-53-butter-churn ["original-e-minors.jpg" 1]
   :catalog/original-base-e-19-gypsy-s-crock ["original-e-minors.jpg" 2]
   :catalog/original-base-e-338-feed-pellets ["original-e-minors.jpg" 3]
   :catalog/original-base-e-49-writing-desk ["original-e-minors.jpg" 4]
   :catalog/original-base-e-48-reed-pond ["original-e-minors.jpg" 5]
   :catalog/original-base-e-59-drinking-trough ["original-e-minors.jpg" 6]
   :catalog/original-base-e-60-cattle-market ["original-e-minors.jpg" 7]
   :catalog/original-base-e-62-turnwrest-plow ["original-e-minors.jpg" 8]
   :catalog/original-base-e-15-baking-tray ["original-e-minors.jpg" 9]
   :catalog/original-base-e-43-fruit-tree ["original-e-minors.jpg" 10]
   :catalog/original-base-e-61-riding-plow ["original-e-minors.jpg" 11]
   :catalog/original-base-e-20-simple-fireplace ["original-e-minors.jpg" 12]
   :catalog/original-base-e-37-clay-supports ["original-e-minors.jpg" 13]
   :catalog/original-base-e-14-baker-s-oven ["original-e-minors.jpg" 14]
   :catalog/original-base-e-58-animal-yard ["original-e-minors.jpg" 15]
   :catalog/original-base-e-57-dovecote ["original-e-minors.jpg" 16]
   :catalog/original-base-e-56-stone-tongs ["original-e-minors.jpg" 17]
   :catalog/original-base-e-27-wood-fired-oven ["original-e-minors.jpg" 18]
   :catalog/original-base-e-47-lettuce-patch ["original-e-minors.jpg" 19]
   :catalog/original-base-e-52-stable ["original-e-minors.jpg" 20]
   :catalog/original-base-e-40-mini-pasture ["original-e-minors.jpg" 21]
   :catalog/original-base-e-26-plane ["original-e-minors.jpg" 22]
   :catalog/original-base-e-38-madonna-statue ["original-e-minors.jpg" 23]
   :catalog/original-base-e-55-stone-house-extension ["original-e-minors.jpg" 24]
   :catalog/original-base-e-54-quarry ["original-e-minors.jpg" 25]
   :catalog/original-base-e-51-spindle ["original-e-minors.jpg" 26]
   :catalog/original-base-e-50-builder-s-trowel ["original-e-minors.jpg" 27]
   :catalog/original-base-e-46-sack-cart ["original-e-minors.jpg" 28]
   :catalog/original-base-e-45-private-forest ["original-e-minors.jpg" 29]
   :catalog/original-base-e-32-potato-dibber ["original-e-minors.jpg" 30]
   :catalog/original-base-e-33-ceramics ["original-e-minors.jpg" 31]
   :catalog/original-base-e-34-basket ["original-e-minors.jpg" 32]
   :catalog/original-base-e-23-manger ["original-e-minors.jpg" 33]
   :catalog/original-base-e-21-half-timbered-house ["original-e-minors.jpg" 34]
   :catalog/original-base-e-18-bean-field ["original-e-minors.jpg" 35]
   :catalog/original-base-e-44-outhouse ["original-e-minors.jpg" 36]
   :catalog/original-base-e-42-helpful-neighbors ["original-e-minors.jpg" 37]
   :catalog/original-base-e-41-millstone ["original-e-minors.jpg" 38]
   :catalog/original-base-e-31-carp-pond ["original-e-minors.jpg" 39]
   :catalog/original-base-e-30-canoe ["original-e-minors.jpg" 40]
   :catalog/original-base-e-29-shepherd-s-pipe ["original-e-minors.jpg" 41]
   :catalog/original-base-e-17-windmill ["original-e-minors.jpg" 42]
   :catalog/original-base-e-16-building-material ["original-e-minors.jpg" 43]
   :catalog/original-base-e-13-axe ["original-e-minors.jpg" 44]
   :catalog/original-base-e-39-market-stall ["original-e-minors.jpg" 45]
   :catalog/original-base-e-36-clay-roof ["original-e-minors.jpg" 46]
   :catalog/original-base-e-35-corn-scoop ["original-e-minors.jpg" 47]
   :catalog/original-base-e-28-clogs ["original-e-minors.jpg" 48]
   :catalog/original-base-e-25-spices ["original-e-minors.jpg" 49]
   :catalog/original-base-e-24-animal-pen ["original-e-minors.jpg" 50]
   :catalog/original-base-e-11-field ["original-e-minors.jpg" 51]
   :catalog/original-base-e-12-fishing-rod ["original-e-minors.jpg" 52]
   :catalog/original-base-e-185-clay-firer ["original-e-occupations-1.jpg" 0]
   :catalog/original-base-e-213-stockman ["original-e-occupations-1.jpg" 1]
   :catalog/original-base-e-169-storyteller ["original-e-occupations-1.jpg" 2]
   :catalog/original-base-e-216-animal-keeper ["original-e-occupations-1.jpg" 3]
   :catalog/original-base-e-215-tenant-farmer ["original-e-occupations-1.jpg" 4]
   :catalog/original-base-e-164-master-forester ["original-e-occupations-1.jpg" 5]
   :catalog/original-base-e-203-shepherd ["original-e-occupations-1.jpg" 6]
   :catalog/original-base-e-186-clay-seller ["original-e-occupations-1.jpg" 7]
   :catalog/original-base-e-166-undergardener ["original-e-occupations-1.jpg" 8]
   :catalog/original-base-e-167-conjurer ["original-e-occupations-1.jpg" 9]
   :catalog/original-base-e-212-dancer ["original-e-occupations-1.jpg" 10]
   :catalog/original-base-e-193-pastor ["original-e-occupations-1.jpg" 11]
   :catalog/original-base-e-183-basketmaker ["original-e-occupations-1.jpg" 12]
   :catalog/original-base-e-181-cook ["original-e-occupations-1.jpg" 13]
   :catalog/original-base-e-163-field-warden ["original-e-occupations-1.jpg" 14]
   :catalog/original-base-e-160-farmer ["original-e-occupations-1.jpg" 15]
   :catalog/original-base-e-206-swineherd ["original-e-occupations-1.jpg" 16]
   :catalog/original-base-e-204-master-shepherd ["original-e-occupations-1.jpg" 17]
   :catalog/original-base-e-192-patron ["original-e-occupations-1.jpg" 18]
   :catalog/original-base-e-178-hut-builder ["original-e-occupations-1.jpg" 19]
   :catalog/original-base-e-341-guildmaster ["original-e-occupations-1.jpg" 20]
   :catalog/original-base-e-211-stonecutter ["original-e-occupations-1.jpg" 21]
   :catalog/original-base-e-198-ratcatcher ["original-e-occupations-1.jpg" 22]
   :catalog/original-base-e-191-mason ["original-e-occupations-1.jpg" 23]
   :catalog/original-base-e-199-renovator ["original-e-occupations-1.jpg" 24]
   :catalog/original-base-e-218-carpenter ["original-e-occupations-1.jpg" 25]
   :catalog/original-base-e-158-turner ["original-e-occupations-1.jpg" 26]
   :catalog/original-base-e-147-land-agent ["original-e-occupations-1.jpg" 27]
   :catalog/original-base-e-170-estate-manager ["original-e-occupations-1.jpg" 28]
   :catalog/original-base-e-182-charcoal-burner ["original-e-occupations-1.jpg" 29]
   :catalog/original-base-e-172-chief ["original-e-occupations-1.jpg" 30]
   :catalog/original-base-e-173-chief-s-daughter ["original-e-occupations-1.jpg" 31]
   :catalog/original-base-e-162-meat-seller ["original-e-occupations-1.jpg" 32]
   :catalog/original-base-e-174-tutor ["original-e-occupations-1.jpg" 33]
   :catalog/original-base-e-210-stone-carrier ["original-e-occupations-1.jpg" 34]
   :catalog/original-base-e-179-merchant ["original-e-occupations-1.jpg" 35]
   :catalog/original-base-e-148-academic ["original-e-occupations-1.jpg" 36]
   :catalog/original-base-e-168-greengrocer ["original-e-occupations-1.jpg" 37]
   :catalog/original-base-e-217-reeve ["original-e-occupations-1.jpg" 38]
   :catalog/original-base-e-161-fisherman ["original-e-occupations-1.jpg" 39]
   :catalog/original-base-e-155-bread-seller ["original-e-occupations-1.jpg" 40]
   :catalog/original-base-e-156-brush-maker ["original-e-occupations-1.jpg" 41]
   :catalog/original-base-e-175-hedge-keeper ["original-e-occupations-1.jpg" 42]
   :catalog/original-base-e-207-stablehand ["original-e-occupations-1.jpg" 43]
   :catalog/original-base-e-196-mushroom-collector ["original-e-occupations-1.jpg" 44]
   :catalog/original-base-e-202-seasonal-worker ["original-e-occupations-1.jpg" 45]
   :catalog/original-base-e-150-baker ["original-e-occupations-1.jpg" 46]
   :catalog/original-base-e-157-thatcher ["original-e-occupations-1.jpg" 47]
   :catalog/original-base-e-214-potter ["original-e-occupations-1.jpg" 48]
   :catalog/original-base-e-200-conservator ["original-e-occupations-1.jpg" 49]
   :catalog/original-base-e-197-braggart ["original-e-occupations-1.jpg" 50]
   :catalog/original-base-e-188-clay-mixer ["original-e-occupations-1.jpg" 51]
   :catalog/original-base-e-187-clay-deliveryman ["original-e-occupations-1.jpg" 52]
   :catalog/original-base-e-171-dock-worker ["original-e-occupations-1.jpg" 53]
   :catalog/original-base-e-209-quarryman ["original-e-occupations-1.jpg" 54]
   :catalog/original-base-e-208-stablemaster ["original-e-occupations-1.jpg" 55]
   :catalog/original-base-e-195-plow-maker ["original-e-occupations-1.jpg" 56]
   :catalog/original-base-e-194-plow-driver ["original-e-occupations-1.jpg" 57]
   :catalog/original-base-e-184-grocer ["original-e-occupations-1.jpg" 58]
   :catalog/original-base-e-165-yeoman-farmer ["original-e-occupations-1.jpg" 59]
   :catalog/original-base-e-205-reed-collector ["original-e-occupations-1.jpg" 60]
   :catalog/original-base-e-190-maid ["original-e-occupations-1.jpg" 61]
   :catalog/original-base-e-189-lord-of-the-manor ["original-e-occupations-1.jpg" 62]
   :catalog/original-base-e-177-wooden-hut-builder ["original-e-occupations-1.jpg" 63]
   :catalog/original-base-e-176-woodcutter ["original-e-occupations-1.jpg" 64]
   :catalog/original-base-e-154-master-brewer ["original-e-occupations-1.jpg" 65]
   :catalog/original-base-e-153-mendicant ["original-e-occupations-1.jpg" 66]
   :catalog/original-base-e-152-berry-picker ["original-e-occupations-1.jpg" 67]
   :catalog/original-base-e-151-master-builder ["original-e-occupations-1.jpg" 68]
   :catalog/original-base-e-180-hobby-farmer ["original-e-occupations-2.jpg" 0]
   :catalog/original-base-e-201-cattle-whisperer ["original-e-occupations-2.jpg" 1]
   :catalog/original-base-e-149-master-baker ["original-e-occupations-2.jpg" 2]
   :catalog/original-base-e-159-head-of-the-family ["original-e-occupations-2.jpg" 3]
   :catalog/original-base-i-100-tavern ["original-i-minors.jpg" 27]
   :catalog/original-base-i-101-animal-feed ["original-i-minors.jpg" 28]
   :catalog/original-base-i-102-wildlife-reserve ["original-i-minors.jpg" 29]
   :catalog/original-base-i-103-water-mill ["original-i-minors.jpg" 42]
   :catalog/original-base-i-104-weekly-market ["original-i-minors.jpg" 41]
   :catalog/original-base-i-337-clay-deposit ["original-i-minors.jpg" 0]
   :catalog/original-base-i-63-moldboard-plow ["original-i-minors.jpg" 1]
   :catalog/original-base-i-64-alms ["original-i-minors.jpg" 2]
   :catalog/original-base-i-65-baker-s-kitchen ["original-i-minors.jpg" 15]
   :catalog/original-base-i-66-village-well ["original-i-minors.jpg" 16]
   :catalog/original-base-i-67-threshing-board ["original-i-minors.jpg" 17]
   :catalog/original-base-i-68-harrow ["original-i-minors.jpg" 30]
   :catalog/original-base-i-69-strawberry-patch ["original-i-minors.jpg" 31]
   :catalog/original-base-i-70-punner ["original-i-minors.jpg" 32]
   :catalog/original-base-i-71-holiday-house ["original-i-minors.jpg" 5]
   :catalog/original-base-i-72-goose-pond ["original-i-minors.jpg" 4]
   :catalog/original-base-i-73-guest ["original-i-minors.jpg" 3]
   :catalog/original-base-i-74-grain-cart ["original-i-minors.jpg" 18]
   :catalog/original-base-i-75-hand-mill ["original-i-minors.jpg" 19]
   :catalog/original-base-i-76-rake ["original-i-minors.jpg" 20]
   :catalog/original-base-i-77-shepherd-s-crook ["original-i-minors.jpg" 33]
   :catalog/original-base-i-78-copse ["original-i-minors.jpg" 34]
   :catalog/original-base-i-79-wood-cart ["original-i-minors.jpg" 35]
   :catalog/original-base-i-80-spinney ["original-i-minors.jpg" 6]
   :catalog/original-base-i-81-wooden-hut-extension ["original-i-minors.jpg" 7]
   :catalog/original-base-i-82-wooden-crane ["original-i-minors.jpg" 8]
   :catalog/original-base-i-83-wooden-path ["original-i-minors.jpg" 21]
   :catalog/original-base-i-84-chicken-coop ["original-i-minors.jpg" 22]
   :catalog/original-base-i-85-cooking-corner ["original-i-minors.jpg" 23]
   :catalog/original-base-i-86-corn-storehouse ["original-i-minors.jpg" 36]
   :catalog/original-base-i-87-flagon ["original-i-minors.jpg" 37]
   :catalog/original-base-i-88-lasso ["original-i-minors.jpg" 38]
   :catalog/original-base-i-89-clay-path ["original-i-minors.jpg" 9]
   :catalog/original-base-i-90-planter-box ["original-i-minors.jpg" 10]
   :catalog/original-base-i-91-ladder ["original-i-minors.jpg" 11]
   :catalog/original-base-i-92-manure ["original-i-minors.jpg" 24]
   :catalog/original-base-i-93-milking-shed ["original-i-minors.jpg" 25]
   :catalog/original-base-i-94-paved-road ["original-i-minors.jpg" 26]
   :catalog/original-base-i-95-fish-trap ["original-i-minors.jpg" 39]
   :catalog/original-base-i-96-reed-exchange ["original-i-minors.jpg" 40]
   :catalog/original-base-i-97-slaughterhouse ["original-i-minors.jpg" 12]
   :catalog/original-base-i-98-schnaps-distillery ["original-i-minors.jpg" 13]
   :catalog/original-base-i-99-straw-thatched-roof ["original-i-minors.jpg" 14]
   :catalog/original-base-i-219-fieldsman ["original-i-occupations.jpg" 0]
   :catalog/original-base-i-220-well-builder ["original-i-occupations.jpg" 2]
   :catalog/original-base-i-221-village-elder ["original-i-occupations.jpg" 15]
   :catalog/original-base-i-222-social-climber ["original-i-occupations.jpg" 16]
   :catalog/original-base-i-223-harvest-helper ["original-i-occupations.jpg" 17]
   :catalog/original-base-i-224-field-worker ["original-i-occupations.jpg" 30]
   :catalog/original-base-i-225-field-watchman ["original-i-occupations.jpg" 31]
   :catalog/original-base-i-226-gardener ["original-i-occupations.jpg" 32]
   :catalog/original-base-i-227-church-warden ["original-i-occupations.jpg" 3]
   :catalog/original-base-i-228-businessman ["original-i-occupations.jpg" 4]
   :catalog/original-base-i-229-sycophant ["original-i-occupations.jpg" 5]
   :catalog/original-base-i-230-clay-digger ["original-i-occupations.jpg" 18]
   :catalog/original-base-i-231-manservant ["original-i-occupations.jpg" 19]
   :catalog/original-base-i-232-midwife ["original-i-occupations.jpg" 20]
   :catalog/original-base-i-233-farm-steward ["original-i-occupations.jpg" 33]
   :catalog/original-base-i-234-wood-buyer ["original-i-occupations.jpg" 34]
   :catalog/original-base-i-235-wood-collector ["original-i-occupations.jpg" 35]
   :catalog/original-base-i-236-hide-farmer ["original-i-occupations.jpg" 7]
   :catalog/original-base-i-237-juggler ["original-i-occupations.jpg" 6]
   :catalog/original-base-i-238-chamberlain ["original-i-occupations.jpg" 8]
   :catalog/original-base-i-239-corn-profiteer ["original-i-occupations.jpg" 21]
   :catalog/original-base-i-240-cowherd ["original-i-occupations.jpg" 22]
   :catalog/original-base-i-241-clay-plasterer ["original-i-occupations.jpg" 23]
   :catalog/original-base-i-242-clay-hut-builder ["original-i-occupations.jpg" 36]
   :catalog/original-base-i-243-bricklayer ["original-i-occupations.jpg" 37]
   :catalog/original-base-i-244-layabout ["original-i-occupations.jpg" 38]
   :catalog/original-base-i-245-market-crier ["original-i-occupations.jpg" 9]
   :catalog/original-base-i-246-milking-hand ["original-i-occupations.jpg" 10]
   :catalog/original-base-i-247-butcher ["original-i-occupations.jpg" 11]
   :catalog/original-base-i-248-net-fisherman ["original-i-occupations.jpg" 24]
   :catalog/original-base-i-249-puppeteer ["original-i-occupations.jpg" 25]
   :catalog/original-base-i-250-sheep-whisperer ["original-i-occupations.jpg" 26]
   :catalog/original-base-i-251-reed-buyer ["original-i-occupations.jpg" 39]
   :catalog/original-base-i-252-pig-breeder ["original-i-occupations.jpg" 40]
   :catalog/original-base-i-253-pig-catcher ["original-i-occupations.jpg" 41]
   :catalog/original-base-i-254-groom ["original-i-occupations.jpg" 13]
   :catalog/original-base-i-255-stone-buyer ["original-i-occupations.jpg" 14]
   :catalog/original-base-i-256-stone-carver ["original-i-occupations.jpg" 12]
   :catalog/original-base-i-257-street-musician ["original-i-occupations.jpg" 27]
   :catalog/original-base-i-258-cabinetmaker ["original-i-occupations.jpg" 28]
   :catalog/original-base-i-259-animal-dealer ["original-i-occupations.jpg" 29]
   :catalog/original-base-i-260-taster ["original-i-occupations.jpg" 42]
   :catalog/original-base-i-261-outrider ["original-i-occupations.jpg" 43]
   :catalog/original-base-i-262-water-carrier ["original-i-occupations.jpg" 44]
   :catalog/original-base-i-263-fence-builder ["original-i-occupations.jpg" 46]
   :catalog/original-base-i-264-fencer ["original-i-occupations.jpg" 47]
   :catalog/original-base-i-265-fence-deliveryman ["original-i-occupations.jpg" 45]
   :catalog/original-base-i-340-rancher ["original-i-occupations.jpg" 1]
   :catalog/original-base-k-142-stone-cart ["original-k-minors.jpg" 31]
   :catalog/original-base-k-117-greenhouse ["original-k-minors.jpg" 26]
   :catalog/original-base-k-108-beehive ["original-k-minors.jpg" 29]
   :catalog/original-base-k-139-sleeping-corner ["original-k-minors.jpg" 4]
   :catalog/original-base-k-115-swing-plow ["original-k-minors.jpg" 40]
   :catalog/original-base-k-135-horse ["original-k-minors.jpg" 20]
   :catalog/original-base-k-144-mansion ["original-k-minors.jpg" 17]
   :catalog/original-base-k-136-brushwood-roof ["original-k-minors.jpg" 19]
   :catalog/original-base-k-113-flail ["original-k-minors.jpg" 12]
   :catalog/original-base-k-121-sawhorse ["original-k-minors.jpg" 10]
   :catalog/original-base-k-112-bookshelf ["original-k-minors.jpg" 13]
   :catalog/original-base-k-107-lumber ["original-k-minors.jpg" 42]
   :catalog/original-base-k-122-sawmill ["original-k-minors.jpg" 9]
   :catalog/original-base-k-146-loom ["original-k-minors.jpg" 15]
   :catalog/original-base-k-119-crooked-plow ["original-k-minors.jpg" 24]
   :catalog/original-base-k-130-herb-garden ["original-k-minors.jpg" 7]
   :catalog/original-base-k-129-corn-sheaf ["original-k-minors.jpg" 8]
   :catalog/original-base-k-143-stone-exchange ["original-k-minors.jpg" 30]
   :catalog/original-base-k-109-spit-roast ["original-k-minors.jpg" 28]
   :catalog/original-base-k-118-liquid-manure ["original-k-minors.jpg" 25]
   :catalog/original-base-k-127-clapper ["original-k-minors.jpg" 22]
   :catalog/original-base-k-132-clay-hut-extension ["original-k-minors.jpg" 35]
   :catalog/original-base-k-124-yoke ["original-k-minors.jpg" 37]
   :catalog/original-base-k-111-bread-paddle ["original-k-minors.jpg" 14]
   :catalog/original-base-k-106-bakehouse ["original-k-minors.jpg" 2]
   :catalog/original-base-k-137-turnip-field ["original-k-minors.jpg" 18]
   :catalog/original-base-k-339-pelts ["original-k-minors.jpg" 0]
   :catalog/original-base-k-140-swan-lake ["original-k-minors.jpg" 3]
   :catalog/original-base-k-131-clay-pit ["original-k-minors.jpg" 6]
   :catalog/original-base-k-125-broom ["original-k-minors.jpg" 36]
   :catalog/original-base-k-128-cooking-hearth ["original-k-minors.jpg" 21]
   :catalog/original-base-k-145-forest-pasture ["original-k-minors.jpg" 16]
   :catalog/original-base-k-120-house-goat ["original-k-minors.jpg" 11]
   :catalog/original-base-k-114-duck-pond ["original-k-minors.jpg" 41]
   :catalog/original-base-k-133-milking-stool ["original-k-minors.jpg" 34]
   :catalog/original-base-k-126-landing-net ["original-k-minors.jpg" 23]
   :catalog/original-base-k-116-granary ["original-k-minors.jpg" 39]
   :catalog/original-base-k-110-brewery ["original-k-minors.jpg" 27]
   :catalog/original-base-k-138-reed-hut ["original-k-minors.jpg" 5]
   :catalog/original-base-k-141-boar-breeding ["original-k-minors.jpg" 32]
   :catalog/original-base-k-105-acreage ["original-k-minors.jpg" 1]
   :catalog/original-base-k-134-ox-team ["original-k-minors.jpg" 33]
   :catalog/original-base-k-123-wooden-strongbox ["original-k-minors.jpg" 38]
   :catalog/original-base-k-266-serf ["original-k-occupations.jpg" 47]
   :catalog/original-base-k-267-adoptive-parents ["original-k-occupations.jpg" 46]
   :catalog/original-base-k-268-pieceworker ["original-k-occupations.jpg" 45]
   :catalog/original-base-k-269-acrobat ["original-k-occupations.jpg" 44]
   :catalog/original-base-k-270-wet-nurse ["original-k-occupations.jpg" 43]
   :catalog/original-base-k-271-educator ["original-k-occupations.jpg" 42]
   :catalog/original-base-k-272-frame-builder ["original-k-occupations.jpg" 29]
   :catalog/original-base-k-273-basin-maker ["original-k-occupations.jpg" 28]
   :catalog/original-base-k-274-organic-farmer ["original-k-occupations.jpg" 27]
   :catalog/original-base-k-275-perpetual-student ["original-k-occupations.jpg" 14]
   :catalog/original-base-k-276-constable ["original-k-occupations.jpg" 13]
   :catalog/original-base-k-277-manufacturer ["original-k-occupations.jpg" 12]
   :catalog/original-base-k-278-forester ["original-k-occupations.jpg" 41]
   :catalog/original-base-k-279-scholar ["original-k-occupations.jpg" 40]
   :catalog/original-base-k-280-tanner ["original-k-occupations.jpg" 39]
   :catalog/original-base-k-281-traveling-salesman ["original-k-occupations.jpg" 26]
   :catalog/original-base-k-282-house-steward ["original-k-occupations.jpg" 25]
   :catalog/original-base-k-283-wood-deliveryman ["original-k-occupations.jpg" 24]
   :catalog/original-base-k-284-wood-distributor ["original-k-occupations.jpg" 11]
   :catalog/original-base-k-285-tinsmith ["original-k-occupations.jpg" 10]
   :catalog/original-base-k-286-smallholder ["original-k-occupations.jpg" 9]
   :catalog/original-base-k-287-storehouse-clerk ["original-k-occupations.jpg" 38]
   :catalog/original-base-k-288-storehouse-keeper ["original-k-occupations.jpg" 37]
   :catalog/original-base-k-289-countryman ["original-k-occupations.jpg" 36]
   :catalog/original-base-k-290-clay-worker ["original-k-occupations.jpg" 23]
   :catalog/original-base-k-291-lover ["original-k-occupations.jpg" 22]
   :catalog/original-base-k-292-market-woman ["original-k-occupations.jpg" 21]
   :catalog/original-base-k-293-plowman ["original-k-occupations.jpg" 8]
   :catalog/original-base-k-294-brushwood-collector ["original-k-occupations.jpg" 7]
   :catalog/original-base-k-295-cattle-breeder ["original-k-occupations.jpg" 6]
   :catalog/original-base-k-296-seed-seller ["original-k-occupations.jpg" 35]
   :catalog/original-base-k-297-sheep-farmer ["original-k-occupations.jpg" 34]
   :catalog/original-base-k-298-shepherd-boy ["original-k-occupations.jpg" 33]
   :catalog/original-base-k-299-slaughterman ["original-k-occupations.jpg" 20]
   :catalog/original-base-k-300-schnaps-distiller ["original-k-occupations.jpg" 19]
   :catalog/original-base-k-301-wood-carver ["original-k-occupations.jpg" 18]
   :catalog/original-base-k-302-pig-whisperer ["original-k-occupations.jpg" 5]
   :catalog/original-base-k-303-stone-breaker ["original-k-occupations.jpg" 4]
   :catalog/original-base-k-304-veterinarian ["original-k-occupations.jpg" 32]
   :catalog/original-base-k-305-animal-handler ["original-k-occupations.jpg" 31]
   :catalog/original-base-k-306-animal-tamer ["original-k-occupations.jpg" 30]
   :catalog/original-base-k-307-animal-breeder ["original-k-occupations.jpg" 17]
   :catalog/original-base-k-308-foreman ["original-k-occupations.jpg" 16]
   :catalog/original-base-k-309-weaver ["original-k-occupations.jpg" 15]
   :catalog/original-base-k-310-resource-seller ["original-k-occupations.jpg" 2]
   :catalog/original-base-k-311-magician ["original-k-occupations.jpg" 1]
   :catalog/original-base-k-312-fence-overseer ["original-k-occupations.jpg" 0]
   :catalog/original-base-k-342-animal-trainer ["original-k-occupations.jpg" 3]})
(defn available? [id] (boolean (or (available id) (printed-files id) (original-sprites id))))
(defn face [id label]
  (if-let [[sheet index columns rows] (original-sprites id)]
    [:span.card-sheet-art
     {:role "img" :aria-label (str label " · English card print") :data-card-art (str id)
      :style {:background-image (str "url(/art/agricola/" sheet ")")
              :background-size (str (* 100 (or columns 10)) "% " (* 100 (or rows 7)) "%")
              :background-position (str (* (/ (mod index (or columns 10)) (dec (or columns 10))) 100) "% "
                                        (* (/ (quot index (or columns 10)) (dec (or rows 7))) 100) "%")}}]
    (when-let [src (url id)]
      [:img {:src src :alt label :loading "lazy" :draggable false}])))
