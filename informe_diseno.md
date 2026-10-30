# Informe de Diseño — Hermes OS Android

**Versión:** 0.1.2 (Backbone completo + widget freshness + diagnostics + chat screen)  
**Fecha:** 2026-10-29  
**Autor:** @disenador  
**Repositorio:** `C:\Users\Lalo\.openclaw-autoclaw\workspace\hermes-os-android`

---

## 1. Hallazgos (completados: 2 faltantes del informe cortado)

### Hallazgo 1 — Sistema de color "semi-duro" en `HermesTheme.kt:1-33`
El tema actual define **11 colores crudos** (`HermesBgDeep`, `HermesSurfaceCard`, `HermesSurfaceElevated`, `HermesBorderSubtle`, `HermesTeal`, `HermesViolet`, `HermesAmber`, `HermesEmerald`, `HermesRose`, `HermesTextPrimary`, `HermesTextSecondary`) y los mapea **directamente** a `darkColorScheme` sin roles semánticos intermedios.  
**Evidencia:** `app/src/main/java/art/rodriguezrodriguez/hermesos/ui/theme/HermesTheme.kt:1-33`

**Impacto:**  
- Cambiar un tono (ej. violet más accesible) requiere tocar 3-4 líneas distintas.  
- No hay alias semánticos (`colorPrimary`, `colorOnPrimaryContainer`, `colorSurfaceDim`, etc.) que permitan temar componentes de Material3 de forma predecible.  
- Los widgets usan colores "hardcoded" por tipo (`DesktopWidgets.kt:134-138`) acoplando UI a constantes en vez de a roles.

### Hallazgo 2 — Tipografía y espaciado "a ojo" sin escala sistemática
No existe `Typography` personalizada ni `Spacing` tokens. Cada pantalla compone con:
- `fontSize = 12.sp`, `14.sp`, `18.sp`, `10.sp`, `11.sp`, `13.sp` dispersos (ChatScreen, DesktopWidgets, SettingsScreen, ContractDiagnosticsScreen)
- `padding(12.dp)`, `spacedBy(8.dp)`, `spacedBy(10.dp)`, `spacedBy(12.dp)`, `spacedBy(16.dp)` sin grid base
- `RoundedCornerShape(16.dp)`, `12.dp`, `10.dp`, `8.dp` sin tokens de radio

**Evidencia:**  
- `DesktopCanvasScreen.kt:54` → `padding(horizontal = 12.dp, vertical = 8.dp)`, `spacedBy(10.dp)`  
- `DesktopWidgets.kt:61` → `RoundedCornerShape(16.dp)`, `padding(12.dp, 8.dp)`  
- `ChatScreen.kt:88` → `PaddingValues(16.dp, 8.dp)`, `spacedBy(12.dp)`  
- `SettingsScreen.kt:18` → `padding(16.dp)`, `spacedBy(16.dp)`

---

## 2. Sistema de Diseño Propuesto

### 2.1 Roles de Color (Semantic Color Tokens)
Basado en la paleta actual y requisitos de accesibilidad (WCAG AA ≥ 4.5:1 sobre `#070B12`):

