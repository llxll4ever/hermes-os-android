package art.rodriguezrodriguez.hermesos.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.network.EndpointProbeResult
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.ui.theme.*

/**
 * Contract Diagnostics Screen
 *
 * Validates every REST route used by `HermesRestClient` against the live backend using
 * authenticated requests (`cookieStore` + `X-Hermes-Session-Token` + `Bearer` + valid POST body).
 * - Emerald = 2xx (or SKIPPED by design in loopback mode)
 * - Amber = informational note (e.g. `hermes-mobile-wall` 404 pending dashboard restart) or transport warning
 * - Rose = 4xx/5xx or connection failure
 */
@Composable
fun ContractDiagnosticsScreen(
    client: HermesRestClient,
    authRequired: Boolean = true
) {
    val endpoints = remember {
        listOf(
            "GET" to "/api/health",
            "GET" to "/api/auth/me",
            "GET" to "/api/auth/providers",
            "GET" to "/api/profiles",
            "GET" to "/api/config/raw?profile=default",
            "GET" to "/api/env?profile=default",
            "GET" to "/api/providers/custom-endpoints?profile=default",
            "GET" to "/api/model/info?profile=default",
            "GET" to "/api/model/options?profile=default",
            "GET" to "/api/analytics/usage?profile=default",
            "GET" to "/api/mcp/catalog?profile=default",
            "GET" to "/api/mcp/servers?profile=default",
            "GET" to "/api/dashboard/plugins/catalog?profile=default",
            "GET" to "/api/dashboard/plugins/hub?profile=default",
            "GET" to "/api/plugins/hermes-mobile-wall/wall",
            "POST" to "/api/auth/ws-ticket"
        )
    }

    var results by remember { mutableStateOf<List<EndpointProbeResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Contract Diagnostics",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = HermesTextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Sondea las ${endpoints.size} rutas REST con la sesión/token activo (${client.activeBaseUrl}).",
            fontSize = 12.sp,
            color = HermesTextSecondary
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                running = true
                results = emptyList()
            },
            enabled = !running
        ) {
            Text(if (running) "Ejecutando sondas..." else "Ejecutar diagnóstico (${endpoints.size} rutas)")
        }

        Spacer(Modifier.height(12.dp))

        if (running) {
            LaunchedEffect(authRequired) {
                results = endpoints.map { (method, path) ->
                    client.probeEndpoint(method = method, path = path, authRequired = authRequired)
                }
                running = false
            }
        }

        LazyColumn {
            items(results) { r ->
                val isInformational404 = r.code == 404 && r.note != null
                val statusColor = when {
                    r.code == 0 -> HermesTeal
                    r.code in 200..299 -> HermesEmerald
                    isInformational404 || r.code in 300..399 -> HermesAmber
                    else -> HermesRose
                }
                val codeLabel = when (r.code) {
                    0 -> "SKP"
                    -1 -> "ERR"
                    else -> r.code.toString().padStart(3)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = codeLabel,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(48.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "${r.method} ${r.path}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = HermesTextPrimary
                        )
                        if (r.note != null) {
                            Text(r.note, fontSize = 11.sp, color = HermesAmber)
                        }
                        if (r.error != null) {
                            Text(r.error, fontSize = 11.sp, color = HermesRose)
                        }
                    }
                    Text("${r.latencyMs}ms", fontSize = 10.sp, color = HermesTextSecondary)
                }
                HorizontalDivider(color = HermesBorderSubtle)
            }
        }
    }
}
