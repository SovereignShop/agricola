(ns eurozone.card-scenarios
  "Prepared, playable positions for reviewing native card effects. Only registered
   scenarios may create test tables; callers cannot supply arbitrary game states."
  (:require [agricola.engine :as a] [agricola.card-game :as game] [agricola.card-rules :as cards]))

(def scenarios
  [   {:id "family-counselor" :card :catalog/original-expansion-g-15-family-counselor :name "Family Counselor" :players 2
    :description "Your first adult took Forest. Place the final worker on Clay Pit: both adults occupy the early-round board, so receive one food at Returning Home. A different board would not qualify."}
   {:id "job-seeker" :card :catalog/original-expansion-g-35-job-seeker :name "Job Seeker" :players 4
    :description "Two opponents have more occupations. A new round offers a pictured one-food payment to play a card from your hand; decline is allowed. Job Seeker itself does not count."}
   {:id "late-sleeper" :card :catalog/original-expansion-g-37-late-sleeper :name "Late Sleeper" :players 4
    :description "At round preparation, choose the extra guest and delay all your workers until every other player finishes, or decline to keep normal order."}
   {:id "master-bakers-apprentice" :card :catalog/original-expansion-g-38-master-baker-s-apprentice :name "Master Baker’s Apprentice" :players 2
    :description "Click a revealed action space to mark it with Bake Bread for everyone. You may bake now; later other players baking through that marker pay you one food from the supply."}
   {:id "oldest-occupation" :card :catalog/original-expansion-g-42-oldest-occupation :name "Oldest Occupation" :players 4
    :description "Day Laborer in round five has triggered optional family growth. You have a spare room. Your own growth is free; an opponent would pay you two food."}
   {:id "tax-collector" :card :catalog/original-expansion-g-54-tax-collector :name "Tax Collector" :players 3
    :description "An opponent built a Fireplace with clay. Click the clay icon for the free resource, then optionally pay one food for another clay. Only resources actually paid qualify."}
   {:id "harvest-feast" :card :catalog/original-expansion-g-80-harvest-feast :name "Harvest Feast" :players 2
    :description "Click planted fields and use crop icons to collect any amount into your supply. Each player receives their own optional choice. This is not a harvest."}
   {:id "hired-helpers" :card :catalog/original-expansion-g-82-hired-helpers :name "Hired Helpers" :players 2
    :description "Your last family member has acted. Accept one guest for a final placement this round or decline. Accepting reserves one fewer worker next round; consecutive use and round fourteen are forbidden."}
   {:id "pelt-roof" :card :catalog/original-expansion-g-91-pelt-roof :name "Pelt Roof" :players 2
    :description "A pair of sheep has just been cooked. Store one reed on Pelt Roof, or decline. The stored reed can pay for room building or renovation but is not ordinary supply."}
   {:id "private-school" :card :catalog/original-expansion-g-96-private-school :name "Private School" :players 2
    :description "Private School is a shared occupation action space. Choose its action to play a card: the owner uses it free; another player pays the owner one food plus any printed occupation costs."}

   {:id "necklace" :card :catalog/original-expansion-fr-37-necklace :name "Necklace" :players 2
    :description "Your first person took Grain Seeds. Take Farmland with your last adult: the two adjacent spaces award one food at Returning Home."}
   {:id "prefect" :card :catalog/original-expansion-fr-102-prefect :name "Prefect" :players 3
    :description "The harvest is ready. Use your Pottery when feeding: Prefect receives one food. Each distinct workshop or occupation that converts building materials during this harvest pays once, whoever uses it."}
   {:id "prosecutor" :card :catalog/original-expansion-fr-103-prosecutor :name "Prosecutor" :players 4
    :description "Three opponents have more improvements. Play Shovel at Meeting Place: choose two pictured material discounts. The discount count updates when opponents or you gain improvements."}
   {:id "tower-builder" :card :catalog/original-expansion-fr-112-tower-builder :name "Tower Builder" :players 4
    :description "You alone have exactly two rooms. Build a normal room at Farm Expansion, then click one additional farm space for Tower Builder’s free room, or decline."}
   {:id "timetable" :card :catalog/original-expansion-fr-51-timetable :name "Timetable" :players 2
    :description "Timetable holds one of every building material. Play Shovel at Meeting Place: the stored wood and clay can cover its cost. Toggle the pictured stock payment to keep those materials instead."}
   {:id "cat-lover" :card :catalog/original-expansion-fr-69-cat-lover :name "Cat Lover" :players 3
    :description "Four animals are housed on your farm. Select a wooden room at Farm Expansion, then click a graphical payment plan to discount any three building resources for that room."}
   {:id "sunday-school" :card :catalog/original-expansion-fr-47-sunday-school :name "Sunday School" :players 2
    :description "Sunday School was passed left after reserving a person. Its next round has ended with one fewer worker. Choose the first of two free occupation plays; printed extra food costs still apply."}
   {:id "park-cemetery" :card :catalog/original-expansion-fr-40-park-cemetery :name "Park Cemetery" :players 2
    :description "Farmland was just used. Take one of the three stone markers or decline. After all stone is gone, open Park Cemetery to discard it and plow directly on the farm."}
   {:id "fencing-master" :card :catalog/original-expansion-fr-80-fencing-master :name "Fencing Master" :players 3
    :description "Fencing Master has just been played as your first occupation. Click one or two fence markers to store them, or decline. They are reserved and become free when you later take a Fences action."}
   {:id "pear-peeler" :card :catalog/original-expansion-fr-99-pear-peeler :name "Pear Peeler" :players 4
    :description "Forest supplied three wood. Click a pictured exchange to leave one, two, or three wood on that space for grain, vegetables, or both; decline to keep all the wood."}
   {:id "grotto" :card :catalog/original-expansion-fr-26-grotto :name "Grotto" :players 2
    :description "Round end has arrived. Click a pictured clay or stone to pay two food and collect it from Grotto, or decline. Once all five pieces have been collected, Grotto provides room for one additional family member."}
   {:id "threshing-machine-plow" :card :catalog/original-expansion-fr-50-threshing-machine-plow :name "Threshing Machine Plow" :players 2
    :description "Farmland is waiting for your plow choice. Click the other two highlighted middle farm spaces to plow the exact three-space middle row. This uses the plow once; skipping leaves a normal one-field action."}
   {:id "wood-saw" :card :catalog/original-expansion-fr-60-wood-saw :name "Wood Saw" :players 2
    :description "Every opponent has a larger family. Click an empty farm space next to your home to build a room at its normal cost, or decline. This card action uses no worker and does not receive Miser discounts."}
   {:id "convict-number-24601" :card :catalog/original-expansion-fr-73-convict-number-24601 :name "Convict Number 24601" :players 2
    :description "Convict was played in round twelve. It is now round thirteen: one family member stays home but still needs food. Take a normal action and inspect the six bonus points earned for rounds twelve through fourteen."}
   {:id "cowboy-and-mother" :card :catalog/original-expansion-fr-75-cowboy-and-mother :name "Cowboy And Mother" :players 2
    :description "Round twelve is returning home after at most two work actions. Choose one food, one pictured animal, or a field. Choosing a field opens direct farm placement; a newborn would prevent this reward."}
   {:id "good-friend" :card :catalog/original-expansion-fr-83-good-friend :name "Good Friend" :players 3
    :description "Another player just paid wood and clay for Shovel. Click either resource to take one matching piece from your Good Friend stock, or decline. Only resources actually paid qualify."}
   {:id "miser" :card :catalog/original-expansion-fr-94-miser :name "Miser" :players 3
    :description "Build exactly one wooden room on Build Rooms. Your four wood and one reed cover its discounted cost. Card-granted room building and building multiple rooms receive no Miser discount."}
   {:id "turkey-breeder" :card :catalog/original-expansion-fr-114-turkey-breeder :name "Turkey Breeder" :players 2
    :description "Choose up to four free fence edges on your farm, then optionally place one free stable. At the end of the final harvest, pay four wood; each missing wood becomes a begging card."}
   {:id "wood-gatherers" :card :catalog/original-expansion-fr-118-wood-gatherers :name "Wood Gatherers" :players 2
    :description "You spent five wood on a room before playing Wood Gatherers this round. Take Grain Seeds with the final worker and observe two wood received at round end."}
   {:id "workaholic" :card :catalog/original-expansion-fr-119-workaholic :name "Workaholic" :players 4
    :description "Five sheep are housed in your pasture. Click the wood pile to collect all five wood, or decline. Seven housed animals unlock the clay pile and nine unlock the stone pile; each pile can be collected once."}
   {:id "borrower" :card :catalog/original-expansion-wm-6-borrower :name "Borrower" :players 2
    :description "Borrower has supplied seven food and two begging cards. Open its card and click the pictured three-food repayment to discard one eligible begging card."}
   {:id "chemist" :card :catalog/original-expansion-wm-9-chemist :name "Chemist" :players 3
    :description "Wood has just been taken from the Forest. Click another building-resource accumulation space to leave one clay there and receive two food, or decline."}
   {:id "family-planner" :card :catalog/original-expansion-wm-19-family-planner :name "Family Planner" :players 2
    :description "Click the future round cards to reserve available family pieces for rounds five, eight and eleven. Reserved pieces cannot be used for earlier growth; each arrival still needs room."}
   {:id "lady-in-waiting" :card :catalog/original-expansion-wm-33-lady-in-waiting :name "Lady in Waiting" :players 3
    :description "The person reserved in stage one is arriving in round twelve. Pay two food to bring this adult home and act this round even without a spare room, or decline."}
   {:id "storehouse-minder" :card :catalog/original-expansion-wm-47-storehouse-minder :name "Storehouse Minder" :players 3
    :description "Click distinct building-resource icons to put one of each selected type on the card. At each harvest choose up to two remaining types to increase; once per round collect every piece of one type."}
   {:id "almshouse" :card :catalog/original-expansion-wm-56-almshouse :name "Almshouse" :players 2
    :description "Feed your family with too little food. After feeding, Almshouse offers to discard one feeding penalty; begging cards from Borrower and other cards are ineligible."}
   {:id "cash-crop" :card :catalog/original-expansion-wm-68-cash-crop :name "Cash Crop" :players 2
    :description "Take Grain Seeds with the final worker to start harvest. The grain harvested from Cash Crop becomes a permanent bonus point instead of entering your supply."}
   {:id "fish-pond" :card :catalog/original-expansion-wm-81-fish-pond :name "Fish Pond" :players 2
    :description "Click an unused farm space to place the clay pond marker. Then take Fishing and choose how much of that pile food to store for the displayed scoring thresholds."}
   {:id "rotherham-plow" :card :catalog/original-expansion-wm-105-rotherham-plow :name "Rotherham Plow" :players 2
    :description "Farmland is staged with the first field at the upper left. Click an additional field that does not touch that first field, even diagonally. Both fields must still join the existing fields."}
   {:id "slapdash-renovation" :card :catalog/original-expansion-wm-107-slapdash-renovation :name "Slapdash Renovation" :players 2
    :description "Renovation has just finished. Return the person who took that action to your farm so they can take another action later this round, or decline."}
   {:id "headmaster" :card :catalog/original-expansion-g-30-headmaster :name "Headmaster" :players 4
    :description "Three opponents have played occupations, leaving three food on Headmaster. Use Lessons to play Chief: the stored food can pay both the lesson fee and the two extra printed food. Use the payment icons to keep the stock instead."}
   {:id "mathematician" :card :catalog/original-expansion-g-39-mathematician :name "Mathematician" :players 4
    :description "Round six has begun with equal wood piles. Click one of the highlighted action spaces to take exactly one wood without placing a worker, or decline."}
   {:id "miner" :card :catalog/original-expansion-g-40-miner :name "Miner" :players 2
    :description "Your last worker just used Day Laborer. Click one marked Stone action space to take its entire pile in Returning Home, without placing another worker, or decline."}
   {:id "opportunist" :card :catalog/original-expansion-g-43-opportunist :name "Opportunist" :players 4
    :description "Place your last worker on Clay Pit. Nobody has taken clay this work phase, so choose the extra clay after collecting the pile. Card bonuses never count as earlier action-space collections."}
   {:id "pilgrim" :card :catalog/original-expansion-g-45-pilgrim :name "Pilgrim" :players 2
    :description "A new round begins with no food or building materials in your supply. Choose a building-resource icon to receive it together with one food. The card ability can return unwanted supply resources."}
   {:id "pond-watchman" :card :catalog/original-expansion-g-46-pond-watchman :name "Pond Watchman" :players 4
    :description "Work has ended with your fisherman on Fishing. Select that worker, then click a free wood accumulation space to move there for one food and collect its pile. Declining costs nothing."}
   {:id "animal-farm" :card :catalog/original-expansion-g-62-animal-farm :name "Animal Farm" :players 2
    :description "Your last family member has acted, but Animal Farm offers one final guest. Click the sheep or boar to return it for a guest this round, or decline. The guest can then take a normal action."}
   {:id "bountiful-harvest" :card :catalog/original-expansion-g-67-bountiful-harvest :name "Bountiful Harvest" :players 2
    :description "Two crop stacks have been harvested. Feed both players, then breed a sheep: one food is stored on Bountiful Harvest. It counts as supply and can pay costs or feeding; unused food scores one point each."}
   {:id "chronicle" :card :catalog/original-expansion-g-68-chronicle :name "Chronicle" :players 2
    :description "Chronicle has placed a food marker on every available major improvement. Build any marked major; Chronicle receives that food immediately, even if another player builds it."}
   {:id "dunghill" :card :catalog/original-expansion-g-72-dunghill :name "Dunghill" :players 2
    :description "The harvest is about to begin with three animal types. Click one or two planted farm spaces to add a matching crop before harvesting, or decline. Planted cards are supported too."}
   {:id "alternative-designer" :card :catalog/original-expansion-g-1-alternative-designer :name "Alternative Designer" :players 2
    :description "Use Farm Expansion to add a wooden room. Click the material icons to substitute up to two clay and two stone for wood in that room. Fences and stables allow unrestricted one-for-one substitutions."}
   {:id "fish-seller" :card :catalog/original-expansion-g-17-fish-seller :name "Fish Seller" :players 3
    :description "Fishing just supplied four food. Click up to two goods to buy with that pile food: building materials cost one, grain or sheep or boar two, and vegetables or cattle three. Clear an item to change the purchase, or decline."}
   {:id "stonepiler" :card :catalog/original-expansion-g-53-stonepiler :name "Stonepiler" :players 2
    :description "Use Fences to draw a single-space pasture. Click the stone payment option: two stone replace its four wood. Odd wood costs may be rounded up when paid with stone."}
   {:id "vegetarian" :card :catalog/original-expansion-g-57-vegetarian :name "Vegetarian" :players 2
    :description "Convert grain and vegetables using the pictured food exchanges. Vegetarian adds one food per converted crop and prevents every animal-to-food exchange for the rest of the game."}
   {:id "bartering-hut" :card :catalog/original-expansion-g-64-bartering-hut :name "Bartering Hut" :players 2
    :description "Choose up to two animals, then click building-resource icons to pay their pictured prices. The pasture can hold two sheep. Confirm to receive the animals and pass Bartering Hut left, or decline the purchase."}
   {:id "corral" :card :catalog/original-expansion-g-70-corral :name "Corral" :players 2
    :description "Take Sheep to house four sheep in the two empty fields beside your home. Each occupied corral scores one bonus point; the fields remain fields and are not pastures."}
   {:id "gimlet" :card :catalog/original-expansion-g-77-gimlet :name "Gimlet" :players 2
    :description "Use Farm Expansion to add a wooden room and two stables. The room costs one less wood and each of the first two stables built this round also costs one less wood."}
   {:id "paddocks" :card :catalog/original-expansion-g-90-paddocks :name "Paddocks" :players 2
    :description "Draw new internal fence edges to split the existing pasture for free. Only edges inside that pasture are allowed, at most four; the card then passes left."}
   {:id "scythe" :card :catalog/original-expansion-g-103-scythe :name "Scythe" :players 2
    :description "Use Sow and Bake and click the already planted grain field to sow more grain on top. Each field can be sown only once in the action, and its crop type cannot change."}
   {:id "sheepdog" :card :catalog/original-expansion-g-108-sheepdog :name "Sheepdog" :players 2
    :description "Feeding has begun with sheep housed on unused farm spaces. Feed your family and dog: the dog requires one additional food. Only sheep may be kept in your home."}
   {:id "bee-keeper" :card :catalog/original-expansion-wm-5-bee-keeper :name "Bee Keeper" :players 2
    :description "Click an empty farm space to place a free stable on its side. It does not house animals or multiply pasture capacity, but remains a built stable and provides one food each feeding phase."}
   {:id "cowboy" :card :catalog/original-expansion-wm-13-cowboy :name "Cowboy" :players 3
    :description "Partner occupies Cattle. Click that occupied space to receive one cattle with your worker. Cowboy also supplies a two-animal holding space of one species that still counts as unused."}
   {:id "hunter" :card :catalog/original-expansion-wm-32-hunter :name "Hunter" :players 2
    :description "You just received sheep. Click the pictured conversion to cook some or all of these newly received animals without an improvement, or decline. Previously owned animals and ordinary breeding offspring are excluded."}
   {:id "pawnbroker" :card :catalog/original-expansion-wm-39-pawnbroker :name "Pawnbroker" :players 2
    :description "Open Pawnbroker and click your Well to exchange all four printed points for twelve food. The Well keeps its effects but cannot be returned or removed, and its lost points stay lost even without Pawnbroker."}
   {:id "village-fool" :card :catalog/original-expansion-wm-53-village-fool :name "Village Fool" :players 2
    :description "The next round is starting. Click a hand card to place it permanently face down under Village Fool, or decline. Stored cards score except the third, fifth, seventh and ninth."}
   {:id "bonfire" :card :catalog/original-expansion-wm-64-bonfire :name "Bonfire" :players 2
    :description "Click an unused farm space for the wood marker. Later open Bonfire to remove it and enable the pictured animal cooking rates for the rest of that round; the space becomes unused again."}
   {:id "raspberries" :card :catalog/original-expansion-wm-103-raspberries :name "Raspberries" :players 2
    :description "Click up to three unused farm spaces to mark them with food. Plow one of those marked spaces afterward and observe its food return to your supply."}
   {:id "reed-nursery" :card :catalog/original-expansion-wm-104-reed-nursery :name "Reed Nursery" :players 2
    :description "Use Sow and Bake, choose reed, and click empty fields to plant reed stacks. Reed harvests like grain but remains reed. Its supply and planted pieces can be exchanged for points at game end."}
   {:id "storage-shed" :card :catalog/original-expansion-wm-110-storage-shed :name "Storage Shed" :players 2
    :description "Click your built stable to store it permanently on the card. Then play Shovel and choose one building resource before paying its cost; that resource can fund the improvement."}
   {:id "wine-closet" :card :catalog/original-expansion-wm-114-wine-closet :name "Wine Closet" :players 2
    :description "Two vegetables scheduled five rounds ago are arriving. Choose how many become points; each remaining vegetable becomes two food. Later, open Wine Closet and click a future round to schedule another vegetable; choosing at least five rounds ahead adds a second."}
   {:id "five-rings" :card :catalog/original-expansion-fr-20-five-rings :name "Five Rings" :players 2
    :description "Play Five Rings at Meeting Place and choose its wood or stone cost. The matching marker determines the discount on every later improvement; the card does not discount itself."}
   {:id "grain-mill-oven" :card :catalog/original-expansion-fr-25-grain-mill-oven :name "Grain Mill Oven" :players 2
    :description "Use Meeting Place to build the Clay Oven as a minor improvement. Return Grain Mill Oven instead of paying the oven price, then optionally bake. Grain Mill Oven itself has no baking capacity."}
   {:id "boatswain" :card :catalog/original-expansion-fr-66-boatswain :name "Boatswain" :players 2
    :description "Fishing has finished. Click empty physical fields to place one grain from the supply in each, or decline. This plants the fields without performing a sow action or spending your grain."}
   {:id "fiddler" :card :catalog/original-expansion-fr-81-fiddler :name "Fiddler" :players 2
    :description "It is the final worker action of an even round, and you have received no building resources. Take Grain Seeds and observe one wood plus one food at round end."}
   {:id "harvester" :card :catalog/original-expansion-fr-85-harvester :name "Harvester" :players 2
    :description "The final harvest is about to begin with five planted fields. Take Grain Seeds with the last worker: all five harvested goods count toward the shared three-point award."}
   {:id "head-of-the-revolution" :card :catalog/original-expansion-fr-86-head-of-the-revolution :name "Head Of The Revolution" :players 3
    :description "Your farm has one unused space and was eligible when this card was played. Click Farmland and plow the last space to claim the first-completion award of three points."}
   {:id "manual-labourer" :card :catalog/original-expansion-fr-91-manual-labourer :name "Manual Labourer" :players 2
    :description "You received only wood among building-resource types this round. Take Grain Seeds with the final worker and observe one additional wood at round end."}
   {:id "mastermind" :card :catalog/original-expansion-fr-93-mastermind :name "Mastermind" :players 2
    :description "Use Major Improvement to build Joinery, which carries a bonus-point symbol. Mastermind records one bonus point for that play; returning and replaying a qualifying card can count again."}
   {:id "sailboat-constructor" :card :catalog/original-expansion-fr-106-sailboat-constructor :name "Sailboat Constructor" :players 2
    :description "The harvest field phase is starting. Click resource stacks to buy them in order at increasing food prices, or decline. Unbought stacks remain on the card for later harvests."}
   {:id "stroller" :card :catalog/original-expansion-fr-110-stroller :name "Stroller" :players 2
    :description "Build a stack of six resource icons, including at least one wood, clay, reed and stone. Reorder it directly before confirming; each harvest field phase delivers its top resource."}
   {:id "reed-nursery-scoring" :card :catalog/original-expansion-wm-104-reed-nursery :name "Reed Nursery · final scoring" :players 2
    :description "Final scoring is paused for Reed Nursery. Click reed in your supply and directly on the planted fields to allocate exactly one, four, six or eight reed for one, two, three or four points. Try one supply reed plus three from one field, leaving the other field untouched, or decline."}
   {:id "workshop-combinations" :card :catalog/original-expansion-g-94-potter-s-wheel :name "Workshop combinations" :players 2
    :description "Feeding is paused with Pottery, Joinery, Potter’s Wheel and Artisan. Select Pottery first to preview one clay for five food, then Joinery for two food. Reverse the order to preview Joinery for four and Pottery for three. Artisan adds two food only once this harvest; both orders total seven food."}
   {:id "animal-farmer" :card :catalog/original-expansion-g-2-animal-farmer :name "Animal Farmer" :players 2
    :description "Take Sheep to fill the second pasture. Both pastures then earn one point and the all-full bonus earns one more; animal housing and stable capacity matter."}
   {:id "artisan" :card :catalog/original-expansion-g-6-artisan :name "Artisan" :players 2
    :description "Feeding has begun. Click Pottery to convert one clay to food and receive two additional food. Artisan pays at most once per harvest, even if another workshop is also used."}
   {:id "resource-collector" :card :catalog/original-expansion-g-47-resource-collector :name "Resource Collector" :players 2
    :description "Arrange the four building-resource icons onto the next four rounds. Confirm the order and inspect the future delivery markers."}
   {:id "understudy" :card :catalog/original-expansion-g-56-understudy :name "Understudy" :players 2
    :description "Your first person already occupies Farmland. Click that space again and plow next to your first field with your second person. A space with two of your people cannot be reused."}
   {:id "beehive-oven" :card :catalog/original-expansion-g-65-beehive-oven :name "Beehive Oven" :players 2
    :description "Choose a pictured baking exchange or decline. Your Clay Oven alone bakes one grain for six food including the Beehive bonus. You can instead bake two grain, one in each oven, for nine food; using Beehive excludes its extra-food bonus."}
   {:id "cow-bell" :card :catalog/original-expansion-g-71-cow-bell :name "Cow Bell" :players 2
    :description "Take four Cattle from the board. Cow Bell houses them on unused farm spaces, at most two per space and four total; those spaces still count as unused for scoring."}
   {:id "hammer" :card :catalog/original-expansion-g-79-hammer :name "Hammer" :players 2
    :description "Draw fences on your farm to form a pasture. Partner already has fences and receives one wood from you. Hammer passes left after this immediate fencing action."}
   {:id "potters-wheel" :card :catalog/original-expansion-g-94-potter-s-wheel :name "Potter's Wheel" :players 2
    :description "Feeding has begun. Click Pottery to convert one clay into three food with the wheel bonus. Pottery can also be bought through a minor-improvement action."}
   {:id "remodeling" :card :catalog/original-expansion-g-101-remodeling :name "Remodeling" :players 2
    :description "Click the stable to return it, then click a legal farm space to add a free wooden room. Only the printed wood-and-reed card cost is paid; the card then passes left."}
   {:id "seasonal-plants" :card :catalog/original-expansion-g-104-seasonal-plants :name "Seasonal Plants" :players 2
    :description "You just sowed grain and vegetables in an even-numbered round. Click either newly sown field to add one extra resource of its crop type."}
   {:id "fence-dealer" :card :catalog/original-expansion-wm-21-fence-dealer :name "Fence Dealer" :players 2
    :description "Use Fences and draw a four-edge pasture to pay two wood. Fence Dealer allows at most twelve built fences and charges one wood per pair, rounded up."}
   {:id "livestock-keeper" :card :catalog/original-expansion-wm-34-livestock-keeper :name "Livestock Keeper" :players 2
    :description "Breeding is beginning with two sheep in a stable pasture. Click sheep to pay one food for two offspring instead of one, or decline. Only one animal type receives the extra offspring."}
   {:id "stable-deliveryman" :card :catalog/original-expansion-wm-45-stable-deliveryman :name "Stable Deliveryman" :players 2
    :description "Click the future round spaces to reserve up to four physical stables. On their arrival rounds, click a farm space and pay one wood, or decline to return the reserved piece."}
   {:id "sunrise-admirer" :card :catalog/original-expansion-wm-49-sunrise-admirer :name "Sunrise Admirer" :players 2
    :description "At round start, pay two food and click an unused space in the easternmost column to plow. Declining discards Sunrise Admirer and cancels its later opportunities."}
   {:id "apple-tree" :card :catalog/original-expansion-wm-58-apple-tree :name "Apple Tree" :players 2
    :description "Click an empty field to plant the apple tree. The field stays a field but cannot be sown or otherwise used; each harvest field phase grants one food."}
   {:id "backyard-well" :card :catalog/original-expansion-wm-62-backyard-well :name "Backyard Well" :players 2
    :description "Open Backyard Well and click an unused farm space beside your home. A stone marks that space as used and one food is scheduled for each of the next three rounds. This ability is once per game."}
   {:id "changing-plans" :card :catalog/original-expansion-wm-69-changing-plans :name "Changing Plans" :players 2
    :description "Click one or both stables to return them, then click the same number of legal spaces to plow. The returned stable spaces may become fields; the card then passes left."}
   {:id "gold-medal" :card :catalog/original-expansion-wm-87-gold-medal :name "Gold Medal" :players 2
    :description "Click a scoring-category icon. At game end, every player tied for the highest score in that category receives two bonus points."}
   {:id "pig-wallow" :card :catalog/original-expansion-wm-99-pig-wallow :name "Pig Wallow" :players 2
    :description "Click the empty field to remove it, then click a space for a free single-space pasture. Normal fence adjacency still applies and Pig Wallow passes left."}
   {:id "wild-breeding" :card :catalog/original-expansion-wm-113-wild-breeding :name "Wild Breeding" :players 2
    :description "An animal was added to every accumulation containing at least two animals. Click an eligible animal action space to take one added animal, or decline. This passing card does not grant a worker action."}
   {:id "absinthe" :card :catalog/original-expansion-fr-2-absinthe :name "Absinthe" :players 2
    :description "The next round has reached its Work phase. Click an action space to place the scheduled food there; the next person using it receives that food."}
   {:id "artists-garden" :card :catalog/original-expansion-fr-5-artists-garden :name "Artists Garden" :players 2
    :description "People are returning home. Click the planted grain field to harvest one crop now, or decline. This is a real extra field harvest before any regular harvest."}
   {:id "chocolade" :card :catalog/original-expansion-fr-14-chocolate :name "Chocolade" :players 2
    :description "Chocolade holds four food. Use Lessons to play Architect and observe the occupation fee come from the card before your personal food. Its stored food cannot feed your family."}
   {:id "straw-thatched-hut" :card :catalog/original-expansion-fr-46-straw-thatched-hut :name "Straw-Thatched Hut" :players 2
    :description "You have a clay hut and two plowed fields. Use Farm Expansion, click a legal room space, and choose the pictured two-clay, one-grain, one-food room price."}
   {:id "animal-welfarist" :card :catalog/original-expansion-fr-62-animal-welfarist :name "Animal Welfarist" :players 4
    :description "This round you built stables and fences on their proper action spaces. Take Grain Seeds with the final worker and observe one sheep, boar, and cattle arrive as people return home."}
   {:id "benefactor" :card :catalog/original-expansion-fr-65-benefactor :name "Benefactor" :players 3
    :description "Four fenced pastures and one sheep are ready. Open Benefactor, click the sheep to return it, then click a legal space for a free wooden room. This exchange is once per game."}
   {:id "cabbage-lover" :card :catalog/original-expansion-fr-67-cabbage-lover :name "Cabbage Lover" :players 4
    :description "You just converted one vegetable to food. Click the pictured clay payment to store one clay for one bonus point, or decline. Stored clay scores at most five points."}
   {:id "country-doctor" :card :catalog/original-expansion-fr-74-country-doctor :name "Country Doctor" :players 2
    :description "Three fields are planted, including a vegetable field, and your two rooms are full. Use Family Growth to grow without a spare room once this game."}
   {:id "martial-artist" :card :catalog/original-expansion-fr-92-martial-artist :name "Martial Artist" :players 4
    :description "The harvest is about to start. Click up to two minor-improvement card faces to discard for two food each, or decline. Discarded cards are removed from your hand."}
   {:id "parquet-setter" :card :catalog/original-expansion-fr-97-parquet-setter :name "Parquet Setter" :players 2
    :description "Two previously unused farm spaces became fields this round. Take Grain Seeds with the final worker and observe one wood plus one food arrive at Returning Home."}
   {:id "bakers-daughter" :card :catalog/original-expansion-g-9-baker-s-daughter :name "Baker's Daughter" :players 3
    :description "Partner has played Baker. Accept the free play of Baker's Daughter, then choose whether to bake with your Clay Oven. Her shared Grain Seeds and Sow and Bake spaces remain usable when opponents occupy them."}
   {:id "carrot-cake-baker" :card :catalog/original-expansion-g-11-carrot-cake-baker :name "Carrot Cake Baker" :players 2
    :description "One vegetable has just been converted to two food. Bake grain with your Clay Oven using the pictured exchange, or decline. Other vegetable-to-food conversions also trigger this baking offer."}
   {:id "freeloader" :card :catalog/original-expansion-g-20-freeloader :name "Freeloader" :players 3
    :description "This is your first placement of the round. Partner occupies Farmland: click it and plow a field on your own farm. Your first person cannot use Meeting Place, even while it is empty."}
   {:id "grain-lover" :card :catalog/original-expansion-g-27-grain-lover :name "Grain Lover" :players 3
    :description "Round seven leaves seven full rounds. Click an empty farm space to plow, then optionally sow that field with grain from your supply. Players tied for the most total grain earn two points, including planted grain."}
   {:id "inventor" :card :catalog/original-expansion-g-34-inventor :name "Inventor" :players 2
    :description "Build the Clay Oven using Major Improvement. Its printed four-resource cost qualifies for a one-resource discount; click clay or stone in the payment preview to choose which resource to save."}
   {:id "pearl-seeker" :card :catalog/original-expansion-g-44-pearl-seeker :name "Pearl Seeker" :players 2
    :description "Fishing has supplied four food plus one automatic stone. Choose how many additional stone to buy for two food each, or decline. Only food from the pile funds these purchases, not other fishing bonuses."}
   {:id "stone-trader" :card :catalog/original-expansion-g-52-stone-trader :name "Stone Trader" :players 4
    :description "Quarry has just supplied stone. Choose an improvement with a printed stone cost, paying one food plus its normal price, or decline. Cards without a printed stone cost are excluded."}
   {:id "birthstone" :card :catalog/original-expansion-g-66-birthstone :name "Birthstone" :players 2
    :description "Your family has just grown. Pay one stone for one point using the pictured exchange, or decline. Playing Birthstone after growth in the same round also offers that exchange."}
   {:id "meat-pie" :card :catalog/original-expansion-g-88-meat-pie :name "Meat Pie" :players 2
    :description "One grain has just been baked after playing Meat Pie. Choose a pictured grain-and-animal exchange for three points, or decline. Only one exchange is allowed per baking action."}
   {:id "pine-forest" :card :catalog/original-expansion-g-92-pine-forest :name "Pine Forest" :players 2
    :description "Four wood have accumulated on Pine Forest and your supply is empty. At round start, accept another stored wood to collect all five automatically, or decline and leave four stored. This choice precedes other round-start deliveries."}
   {:id "bargaining-baker" :card :catalog/original-expansion-wm-4-bargaining-baker :name "Bargaining Baker" :players 2
    :description "Your grain supply is empty. Build the Clay Oven using Major Improvement: choose a one-resource discount, and receive one grain before construction. Other bread-symbol improvements give grain, but only ovens get the discount."}
   {:id "covetous-farmer" :card :catalog/original-expansion-wm-12-covetous-farmer :name "Covetous Farmer" :players 3
    :description "It is your turn. Your reed supply is empty while every opponent has reed. Open Covetous Farmer and click reed to receive one. The ability is limited to once per round and resources missing from your supply."}
   {:id "diplomat" :card :catalog/original-expansion-wm-16-diplomat :name "Diplomat" :players 3
    :description "Diplomat was played early, so two permissions are available in round fourteen. Partner occupies Farmland: click it to plow on your own farm, spending one permission. Your own occupied spaces remain unavailable."}
   {:id "farm-school-graduate" :card :catalog/original-expansion-wm-20-farm-school-graduate :name "Farm School Graduate" :players 3
    :description "The first harvest is starting. Click an occupation face to play it for two food, or decline. Farm School Graduate was your first occupation and blocks occupation actions on shared action spaces."}
   {:id "gem-hunter" :card :catalog/original-expansion-wm-24-gem-hunter :name "Gem Hunter" :players 2
    :description "You have taken a three-clay accumulation in round three. Accept one stone by returning one clay to the action space, or decline. After round nine, or with a smaller pile, no clay needs to be left behind."}
   {:id "wheelbarrow-pusher" :card :catalog/original-expansion-wm-54-wheelbarrow-pusher :name "Wheelbarrow Pusher" :players 4
    :description "You took exactly one reed from its accumulation space. Click a building resource to receive it together with one food. In a three-player game the reward would instead be a resource or food."}
   {:id "wood-worker" :card :catalog/original-expansion-wm-55-wood-worker :name "Wood Worker" :players 3
    :description "Grain Seeds has finished. Pay one food onto that action space to receive one wood, or decline. The next player using Grain Seeds receives the marked food; this wood cannot fund the action already completed."}
   {:id "attic" :card :catalog/original-expansion-wm-60-attic :name "Attic" :players 2
    :description "A new wooden room has just been built. Click that new room to place one wood from your supply there for one point, or decline. Existing rooms cannot be selected; removing a marked room later loses its point."}
   {:id "clockwork-man" :card :catalog/original-expansion-wm-70-clockwork-man :name "Clockwork Man" :players 2
    :description "Play Clockwork Man at Meeting Place. It counts as both an occupation and a minor improvement and earns your Tutor one point. It must not trigger occupation-play income or other occupation-play effects."}
   {:id "honeymoon" :card :catalog/original-expansion-wm-90-honeymoon :name "Honeymoon" :players 2
    :description "Round fourteen has begun with three family members, but Honeymoon leaves two at home. You have one worker to place: take Grain Seeds and observe that no further ordinary placement remains. The card is worth five points."}
   {:id "chameleon" :card :catalog/original-expansion-fr-13-chameleon :name "Chameleon" :players 2
    :description "A stable pasture holds three sheep and the house pet is a cattle. Play Chameleon at Meeting Place: pay one sheep and receive one boar, which shares the pasture with the two remaining sheep within normal capacity."}
   {:id "goblet" :card :catalog/original-expansion-fr-23-goblet :name "Goblet" :players 2
    :description "Use Meeting Place to build the Well as a minor improvement at its normal cost. Goblet places two food on each of the next five rounds. Playing Goblet after already building the Well does not increase existing deliveries."}
   {:id "hay-rake" :card :catalog/original-expansion-fr-29-hay-rake :name "Hay Rake" :players 2
    :description "Your first person used Farmland and your second took Grain Seeds. Click your own occupied Farmland with your third person to plow beside the first field. Hay Rake works only for that third placement."}
   {:id "rock-pyramid" :card :catalog/original-expansion-fr-42-rock-pyramid :name "Rock Pyramid" :players 2
    :description "You just paid stone to build one stone room. Click one or two empty farm spaces to plow, or decline. A free stone room permits one field; multiple rooms may permit more fields."}
   {:id "tuileries-garden" :card :catalog/original-expansion-fr-54-tuileries-garden :name "Tuileries Garden" :players 2
    :description "Farmland has just been used. Choose the pictured grain or vegetable, receive it from the supply, and sow it by clicking a field. You may decline before choosing a crop; selecting it commits to sowing that crop."}
   {:id "winter-deadwood" :card :catalog/original-expansion-fr-58-winter-deadwood :name "Winter Deadwood" :players 2
    :description "Choose either the pictured one-wood payment for one point, keeping the card, or receive one wood and pass Winter Deadwood to Partner. Only the receive-wood choice passes the card."}
   {:id "award-winner" :card :catalog/original-expansion-fr-64-award-winner :name "Award Winner" :players 2
    :description "You have just paid wood to play Shovel. Click the wood icon to pay one additional wood for one point, or decline. Only a building resource actually paid for that improvement is eligible, once per round."}
   {:id "cattle-dealer" :card :catalog/original-expansion-fr-70-cattle-dealer :name "Cattle Dealer" :players 4
    :description "One new pasture covering three farm spaces has just been fenced. Pay the pictured reed for two cattle, or decline. Making several new qualifying pastures can offer several pairs; simply reshaping one does not qualify."}
   {:id "gardening-enthusiast" :card :catalog/original-expansion-fr-82-gardening-enthusiast :name "Gardening Enthusiast" :players 3
    :description "The game is paused before final scoring. Click grain and vegetable icons to return either or both loaned crops, then confirm. Each unreturned crop costs one point; submitting a partial repayment closes the loan."}
   {:id "sculptors-son" :card :catalog/original-expansion-fr-107-sculptors-son :name "Sculptors Son" :players 3
    :description "You own Joinery, Pottery, and Basketmaker's Workshop. Take Forest to receive its wood plus two additional wood automatically. Clay and reed actions similarly reward their matching workshops; mixed source goods do not qualify."}
   {:id "animal-holder" :card :catalog/original-expansion-g-3-animal-holder :name "Animal Holder" :players 3
    :description "Play Animal Holder with Lessons in round six to gain three wood. You and Partner tie for the most animals and both receive three bonus points."}
   {:id "apprentice" :card :catalog/original-expansion-g-4-apprentice :name "Apprentice" :players 3
    :description "Take Grain Seeds with the last worker to begin harvest. Your Well and Clay Oven each supply one food before crops are harvested; the Fireplace supplies none."}
   {:id "glutton" :card :catalog/original-expansion-g-23-glutton :name "Glutton" :players 2
    :description "Your family has been fed in the final harvest. Choose the pictured three-food-per-point exchange, up to six points, or decline. Food for your family is already paid."}
   {:id "hobbyist" :card :catalog/original-expansion-g-32-hobbyist :name "Hobbyist" :players 3
    :description "Partner has played an occupation. Click a minor improvement for a one-food entry fee, or a major for two food, then pay its normal cost; either offer may be declined."}
   {:id "ringmaster" :card :catalog/original-expansion-g-48-ringmaster :name "Ringmaster" :players 4
    :description "Traveling Players has supplied its food. Play a minor at normal cost or a major with one extra grain, using the pictured card choices; or decline."}
   {:id "wintercrafter" :card :catalog/original-expansion-g-60-wintercrafter :name "Wintercrafter" :players 2
    :description "The field phase has ended. Play a minor at normal cost or a major for two extra food before feeding, or decline."}
   {:id "holiday-present" :card :catalog/original-expansion-g-83-holiday-present :name "Holiday Present" :players 2
    :description "Choose the pictured sheep, wild boar, or cattle, or discard your Begging card. The one-wood price is already paid. Holiday Present then passes to Partner."}
   {:id "horse-barn" :card :catalog/original-expansion-g-84-horse-barn :name "Horse Barn" :players 2
    :description "Take Sow and Bake and click both empty fields to sow grain. The field between two stables receives two extra grain; the field beside one stable receives one. Fenced and unfenced stables count alike."}
   {:id "pumpkin-pie" :card :catalog/original-expansion-g-98-pumpkin-pie :name "Pumpkin Pie" :players 2
    :description "Bread has just been baked using the on-play baking action. Pay the pictured extra grain and vegetable for three points, or decline. The card also gives one printed point."}
   {:id "fish-farm" :card :catalog/original-expansion-g-115-fish-farm :name "Fish Farm" :players 2
    :description "Partner has taken Fishing. Click a grain or vegetable crop directly on each field you want to collect from, at most one crop per field, then confirm; or decline. This is not a harvest."}
   {:id "bread-carrier" :card :catalog/original-expansion-wm-8-bread-carrier :name "Bread Carrier" :players 2
    :description "Partner is about to build the first oven on Major Improvement. Build the Clay Oven: Bread Carrier places one food on every remaining round for you, only once even if more ovens are built."}
   {:id "engineer" :card :catalog/original-expansion-wm-17-engineer :name "Engineer" :players 3
    :description "Your food supply is empty. Play Itinerant Laborers at Meeting Place: Engineer provides its one-food price before payment. Improvements with a printed stone cost instead provide two food."}
   {:id "heiress" :card :catalog/original-expansion-wm-27-heiress :name "Heiress" :players 2
    :description "Round six is starting. Click one empty farm space adjacent to your wooden house to build a room at normal cost, or decline. The round-ten clay and round-thirteen stone room markers remain on the round track."}
   {:id "hoarder" :card :catalog/original-expansion-wm-30-hoarder :name "Hoarder" :players 2
    :description "Feeding is starting. Click a building resource to store it on Hoarder, or decline. Different stored types score one, two, three, or five points; stored resources cannot be spent."}
   {:id "master-stableman" :card :catalog/original-expansion-wm-37-master-stableman :name "Master Stableman" :players 2
    :description "You selected the two-sheep accumulation. Before receiving the animals, click up to two farm spaces to build stables for one wood each, or decline. The animals arrive after this decision."}
   {:id "tapestry-weaver" :card :catalog/original-expansion-wm-50-tapestry-weaver :name "Tapestry Weaver" :players 2
    :description "Three sheep are present at the start of the field phase. Click an empty room to place scoring food from the general supply. Each room can hold one marker for this card, and the food cannot be spent."}
   {:id "cookies" :card :catalog/original-expansion-wm-71-cookies :name "Cookies" :players 2
    :description "One grain has just been baked. Click a room to place one food from your supply there for one point, or decline. Future baking can fill other rooms but cannot reuse a marked room."}
   {:id "crib" :card :catalog/original-expansion-wm-72-crib :name "Crib" :players 2
    :description "Your family grew earlier this work phase. Choose two food from the supply or pay two food for one permanent point, using the pictured exchange; or decline."}
   {:id "friendly-pastures" :card :catalog/original-expansion-wm-84-friendly-pastures :name "Friendly Pastures" :players 2
    :description "Two sheep occupy a pasture with a stable, and a cattle is the house pet. Take Wild Boar: the stabled pasture can now house sheep and boar together, within its normal four-animal capacity. A third species cannot share it."}
   {:id "home-garden" :card :catalog/original-expansion-wm-89-home-garden :name "Home Garden" :players 2
    :description "Renovate your wooden house to clay. One food is automatically placed on each of the next three rounds. Playing Home Garden after renovating in the same round also schedules the food."}
   {:id "evening-prayer" :card :catalog/original-expansion-fr-19-evening-prayer :name "Evening Prayer" :players 2
    :description "With two fields and no played occupations, Evening Prayer offers two occupations for one food each. Click each card face to play it; the second offer waits for the first card to finish."}
   {:id "golden-rose" :card :catalog/original-expansion-fr-24-golden-rose :name "Golden Rose" :players 2
    :description "Your food supply is empty and one occupation is played. Use Lessons to play Architect: Golden Rose removes the normal one-food occupation fee. It also discounts food paid for minor improvements, up to two."}
   {:id "heatwave" :card :catalog/original-expansion-fr-31-heatwave :name "Heatwave" :players 2
    :description "The stone price has been paid in your clay hut. Click a Fireplace to build it free, or upgrade an owned Fireplace to a Cooking Hearth. A Hearth without returning a Fireplace is not offered. Heatwave then passes to Partner."}
   {:id "kids-corner" :card :catalog/original-expansion-fr-33-kids-corner :name "Kids Corner" :players 2
    :description "A sheep already lives in your house. Take Wild Boar: Kids Corner supplies an additional house-pet space, allowing both species in the house."}
   {:id "march" :card :catalog/original-expansion-fr-36-marsh :name "March" :players 2
    :description "Click the pictured pair of different animals, then click two empty farm spaces to plow them. Payment and both fields resolve together; decline before confirming to keep the animals."}
   {:id "trees-for-the-citizens" :card :catalog/original-expansion-fr-52-trees-for-the-citizens :name "Trees for the Citizens" :players 2
    :description "Play Trees for the Citizens at Meeting Place. Your Joinery waives its three-wood cost. Improvements with a printed wood cost each count toward its one-point-per-pair bonus, including this card."}
   {:id "drawing-genius" :card :catalog/original-expansion-fr-78-drawing-genius :name "Drawing Genius" :players 2
    :description "You just used the most recently revealed Round card to build a Fireplace. Choose another major or minor improvement at its normal cost, or decline."}
   {:id "lemon-trader" :card :catalog/original-expansion-fr-90-lemon-trader :name "Lemon Trader" :players 3
    :description "Open Lemon Trader and click a grain-to-one-resource or vegetable-to-two-different-resources exchange. You may exchange twice this round; the limit resets next round."}
   {:id "pasteurization-expert" :card :catalog/original-expansion-fr-98-pasteurization-expert :name "Pasteurization Expert" :players 3
    :description "Take Sheep: receiving a sheep removes the top sheep from the visible card stack and adds it to your animals. Wild boar is next. Breeding does not trigger this card."}
   {:id "reformer" :card :catalog/original-expansion-fr-105-reformer :name "Reformer" :players 2
    :description "Two sheep are already housed with your two occupations and house pet space. Take Cattle: Reformer allows the third animal on the remaining occupation. Each actual occupation card holds one animal."}
   {:id "fishermans-apprentice" :card :catalog/original-expansion-g-18-fisherman-s-apprentice :name "Fisherman's Apprentice" :players 3
    :description "Partner has taken Fishing. Click an occupation face to play it, paying one food to Partner instead of a lesson fee, or decline."}
   {:id "glass-blower" :card :catalog/original-expansion-g-22-glass-blower :name "Glass Blower" :players 3
    :description "During feeding, click one clay for three food or two clay for five food. Only one exchange is allowed this harvest, and an oven is required."}
   {:id "grafter" :card :catalog/original-expansion-g-24-grafter :name "Grafter" :players 2
    :description "All but the last player have fed. Feed Partner to start breeding: your supply automatically gains one grain and one vegetable. Planted crops do not count toward the two-crop thresholds."}
   {:id "grain-farmer" :card :catalog/original-expansion-g-26-grain-farmer :name "Grain Farmer" :players 2
    :description "Take Grain Seeds with the final worker to start harvest. Seven grain planted on three fields earn three food before any crops are harvested."}
   {:id "grump" :card :catalog/original-expansion-g-28-grump :name "Grump" :players 3
    :description "Play Grump using Lessons in round six to gain three wood. Players tied for the smallest family each receive two bonus points, including opponents."}
   {:id "helper" :card :catalog/original-expansion-g-31-helper :name "Helper" :players 3
    :description "Partner has used Build Rooms. Click that occupied space and build a room on your own farm. Helper also works on renovation and major-improvement spaces, but never on a space occupied by your own worker."}
   {:id "truffle-digger" :card :catalog/original-expansion-g-55-truffle-digger :name "Truffle Digger" :players 4
    :description "Two wild boar are housed in a pasture. Take Forest: the wood accumulation gives two extra food automatically. Wood obtained from cards does not trigger this."}
   {:id "village-farmer" :card :catalog/original-expansion-g-58-village-farmer :name "Village Farmer" :players 3
    :description "Play Village Farmer with Lessons. You receive four wood in round one. You and Partner each have two field tiles and tie for the three-point bonus; card fields do not count."}
   {:id "wheedler" :card :catalog/original-expansion-g-59-wheedler :name "Wheedler" :players 2
    :description "Take Day Laborer to receive three food instead of two, or take Fishing for its pile plus one food. Card-generated food receives no additional bonus."}
   {:id "sausage-grinder" :card :catalog/original-expansion-g-102-sausage-grinder :name "Sausage Grinder" :players 2
    :description "During feeding, click the sheep to cook it with your Fireplace: Sausage Grinder increases its food value from two to three. The card also improves other animal-to-food conversions."}
   {:id "animal-buyer" :card :catalog/original-expansion-wm-2-animal-buyer :name "Animal Buyer" :players 3
    :description "The field phase has ended. Click a pictured offer to buy one or two animals, then confirm, or decline. An empty pasture and house pet space provide housing. Purchased pairs can breed this harvest."}
   {:id "cloister-dweller" :card :catalog/original-expansion-wm-10-cloister-dweller :name "Cloister Dweller" :players 2
    :description "Play Cloister Dweller. The rightmost column is all fields and earns one point. Columns must be entirely rooms, entirely fields, or inside one pasture; mixed or incomplete columns give no point."}
   {:id "humble-farmboy" :card :catalog/original-expansion-wm-31-humble-farmboy :name "Humble Farmboy" :players 3
    :description "Play Humble Farmboy for four wood. It counts all played occupations and improvements when awarding three points to every player tied for the fewest cards. Empty-handed opponents qualify."}
   {:id "silo-girl" :card :catalog/original-expansion-wm-44-silo-girl :name "Silo Girl" :players 3
    :description "Renovate your clay hut to stone. Silo Girl automatically places grain on remaining even rounds and vegetables on odd rounds, delivered at round start. Renovating again cannot duplicate the markers."}
   {:id "stone-curator" :card :catalog/original-expansion-wm-46-stone-curator :name "Stone Curator" :players 2
    :description "Take Quarry for its stone and one extra food. Your two-room stone house and two improvements with printed stone costs already earn two bonus points."}
   {:id "tile-maker" :card :catalog/original-expansion-wm-52-tile-maker :name "Tile Maker" :players 3
    :description "Play Tile Maker in round six to gain three clay. Every player living in a clay hut receives two bonus points; wood and stone houses do not qualify."}
   {:id "birdcage" :card :catalog/original-expansion-wm-63-birdcage :name "Birdcage" :players 2
    :description "Feeding is starting. Store one food on Birdcage for one endgame point using the pictured exchange, or decline. Birdcage prevents keeping animals in the house, even with other cards."}
   {:id "free-range-chickens" :card :catalog/original-expansion-wm-83-free-range-chickens :name "Free-Range Chickens" :players 2
    :description "Six food have accumulated while a grain field remains planted. Open the played card and choose how much food to collect using the quantity buttons. Leave three or six food for one or two bonus points."}
   {:id "fruitcake" :card :catalog/original-expansion-wm-85-fruitcake :name "Fruitcake" :players 2
    :description "Take Grain Seeds with the last worker to start harvest. Fruitcake reduces each family member's food requirement by one for this harvest only. The normal requirement returns at the following harvest."}
   {:id "iron-plow" :card :catalog/original-expansion-wm-91-iron-plow :name "Iron Plow" :players 2
    :description "Farmland is selected. Click one additional farm space to plow two fields, then choose whether to sow them. Iron Plow can be used only once; declining leaves the use available."}
   {:id "apple-garden" :card :catalog/original-expansion-fr-4-apple-orchard :name "Apple Garden" :players 2
    :description "Play Apple Garden at Meeting Place. You have neither grain nor vegetables, so it gives two bonus points total. Having both crops removes the bonus; normal missing-crop penalties still apply."}
   {:id "peasants-boutique" :card :catalog/original-expansion-fr-41-peasants-boutique :name "Peasants Boutique" :players 2
    :description "During feeding, exchange one reed for four food using the played card. Basketmaker's Workshop was returned on play. One/three/four reed remaining earn one/two/three points without a second workshop bonus."}
   {:id "the-port-le-havre" :card :catalog/original-expansion-fr-49-the-port-le-havre :name "The Port Le Havre" :players 2
    :description "Bread has just been baked in your Clay Oven. Click the pictured one- or two-clay exchange for the same amount of stone, or decline. The offer happens once per Bake Bread action."}
   {:id "watering-can" :card :catalog/original-expansion-fr-56-watering-can :name "Watering Can" :players 2
    :description "Play Watering Can at Meeting Place. Each planted grain and vegetable field gains one crop, including the planted Bean Field card. Empty fields stay empty, then Watering Can passes to Partner."}
   {:id "art-director" :card :catalog/original-expansion-fr-63-art-director :name "Art Director" :players 4
    :description "Traveling Players has just given its food plus one extra food. Click a building-resource icon to collect the additional resource."}
   {:id "cocotte" :card :catalog/original-expansion-fr-72-cocotte :name "Cocotte" :players 2
    :description "Day Laborer has just supplied two food. Click a minor improvement to play it at normal cost, or a major improvement with an additional one-food fee, or decline. The fee is paid only when a legal improvement is built."}
   {:id "dove-hunter" :card :catalog/original-expansion-fr-77-dove-hunter :name "Dove Hunter" :players 4
    :description "During feeding, open Dove Hunter and click sheep for four food. The cattle alternative gives six food. Only one of these exchanges is allowed per harvest, and you may feed without using either."}
   {:id "landscape-gardener" :card :catalog/original-expansion-fr-89-landscape-gardener :name "Landscape Gardener" :players 2
    :description "Choose whether to sow after playing Landscape Gardener. Then click its two illustrated card fields to plant grain or vegetables. These act as fields for sowing and harvesting, but not field points."}
   {:id "stage-star" :card :catalog/original-expansion-fr-109-stage-star :name "Stage Star" :players 4
    :description "Stage Star supplied six wood in stage one. Partner is about to take Traveling Players: one of your wood moves to Partner automatically. No payment is due when your wood supply is empty."}
   {:id "wealthiest-european" :card :catalog/original-expansion-fr-117-wealthiest-european :name "Wealthiest European" :players 4
    :description "This is your first occupation, played in round four. Click building-resource icons to queue three resources, one for each completed round. Mix types freely; remove a queued choice to change it."}
   {:id "reed-sale" :card :catalog/original-expansion-g-100-reed-sale :name "Reed Sale"
    :description "Use Meeting Place to play Reed Sale: pay one reed, receive three food automatically, then pass the card to Partner."}
   {:id "sheepskin-rug" :card :catalog/original-expansion-g-109-sheepskin-rug :name "Sheepskin Rug"
    :description "Play Sheepskin Rug at Meeting Place, paying one sheep. Your Fireplace earns its two-point bonus. Upgrading that Fireplace to a Cooking Hearth later removes the bonus."}
   {:id "sickle" :card :catalog/original-expansion-g-111-sickle :name "Sickle"
    :description "Plow one more field at Farmland. Five actual field tiles plus eight grain and four vegetables (including planted crops) activate Sickle's three-point bonus."}
   {:id "stonecutters-hall" :card :catalog/original-expansion-g-114-stonecutter-s-hall :name "Stonecutter's Hall"
    :description "It is feeding time. Open the played card and click its stone-to-three-food exchange, once this harvest, or feed without using it. Two/four/five remaining stone earn one/two/three bonus points."}
   {:id "tree-house" :card :catalog/original-expansion-g-116-tree-house :name "Tree House"
    :description "Your clay hut has two rooms and two people. Take Family Growth: Tree House provides space for a third person without adding a room tile."}
   {:id "boundary-stones" :card :catalog/original-expansion-wm-65-boundary-stones :name "Boundary Stones"
    :description "Plow the empty space below the central field to make a T shape. Three fields now touch exactly one other field, increasing Boundary Stones' bonus from one to two points."}
   {:id "brazier" :card :catalog/original-expansion-wm-66-brazier :name "Brazier"
    :description "During feeding, open Brazier and click one pictured animal or vegetable exchange. Only one unit of one type may be converted per harvest. You may feed without using it."}
   {:id "glassblowers-shop" :card :catalog/original-expansion-wm-86-glassblowers-shop :name "Glassblowers Shop"
    :description "During feeding, open the played card to exchange one clay for three food. Pottery was returned on play; a repurchased Pottery still converts clay but must not add a second clay-scoring bonus."}
   {:id "lamp-oil" :card :catalog/original-expansion-wm-94-lamp-oil :name "Lamp Oil"
    :description "You have no food. Use Lessons to play Architect: Lamp Oil supplies one food before the one-food occupation fee, so the play is legal and your food stays at zero."}
   {:id "worm-bait" :card :catalog/original-expansion-wm-115-worm-bait :name "Worm Bait"
    :description "Two food are stored from an earlier Farmland action. Take Grain Seeds with the last worker to start harvest: one stored food returns to supply. Next round, Fishing collects the remainder along with its normal food."}
   {:id "flat-hill" :card :catalog/original-expansion-fr-21-flat-hill :name "Flat Hill"
    :description "Four fields are planted. Play Flat Hill at Meeting Place to renovate the wooden hut to clay automatically, without paying clay or reed."}
   {:id "lighthouse" :card :catalog/original-expansion-fr-35-lighthouse :name "Lighthouse"
    :description "Lighthouse was built in a house with exactly two stone rooms. Take Family Growth: it provides space for a third person. Later room extensions do not remove this space."}
   {:id "drinker-of-absinthe" :card :catalog/original-expansion-fr-79-drinker-of-absinthe :name "Drinker of Absinthe"
    :description "Play this occupation using Lessons in round six. One visible food marker is placed on each of rounds seven through fourteen and delivered at the start of that round."}
   {:id "immigrants-son" :card :catalog/original-expansion-fr-87-immigrants-son :name "Immigrants Son"
    :description "Your fifth occupation has just been played. Click an unused farm space to plow for free, or decline. The sixth and seventh occupations offer the same action; playing Immigrants Son itself never does."}
   {:id "village-druid" :card :catalog/original-expansion-fr-116-village-druid :name "Village Druid" :players 4
    :description "Play Architect using Lessons. Village Druid automatically supplies one sheep afterward. Playing Village Druid itself does not award a sheep."}
   {:id "clay-starter" :card :catalog/original-expansion-g-13-clay-starter :name "Clay Starter"
    :description "Use Lessons to play Clay Starter from your hand. Your two-room wooden hut automatically renovates to clay for one additional food, without paying clay or reed."}
   {:id "fence-helper" :card :catalog/original-expansion-g-16-fence-helper :name "Fence Helper" :players 3
    :description "Partner has just built fences. Select fence edges directly on your farm, paying their normal cost plus one food to Partner, or decline. Both payments are pictured."}
   {:id "amazon" :card :catalog/original-expansion-wm-1-amazon :name "Amazon" :players 3
    :description "Take Fishing in round seven to receive one sheep automatically as well as the food. The next table tests the round-thirteen animal choice."}
   {:id "amazon-choice" :card :catalog/original-expansion-wm-1-amazon :name "Amazon · animal choice" :players 3
    :description "Fishing in round thirteen has just supplied food. Click a sheep, wild boar, or cattle icon to choose the extra animal."}
   {:id "apprenticeship" :card :catalog/original-expansion-wm-59-apprenticeship :name "Apprenticeship"
    :description "You have fewer occupations than Partner, and the minor's one-food cost is paid. Click an occupation face to play it without a lesson fee, or decline. Apprenticeship then passes to Partner's hand."}
   {:id "camembert" :card :catalog/original-expansion-fr-12-camembert :name "Camembert"
    :description "Click three revealed action spaces to place one food on each. Occupied spaces, including Meeting Place, are allowed. Click again to undo a selection. The next player to use each space receives its food."}
   {:id "coffee-break" :card :catalog/original-expansion-fr-15-coffee-break :name "Coffee Break"
    :description "Round two has delivered the first food. Four food markers remain on rounds three to six. Play an occupation using Lessons to remove those future food; Flower Pots' round-eight vegetable must remain."}
   {:id "private-market" :card :catalog/original-expansion-g-95-private-market :name "Private Market"
    :description "Open the played card and choose a pictured two-food exchange for wood, clay, reed, or stone. Only one exchange is allowed per round."}
   {:id "grain-market" :card :catalog/original-expansion-g-78-grain-market :name "Grain Market"
    :description "Three grain fields have just been harvested. Exchange one grain for a vegetable using the pictured payment, or decline. This is one offer per harvest, not one per field."}
   {:id "grain-elevator" :card :catalog/original-expansion-wm-88-grain-elevator :name "Grain Elevator"
    :description "Two grain have accumulated on the card, the cap for this table. Take Grain Seeds: receive its one grain plus both stored grain. The next test shows what happens when an opponent takes it."}
   {:id "grain-elevator-opponent" :card :catalog/original-expansion-wm-88-grain-elevator :name "Grain Elevator · opponent"
    :description "Partner is about to act. Take Grain Seeds for Partner: they receive only the space's one grain; both grain on your Elevator return to the general supply."}
   {:id "farm-school" :card :catalog/original-expansion-wm-78-farm-school :name "Farm School"
    :description "Two goods have just been harvested, including wood from Copse. Click an occupation face to play it for two food, or decline. The payment is shown graphically."}
   {:id "brickyard" :card :catalog/original-expansion-fr-11-brickyard :name "Brickyard"
    :description "Three clay are stored, too few to collect. Take Grain Seeds with the last worker: round seven adds the fourth clay. Open Brickyard to collect all four; collecting does not discard it."}
   {:id "journeyman" :card :catalog/original-expansion-fr-88-journeyman :name "Journeyman" :players 3
    :description "A round is starting and your house is stone. Click the pictured one-food exchange for a sheep, wild boar, or cattle, or decline. The chosen animal fits as your house pet."}
   {:id "carrot-farmer" :card :catalog/original-expansion-g-12-carrot-farmer :name "Carrot Farmer"
    :description "Farmland has just been used. Pay the pictured one food to take one of the three vegetables on this card, or decline. The remaining stock stays on the card."}
   {:id "pulley" :card :catalog/original-expansion-g-97-pulley :name "Pulley"
    :description "Day Laborer has just supplied two food. Click one space beside your house to build a room at its normal cost, or decline. The payment is shown before you confirm."}
   {:id "food-connoisseur" :card :catalog/original-expansion-wm-22-food-connoisseur :name "Food Connoisseur" :players 4
    :description "Take Fishing, Traveling Players, or Day Laborer. You automatically receive the top item from the visible stack: reed first, then wood. Receiving food from a card does not count."}
   {:id "oak-cask" :card :catalog/original-expansion-wm-96-oak-cask :name "Oak Cask"
    :description "Take Grain Seeds with the last worker to start harvest. Oak Cask grows from one stored food to two. Open the played card to collect all the food; an emptied cask never refills."}
   {:id "child-care-worker" :card :catalog/original-expansion-fr-71-child-care-worker :name "Child Care Worker" :players 4
    :description "Partner has just grown their family. Choose the pictured wood-for-food or wood-and-reed-for-food exchange, or decline. Your own family growth does not trigger this card."}
   {:id "oceanographer" :card :catalog/original-expansion-fr-96-oceanographer :name "Oceanographer"
    :description "Exactly one field has just been plowed. Take the top resource from the visible stack, or leave it for later. Plowing multiple fields in one action gives no resource."}
   {:id "sheep-market" :card :catalog/original-expansion-g-107-sheep-market :name "Sheep Market"
    :description "Harvest is about to begin. Click a pictured grain, vegetable or sheep exchange, or decline. The exchange happens before crops are harvested."}
   {:id "wooden-bridge" :card :catalog/original-expansion-g-119-wooden-bridge :name "Wooden Bridge"
    :description "Take Reed Bank to receive one extra food, or Day Laborer to receive one extra reed. The bonus is automatic."}
   {:id "edible-roots" :card :catalog/original-expansion-wm-77-edible-roots :name "Edible Roots"
    :description "Take Grain Seeds with the last worker to start harvest. The last grain and vegetable on two fields give one food each; the third field still holds grain and gives no food."}
   {:id "festival-hall" :card :catalog/original-expansion-wm-79-festival-hall :name "Festival Hall"
    :description "Your fields have just been harvested. Exchange one grain for the pictured building resource plus two food, or decline. All four resource choices are available."}
   {:id "abandoned-willow" :card :catalog/original-expansion-fr-1-abandoned-willow :name "Abandoned Willow"
    :description "Click an empty field on your farm to return it and receive four wood. Removing the middle field would disconnect the others, so it cannot be selected. The card then belongs to Partner."}
   {:id "powerhouse" :card :catalog/original-expansion-fr-101-powerhouse :name "Powerhouse" :players 4
    :description "Take Grain Seeds with the last worker to start round two. Five stone in your supply give two food automatically at the start of work, after scheduled resources arrive."}
   {:id "powerhouse-politician" :card :catalog/original-expansion-fr-101-powerhouse :name "Powerhouse + Politician" :players 4
    :description "Round two is prepared, with four stone in supply. Open Politician and click its reed-to-stone exchange, then Begin work: Powerhouse gives two food. Continuing without the exchange gives one food. No farm selection is needed."}
   {:id "seed-spreader" :card :catalog/original-expansion-g-105-seed-spreader :name "Seed Spreader"
    :description "Farmland has just been used. Sow exactly one grain or vegetable by clicking a field, or decline. Copse is also present to check that wood cannot be sown with this effect."}
   {:id "vegetable-scoop" :card :catalog/original-expansion-g-117-vegetable-scoop :name "Vegetable Scoop"
    :description "Take Vegetable Seeds on the shared board: you receive two vegetables automatically. Other vegetable receipts do not trigger the Scoop."}
   {:id "diamond-mine" :card :catalog/original-expansion-wm-73-diamond-mine :name "Diamond Mine"
    :description "Three occupations are already played. Use Meeting Place to play Diamond Mine and see stone markers on the next two rounds."}
   {:id "encircling-wall" :card :catalog/original-expansion-fr-18-encircling-wall :name "Encircling Wall"
    :description "The four-stone cost has been paid. Click a farm space adjoining the existing pasture to fence it without paying wood, or decline."}
   {:id "stone-house-reconstruction" :card :catalog/original-expansion-fr-45-stone-house-reconstruction :name "Stone House Reconstruction"
    :description "Open the played card and renovate the clay house to stone. The normal renovation cost applies and no worker is placed."}
   {:id "swing-plow" :card :catalog/original-base-k-115-swing-plow
    :name "Swing Plow"
    :description "Paused after choosing Plow. Click your farm to choose up to two additional fields, or decline the plow. You control both players."}
   {:id "businesswoman" :card :catalog/original-expansion-g-25-businesswoman :name "Businesswoman"
    :description "Choose up to two improvements, paying each cost. Try two majors, two minors, or one of each; either offer can be declined."}
   {:id "singer" :card :catalog/original-expansion-g-50-singer :name "Singer"
    :description "Click resource icons to queue two building resources. Remove either queued choice or clear both, then take them together. The same resource may be chosen twice."}
   {:id "gardening-tools" :card :catalog/original-expansion-g-76-gardening-tools :name "Gardening Tools"
    :description "Partner has just sown. Pay one food to sow your own empty field, or decline. This interrupts Partner's turn without using your worker."}
   {:id "shovel" :card :catalog/original-expansion-g-110-shovel :name "Shovel"
    :description "Day Laborer has just supplied two food. Sow the prepared field for free, or decline."}
   {:id "debris-dealer" :card :catalog/original-expansion-wm-15-debris-dealer :name "Debris Dealer" :players 3
    :description "You live in clay. Renovate to stone to receive two clay afterward. You control all three players, so you can also test income from their renovations."}
   {:id "herdsman" :card :catalog/original-expansion-wm-28-herdsman :name "Herdsman"
    :description "Day Laborer in round six: choose a sheep or a wild boar. The animal fits as your house pet."}
   {:id "ruins" :card :catalog/original-expansion-wm-106-ruins :name "Ruins"
    :description "At the first harvest, take one of the four stone shown on Ruins, or leave it there. The remaining stock survives later harvests and reloads."}
   {:id "steamer" :card :catalog/original-expansion-wm-108-steamer :name "Steamer" :players 3
    :description "Open your played Steamer to exchange grain with vegetables or animals for food. Pelts is also in play: cooking an animal should offer its bonus."}
   {:id "baguette" :card :catalog/original-expansion-fr-7-baguette :name "Baguette"
    :description "The feeding phase is starting. See the one-wood entry cost and click grain icons to preview the food from your Clay Oven. Confirm the pictured exchange or decline. Wood is paid once per baking action."}
   {:id "barber-shop" :card :catalog/original-expansion-fr-8-barber-shop :name "Barber Shop"
    :description "Round eight: play Barber Shop from your hand via Meeting Place. Its four remaining harvests should award four bonus points immediately."}
   {:id "homework" :card :catalog/original-expansion-fr-32-homework :name "Homework"
    :description "Homework has been paid for. Play up to two occupations without an occupation-action fee. Singer's choices resolve before the second occupation offer."}
   {:id "trailblazer" :card :catalog/original-expansion-fr-113-trailblazer :name "Trailblazer"
    :description "At round start your only farm field is planted. Pay one food and click an adjacent farm space to plow, or decline. The empty Bean Field card does not block this."}
   {:id "foreman" :card :catalog/original-base-k-308-foreman :name "Foreman" :players 4
    :description "Click an action space on the shared board to leave food there, or decline. Occupied spaces can still be marked."}
   {:id "fence-builder" :card :catalog/original-base-i-263-fence-builder :name "Fence Builder" :players 4
    :description "Click a shared action space to place the fence marker. Taking that space later offers the fencing action."}
   {:id "fence-overseer" :card :catalog/original-base-k-312-fence-overseer :name "Fence Overseer" :players 4
    :description "A stable has just been built. Click that farm space to fence it for one food, or decline."}
   {:id "animal-breeder" :card :catalog/original-base-k-307-animal-breeder :name "Animal Breeder" :players 4
    :description "A pasture has just been fenced. Click the animal pair and food price you want, then confirm or decline."}
   {:id "resource-seller" :card :catalog/original-base-k-310-resource-seller :name "Resource Seller" :players 4
    :description "A combined wood and clay receipt is pending. Click the illustrated receipt order and compare its rewards."}
   {:id "silage" :card (cards/id :a084) :name "Silage" :players 2
    :description "Choose grain directly from the farm or supply, then click the animal species to breed. The prepared pasture has room."}
   {:id "stable-planner" :card (cards/id :a089) :name "Stable Planner" :players 2
    :description "Click future round cards to reserve stable pieces. Click again to remove a reservation, or decline."}
   {:id "harvest-helper" :card :catalog/original-base-i-223-harvest-helper :name "Harvest Helper" :players 4
    :description "At feeding, click a grain field on Partner’s farm. They receive two food; you receive one grain."}
   {:id "hide-farmer" :card :catalog/original-base-i-236-hide-farmer :name "Hide Farmer" :players 4
    :description "At final scoring, click unused farm spaces to cover them with food. Click again to remove a selection."}
   {:id "clearing-spade" :card (cards/id :a071) :name "Clearing Spade" :players 2
    :description "Open the played card. Click a planted field, then an empty farm field or Bean Field card, to move a crop."}
   {:id "asparagus-knife" :card (cards/id :a058) :name "Asparagus Knife" :players 2
    :description "Click the vegetable field to harvest from. You can also exchange the vegetable for food and a bonus point."}
   {:id "work-certificate" :card (cards/id :a082) :name "Work Certificate" :players 2
    :description "Click an eligible accumulation space on the shared board to take one building resource, or decline."}
   {:id "stagehand" :card (cards/id :a150) :name "Stagehand" :players 4
    :description "Partner has taken Traveling Players. Click rooms, stables, or fences, then build directly on your farm without spending a worker. Clay Roof and Clay Supports allow individual room payments."}
   {:id "cottager" :card (cards/id :b087) :name "Cottager" :players 2
    :description "After Day Laborer, click the room or renovation picture. Choose a new room directly on the farm, or renovate the house."}
   {:id "copse" :card :catalog/original-base-i-78-copse :name "Copse" :players 2
    :description "Choose Sow on the board, then click the wood stacks on the played Copse card. Each stack is individually selectable."}
])

