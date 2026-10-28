# Agricola learning and verified recordings

This is a working research baseline for the **two-player Revised Edition card
game**, not an expert player. It includes a persistent engine interface,
deterministic numeric encoding, learned dynamics search, training, evaluation,
checkpoints, and portable EDN recordings.

## Paired action ranking (2026-10-27, complete; not promoted)

`ai/action_rank.py` takes another approach after the growth-value experiments:
learn the relative value of actions, including their effect on the opponent's
score. It branches up to four distinct early food, preparation, room and growth
decisions from each of the 192 source deals in the first growth study. The original
recorded continuation supplies the baseline; every alternative completes under
the same frozen deployed policy and is replay-verified. The sampler asserts that
the original move matches that policy. All branches from a deal retain its original
training/validation partition. Evaluation deals from previous studies are excluded.

The target is the actual final score-margin change, with no artificial growth
bonus. Models learn a residual over the deployed ranking. Linear and nonlinear
24-unit tanh models have three fixed-seed members; held-out decision regret selects
regularization and model type. Nonlinear features can express interactions between
family size, feeding, room readiness and own cards. This provides a mechanism for
learning combinations; it does not establish that particular combinations improve
play. The portable network is checked against Python predictions in the native JVM.

Development compares narrow/wider action sets and correction strengths on both
random and drafted games. An unchanged parent and a wider-only policy are controls.
The selected policy gets new random and draft tests, each with 24 deals in both
seats. Promotion requires positive score and margin confidence intervals in both
modes. The live bot remains unchanged until that succeeds and browser validation
passes. Only ordinary work decisions through round 10 can use the correction.

Completed run: `data/analysis/action-rank-20261027/`. The 685 paired alternatives
yielded 549 training and 136 validation pairs from 154/38 source deals. The linear
ranker won model selection over the nonlinear alternatives: held-out pair regret
3.52 to 2.60, but this did not establish stronger complete-game play. Development
selected half-strength correction with the original narrow candidate filter.

| Independent test, 24 deals × both seats | Current mean | Candidate mean | Candidate wins |
| --- | ---: | ---: | ---: |
| Random hands | 32.02 | 30.98 | 18/48 |
| Drafted hands | 31.38 | 31.81 | 27/48 |

Random-hand score change -1.04, 95% deal-bootstrap interval [-2.75, 0.60]; margin
change -1.31 [-2.96, 0.21]. Draft score change +0.44 [-0.77, 1.69]; margin +1.48
[-0.33, 3.19]. Neither mode established a reliable gain. First growth moved from
6.98 to 6.33 in random games and 7.94 to 7.04 in drafts; no final-test player failed
to grow. Earlier growth alone did not yield a reliably stronger policy.

All 685 branch continuations and 352 development/final games replayed successfully.
`report.html`, `behavior-differences.json`, and `score-diagnostics.json` preserve the
results. Random-game score accounting shows +1.04 improvement points offset by
-0.85 unused-land points and lower animal/crop points; this is descriptive, not a
causal diagnosis. The live model remains unchanged, and no job from this experiment
is running. The separate 12-source smoke run is not strength evidence.

## Family-growth learning follow-up (2026-10-27)

The deployed card-aware bot remains unchanged. Two new experiments learn from
actual final scores under that stronger policy, with no artificial growth bonus.
The first samples growth, room-building and preparation decisions from 192 fresh
deals, then records two legal continuations from the same position. Its 167 usable
pairs stay grouped by source deal in training and validation. The second refits
the broader value function from 17,809 early-game positions in the resulting 526
recordings, retaining the old coefficients as a regularized prior. Each experiment
uses separate development and final-test deals; only ordinary work decisions
through round 10 may change.

The paired correction did encourage earlier growth: in the independent draft
test, first growth moved from round 7.81 to 6.54 (non-growth counts as round 15).
However, mean score moved only from 32.88 to 33.17, a gain of +0.29 with a 95%
deal-bootstrap interval of [-1.17, 1.71]. Random-hand scores improved by +1.58
[0.13, 3.02], but the score-margin interval included zero. It failed the predefined
promotion rule: positive score and margin intervals in both modes.

