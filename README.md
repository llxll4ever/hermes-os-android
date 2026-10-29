# Hermes OS Android

**Modo Desktop Móvil, Muro de Equipos de Bots en Vivo, Micrófono Bidireccional, Gestor Manual de Providers/Modelos y Tienda de MCPs/Plugins para `hermes-agent`.**

Diseñado en **Kotlin + Jetpack Compose** para teléfonos de alta densidad, plegables y **Samsung DeX** (optimizado para Galaxy S24 Ultra).

## Capacidades Principales (Columna Vertebral)
- **Desktop Workspace Canvas**: Lienzo modular con presets (*Command Center*, *Team War Room*, *Voice & Domotics*, *DeX Studio*) donde puedes colocar, redimensionar, reordenar o maximizar widgets en pantalla.
- **Muro de Bots en Vivo (`BotWallWidget`)**: Estado en tiempo real de tus perfiles de Hermes (`hermes`, `orchestrator`, `programador`, `disenador`, `domo`, `sabio`, `redactor`, `rapidito`), herramienta activa, razonamiento y aprobaciones en 1 toque.
- **Equipos de Trabajo (`Hosted Rooms` + `Bot Relay`)**: Crea cuartos multi-bot (`groups.create`, `groups.send`, `groups.log`, `groups.approve`), menciona especialistas con `@handle` y edita perfiles / `SOUL.md`.
- **Providers, Configs & Modelos Manuales**: Gestiona variables `/api/env` con protección de secretos redactados (`«redacted:…»`), endpoints OpenAI-compatibles (OmniRoute, Ollama, proxies), editor YAML `/api/config/raw` y **registro manual de modelos** (agregar/quitar IDs de modelos manualmente y asignarlos globalmente, por bot o MoA).
- **Conector MCP + Tienda de Plugins con Buscador**: Conecta nuevos MCPs (`stdio`/`SSE`/`HTTP`) o instala del catálogo curado (`+68 MCPs`), y busca plugins en el catálogo oficial con visualización de escaneo de seguridad (`scan_findings`), consentimiento de capacidades y ajustes dinámicos.
- **Micrófono Inteligente + Android Home Widgets (`Jetpack Glance`)**: Voz push-to-talk / continua (`/api/audio/*`) dirigida a cualquier bot o equipo, más widgets nativos para la pantalla de inicio de Android y Tile en Ajustes Rápidos.

## Compilación
- **GitHub Actions (x86_64)**: Automática en cada push vía `.github/workflows/build-apk.yml`.
- **Local en `lalo-vps` (Linux ARM64)**:
  ```bash
  ./gradlew testDebugUnitTest assembleDebug -Pandroid.aapt2FromMavenOverride=/home/opc/android-sdk/build-tools/aapt2
  ```

Para agentes de IA continuando el desarrollo, leer **[`AGENTS.md`](./AGENTS.md)**, **[`CLAUDE.md`](./CLAUDE.md)** y **[`STATE.md`](./STATE.md)**.
