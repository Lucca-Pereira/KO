# KO Kitchen

**A personal Android app for running a kitchen and a diet — that you can also just talk to.**

KO Kitchen keeps track of what's in your pantry, what you're cooking this week, what you need
to buy, and what you've eaten against a calorie and macro target that adapts to your real
weight trend. Through an optional self-hosted sync service, it doubles as a **Claude connector**:
from the ordinary Claude app on your phone or computer you can say *"what can I cook tonight?"*,
*"plan dinners for next week"*, *"had three eggs and toast for breakfast"* or *"weighed 78.4 this
morning"*, and it happens — the app only has to be opened to look at the results.

Kotlin · Jetpack Compose · Room · Python · FastAPI · MCP · Docker · Tailscale · GitHub Actions

---

## What it does

**Kitchen**
- **Pantry** — every product and spice, each *In stock*, *Low* or *Out*.
- **Recipes** — a fully editable library (photo, servings, times, tags, notes, ingredients,
  steps), with every ingredient colour-coded against the pantry: green if you have it, red if
  you don't. Marking one *ran out* updates the pantry and the shopping list in one tap.
- **Meal plan** — a weekly calendar built from the library; tick meals off as cooked.
- **Shopping list** — fills itself from whatever runs out; ticking an item off restocks it.

**Gym**
- **Food diary** — barcode scanning (Open Food Facts), a bundled table of common foods,
  logging a recipe you cooked, or quick entries.
- **Adaptive targets** — calories and macros start from a formula (height, weight, goal) and
  are then corrected from what your smoothed weight trend actually does, rather than trusting
  the formula forever.
- **Body metrics** — weigh-ins and measurements, charted as raw readings plus an exponential
  moving-average trend.
- **Supplements** — daily habits with streaks and adherence; anything with calories (a protein
  shake) also lands in the diary automatically.

**Talk to it**
- With the sync service running, Claude can read the pantry, recipe library, plan, shopping
  list and food diary, and write back: save recipes, update the pantry, plan meals, add to the
  shopping list, log food (estimating macros itself), tick off supplements, and record
  weigh-ins — including correcting its own gym entries ("actually it was two eggs").

## How it works

```
 ┌─────────────────┐   HTTPS + bearer token   ┌───────────────────────┐   HTTPS + Google OAuth   ┌──────────────────┐
 │  Android app    │ ───── POST /v1/sync ───► │  ko-sync (Docker)     │ ◄──── MCP at /mcp ────── │  Claude (phone,  │
 │  Room database  │ ◄──── push then pull ─── │  FastAPI + FastMCP    │                          │  desktop, web)   │
 │  (source of     │                          │  SQLite               │                          └──────────────────┘
 │   truth)        │                          └───────────┬───────────┘
 └─────────────────┘                                      │ 127.0.0.1 only
                                                Tailscale Funnel (public HTTPS)
```

- **The phone is the source of truth** and works fully offline. Nothing in the app calls an AI
  provider — no API key, no usage billing. Claude reaches the data *through the connector*, on
  the user's own Claude subscription.
- **The sync service is the always-on middle.** Claude's servers can't reach a phone, so a small
  service on a home server holds a copy. The phone syncs to it every time the app opens; Claude
  reads and writes the same store through MCP tools.
- **Sync is one request, push then pull.** The phone sends what changed since its last sync plus
  the full list of ids it still has; the server applies the push, drops anything the phone has
  deleted, and returns everything newer than the phone's last sync — including what Claude wrote.
- **No-server fallback.** Without the service, you can still ask Claude in any chat for a recipe
  or pantry update, have it write a small JSON file, and import it from Settings.

## Engineering highlights

The parts I'd point a reviewer at:

- **Idempotent sync by construction.** Every row gets a UUID assigned *on the phone* at creation,
  never by the server. A push whose response is lost to a dropped connection retries with the
  same ids, so the server upserts instead of duplicating.
  ([`SyncRepository.kt`](app/src/main/java/com/lucca/ko/data/repo/SyncRepository.kt))
- **Deletion without tombstone bookkeeping on the phone.** Each sync carries the ids the phone
  still has; the server prunes only rows it knows the phone has *already seen* (written at or
  before its last sync), so an item Claude added a second ago can't be mistaken for a deletion.
  ([`store.py`](server/ko_sync/store.py) `prune_absent`)
- **Edits from Claude that still reach the phone.** Gym entries Claude corrects or removes use
  last-write-wins plus a `deleted` tombstone the phone applies on pull; a server-side edit is
  always stamped newer than the row it replaces, so a correction made in the same millisecond as
  the original can't silently lose the tie. (Found by a test, not in production.)
- **Real database migrations, tested.** Room with no destructive fallback: a missing migration
  crashes on launch rather than quietly wiping someone's pantry. Nine schema versions with
  hand-written migrations; Robolectric tests seed a realistic v2 database and walk it forward
  through every later migration, asserting both row survival and schema validity at each
  step. Schemas are exported and CI fails if they're stale.
- **History that doesn't rewrite itself.** Diary entries carry their own macros rather than
  pointing at a food, so correcting a food next month can't change last month's totals, and a
  day total stays a plain `SUM` with no joins. Targets are point-in-time for the same reason.
- **Secure by default for a public endpoint.** The service listens on localhost only and is
  exposed through Tailscale Funnel. Claude authenticates with Google OAuth (via FastMCP's OAuth
  proxy) restricted to an email allowlist that's re-checked on every request; the phone uses a
  separate bearer token. The server **refuses to start** on any configuration that would leave it
  open (a public URL with no token, Google sign-in with no allowlist).