The broader refit's held-out prediction RMSE improved from 4.22 to 4.01, but its
independent random-hand play was worse: mean score 31.98 to 29.98, a change of
-2.00 [-3.44, -0.44]. Better prediction on recorded positions did not imply better
action selection. The completed draft test scored 30.40 for the parent versus
31.29 for the candidate: +0.90 [-0.48, 2.21], with no reliable margin gain either.
Both runs are complete. Neither supports replacing the live policy.

Reproducible pipelines: `ai/growth_learning.py` and `ai/growth_value_iteration.py`.
Results and verified recordings are in `data/analysis/growth-learning-20261027/`
and `data/analysis/growth-value-iteration-20261027/`. `ai/report_growth.py` produces
readable reports and paired behavioral intervals. Three engine timing gaps were
separately reproduced during this work and are tracked in
`resources/rules/open_issues.edn`; research rules remain frozen, and replay
verification establishes engine consistency rather than independent rules accuracy.

## Card-aware afterstate value experiment (2026-10-27)

`ai/afterstate_value.py` learns expected final scores from immediate action
previews produced by the native engine. Training and inference use the same
preview boundary, including pending optional decisions. It fits a regularized
residual over the existing utility, using three nested feature sets: public farm
features; farm plus own hand/played-card identities and context; and those
features plus own card-pair interactions. Interactions include major improvements.
These are observational predictions under the recorded continuation policy,
not causal estimates of card or combination strength.

The original randomized study supplies 819 training games and 205 validation
games. All positions from a game stay in its partition. Games receive equal total
training weight. Validation selects ridge regularization; fresh development
games select the policy and blend. A separate fresh test evaluates that selection.
Opponents and starting hands are identical across variants. Candidate commands
use native legality checks, retain the existing anti-cycle rule, and must be
within one utility point of the heuristic's best action. Game rules and original
checkpoint fingerprints are unchanged. The validated export now drives browser
two-player card games; larger tables and beginner games retain their policies.

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 .venv-ai/bin/python -u ai/afterstate_value.py \
  --output data/analysis/NEW_DIRECTORY --workers 4 --dev-deals 8 --test-deals 24
