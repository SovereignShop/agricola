# Voice command prototype

**Status: disabled at the user’s request.** Voice controls and hosted voice/assistant
endpoints are unavailable. The implementation below is retained for future work.

Open a seated game, choose **Voice commands**, then **Speak**. Allow the browser
to use the microphone. Say “take wood”, “fishing”, “take grain”, or the name of an
available resource/animal space. With local transcription, choose **Finish recording**
(or wait for the eight-second limit). Clear commands run immediately. The panel
also accepts typed commands. Uncertain model choices offer confirmation or clarification.

For hands-free use, choose **Start continuous listening** once. The microphone
stays open between commands. Pause for about a second after each phrase; local
speech detection sends each utterance to the existing transcription worker. Idle
silence is not uploaded, and neither recordings nor transcripts are stored by the
speech service. Wait for transcription to finish before speaking the next command.
Assistant responses appear in the game panel; this mode adds continuous voice input.
The game takes a fresh context stamp at the beginning of every utterance, so a
changed menu or game revision still invalidates an in-flight command.

Say **“stop listening”**, click **Stop listening**, or close the voice panel to stop.
Minimizing the panel keeps the microphone active and leaves a stop button visible.
The external microphone companion has the same mode. It stays active across game
moves and desktop tab changes, but closes its microphone when the pairing closes.
Browser/device suspension may require starting listening again. Continuous mode is
explicitly enabled each time; it never starts automatically after reload. All speech
heard while it is enabled is treated as input; this is not a wake-word mode.

