# Current handoff — 2026-10-27

NEW RUN COMPLETE after user asked "take another shot":
`data/analysis/action-rank-20261027/`, historical launch PID 794807. Log and process metadata
are same-named sibling files. No job from this study remains running.
Frozen code: agricola.action-rank and ai/action_rank.py. Ten JVM workers;
CUDA fits small residual ranking networks. No live model/rules/bundle changes.

Reuses only the 192 SOURCE deals from the first growth study, preserving its
154/38 case split. Up to four distinct food/preparation/room/growth pivots per
source, one alternative continuation each. Recorded source is the matched
baseline, and its chosen move must equal the current parent. Training alternatives
within five utility points; deployment candidates tested at margin1 or3. Features
include own cards/public farms, feeding context and family/room readiness. Target
is final score-MARGIN change, not a growth reward. Linear and 24-unit tanh rankers,
three fixed seeds each, penalties .001/.01/.1; held-out case-weighted decision
regret selects model. Early work through round10 only; correction capped at4.

Development uses random10400000+ and draft10410000+, eight deals each/both seats;
select highest mean margin averaged over both modes, including parent/wide-parent
controls. Final random10500000+ and draft10510000+, 24 deals each/both seats.
Wide-only control included in final tests if selected policy widens candidates.
Promotion still requires positive independent score and margin intervals in BOTH
modes. Browser port/validation required before deployment; research with-redefs
recording helpers must never be used in a concurrent server.

Completed smoke `action-rank-smoke-20261027` uses 12 sources/42 branches and separate older
evaluation seeds10100000/10110000/10200000/10210000. Fitting and Python/JVM
prediction parity passed; all32 development/test games replayed successfully.
Selected rank-wide-0.5, but smoke results are not strength evidence. Report generated.
Targeted native tests2/165 passed (privacy, whole-game zero correction parity,
late/pending fallback). Python tests2 passed (case split/weight, orientation,
portable neural predictions). Existing full suite114/5504 passed; runner now
also includes the two new native tests. No production changes.

Action-rank collection/fitting update:192 source deals ->685 paired alternatives
(food192, prepare191, room165, grow137);549 training pairs/136 validation from
154/38 whole deals. Selected LINEAR width0, penalty.001,901features. Held-out
pair regret3.51535 ->2.60307 (about26% lower); accuracy.5329 ->.5746. Nonlinear
width24 narrowly lost on prespecified regret criterion. Python/JVM export parity
passed. Development selected rank-narrow-0.5 (same admission margin1 as current
parent). Random development mean margin+2.75; draft development-0.0625. Wider
variants were inconsistent across modes. Independent final tests complete;
promotion_supported=false. The old
ranker validation metric evaluates the sampled pairs without its narrow filter,
so it is not the live parent's actual gameplay regret. Data audit saved in
validation/data-audit.json. Current live bot is unchanged.

Final random48 candidate games: parent32.0208 vs candidate30.9792, gain-1.0417
CI[-2.75,0.6042], margin-1.3125 CI[-2.9583,0.2083], wins18/48. First growth
6.9792 ->6.3333, begging unchanged0.0625, final family4.3333 ->4.2917.
Final draft48 candidate games: parent31.375 vs candidate31.8125, gain+0.4375
CI[-0.7708,1.6875], margin+1.4792 CI[-0.3333,3.1875], wins27/48. First growth
7.9375 ->7.0417, begging0.1042 ->0, final family4.2292 ->4.5625. No non-growth
games in either final mode. No reliable overall improvement; no production update.
All685 branch continuations (58,739 moves) and352 development/final games verified.
Generated report.html and behavior-differences.json. scripts/diagnose_action_rank.clj
extracts final score components and early action counts into score-diagnostics.json.
Random-game accounting: improvements+1.0417 but unused-land-0.8542, sheep-0.4167,
boar-0.3125, grain-0.2708; descriptive only. Learned earlier growth did not ensure
productive farm development. Do not tune on or reuse these final seeds for selection.

Older completed runs below:

FIRST GROWTH RUN NOW COMPLETE: `data/analysis/growth-learning-20261027/`, historical PID 667528;
log `data/analysis/growth-learning-20261027.log`, process metadata alongside it.
User requested another learning round focused on early family growth. Check
status.json and process identity before reporting whether it is still running.
Live card-aware bot remains unchanged during this experiment.

