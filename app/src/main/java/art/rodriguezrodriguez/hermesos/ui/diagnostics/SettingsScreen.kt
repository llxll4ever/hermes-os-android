package art.rodriguezrodriguez.hermesos.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import art.rodriguezrodriguez.hermesos.core.network.HermesRestClient
import art.rodriguezrodriguez.hermesos.feature.desktop.HermesOsViewModel
import art.rodriguezrodriguez.hermesos.feature.desktop.OsDockDestination
import art.rodriguezrodriguez.hermesos.ui.theme.*

/**
 * Settings Screen - Central place for app configuration and diagnostics access.
 */
@Composable
fun SettingsScreen(vm: HermesOsViewModel, client: HermesRestClient) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Ajustes",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = HermesTextPrimary
        )

        // Diagnostics Section
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = HermesViolet,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Diagnóstico de Contrato",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = HermesTextPrimary
                            )
                            Text(
                                text = "Valida rutas REST contra el backend autenticado",
                                fontSize = 12.sp,
                                color = HermesTextSecondary
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = { vm.selectDestination(OsDockDestination.DIAGNOSTICS) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir Diagnóstico de Contrato", fontSize = 14.sp)
                }
            }
        }

        // App Info Section
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Información de la App",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = HermesTeal
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Versión", color = HermesTextSecondary)
                    Text("0.1.0-backbone", fontFamily = FontFamily.Monospace, color = HermesTextPrimary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Build", color = HermesTextSecondary)
                    Text("2026-10-29", fontFamily = FontFamily.Monospace, color = HermesTextPrimary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Arquitectura", color = HermesTextSecondary)
                    Text("ARM64", fontFamily = FontFamily.Monospace, color = HermesTeal)
                }
            }
        }

        // Backend Connection Info
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = HermesSurfaceCard,
            border = BorderStroke(1.dp, HermesBorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Backend Hermes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = HermesTeal
                )
                Text(
                    text = "Conectado vía Tailscale (100.103.166.20:9119) o Cloudflare Tunnel",
                    fontSize = 12.sp,
                    color = HermesTextSecondary
                )
                Text(
                    text = "Dashboard: https://hermes.rodriguezrodriguez.art",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = HermesViolet
                )
            }
        }
    }
}