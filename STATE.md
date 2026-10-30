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

## Milestone Status (v0.1.2 — diagnostics, fixes, chat screen)

- [x] **Fix #6 — Snackbar con deshacer para controles destructivos.** `DesktopCanvasScreen` + `SnackbarHost` muestra "Widget eliminado" con acción "Deshacer" al borrar widgets.
- [x] **Fix #8 — 4 colores de contraste ajustados.** Violeta ya más oscuro (0xFF6B4CE6). Badges: modelo 20%→40%, aprobaciones 25%→45%, mic 22%→45%, conexión 60%→80%.
- [x] **Gap #1 — Pantalla de chat real.** `ChatScreen` con scroll interno (LazyColumn), `imePadding`, burbujas de mensaje (usuario/bot), reasoning, tool calls, streaming indicator, @mention chips, auto-scroll.
- [x] **Navegación extendida.** Destinos `SETTINGS`, `DIAGNOSTICS`, `CHAT` añadidos a `OsDockDestination` + rail/bar + iconos.
- [x] **SettingsScreen** con acceso a Diagnóstico de Contrato, info de app y backend.
- [x] **ContractDiagnosticsScreen** integrado y limpiado (quitado "PY" final).

## Milestone Status (v0.1.3 — Design System & DeX Rules) 🎨

- [x] **Informe de diseño completado** — 2 hallazgos validados contra código real (archivo:línea):
  - Hallazgo 1: Sistema de color "semi-duro" en `HermesTheme.kt:3-31` — 11 colores crudos mapeados directo a `darkColorScheme` sin roles semánticos intermedios; widgets acoplan UI a constantes (`DesktopWidgets.kt:134-138`).
  - Hallazgo 2: Tipografía y espaciado "a ojo" sin escala sistemática — `fontSize` dispersos (10-18sp), `padding`/`spacedBy` sin grid base (8/10/12/16dp), `RoundedCornerShape` sin tokens (8/10/12/16dp).
- [x] **Sistema de diseño propuesto** documentado en `informe_diseno.md`:
  - **Roles de color (22 tokens semánticos):** Primary `#586F6B` (Verde Esmeralda Brillante), Secondary `#6B4CE6`, Tertiary `#FFB020`, Error `#FF4D6D`, Surface variants, Outline, widget-functional roles, state roles — todos WCAG AA ≥ 4.5:1 sobre `#070B12`.
  - **Escala tipográfica (19 tokens en sp):** Display → Headline → Title → Label → Body → Mono, alineada a Material3; mapeo a componentes actuales incluido.
  - **Grid de espaciado (base 4dp, 8 tokens):** `space0`–`space7` (0/4/8/12/16/24/32/48dp); regla: solo estos tokens en `padding`/`spacedBy`.
  - **Radios (7 tokens):** `radiusNone`–`radiusFull` (0/4/8/12/16/24/9999dp); migración de hardcoded a tokens mapeada.
- [x] **Reglas concretas DeX / Pantallas grandes (4 breakpoints):**
  - `compact` (<600dp): NavBar bottom, single-col widgets, 2-up SMALL.
  - `medium` (600–839dp): Rail colapsable, single-col, chat max 480dp.
  - `expanded` (840–1199dp) — **DeX principal**: Rail persistente, **2-col grid** (gap 12dp), master-detail chat, WIDE span 2 cols.
  - `large` (≥1200dp): Rail + app bar, **3-col grid opcional**, dense mode, free-form windows, shortcuts.
  - Tabla de componentes adaptativos por breakpoint + reglas de foco/hover/teclado DeX.
- [x] **Cierre: 3 Quick Wins / 3 Cambios Estructurales / Qué No Tocar:**
  - **QW1** (1.5h): Centralizar tokens color en `HermesTokens.kt` + migrar `HermesTheme.kt` a roles semánticos.
  - **QW2** (2h): Definir `HermesTypography` + aplicar en 5 pantallas principales.
  - **QW3** (1.5h): Extraer `HermesSpacing` + `HermesShape` + migrar hardcoded.
  - **SE1** (1-3d): `WindowSizeClass` + 4 breakpoints + 3-col grid Large.
  - **SE2** (1-3d): Master-detail Chat en Expanded/Large.
  - **SE3** (1-3d): Design System package (`ui/designsystem/`) + `LocalHermesTokens` composition locals.
  - **No tocar:** Core network/RPC/storage, Repository merge logic, Glance widgets, Voice Service, WorkManager sync, AutoMirrored icons (hecho).

## Known Gaps / Next Tasks

1. **Validate endpoints against live server** — several REST paths in `HermesRestClient` are best-guess (`/api/providers/custom-endpoints`, `/api/mcp/catalog/install`, `/api/dashboard/agent-plugins/*`, `/api/profiles/{p}/model`). Diff against `hermes_cli/web_routers/*.py` and fix. **Bloqueado**: se necesita acceso al código del backend en `lalo-vps` (`/home/opc/.hermes/hermes-agent`) o una sesión autenticada contra el dashboard. Nota de sondeo: el servidor responde `401` a *cualquier* ruta no autenticada (incluidas inexistentes), por lo que no sirve como oráculo de existencia de rutas; sólo `/api/health`, `/api/auth/providers` y `/api/model/info` son públicos (verificados el 2026-10-29, versión 0.22.5).
2. **Restart `hermes dashboard`** (user decision) so `hermes-mobile-wall` loads; verify `GET /api/plugins/hermes-mobile-wall/wall`.
3. Plugin settings editor (render `settingsSchema` fields) and consent-delta dialog.
4. Streaming voice via `/api/audio/transcribe-stream` + `speak-stream` instead of system RecognizerIntent.
5. ~~Periodic Glance refresh (WorkManager)~~ — hecho en v0.1.1.
6. Drag-and-drop widget reorder (la parte de iconos AutoMirrored ya está hecha).
7. Instrumented UI tests; release signing config.
8. **Design System Implementation** — Ejecutar QW1, QW2, QW3 (tokens, tipografía, espaciado, radios).
9. **DeX Breakpoints + Master-Detail Chat** — Ejecutar SE1, SE2.
10. **Design System Package** — Ejecutar SE3.

## Build Notes

- ARM64: `./gradlew testDebugUnitTest assembleDebug -Pandroid.aapt2FromMavenOverride=/home/opc/android-sdk/build-tools/aapt2`
- VPS has only `java-17-openjdk-headless` (no `jlink`/`javac`) → project must stay **pure Kotlin** (no Java sources, `buildConfig` disabled). Installing `java-17-openjdk-devel` would lift this.
- Windows x86_64 (JDK 17 + Android SDK local): `.\\gradlew.bat testDebugUnitTest assembleDebug` — sin el flag de aapt2.