(defn scenario [id]
  (or (some #(when (= id (:id %)) %) scenarios)
      (throw (ex-info "Choose a supported card test table." {:scenario id}))))

(defn options [id player-name]
  (let [definition (scenario id)]
    {:scenario id :name player-name :title (str "Test · " (:name definition))
     :mode :local :expected-players (:players definition 2) :bots 0 :turn-seconds 0 :setup {:mode :random}
     :names (into [player-name] (take (dec (:players definition 2)) (remove #{player-name} ["Partner" "Neighbor" "Visitor" "Guest"])))}))

(defn prepared-round [base round]
  (if (= 1 round) base
      ;; Reveal the real stage order through native round preparation.
      (nth (iterate #(game/drain (game/begin-round %)) base) (dec round))))

(defn put-in-hand [g pid card]
  (let [kind (:kind (cards/card card))
        g (update-in g [:players pid :hand kind] (fnil conj []) card)]
    (if (= kind :minor)
      (let [deck (vec (remove #{card} (:minor-deck g)))]
        (assoc g :minor-deck deck :minor-deck-count (count deck))) g)))

(defn played [g pid card]
  (game/drain (game/play-card (put-in-hand g pid card) pid card 0 {})))

(defn resolve-preparation [g choice]
  (game/command g (game/acting-player g) (merge {:type :resolve :decision (:decision-id g)} choice)))

(defn last-harvest-action [g]
  (-> g (assoc :turn 0)
      (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %))
      (game/command 0 {:type :action :space :grain})))

(defn prepare-fields [g cells]
  (update-in g [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % cells)))
(defn final-worker [g]
  (-> g (assoc :turn 0)
      (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %))))

(defn initial-state [id names]
  (let [{:keys [card]} (scenario id)
        base (game/initialize (a/new-game names (vec (mapcat identity a/stages)) 0))
        funded (update base :players #(mapv (fn [p] (a/gain p {:food 12 :wood 8 :clay 8 :reed 6 :stone 8 :grain 3 :vegetable 2})) %))]
    (case id
      "family-counselor" (-> funded (played 0 card) (game/command 0 {:type :action :space :wood})
                              (game/command 1 {:type :action :space :grain}) final-worker)
      "job-seeker" (-> funded (played 1 :catalog/original-expansion-g-21-hermit)
                        (played 2 :catalog/original-expansion-g-21-hermit) (played 0 card)
                        (put-in-hand 0 :catalog/original-expansion-g-5-architect) game/begin-round game/drain)
      "late-sleeper" (-> funded (played 0 card) game/begin-round game/drain)
      "master-bakers-apprentice" (-> funded (a/build-improvement 0 {:improvement :clay-oven :bake-grain 0}) (played 0 card))
      "oldest-occupation" (-> funded (prepared-round 5) (update-in [:players 0] a/build-rooms [0])
                               (played 0 card) (game/command 0 {:type :action :space :labor}))
      "tax-collector" (-> funded (prepared-round 4) (played 0 card) (assoc :turn 1)
                          (game/command 1 {:type :action :space :improvement :improvement :fireplace-2}))
      "harvest-feast" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                          (played 0 :catalog/original-expansion-g-49-seed-master) (prepare-fields [0 1])
                          (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}]) (played 0 card))
      "hired-helpers" (-> funded (played 0 card) final-worker (game/command 0 {:type :action :space :grain}))
      "pelt-roof" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                       (a/build-improvement 0 {:improvement :fireplace-2}) (played 0 card)
                       (update-in [:players 0] a/gain {:sheep 2}) (game/command 0 {:type :convert :goods {:sheep 2}}))
      "private-school" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-5-architect))

      "necklace" (-> funded (played 0 :catalog/original-base-e-174-tutor) (played 0 card)
                     (game/command 0 {:type :action :space :grain}) final-worker)
      "prefect" (-> funded (prepared-round 4) (a/build-improvement 0 {:improvement :pottery}) (played 0 card) last-harvest-action)
      "prosecutor" (-> (reduce (fn [g pid] (-> g (played pid :catalog/original-base-e-21-half-timbered-house)
                                               (played pid :catalog/original-base-e-35-corn-scoop))) funded [1 2 3])
                       (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-110-shovel))
      "tower-builder" (-> funded (update :players #(mapv (fn [p] (if (zero? (:id p)) p (a/build-rooms p [0] {} {:free? true}))) %))
                          (played 0 card))
      "timetable" (-> funded (a/build-improvement 0 {:improvement :fireplace-2}) (played 0 card)
                      (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
                      (assoc-in [:players 0 :goods :wood] 0) (assoc-in [:players 0 :goods :clay] 0))
      "cat-lover" (-> funded (update-in [:players 0] a/build-fences (a/edges 0))
                      (update-in [:players 0] a/build-stables [0] 0) (update-in [:players 0] a/gain {:sheep 4})
                      (played 0 card))
      "sunday-school" (-> funded (put-in-hand 0 :catalog/original-base-e-172-chief) (put-in-hand 0 :catalog/original-base-e-174-tutor)
                          (played 0 card) game/begin-round game/drain final-worker
                          (game/command 0 {:type :action :space :grain}))
      "park-cemetery" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0}))
      "fencing-master" (played funded 0 card)
      "pear-peeler" (-> funded (played 0 card) (game/command 0 {:type :action :space :wood}))
      "grotto" (-> funded (played 0 :catalog/original-expansion-g-49-seed-master)
                   (played 0 :catalog/original-expansion-g-5-architect) (played 0 card) final-worker
                   (game/command 0 {:type :action :space :grain}))
      "threshing-machine-plow" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 6}))
      "wood-saw" (-> funded (update-in [:players 1] a/build-rooms [0]) (update-in [:players 1] a/grow true)
                     (played 0 card) (game/command 0 {:type :ability :card card}))
      "convict-number-24601" (-> funded (prepared-round 12) (played 0 card) game/begin-round game/drain)
      "cowboy-and-mother" (-> funded (prepared-round 12) (played 0 card) final-worker (game/command 0 {:type :action :space :grain}))
      "good-friend" (-> funded (played 0 card) (played 1 :catalog/original-expansion-g-110-shovel))
      "miser" (-> funded (played 0 card) (assoc-in [:players 0 :goods :wood] 4) (assoc-in [:players 0 :goods :reed] 1))
      "turkey-breeder" (played funded 0 card)
      "wood-gatherers" (-> funded (put-in-hand 0 card) game/begin-round game/drain
                           (game/command 0 {:type :action :space :build :rooms [0]}) (played 0 card) final-worker)
      "workaholic" (-> funded (played 0 card)
                       (update-in [:players 0] a/gain {:wood 8})
                       (update-in [:players 0] a/build-fences (vec (map key (filter #(= 1 (val %)) (frequencies (mapcat a/edges [0 1 2]))))))
                       (update-in [:players 0] a/gain {:sheep 5})
                       (game/command 0 {:type :ability :card card}))
      "borrower" (played funded 0 card)
      "chemist" (-> funded (played 0 card) (game/command 0 {:type :action :space :wood}))
      "family-planner" (played funded 0 card)
      "lady-in-waiting" (-> funded (played 0 card) (prepared-round 12))
      "storehouse-minder" (played funded 0 card)
      "almshouse" (-> funded (played 0 card) (prepared-round 4) (assoc-in [:players 0 :goods :food] 1) last-harvest-action)
      "cash-crop" (-> funded (played 0 :catalog/original-base-e-174-tutor) (played 0 card)
                      (update-in [:players 0] a/sow [{:cell card :crop :grain}]) (prepared-round 4) final-worker)
      "fish-pond" (played funded 0 card)
      "rotherham-plow" (-> funded (played 0 :catalog/original-base-e-174-tutor) (played 0 card)
                           (prepare-fields [1 6]) (game/command 0 {:type :action :space :plow :cell 0}))
      "slapdash-renovation" (-> funded (played 0 card) (prepared-round 7) (game/command 0 {:type :action :space :renovate}))

      "headmaster" (-> funded (played 0 card)
                       (played 1 :catalog/original-expansion-g-21-hermit)
                       (played 2 :catalog/original-expansion-g-21-hermit)
                       (played 3 :catalog/original-expansion-g-21-hermit)
                       (assoc-in [:players 0 :goods :food] 0)
                       (put-in-hand 0 :catalog/original-base-e-172-chief))
      "mathematician" (-> funded (prepared-round 5) (played 0 card)
                          (assoc-in [:piles :wood :wood] 0) (assoc-in [:piles :copse :wood] 2)
                          game/begin-round game/drain)
      "miner" (-> funded (prepared-round 7) (played 0 card) final-worker
                   (game/command 0 {:type :action :space :labor}))
      "opportunist" (-> funded (prepared-round 3) (played 0 card) (assoc-in [:players 0 :remaining] 1))
      "pilgrim" (-> funded (played 0 card)
                     (update-in [:players 0 :goods] merge {:food 0 :wood 0 :clay 0 :reed 0 :stone 0})
                     game/begin-round game/drain)
      "pond-watchman" (-> funded (played 0 card) final-worker (game/command 0 {:type :action :space :fishing}))
      "animal-farm" (-> funded (update-in [:players 0] a/build-stables [0] 0)
                         (update-in [:players 0] a/gain {:sheep 1 :boar 1})
                         (played 0 card) final-worker (game/command 0 {:type :action :space :grain}))
      "bountiful-harvest" (-> funded (prepared-round 4)
                               (assoc-in [:players 0 :occupations] [:catalog/original-expansion-g-21-hermit :catalog/original-expansion-g-5-architect])
                               (prepare-fields [1 2])
                               (assoc-in [:players 0 :farm 1] {:type :field :crop :grain :count 2})
                               (assoc-in [:players 0 :farm 2] {:type :field :crop :vegetable :count 2})
                               (update-in [:players 0] a/build-fences (a/edges 0))
                               (update-in [:players 0] a/build-stables [0] 0)
                               (update-in [:players 0] a/gain {:sheep 2})
                               (played 0 card) last-harvest-action)
      "chronicle" (-> funded (prepared-round 2) (assoc-in [:players 0 :occupations] [:catalog/original-expansion-g-21-hermit]) (played 0 card))
      "dunghill" (-> funded (prepared-round 4)
                      (assoc-in [:players 0 :occupations] [:catalog/original-expansion-g-21-hermit :catalog/original-expansion-g-5-architect])
                      (prepare-fields [1 2])
                      (assoc-in [:players 0 :farm 1] {:type :field :crop :grain :count 2})
                      (assoc-in [:players 0 :farm 2] {:type :field :crop :vegetable :count 2})
                      (update-in [:players 0] a/build-fences (a/edges 0))
                      (update-in [:players 0] a/build-stables [3] 0)
                      (update-in [:players 0] a/gain {:sheep 2 :boar 1 :cattle 1})
                      (played 0 card) last-harvest-action)
      "alternative-designer" (-> funded (played 0 card) (assoc-in [:players 0 :goods :wood] 1))
      "fish-seller" (-> funded (prepared-round 4) (played 0 card) (game/command 0 {:type :action :space :fishing}))
      "stonepiler" (-> funded (prepared-round 4) (played 0 card))
      "vegetarian" (-> funded (a/build-improvement 0 {:improvement :fireplace-2}) (played 0 card))
      "bartering-hut" (-> funded (update-in [:players 0] a/build-fences (vec (a/edges 0))) (played 0 card))
      "corral" (-> funded (played 0 :catalog/original-expansion-g-49-seed-master) (prepare-fields [0 1 6]) (played 0 card) (prepared-round 4))
      "gimlet" (-> funded (played 0 card) (update-in [:players 0] a/gain {:wood 3}))
      "paddocks" (-> funded (played 0 :catalog/original-expansion-g-49-seed-master)
                     (update-in [:players 0] a/build-fences (vec (map key (filter #(= 1 (val %)) (frequencies (mapcat a/edges [1 2 6 7]))))))
                     (played 0 card))
      "scythe" (-> funded (played 0 :catalog/original-expansion-g-49-seed-master) (prepare-fields [0])
                   (update-in [:players 0] a/sow [{:cell 0 :crop :grain}]) (played 0 card) (prepared-round 3))
      "sheepdog" (-> funded (played 0 card) (update-in [:players 0] a/gain {:sheep 4}) (prepared-round 4) last-harvest-action)
      "bee-keeper" (played funded 0 card)
      "cowboy" (-> funded (played 0 card) (prepared-round 10) (assoc :turn 1)
                   (game/command 1 {:type :action :space :cattle}) (assoc :turn 0))
      "hunter" (-> funded (played 0 card) (prepared-round 3) (game/command 0 {:type :action :space :sheep}))
      "pawnbroker" (-> funded (a/build-improvement 0 {:improvement :well}) (played 0 card))
      "village-fool" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-5-architect)
                         (put-in-hand 0 :catalog/original-expansion-g-110-shovel) game/begin-round game/drain)
      "bonfire" (-> funded (update-in [:players 0] a/gain {:boar 1}) (played 0 card))
      "raspberries" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115)) (played 0 card))
      "reed-nursery" (-> funded (played 0 card) (prepare-fields [0 1]) (prepared-round 3))
      "storage-shed" (-> funded (update-in [:players 0] a/build-stables [0] 2) (assoc-in [:players 0 :goods :wood] 0)
                         (put-in-hand 0 :catalog/original-expansion-g-110-shovel) (played 0 card))
      "wine-closet" (-> funded (played 0 card) (game/command 0 {:type :ability :card card :round 6}) (prepared-round 6))
      "five-rings" (put-in-hand funded 0 card)
      "grain-mill-oven" (-> funded (played 0 card) (prepared-round 2))
      "boatswain" (-> funded (prepare-fields [0 1]) (played 0 card) (game/command 0 {:type :action :space :fishing}))
      "fiddler" (-> funded (played 0 card) (prepared-round 2) final-worker)
      "harvester" (-> funded (played 0 card) (prepared-round 14) (prepare-fields [0 1 2 3 4])
                      (update-in [:players 0] a/gain {:grain 2})
                      (update-in [:players 0] a/sow (mapv #(hash-map :cell % :crop :grain) [0 1 2 3 4])) final-worker)
      "head-of-the-revolution" (-> funded (prepare-fields [1 2 3 4 6 7 8 9 11 12 13 14]) (played 0 card))
      "manual-labourer" (-> funded (played 0 card) (prepared-round 2)
                            (game/command 0 {:type :action :space :wood}) final-worker)
      "mastermind" (-> funded (played 0 card) (prepared-round 2))
      "sailboat-constructor" (-> funded (played 0 card) (prepared-round 4) last-harvest-action)
      "stroller" (played funded 0 card)
      "reed-nursery-scoring" (-> funded (played 0 card) (prepare-fields [0 1])
                                  (update-in [:players 0] a/sow [{:cell 0 :crop :reed} {:cell 1 :crop :reed}])
                                  (assoc :round 14 :phase :finish-round) game/finish-game game/drain)
      "workshop-combinations" (-> funded (a/build-improvement 0 {:improvement :pottery})
                                  (a/build-improvement 0 {:improvement :joinery}) (played 0 card)
                                  (played 0 :catalog/original-expansion-g-6-artisan) (prepared-round 4) last-harvest-action)
      "animal-farmer" (-> funded (played 0 card)
                          (update-in [:players 0] #(-> % (a/build-fences (vec (distinct (mapcat a/edges [0 1])))) (a/gain {:sheep 1 :boar 2 :cattle 1}))))
      "artisan" (-> funded (a/build-improvement 0 {:improvement :pottery}) (played 0 card) (prepared-round 4) last-harvest-action)
      "resource-collector" (played funded 0 card)
      "understudy" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0}) (assoc :turn 0))
      "beehive-oven" (-> funded (a/build-improvement 0 {:improvement :clay-oven}) (played 0 card))
      "cow-bell" (-> funded (played 0 card) (prepared-round 13))
      "hammer" (-> funded (update-in [:players 1] a/build-fences (vec (a/edges 0))) (played 0 card))
      "potters-wheel" (-> funded (a/build-improvement 0 {:improvement :pottery}) (played 0 card) (prepared-round 4) last-harvest-action)
      "remodeling" (-> funded (update-in [:players 0] a/build-stables [0] 2) (played 0 card))
      "seasonal-plants" (-> funded (played 0 :catalog/original-expansion-g-49-seed-master) (played 0 card)
                            (prepared-round 4) (prepare-fields [0 1])
                            (game/command 0 {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}]}))
      "fence-dealer" (-> funded (played 0 card) (prepared-round 4))
      "livestock-keeper" (-> funded (update-in [:players 0] #(-> % (a/build-fences (vec (a/edges 0))) (a/build-stables [0] 2) (a/gain {:sheep 2})))
                             (played 0 card) (prepared-round 4) last-harvest-action
                             (game/command 0 {:type :feed}) (game/command 1 {:type :feed}))
      "stable-deliveryman" (played funded 0 card)
      "sunrise-admirer" (-> funded (played 0 card) game/begin-round game/drain)
      "apple-tree" (-> funded (prepare-fields [0 1]) (played 0 card))
      "backyard-well" (played funded 0 card)
      "changing-plans" (-> funded (update-in [:players 0] a/build-stables [0 1] 2) (played 0 card))
      "gold-medal" (played funded 0 card)
      "pig-wallow" (-> funded (prepare-fields [0]) (played 0 card))
      "wild-breeding" (-> funded (prepared-round 10) (played 0 card))
      "absinthe" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                     (played 0 :catalog/original-expansion-g-49-seed-master) (played 0 card) game/begin-round game/drain)
      "artists-garden" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                           (played 0 :catalog/original-expansion-g-49-seed-master) (played 0 card)
                           (prepare-fields [0]) (update-in [:players 0] a/sow [{:cell 0 :crop :grain}]) last-harvest-action)
      "chocolade" (-> funded (a/build-improvement 0 {:improvement :pottery}) (played 0 card)
                      (played 0 :catalog/original-expansion-g-49-seed-master)
                      (put-in-hand 0 :catalog/original-expansion-g-5-architect))
      "straw-thatched-hut" (-> funded (assoc-in [:players 0 :house] :clay) (prepare-fields [1 2]) (played 0 card))
      "animal-welfarist" (-> funded (played 0 card) (prepared-round 4)
                            (update-in [:players 0] a/gain {:wood 12})
                            (game/command 0 {:type :action :space :build :stables [0]}) (assoc :turn 0)
                            (game/command 0 {:type :action :space :fences :fences (vec (distinct (mapcat a/edges [0 1 2])))}) final-worker)
      "benefactor" (-> funded (update-in [:players 0] a/gain {:wood 12})
                       (update-in [:players 0] a/build-fences (vec (distinct (mapcat a/edges [1 2 3 4]))))
                       (update-in [:players 0] a/gain {:sheep 1}) (played 0 card))
      "cabbage-lover" (-> funded (played 0 card) (game/command 0 {:type :convert :goods {:vegetable 1}}))
      "country-doctor" (-> funded (played 0 card) (prepare-fields [0 1 2])
                           (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable} {:cell 2 :crop :grain}]) (prepared-round 5))
      "martial-artist" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
                           (put-in-hand 0 :catalog/original-expansion-wm-70-clockwork-man) (prepared-round 4) last-harvest-action)
      "parquet-setter" (-> funded (played 0 card) (prepared-round 2) (prepare-fields [0 1]) final-worker)
      "bakers-daughter" (-> funded (a/build-improvement 0 {:improvement :clay-oven}) (put-in-hand 0 card)
                            (put-in-hand 1 :catalog/original-base-e-150-baker) (assoc :turn 1)
                            (game/command 1 {:type :action :space :lessons :card :catalog/original-base-e-150-baker}))
      "carrot-cake-baker" (-> funded (a/build-improvement 0 {:improvement :clay-oven}) (played 0 card)
                              (game/command 0 {:type :ability :card card :option 0}))
      "freeloader" (-> funded (played 0 card) (assoc :turn 1)
                        (game/command 1 {:type :action :space :plow :cell 0}) (assoc :turn 0))
      "grain-lover" (-> funded (prepared-round 7) (played 0 card))
      "inventor" (-> funded (prepared-round 2) (played 0 card))
      "pearl-seeker" (-> funded (prepared-round 4) (played 0 card) (game/command 0 {:type :action :space :fishing}))
      "stone-trader" (-> funded (prepared-round 7) (played 0 card)
                          (put-in-hand 0 :catalog/original-expansion-wm-70-clockwork-man)
                          (game/command 0 {:type :action :space :stone-west}))
      "birthstone" (-> funded (prepared-round 5) (played 0 card) (update-in [:players 0] a/build-rooms [0])
                        (game/command 0 {:type :action :space :growth}))
      "meat-pie" (-> funded (played 0 :catalog/original-expansion-g-49-seed-master)
                      (a/build-improvement 0 {:improvement :clay-oven})
                      (update-in [:players 0] #(-> % (a/build-fences (vec (a/edges 0))) (a/gain {:sheep 2})))
                      (put-in-hand 0 card) (game/play-card 0 card 0 {:payment-option 1}) game/drain
                      (resolve-preparation {:grain 1}))
      "pine-forest" (let [start (-> funded (played 0 card) (assoc-in [:players 0 :goods :wood] 0))
                           four (nth (iterate #(resolve-preparation (game/drain (game/begin-round %)) {}) start) 4)]
                       (game/drain (game/begin-round four)))
      "bargaining-baker" (-> funded (prepared-round 2) (played 0 card) (assoc-in [:players 0 :goods :grain] 0))
      "covetous-farmer" (-> funded (played 0 card) (assoc-in [:players 0 :goods :reed] 0))
      "diplomat" (-> funded (played 0 card) (prepared-round 14) (assoc :turn 1)
                      (game/command 1 {:type :action :space :plow :cell 0}) (assoc :turn 0))
      "farm-school-graduate" (-> funded (played 0 card) (prepared-round 4)
                                  (put-in-hand 0 :catalog/original-expansion-g-5-architect) last-harvest-action)
      "gem-hunter" (-> funded (prepared-round 3) (played 0 card) (game/command 0 {:type :action :space :clay}))
      "wheelbarrow-pusher" (-> funded (played 0 card) (game/command 0 {:type :action :space :reed}))
      "wood-worker" (-> funded (played 0 card) (game/command 0 {:type :action :space :grain}))
      "attic" (-> funded (played 0 card) (game/command 0 {:type :action :space :build :rooms [0]}))
      "clockwork-man" (-> funded (played 0 :catalog/original-base-e-174-tutor) (put-in-hand 0 card))
      "honeymoon" (-> funded (update-in [:players 0] a/build-rooms [0]) (update-in [:players 0] a/grow true)
                       (prepared-round 13) (played 0 card) game/begin-round game/drain)
      "chameleon" (-> funded (update-in [:players 0] #(-> % (a/build-fences (vec (a/edges 0)))
                                                           (a/build-stables [0] 2) (a/gain {:sheep 3 :cattle 1})))
                       (put-in-hand 0 card))
      "goblet" (-> funded (prepared-round 2) (played 0 card))
      "hay-rake" (-> funded (update-in [:players 0] a/build-rooms [6]) (update-in [:players 0] a/grow true)
                      (prepared-round 2) (played 0 card)
                      (game/command 0 {:type :action :space :plow :cell 0}) (assoc :turn 0)
                      (game/command 0 {:type :action :space :grain}) (assoc :turn 0))
      "rock-pyramid" (-> funded (assoc-in [:players 0 :house] :stone) (played 0 card)
                          (game/command 0 {:type :action :space :build :rooms [0]}))
      "tuileries-garden" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                              (played 0 :catalog/original-expansion-g-49-seed-master)
                              (played 0 :catalog/original-expansion-g-5-architect) (played 0 card)
                              (game/command 0 {:type :action :space :plow :cell 0}))
      "winter-deadwood" (played funded 0 card)
      "award-winner" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
                          (game/command 0 {:type :action :space :meeting :card :catalog/original-expansion-g-110-shovel}))
      "cattle-dealer" (-> funded (prepared-round 4) (played 0 card)
                           (game/command 0 {:type :action :space :fences
                                            :fences (vec (map key (filter #(= 1 (val %)) (frequencies (mapcat a/edges [0 1 2])))))}))
      "gardening-enthusiast" (-> funded (played 0 card) (assoc :round 14 :phase :finish-round) game/finish-game game/drain)
      "sculptors-son" (-> funded (a/build-improvement 0 {:improvement :joinery})
                           (a/build-improvement 0 {:improvement :pottery}) (a/build-improvement 0 {:improvement :basketry})
                           (played 0 card))
      "animal-holder" (-> funded (prepared-round 6) (put-in-hand 0 card)
                           (update-in [:players 0] #(-> % (a/build-fences (vec (a/edges 0))) (a/gain {:sheep 2})))
                           (update-in [:players 1] #(-> % (a/build-fences (vec (a/edges 0))) (a/gain {:boar 2}))))
      "apprentice" (-> funded (prepared-round 4) (played 0 card)
                        (a/build-improvement 0 {:improvement :well}) (a/build-improvement 0 {:improvement :clay-oven})
                        (a/build-improvement 0 {:improvement :fireplace-2}) final-worker)
      "glutton" (-> funded (prepared-round 14) (played 0 card) last-harvest-action (game/command 0 {:type :feed}))
      "hobbyist" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
                      (put-in-hand 1 :catalog/original-expansion-g-5-architect) (assoc :turn 1)
                      (game/command 1 {:type :action :space :lessons :card :catalog/original-expansion-g-5-architect}))
      "ringmaster" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
                        (game/command 0 {:type :action :space :traveling}))
      "wintercrafter" (-> funded (prepared-round 4) (played 0 card)
                           (put-in-hand 0 :catalog/original-expansion-g-110-shovel) last-harvest-action)
      "holiday-present" (-> funded (assoc-in [:players 0 :begging] 1) (played 0 card))
      "horse-barn" (-> funded (prepared-round 3) (played 0 :catalog/original-expansion-g-49-seed-master)
                        (prepare-fields [1 2]) (update-in [:players 0] a/build-stables [0 3 6] 2) (played 0 card))
      "pumpkin-pie" (-> funded (a/build-improvement 0 {:improvement :clay-oven})
                         (put-in-hand 0 card) (game/play-card 0 card 0 {:payment-option 1}) game/drain
                         (resolve-preparation {:grain 1}))
      "fish-farm" (-> funded (played 0 :catalog/original-expansion-g-49-seed-master)
                       (prepare-fields [0 1]) (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable}])
                       (played 0 card) (assoc :turn 1) (game/command 1 {:type :action :space :fishing}))
      "bread-carrier" (-> funded (prepared-round 2) (played 0 card) (assoc :turn 1))
      "engineer" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-86-itinerant-laborers)
                      (assoc-in [:players 0 :goods :food] 0))
      "heiress" (-> funded (played 0 card) (prepared-round 6))
      "hoarder" (-> funded (prepared-round 4) (played 0 card) last-harvest-action)
      "master-stableman" (-> funded (prepared-round 2) (played 0 card) (game/command 0 {:type :action :space :sheep}))
      "tapestry-weaver" (-> funded (prepared-round 4) (played 0 card)
                             (update-in [:players 0] #(-> % (a/build-fences (vec (a/edges 0))) (a/gain {:sheep 3}))) last-harvest-action)
      "cookies" (-> funded (prepared-round 3) (played 0 :catalog/original-expansion-g-49-seed-master)
                     (a/build-improvement 0 {:improvement :clay-oven}) (played 0 card)
                     (game/command 0 {:type :action :space :sow-bake :bake-grain 1}))
      "crib" (-> funded (prepared-round 5) (played 0 card) (update-in [:players 0] a/build-rooms [0])
                  (game/command 0 {:type :action :space :growth}) last-harvest-action)
      "friendly-pastures" (-> funded (prepared-round 9) (played 0 card)
                               (update-in [:players 0] #(-> % (a/build-fences (vec (a/edges 0)))
                                                            (a/build-stables [0] 2) (a/gain {:sheep 2 :cattle 1}))))
      "home-garden" (-> funded (prepared-round 6) (played 0 card))
      "evening-prayer" (-> funded (prepare-fields [0 1])
                            (put-in-hand 0 :catalog/original-expansion-g-49-seed-master)
                            (put-in-hand 0 :catalog/original-expansion-g-5-architect) (played 0 card))
      "golden-rose" (-> funded (prepare-fields [0]) (update-in [:players 0] a/sow [{:cell 0 :crop :grain}])
                         (played 0 card) (played 0 :catalog/original-expansion-g-49-seed-master)
                         (put-in-hand 0 :catalog/original-expansion-g-5-architect) (assoc-in [:players 0 :goods :food] 0))
      "heatwave" (-> funded (update-in [:players 0] a/renovate {}) (played 0 card))
      "kids-corner" (-> funded (prepared-round 9) (played 0 card) (update-in [:players 0] a/gain {:sheep 1}))
      "march" (-> funded (update-in [:players 0] #(-> % (a/build-fences (vec (a/edges 3)))
                                                      (a/build-stables [3] 2) (a/gain {:sheep 1 :boar 1}))) (played 0 card))
      "trees-for-the-citizens" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                                   (played 0 :catalog/original-expansion-g-49-seed-master)
                                   (a/build-improvement 0 {:improvement :joinery})
                                   (played 0 :catalog/original-expansion-g-110-shovel) (put-in-hand 0 card))
      "drawing-genius" (-> funded (prepared-round 2) (played 0 card)
                            (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
                            (game/command 0 {:type :action :space :improvement :improvement :fireplace-2}))
      "lemon-trader" (played funded 0 card)
      "pasteurization-expert" (-> funded (played 0 card) (update-in [:players 0] a/build-fences (vec (a/edges 0))))
      "reformer" (-> funded (prepared-round 10) (played 0 card)
                      (played 0 :catalog/original-expansion-g-49-seed-master) (update-in [:players 0] a/gain {:sheep 2}))
      "fishermans-apprentice" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-5-architect) (assoc :turn 1) (game/command 1 {:type :action :space :fishing}))
      "glass-blower" (-> funded (prepared-round 4) (a/build-improvement 0 {:improvement :clay-oven}) (played 0 card) last-harvest-action)
      "grafter" (-> funded (prepared-round 4) (played 0 card) last-harvest-action (game/command 0 {:type :feed}))
      "grain-farmer" (-> funded (prepared-round 4) (played 0 card) (prepare-fields [0 1 2]) (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :grain} {:cell 2 :crop :grain}]) (assoc-in [:players 0 :farm 2 :count] 1) final-worker)
      "grump" (-> funded (prepared-round 6) (put-in-hand 0 card) (assoc-in [:players 1 :family] 3))
      "helper" (-> funded (played 0 card) (assoc :turn 1) (game/command 1 {:type :action :space :build :rooms [0]}) (assoc :turn 0))
      "truffle-digger" (-> funded (played 0 card) (update-in [:players 0] a/build-fences (vec (a/edges 0))) (update-in [:players 0] a/gain {:boar 2}))
      "village-farmer" (-> funded (prepare-fields [0 1]) (update-in [:players 1] #(-> % (a/place-tile :field 0) (a/place-tile :field 1))) (put-in-hand 0 card))
      "wheedler" (played funded 0 card)
      "sausage-grinder" (-> funded (prepared-round 4) (a/build-improvement 0 {:improvement :fireplace-2}) (played 0 card) (update-in [:players 0] a/gain {:sheep 1}) last-harvest-action)
      "animal-buyer" (-> funded (prepared-round 4) (played 0 card) (update-in [:players 0] a/build-fences (vec (distinct (mapcat a/edges [0 1])))) last-harvest-action)
      "cloister-dweller" (-> funded (prepare-fields [4 9 14]) (put-in-hand 0 card))
      "humble-farmboy" (put-in-hand funded 0 card)
      "silo-girl" (-> funded (prepared-round 6) (assoc-in [:players 0 :house] :clay) (played 0 card))
      "stone-curator" (-> funded (prepared-round 7) (assoc-in [:players 0 :house] :stone) (a/build-improvement 0 {:improvement :well}) (a/build-improvement 0 {:improvement :clay-oven}) (played 0 card))
      "tile-maker" (-> funded (prepared-round 6) (assoc-in [:players 1 :house] :clay) (put-in-hand 0 card))
      "birdcage" (-> funded (prepared-round 4) (played 0 card) last-harvest-action)
      "free-range-chickens" (-> funded (prepare-fields [0]) (update-in [:players 0] a/sow [{:cell 0 :crop :grain}]) (played 0 card) (prepared-round 7))
      "fruitcake" (-> funded (prepared-round 4) (played 0 (cards/id :a109)) (played 0 (cards/id :a115)) (played 0 card) final-worker)
      "iron-plow" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0}))
      "apple-garden" (-> funded (assoc-in [:players 0 :goods :grain] 0) (assoc-in [:players 0 :goods :vegetable] 0) (put-in-hand 0 card))
      "peasants-boutique" (-> funded (prepared-round 4) (a/build-improvement 0 {:improvement :basketry}) (played 0 card) last-harvest-action)
      "the-port-le-havre" (-> funded (prepared-round 4) (a/build-improvement 0 {:improvement :clay-oven}) (played 0 card) (game/command 0 {:type :action :space :sow-bake :bake-grain 1}))
      "watering-can" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115)) (prepare-fields [0 1 2]) (played 0 :catalog/original-base-e-18-bean-field) (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable} {:cell :catalog/original-base-e-18-bean-field :crop :vegetable}]) (put-in-hand 0 card))
      "art-director" (-> funded (played 0 card) (game/command 0 {:type :action :space :traveling}))
      "cocotte" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-110-shovel) (game/command 0 {:type :action :space :labor}))
      "dove-hunter" (-> funded (prepared-round 4) (played 0 card) (update-in [:players 0] a/gain {:sheep 1}) last-harvest-action)
      "landscape-gardener" (played funded 0 card)
      "stage-star" (-> funded (played 0 card) (assoc :turn 1))
      "wealthiest-european" (-> funded (prepared-round 4) (put-in-hand 0 card) (game/command 0 {:type :action :space :lessons :card card}))
      "reed-sale" (put-in-hand funded 0 card)
      "sheepskin-rug" (-> funded (a/build-improvement 0 {:improvement :fireplace-2})
                              (update-in [:players 0] a/gain {:sheep 1}) (put-in-hand 0 card))
      "sickle" (-> funded (played 0 card)
                     (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2 3]))
                     (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :grain}
                                                  {:cell 2 :crop :vegetable} {:cell 3 :crop :vegetable}])
                     (update-in [:players 0] a/gain {:grain 1}))
      "stonecutters-hall" (-> funded (prepared-round 4) (played 0 card) last-harvest-action)
      "tree-house" (-> funded (prepared-round 7)
                         (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                         (played 0 :catalog/original-expansion-g-49-seed-master)
                         (played 0 card) (game/run-effect (game/task 0 nil {:op :renovate} {}) {}) game/drain)
      "boundary-stones" (-> funded (played 0 card)
                              (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2])))
      "brazier" (-> funded (prepared-round 4) (played 0 card)
                      (update-in [:players 0] a/gain {:sheep 1}) last-harvest-action)
      "glassblowers-shop" (-> funded (prepared-round 4)
                               (a/build-improvement 0 {:improvement :pottery}) (played 0 card) last-harvest-action)
      "lamp-oil" (-> funded (update-in [:players 0] a/build-fences (vec (a/edges 0)))
                       (update-in [:players 0] a/gain {:sheep 2}) (played 0 card)
                       (played 0 :catalog/original-expansion-g-49-seed-master)
                       (put-in-hand 0 :catalog/original-expansion-g-5-architect)
                       (assoc-in [:players 0 :goods :food] 0))
      "worm-bait" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0})
                        (prepared-round 4) (assoc :turn 0)
                        (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "flat-hill" (-> funded
                        (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2 3]))
                        (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :grain}
                                                     {:cell 2 :crop :vegetable} {:cell 3 :crop :vegetable}])
                        (put-in-hand 0 card))
      "lighthouse" (-> funded (prepared-round 7) (assoc-in [:players 0 :house] :stone) (played 0 card))
      "drinker-of-absinthe" (-> funded (prepared-round 6) (put-in-hand 0 card))
      "immigrants-son" (-> funded (played 0 card) (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                             (played 0 :catalog/original-expansion-g-49-seed-master)
                             (put-in-hand 0 :catalog/original-expansion-g-5-architect)
                             (game/command 0 {:type :action :space :lessons :card :catalog/original-expansion-g-5-architect}))
      "village-druid" (-> funded (played 0 card) (put-in-hand 0 :catalog/original-expansion-g-5-architect))
      "clay-starter" (put-in-hand funded 0 card)
      "fence-helper" (-> funded (prepared-round 4) (played 0 card) (assoc :turn 1)
                          (game/command 1 {:type :action :space :fences :fences (vec (a/edges 0))}))
      "amazon" (-> funded (prepared-round 7) (played 0 card))
      "amazon-choice" (-> funded (prepared-round 13) (played 0 card)
                           (game/command 0 {:type :action :space :fishing}))
      "apprenticeship" (-> funded (played 1 (cards/id :a109))
                             (put-in-hand 0 :catalog/original-expansion-g-5-architect)
                             (put-in-hand 0 :catalog/original-expansion-g-49-seed-master)
                             (played 0 card))
      "camembert" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                       (put-in-hand 0 card) (game/command 0 {:type :action :space :meeting :card card}))
      "coffee-break" (-> funded (played 0 :catalog/original-expansion-wm-82-flower-pots)
                          (played 0 card) game/begin-round game/drain
                          (put-in-hand 0 :catalog/original-expansion-g-5-architect))
      "private-market" (played funded 0 card)
      "grain-market" (-> funded (prepared-round 4)
                           (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2]))
                           (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :grain} {:cell 2 :crop :grain}])
                           (played 0 card) last-harvest-action)
      "grain-elevator" (-> funded (played 0 card) (prepared-round 3))
      "grain-elevator-opponent" (assoc (initial-state "grain-elevator" names) :turn 1)
      "farm-school" (-> funded (prepared-round 4)
                          (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                          (played 0 :catalog/original-base-i-78-copse) (played 0 card)
                          (update-in [:players 0] a/place-tile :field 0)
                          (update-in [:players 0] a/sow [{:cell 0 :crop :grain}
                                                       {:cell :catalog/original-base-i-78-copse :crop :wood}])
                          (put-in-hand 0 :catalog/original-expansion-g-5-architect)
                          (put-in-hand 0 :catalog/original-expansion-g-49-seed-master)
                          last-harvest-action)
      "brickyard" (-> funded (played 0 (cards/id :a109)) (played 0 (cards/id :a115))
                        (played 0 card) (prepared-round 3) (assoc-in [:players 0 :goods :clay] 0)
                        game/begin-round game/drain
                        game/begin-round game/drain
                        game/begin-round game/drain
                        (assoc :turn 0)
                        (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "journeyman" (-> funded (update-in [:players 0] a/renovate {})
                          (update-in [:players 0] a/renovate {})
                          (played 0 card) game/begin-round game/drain)
      "carrot-farmer" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0}))
      "pulley" (-> funded (played 0 card) (game/command 0 {:type :action :space :labor}))
      "food-connoisseur" (played funded 0 card)
      "oak-cask" (-> funded (prepared-round 4) (played 0 card) (assoc :turn 0)
                     (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "child-care-worker" (-> funded (prepared-round 7) (played 0 card)
                               (update-in [:players 1] a/build-rooms [0])
                               (assoc :turn 1) (game/command 1 {:type :action :space :growth}))
      "oceanographer" (-> funded (played 0 card) (game/command 0 {:type :action :space :plow :cell 0}))
      "sheep-market" (-> funded (prepared-round 4) (played 0 (cards/id :a109)) (played 0 card)
                          (update-in [:players 0] a/gain {:sheep 1}) last-harvest-action)
      "wooden-bridge" (played funded 0 card)
      "edible-roots" (-> funded (prepared-round 4)
                           (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2]))
                           (played 0 card)
                           (update-in [:players 0] a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :vegetable} {:cell 2 :crop :grain}])
                           (assoc-in [:players 0 :farm 0 :count] 1) (assoc-in [:players 0 :farm 1 :count] 1)
                           (assoc :turn 0)
                           (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "festival-hall" (-> funded (prepared-round 4)
                            (update-in [:players 0] #(-> % (a/place-tile :field 0) (a/place-tile :field 1)
                                                          (a/sow [{:cell 0 :crop :grain} {:cell 1 :crop :grain}])))
                            (played 0 card) last-harvest-action)
      "abandoned-willow" (-> funded
                               (update-in [:players 0] #(reduce (fn [p cell] (a/place-tile p :field cell)) % [0 1 2]))
                               (played 0 card))
      "powerhouse" (-> funded (played 0 card) (assoc-in [:players 0 :goods :stone] 5) (assoc :turn 0)
                       (update :players #(mapv (fn [p] (assoc p :remaining (if (zero? (:id p)) 1 0))) %)))
      "powerhouse-politician" (-> (initial-state "powerhouse" names)
                                 (played 0 :catalog/original-expansion-wm-41-politician)
                                 (assoc-in [:players 0 :goods :stone] 4)
                                 (game/command 0 {:type :action :space :grain}))
      "seed-spreader" (-> funded (played 0 (cards/id :a109)) (played 0 card)
                          (played 0 :catalog/original-base-i-78-copse)
                          (update-in [:players 0] a/place-tile :field 0)
                          (game/command 0 {:type :action :space :plow :cell 1}))
      "vegetable-scoop" (-> funded (prepared-round 8) (played 0 card))
      "diamond-mine" (-> funded (prepared-round 4)
                          (played 0 (cards/id :a109))
                          (played 0 (cards/id :a115))
                          (played 0 (cards/id :a117))
                          (put-in-hand 0 card))
      "encircling-wall" (-> funded (update-in [:players 0] a/build-fences (vec (a/edges 0))) (played 0 card))
      "stone-house-reconstruction" (-> funded (update-in [:players 0] a/renovate {}) (played 0 card))
      "foreman" (-> funded (played 0 card) game/begin-round game/drain)
      "fence-builder" (played funded 0 card)
      "fence-overseer" (-> funded (played 0 card) (game/command 0 {:type :action :space :build :stables [0]}))
      "animal-breeder" (-> funded (prepared-round 4) (played 0 card)
                            (game/command 0 {:type :action :space :fences :fences (vec (a/edges 0))}))
      "resource-seller"
      (-> funded (played 0 card)
          (game/run-effect (game/task 0 :catalog/original-base-k-284-wood-distributor {:op :gain :goods {:wood 1 :clay 1}} {}) {}) game/drain)
      "silage"
      (-> funded (update-in [:players 0] #(-> % (a/place-tile :field 2) (a/place-tile :field 3)
                                               (a/sow [{:cell 2 :crop :grain}])
                                               (a/build-fences (vec (distinct (mapcat a/edges [0 1])))) (a/gain {:sheep 2})))
          (played 0 card) (game/continue-step :return-home) game/drain)
      "stable-planner" (played funded 0 card)
      "harvest-helper"
      (-> funded (prepared-round 4) (played 0 card)
          (update-in [:players 1] #(-> % (a/place-tile :field 0) (a/sow [{:cell 0 :crop :grain}]))) last-harvest-action)
      "hide-farmer" (-> funded (played 0 card) (assoc :round 14 :phase :finish-round) game/finish-game game/drain)
      "clearing-spade"
      (-> funded (update-in [:players 0 :occupations] into (map cards/id [:a103 :a104]))
          (played 0 card) (played 0 :catalog/original-base-e-18-bean-field)
          (update-in [:players 0] #(-> % (a/place-tile :field 0) (a/place-tile :field 1)
                                       (a/sow [{:cell 0 :crop :vegetable}]))))
      "asparagus-knife"
      (-> funded (prepared-round 8) (played 0 card)
          (update-in [:players 0] #(-> % (a/place-tile :field 0) (a/sow [{:cell 0 :crop :vegetable}])))
          (game/continue-step :return-home) game/drain)
      "work-certificate"
      (-> funded (update-in [:players 0 :occupations] into (map cards/id [:a103 :a104 :a106]))
          (assoc-in [:piles :wood :wood] 4) (put-in-hand 0 card)
          (game/command 0 {:type :action :space :meeting :card card}))
      "stagehand"
      (-> funded (played 0 card) (played 0 :catalog/original-base-e-36-clay-roof)
          (played 0 :catalog/original-base-e-37-clay-supports) (assoc-in [:players 0 :house] :clay)
          (assoc :turn 1) (game/command 1 {:type :action :space :traveling}))
      "cottager" (-> funded (played 0 card) (game/command 0 {:type :action :space :labor}))
      "copse" (-> funded (prepared-round 4) (update-in [:players 0 :occupations] conj (cards/id :a103)) (played 0 card))
      "swing-plow"
      ;; Prepared ownership, then the real action pipeline creates the pending
      ;; choice and uncommitted base field. No synthetic decision queue.
      (-> base
          (assoc-in [:players 0 :minors] [card])
          (assoc-in [:players 0 :card-state card :uses] 2)
          (game/command 0 {:type :action :space :plow :cell 0}))
      "businesswoman"
      (-> funded (put-in-hand 0 card)
          (put-in-hand 0 :catalog/original-expansion-g-110-shovel)
          (put-in-hand 0 :catalog/original-expansion-fr-7-baguette)
          (game/command 0 {:type :action :space :lessons :card card}))
      "singer" (-> funded (put-in-hand 0 card) (game/command 0 {:type :action :space :lessons :card card}))
      "homework"
      (-> funded (put-in-hand 0 card)
          (put-in-hand 0 :catalog/original-expansion-g-50-singer)
          (put-in-hand 0 :catalog/original-expansion-g-49-seed-master)
          (game/command 0 {:type :action :space :meeting :card card}))
      "gardening-tools"
      (-> funded (prepared-round 4) (played 0 card)
          (update-in [:players 0] a/place-tile :field 0)
          (update-in [:players 1] a/place-tile :field 0)
          (assoc :turn 1)
          (game/command 1 {:type :action :space :sow-bake :plantings [{:cell 0 :crop :grain}]}))
      "shovel"
      (-> funded (played 0 card) (update-in [:players 0] a/place-tile :field 0)
          (game/command 0 {:type :action :space :labor}))
      "debris-dealer"
      (-> funded (prepared-round 7) (played 0 card) (assoc-in [:players 0 :house] :clay))
      "herdsman"
      (-> funded (prepared-round 6) (played 0 card) (game/command 0 {:type :action :space :labor}))
      "ruins"
      (-> funded (prepared-round 4) (played 0 card) last-harvest-action)
      "steamer"
      (-> funded (played 0 card)
          (played 0 :catalog/original-expansion-g-49-seed-master)
          (played 0 :catalog/original-expansion-wm-38-minimalist)
          (played 0 :catalog/original-base-e-197-braggart)
          (played 0 :catalog/original-base-k-339-pelts)
          (update-in [:players 0] a/build-fences (vec (distinct (mapcat a/edges [0 1]))))
          (update-in [:players 0] a/gain {:sheep 1 :boar 1 :cattle 1}))
      "baguette"
      (-> funded (prepared-round 4) (played 0 card)
          (a/build-improvement 0 {:improvement :clay-oven}) last-harvest-action)
      "barber-shop" (-> funded (prepared-round 8) (put-in-hand 0 card))
      "trailblazer"
      (-> funded (played 0 card) (played 0 :catalog/original-expansion-g-49-seed-master)
          (played 0 :catalog/original-base-e-18-bean-field)
          (update-in [:players 0] a/place-tile :field 0)
          (update-in [:players 0] a/sow [{:cell 0 :crop :grain}])
          game/begin-round game/drain))))
