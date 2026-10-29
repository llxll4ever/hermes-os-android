# AGENTS.md — Hermes OS Android (Multi-Agent Backbone Contract)

> **Auto-loaded by Hermes (`@programador`, `@orchestrator`, `@disenador`), Claude Code/Opus (via `CLAUDE.md`), Codex, OpenCode, Jules, and Antigravity.**

## 1. Golden Workflow Rule (Read First!)
1. **Always check `STATE.md` and `git log -n 10 --oneline` first** to see completed phases, architectural invariants, and exact next tasks.
2. **Never leave work only on local disk**: after completing any coherent slice, run unit tests + debug APK build, update `STATE.md`, commit cleanly, and `git push origin main`.
3. **Never leak or commit secrets**: NEVER read/print/commit values from `/home/opc/.hermes/.env`, `/home/opc/.hermes/auth.json`, `/home/opc/.hermes/api_keys.txt`, or GitHub PATs. Git pushes use the external credential file `/home/opc/.git-credentials-hermesos` outside the repo.

## 2. ARM64 Local Build vs GitHub Actions x86_64 Build
`lalo-vps` runs **Linux ARM64 (`aarch64`)** with JDK 17 and Android SDK at `/home/opc/Android/Sdk`.
Maven's default `aapt2` is `x86_64`, so on `lalo-vps` you **MUST** pass the native ARM64 `aapt2` binary override on the command line:

```bash
cd /home/opc/Android/HermesOS
./gradlew testDebugUnitTest assembleDebug -Pandroid.aapt2FromMavenOverride=/home/opc/android-sdk/build-tools/aapt2
```

- **DO NOT** hardcode `android.aapt2FromMavenOverride` in tracked `gradle.properties`, because `.github/workflows/build-apk.yml` builds on `ubuntu-latest` (`x86_64`).

## 3. Live Hermes Backend Surface on `lalo-vps` (Read-Only Reference)
- **Source code**: `/home/opc/.hermes/hermes-agent` (DO NOT modify its git tree!).
- **Live user home**: `/home/opc/.hermes`
- **Live server**: `hermes dashboard --no-open --port 9119 --skip-build` (`v0.22.5+`) listening on `127.0.0.1:9119`, exposed publicly via Cloudflare Tunnel at **`https://hermes.rodriguezrodriguez.art`** and reachable on Tailscale (`100.103.166.20`) when port-forwarded or exposed via Tailscale Serve.
- **8 Active Bot Profiles** (`/home/opc/.hermes/bot_relay/roster.json`):
  `hermes` (`default`), `orchestrator`, `programador`, `disenador`, `domo` (Home Assistant), `sabio` (Research), `redactor`, `rapidito`.
- **Services you MUST NOT disrupt**: `ollama` (`:11434`), `omniroute` (`:20128`), `hermes-proxy-router.py` (`:8889`), `cloudflared`, `hermes-app` legacy (`:8090`), `hermes gateway run`, and `hermes dashboard` (`:9119`).

## 4. Exact Hermes Backend Contracts Used by Hermes OS Android
Consult these exact files in `/home/opc/.hermes/hermes-agent` whenever extending features:

