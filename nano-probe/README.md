# Hermes Nano Lab · Gemini Nano 4 Fast Preview

Prueba independiente para Samsung Galaxy S24 Ultra. **No modifica ni reemplaza Hermes OS Android**. Permite verificar si una aplicación propia puede acceder a Gemini Nano 4 Fast Preview mediante AICore/ML Kit, y evaluar llamadas a funciones.

## Qué incluye

- Modelo seleccionado: `ModelReleaseStage.PREVIEW` + `ModelPreference.FAST`.
- `checkStatus()`: AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE.
- Dos herramientas tipadas: `turn_on_light`, `turn_off_light`.
- Validación conservadora: solo sala y cocina; porcentaje 0 a 100.
- Sin permiso `INTERNET`, sin credenciales de Home Assistant, sin ejecución real.

## Instalar desde el teléfono (sin PC)

1. Abre la pestaña **Actions** del repositorio `llxll4ever/hermes-os-android`.
2. Abre el workflow **Gemini Nano 4 Lab · Build APK**, y la ejecución exitosa.
3. Descarga el artifact `hermes-nano4-lab-debug-apk` (archivo ZIP).
4. Extrae el ZIP desde **Mis archivos** en Samsung.
5. Instala `app-debug.apk`. Android puede solicitar que autorices esta fuente de instalación.
6. En AICore habilita Developer Preview y descarga Gemini Nano 4 Fast.
7. Abre **Hermes Nano Lab**, pulsa **Comprobar AICore** y luego **Probar llamadas a funciones**.

**Importante:** un modelo que funciona en la aplicación AICore puede devolver UNAVAILABLE a una aplicación de terceros si la compatibilidad de ML Kit está limitada en este dispositivo. Ese es el propósito del experimento.

## Compilación

```bash
gradle -p nano-probe :app:assembleDebug
```

Requiere JDK 17, Android SDK API 35, Gradle 8.13, AGP 8.13.2 y Kotlin 2.3.0. Este proyecto está deliberadamente aislado del Gradle 8.7/Kotlin 1.9.24 del proyecto principal.

## Fuentes

- https://developers.google.com/ml-kit/genai/aicore-dev-preview
- https://developers.google.com/ml-kit/genai/prompt/android/select-model
- https://developers.google.com/ml-kit/genai/prompt/android/function-calling

Estado: prototipo experimental. Se considera útil solo después de compilar en GitHub Actions y probar en el teléfono.
