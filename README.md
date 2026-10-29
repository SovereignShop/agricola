# Eurozone

The [AI and recording guide](ai/README.md) covers the EDN game definitions,
verified browser playback, legal action interface, and MuZero-style training.

A browser-based home for board games, with **Agricola Revised Edition** as the first
playable game, including a beginner mode and the 96 base hand cards. Built in Clojure and ClojureScript, following
the shared-schema, DataScript, re-frame and Reagent architecture of `../codenames`.

## Run

Requires a JDK (17 or newer), Clojure CLI, Python 3, and Node.js 20+ with npm
to install the bundled Discord SDK. No external JavaScript CDN is used.

```sh
npm ci
make build
make run
```

Open **http://localhost:3001**. Create a solo/pass-and-play table or an online
table, choose the expected player count and hand setup, then share its URL.
The host can start when every expected seat is filled, ready, and connected.
Choose one expected player for solo play. Guests can leave before the start.

For testing on one machine, use **Open another player in a new tab** in the lobby.
That link creates an independent seat in session storage; reloading keeps it.
Ordinary tabs return to the browser’s default seat. Remote players open the normal
invite URL on their own devices. The server retains games and pending card
decisions across restarts.

```sh
clojure -M:run 3002                       # another port
HOST=0.0.0.0 clojure -M:run              # allow devices on your network
EUROZONE_DATA=/path/to/games.edn make run # alternate save location
make test
```

The default save file is `data/eurozone.edn`, separate from the original desktop
prototype’s database. Use one server process per save file. An online table is
visible in this server’s lobby, including to spectators. Browser-local seat
tokens identify players in local development. Discord deployments use verified
Discord identities. For internet hosting, use an HTTPS reverse proxy with
WebSocket forwarding for both `/chsk` and `/peer`.

## Peer hosting and private Discord setup

