package art.rodriguezrodriguez.hermesos.core.plugins

import art.rodriguezrodriguez.hermesos.core.model.PluginSettingFieldUi
import art.rodriguezrodriguez.hermesos.core.network.CredentialGuards
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class PluginSettingsSavePlan(
    val configValues: JsonObject,
    val secretEnvUpdates: Map<String, String>
)

class PluginSettingsValidationException(
    val fieldKey: String,
    override val message: String
) : IllegalArgumentException(message)

/**
 * Pure-Kotlin validator & payload builder for Hermes Plugin Manifest `config_schema`
 * (`hermes_cli/plugins_settings.py:33, 128-152`).
 *
 * Enforces strict Python-side wire types:
 * - `string` / `enum` -> JSON string
 * - `number` -> JSON integer (`Long`) or floating-point (`Double`)
 * - `boolean` -> JSON boolean
 * - `json` -> JSON object (`{...}`) or array (`[...]`)
 * - `secret` -> NEVER sent to `plugins.manage`; routed exclusively to `PUT /api/env`
 *   when modified and non-redacted.
 */
object PluginSettingsCodec {

    private val lenientJson = Json {
        isLenient = false
        ignoreUnknownKeys = true
    }

    fun initialDrafts(schema: List<PluginSettingFieldUi>): Map<String, String> {
        val map = LinkedHashMap<String, String>(schema.size)
        for (field in schema) {
            val type = field.type.trim().lowercase()
            map[field.key] = when (type) {
                "secret" -> ""
                "boolean" -> {
                    val raw = field.currentValue.ifBlank { field.defaultValue }.trim().lowercase()
                    if (raw == "true" || raw == "1" || raw == "yes") "true" else "false"
                }
                else -> field.currentValue.ifBlank { field.defaultValue }
            }
        }
        return map
    }

    fun buildPayloads(
        schema: List<PluginSettingFieldUi>,
        drafts: Map<String, String>
    ): Result<PluginSettingsSavePlan> = runCatching {
        val typedValues = LinkedHashMap<String, JsonElement>()
        val secretUpdates = LinkedHashMap<String, String>()

        for (field in schema) {
            val type = field.type.trim().lowercase()
            val rawDraft = drafts[field.key]

            when (type) {
                "secret" -> {
                    val trimmed = rawDraft?.trim().orEmpty()
                    if (trimmed.isEmpty()) {
                        if (field.required && !field.hasSecretValue) {
                            throw PluginSettingsValidationException(
                                field.key,
                                "El secreto '${field.label}' (${field.envKey ?: field.key}) es obligatorio."
                            )
                        }
                    } else if (!CredentialGuards.isRedactedPreview(trimmed)) {
                        val envName = field.envKey?.trim().takeUnless { it.isNullOrEmpty() }
                            ?: field.key.trim().uppercase()
                        secretUpdates[envName] = trimmed
                    }
                }

                "boolean" -> {
                    val norm = rawDraft?.trim()?.lowercase().orEmpty()
                        .ifBlank { field.defaultValue.trim().lowercase().ifBlank { "false" } }
                    val boolVal = when (norm) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> throw PluginSettingsValidationException(
                            field.key,
                            "'${field.label}' debe ser un valor booleano (true/false)."
                        )
                    }
                    typedValues[field.key] = JsonPrimitive(boolVal)
                }

                "number" -> {
                    val trimmed = rawDraft?.trim().orEmpty()
                    if (trimmed.isEmpty()) {
                        if (field.required) {
                            throw PluginSettingsValidationException(
                                field.key,
                                "'${field.label}' es obligatorio."
                            )
                        }
                    } else {
                        val numPrimitive = trimmed.toLongOrNull()?.let { JsonPrimitive(it) }
                            ?: trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
                            ?: throw PluginSettingsValidationException(
                                field.key,
                                "'${field.label}' debe ser un número válido."
                            )
                        typedValues[field.key] = numPrimitive
                    }
                }

                "enum" -> {
                    val trimmed = rawDraft?.trim().orEmpty()
                    if (trimmed.isEmpty()) {
                        if (field.required) {
                            throw PluginSettingsValidationException(
                                field.key,
                                "Selecciona una opción para '${field.label}'."
                            )
                        }
                    } else {
                        if (field.choices.isNotEmpty() && trimmed !in field.choices) {
                            throw PluginSettingsValidationException(
                                field.key,
                                "'${field.label}' debe ser uno de: ${field.choices.joinToString(", ")}."
                            )
                        }
                        typedValues[field.key] = JsonPrimitive(trimmed)
                    }
                }

                "json" -> {
                    val trimmed = rawDraft?.trim().orEmpty()
                    if (trimmed.isEmpty()) {
                        if (field.required) {
                            throw PluginSettingsValidationException(
                                field.key,
                                "'${field.label}' requiere un objeto {...} o arreglo [...] JSON."
                            )
                        }
                    } else {
                        val parsed = runCatching { lenientJson.parseToJsonElement(trimmed) }.getOrElse {
                            throw PluginSettingsValidationException(
                                field.key,
                                "JSON inválido en '${field.label}': ${it.message?.take(80) ?: "error de sintaxis"}"
                            )
                        }
                        if (parsed !is JsonObject && parsed !is JsonArray) {
                            throw PluginSettingsValidationException(
                                field.key,
                                "'${field.label}' debe ser un objeto JSON {...} o arreglo [...]."
                            )
                        }
                        typedValues[field.key] = parsed
                    }
                }

                else -> {
                    val value = rawDraft ?: ""
                    if (value.trim().isEmpty() && field.required) {
                        throw PluginSettingsValidationException(
                            field.key,
                            "'${field.label}' es obligatorio."
                        )
                    }
                    typedValues[field.key] = JsonPrimitive(value)
                }
            }
        }

        PluginSettingsSavePlan(
            configValues = JsonObject(typedValues),
            secretEnvUpdates = secretUpdates
        )
    }
}
