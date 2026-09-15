# KO Kitchen

A personal Android app to run your kitchen:

- **Pantry** – everything you keep at home, products and spices. Each item is
  *In stock*, *Low*, or *Out*. Tap the status pill to cycle it.
- **Meal plan** – a weekly calendar. Add a meal from your recipe library, by asking
  the agent, or by writing one yourself. Tick a meal off as cooked, change its servings,
  or move it to another day.
- **Recipes** – your own library. Recipes are yours to keep: save, favourite, tag and
  search them, and plan the same one on as many days as you like. Every recipe is fully
  editable – photo, servings, times, tags, notes, ingredients and step-by-step method.
- **The agent** – a conversation, not a search box. Ask what to cook, get a recipe
  written down, ask about swaps or scaling for one you already have open, or open it
  from a specific day on the plan. It can save recipes, add them to your plan and add
  things to your shopping list — but only ever proposes it first. Every proposal comes
  back as a review you approve line by line before anything is written, and every
  applied change can be undone.
- **Gym** – a food diary that knows about your kitchen. Calories and macros against a
  target worked out from your own height, weight and goal, and corrected over time from
  what your weight actually does rather than from a formula. Scan a barcode, search a
  bundled table of 135 common foods, or log a recipe you cooked. Creatine and protein
  shakes tracked as daily habits — anything with calories also lands in the diary.
- **Shopping list** – auto-filled from anything that runs out, plus manual entries.
  Tick an item off and it goes back to the pantry as *In stock*.

Ingredients are colour-coded against your pantry: **green** if you have it, **red** if
you don't. Mark one *Ran out* and it flips your pantry and lands on the shopping list.

The agent talks to **the Claude API directly, using your own Anthropic API key** — no
NAS, no server to run, no Ollama. It finds and writes recipes, answers questions about
the one you're cooking, and estimates a recipe's macros. Settings is one tap away from
any screen (the gear, top right), and that's where the key lives.

## Install

1. Open the [Releases](../../releases) page and download the newest
   `ko-kitchen-vX.Y.Z.apk`.
2. On your phone, allow installing from your browser / files app
   (*Settings → Apps → Special access → Install unknown apps*).
3. Open the APK to install. Play Protect may warn — that is expected for a
   self-published app; choose *Install anyway*.

> **Upgrading to v0.8.0:** the recipe bot moves off your NAS and into the app itself — it now
> talks to the Claude API directly, using your own Anthropic API key, and the old NAS URL and
> token are gone from Settings along with `server/`. Recipe search no longer goes through
> TheMealDB either; the agent finds and writes recipes itself. Existing recipes, plan and
> shopping list are untouched — enter an API key in Settings to pick the agent back up.
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

## Connect the agent

1. Get an API key from [console.anthropic.com](https://console.anthropic.com).
2. Open **Settings** (the gear icon, top right of any screen) and paste it into
   **Anthropic API key**.
3. **Test key**.

The key is kept in its own store, excluded from both Android's cloud backup and KO's own JSON
export, so restoring on a new phone asks for it again rather than carrying it around in a file.
It never leaves the phone except in requests to `api.anthropic.com`.

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
single-module, manual DI. The recipe agent is the Claude Messages API, called directly
from the phone with tool use for saving recipes, planning meals and shopping list
items — see `data/remote/claude/`.
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
