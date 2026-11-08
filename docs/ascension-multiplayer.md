# Ascension in Eurozone

**Ascension: Claim the Throne** is the second Eurozone game. Choose it from **Eurozone games** in either the web lobby or the Discord Activity. Its direct route is `/ascension/index.html`.

Create a 2–4-player table and choose 0–3 bots, leaving at least one human seat. Other players open the invitation or find the table in the Ascension lobby, then choose **Join table**. The creator starts once all human seats are occupied. A full or started table can still be observed. A guest can release their seat before the game starts; started seats retain their owner for reconnection.

Every move is validated and saved by the server. The creator can disconnect without interrupting other players. Any seated human can advance common income/event/end-of-round phases. Drafting and card play belong only to the active seat; bot turns run on the server. Reopen the same URL with the same Discord account (or the same browser identity in local development) to reclaim a seat. Finished games remain saved and inspectable. **Save visible snapshot** exports the public position plus your own hand, not a transferable full-game save.

## Rules and content

This carries forward the playable prototype based on `Ascension_Manual.txt`: its original map, sixteen card types, court characters, seeded events, projects, income, conflict, and victory. The unfinished manual's gaps retain the prototype's explicit choices: 12 power and 8 legitimacy for victory, 12 starting stability, one paid draft per player per round, prices 2/3/4, upfront project costs, two-round projects, symmetrical starting houses and shared ties. Collapse awards victory to the highest-power claimant. This is a playable prototype ruleset, not a claim that the unfinished manual uniquely specifies these choices.

## Architecture

- `resources/public/ascension/engine.js` is the same pure seeded engine used by the browser's legal-target previews, the server's authority, and 300-game simulation tests. `data.js` supplies shared definitions.
- `scripts/ascension_worker.cjs` runs that engine in a long-lived serialized Node worker. The host process sends only server-owned state and validated command types; a client never supplies replacement game state.
- `eurozone.ascension` owns seats, revision checks, authorization, persistence, per-viewer redaction, bot scheduling and the `/ascension/ws` transport.
- Eurozone's existing Discord session proof and private allowlist protect the new route. WebSocket origin checks remain enforced. Invitations never contain a seat credential. Lobby discovery is shared among authorized Eurozone users; it is not restricted to one Discord channel.
- Only your unplayed hand is transmitted to you. Other hands, RNG state and unrevealed decks are omitted, including for observers. Draft picks come from a public market and remain public in the chronicle.
- Ascension tables use `<EUROZONE_DATA>.ascension`, separate from Agricola saves and peer metadata, with the existing atomic-save helper. Room changes are serialized; stale revisions and impersonated actors are rejected before execution. This edition uses server-authoritative multiplayer, not peer-hosted game authority.
- `multiplayer.js` supplies reconnection, offline detection, mobile-resume checks, an online lobby and commands. Pending actions are not optimistically applied or blindly resent after a disconnect.
- `scripts/build_ascension.py` creates the minified 63 KB `play.js` with the repository's pinned esbuild. `scripts/build_browser.py` includes it alongside the advanced-optimized Agricola browser and bot bundles.

The server needs Node.js 20+ for Ascension. Set `EUROZONE_NODE` if Node is not on a normal install path. The current deployment also supports its existing Codex runtime Node location. No new npm dependencies, Discord applications, OAuth scopes or URL mappings are needed; the existing `/` mapping serves the game and WebSocket route.

## Verification

- `node test/ascension/engine.test.cjs`: all sixteen card effects, rejected moves without mutation, save/resume determinism and 300 complete 2–4-player bot games. Seeded result: 293 throne victories, seven collapses.
- `eurozone.ascension-test`: seat ownership, host-only start, joined-player advancement after creator disconnect, private views, observer rejection, stale commands, authenticated transport, saved-state reload and a complete hosted human/bot game.
- `test/browser_ascension.py`: real independent browser clients, game selection, lobby create/join/start, private hands, observers, creator departure/rejoin, direct map targeting, temporary network loss, four-human tables, Discord SDK/OAuth, mobile play, terminal game and reload.

Release verification passed: 1,044 Clojure tests with 28,149 assertions, zero failures/errors; all 300 engine simulations; and the independent-client browser checks listed above. The production browser build completed with advanced ClojureScript optimization and minified Ascension/Discord bundles.

Deployed to the existing private Eurozone service on 2026-11-07 after backing up its saves. Local/public health checks passed; public HTML, Ascension and Discord bundles matched disk checksums. The public Ascension WebSocket accepted connections and correctly rejected a valid identity without Discord authentication.
