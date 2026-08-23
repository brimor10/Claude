package ve.transporte.panapago

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ve.transporte.panapago.ui.passenger.PassengerScreen
import ve.transporte.panapago.ui.theme.PanaPagoTheme
import ve.transporte.panapago.ui.validator.ValidatorScreen

/**
 * La misma app sirve para las dos puntas: el pasajero que paga y el cobrador
 * que valida. En produccion serian dos APK distintos (o dos perfiles con
 * permisos distintos); aqui van juntos para poder probar el flujo completo con
 * dos telefonos.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PanaPagoTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RootScreen()
                }
            }
        }
    }
}

@Composable
private fun RootScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (AppContainer.isDemoMode) {
                Text(
                    "MODO DEMOSTRACION — el emisor corre dentro del telefono. No usar con dinero real.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pasajero") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Cobrador") })
            }
            when (tab) {
                0 -> PassengerScreen()
                else -> ValidatorScreen()
            }
        }
    }
}