```

The job saves frozen sources, verified position samples, model JSON, inference
parity checks, all game recordings, a report, and `status.json`. CUDA performs
regression fitting; the CPU runs the game engine. JSON models can be evaluated
in Clojure without PyTorch. Rules and feature/policy source hashes must match.
Use a new output directory for each run. `--limit 32 --dev-deals 1 --test-deals 1`
is a plumbing smoke test and is explicitly labeled unsuitable for strength claims.

Completed experiment: `data/analysis/afterstate-value-20261027/`. Extracted
56,741 positions: 45,278 training and 11,463 validation. Development selected
`cards-0.75`: 955 features, ridge penalty
0.1, blend 0.75, heuristic admission margin 1.0. This is an expected-score
regression with contextual features, not a new neural dynamics/search model.

| Independent test | Old mean | Learned mean | Learned median / best | Learned wins |
| --- | ---: | ---: | ---: | ---: |
| Random hands, 24 deals × both seats | 28.17 | 34.85 | 34.5 / 45 | 41/48 |
| 14+2 draw, 24 new deals × both seats | 30.04 | 34.27 | 35 / 43 | 40/48 |

Random-hand score gain: +6.69, deal-bootstrap 95% interval [5.27, 8.23]; margin
gain +7.06 [5.21, 8.94]. Draft score gain: +4.23 [2.52, 6.10]; margin gain +7.38
[5.06, 9.75]. Both draft arms used the deployed learned draft ranking; identical
post-draft state hashes verify identical hands and positions. No begging in the
selected model's 96 final-test games. These results compare against the existing
simulation heuristic, not human experts.

The prespecified independent feature ablation used the same final random deals,
with the selected blend fixed: board-only mean 33.23, cards 34.85, pairs 34.77.
Cards added +1.63 points [0.25, 3.02], but their margin advantage over board-only
was uncertain. Pair features added −0.08 [−1.17, 0.92]: no confirmed combo gain.
Test outcomes did not select or retune a different model. Development and final
comparisons total 400 games / 46,332 replay-verified moves.

`ai/afterstate_ablation.py` runs the feature ablation from a recorded plan;
`ai/afterstate_draw.py` tests fixed draft choices. `ai/export_afterstate_value.py`
requires positive independent score and margin intervals in both game modes.
The export is `resources/eurozone/play-values.edn`. Browser builds generate the
pure evaluator from its tested JVM source. Native tests verify a complete runtime
game's decisions; browser tests verify identical features/predictions at five
stages in both the main thread and real worker, then complete a draft/game with
host reload and exported replay verification. Full suite: 109 tests / 5,279
assertions. Live bundles match the tested files; metadata is in
`data/discord-testing/play-values-verified.json`.

Representative replay: `/?replay=/recordings/card-aware-demo.edn`, learned seat 1
scored 34 against 28. Selection was closest to the draft test's mean, then seed
and seat, rather than highest score. Reload an existing Activity to load the new
worker. There is no ongoing training job from this experiment.

## Card expected-value comparison (2026-10-26)

Seven draft policies were compared: the existing heuristic, raw dealt-card mean
score margin, sample-size shrinkage, adjusted ridge, a one-standard-deviation
conservative estimate, conditional card-pair ridge, and value per cost. Fit data:
819 randomized training deals; the original 205 validation deals were excluded
from fitting and hyperparameter selection. Training-only four-fold CV chose
regularization. These estimates depend on the gameplay policy, not intrinsic or
causal card points. Pair coefficients remain exploratory.

Eight fresh development deals, both seats, selected **shrunk mean margin**:
`value(card) = n / (n + 50) × mean(own score − opponent score | dealt card)`.
A separate 24-deal test of 14+2 drafts, both seats, compared it with the original draft policy.
All play after drafting used the same heuristic.

| Final-test policy | Games | Mean score | Median | Best | Wins |
| --- | ---: | ---: | ---: | ---: | ---: |
| Original drafting heuristic | 48 | 27.19 | 26.5 | 37 | 24 |
| Shrunk expected value | 48 | 29.98 | 30 | 39 | 34 |

Paired score gain +2.79, deal-bootstrap 95% interval [1.52, 4.10]; margin gain
+4.92 [2.96, 6.92]. All 208 development/test games replayed successfully.
This demonstrates improved **two-player drafting**, not better play of a fixed
hand or validated combo learning. Scores are not directly comparable with the
random-hand neural benchmark below.

Full rankings, formula descriptions, development results, final test and frozen
sources: `data/analysis/card-ev-comparison-20261026/` (`report.html`, `report.json`,
`rankings.csv`, `models.json`). Original failed JSON-output attempt is retained
separately in `card-ev-20261026/`; it is not the final experiment.

`ai/export_card_expectations.py` exports only a complete, positive independent
test of a static ranking. The selected 82 values and provenance are saved in
`resources/eurozone/draft-values.edn`. Browser bots use them for two-player draws;
3–5-player draws retain the existing heuristic until separately evaluated.
Gameplay rules and the historical neural/action fingerprints are unchanged.
A representative final-test replay is `/recordings/learned-draft-demo.edn`.

Reproduce in a new directory:

```sh
OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 .venv-ai/bin/python ai/card_expectations.py \
  --study data/muzero/overnight-20261026/study \
  --output data/analysis/card-ev-reproduction --dev-deals 8 --test-deals 24 --workers 4
```

## Latest measured policy improvement (2026-10-26)

The selected **hybrid** checkpoint is
`data/muzero/guarded-policy-20261026-0.5/best.pt`. A public-state heuristic screens
legal actions; the distilled neural policy selects within 0.5 utility units of
the best candidate. This threshold is not a bound on final score. Guidance uses
only the acting player's observable state and own hand, with an explicit source
fingerprint. Learned value/dynamics heads are bypassed because policy-only
training did not update them. Activity and peer-table bots use the existing heuristic; this hybrid checkpoint remains an offline research agent.

On 16 fresh random-hand deals in both seats against a fixed heuristic opponent:

| Policy | Games | Mean score | Mean begging | First growth round |
| --- | ---: | ---: | ---: | ---: |
| Original neural | 32 | 1.97 | 3.94 | 8.78 |
| Selected hybrid | 32 | 18.25 | 0.25 | 7.47 |
| Heuristic benchmark | 32 | 18.78 | 0.34 | 7.19 |

Hybrid improvement over original: +16.28 points, deal-bootstrap 95% interval
[10.69, 22.00]. Difference from heuristic: −0.53 [−3.03, 1.97]. All 96 recordings
replay successfully. This supports a hybrid improvement over the old neural
agent; it does not establish better-than-heuristic play or strong combo learning.
Only two-player random-hand games were tested. Full results and frozen sources:
`data/muzero/guarded-policy-test-20261026/`.

```sh
.venv-ai/bin/python ai/compare_policies.py \
  --baseline data/muzero/overnight-20261026/training/best.pt \
  --candidate data/muzero/guarded-policy-20261026-0.5/best.pt \
  --output data/muzero/hybrid-reproduction --deals 16 --workers 4