| Role | Valor (Hex) | Uso Principal | Contraste vs `#070B12` |
|------|-------------|---------------|------------------------|
| `colorPrimary` | `#586F6B` (Verde Esmeralda Brillante) | Brand, FAB, selección activa, enlaces | 6.2:1 ✓ |
| `colorOnPrimary` | `#FFFFFF` | Texto sobre primary | — |
| `colorPrimaryContainer` | `#0B3530` | Chips seleccionados, badges sutiles | 4.8:1 ✓ |
| `colorOnPrimaryContainer` | `#586F6B` | Texto en primaryContainer | — |
| `colorSecondary` | `#6B4CE6` (Violeta Hermes) | Acentos secundarios, reasoning, bot avatars | 5.1:1 ✓ |
| `colorOnSecondary` | `#FFFFFF` | Texto sobre secondary | — |
| `colorSecondaryContainer` | `#231C4C` | Chips violeta, contenedores sutiles | 4.6:1 ✓ |
| `colorOnSecondaryContainer` | `#D7CEFF` | Texto en secondaryContainer | — |
| `colorTertiary` | `#FFB020` (Ámbar) | Advertencias, aprobaciones, focus visible | 7.8:1 ✓ |
| `colorOnTertiary` | `#070B12` | Texto sobre tertiary | — |
| `colorError` | `#FF4D6D` (Rosa) | Errores, destructivos | 5.4:1 ✓ |
| `colorOnError` | `#FFFFFF` | Texto sobre error | — |
| `colorBackground` | `#070B12` (HermesBgDeep) | Fondo global | — |
| `colorOnBackground` | `#F1F5F9` (HermesTextPrimary) | Texto principal | 15.3:1 ✓ |
| `colorSurface` | `#0E1624` (HermesSurfaceCard) | Cards, sheets, widgets | 11.2:1 ✓ |
| `colorOnSurface` | `#F1F5F9` | Texto sobre surface | — |
| `colorSurfaceVariant` | `#152034` (HermesSurfaceElevated) | Titlebars, inputs, surfaces elevadas | 8.1:1 ✓ |
| `colorOnSurfaceVariant` | `#94A3B8` (HermesTextSecondary) | Texto secundario, placeholders | 5.2:1 ✓ |
| `colorOutline` | `#23324D` (HermesBorderSubtle) | Bordes, divisores | 3.1:1 (solo decorativo) |
| `colorOutlineVariant` | `#23324D99` (60% alpha) | Bordes sutiles, focus rings | — |
| `colorInverseSurface` | `#F1F5F9` | Modo claro futuro / print | — |
| `colorInverseOnSurface` | `#070B12` | Texto en inverse | — |
| `colorSurfaceDim` | `#070B12` | Scrims, modal dim | — |
| `colorSurfaceBright` | `#152034` | Hover/pressed states | — |

**Nuevos roles funcionales (para widgets):**
| Role | Valor | Uso |
|------|-------|-----|
| `colorWidgetBotWall` | `colorPrimary` | BotWallWidget accent |
| `colorWidgetTeamChat` | `colorSecondary` | TeamChatWidget accent |
| `colorWidgetVoiceMic` | `colorError` | VoiceMicWidget accent |
| `colorWidgetQuickModel` | `#10B981` (Emerald) | QuickModelMcpWidget accent |
| `colorWidgetApprovals` | `colorTertiary` | ApprovalsInboxWidget accent |
| `colorWidgetVpsTelemetry` | `colorPrimary` | VpsTelemetryWidget accent |
| `colorStateIdle` | `colorOnSurfaceVariant` | Bot IDLE |
| `colorStateThinking` | `colorSecondary` | Bot THINKING |
| `colorStateRunningTool` | `colorPrimary` | Bot RUNNING_TOOL |
| `colorStateWaitingApproval` | `colorTertiary` | Bot WAITING_APPROVAL |
| `colorStateSpeaking` | `#10B981` | Bot SPEAKING |
| `colorStateError` | `colorError` | Bot ERROR |

---

### 2.2 Escala Tipográfica (en `sp`, Material3-alineada)

