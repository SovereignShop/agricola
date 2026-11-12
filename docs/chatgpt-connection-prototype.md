# ChatGPT account connection prototype

This local companion supports **Continue with ChatGPT** and an explicitly paired
game assistant without changing game authentication or the paused state migration. It uses
OpenAI's documented public-client registration flow for local open-source apps.
It does not need an API key or a commercial partner client ID. Account eligibility
and plan access are determined by OpenAI during sign-in.

## Try it

Requires Python 3.10+ on Linux/macOS. From the repository root:

```sh
python3 -m venv .venv-chatgpt
.venv-chatgpt/bin/pip install -r scripts/chatgpt/requirements.txt
.venv-chatgpt/bin/python scripts/chatgpt/connect.py
```

Open **http://127.0.0.1:1455** on the same computer. Use `--port 1456` if that
port is occupied. Use the printed `127.0.0.1` URL, not `localhost`.

1. Choose **Continue with ChatGPT**, complete OpenAI's sign-in, and review the
   optional permission to use your ChatGPT plan.
2. After returning, check the account and whether plan usage is enabled.
3. Choose **Check available models**, then a **Test with …** button. Only this
   explicit test sends a short Responses API request and consumes plan usage.
4. Restart the companion to check persistence. Tokens renew on demand near expiry.
5. Use **Sign out** to revoke the renewable session and clear local credentials.
   The account registration remains available for reconnection. If remote
   revocation cannot be confirmed, the page says so and points to ChatGPT settings.

Each connection has its own card and actions. Add another account with the button
below the cards; use the button inside a card to reconnect that registration.
Game seats are never linked by matching email addresses.

## Pair your own account with a game

1. Run this companion on the same computer as your browser. Each player runs their
   own copy and signs in with their own account.
2. In a seated game, open **Voice commands → Connect my ChatGPT**. You can minimize the panel after connecting.
3. Choose **Open local ChatGPT companion**. If you use a different companion port,
   copy the **ChatGPT pairing link** into its **Game pairing link** field instead.
4. Choose **Check available models** under your account, then **Use [model] with my game**.
   This enables model requests from that paired game using the selected account's plan.
   Pairing itself runs no inference.
5. Return to the game. Try “take wood”, “prepare a field in the top right”,
   “show me Partner’s farm”, or “pull up Partner’s played improvements”.
6. Clear requests execute immediately through native controls. Low-confidence
   choices ask for confirmation or clarification. Questions return answers.
   The overlay works above menus and card dialogs. Minimize it to see more of the board.

The one-use pairing link binds an authenticated game connection to the account and
model selected in the local companion. Its secret travels in a URL fragment, which
is removed from the displayed companion URL. Keep it private. A local companion
pairs with one game at a time. Closing the voice panel, leaving the game, disconnecting
either endpoint, signing out of the paired account, or the one-hour expiry ends the
pairing. Reconnect with a fresh link; pairing is not persisted.

The companion opens an outbound `/assistant` WebSocket to the game server; remote
games require WSS. No browser-to-localhost fetch or exposed inbound port is needed.
Discord users open the companion through the external browser. This requires a
computer running the companion; a phone-only setup is not provided. Restart the
game server and forward `/assistant` WebSocket upgrades through its reverse proxy.

The context contains public game state (round, phase, resources and farm summaries),
enabled native controls in the current view or topmost dialog, visible dialog/card
text, the last three exchanges, and any current choice. This can include your own
cards when shown; other players’ hidden hands and unrevealed round order are not
provided. Account credentials and game session tokens are excluded. Context stays
in memory and clears on account change or disconnect. Cancellation ignores late
answers; an already-started request may still consume allowance.

The model returns a supplied control ID, optional input value, high/low confidence,
a continuation flag, and a message, or a text answer. The browser validates the ID,
table revision, navigation, draft and current control snapshot before using the
native control. Normal game permissions and engine validation still apply.