```

Component experiments are in `ai/policy_audit.py`; expert root-policy training
is in `ai/distill_policy.py`. Neither pure policy distillation nor the earlier
food-growth reward-shaping pilot improved playing strength on its own. The
original checkpoint is preserved. Policy-only models require `--transfer` to
resume recurrent training; `card_values.py` rejects their stale value heads.

## EDN is the source of truth

- `resources/agricola/board.edn`: action-space descriptions, resources, stages,
  harvest rounds, and major improvement data.
- `resources/agricola/cards.edn`: the 96 executable base hand-card effect
  definitions, including triggers, costs, schedules, conditions, and scoring.
  Card catalogue metadata is still generated by the existing catalogue importer.
- `resources/agricola/learning.edn`: the versioned observation/action contract,
  privacy rules, encoder dimensions, transforms, reward scaling, and limitations.
- Game states, commands, and full recordings are EDN values. The Clojure rules
  functions interpret these values; Python does not implement Agricola rules.

The `agricola.data/read-edn` macro embeds the same game data in JVM and browser
builds. After changing game EDN, run `make build` and restart the engine.
The build checks EDN fingerprints and invalidates the generated ClojureScript
cache automatically. If using the development watcher, stop it and run
`make build` after editing EDN before restarting the watcher.
This is compile-time loading, not browser fetching or a second generated copy.

The encoder traverses engine observations and parameterized commands into
stable named scalar features, then uses signed BLAKE2b feature hashing and a
signed log transform to produce float32 vectors. It automatically incorporates
new state/command fields. It has no hand-maintained per-card feature table.
Feature hashing is bounded and can collide; this baseline does **not** claim a
lossless state encoding. The complete EDN remains the authoritative state.

Action IDs hash canonical EDN commands, ignoring only ephemeral decision/wave
numbers as declared in the schema. IDs are stable; positions in a legal-action
list are local to that observation. The environment decodes a selected index
back to the exact command, including its current decision ID.

## Recording and playback

In the web app, **Save recording** exports the current game. **Open recording**
loads an EDN file into a read-only viewer with play/pause, previous/next,
first/last, and a move slider. The viewer verifies every command, effect trace,
state hash, and final result before displaying it. Undo truncates the exported
line of play; subsequent moves create a new continuation.

Games created before recording was added start their export at the first
recorded position (`:partial-start? true`); missing historical commands are not
invented. A partial recording can be viewed without claiming a complete game.
Recordings contain all hands. Online exports are host-only and unavailable
until the game finishes; pass-and-play hosts can export while playing.

CLI verification:

```sh
clojure -M:simulate --replay data/path/game.edn
```

Hash canonicalization is shared by Clojure and ClojureScript, including namespace
map printing. Malformed/corrupt recordings and divergent replays are rejected.
The existing simulation recordings were replayed before starting training.

## Environment and action space

```sh
clojure -M:ai
```

The process accepts one JSON request per line and returns one JSON response.
JSON is only a transport for the EDN-derived contract:

```json
{"op":"schema"}
{"op":"reset","seed":1000,"players":2,"mode":"random","record":"data/example.edn"}
{"op":"step","action":0}
{"op":"observe"}
{"op":"replay","path":"data/example.edn"}
```

Use a new recording path for every reset. Modes are `beginner`, `random` (random
hands), and `draw`. Responses include public/own-hand observation features,
the acting seat, legal commands' IDs/features/EDN, terminal status, and rewards.
Draft observations include only the player's packet and public draft counts.
Future round order and opponents' hands are excluded. Requests can set
`"expert":true` to obtain a heuristic demonstration index; this is never an
input to the neural model.

`macro-v1` is deliberately bounded: it covers all action categories and the
card decision types, but not every legal command combination. It uses single
room/stable builds, rectangular fencing plans, selected sowing/feeding plans,
and the current effect order. It does not activate Lasso or reorder simultaneous
effects. The full engine still validates each proposed command. Extending the
generator changes its fingerprint, so incompatible caches cannot be resumed.

## Train

### Overnight card strength and combo studies

`ai/card_study.py` runs isolated Clojure workers against the production rules.
It starts with random deals and fits regularized dealt-card associations while
accounting for opponents' hands and the starting seat. A separate deal split
reports prediction error; bootstrap intervals resample whole games. These are
policy-dependent associations, not intrinsic point bonuses or human rankings.

An exploratory interaction model proposes pairs; one quarter of tested pairs
are sampled randomly to avoid only pursuing the model's favorites. Each pair
gets a 2×2 experiment: `ab`, `Ab`, `aB`, `AB`, with a fixed background, opponent,
round order and starting player. Lowercase cards are randomly chosen replacements
of the same kind. All deals retain seven minors/seven occupations and no duplicates.
The synergy contrast is `score(AB) - score(Ab) - score(aB) + score(ab)` for the
focal player, separating extra joint value from individual card strength.

The strongest exploratory pairs are then tested on fresh background deals.
Confirmation intervals use a Bonferroni-adjusted deal bootstrap across the
selected pairs. They remain approximate, especially with small samples. The
current study uses a fixed heuristic in two-player games; it can miss combos
that require planning the heuristic never attempts. It covers cards eligible
at that player count, not the five-player occupations. Every game is recorded
and replay-verified. Known rule limitations remain in `resources/rules/open_issues.edn`.

```sh
OPENBLAS_NUM_THREADS=1 .venv-ai/bin/python ai/card_study.py \
  --output data/muzero/my-card-study --workers 6
