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
- **Shopping list** – auto-filled from anything that runs out, plus manual entries.
  Tick an item off and it goes back to the pantry as *In stock*.

Ingredients are colour-coded against your pantry: **green** if you have it, **red** if
you don't. Mark one *Ran out* and it flips your pantry and lands on the shopping list.

The "bot" is **your own [Ollama](https://ollama.com) server** on your home network –
no cloud account, no API key. It proposes dish ideas; recipe content comes from
TheMealDB or from you.

## Install

1. Open the [Releases](../../releases) page and download the newest
   `ko-kitchen-vX.Y.Z.apk`.
2. On your phone, allow installing from your browser / files app
   (*Settings → Apps → Special access → Install unknown apps*).
3. Open the APK to install. Play Protect may warn — that is expected for a
   self-published app; choose *Install anyway*.

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

1. On your computer, install Ollama and pull a model:
   ```bash
   ollama pull llama3.1
   ```
2. Point the app at the server in **Settings → Ollama server URL**:
   - **Android emulator:** `http://10.0.2.2:11434` (the pre-filled default –
     `10.0.2.2` is the emulator's alias for the host's `127.0.0.1`).
   - **Real phone:** start Ollama with `OLLAMA_HOST=0.0.0.0` (Windows: set it as an
     environment variable and restart Ollama), find your computer's Wi-Fi IP
     (`ipconfig` / `ip addr`), and use `http://<that-ip>:11434`.
3. Set the model name to what you pulled and tap **Test connection**.

If the bot is unreachable, suggestions fall back to TheMealDB matches for what is
in your pantry, so the app still works offline-of-bot.

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

Kotlin · Jetpack Compose · Room (with real migrations and exported schemas) ·
DataStore · OkHttp + kotlinx.serialization · Coil · type-safe Navigation ·
single-module, manual DI. CI in
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
