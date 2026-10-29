# CLAUDE.md — Instructions for Claude Opus / Claude Code

Welcome! You are continuing development on **Hermes OS Android** (`https://github.com/llxll4ever/hermes-os-android`), located at `/home/opc/Android/HermesOS` on `lalo-vps`.

1. **Read `AGENTS.md` and `STATE.md` immediately.** `AGENTS.md` contains the complete backend contract map against `/home/opc/.hermes/hermes-agent`, security boundaries, and package architecture. `STATE.md` tracks exact completed milestones and prioritized next tasks for Opus and Hermes.
2. **ARM64 Build Command on this VPS**:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug -Pandroid.aapt2FromMavenOverride=/home/opc/android-sdk/build-tools/aapt2
   ```
3. **Commit & Push Discipline**:
   Always keep `main` green, update `STATE.md` after every feature or polish pass, and push to `origin` (`git push origin main`) so GitHub Actions builds a fresh APK artifact and Hermes agents (`@programador`, `@disenador`) can continue seamlessly.
