package art.rodriguezrodriguez.hermesos.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Contract Diagnostics Screen
 *
 * Validates every REST route used by HermesRestClient against the live backend.
 * Green = 2xx, Yellow = 3xx/timeout, Red = 4xx/5xx or connection error.
 * Requires an authenticated session (cookie jar must be populated).
 */
@Composable
fun ContractDiagnosticsScreen(client: HermesRestClient) {
    val endpoints = remember {
        listOf(
            "GET" to "/api/health",
            "GET" to "/api/auth/me",
            "GET" to "/api/auth/providers",
            "GET" to "/api/profiles",
            "GET" to "/api/config/raw?profile=default",
            "GET" to "/api/env?profile=default",
            "GET" to "/api/model/info?profile=default",
            "GET" to "/api/model/options?profile=default",
            "GET" to "/api/mcp/catalog",
            "GET" to "/api/mcp/servers",
            "GET" to "/api/dashboard/plugins/catalog",
            "GET" to "/api/dashboard/plugins/hub",
            "POST" to "/api/auth/ws-ticket"
        )
    }

    var results by remember { mutableStateOf<List<DiagResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Contract Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Prueba cada ruta REST con la sesión activa. Verde = OK, Rojo = fallo.", fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                running = true
                results = emptyList()
            },
            enabled = !running
        ) {
            Text(if (running) "Ejecutando..." else "Ejecutar diagnóstico")
        }

        Spacer(Modifier.height(12.dp))

        if (running) {
            LaunchedEffect(Unit) {
                val r = withContext(Dispatchers.IO) {
                    endpoints.map { (method, path) ->
                        val url = "${client.activeBaseUrl}$path"
                        val req = Request.Builder().url(url).method(method, null).build()
                        val t0 = System.currentTimeMillis()
                        try {
                            client.httpClient.newCall(req).execute().use { resp ->
                                DiagResult(path, method, resp.code, System.currentTimeMillis() - t0, null)
                            }
                        } catch (e: Exception) {
                            DiagResult(path, method, -1, System.currentTimeMillis() - t0, e.message)
                        }
                    }
                }
                results = r
                running = false
            }
        }

        LazyColumn {
            items(results) { r ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color = when {
                        r.code in 200..299 -> Color(0xFF2E7D32)
                        r.code == -1 -> Color(0xFFE65100)
                        else -> Color(0xFFC62828)
                    }
                    Text(
                        text = r.code.toString().padStart(3),
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(48.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(r.path, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        if (r.error != null) {
                            Text(r.error!!, fontSize = 10.sp, color = Color.Red)
                        }
                    }
                    Text("${r.latencyMs}ms", fontSize = 10.sp, color = Color.Gray)
                }
                HorizontalDivider()
            }
        }
    }
}

data class DiagResult(
    val path: String,
    val method: String,
    val code: Int,
    val latencyMs: Long,
    val error: String?
)