Local continuous capture uses an [AudioWorklet](https://developer.mozilla.org/en-US/docs/Web/API/AudioWorkletNode)
to collect mono PCM and detect speech, with a short pre-roll and one second of
silence to finish a phrase. Utterances longer than nine seconds are discarded rather
than executing a truncated instruction. Capture is paused during transcription and
resumes after silence. Without the worker it uses the browser's
[continuous recognition mode](https://developer.mozilla.org/en-US/docs/Web/API/SpeechRecognition/continuous),
restarting ended sessions until stopped. Permission, device, or service errors stop
the mode instead of repeatedly retrying.

Without a paired ChatGPT account, the command vocabulary is exact. When local transcription is enabled, the browser
records a short clip and sends it to the game server, where a loopback Whisper
worker transcribes it on the CPU. Audio stays in memory and is not saved. No API
key is needed, and transcription does not consume ChatGPT usage. Microphone capture
requires HTTPS or localhost.

For natural-language commands and questions, use **Connect my ChatGPT** and follow
the [local companion pairing steps](chatgpt-connection-prototype.md#pair-your-own-account-with-a-game).
Once paired, submitted commands and questions use that player's selected plan.
The model receives the enabled controls in the current game view or topmost dialog,
visible dialog/card text, public game context, and recent conversation. It selects
native controls, including farm cells, fence edges, resource choices, quantities,
card abilities, and player navigation. Clear commands run immediately; low-confidence
choices need confirmation or clarification. A multi-step command can use several
model calls. It stops when a move commits, so a newly triggered decision needs a
new instruction. “Show me Partner’s farm” and “pull up Partner’s played improvements”
work outside your turn. The floating panel remains usable above dialogs; minimize it
without disconnecting. Close it or choose **Disconnect ChatGPT** to end the pairing.

Without the worker configuration, the prototype uses the browser's SpeechRecognition
service. That service may send audio to the browser vendor; some Chromium builds
expose the API but cannot connect to its remote service. Local transcription avoids
that dependency. Typed commands remain available in either mode.

## Local transcription setup

Install Python 3, ffmpeg, and the worker's dependencies in a virtual environment:

```sh
python3 -m venv .venv-voice
.venv-voice/bin/python -m pip install -r scripts/voice/requirements.txt
.venv-voice/bin/python scripts/voice/transcribe.py
```

The first run downloads `base.en`; subsequent runs use the cached model. The worker
listens on `127.0.0.1:59300`. Wait for “Local transcription ready”, then start the
game server with `EUROZONE_TRANSCRIBE_URL=http://127.0.0.1:59300/transcribe`, preserving
its existing `EUROZONE_DATA` setting. Refresh the browser after enabling it.
`GET /voice/config` reports whether local transcription is configured.

The game server authorizes uploads with the existing session or a live paired
microphone capability. It limits audio to 2 MB and one transcription at a time;
the worker rejects clips longer than 12 seconds. It never issues game commands.
Cancellation discards the recording or ignores an in-flight transcription. Before
execution, the browser checks that the table revision and current controls still match.

“Take wood” means Forest, “take clay” means Clay Pit, and “take reed” means Reed
Bank. For other spaces, use the printed name. “Take stone” asks for a quarry name
if both quarries are available. Without ChatGPT, farm construction, card
choices, and harvest decisions use the existing interface. With ChatGPT, these
controls are included whenever the native interface makes them available.
Animal pickups can trigger the normal housing decision in the game.

## Discord microphone companion

In the Activity, **Voice commands → Open voice companion** opens a small browser
page. Keep the original game open. The companion sends final transcripts back
to that game over `/voice`; it does not join the peer table as a second game
client or take over the player's seat.

The game authenticates to the relay using its existing session and creates a
random, single-use pairing capability. The link carries only that temporary
capability in its fragment, which the companion removes from the displayed URL.
Treat the link as private: possession delegates speech input to the open game.
No Discord or ChatGPT account tokens are put in the link. Pairing expires after
one hour or when either endpoint disconnects; use a fresh link to reconnect.

The relay forwards text only, performs no game actions, and does not persist
transcripts or audio. Commands still pass through the normal authenticated game
command handler. The browser checks the acting seat, decision state, connection,
pending command, table revision, navigation, draft, current controls, and authority
epoch before executing a choice. Stale responses and duplicate confirmations do not submit moves.

The server must be restarted with this code for the companion endpoint, and an
HTTPS reverse proxy must forward WebSocket upgrades for `/voice`. The Discord
JavaScript bundle must be rebuilt. Existing game data need not be migrated.
The local-browser Speak button works without the companion relay.

One-shot microphone capture stops when its page is hidden. Continuous mode remains
active while the page is open, subject to browser/device suspension. Mobile app
switching and real Discord launch behavior still need device testing.

## Verification

- Advanced browser build and card print coverage pass.
- `eurozone.voice-test`: exact vocabulary, native resource pickup, occupied and
  ambiguous spaces, control gates, authentication, one-use pairing, message
  deduplication, isolation, disconnect cleanup, transcription authorization and
  upload bounds.
- `test/browser_voice.py`: simulated SpeechRecognition events drive real game
  moves and persistence. Tests cancellation, stale recognition, denied microphone,
  observer restrictions, actual companion WebSockets, and a 390px mobile layout.
- `test/browser_voice_continuous.py`: real AudioWorklet speech detection, WAV
  encoding and local Whisper; repeated commands on one microphone, silent idle,
  stale context, spoken stop, late cancellation, minimized stop control, and the
  external microphone companion across revisions and disconnect.
- `test/browser_voice_recording.py`: synthesized speech through real browser
  MediaRecorder encoding and local Whisper, then a real automatic move; checks
  microphone cleanup and recording cancellation.
- `test/browser_assistant.py`: real local companion/relay with a simulated model;
  account isolation, immediate actions, low-confidence confirmation, player views,
  modal controls, spatial card/fence choices, bounded quantities, expandable sections,
  optional decline, persistence/playback,
  cancelled/stale answers, sign-out and mobile layout. The prepared
  **Voice controls · Benefactor** table is also available for manual review.
- `test/browser_discord.py`: the production Discord SDK and compiled game run
  inside an Activity iframe with simulated Discord OAuth and RPC. Checks external
  voice and ChatGPT pairing links use the public website, then relays continuous
  speech into a real game move visible to both players.
- Physical microphone hardware and a real Discord Activity launch remain to be
  tested in the Discord client; the automated test simulates Discord's endpoints.

Run the focused browser check after `make build`, with Playwright installed:

```sh
python3 test/browser_voice.py
EUROZONE_TRANSCRIBE_URL=http://127.0.0.1:59300/transcribe python3 test/browser_voice_recording.py
```

`EUROZONE_BROWSER` can select an installed Chromium executable. The test creates
an isolated server and fresh **Voice command prototype** table. No existing game
is overwritten. Unit tests are included in `make test`.

Protocol references:
[SpeechRecognition](https://developer.mozilla.org/en-US/docs/Web/API/SpeechRecognition),
[Discord external links](https://docs.discord.com/developers/developer-tools/embedded-app-sdk#openexternallink).