Choose **Peer-hosted table** to run the shared rules in the host browser.
Native browsers use WebRTC, falling back to a WebSocket relay. Activities use
the relay because [Discord does not support WebRTC](https://docs.discord.com/developers/activities/development-guides/networking).
The coordinator saves invitation/host metadata, not peer game states. The host
commits moves to IndexedDB before sending each guest public state and their own
hand. Chat uses these connections and is not saved. Peer tables are absent from
the ordinary server lobby.

Keep the host tab open and bookmark the invitation. Returning in the original
browser restores the save; clearing its storage loses the authoritative save.
There is no automatic host migration. The host is trusted with all hands.
Relay traffic uses HTTPS/WSS but is readable by the relay operator; this is not
application-level end-to-end encryption. Native and Activity browser storage
are separate origins; opening the website does not migrate an Activity save.

For a new **private** deployment:

1. Create **Eurozone** at <https://discord.com/developers/applications>. Copy the
   Application ID and Public Key, generate an OAuth2 Client Secret and Bot Token,
   and enable Activities. No privileged intents or message-reading permission
   are needed. Keep it unverified and invite friends as app testers.
2. Copy `.env.example` to `.env` and fill the four Discord credentials. Set a
   stable `EUROZONE_SESSION_SECRET`, generated with
   `python3 -c 'import secrets; print(secrets.token_hex(32))'`, and your public HTTPS
   origin in `EUROZONE_PUBLIC_URL`. Keep secrets in this ignored file or service
   environment, never browser code.
3. Keep `EUROZONE_PRIVATE=true`. Fill `DISCORD_ALLOWED_USER_IDS` with your own ID
   and friends' IDs, comma separated. Discord Developer Mode enables **Copy User
   ID**. An empty list admits nobody; restart after changing it. Knowing a game
   invitation alone does not grant access. Static code/artwork and the sign-in
   page remain served; the game endpoints require authentication.
4. Run `python3 scripts/with_discord_env.py`. It uses port 3002 and a separate save
   file. Point an HTTPS reverse proxy at `127.0.0.1:3002`, forwarding WebSockets
   and preserving Host. For Caddy, a site block for your domain containing
   `reverse_proxy 127.0.0.1:3002` suffices. Discord requires reachable HTTPS even
   when play is private. The normal localhost server can still run without Discord.
5. Set OAuth2 redirect to `https://YOUR_DOMAIN/discord/callback`, Interactions
   Endpoint URL to `https://YOUR_DOMAIN/discord/interactions`, and Activity URL
   Mapping `/` to `YOUR_DOMAIN`. The server answers the signed verification ping.
   Enable the Activity platforms you intend to test.
6. Enable Guild Install and, if wanted, User Install. Configure the installation
   link with `applications.commands` (plus `bot` for Guild Install), without extra
   bot permissions. Register in your private test server first:

   ```sh
   python3 scripts/with_discord_env.py python3 scripts/register_discord.py --guild YOUR_SERVER_ID
   ```

   Install using the printed link. Friends accept their tester invitations and
   join with allowlisted accounts. For user-installed commands/DM use, register
   without `--guild` after enabling those installation contexts. Global commands
   do not remove the allowlist or publish the Activity.
7. Use `/eurozone agricola players:5 setup:draw extra:2` for a browser invitation,
   or `/eurozone activity players:5 bots:3 setup:draw extra:2` for an embedded
   game with two people and three bots. `players` is the total, including bots;
   leave at least one human seat. Both commands accept bot count, hand setup,
   and extra draft cards. The organizer hosts slash-command games. Direct Activity
   launches make the first participant host with two-player random-hand defaults.
   In an unstarted lobby, the host can change **Table settings → Players expected**
   to 1–5, set **Bot players**, and choose random hands, beginner mode, or the draw. These settings save
   automatically and survive reopening the Activity. Seated guests must mark
   themselves ready again after a change; start requires all expected players.
   **Invite in Discord** invites others to that Activity. The game fills its frame;
   use Discord's fullscreen controls. Each human uses their own Discord account.
   Bots need no Discord identity; they automatically draft, place workers, resolve
   card choices and feed their families. The host browser runs the bot
   in a worker (with a local fallback) and saves every move before
   sharing it. Closing the host pauses play; reopening restores the game and bots.
   Bot hands remain private to their seats. Bots are available in Activities and
   peer-hosted browser tables; ordinary server-hosted tables remain human-only.
   Two-player draws use learned card rankings. Two-player card games also use
   a learned, card-aware value function: independent tests improved mean score
   from 28.17 to 34.85 with random hands and from 30.04 to 34.27 after drafting.
   Each comparison used 24 fresh deals, both seats. Three-to-five-player games
   and beginner games retain their existing policies. The older neural search
   model remains an offline research agent. See the AI guide for results and limits.

[Unverified Activities](https://support-dev.discord.com/hc/en-us/articles/26576097154199-What-are-Verified-and-Unverified-Activities)
are restricted to developers/testers and small servers (currently fewer than 25
members). These restrictions are separate from Eurozone's server-side allowlist.
The real installation and hosting require your Developer Portal account and
domain; the scripts do not create them. Preview commands without credentials via
`python3 scripts/register_discord.py --dry-run`.

Optional `EUROZONE_RTC_CONFIG` accepts RTCConfiguration JSON for your STUN/TURN
provider. Private deployments serve it only after authentication; supplied
credentials are visible to invited clients and should be rotated appropriately.
Without TURN, restrictive networks still fall back to the coordinator relay.
`test/browser_peer.py` exercises direct/relay games and browser recovery.
`test/browser_discord.py` uses the production SDK in an iframe with simulated
Discord RPC/API responses, including private OAuth and both WebSocket gates.
Live Discord testing still requires your application configuration.

### Hosting Discord through Tailscale Funnel

Tailscale Funnel provides a stable public HTTPS address for Discord's proxy and
interaction callbacks. Players do not need Tailscale. Keep `EUROZONE_PRIVATE=true`
and the Discord user allowlist: Funnel is publicly reachable, whereas ordinary
Tailscale Serve is accessible only inside your tailnet.

Inspect `tailscale serve status` first. Preserve any existing routes; in particular,
do not enable Funnel on a port already hosting a private service. An unused 8443
can expose Eurozone separately while an existing 443 service remains private:

```sh
tailscale funnel --bg --https=8443 http://127.0.0.1:3002
```

Set `EUROZONE_PUBLIC_URL` to the printed HTTPS origin, including `:8443`, and
restart the Discord server. Wait for public DNS to resolve before updating the
interaction endpoint; a successful check from a Tailscale device alone does not
verify internet access. Then configure the supported Discord settings:

```sh
python3 scripts/with_discord_env.py python3 scripts/configure_discord.py --apply --guild YOUR_SERVER_ID
```

Discord verifies a signed ping before accepting the new interaction endpoint.
In the Developer Portal, change Activity mapping `/` to `HOSTNAME.TAILNET.ts.net:8443`
(no protocol), and add OAuth redirect `https://HOSTNAME.TAILNET.ts.net:8443/discord/callback`.
Enable Activities and invite testers as above. Retire the previous tunnel after
checking the new public endpoint and updating the Portal mappings.

`--bg` keeps the Funnel configuration in Tailscale; Eurozone itself must also be
running on this machine. To remove only this Funnel, use
`tailscale funnel --https=8443 off`; do not reset unrelated Serve configurations.

To develop the UI with incremental compilation:

```sh
clojure -M:cljs -w src/cljs:src/main/cljc -c eurozone.browser.core
```

Reload the browser after compilation. `build.cljs.edn` uses simple optimization;
`resources/public/js/` is generated and ignored. The previous HumbleUI code and
card catalogue are retained; its dependencies are isolated under `:desktop` and
are not loaded by the browser application.

## Playable scope and rule sources

The implementation follows Lookout’s [official rulebook](https://www.lookout-spiele.de/upload/en_agricolare.html_Rules_Agricola-RE_EN.pdf)
and [appendix](https://lookout-spiele.de/upload/en_agricolare.html_Appendix_Agricola-RE_EN.pdf),
including the appendix’s beginner variant without hand cards. The default
configuration remains beginner mode; select random hands or the draw to enable
occupations and minor improvements.

- One to five players; stage-shuffled action cards; 14 rounds and six harvests.
- Worker placement, starting player, accumulation, and player-count-specific spaces.
- Connected rooms and fields; renovation; stables; edge-based fences and pasture subdivision.
- Sowing, harvesting, feeding, newborns, begging, animal housing and breeding.
- All ten major improvements, including oven limits, Fireplace upgrades, workshop
  feeding, Well income and bonus points.
- Final scoring and the building-resource tiebreaker.
- Solo adjustments: zero initial food, two wood per Forest replenishment,
  and three food per adult at harvest.

In beginner mode, Meeting Place accumulates food and Side Job is included. With
hand cards, Meeting Place allows a minor improvement and Lessons plays an
occupation. The 96 Revised base hand cards have explicit native definitions:
prerequisites, payment, immediate and triggered effects, schedules, passive rules,
optional decisions, travelling cards, field cards, and scoring. Costs and
prerequisites are validated on the server, including Scholar plays; invalid plays
leave the entire game unchanged. The UI uses the shared rules to disable illegal
card choices and explain why. Mandatory effects run automatically. Optional
exchanges and spatial choices wait for their owner, with decisions saved for
reconnection. Simultaneous optional card effects can be reordered by their owner.

Animal housing is computed automatically (including placement that maximizes
Organic Farmer’s pasture bonus); overflow requires an explicit choice
of which animals to keep and whether to cook or release the surplus. Breeding
cannot discard parents or cook offspring. Farm selections preview the action;
resources and workers are spent only after the server accepts it.

Five-player games use the Revised Edition
[5–6 player extension rules](https://www.lookout-spiele.de/upload/en_agricolare_56players.html_Rules_Agricola-RE-56Expansion_EN.pdf).
This includes linked spaces (blocked is distinct from occupied), Modest Wish for
Children opening in round five, the extra Lessons prices, House Building without
stables, Riverbank, Animal Market, and the five-player resource amounts. The ten
base major improvements remain in use; extra majors belong to six-player games.
All 48 extension occupations (A–D 169–180) have native definitions and enter hands
only at five players. Their optional decisions, extra card action spaces, payment,
one-use effects, and automatic triggers use the shared rules engine. Five online
seats and mixed-packet draws are supported, including extra-card draws.

**Coverage limits:** other expansion decks, six-player games, Original Edition
card rules, solo campaigns, browser bot seats, and optional multiplayer action
tiles are not enabled.
A headless bot and recording/replay harness are available below. The imported
2,214-card catalogue is reference data, not a claim that all those cards execute.
Base-card regressions cover prerequisite boundaries and representative timing
interactions; exhaustive card-combination validation remains ongoing. Five-player
tests exercise all 48 occupation plays and representative interactions. The
heuristic simulation bot supports five players; the existing neural encoder and
GPU checkpoints still support at most four and require a new schema before
five-player training.

Card clarifications are tracked by edition in
[`resources/rules/sources.edn`](resources/rules/sources.edn). Useful references are
[Play-Agricola’s Rules forum](http://playagricola.com/forums/index.php?board=23.0)
(and its separate expansion boards), the
[Revised Edition Rules forum on BGG](https://boardgamegeek.com/boardgame/200680/agricola-revised-edition/forums/66),
and [Lumin Sperling’s unofficial Revised compendium](https://docs.google.com/document/d/1WUl0HlzX6Yx0ume75UmpaIMhoHukx9XufM_LbV1oZ6E/edit).
Community discussions and compilations are not automatically publisher rulings.
For example, the [B097 Scholar printing discussion](https://boardgamegeek.com/thread/2505218/b097-scholar)
distinguishes current round-start timing from an older preview image. A linked
regression checks Well income and the timing of newly played round-start cards.
A second checks that Scholar/Manservant schedules food from the following round.
The compendium explicitly permits immediate Plow Driver use when Scholar plays
it; the engine currently misses that opportunity. The existing test's blanket
assumption about newly played round-start triggers is therefore not a verified
general ruling. Remaining-round schedules and same-window abilities need separate handling.
The source index records individual checks; it does not claim all forum edge
cases have been audited.
[Open timing issues](resources/rules/open_issues.edn) include the missing
post-breeding Sheep Walker window and an order-dependent Scholar/Plow Driver
interaction. Reproduce them with `clojure -M scripts/audit_card_timing.clj`.

## The draw

At creation, choose **Beginner**, **Random hands**, or **Draw**. Random hands deal
seven occupations and seven minor improvements to each player. The draw deals
mixed packets of **14 + N** cards, with N configurable from 0 to 10. Every player
keeps one legal card per wave, then packets pass left, until everyone has exactly
seven of each type. A player whose quota prevents any pick automatically passes;
completed players also pass. Empty and blocked packets continue around the table
without requiring impossible picks. The initial supply guarantees enough cards
of both types for every seat. Leftover cards are discarded after completion.

This mixed-packet draw implements the requested house variant. The publisher’s
appendix describes separate occupation/minor drafts and a paired variant; those
are not the same procedure. Opponents and spectators never receive private hands
or draft packets. Local pass-and-play asks the next player to reveal their packet.
Concurrent picks in the same wave are accepted independently; stale or duplicate
picks are rejected. Picks and pending decisions survive server restarts.

## Tabletop, artwork, and chat

The table uses the full browser width with a compact game header. **Table view**
puts the shared board, active farm, and chat side by side. The board scales to its
panel height, and panel contents scroll independently. **Focus table** hides the
site header and round strip; **Fullscreen** uses the browser’s fullscreen mode.

Drag a panel title to another panel’s left or right edge to split that space, or
to its top or bottom edge to create a row. A highlighted preview shows where it
will land. Drag a divider to resize neighboring panels together, or drag a panel’s
corner to change its size. Dividers also support the arrow keys. Double-click a
title or use its maximize button to expand a panel; Escape restores the layout or
cancels an unfinished drag. Alt + arrow keys on a title move a panel by keyboard.

**Show all farms** places every farm immediately below the board. **Panels** lets
you show hidden panels and exposes width, move, full-row, and fit-height controls.
Arrangements save locally per seat and table. Existing saved layouts are migrated
with their order, visibility, and explicit heights preserved. Small screens stack
panels without changing the saved desktop arrangement.

Materials and workers appear as pieces; planted crops and housed animals appear
on farms. Pieces slide from their previous visible location when state changes.
When a source is hidden or offscreen, they slide and fade in near the destination.
Scrolling cancels the animation, and reduced-motion preferences disable it.
Played major/minor improvements and occupations have separate stacks:
click a card to slide it out, click again to put it back, or choose **Show all** /
**Stack cards**. Private hands appear in their own tile.

Artwork is stored locally in `resources/public/art/agricola/`. Board, farm,
pieces, and ten major scans come from the **Korean Revised Edition**, with English
interface labels. Ninety base hand cards use original **English** card images;
six have readable text fallbacks. The 48 five-player occupations currently use
text cards; matching scans have not been sourced. All fourteen round cards use original English
edition scans with matching stage backs and Revised Edition action labels. They
flip face-up as each round opens (with reduced-motion support); unrevealed action
identities stay server-private. Resource piles, workers, and scheduled future
goods remain overlaid on the board. The [source manifest](resources/public/art/agricola/manifest.json)
pins revisions and checksums; [artwork credits](resources/public/art/agricola/sources.html)
are linked in the game. Third-party artwork is separate from the application’s
source-code licensing. The lobby illustration remains original SVG.

The translucent panel at the right is **WebRTC data-channel chat**. The existing
WebSocket carries seat-authorized, table-scoped signaling; chat text travels
between browsers, never through the game store or save file. Messages stay in
memory in each tab, capped at 100 per table, and disappear on reload. Spectators
cannot join chat. The send button waits until channels to all connected peers
are open; disconnected peers do not receive offline messages. On narrow screens,
panels stack so chat does not cover game controls.

Modern browsers need WebRTC support. The default uses Google's public STUN server;
some NAT/firewall configurations require TURN. For hosted deployments, set
`window.EUROZONE_RTC_CONFIG` before loading `js/main.js`, using a standard
`RTCConfiguration` with your STUN/TURN servers and short-lived credentials. There
is no silent WebSocket fallback for chat. A connection problem is shown in the
panel, with a reconnect control. Game state still uses the authoritative server.

## Architecture

The design draws on the Codenames implementation and
[User-driven UI with Datascript](http://www.cartesiantheatrics.com/2020/04/14/codenames-app.html):

| Layer | Files | Responsibility |
| --- | --- | --- |
| Shared model | `src/main/cljc/eurozone/model.cljc` | Normalized game → player → farm-cell entities, schema, Datalog queries and pulls |
| Game registry | `src/main/cljc/eurozone/catalog.cljc`, `src/clj/eurozone/store.clj` | Game metadata and `new-state` / `apply-command` multimethods |
| Agricola rules | `src/main/cljc/agricola/{engine,card_game,card_rules,five_player,draw}.cljc` | Pure farm rules, explicit card definitions, persistent effect queue, and mixed-packet drafting |
| Server | `src/clj/eurozone/{server,store}.clj` | http-kit routes, WebSockets, seat authorization, optimistic revisions, atomic saves |
| Browser | `src/cljs/eurozone/browser/` | Re-frame events/subscriptions, DataScript replica, Reagent views and transport |

Rules operate on an immutable game aggregate on the server. Its public projection
is normalized into DataScript for querying and rendering. Hidden future round
cards, hands, the reserve minor deck, draft packets, and seat credentials are excluded from public facts.
Private hands and packets are delivered separately to their authorized seats. For this small state
space, subscriptions use standard re-frame with immutable DataScript values;
Posh/re-posh’s incremental query machinery and Swig’s movable layout are not
required. No sibling library modifications are needed.

Clients send **commands**, never arbitrary database writes. The server serializes
revision checks, validation, durable persistence and broadcasts. It then sends
DataScript transaction deltas to connected clients. A reconnect replaces the
replica with a complete snapshot. Rejected moves leave both storage and public
facts unchanged. The transport uses EDN over http-kit WebSockets, rather than
Sente/Transit; UI components do not depend on that choice.

Local games support undo. Online games reject undo to prevent one player from
rewinding other players’ moves. Saves retain up to 100 previous game states.
To add a game, register its metadata, rules multimethods, public projection and
browser views; lobby, sessions, revisions and transport remain Eurozone concerns.

## Verification

`make test` covers rules, complete games at every supported player count,
normalized client replication, seat authorization, stale commands, persistence,
and undo. Tests also cover draw quota/rotation edge cases, concurrent picks, private
hands, card prerequisites, schedules, effect timing, and complete card-mode games.
The browser build compiles the shared engine for ClojureScript as well.

`test/browser_smoke.py` optionally runs isolated Playwright integration tests: two
browser sessions, direct WebRTC chat, reconnection, artwork loading, a full game
through the rendered UI, undo, played-card stacks, farm pieces, and mobile layouts. Its header contains setup instructions; it uses temporary saves.
`test/browser_setup.py` tests two seats in one browser, readiness, the full draw,
and personal panel layouts. `test/browser_layout.py` verifies wide-screen sizing,
docking previews, pointer and keyboard resizing, cancellation, focus/maximize,
layout persistence, and mobile stacking. `test/browser_cards.py` tests disabled prerequisites,
automatic card effects, private hands, and decisions across reloads.

## Automated games and recordings

The headless heuristic bot plans for family growth, feeding, crops, pastures and
points. It plays hand cards, resolves mandatory choices, accepts or skips optional
effects, and uses card abilities. Candidate commands go through the production
rules. Its observation contains its own hand and public information; opponents’
hands, draft packets and unrevealed round cards are excluded. Candidate previews
stop before revealing the next round.

```sh
# Seeded self-play, including the mixed 14+N draw
clojure -J-Xmx512m -M:simulate --games 100 --players 4 --seed 1 \
  --mode draw --extra 2 --output data/simulations/draw-batch

# Heuristic versus random action selection; policies alternate seats by game
clojure -J-Xmx512m -M:simulate --games 20 --players 2 --seed 101 \
  --policy mixed --output data/simulations/comparison

clojure -M:simulate --replay data/simulations/comparison/game-101.edn
```

Each output directory must be new. `game-SEED.edn` is an append-only stream:
initial state, accepted commands, committed card effects (including skips), state
hashes, and final scores. Moves are flushed immediately. On failure, the file also
contains the failing position and attempted command; an interrupted prefix can
still be replayed. Replay verifies each state hash, effect trace and final score.
Recordings include a rules fingerprint and all hands for local debugging; they
are not served to players. `summary.edn` reports scores and dealt/played cards,
major improvements, action spaces, and effect/choice coverage.

An initial validation batch completed and replayed 16 games (2,573 commands),
including three- and four-player draws. It played 53 distinct base hand cards and
all ten majors. In the eight two-player mixed-policy games, heuristic seats
averaged 35.75 points versus −25.5 for random seats; three-player heuristic
self-play averaged 22.67 and four-player self-play 26.06. The recording headers
pin the rules used for each game.

This is a bounded one-ply baseline, not an expert player or exhaustive legality
search. It considers selected useful farm layouts and sowing plans, and resolves
simultaneous effects in their queued order. The random baseline uses the same
legal candidates and sensible harvest handling. Scores against that baseline
measure progress, not human-level strength. Recorded games test engine stability
and replay; source-linked regression tests are still needed to establish that a
particular card interaction matches the rules. Tests cover hidden-information
isolation, deterministic setup, optional choices, complete drafted games, replay
corruption, and failure recordings.

Agricola is designed by Uwe Rosenberg and published by Lookout Games. This is an
unofficial implementation. Illustrations are by Klemens Franz; imported artwork
is attributed separately above. The rulebooks are linked, not bundled.

## Table flow and artwork

The draw keeps your chosen occupations and minor improvements visible beside the
current packet, including while waiting. Pass-and-play still requires revealing
the current player's private packet.

During harvest, each player can queue feeding and optional breeding from their
farm panel. Plans are private, survive reconnects, and execute in normal harvest
order. Every command is revalidated when due; card decisions pause the queue and
a changed position can require a revised plan. Queuing is optional.

A sticky turn ribbon names the player and the next required decision. Table
creation and lobby settings offer no clock (default), or 30–300 seconds per
required decision. The last ten seconds receive an urgent countdown. At expiry,
the existing legal bot chooses that decision. Server tables use the server clock;
peer tables use the host's clock and worker, so the host must remain open.

Any human seat may request a rewind to their own last turn, including decisions
made by bots or other players afterward. A popup names the requester, round, and
number of decisions that will be undone, then collects a
strict majority of human seats, counting the requester's approval; bots do not
vote. Play and clocks pause for up to one minute. Declines/expiry restore the
remaining time. Approval restores the requesting player’s turn, truncates the recording,
and clears queued harvest plans. Already revealed information cannot be hidden
from players' memories.

The new parchment, wood and muted olive treatment uses an original generated
farmstead illustration. Its prompt and provenance are in
`resources/public/art/eurozone/manifest.json`; existing card/board scans retain
their separate attribution. All players should reload after upgrading.

Additional browser integration coverage (after building and installing Playwright):

```sh
python test/browser_table_flow.py
python test/browser_peer.py
```

Discord Activities open to a shared lobby with **Join table** and **Create a new
table**. The list is restricted to verified participants of that Activity and
refreshes automatically. Any participant may create a separate table. Choosing
**All tables** returns to the lobby; reloading a selected game resumes it.
Host saves unavailable in this browser are clearly marked, with creation still
available. Old tables and browser saves remain intact. Regression coverage:
`test/browser_activity_recovery.py`.

Table creators can include/exclude the implemented base A/B card groups and the
5–6 Player Expansion before starting. Exclusions apply to actual draft packets,
random hands and the five-player minor reserve. Guests see the selected packs
and must confirm readiness after changes. Unsupported catalogue-only packs are
not offered. The table cannot start until the pool contains seven cards of each
kind per player plus all requested extra draft cards.