| Domain | Reference Files in `/home/opc/.hermes/hermes-agent` | Endpoints / RPC Methods |
|---|---|---|
| **Auth & WS Tickets** | `hermes_cli/dashboard_auth/routes.py`, `hermes_cli/web_server_boundary.py` | `GET /api/health`, `GET /api/auth/providers`, `POST /auth/password-login` (`provider="basic"`), `GET /api/auth/me`, `POST /api/auth/ws-ticket` → `Sec-WebSocket-Protocol: hermes-gateway-v1, hermes-gateway-ticket.<ticket>` |
| **Multiplex Gateway WS** | `hermes_cli/web_routers/chat_ws.py`, `tui_gateway/contracts/sessions.py`, `prompt_voice.py`, `events.py` | `WS /api/ws` (`profile_scope: "host"`): `session.create`, `prompt.submit`, `approval.respond`, live `event` frames (`gateway.ready`, `tool.start`, `tool.complete`, `message.delta`, `reasoning.delta`, `error`) |
| **Bot Teams & Live Wall** | `tui_gateway/contracts/groups_bot_relay.py`, `tui_gateway/hosted_room_member_activity.py`, `hermes_cli/web_routers/profiles.py` | RPC: `groups.capabilities`, `groups.list`, `groups.create`, `groups.state`, `groups.send`, `groups.log`, `groups.approve`, `groups.stop`, `groups.disband`. REST: `GET/POST/PATCH/DELETE /api/profiles`, `GET/PUT /api/profiles/{name}/soul`, `PUT /api/profiles/{name}/model` |
| **Configs, Providers & Manual Models** | `hermes_cli/web_routers/config_env.py`, `hermes_cli/web_routers/models.py`, `web_routers/_common.py` | `GET/PUT /api/config/raw`, `PUT /api/config`, `GET/PUT/DELETE /api/env` (**never write back `«redacted:…»` previews!**), `GET/POST/DELETE /api/providers/custom-endpoints`, `POST /api/providers/validate`, `GET /api/model/info`, `GET /api/model/options`, `POST /api/model/set`, `GET/PUT /api/model/moa` |
| **MCP Servers & Catalog** | `hermes_cli/web_routers/mcp.py`, `tui_gateway/contracts/tools_mcp_plugins.py` | `GET/POST/PUT /api/mcp/servers`, `DELETE /api/mcp/servers/{name}`, `PUT /api/mcp/servers/{name}/enabled`, `POST /api/mcp/servers/{name}/test`, `GET /api/mcp/catalog`, `POST /api/mcp/catalog/install`, RPC `reload.mcp` |
| **Plugin Store & Scanner** | `hermes_cli/web_routers/dashboard_ui.py`, `tui_gateway/contracts/tools_mcp_plugins.py` | `GET /api/dashboard/plugins/hub`, `GET /api/dashboard/plugins/catalog`, `POST /api/dashboard/agent-plugins/install`, `/enable`, `/disable`, `/update`, `DELETE`, RPC `plugins.manage` (`settings`, `scan_findings`, `consent_required`) |
| **Voice / Microphone** | `hermes_cli/web_routers/audio.py` | `POST /api/audio/transcribe`, `POST /api/audio/speak`, `GET /api/audio/voice-config`, `WS /api/audio/transcribe-stream`, `WS /api/audio/speak-stream`, `POST /api/audio/voice-live/session` |

## 5. Package Architecture (`art.rodriguezrodriguez.hermesos`)
- `core/model/` — Domain & Wire DTOs (`kotlinx.serialization`) for all 6 Hermes subsystems + Desktop Widget layout models.
- `core/network/` — `ConnectionLadder` (Tailscale/LAN → Cloudflare → Custom), `PersistentCookieStore`, `HermesAuthManager`, `HermesRestClient`.
- `core/rpc/` — `HermesRpcClient` (`WS /api/ws` JSON-RPC 2.0 multiplex client, server-to-client approval requests `srq-*`, live event stream).
- `core/storage/` — `WorkspacePreferencesStore` (DataStore persistence for modular desktop widgets, presets, manual model registry, connection endpoints).
- `data/repository/` — `HermesOsRepository` unifying live backend truth + optimistic local state (*"Merge, don't clobber"* per `apps/desktop/ENGINEERING.md`).
- `feature/desktop/` — **Desktop Mode Canvas** (tiling/grid/DeX workspace, widget picker, presets, top OS bar & dock) + ViewModel.
- `feature/widgets/` — Modular Desktop Widgets: `BotWallWidget`, `TeamWarRoomWidget`, `VoiceMicWidget`, `QuickModelMcpWidget`, `ApprovalsInboxWidget`.
- `feature/teams/`, `feature/models/`, `feature/mcp/`, `feature/plugins/`, `feature/voice/` — Full management hubs accessible from Dock or maximized widgets.
- `feature/glance/` — Native Android Launcher AppWidgets (`Jetpack Glance`) + `HermesVoiceTileService` (Quick Settings Tile).
