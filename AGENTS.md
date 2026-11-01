# Card implementation and review

For every new card implementation, and for fixes to interactive card effects:

- Add a playable prepared position to `src/main/cljc/eurozone/card_scenarios.cljc` so it appears under **Card test tables** in the browser and Discord Activity lobby.
- Prepare the prerequisites and resources, then trigger the effect through the native engine. Pause immediately before the relevant decision. For automatic effects, pause before the triggering action so the reviewer can observe its result.
- Give each scenario a clear description of what to try. Use a fresh table controlled by its creator; never overwrite an existing game to stage a test.
- Verify resolution, optional decline when applicable, persistence, and recording playback. Exercise farm/cell choices through the browser for interactive spatial effects.
- Mention the test table name when reporting the card ready for review.

Tabletop placement is personal: the viewer's own farm belongs at the upper left. Keep actual player IDs, seating order, tab order and ownership unchanged when arranging the view.