Final draft result: parent32.875 vs candidate33.1667, score gain+0.2917
CI[-1.1667,1.7083], margin+1.4792 CI[-0.8333,3.8130]. First growth7.8125 ->6.5417
(no growth counted round15; parent2 never-grew games, candidate0). Candidate29/48
wins; begging0 ->0.0417. No promotion: earlier growth did not establish a reliable
overall strength gain. Primary report.html and behavior-differences.json generated.
The broader value-iteration follow-up below is also COMPLETE. Neither was promoted.

FOLLOW-UP RUN COMPLETE: `data/analysis/growth-value-iteration-20261027/`, historical launch
PID 675614, same-named .log/.process.json files. This uses all eligible early-game
positions from the first study's completed 192 sources + 334 paired continuations
to refresh full card/family value estimates under the CURRENT stronger policy.
Original model coefficients are the prior; ridge fits return residuals. Preserve
the original study's case-level train/validation split; each case/file is weighted
equally. Only normal work choices through round 10 use refitted values; all other
decisions retain the parent. Four workers, CUDA ridge fitting. Fresh development
9,900,000+, final random 9,910,000+, final draft 9,920,000+; eight/24/24 deals,
both seats. Two native tests (105 assertions) verified privacy, late-game fallback,
and whole-game parity when the prior is unchanged. Source extractor smoke passed.
New code: agricola.growth-value; ai/growth_value_iteration.py. No production update.

Follow-up independent RANDOM test complete: selected full-narrow scores29.9792
against parent31.9792; score change-2.0000 CI[-3.4375,-0.4375], margin-2.1875
CI[-4.1875,-0.1458]. First growth7.1667 ->7.0625; no-growth games0 ->1.
Despite validation RMSE4.2203 ->4.0145, this is worse gameplay and already fails
the promotion requirement. Final draft test also complete: parent30.3958 vs
candidate31.2917, gain+0.8958 CI[-0.4792,2.2083], margin+0.3542
CI[-2.0214,2.7089]. First growth7.8542 ->7.2708 (no growth counted round15;
no-growth games0 ->2); candidate31/48 wins, begging0.0833 ->0.1458.
Do not publish this model or tune against these final tests. Report.html and
behavior-differences.json generated. No job remains running from either study.
The current deployed card-aware model and browser bundles remain unchanged.

First study sampling COMPLETE: 167 usable pairs, 134 training /33 validation;
71 correction features. Payoff RMSE only 4.2759 ->4.2652 (penalty 10). Development
selected learned-narrow-0.5. First independent random test completed: parent mean
31.3958 vs candidate32.9792; score gain+1.5833 CI[0.125,3.0208], margin+1.3125
CI[-0.8125,3.3542]. Mean first growth7.50 ->7.1042; candidate24wins+1draw/48.
Thus this does NOT satisfy the predeclared promotion gate (both score and margin
intervals positive in BOTH modes); the completed draft result also fails. Do not
relax the gate after seeing results. The broader refit was initiated based on the
weak predictive improvement, before its fresh development/test outcomes.

Paired native continuations: 192 fresh source deals, alternating random/draw,
stratified grow/room/prepare and focal seats. A legal investment and alternative
start from the exact same full position, then both use the frozen stronger parent
policy. Outcomes are actual final own scores, not an added family-growth reward.
Both branches stay in the same preassigned train/validation partition. Source
games and completed partial-start continuations are recorded and verified.
Correction features distinguish family size, remaining rounds, harvest burden,
food stock/engine, room cost/readiness and public growth-space availability.
Development includes unchanged parent, wider filter only, and two correction
strengths with narrow/wide filters. Independent random and draft tests compare
the selected candidate against the CURRENT stronger parent, not the old heuristic.
Frozen seeds: source 9,500,000–9,500,191; development 9,600,000–9,600,007;
final random 9,700,000–9,700,023; final draw 9,800,000–9,800,023.

Smoke completed in growth-smoke-20261027 (12 sources, 7 paired positions; no
strength claim). It exposed sampling bias: keeping only narrow alternatives
omitted positions where growth already looked clearly best. Training now admits
alternatives within 8 utility points; gameplay guards remain 1 or 3. Reproduced
fixed sampling on seed 9500009: round-six growth is captured, +2 actual points.
Python pair-orientation/training-vocabulary test passed; native privacy, native
room-cost and full-game zero-correction parity tests passed (3 tests/123 assertions).
Full run uses six workers and does not change game rules, deployed models or old
AI fingerprints. New code: agricola.growth-learning and ai/growth_learning.py.