The action catalog covers enabled native controls, including spatial farm/fence
choices, card faces, resource selectors, quantity inputs and player views. Multi-step
UI interactions get fresh context after each step and may consume multiple model
calls. They stop after a committed move, a changed table, or 24 control steps.
New decisions need another instruction. This is UI coverage, not an enumeration of
every possible compound engine command; natural-language quality depends on the
selected model. This uses Responses, not Decisions; ChatGPT-plan eligibility for
Decisions is not established by the documented flow.

## Storage and boundaries

Credentials default to `~/.local/state/eurozone-chatgpt/connections.json`.
Override the dedicated directory with `--state-dir /private/path`.
The directory is owner-only (0700); credential files are owner-only (0600).
These files contain plaintext credentials protected by filesystem permissions,
not an encrypted credential vault. Keep them out of source control and backups
shared with other people. The default is outside this repository.

The companion binds only to loopback, validates Host and POST Origin, checks form
tokens, and uses an HttpOnly SameSite cookie. OAuth attempts expire after ten
minutes and are consumed once. PKCE, state, nonce, signed identity, issuer,
audience, expiry, and reconnect identity are validated. A process lock and request
lock serialize token rotation. Tokens and authorization URLs are not logged or
returned to browser scripts. Only the ephemeral game pairing link uses tab storage;
OAuth credentials never use browser storage. Sign-out retains the
host/client mapping but clears access, refresh, and ID tokens.

This is a single-user local prototype, not a hosted authentication service.
It does not read the Codex credential cache or automate passwords/MFA. It does
not grant access to ChatGPT conversations or establish GPT-Live/Decisions access.
The documented plan-usage route is Responses. Microphone transcription is handled
separately by the existing voice interface.

## Verification

```sh
.venv-chatgpt/bin/python -m unittest discover -s scripts/chatgpt -p 'test_*.py' -v
```

Offline tests use locally signed identities and a fake provider. They cover the
full HTTP connect/callback/models/test/sign-out path, rejected identities and
cross-site requests, consent decline, single-use callbacks, expiry, reconnect
account mismatch, scope gating, rotating refresh, restart, exclusive writer,
secret-free HTML, incomplete response streams, constrained action output, pairing
URL validation, and ignoring in-flight answers after disconnect. They do not spend plan usage.

The optional Playwright check exercises a simulated provider in Chromium at
desktop and 390-pixel mobile widths, including form submission, callback, model
test, reload, sign-out, and reconnect:

```sh
.venv-chatgpt/bin/pip install playwright
.venv-chatgpt/bin/playwright install chromium
.venv-chatgpt/bin/python scripts/chatgpt/browser_check.py
```

Set `EUROZONE_BROWSER` to use an existing Chromium executable. Screenshots go to
`/tmp/eurozone-chatgpt-desktop.png` and `/tmp/eurozone-chatgpt-mobile.png`.
The browser check uses a local simulated approval page; it does not verify the
real OpenAI approval page or cross-site callback behavior.

After building the browser bundle, `python3 test/browser_assistant.py` exercises
the real relay, local companion and native game with a simulated model: two accounts,
questions, unavailable actions, automatic moves, low-confidence clarification,
player farms and played improvements, popup overlay, spatial card/fence choices,
bounded quantities, expandable sections, optional decline, persistence/playback, cancelled/stale answers, sign-out and mobile layout.
It creates fresh tables, including **Voice controls · Benefactor**, and never
overwrites an existing game.

The connection prototype was previously confirmed working by a user. Automated
assistant tests use simulated model output; natural-language quality against a
chosen live account/model must be checked after pairing it.

## Official protocol references

- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [Accounts, renewal, and sign-out](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [Model discovery and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [Current plan-usage limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)

This local open-source route should not be treated as authorization to offer plan
sharing directly from a hosted service. See the scope of the
[official overview](https://developers.openai.com/siwc/token-sharing-open-source).