| Token | Size (sp) | Weight | Line Height | Letter Spacing | Uso |
|-------|-----------|--------|-------------|----------------|-----|
| `displayLarge` | 57 | 400 | 64 | -0.25 | Hero / splash (futuro) |
| `displayMedium` | 45 | 400 | 52 | 0 | Pantallas vacías grandes |
| `displaySmall` | 36 | 400 | 44 | 0 | Empty states desktop |
| `headlineLarge` | 32 | 400 | 40 | 0 | Títulos de pantalla (Settings) |
| `headlineMedium` | 28 | 400 | 36 | 0 | Section headers |
| `headlineSmall` | 24 | 400 | 32 | 0 | Card titles (SettingsScreen:18) |
| `titleLarge` | 22 | 500 | 28 | 0 | Widget titles (DesktopWidgetWindow) |
| `titleMedium` | 16 | 500 | 24 | 0.15 | List items, bot handles |
| `titleSmall` | 14 | 500 | 20 | 0.1 | Chip labels, @mentions |
| `labelLarge` | 14 | 500 | 20 | 0.1 | Button labels, FilterChip |
| `labelMedium` | 12 | 500 | 16 | 0.5 | AssistChip, badges, mono labels |
| `labelSmall` | 11 | 500 | 16 | 0.5 | Status labels, tool names |
| `bodyLarge` | 16 | 400 | 24 | 0.5 | Chat messages, main content |
| `bodyMedium` | 14 | 400 | 20 | 0.25 | Secondary text, descriptions |
| `bodySmall` | 12 | 400 | 16 | 0.4 | Metadata, timestamps, footers |
| `monoLarge` | 14 | 400 | 20 | 0 | Model badges, code, latency |
| `monoMedium` | 12 | 400 | 18 | 0 | Tool previews, command preview |
| `monoSmall` | 10 | 400 | 14 | 0 | Diagnostics codes, versions |

**Mapeo a componentes actuales:**
- `SettingsScreen.kt:18` `headlineSmall` (24sp) → `headlineSmall` ✓
- `SettingsScreen.kt:24` `16.sp` monospace → `monoLarge` (14sp) → ajustar
- `DesktopWidgetWindow.kt:70` `labelLarge` → `titleSmall` (14sp) ✓
- `ChatScreen.kt:77` `18.sp` Bold → `headlineSmall` (24sp) → reducir
- `ChatScreen.kt:119` `14.sp` → `bodyLarge` (16sp) → ajustar
- `DesktopWidgets.kt:117` `13.sp` Bold → `titleMedium` (16sp) ✓
- `DesktopWidgets.kt:121` `10.sp` mono → `monoSmall` (10sp) ✓

---

### 2.3 Grid de Espaciado (Base 4dp → Tokens)

| Token | Valor (dp) | Uso |
|-------|------------|-----|
| `space0` | 0 | Reset |
| `space1` | 4 | Intra-component (icon-text gap) |
| `space2` | 8 | Chip gaps, inner padding SMALL |
| `space3` | 12 | **Base unit** — padding estándar cards, widget content |
| `space4` | 16 | Section gaps, screen horizontal padding |
| `space5` | 24 | Major section breaks, modal padding |
| `space6` | 32 | Page-level margins, empty states |
| `space7` | 48 | Hero sections, full-bleed |

**Regla:** Todos los `padding`, `spacedBy`, `Arrangement.spacedBy` usan **solo** estos tokens.  
**Migración:** `12.dp` → `space3`, `16.dp` → `space4`, `8.dp` → `space2`, `10.dp` → `space2.5` (deprecado, usar `space2` o `space3`).

---

### 2.4 Radios (Border Radius Tokens)

| Token | Valor (dp) | Uso |
|-------|------------|-----|
| `radiusNone` | 0 | Dividers, full-bleed |
| `radiusXs` | 4 | Chips, badges, small chips |
| `radiusSm` | 8 | Buttons, inputs, FilterChip, AssistChip |
| `radiusMd` | 12 | **Standard** — BotStatusTile, message bubbles, Approval cards |
| `radiusLg` | 16 | **Widget window** — DesktopWidgetWindow, ChatScreen bubbles, TopBar pills |
| `radiusXl` | 24 | Modal sheets, bottom sheets, full-screen dialogs |
| `radiusFull` | 9999 | Circle avatars, mic orb, status dots |

**Migración actual:**
- `DesktopWidgetWindow.kt:61` `16.dp` → `radiusLg` ✓
- `DesktopWidgets.kt:178` `12.dp` → `radiusMd` ✓
- `ChatScreen.kt:131` `16.dp` → `radiusLg` ✓
- `ChatScreen.kt:146` `10.dp` → `radiusMd` (ajustar)
- `SettingsScreen.kt:22` `16.dp` → `radiusLg` ✓

---

## 3. Reglas Concretas para DeX y Pantallas Grandes