While this run was active, user asked about bugs and online rule clarifications.
Answered with Play-Agricola/BGG/Revised compendium sources. Re-reading the live
Google Docs text found an additional verified discrepancy: compendium A090 says
newly played Plow Driver can be used immediately when Scholar plays it. Native
reproducer confirms no plow decision is offered. Added it to open_issues.edn and
scripts/audit_card_timing.clj; corrected sources.edn's unsupported blanket claim
about all newly played round-start cards. Scholar/Manservant remaining-round
scheduling is a different rule and remains supported. Evidence and source hash:
data/analysis/rule-review-20261027/. NO engine rules were changed. Three open
timing issues are now reproduced, not fixed; keep this distinction clear.

The completed run below describes the CURRENT deployed bot.

The new card-aware afterstate value experiment is COMPLETE and deployed for
two-player browser card games. No training/evaluation job remains running.
`data/analysis/afterstate-value-20261027/` contains frozen sources, models,
position samples, development/final reports and all recordings. Launch PID
653546, ablation supervisor 655859, and draft evaluator 658018 are historical.

The model predicts final score using a regularized residual over bot utility.
56,741 immediate native previews from 1,024 replayed random deals; original
819 training / 205 validation partition preserved by whole game (45,278 training
positions / 11,463 validation positions). Equal game
weights, training-only feature vocabulary. Validation selects ridge penalty;
8 new development deals both seats select model and blend. Selected cards-0.75,
955 features, penalty 0.1, candidate admission margin 1.0. This is a learned
value function with card context, not neural dynamics or multi-ply search.

Independent random test: 24 deals, both seats. Old heuristic mean 28.1667;
learned mean 34.8542, median 34.5, max 45, 41/48 wins, zero begging.
Paired score gain +6.6875, 95% deal-bootstrap interval [5.2708,8.2292].
Independent draft test: 24 different 14+2 deals, both seats, identical deployed
draft choices and post-draft state hashes. Old mean 30.0417; learned 34.2708,
median 35, max 43, 40/48 wins, zero begging. Gain +4.2292 [2.5208,6.1042].
Both score and margin intervals are positive in both modes. These are gains
against the current simulation heuristic, not calibrated human strength.

Prespecified final feature ablation at blend 0.75: board-only mean 33.2292,
card-aware 34.8542, pairs 34.7708. Card features add +1.625 points [0.25,3.0208],
but margin gain is uncertain. Pair gain −0.0833 [−1.1667,0.9167], so no confirmed
combo benefit and pair terms were not promoted. No final-test tuning/selection.
Total: 400 development/test games, 46,332 verified moves; source replay separately
verified 1,024 games and 112,983 moves. Preserve seeds 9,200,000+, 9,300,000+,
9,400,000+ as already-used development/test data.

Production: resources/eurozone/play-values.edn, eurozone.play-values and
eurozone.bot-player. Only two-player card moves use it. Draft rankings unchanged;
3–5 players and beginner games retain existing policies. Old bot.clj, action
fingerprints and neural checkpoints untouched. scripts/build_browser.py generates
agricola/afterstate_value.cljs from the pure JVM evaluator, without its I/O code.
Export checks completed random/draft/ablation runs and positive independent gains.

Validation: 109 Clojure tests / 5,279 assertions; complete runtime policy parity;
browser/main and real-worker features and predictions match JVM at five stages;
full browser draft/game, host reload and exported replay pass. Built and tested
in browser-staging before publishing bundles. Public byte verification metadata:
data/discord-testing/play-values-verified.json. Main SHA ceaac7004c22c62650086350d1f852388847e9601f590b4e02e28c82bece64e8;
worker SHA 3bec265baad4bfa715d58fe6f091e27f1e415065d6b7ab876e00c54aaebdabd4.
Representative replay /recordings/card-aware-demo.edn: focal seat 1 scored 34
versus 28, selected nearest test mean (not strongest win). Reload Activity to use
new bundles. Backend, Discord configuration and allowlist were not changed.

