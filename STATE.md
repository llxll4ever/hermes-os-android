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

## Milestone Status (v0.1.1 — widget freshness)

- [x] **Gap 5 — Refresco periódico del widget Bot Wall.** `HermesBotWallSyncWorker` (WorkManager, 30 min, constraint `NetworkType.CONNECTED`, backoff exponencial 10 min, techo de 3 intentos) reconstruye el mismo camino de datos que la app en primer plano —escalera de conexión + `/api/profiles` + telemetría de `hermes-mobile-wall`— y dispara `HermesWallGlanceWidget().updateAll()`. Programado de forma idempotente (`ExistingPeriodicWorkPolicy.UPDATE`) desde `HermesOsApp.onCreate`. WorkManager persiste el trabajo entre reinicios, así que no se agregó un receiver de `BOOT_COMPLETED`.
- [x] **Gap 6 (parte 1) — Iconos auto-espejados.** 7 usos migrados a `Icons.AutoMirrored.Filled.*` (ArrowForward, VolumeUp, VolumeOff, Login, OpenInNew, Send ×2). Compilación sin warnings de deprecación.
- [x] **Regla "merge, don't clobber" extraída** a `BotWallMerge` para que el worker y el repositorio compartan una sola implementación. Cubierta por `BotWallMergeTest` (4 casos).

## Known Gaps / Next Tasks

1. **Validate endpoints against live server** — several REST paths in `HermesRestClient` are best-guess (`/api/providers/custom-endpoints`, `/api/mcp/catalog/install`, `/api/dashboard/agent-plugins/*`, `/api/profiles/{p}/model`). Diff against `hermes_cli/web_routers/*.py` and fix. **Bloqueado**: se necesita acceso al código del backend en `lalo-vps` (`/home/opc/.hermes/hermes-agent`) o una sesión autenticada contra el dashboard. Nota de sondeo: el servidor responde `401` a *cualquier* ruta no autenticada (incluidas inexistentes), por lo que no sirve como oráculo de existencia de rutas; sólo `/api/health`, `/api/auth/providers` y `/api/model/info` son públicos (verificados el 2026-10-29, versión 0.22.5).
2. **Restart `hermes dashboard`** (user decision) so `hermes-mobile-wall` loads; verify `GET /api/plugins/hermes-mobile-wall/wall`.
3. Plugin settings editor (render `settingsSchema` fields) and consent-delta dialog.
4. Streaming voice via `/api/audio/transcribe-stream` + `speak-stream` instead of system RecognizerIntent.
5. ~~Periodic Glance refresh (WorkManager)~~ — hecho en v0.1.1.
6. Drag-and-drop widget reorder (la parte de iconos AutoMirrored ya está hecha).
7. Instrumented UI tests; release signing config.

## Build Notes
- ARM64: `./gradlew testDebugUnitTest assembleDebug -Pandroid.aapt2FromMavenOverride=/home/opc/android-sdk/build-tools/aapt2`
- VPS has only `java-17-openjdk-headless` (no `jlink`/`javac`) → project must stay **pure Kotlin** (no Java sources, `buildConfig` disabled). Installing `java-17-openjdk-devel` would lift this.
- Windows x86_64 (JDK 17 + Android SDK local): `.\gradlew.bat testDebugUnitTest assembleDebug` — sin el flag de aapt2.