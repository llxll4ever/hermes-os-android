# STATE.md — Hermes OS Android Live Progress Ledger

Last Updated: 2026-10-29
Repository: `https://github.com/llxll4ever/hermes-os-android`
Local Path on VPS: `/home/opc/Android/HermesOS`

## Milestone Status (Backbone v0.1.0 — COMPLETE)

- [x] Phase 0 — Repo, multi-agent docs, CI, Gradle
- [x] Phase 1 — Core: models, Connection Ladder (Tailscale → Cloudflare → Custom), REST client, encrypted cookies, `WS /api/ws` JSON-RPC client, DataStore
- [x] Phase 2 — Desktop Canvas (presets, add/remove/resize/reorder widgets, DeX 2-column tiling) + `hermes-mobile-wall` plugin (installed at `~/.hermes/plugins/hermes-mobile-wall`)
- [x] Phase 3 — Teams (Hosted Rooms `groups.*`), roster, per-bot model, SOUL.md editor, create bot
- [x] Phase 4 — `ModelsAndProvidersScreen`: manual model registry, global/per-bot assign, custom endpoints, `/api/env` (redaction-guarded), raw `config.yaml`
- [x] Phase 5 — `McpManagerScreen` (STDIO/SSE/HTTP, toggle/test/delete, reload, curated catalog search) + `PluginStoreScreen` (search, tier filters, git install, enable/disable/uninstall, scan badges)
- [x] Phase 6 — Voice (RecognizerIntent STT + TTS readback), `HermesVoiceService`, Glance home widgets (Mic + Bot Wall), Quick Settings tile
- [x] `testDebugUnitTest` + `assembleDebug` green on ARM64

## Known Gaps / Next Tasks (for Opus / Hermes)

1. **Validate endpoints against live server** — several REST paths in `HermesRestClient` are best-guess (`/api/providers/custom-endpoints`, `/api/mcp/catalog/install`, `/api/dashboard/agent-plugins/*`, `/api/profiles/{p}/model`). Diff against `hermes_cli/web_routers/*.py` and fix.
2. **Restart `hermes dashboard`** (user decision) so `hermes-mobile-wall` loads; verify `GET /api/plugins/hermes-mobile-wall/wall`.
3. Plugin settings editor (render `settingsSchema` fields) and consent-delta dialog.
4. Streaming voice via `/api/audio/transcribe-stream` + `speak-stream` instead of system RecognizerIntent.
5. Periodic Glance refresh (WorkManager) — wall widget currently only updates on app sync.
6. Replace deprecated icons with `Icons.AutoMirrored.*`; drag-and-drop widget reorder.
7. Instrumented UI tests; release signing config.

## Build Notes
- ARM64: `./gradlew testDebugUnitTest assembleDebug -Pandroid.aapt2FromMavenOverride=/home/opc/android-sdk/build-tools/aapt2`
- VPS has only `java-17-openjdk-headless` (no `jlink`/`javac`) → project must stay **pure Kotlin** (no Java sources, `buildConfig` disabled). Installing `java-17-openjdk-devel` would lift this.