Next research: genuine multi-step card plans and combo interventions; extending
and independently validating learned values for 3–5 players. Current pair model
did not demonstrate extra strength. Do not describe all combinations as learned.

The 2026-10-26 notes below are historical, including their old deployment state.

---

# Previous handoff — 2026-10-26

The prior card-value and overnight runs below are COMPLETE. Their historical
launch PIDs are no longer active. The overnight supervisor finished at 06:42 PDT:
3,712 study games; 1,140 neural training games; 1,908 total neural verified games;
41,600 updates. Selected checkpoint: 25,600 updates. No confirmed card combos.
Held-out random mean improved by 6.22 points against its parent, but the 95%
interval crossed zero; draft mean regressed by 5.38. No reliable overall gain.

User next requested food-engine / feeding-efficiency / early-family-growth
learning and analysis of all current games. Analysis is COMPLETE:
`data/analysis/openings-20261026/report.html` and `report.json`.
7,123 unique complete games, 809,914 moves, zero replay failures. Four rules
versions inventoried separately; ordinary two-player neural opening comparisons
exclude factorial card interventions. Early growth plus cooking averaged 12.85
versus −16.69 without cooking. Observational, not causal.

New opt-in reward mode `food-growth-v1` preserves actual-score trajectory return
through public-state potential differences. Raw mode remains default. New
reward definitions and wrappers are fingerprinted; changes require --transfer
and fresh targets. Forecasts are approximate and not a complete model of all card
engines. No game rules/action/encoding changes. See `ai/README.md`.

The food-growth reward pilot is COMPLETE and was not promoted: shaped learning
regressed on its within-arm paired parent comparison (−7.81 points, wide 95%
interval crossing zero). Control/shaped action pools differ, so their direct
cross-arm contrast does not isolate reward shaping.

A follow-up policy audit and expert distillation are COMPLETE. Pure policy
imitation did not improve play. The selected hybrid uses public-state heuristic
utility to admit actions within 0.5 of the best utility, then the learned policy
chooses among them. It does not use stale learned dynamics/value heads.

Fresh final test: `data/muzero/guarded-policy-test-20261026/report.json`.
16 independent random-hand deals, both seats, fixed heuristic opponent, 96 total
recorded and verified games across original neural / hybrid / heuristic policies.
Mean raw score: 1.97 / 18.25 / 18.78. Hybrid vs original: +16.28 points, deal-level
bootstrap 95% interval [10.69, 22.00]. Hybrid vs heuristic: −0.53, interval
[−3.03, 1.97]. Mean begging: 3.94 → 0.25; first family growth: round 8.78 → 7.47.
This establishes a hybrid improvement over the old neural agent, not superiority
to the heuristic, pure learned improvement, or confirmed card-combo learning.
Only two-player random-hand hybrid play was evaluated. Activity bots now use the
existing heuristic in the host browser; this GPU model has not been deployed.

Selected checkpoint: `data/muzero/guarded-policy-20261026-0.5/best.pt`.
Original checkpoint preserved. Frozen evaluation sources and reports live under
the test directory. `ai/compare_policies.py` reproduces the paired evaluation;
`ai/policy_audit.py` runs component ablations. `ai/distill_policy.py` trained the
root policy from existing expert demonstrations. Its validation games were held
out from the new updates only; the parent had already seen those demonstrations.
Final test deals are fresh. Guidance has a separate fingerprint and cannot read
opponent hands or future round order. Policy-only checkpoints cannot be resumed
as recurrent models without transfer or used for the untrained card-value head.

Card expected-value comparison is COMPLETE:
`data/analysis/card-ev-comparison-20261026/`.
Six new ranking formulas were fit on 819 randomized training deals. The original
205 validation deals remained excluded from fitting and training-only inner CV.
Eight fresh development deals (both seats), seeds 9,000,000+, selected shrunk
mean score margin. Formula: n/(n+50) × mean(own score − opponent score | dealt c).
Conditional pair ridge did not win development; no confirmed card-combo claim.

