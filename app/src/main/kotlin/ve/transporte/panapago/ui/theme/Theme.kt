package ve.transporte.panapago.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amarillo = Color(0xFFF5C518)
private val Azul = Color(0xFF0B3D91)
private val Rojo = Color(0xFFC8102E)

private val ClaroScheme = lightColorScheme(
    primary = Azul,
    secondary = Amarillo,
    error = Rojo,
)

private val OscuroScheme = darkColorScheme(
    primary = Amarillo,
    secondary = Azul,
    error = Rojo,
)

@Composable
fun PanaPagoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) OscuroScheme else ClaroScheme,
        content = content,
    )
}