```

Outputs include `card-values.json`, `combo-discovery.json`,
`combo-confirmation.json`, `bootstrap.json`, and progress in `status.json`.
Bootstrap recordings exclude the estimator holdout and confirmation partitions.
Confirmed estimates may inform the experimental draft teacher, so the neural
model's final evaluation uses an entirely separate set of deals.

`ai/deep_train.py` now supports `--draft-fraction`, `--draft-prior`, a stronger
expert/mixed-opponent curriculum, demonstration retention/sampling controls,
and `--train-seconds`. The latter stops at a game boundary and then evaluates.
Draft guidance only sees the acting player's selected cards and legal choices;
it never reads opponents' private cards. Low-evidence estimates defer to the
existing teacher. Validation weights random-hand and draw results by the chosen
training mixture. Final reports compare both modes against the previous model,
swap seats, and test the contextual value hint separately.

`ai/overnight.py` freezes the sources, runs the CPU card study alongside the
existing job, then queues CUDA training from that run's selected checkpoint.
Its output directory must be new. It never interrupts the previous run or
overwrites its checkpoints. It reserves an hour for final evaluation and retains
partial recordings/latest checkpoints on failure. `status.json`, `study.log`,
`training.log`, and the child process metadata explain each stage. A detached
process survives closing the terminal; a machine shutdown still interrupts it.

```sh
.venv-ai/bin/python ai/overnight.py --output data/muzero/my-overnight \
  --wait-run data/muzero/cards-value-v1 --wait-pid EXISTING_PID \
  --until '2026-10-26T07:00:00-07:00'
```

These are experiments, not automatic promotion of a stronger model. Inspect
the held-out comparison and its uncertainty before making strength claims.

Create a Python environment and install dependencies (CPU wheels are sufficient):

```sh
python3 -m venv .venv-ai
.venv-ai/bin/pip install -r ai/requirements.txt --extra-index-url https://download.pytorch.org/whl/cpu
.venv-ai/bin/python ai/muzero.py --output data/muzero/my-run \
  --mode random --players 2 --warmup-games 16 --selfplay-games 32 \
  --pretrain-updates 250 --updates-per-game 15 --simulations 16 --eval-games 4