Final test used 24 independent fresh deals, seeds 9,100,000+, both seats.
Original draft policy: mean 27.1875, median 26.5, max 37, 24/48 wins.
Selected policy: mean 29.9792, median 30, max 39, 34/48 wins.
Paired score gain +2.7917, 95% deal-bootstrap interval [1.5208, 4.1042].
Margin gain +4.9167 [2.9583, 6.9167]. All post-draft play stayed the same
simulation heuristic. This is improved two-player drafting, not a fixed-hand
play improvement; do not compare its scores directly with random-hand neural
benchmarks. All 208 development/test games and 28,689 moves replayed correctly.

`ai/card_expectations.py` is the reproducible fitting/evaluation pipeline, with
frozen sources, train/validation provenance, rankings CSV, HTML and JSON reports.
`ai/export_card_expectations.py` requires positive independent score and margin
intervals before exporting a static rank model. The selected model is in
`resources/eurozone/draft-values.edn`; `eurozone.bot-player` uses it only for
TWO-player browser bot draws. Larger tables and all post-draft moves retain the
existing policy. This does not change game rules or neural/action fingerprints.
Three complete draft parity tests match the offline ranking exactly (91 total
policy assertions). The complete two-player browser draft/game passed with a real
worker, host reload, and exported replay verification. Public worker bytes match
the tested build; see `data/discord-testing/card-values-verified.json`. Reload the
Activity to use the new ranking. No AI training or evaluation job remains running.
Representative replay: `resources/public/recordings/learned-draft-demo.edn`,
selected seat 1 scored 30 against 31, closest to test mean, not a cherry-picked win.

The initial attempt `data/analysis/card-ev-20261026/` stopped on JSON serialization
of its draft trace (an atom needed dereferencing); it is retained separately and
is not the completed tournament. A complete smoke game passed after the fix.

Next research: improve planning and realization of useful cards, or evaluate the
new draft formula separately for 3–5 players. Any further combo selection needs
new data and independent confirmation. GPU research checkpoints are preserved.

Do not reuse final test seeds for selection or claim five-player neural support.

The historical notes below describe previous launches, not current processes.

---

# GPU training handoff — 2026-10-25

User requested substantially more training to improve Agricola points, then
asked to fix GPU drivers first. After reboot they clarified this was the GPU
training task. Driver recovery and GPU training validation are COMPLETE.
That first longer run is COMPLETE. A new card-value run is active; see below.
The app has since gained five-player rules; do not resume the checkpoint against
the changed rules without an explicit migration and validation.

## Overnight continuation — 2026-10-25

User explicitly requested overnight training, card strength estimates, and strong
card combos. New pipeline is `ai/overnight.py`; active output is
`data/muzero/overnight-20261026/`. Check `status.json` and the adjacent
`overnight-20261026.process.json` before starting anything else. The target end is
**07:00 PDT on October 26**. Original PID 82376 remains untouched and continues
`cards-value-v1`; the supervisor waits for it and trains from its `best.pt`.
Launched **23:02 PDT**: supervisor PID **108451**, sleep-inhibitor PID **108449**,
study PID **108456**. Six workers began producing replay-verified games. The
sleep inhibition is scoped to the supervisor lifetime; no desktop setting changed.

The card study runs 1,024 random deals, 64 pairs × 6 factorial contexts × 4 cells,
then 12 candidate pairs × 24 fresh contexts × 4 cells: **3,712 planned games**.
Six isolated CPU workers. All games use current two-player eligible cards and
the fixed heuristic. Every game is recorded and replay-verified. The factorial
contrast removes main effects; confirmation uses independent deals and adjusted
bootstrap intervals. Interpret results under this policy/rules version.

Next CUDA run: 75% random hands / 25% draw, 64 search simulations, batch 48,
half of updates from retained demonstrations, 50% card-focused sequence starts,
25% expert / 25% mixed opponents / 50% self-play, learning rate 0.00015.
Up to 192 newly verified study recordings bootstrap it; estimator holdout and
confirmation recordings are excluded. Confirmed estimates can guide training
draft picks. Validation and final evaluation use separate 3,200,000 / 4,200,000
seed ranges, both seats, both hand modes. No new model is automatically promoted.

The supervisor copies runnable sources into its `source-baseline` and executes
there, so subsequent workspace edits cannot silently alter that experiment.
`deep_train.py` was extended while the old process was already loaded; its
original source remains archived in `cards-value-v1.source-baseline`. No existing
rule/action/encoder source was changed. The neural encoder still has four slots;
this overnight run is two-player, not five-player neural training.