- **Tools designed for an LLM caller.** MCP tools validate input and return actionable errors
  ("no single supplement matches 'fish oil' — known: Creatine, Whey protein") instead of
  crashing, carry read-only/destructive hints so Claude apps can skip permission prompts for
  reads, and never let Claude delete kitchen data at all.

## Tech

| | |
|---|---|
| **App** | Kotlin, Jetpack Compose (Material 3), Room (SQLite) with exported schemas, DataStore, OkHttp + kotlinx.serialization, Coil, ML Kit code scanner for barcodes, type-safe Navigation. Single module, manual dependency injection (one `AppContainer`, no Hilt). |
| **Server** | Python 3.12, FastAPI, FastMCP (MCP over streamable HTTP, Google OAuth proxy), SQLite, Pydantic. Docker Compose. |
| **Infra** | Self-hosted on a home server; Tailscale Funnel for public HTTPS without opening ports. |
| **Quality** | 200+ Android unit tests (Robolectric, real in-memory Room — no mocked DAOs, because cascade rules and unique indices *are* the behaviour), 40+ server tests (pytest, httpx ASGI), ruff. |
| **CI/CD** | GitHub Actions: unit tests, schema-drift check, signed release APK attached to every `v*` tag. |

## Project layout

```
app/src/main/java/com/lucca/ko/
  data/db/        Room entities, DAOs, migrations
  data/repo/      one repository per feature, plus Sync / GymSync / AgentImport / Backup
  data/remote/    Open Food Facts and sync HTTP clients
  domain/         pure, unit-tested logic: ingredient matching, measures, nutrition maths
  ui/<feature>/   a Compose screen + ViewModel per screen
server/ko_sync/
  api/rest.py     the phone's sync endpoint
  mcp_server.py   kitchen MCP tools · mcp_gym.py gym MCP tools
  store.py        SQLite store · google_auth.py OAuth + allowlist · config.py
```

## Running it

**The app:** download the APK from [Releases](../../releases), or build it:

```bash
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests, including every database migration
```

Requires JDK 17 and the Android SDK. Release builds are signed with the committed
`app/ko.keystore` — a self-signed key that only ties updates to the package name, fine for a
sideloaded app; a store release would move it into CI secrets.

**The sync service and Claude connector** (optional):

1. **Public HTTPS.** Run `server/` with Docker on a machine with Tailscale, and expose it with
   `tailscale funnel --bg --https=443 http://localhost:8090`. Use port 443 — in practice
   Claude's connector wouldn't connect on Funnel's 8443 — and give the service that port to
   itself, since Funnel makes everything on a port public.
2. **Google OAuth client** (free) in Google Cloud Console → *Web application*, redirect URI
   `<public URL>/auth/callback`; keep it in *Testing* with your account as a test user.
3. **Configure** `server/.env` from [`.env.example`](server/.env.example) — token, public URL,
   Google client id/secret, allowed email(s), timezone — then `docker compose up -d --build`.
4. **Phone:** Settings → NAS sync → the public URL and token.
5. **Claude:** Settings → Connectors → *Add custom connector* → `<public URL>/mcp`, sign in with
   Google. It then appears in the Claude apps on every device.

```bash
cd server && python -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest && .venv/bin/ruff check .
```

<details>
<summary><b>The no-server import file format</b></summary>

Ask Claude for a recipe or pantry update in any chat, have it write a file in this shape, and
import it from **Settings → Import from Claude**. Importing is additive — it never wipes
anything — and editing an existing recipe snapshots it first, so it's one tap from Undo.

```json
{
  "recipes": [
    {
      "recipeId": 0,
      "title": "Chicken Teriyaki",
      "servings": 2,
      "prepMinutes": 10,
      "cookMinutes": 15,
      "tags": ["quick", "chicken"],
      "kcalPerServing": 420, "proteinG": 35, "carbsG": 30, "fatG": 14,
      "ingredients": [{ "name": "chicken thigh", "amount": "400 g" }],
      "steps": [{ "text": "Marinate the chicken for 10 minutes.", "minutes": 10 }]
    }
  ],
  "pantryUpdates": [{ "name": "onion", "status": "OUT" }],
  "shoppingItems": ["flour", "sugar"],
  "mealPlan": [{ "recipeTitle": "Chicken Teriyaki", "date": "2026-09-20", "slot": "DINNER" }]
}
```

`recipeId` omitted or `0` creates a recipe; an existing id replaces it wholesale. Pantry names
match case-insensitively; status is `IN_STOCK` / `LOW` / `OUT`. A plan entry's `recipeTitle`
must match a saved recipe or one in the same file; slot is `BREAKFAST` / `LUNCH` / `DINNER` /
`OTHER`. The authoritative shape is
[`AgentImportRepository.kt`](app/src/main/java/com/lucca/ko/data/repo/AgentImportRepository.kt).

</details>

## History

The AI side went through three designs before this one — a local LLM on the home server (not
good enough), an in-app Claude API agent (usage billing wasn't worth it for a personal app), and
a file-import workflow (kept as the fallback) — before settling on the connector, which needs no
API key and works from any Claude app. Version-by-version notes are in
[CHANGELOG.md](CHANGELOG.md).

## Credits

Barcode data from [Open Food Facts](https://world.openfoodfacts.org), an open database.
Built with help from [Claude](https://www.anthropic.com/claude).