```

The original CPU run is preserved in `data/muzero/cards-edn-v1/`.
After the reboot, the GTX 1660 driver is working (580.178.04). Use the persistent
project environment for CUDA; the old `/tmp` environment does not survive reboot.
The pinned CUDA 12.6 wheel supports Turing/sm_75 according to the
[PyTorch support matrix](https://github.com/pytorch/pytorch/blob/main/RELEASE.md#pytorch-cuda-support-matrix).

```sh
python3 -m venv --without-pip .venv-ai
python3 -m pip --python .venv-ai/bin/python install -r ai/requirements-cuda.txt
.venv-ai/bin/python -m unittest discover -s ai -p 'test_*.py'
.venv-ai/bin/python ai/benchmark_training.py \
  --checkpoint data/muzero/cards-edn-v1/latest.pt \
  --output data/muzero/gpu-benchmark.json
```

`ai/training.py` batches recurrent updates on the model's device, with masks for
variable action counts and terminal trajectories. Its losses and gradients are
checked against the original serial CPU implementation. `ai/deep_train.py`
accepts `--device auto|cpu|cuda`; explicitly requesting unavailable CUDA fails.
Search uses a CPU copy of the current weights, refreshed for each game, while
optimizer updates run on the selected device. GPU speedups refer to learner
updates, not the Clojure engine or serial tree search.

The deeper experiment retains expert demonstrations, mixes opponents, selects
`best.pt` on separate validation deals, and evaluates the original and selected
models on the same held-out deals with both seat assignments. `latest.pt` holds
the most recent optimizer state. It writes progress to `status.json` and
`metrics.jsonl`, then writes the completed comparison to `report.json`.

```sh
.venv-ai/bin/python ai/deep_train.py --device cuda --batch-size 48 \
  --resume data/muzero/cards-edn-v1/latest.pt \
  --output data/muzero/cards-deep-v1
```

`ai/TRAINING_NEXT.md` records the actual launch command and current run location.

### Contextual card-value experiment

The next experiment adds `CardValueNetwork` (`ai/enhancements.py`) to the base
network. It predicts the acting player's final score after a legal action,
conditioned on the current public/own-hand observation and the full command.
This includes occupations, improvements, and their payment choices, while
ordinary actions provide alternatives for comparison. It is a learned return
estimate, not an intrinsic or causal point bonus for a card. Unplayed alternatives
have no direct counterfactual labels, and predictions are not calibrated guarantees.

Three changes are enabled by `--enhanced`:

- An auxiliary Huber loss fits the chosen action's eventual return. At the real
  search root, relative predicted values add a bounded hint to policy logits.
- 35% of sampled sequence starts are drawn from recorded card decisions; the
  remainder use ordinary replay sampling. This intentionally emphasizes cards.
- A small consistency loss matches predicted next latents to the detached
  encoding of the next real observation, only when the acting seat stays the
  same. Opponents' private observations are never consistency targets for that
  actor. This is inspired by the auxiliary consistency idea in
  [EfficientZero](https://arxiv.org/abs/2111.00210), not a reproduction of that algorithm.

`--transfer` explicitly transfers compatible network weights, resets optimizer
and replay, and records the parent checkpoint hash. It allows changed rules and
action generators but rejects changed encoding/schema fingerprints. Old EDN
demonstrations must be verified and re-encoded using `--bootstrap-recordings`;
stale tensors, policy visits, and optimizer state are not reused. The original
checkpoints remain unchanged. This experiment still trains two-player random
hands; five-player neural training requires a separate schema migration.

Validation and final tests use disjoint, configurable seeds. The final comparison
uses the same training-only action catalog and search budget for both models.
`--ablate-root-value` also evaluates the selected weights with the value hint
disabled on those same held-out deals. That isolates the search hint, not the
effects of the auxiliary training losses. Every actual game is replay-verified.

Inspect card estimates at an actual recorded position:

```sh
.venv-ai/bin/python ai/card_values.py \
  --checkpoint data/muzero/cards-value-v1/latest.pt \
  --recording data/muzero/cards-value-v1/recordings/warmup-0000.edn --move 0