Validation: 44-game study smoke / 4,915 replayed moves passed; individual and combo
bootstrap recordings re-encoded through the production macro action space.
Python tests include synthetic interaction cancellation, seat symmetry, ridge
recovery, and draft guidance ignoring opponent cards. Full trainer smoke is at
`data/muzero/overnight-trainer-smoke-v1/`; it completed 31 games / 4,183 verified
moves, including CUDA updates, mixed/draft training, selection and final evaluation.
90 Clojure tests / 3,274 assertions and 16 Python tests passed. The frozen-source
worker was independently started and its rules/schema/actions fingerprints matched.
Discord/P2P work is separately completed and tested; localhost:3001
was restarted with the new backend. New Discord credentials/domain are still needed.

## Active card-value experiment — 2026-10-25 21:51 PDT

The user authorized more training and suggested a card-value estimator. The new
run is detached and active. Check its PID and status before starting another job.

- PID at launch: **82376**; verify it is still the same command.
- Output: `data/muzero/cards-value-v1/`.
- Status: `status.json`; progress: `metrics.jsonl`; final: `report.json`.
- Log: `data/muzero/cards-value-v1.log`.
- Exact command and process metadata: `data/muzero/cards-value-v1.process.json`.
- Frozen source and SHA-256 manifest: `data/muzero/cards-value-v1.source-baseline/`.
- Parent: `data/muzero/cards-deep-v1/best.pt` (13,530 updates), unchanged.

It explicitly transfers weights onto the current rules, resets optimizer and
replay, and revalidates/re-encodes 16 old expert recordings. It adds 48 fresh
expert games, then 512 training games (self-play, heuristic, and mixed matches).
CUDA batch 48; 2,048 pretraining updates plus 32/game = 18,432 new updates;
32 search simulations/move. Training starts at seed 41,000 (main games 42,000+).
Eight validation deals, both seats, every 64 games; 24 final held-out deals,
both seats. Validation seeds 810100+ and test seeds 910100+ are separate from
training and from the previous experiment. No evaluation games enter replay.

New learning components:
- Contextual choice-value head predicts acting-seat final score after a legal
  card/action, trained on actual returns. This is not a causal card-point bonus.
- 35% focused sampling of card-decision sequence starts.
- Same-actor next-latent consistency (no opponent-private observation targets).
- Bounded value hint to root search. Final evaluation includes the selected
  model with this hint disabled, to test the hint's contribution separately.

The run remains two-player random hands. Five-player neural training still needs
an encoder/schema migration. Known base-card timing issues remain documented in
`resources/rules/open_issues.edn`; no rule source changed during this training work.
Do not edit rules, encoder or learner while the run is active without preserving
and explicitly handling its source baseline.

Validation before launch: 82 Clojure tests / 3,177 assertions, 12 Python tests
including CUDA, complete transfer and resume/ablation smoke experiments. The
transfer smoke verified 17 games / 2,074 moves. The resume smoke also completed;
its report is `data/muzero/cards-value-resume-smoke-v1/report.json`. These smoke
runs are plumbing checks, not evidence of improved playing strength.

Inspect current contextual estimates after the first checkpoint is available:

```sh
.venv-ai/bin/python ai/card_values.py \
  --checkpoint data/muzero/cards-value-v1/latest.pt \
  --recording data/muzero/cards-value-v1/recordings/warmup-0000.edn --move 0
```

## Completed first experiment

- PID at launch: **12859** (verify command/status; PIDs can be reused).
- Launched: 2026-10-25 17:53 PDT / 2026-10-26 00:53 UTC.
- Process metadata: `data/muzero/cards-deep-v1.process.json`.
- Log: `data/muzero/cards-deep-v1.log`.
- Live progress: `data/muzero/cards-deep-v1/status.json` and `metrics.jsonl`.
- Final result: `data/muzero/cards-deep-v1/report.json`.
- Original checkpoint: `data/muzero/cards-edn-v1/latest.pt`, preserved unchanged.
  Its checksum is in `data/muzero/pre-gpu-checkpoint.json`.
- Original demo remains at `resources/public/recordings/muzero-card-demo.edn`.

Actual command:

```sh
.venv-ai/bin/python -u ai/deep_train.py --device cuda --batch-size 48 \
  --resume data/muzero/cards-edn-v1/latest.pt \
  --output data/muzero/cards-deep-v1
```

