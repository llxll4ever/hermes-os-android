# BRIEF UNIVERSAL DE RELEVO MULTI-AGENTE — HERMES OS ANDROID

Copia y pega este brief en **Claude Opus**, **Hermes (`@orchestrator` / `@programador`)**, **Codex**, **OpenCode** o **Antigravity** para continuar el desarrollo en cualquier momento:

---

Actúa como Principal Android & Systems Architect continuando el proyecto **Hermes OS Android**.

1. **Dónde está todo**:
   - Repo local en `lalo-vps`: `/home/opc/Android/HermesOS`
   - Repo remoto en GitHub: `https://github.com/llxll4ever/hermes-os-android`
   - Backend real de Hermes (SOLO LECTURA, no modificar su git): `/home/opc/.hermes/hermes-agent` (corriendo en `127.0.0.1:9119` y publicado en `https://hermes.rodriguezrodriguez.art`).
2. **Qué leer primero**:
   - Abre `/home/opc/Android/HermesOS/AGENTS.md` (o `CLAUDE.md`) y `/home/opc/Android/HermesOS/STATE.md`, además de `git log -n 10 --oneline`.
3. **Cómo compilar y probar en `lalo-vps` (ARM64)**:
   - Ejecuta siempre:
     `./gradlew testDebugUnitTest assembleDebug -Pandroid.aapt2FromMavenOverride=/home/opc/android-sdk/build-tools/aapt2`
   - Nunca metas ese flag en `gradle.properties` para no romper el runner x86_64 de GitHub Actions (`.github/workflows/build-apk.yml`).
4. **Regla de oro**:
   - No subas jamás secretos ni tokens.
   - Al terminar cada mejora, corre los tests, verifica `assembleDebug`, actualiza `STATE.md` con lo completado y lo que sigue, y haz `git commit` + `git push origin main`.