```

The tool emits estimated final scores for legal card choices and ordinary
alternatives, without making a move or modifying the recording.

On the GTX 1660, a 20-update benchmark measured batch-48 updates at 62.97 ms
on CUDA versus 145.64 ms on CPU (2.31×). The serial CPU batch-12 reference took
89.46 ms; batched CUDA batch-12 took 31.55 ms. The benchmark uses the existing
checkpoint's replay buffer; results are in `data/muzero/gpu-benchmark.json`.
The complete GPU trainer smoke test verified 25 recordings / 3,042 moves.

Training starts with heuristic/random demonstrations, then trains on self-play
search visits. It learns representation, dynamics, policy, per-seat value,
reward, next-actor, terminal, and candidate-legality predictions. The recurrent
loss unrolls three transitions. Root actions are engine-validated; deeper search
uses sampled previously encountered action embeddings and learned legality.
The search does not call the engine for imagined transitions. It is a sampled
action **MuZero-style** baseline, not a full replication of
[MuZero](https://arxiv.org/abs/1911.08265).

Agricola has repeated turns and mandatory follow-up decisions, so the value is
an absolute-seat vector. Search maximizes the predicted acting seat's value;
it does not blindly flip a scalar sign after each action. Rewards are terminal
Agricola score / 50 (configured in EDN) by default. The optional food/growth
experiment below provides earlier feedback while preserving total game return.

Outputs:

- `latest.pt`: atomically saved model, optimizer, RNG state, replay buffer,
  encoder/action/rule fingerprints, and configuration.
- `recordings/*.edn`: every rollout, replay-verified after completion.
- `metrics.jsonl`: episode scores, timings, and individual training losses.
- `report.json`: held-out comparisons with random, heuristic, and untrained
  search policies, plus two-seat matches against the heuristic.

Play a new game using a saved model (no further training):

```sh
.venv-ai/bin/python ai/play.py --checkpoint data/muzero/my-run/latest.pt \
  --output data/muzero/model-game.edn --seed 900000 --opponent heuristic
```

Open that EDN in the browser viewer. A recording placed in
`resources/public/recordings/demo.edn` can also be linked directly as
`http://localhost:3001/?replay=/recordings/demo.edn`.

Training/evaluation use separate game seeds. Evaluation trajectories and their
action catalogs do not enter the training replay buffer. Four evaluation seeds
are a smoke benchmark, not a reliable strength estimate.

Resume into a new output directory with `--resume data/muzero/my-run/latest.pt`.
Use the same mode and player count. Only load trusted local checkpoints; they
contain Python replay-buffer objects. In a resumed run the metric called
`untrained-search` means the pre-update checkpoint, not random weights.

## Rule corrections and automatic re-encoding

Every run/checkpoint is tagged with fingerprints for the EDN schema, rule data
and interpreter, action generator, and encoder implementation. Resume fails if
these change. Cached tensors and old rewards are not silently reused.

To regenerate examples from recordings that still verify under the corrected
engine, start a fresh model and use:

```sh
.venv-ai/bin/python ai/muzero.py --output data/muzero/reencoded \
  --mode random --players 2 --warmup-games 0 \
  --bootstrap-recordings data/muzero/old-run/recordings/selfplay-*.edn
```

This executes the EDN commands again, builds new legal action lists and encoded
observations, and recomputes returns. It uses recorded actions as demonstration
targets, not stale search visits. If a rule correction changes a recorded state
or makes a move illegal, verification stops at the divergence: regenerate those
games rather than relabeling them as exact replays. This explicit failure is the
boundary between a valid old trajectory and one needing migration.

## Checks

```sh
clojure -M:test
.venv-ai/bin/python -m unittest discover -s ai -p 'test_*.py'
python test/browser_replay.py  # requires Playwright; see other browser tests
```

Tests cover action legality/determinism, private-information isolation, stable
IDs, vector backups across repeated turns, recurrent gradient updates, browser
playback and live exports, undo branches, and recording corruption rejection.

## First completed card-game run — 2026-10-25

`data/muzero/cards-edn-v1/`: two players, random hands, 16 re-encoded bootstrap
games, 32 self-play games, 730 optimizer updates, 16 search simulations per move.
Four held-out seeds were used for each self-play policy comparison:

| Policy | Mean score per seat |
| --- | ---: |
| Random legal macro action | -22.125 |
| Untrained model + search | -27.125 |
| Trained model + search | -5.500 |
| Heuristic | 22.000 |

In eight direct matches against the heuristic (both seat assignments on each
held-out seed), the model won **0/8**. This is a functioning, improving baseline;
it does not yet play well. The small benchmark cannot establish general strength.

The [demo replay](http://localhost:3001/?replay=/recordings/muzero-card-demo.edn)
is a new seed, 900000: Bot 1 is the trained model, Bot 2 is the heuristic. It
finishes 0–30 after 123 commands. The demo uses 32 search simulations per move.

Final verification: 90 recordings / 10,291 commands replayed exactly, including
the original 17 validation games, all 72 run recordings, and the demo. Results
are in `data/muzero/cards-edn-v1/verification.edn`. Rule tests: 69 tests / 2,889
assertions; neural tests: four; browser checks include cross-runtime hashes and
live-game save/import/playback.


## Opening analysis and food/growth experiment — 2026-10-26

The complete recording audit is `data/analysis/openings-20261026/report.html`,
with machine-readable `report.json` and per-seat `seats.csv` / `seats.jsonl`.
It deduplicated 7,417 complete recording files into 7,123 games and verified
809,914 commands, across four recorded rules versions. In-progress tables are
reported separately. Controlled card-intervention games are inventoried but
excluded from ordinary opening comparisons. Historical implementations are used
where available; otherwise every state hash, effect trace and final score must
match before a compatible replay is accepted.

Among 3,214 current-rule two-player neural seats, early growth plus cooking
(third worker and Fireplace/Cooking Hearth by round 7) averaged 12.85 points,
versus −16.69 for early growth without cooking. This is observational evidence
across different opponents/checkpoints, not a causal estimate of an improvement's
value. The report separates random/draw modes and the latest held-out games,
and includes the first four rounds of twelve winning neural openings.

Reproduce the inventory or rebuild the presentation from its verified cache:

```sh
.venv-ai/bin/python ai/analyze_games.py --output data/analysis/new-openings --workers 6
.venv-ai/bin/python ai/analyze_games.py --output data/analysis/openings-20261026 --summarize
```

`deep_train.py --reward-shaping food-growth-v1 --transfer` reconstructs all
training targets with public-state potential differences:

`reward = raw_score_reward + (potential_after - potential_before) / 50`

With discount 1 and terminal potential zero, a completed game's sum remains
`final_score / 50 - initial_potential / 50`. Repeated action cycles cannot create
extra return. Actual Agricola points still select checkpoints and evaluate them.

The potential in `resources/agricola/rewards.edn` gives earlier feedback for:

- Meal coverage, reserving a seed and breeding pairs rather than valuing all stock
  as disposable food; coverage is capped to avoid rewarding food hoarding.
- Productive fields, legally housed breeding pairs and scheduled food. Baking
  uses actual oven batch limits and deducts an action opportunity cost.
- Earlier family growth and a spare room, weighted by estimated feeding support.
- Begging losses at the time they occur.

The food forecast is deliberately approximate: it does not model every
occupation/minor interaction or future competition for food spaces. A cooking
improvement supplies a bounded acquisition-efficiency estimate, not guaranteed
food. Current food and actual feeding outcomes still provide feedback.
The separate learning telemetry is not part of the model observation and cannot
expose opponents' hands. Game rules, legal actions and final scoring are unchanged.

Checkpoints fingerprint both the Clojure potential and Python reward wrapper.
Changing reward modes/definitions requires explicit weight transfer and fresh
replay targets. The baseline with an old value interpretation is retained as a
benchmark but cannot be mislabeled as a newly trained shaped checkpoint.

`ai/reward_pilot.py` runs a bounded comparison: identical starting weights, 32
well-fed teaching games (both seats score at least 15 with at most one begging
card), fresh seeds, update/search budgets, and held-out opponents. One arm uses
raw rewards, the other food/growth feedback. It freezes sources, preserves the
parent, and compares actual scores on paired new deals. Training/evaluation
metrics also record growth rounds, begging, food consumption and placements.
No model is automatically promoted.

```sh
.venv-ai/bin/python ai/reward_pilot.py \
  --resume data/muzero/overnight-20261026/training/best.pt \
  --analysis data/analysis/openings-20261026 \
  --output data/muzero/food-growth-pilot-20261026 --games 64
```