Configuration: two-player random-hand Revised card games; starts from 730
optimizer updates; 1,536 pretraining updates; 384 training games × 32 updates;
14,554 final updates; 32 search simulations/move; 16 fresh expert warmups;
retained demonstrations in 25% of batches. GPU trains batched recurrent updates;
CPU runs the Clojure engine and small serial tree searches, using a refreshed
copy of model weights for each game. Low GPU utilization during games is normal.

Validation: 4 fixed deals, both seats, every 32 games. Final testing: 16 separate
deals, both seats against heuristic, plus same-policy self-play. Both previous
and selected model use the same search budget. Paired uncertainty resamples
independent deals. Every game is recorded as EDN and replay-verified.

`latest.pt` saves the last training state; `best.pt` selects highest validation
mean score against heuristic. The original model is a baseline candidate.
A better validation result does not guarantee better held-out test results.
Demonstrations are retained on Replay for later continuation.

## Completed GPU setup and verification

- Kernel 6.8.0-147; loaded NVIDIA driver and libraries 580.178.04.
  `nvidia-smi` works; GTX 1660, 6 GiB, compute capability 7.5.
- Persistent `.venv-ai`: PyTorch **2.14.1+cu126**, CUDA 12.6, includes sm_75.
  Actual CUDA matrix forward/backward passed. The previous `/tmp` Python
  environment was removed by reboot; use this project environment now.
- Installer recipe: `ai/requirements-cuda.txt`. Package lock, installation log,
  CUDA probe and test output: `data/muzero/gpu-setup/`.
- Added batched learner `ai/training.py`. Variable legal-action padding, terminal
  masks and gradient scaling match the serial reference. Seven neural tests
  pass, including CPU/CUDA loss and gradient equivalence.
- Benchmark on checkpoint replay, 20 timed updates per variant:
  serial CPU batch12 89.46 ms; batched CPU batch12 41.36 ms;
  CUDA batch12 31.55 ms; batched CPU batch48 145.64 ms;
  CUDA batch48 62.97 ms (2.31× faster than same-batch CPU).
  Results: `data/muzero/gpu-benchmark.json`. This is learner speed, not whole-game speed.
- Full GPU trainer smoke test completed: `data/muzero/gpu-smoke-v1/report.json`.
  25 games / 3,042 moves replayed exactly; resume, four GPU updates, checkpoint
  save/reload, validation and final evaluation succeeded. This tiny smoke test
  is not a strength result; the long run resumes the original, not smoke weights.
- 69 Clojure tests / 2,889 assertions pass after reboot.
- Web server restarted at localhost:3001; live browser connects without JS errors.

## Rules audit during this run

User asked whether rule issues had appeared. Targeted inspection reproduced two
issues, recorded in `resources/rules/open_issues.edn`, with executable examples
in `scripts/audit_card_timing.clj`: no post-breeding Sheep Walker exchange window,
and prematurely dropping unaffordable Plow Driver before Scholar/Mining Hammer
can supply food. Use-space before/after timing needs a broader audit too.
These were NOT exceptions discovered by self-play. Rules stayed unchanged during
the run. Before adding five-player support, its source was archived at
`data/muzero/cards-deep-v1/source-baseline/`. The working tree now has different
rules and action fingerprints. Replay success demonstrates consistency, not
independent correctness.
Report the run as a baseline under that rules version. Fixes require new rules
fingerprints and revalidated/re-encoded or regenerated training examples.

## Next work

Completed report: 384 training games, 14,554 updates, 620 replay-verified games
(68,222 moves), 5,409.5 seconds. Held-out mean score improved from −18.28125 to
−1.21875 across 16 deals / 32 seat-swapped games; wins went from 0/32 to 2/32.
Paired mean improvement was 17.0625 points (deal-bootstrap 95% interval
7.1242–25.46875). The selected model still trails the heuristic, which averaged
24.125 against it. Self-play mean improved from −10.625 to −2.125.

The neural schema still has four player slots. Five-player heuristic simulation
is supported, but five-player GPU training needs a new encoder/action schema and
regenerated or explicitly migrated examples. Preserve the completed checkpoints
and the archived baseline.

No git commits requested. Many unrelated files are untracked; do not add all.
