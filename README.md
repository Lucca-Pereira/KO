# KO Kitchen

A personal Android app to run your kitchen:

- **Pantry** – everything you keep at home, products and spices. Each item is
  *In stock*, *Low*, or *Out*. Tap the status pill to cycle it.
- **Meal plan** – a weekly calendar. Add a meal from your recipe library, by asking
  the recipe bot, by searching [TheMealDB](https://www.themealdb.com), or by writing
  one yourself. Tick a meal off as cooked, change its servings, or move it to another day.
- **Recipes** – your own library. Recipes are yours to keep: save, favourite, tag and
  search them, and plan the same one on as many days as you like. Every recipe is fully
  editable – photo, servings, times, tags, notes, ingredients and step-by-step method.
- **Ask about a recipe** – a conversation attached to each one. Swaps, scaling, technique,
  what to do without an oven. If you ask for a change it comes back as a proposal you review
  line by line before it is applied, and every applied change can be undone.
- **Gym** – a food diary that knows about your kitchen. Calories and macros against a
  target worked out from your own height, weight and goal, and corrected over time from
  what your weight actually does rather than from a formula. Scan a barcode, search a
  bundled table of 135 common foods, or log a recipe you cooked. Creatine and protein
  shakes tracked as daily habits — anything with calories also lands in the diary.
- **Shopping list** – auto-filled from anything that runs out, plus manual entries.
  Tick an item off and it goes back to the pantry as *In stock*.

Ingredients are colour-coded against your pantry: **green** if you have it, **red** if
you don't. Mark one *Ran out* and it flips your pantry and lands on the shopping list.

The "bot" is **your own hardware** – a small service on your NAS wrapping
[Ollama](https://ollama.com), no cloud account and no API key. It proposes dish ideas,
writes recipes, and answers questions about the one you are cooking.

## Install

1. Open the [Releases](../../releases) page and download the newest
   `ko-kitchen-vX.Y.Z.apk`.
2. On your phone, allow installing from your browser / files app
   (*Settings → Apps → Special access → Install unknown apps*).
3. Open the APK to install. Play Protect may warn — that is expected for a
   self-published app; choose *Install anyway*.

> **Upgrading to v0.7.0:** adds the gym side. No existing data moves — the migration only
> creates new tables.
>
> **Upgrading to v0.5.0:** the app now talks to the KO brain service instead of to Ollama
> directly, so the old server setting does not carry over — it pointed at Ollama's own port.
> Deploy [`server/`](server/) and set the new URL and token in Settings.
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

## Connect the recipe bot

The AI lives in [`server/`](server/) — a small service you run on your NAS. It owns every
prompt, wraps Ollama, and serves both this app and Claude (over MCP). See
[`server/README.md`](server/README.md) for the deploy.

Once it is up, in **Settings → Recipe bot**:

1. **Brain URL** — `http://<nas>:8090`. Port 8090, not Ollama's 11434; the app no longer talks
   to Ollama directly.
2. **Access token** — the `KO_API_TOKEN` from the server's `.env`.
3. **Test connection**.

The token is kept in its own store, excluded from both Android's cloud backup and KO's own JSON
export, so restoring on a new phone asks for it again rather than carrying it around in a file.

If the brain is unreachable a banner says so, once, at the top of every screen — and suggestions
fall back to matching your pantry against TheMealDB, so the app still does something useful.

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
DataStore · OkHttp + kotlinx.serialization (incl. SSE) · Coil · type-safe Navigation ·
single-module, manual DI.
**Server:** Python · FastAPI · FastMCP · Ollama. See [`server/`](server/).
CI in
[`.github/workflows/android.yml`](.github/workflows/android.yml) builds the APK and
attaches it to every `v*` tag.

### Signing

CI runs `assembleRelease` and signs with the committed `app/ko.keystore`
(password `ko-kitchen`, alias `ko`). It is a self-signed key with no value beyond
tying updates to this package name, so it lives in the repo — fine for a
sideloaded personal app. For a Play Store release, swap in a private keystore via
repository secrets.

## Credits

Recipe content © [TheMealDB](https://www.themealdb.com). Licensed under MIT.
