package art.rodriguezrodriguez.hermesos.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HermesBgDeep = Color(0xFF070B12)
val HermesSurfaceCard = Color(0xFF0E1624)
val HermesSurfaceElevated = Color(0xFF152034)
val HermesBorderSubtle = Color(0xFF23324D)
val HermesTeal = Color(0xFF00E5C3)
val HermesViolet = Color(0xFF7C5CFF)
val HermesAmber = Color(0xFFFFB020)
val HermesEmerald = Color(0xFF10B981)
val HermesRose = Color(0xFFFF4D6D)
val HermesTextPrimary = Color(0xFFF1F5F9)
val HermesTextSecondary = Color(0xFF94A3B8)

private val HermesDarkColors = darkColorScheme(
    primary = HermesTeal,
    onPrimary = Color(0xFF031815),
    primaryContainer = Color(0xFF0B3530),
    onPrimaryContainer = HermesTeal,
    secondary = HermesViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF231C4C),
    onSecondaryContainer = Color(0xFFD7CEFF),
    tertiary = HermesAmber,
    background = HermesBgDeep,
    onBackground = HermesTextPrimary,
    surface = HermesSurfaceCard,
    onSurface = HermesTextPrimary,
    surfaceVariant = HermesSurfaceElevated,
    onSurfaceVariant = HermesTextSecondary,
    outline = HermesBorderSubtle,
    error = HermesRose
)

@Composable
fun HermesOsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HermesDarkColors,
        content = content
    )
}
