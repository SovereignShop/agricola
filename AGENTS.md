# Card implementation and review

For every new card implementation, and for fixes to interactive card effects:

- Never use dropdowns in card resolutions or card abilities, including shared payment and action controls. Offer clickable resource/piece icons, card faces, or other meaningful graphics. Show exchanges graphically with their costs, quantities, and rewards so the flow is understandable without reading the rules text; keep the card text available. Spatial assignments must be made directly on the actual farm, action board, round track, or card; never ask players to choose numbered fields/spaces from a list.
- Review the interaction explicitly: reuse an existing flow only when it fits the card well; otherwise add a tailored flow built from consistent shared controls. Resource choices should use resource icons and editable selections where appropriate. Show a farm or clickable farm tiles only when they help with the current decision. Verify the chosen flow in the prepared test table, including mobile use.
- Add a playable prepared position to `src/main/cljc/eurozone/card_scenarios.cljc` so it appears under **Card test tables** in the browser and Discord Activity lobby.
- Prepare the prerequisites and resources, then trigger the effect through the native engine. Pause immediately before the relevant decision. For automatic effects, pause before the triggering action so the reviewer can observe its result.
- Give each scenario a clear description of what to try. Use a fresh table controlled by its creator; never overwrite an existing game to stage a test.
- Verify resolution, optional decline when applicable, persistence, and recording playback. Exercise farm/cell choices through the browser for interactive spatial effects.
- Mention the test table name when reporting the card ready for review.

Tabletop placement is personal: the viewer's own farm belongs at the upper left. Keep actual player IDs, seating order, tab order and ownership unchanged when arranging the view.