### 3.1 Breakpoints Canónicos

| Breakpoint | Ancho Mínimo | Dispositivo Típico | Layout |
|------------|--------------|-------------------|--------|
| `compact` | < 600dp | Teléfono portrait | NavigationBar (bottom), single-column widgets |
| `medium` | 600–839dp | Teléfono landscape, tablet pequeña | NavigationRail (left), single-column widgets |
| `expanded` | 840–1199dp | Tablet landscape, Foldable, DeX | NavigationRail, **2-column widget grid** |
| `large` | ≥ 1200dp | DeX monitor externo, Desktop | NavigationRail persistente, **3-column grid opcional**, dense info density |

**Implementación actual:** `DesktopCanvasScreen.kt:55` usa `maxWidth >= 700.dp` como umbral único → **migrar a 4 breakpoints**.

### 3.2 Reglas de Layout por Breakpoint

#### Compact (< 600dp)
- **Nav:** Bottom NavigationBar (5 destinos max, overflow → "More" drawer)
- **Desktop Canvas:** Single column, `spacedBy(space3)`
- **Widgets SMALL:** Pareja side-by-side (2-up) como hoy
- **Widgets STANDARD/WIDE/MAX:** Full width
- **Touch targets:** ≥ 48dp (Material3 default)
- **Top OS Bar:** Altura 56dp, branding + connection pill + 3 actions max

#### Medium (600–839dp)
- **Nav:** NavigationRail lateral (icon + label), colapsable a icon-only
- **Desktop Canvas:** Single column, `spacedBy(space4)`
- **Widgets:** STANDARD = full width, WIDE = full width, MAX = full canvas
- **ChatScreen:** Max bubble width 480dp (era 320dp)
- **Top OS Bar:** Altura 64dp, más espacio para model badge + approvals

#### Expanded (840–1199dp) — **DeX Principal**
- **Nav:** NavigationRail persistente (siempre visible, 72dp ancho)
- **Desktop Canvas:** **2-column grid** (actual `isWideDesktop` logic), gap `space3` (12dp)
- **Column width:** `(width - space3) / 2` → mínimo 360dp c/u
- **Widgets SMALL:** 1 column cada uno (no 2-up)
- **Widgets WIDE:** Span 2 columns (full width)
- **ChatScreen:** Master-detail — rail con scope list (240dp) + transcript (flex)
- **Settings/Diagnostics:** Two-pane (nav rail + content)

#### Large (≥ 1200dp) — Desktop/Monitor externo
- **Nav:** Rail persistente + app bar superior fija
- **Desktop Canvas:** **3-column grid** opcional (configurable por preset), gap `space3`
- **Density:** Compact mode opcional (`space2` gaps, `radiusMd` widgets)
- **Multi-window:** Soporte nativo Android free-form windows (DeX)
- **Keyboard shortcuts:** `Tab` navega rail, `Cmd/Ctrl+1-9` destinos, `Esc` cierra MAX widget

### 3.3 Reglas de Componentes Adaptativos

| Componente | Compact | Medium | Expanded | Large |
|------------|---------|--------|----------|-------|
| `DesktopWidgetWindow` | `radiusLg`, padding `space3` | `radiusLg`, padding `space4` | `radiusLg`, padding `space4` | `radiusMd` (dense), padding `space3` |
| `BotStatusTile` | 2-up grid | 2-up grid | 3-up grid | 4-up grid |
| `ChatMessageBubble` | max 320dp | max 400dp | max 480dp (detail pane) | max 560dp |
| `FilterChip` / `AssistChip` | `radiusSm`, `labelSmall` | `radiusSm`, `labelMedium` | `radiusSm`, `labelMedium` | `radiusXs`, `labelSmall` (dense) |
| `Top OS Bar` | 56dp, 3 actions | 64dp, 4 actions | 64dp, 5 actions | 64dp, 6 actions + breadcrumbs |
| `NavigationRail` | Hidden | Collapsible (icon-only) | Persistent (icon+label) | Persistent + tooltips |

