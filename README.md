# KO Kitchen

A personal Android app to run your kitchen:

- **Pantry** – everything you keep at home, products and spices. Each item is
  *In stock*, *Low*, or *Out*. Tap the status pill to cycle it.
- **Meal plan** – a weekly calendar. Add dishes per day/slot, either by asking the
  recipe bot, searching [TheMealDB](https://www.themealdb.com), or entering one by hand.
- **Dish view** – the ingredient list, each line **green** (you have it) or **red**
  (you don't), a link to the real recipe on the web, and the instructions. Mark an
  ingredient *Ran out* and it flips your pantry and lands on the shopping list.
- **Shopping list** – auto-filled from anything that runs out, plus manual entries.
  Tick an item off and it goes back to the pantry as *In stock*.

The "bot" is **your own [Ollama](https://ollama.com) server** on your home network –
no cloud account, no API key. It only proposes dish ideas; the actual recipes,
ingredients, photos and source links come from TheMealDB.

## Install

1. Open the [Releases](../../releases) page and download the newest
   `ko-kitchen-vX.Y.Z.apk`.
2. On your phone, allow installing from your browser / files app
   (*Settings → Apps → Special access → Install unknown apps*).
3. Open the APK to install. The build is debug-signed, so Play Protect may warn –
   that is expected for a self-published app.

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
./gradlew testDebugUnitTest    # unit tests
```

Open the folder in Android Studio and press Run to deploy to a device/emulator.

## Tech

Kotlin · Jetpack Compose · Room · DataStore · OkHttp + kotlinx.serialization ·
Coil · single-module, manual DI. CI in
[`.github/workflows/android.yml`](.github/workflows/android.yml) builds the APK and
attaches it to every `v*` tag.

### Optional: release signing

The CI ships a debug-signed APK. To publish a release-signed build instead, add a
keystore and wire a `signingConfig` into `app/build.gradle.kts`, passing the
secrets from repository settings into the Gradle build.

## Credits

Recipe content © [TheMealDB](https://www.themealdb.com). Licensed under MIT.
