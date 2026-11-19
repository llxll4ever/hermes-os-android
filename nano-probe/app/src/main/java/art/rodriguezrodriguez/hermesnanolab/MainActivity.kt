package art.rodriguezrodriguez.hermesnanolab

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.FunctionArgValue
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import com.google.mlkit.genai.schema.tools.FunctionDeclaration
import com.google.mlkit.genai.schema.tools.FunctionSchema
import com.google.mlkit.genai.schema.tools.FunctionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Isolated on-device test of the preview Fast model.
 *
 * This app never invokes Home Assistant. Function calls are printed and checked
 * against a tiny local allowlist only, so model hallucinations are visible.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val allowedRooms = setOf("sala", "cocina")

    private val model by lazy {
        Generation.getClient(
            generationConfig {
                modelConfig = modelConfig {
                    releaseStage = ModelReleaseStage.PREVIEW
                    preference = ModelPreference.FAST
                }
            }
        )
    }

    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var commandInput: EditText
    private lateinit var checkButton: Button
    private lateinit var testButton: Button

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 18)
        window.navigationBarColor = Color.rgb(7, 11, 18)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(30))
            setBackgroundColor(Color.rgb(7, 11, 18))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        fun label(text: String, size: Float, color: Int): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setPadding(0, dp(5), 0, dp(10))
                root.addView(this)
            }
        }

        label("HERMES / NANO LAB", 25f, Color.rgb(125, 221, 201))
        label("Galaxy S24 Ultra · Gemini Nano 4 Fast Preview (CPU / AICore)", 13f, Color.LTGRAY)
        label("Experimento local. No envía solicitudes a Internet ni ejecuta órdenes.", 14f, Color.WHITE)

        statusText = label("Estado: no comprobado", 16f, Color.rgb(255, 188, 95))
        checkButton = Button(this).apply {
            text = "1 · Comprobar AICore"
            setOnClickListener { checkModel() }
            root.addView(this)
        }

        commandInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 3
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Escribe una orden de Home Assistant"
            setText("Apaga la luz de la cocina y enciende la sala al 40%.")
            root.addView(this)
        }

        testButton = Button(this).apply {
            text = "2 · Probar llamadas a funciones"
            isEnabled = false
            setOnClickListener { testFunctionCalling() }
            root.addView(this)
        }

        resultText = label("Resultados: se mostrarán aquí.", 15f, Color.WHITE)
        label(
            "MODO SEGURO: el modelo puede proponer turn_on_light / turn_off_light, " +
                "pero ninguna herramienta se ejecutará. Solo se aceptan sala y cocina.",
            12f,
            Color.LTGRAY
        )
    }

    private fun checkModel() {
        checkButton.isEnabled = false
        statusText.text = "Consultando disponibilidad de Gemini Nano 4 Fast…"
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) { model.checkStatus() }
                when (status) {
                    FeatureStatus.AVAILABLE -> {
                        statusText.text = "DISPONIBLE · AICore permite usar el modelo."
                        testButton.isEnabled = true
                    }
                    FeatureStatus.DOWNLOADABLE -> {
                        statusText.text = "DESCARGABLE · Descarga el modelo desde AICore y vuelve a comprobar."
                        testButton.isEnabled = false
                    }
                    FeatureStatus.DOWNLOADING -> {
                        statusText.text = "DESCARGANDO · Espera y vuelve a comprobar."
                        testButton.isEnabled = false
                    }
                    else -> {
                        statusText.text = "NO DISPONIBLE · Estado devuelto: " + status
                        testButton.isEnabled = false
                    }
                }
            } catch (e: Exception) {
                statusText.text = "ERROR " + e.javaClass.simpleName + ": " + e.message
                testButton.isEnabled = false
            } finally {
                checkButton.isEnabled = true
            }
        }
    }

    private fun testFunctionCalling() {
        val command = commandInput.text.toString().trim()
        if (command.isEmpty()) {
            resultText.text = "Escribe una orden para continuar."
            return
        }
        testButton.isEnabled = false
        resultText.text = "Generando propuestas de herramientas localmente…"
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val available = model.checkStatus()
                    if (available != FeatureStatus.AVAILABLE) {
                        return@withContext "No se ejecutó inferencia: el modelo no está disponible."
                    }

                    val room = FunctionSchema(
                        type = FunctionType.STRING,
                        description = "Nombre exacto de la habitación: sala o cocina"
                    )
                    val on = FunctionDeclaration(
                        name = "turn_on_light",
                        description = "Encender una luz de la sala o cocina con porcentaje de brillo",
                        parameters = FunctionSchema(
                            type = FunctionType.OBJECT,
                            properties = mapOf(
                                "room" to room,
                                "brightness" to FunctionSchema(
                                    type = FunctionType.INTEGER,
                                    description = "Porcentaje de brillo del 0 al 100"
                                )
                            ),
                            required = listOf("room")
                        )
                    )
                    val off = FunctionDeclaration(
                        name = "turn_off_light",
                        description = "Apagar una luz de la sala o cocina",
                        parameters = FunctionSchema(
                            type = FunctionType.OBJECT,
                            properties = mapOf("room" to room),
                            required = listOf("room")
                        )
                    )
                    val request = generateContentRequest(
                        SystemInstruction(
                            "Eres Hermes. Propón únicamente herramientas para controlar " +
                                "las luces sala y cocina. No inventes otras habitaciones ni actúes."
                        ),
                        TextPart(command)
                    ) {
                        setTools(listOf(on, off))
                        temperature = 0.2f
                        maxOutputTokens = 256
                    }
                    val response = model.generateContent(request)
                    val candidate = response.candidates.firstOrNull()
                    val calls = candidate?.functionCalls.orEmpty()
                    if (calls.isEmpty()) {
                        return@withContext "Sin llamadas a herramientas. Texto del modelo:\n" +
                            (candidate?.text ?: "(sin salida)")
                    }
                    buildString {
                        append("Propuestas recibidas: ").append(calls.size).append("\n\n")
                        for (call in calls) {
                            val roomValue =
                                (call.arguments["room"] as? FunctionArgValue.StringValue)
                                    ?.value?.trim()?.lowercase(Locale.ROOT).orEmpty()
                            val brightness =
                                (call.arguments["brightness"] as? FunctionArgValue.IntValue)?.value
                            val approved =
                                call.name in setOf("turn_on_light", "turn_off_light") &&
                                    roomValue in allowedRooms &&
                                    (call.name == "turn_off_light" ||
                                        brightness == null || brightness in 0..100)
                            append(if (approved) "[VÁLIDA, NO EJECUTADA]\n" else "[BLOQUEADA]\n")
                            append("Función: ").append(call.name).append("\n")
                            append("Argumentos: ").append(call.arguments).append("\n\n")
                        }
                        append("No se hizo ninguna solicitud a Home Assistant.")
                    }
                }
                resultText.text = text
            } catch (e: Exception) {
                resultText.text = "ERROR " + e.javaClass.simpleName + ": " + e.message +
                    "\nCopia el texto del error para corregir la integración."
            } finally {
                testButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