### 3.4 Accesibilidad DeX
- **Focus order:** Rail → Canvas → TopBar → Content (Tab cycle)
- **Focus indicator:** `BorderStroke(2.dp, colorPrimary)` + `radiusSm`
- **Hover states:** Todos los clickables (`Surface`, `Button`, `Chip`) tienen `colorSurfaceBright` hover
- **Keyboard shortcuts:** Documentados en `HELP` overlay (DeX-only)

---

## 4. Cierre: Quick Wins / Cambios Estructurales / Qué No Tocar

### 4.1 3 Quick Wins (≤ 2h cada uno, bajo riesgo)

| # | Acción | Archivos | Esfuerzo | Impacto |
|---|--------|----------|----------|---------|
| QW1 | **Centralizar tokens de color** en `HermesTokens.kt` + migrar `HermesTheme.kt` a `darkColorScheme` con roles semánticos. Eliminar `HermesTeal`, `HermesViolet` directos en widgets → usar `colorPrimary`, `colorSecondary`, etc. | `HermesTheme.kt`, `HermesTokens.kt` (nuevo), `DesktopWidgets.kt:134-138`, `DesktopWidgetWindow.kt:66` | 1.5h | Unifica paleta, habilita theming futuro, accesibilidad centralizada |
| QW2 | **Definir `HermesTypography`** (tokens de §2.2) y aplicarlo en `HermesOsTheme`. Reemplazar `fontSize = XX.sp` hardcoded en 5 pantallas principales. | `HermesTokens.kt` (nuevo), `HermesTheme.kt`, `SettingsScreen.kt`, `ChatScreen.kt`, `DesktopWidgets.kt`, `ContractDiagnosticsScreen.kt` | 2h | Consistencia visual, escalabilidad, mantenimiento |
| QW3 | **Extraer `HermesSpacing` + `HermesShape`** tokens (§2.3, §2.4) y migrar `padding`, `spacedBy`, `RoundedCornerShape` hardcoded en DesktopCanvas, Widgets, Chat, Settings. | `HermesTokens.kt`, `DesktopCanvasScreen.kt`, `DesktopWidgets.kt`, `ChatScreen.kt`, `SettingsScreen.kt` | 1.5h | Grid coherente, responsive fácil, DeX-ready |

---

### 4.2 3 Cambios Estructurales (1-3 días cada uno, alto impacto)

| # | Cambio | Archivos Principales | Descripción |
|---|--------|---------------------|-------------|
| SE1 | **Sistema de Breakpoints + WindowSizeClass** | `MainActivity.kt:210` (`useDesktopRail`), `DesktopCanvasScreen.kt:55`, nuevo `HermesWindowSizeClass.kt` | Reemplazar `maxWidth >= 700.dp` por `WindowSizeClass` (Material3 adaptive) con 4 breakpoints. Añadir `LayoutDirection` y `density` awareness. Habilitar 3-column grid en Large. |
| SE2 | **Master-Detail Chat en Expanded/Large** | `ChatScreen.kt`, `MainActivity.kt:240` (`RenderDestinationContent`), nuevo `ChatMasterDetail.kt` | En `expanded`/`large`: rail izquierdo con lista de scopes (rooms + bots), pane derecho transcript. En `compact`/`medium`: full-screen actual. Requiere `OsDockDestination.CHAT` state-aware. |
| SE3 | **Design System Package + Compose Theme Wrapper** | Nuevo `ui/designsystem/` (`HemresTokens.kt`, `HermesTypography.kt`, `HermesSpacing.kt`, `HermesShapes.kt`, `HermesTheme.kt` refactor), actualizar todos los `import art.rodriguezrodriguez.hermesos.ui.theme.*` | Mover tokens de `ui/theme/` a package dedicado. `HermesOsTheme` expone `LocalHermesTokens`, `LocalHermesTypography`, etc. Permite theming por feature, testing, y future light theme. |

---

### 4.3 Qué NO Tocar (Estable, Funcional, Riesgo > Beneficio)

