# KO brain

The AI service behind KO Kitchen. One process, two front doors:

- **`/v1/*`** — REST, for the phone.
- **`/mcp`** — MCP over streamable HTTP, for Claude.

Both call the same functions in `ko_brain/tools/`. There is one implementation of each
capability, not two.

## Why it exists

The app used to talk to Ollama directly, which meant every prompt lived inside the APK. Tuning
a prompt meant a build, a sideload and an install. It also meant the phone did its own
twenty-request serial fan-out to TheMealDB on every suggestion refresh, uncached.

Moving it here buys three things: prompts are files you edit and restart, upstream lookups are
cached and concurrent, and the model gets a real JSON Schema instead of `format: "json"` and a
hopeful parser.

## The rule that keeps this simple

> **The phone's Room database is the only source of truth. The NAS is stateless except for
> caches.**

Deleting `/data/cache.sqlite3` costs one round trip to TheMealDB. Nothing here is backed up
because nothing here is yours.

The consequence to accept: **the MCP tools are read and compute only.** Claude can suggest, write
recipes, look things up and estimate nutrition. It cannot touch your pantry, shopping list or
meal plan. Making it able to would make the NAS a second source of truth and turn this into a
bidirectional offline-sync problem with conflict resolution — an order of magnitude more work
than everything else here put together.

## Running it

```bash
cp .env.example .env
# edit .env: set KO_API_TOKEN (openssl rand -hex 32)
docker compose up -d --build
curl http://100.67.219.26:8090/health
```

Pull the models on the NAS first:

```bash
ollama pull qwen2.5:7b-instruct
ollama pull qwen2.5:3b
```

`/health` reports `ollama.missing` for anything configured but not installed, so check it after
a first run. The service falls back to whatever *is* installed rather than failing, so a stale
model name degrades quietly instead of breaking the app — check the log for the warning.

### Bind address

`docker-compose.yml` publishes on `100.67.219.26:8090`, the NAS's Tailscale address. That is
what decides who can reach the service: the tailnet can, the LAN cannot. Change that IP and you
change who has access. Do not publish on `0.0.0.0`.

The bearer token is a second lock, not the only one — anything on the tailnet can reach the
port, and "my own devices" grows over time.

### Without Docker

```bash
pip install -e ".[dev]"
KO_API_TOKEN=... KO_OLLAMA_URL=http://127.0.0.1:11434 python -m ko_brain.main
```

## Connecting Claude

Add the MCP endpoint to Claude Code or Claude Desktop:

```json
{
  "mcpServers": {
    "ko-kitchen": {
      "type": "http",
      "url": "http://100.67.219.26:8090/mcp",
      "headers": { "Authorization": "Bearer YOUR_TOKEN" }
    }
  }
}
```

Eight tools: `suggest_dishes`, `write_recipe`, `search_recipes`, `get_recipe`, `translate_foods`,
`estimate_nutrition`, `lookup_barcode`, `search_foods`.

## Tuning prompts

Prompts are Markdown in `ko_brain/prompts/`. Edit and restart. Set `KO_RELOAD_PROMPTS=1` and
they are re-read on every request, so tuning is edit-and-retry.

They are prompt *and* schema: the Pydantic models in `schemas.py` marked "LLM output" become the
JSON Schema handed to Ollama, so a field's name and description are as much a part of the
instruction as the Markdown. Making a field required is often a more effective fix than another
sentence of prompt — a model that can answer `null` frequently will.

## Nutrition: table first, model second

A 7B model's macro estimates vary by 20%+ between runs on the same input, which makes a calorie
tracker decorative. So every ingredient is matched against `data/foods_seed.json`
deterministically, and only the ones the table has never heard of go to the model. The response
reports `method` per ingredient (`TABLE` / `AI` / `UNKNOWN`) and an overall `coverage`, so the UI
can be honest about a number it half-guessed.

Add or correct foods by editing `tools/build_food_seed.py` and re-running it. The same JSON
ships inside the APK, so both sides agree.

## Things worth knowing

- **`keep_alive` is a number, not a string.** Ollama parses it as a Go duration; `"-1"` fails
  with `missing unit in duration` while `-1` means "keep the model loaded forever". `-1` is what
  stops the first request each morning paying a 30-90s cold load.
- **One generation at a time.** Ollama on NAS CPU serves one; a semaphore makes that explicit
  and `/health` reports `busy` and `queue_depth` rather than letting requests pile up invisibly.
- **A product with no macros is a 404.** Open Food Facts often has a product whose contributor
  filled in per-serving values only. Returning zeroes would log a zero-calorie yoghurt; a 404
  sends the phone to manual entry.
- **Model choice matters more than prompt wording** for the structured paths. `llama3.1:8b`
  writes decent recipes but is unreliable at applying a change consistently — asked to make a
  recipe dairy-free it will swap the cream and leave the butter. `qwen2.5:7b-instruct` is the
  recommendation for `KO_MODEL_CHAT`.

## Layout

```
ko_brain/
  main.py          FastAPI app; mounts the REST router and the MCP app
  config.py        every setting, from the environment
  auth.py          bearer token
  ollama.py        structured generation, retry-on-validation-error, the concurrency gate
  schemas.py       the wire contract; the "LLM output" models double as JSON Schemas
  parsing.py       "200 g chicken breast" -> (200, "g", "chicken breast"), for the MCP path
  cache.py         SQLite TTL cache for upstream lookups
  prompts/*.md     edit these, not the code
  tools/           one implementation per capability
  sources/         TheMealDB, Open Food Facts, the local food table
  api/rest.py      thin routes over tools/
  mcp_server.py    thin MCP adapters over tools/
```

## Tests

```bash
pytest          # 60 tests, no network, no model
ruff check .
```

Nothing in the suite calls Ollama or a real upstream. The nutrition tests in particular are all
table-only, which is the point: the deterministic half has to be verifiable without a GPU.
