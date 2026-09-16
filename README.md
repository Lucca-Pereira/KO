# KO Kitchen

A personal Android app to run your kitchen:

- **Pantry** – everything you keep at home, products and spices. Each item is
  *In stock*, *Low*, or *Out*. Tap the status pill to cycle it.
- **Meal plan** – a weekly calendar. Add a meal from your recipe library or by writing
  one yourself. Tick a meal off as cooked, change its servings, or move it to another day.
- **Recipes** – your own library. Recipes are yours to keep: save, favourite, tag and
  search them, and plan the same one on as many days as you like. Every recipe is fully
  editable – photo, servings, times, tags, notes, ingredients and step-by-step method.
- **Ask Claude for recipes** – not a button in the app, but a workflow: you ask Claude
  for a dish idea, a written-out recipe, or a pantry/shopping update wherever you already
  talk to it, and import the small file it gives you from **Settings**. Editing an
  existing recipe this way is undoable, same as a manual edit. See
  [Ask Claude for recipes](#ask-claude-for-recipes) below for the exact file shape.
- **NAS sync (optional)** – if you run the small `server/` service on your own NAS, the
  app syncs with it automatically on foreground: Claude Desktop can add recipes and
  pantry updates directly over MCP, no file to carry over. See
  [Live sync with your own NAS](#live-sync-with-your-own-nas) below. The file-import
  workflow above still works with or without this — it's the fallback for whenever the
  NAS is unreachable, or if you never set one up at all.
- **Gym** – a food diary that knows about your kitchen. Calories and macros against a
  target worked out from your own height, weight and goal, and corrected over time from
  what your weight actually does rather than from a formula. Scan a barcode, search a
  bundled table of 135 common foods, or log a recipe you cooked. Creatine and protein
  shakes tracked as daily habits — anything with calories also lands in the diary.
- **Shopping list** – auto-filled from anything that runs out, plus manual entries.
  Tick an item off and it goes back to the pantry as *In stock*.

Ingredients are colour-coded against your pantry: **green** if you have it, **red** if
you don't. Mark one *Ran out* and it flips your pantry and lands on the shopping list.

KO doesn't call any AI itself — no API key, no billing baked into the app. Recipes and
pantry updates come from asking Claude directly, either importing a file it writes or,
if you've set up the optional NAS sync, straight from a Claude Desktop chat. Settings is
one tap away from any screen (the gear, top right).

## Install

1. Open the [Releases](../../releases) page and download the newest
   `ko-kitchen-vX.Y.Z.apk`.
2. On your phone, allow installing from your browser / files app
   (*Settings → Apps → Special access → Install unknown apps*).
3. Open the APK to install. Play Protect may warn — that is expected for a
   self-published app; choose *Install anyway*.

> **Upgrading to v0.9.2:** fixes the **Your details** (Gym → Body → Edit profile) Save
> button silently doing nothing on incomplete data — it now shows exactly what's missing
> instead of just sitting there disabled.
>
> **Upgrading to v0.9.1:** fixes a crash on v0.9.0 — tapping **Sync now** (or syncing on
> foreground) with a NAS configured could crash the app outright, because plain `http://`
> traffic was blocked at the network-security-config level regardless of destination.
>
> **Upgrading to v0.9.0:** adds optional live sync with a NAS-hosted service (see
> [Live sync with your own NAS](#live-sync-with-your-own-nas)) — entirely opt-in, nothing
> changes if you don't set a NAS URL in Settings. The database migration only adds new
> columns; existing recipes, pantry, plan and shopping list are untouched.
>
> **Upgrading to v0.8.1:** the in-app Claude API agent from v0.8.0 is gone again, one release
> later — real usage-based billing, however small, wasn't worth it for a personal app. There's
> no API key field in Settings any more; instead, ask Claude for recipes wherever you already
> talk to it and import the file it gives you (Settings → **Import from Claude**). Existing
> recipes, plan and shopping list are untouched.
>
> **Upgrading to v0.8.0:** the recipe bot moved off the NAS and into the app itself, talking to
> the Claude API directly. Superseded by v0.8.1 above one release later.
> Recipe search no longer goes through TheMealDB either way; recipes come from Claude now,
> one way or another.
>
> **Upgrading to v0.7.0:** adds the gym side. No existing data moves — the migration only
> creates new tables.
>
> **Upgrading to v0.4.0:** the database changes shape on first launch — recipes stop
> being throwaway attachments to a calendar day and become a library of their own, and
> duplicate copies of the same TheMealDB meal are merged. Export a backup from
> *Settings → Export* first, then install over the top **without uninstalling**.
>
> **Upgrading from v0.1.1 or earlier:** those builds were each signed with a
> throwaway key, so Android will refuse to install a newer one on top. **Uninstall
> KO Kitchen once**, then install v0.1.2+. From v0.1.2 on, every build uses one
> committed key (`app/ko.keystore`) so updates install straight over each other.

## Ask Claude for recipes

KO has no chat screen and calls no AI itself. Instead:

1. Ask Claude for what you want — a dish idea, a recipe written down, a pantry or
   shopping update — wherever you already talk to it (a Claude Code session, claude.ai).
   Point it at this README if it needs the file shape below.
2. Ask it to save that as a KO Kitchen import file (the JSON shape below) and get the
   file onto your phone (e.g. send it to yourself, or save it from a Claude Code session).
3. In the app, **Settings → Import from Claude**, pick that file.

Importing is additive: it never wipes anything, unlike Settings' full backup restore
(that one replaces everything from a JSON export — a different feature, for moving to
a new phone). Editing an existing recipe (by passing its `recipeId`) snapshots it
first, so it's one tap from **Undo** in the recipe editor's history — same safety net
a manual edit gets.

### The file shape

A JSON object with any combination of these top-level keys, all optional:

```json
{
  "recipes": [
    {
      "recipeId": 0,
      "title": "Chicken Teriyaki",
      "servings": 2,
      "prepMinutes": 10,
      "cookMinutes": 15,
      "notes": "Freezes well.",
      "tags": ["quick", "chicken"],
      "kcalPerServing": 420,
      "proteinG": 35,
      "carbsG": 30,
      "fatG": 14,
      "macroNote": "Estimated from the ingredient list.",
      "ingredients": [
        { "name": "chicken thigh", "amount": "400 g" },
        { "name": "soy sauce", "amount": "3 tbsp", "optional": false }
      ],
      "steps": [
        { "text": "Marinate the chicken for 10 minutes.", "minutes": 10 },
        { "text": "Sear and glaze until sticky." }
      ]
    }
  ],
  "pantryUpdates": [
    { "name": "onion", "status": "OUT" }
  ],
  "shoppingItems": ["flour", "sugar"],
  "mealPlan": [
    { "recipeTitle": "Chicken Teriyaki", "date": "2026-09-20", "slot": "DINNER" }
  ]
}
```

- `recipes[].recipeId` — omit or `0` to create a new recipe; an existing id replaces
  that recipe (title, ingredients, steps and all — send the whole thing, not a diff).
- `pantryUpdates[].name` matches an existing pantry item case-insensitively, or creates
  one; `status` is one of `IN_STOCK` / `LOW` / `OUT`.
- `mealPlan[].recipeTitle` must match a recipe already in the library — either already
  saved, or in this same file's `recipes` list. `slot` is `BREAKFAST` / `LUNCH` /
  `DINNER` / `OTHER`.

See `data/repo/AgentImportRepository.kt` for the authoritative shape if this drifts.

## Live sync with your own NAS

Optional, and off by default. If you run the `server/` service (see `server/README.md`
if present, or `server/docker-compose.yml`) on a NAS or box you control, KO can sync
with it directly instead of the file-import dance above:

1. Deploy `server/` — it needs Docker and a `KO_API_TOKEN` set in `server/.env`. It binds
   to the host's Tailscale address on purpose, so only your own devices can reach it.
2. In the app, **Settings → NAS sync**, enter the NAS's URL (e.g. `http://100.x.x.x:8090`)
   and the same token. Tap **Sync now**, or just open the app — it syncs automatically on
   every foreground.
3. Point Claude Desktop's MCP config at `http://<that-url>/mcp` with the same bearer
   token, and it can add recipes, update the pantry, and add shopping/plan entries
   directly — no file, no import step. It cannot delete anything; that stays phone-only.

Both syncing and the file-import workflow above use the same underlying merge logic
(`data/repo/RecipeMerge.kt`, `data/repo/SyncRepository.kt`), so they're safe to use
interchangeably — sync when the NAS is reachable, a file when it isn't.

## Build from source

Requires JDK 17 and the Android SDK (Android Studio Koala or newer).

```bash
git clone https://github.com/<you>/KO.git
cd KO
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests, including the database migrations
```

Database schemas are exported to `app/schemas/` and committed. Any change to an entity
needs a matching migration in `data/db/Migrations.kt` and a test in `MigrationTest` —
there is no destructive fallback, so a missing migration fails loudly instead of
quietly deleting the pantry.

Open the folder in Android Studio and press Run to deploy to a device/emulator.

## Tech

**App:** Kotlin · Jetpack Compose · Room (with real migrations and exported schemas) ·
DataStore · OkHttp + kotlinx.serialization · Coil · type-safe Navigation ·
single-module, manual DI. No network calls to any AI provider — recipes and pantry
updates arrive as a JSON file (`data/repo/AgentImportRepository.kt`) or, optionally, a
sync round trip with your own NAS (`data/repo/SyncRepository.kt`), both applied
additively.
CI in
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) builds the APK and
attaches it to every `v*` tag.

### Signing

CI runs `assembleRelease` and signs with the committed `app/ko.keystore`
(password `ko-kitchen`, alias `ko`). It is a self-signed key with no value beyond
tying updates to this package name, so it lives in the repo — fine for a
sideloaded personal app. For a Play Store release, swap in a private keystore via
repository secrets.

## Credits

Barcode lookups use [Open Food Facts](https://world.openfoodfacts.org), a free, open
database. Recipes are found and written by [Claude](https://www.anthropic.com/claude).