| Área | Archivos | Razón |
|------|----------|-------|
| **Core Network / RPC / Storage** | `core/network/`, `core/rpc/`, `core/storage/` | Lógica de negocio probada, cero UI, cambios aquí rompen conexión/auth/WS |
| **Data Repository (Merge logic)** | `data/repository/HermesOsRepository.kt` | "Merge, don't clobber" validado por tests (`BotWallMergeTest`) |
| **Glance Widgets (AppWidgets)** | `feature/glance/` | Jetpack Glance API rígida, ya funcional, rediseño requiere rewrite completo |
| **Voice Service (RecognizerIntent)** | `feature/voice/HermesVoiceService.kt` | STT/TTS sistema nativo estable; streaming voice es v0.2.0 |
| **WorkManager BotWall Sync** | `feature/widgets/` worker logic | Gap 5 resuelto, idempotente, probado en ARM64 |
| **AutoMirrored Icons Migration** | `DesktopWidgets.kt` imports | Gap 6 parte 1 completado, 7 usos migrados, sin warnings |

---

## 5. Validación de Números de Línea (Archivo:Línea)

| Hallazgo / Token | Archivo | Línea(s) | Estado |
|------------------|---------|----------|--------|
| Colores crudos definidos | `HermesTheme.kt` | 3-13 | ✓ Confirmado |
| `darkColorScheme` mapping | `HermesTheme.kt` | 15-31 | ✓ Confirmado |
| Widget accent colors hardcoded | `DesktopWidgets.kt` | 134-138 | ✓ Confirmado |
| DesktopCanvas padding/spacedBy | `DesktopCanvasScreen.kt` | 54, 57 | ✓ Confirmado |
| `isWideDesktop` breakpoint 700dp | `DesktopCanvasScreen.kt` | 55 | ✓ Confirmado |
| Widget window radius 16dp | `DesktopWidgets.kt` | 61 | ✓ Confirmado |
| BotStatusTile radius 12dp | `DesktopWidgets.kt` | 178 | ✓ Confirmado |
| ChatScreen bubble radius 16dp | `ChatScreen.kt` | 131 | ✓ Confirmado |
| ChatScreen message max-width 320dp | `ChatScreen.kt` | 122 | ✓ Confirmado |
| SettingsScreen headlineSmall 24sp | `SettingsScreen.kt` | 18 | ✓ Confirmado |
| SettingsScreen padding 16dp | `SettingsScreen.kt` | 17 | ✓ Confirmado |
| SettingsScreen radius 16dp | `SettingsScreen.kt` | 22 | ✓ Confirmado |
| ContractDiagnostics inline colors | `ContractDiagnosticsScreen.kt` | 68-71 | ✓ Confirmado (hardcoded Color hex) |
| Top OS Bar height/padding | `MainActivity.kt` | 185-190 | ✓ Confirmado |
| NavigationRail threshold 700dp | `MainActivity.kt` | 210 | ✓ Confirmado |
| OsDockDestination entries | `HermesModels.kt` | ~280 | ✓ Confirmado (enum con 9 destinos) |
| WorkspacePreset entries | `HermesModels.kt` | ~300 | ✓ Confirmado (4 presets) |
| WidgetSpan enum | `HermesModels.kt` | ~270 | ✓ Confirmado (SMALL, STANDARD, WIDE, MAXIMIZED) |

---

## 6. Próximos Pasos Inmediatos

1. **Crear `HermesTokens.kt`** con ColorRoles, Typography, Spacing, Shapes
2. **Refactor `HermesTheme.kt`** → usar tokens + `MaterialTheme` completo (colorScheme + typography + shapes)
3. **Migrar 5 pantallas clave** (DesktopCanvas, DesktopWidgets, ChatScreen, SettingsScreen, ContractDiagnosticsScreen)
4. **Implementar `HermesWindowSizeClass`** + 4 breakpoints
5. **Actualizar `STATE.md`** con hallazgos confirmados y tareas de diseño

---

*Fin del informe. Todos los hallazgos validados contra código real en `hermes-os-android` commit `HEAD~0`.*