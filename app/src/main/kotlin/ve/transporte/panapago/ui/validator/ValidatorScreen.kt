package ve.transporte.panapago.ui.validator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ve.transporte.core.qr.QrEnvelope
import ve.transporte.panapago.ui.QrCode
import ve.transporte.panapago.ui.QrScanner

@Composable
fun ValidatorScreen(viewModel: ValidatorViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    if (!state.ready) {
        SetUpForm(busy = state.busy, error = state.error, onSetUp = viewModel::setUp)
        return
    }

    if (state.scanning) {
        Box(Modifier.fillMaxSize()) {
            QrScanner(
                expectedType = QrEnvelope.TYPE_SPEND,
                onScanned = viewModel::onPaymentQrScanned,
            )
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Apunta al QR del pasajero", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::cancelScanning) { Text("Cancelar") }
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Unidad ${state.unitId}", style = MaterialTheme.typography.titleMedium)
                Text("Pasaje: ${state.fare}")
                Text(
                    "Acumulado por cobrar: ${state.accrued}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${state.pendingReceipts} recibo(s) sin subir",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        state.challengeQr?.let { qr ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Que el pasajero escanee esto")
                    QrCode(qr, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(
                        "Vence en 45 segundos. Cada cobro genera un QR distinto.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = viewModel::startScanning, modifier = Modifier.fillMaxWidth()) {
                        Text("Ya escaneó: leer su pago")
                    }
                }
            }
        }

        Button(
            onClick = viewModel::startCharge,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("Cobrar pasaje", style = MaterialTheme.typography.titleMedium)
        }

        state.lastResult?.let { ResultCard(it) }

        OutlinedButton(
            onClick = viewModel::uploadReceipts,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Subir recibos y cobrar en Bs")
        }

        if (state.busy) CircularProgressIndicator()
        state.message?.let { Text(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.message != null || state.error != null) {
            TextButton(onClick = viewModel::clearMessages) { Text("Entendido") }
        }
    }
}

@Composable
private fun ResultCard(result: ChargeResult) {
    val (color, title, detail) = when (result) {
        is ChargeResult.Ok -> Triple(
            Color(0xFF1B5E20),
            "PAGO ACEPTADO — ${result.amount}",
            "Le queda ${result.passengerBalance}",
        )

        is ChargeResult.AlreadyPaid -> Triple(
            Color(0xFFE65100),
            "YA COBRADO",
            result.detail,
        )

        is ChargeResult.Rejected -> Triple(
            Color(0xFFB71C1C),
            "RECHAZADO — ${result.title}",
            result.detail,
        )
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detail, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SetUpForm(busy: Boolean, error: String?, onSetUp: (String, String, String) -> Unit) {
    var unit by remember { mutableStateOf("bus-01") }
    var owner by remember { mutableStateOf("don-enrique") }
    var fare by remember { mutableStateOf("5.00") }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Configurar la unidad", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(unit, { unit = it }, label = { Text("Unidad") }, singleLine = true)
        OutlinedTextField(owner, { owner = it }, label = { Text("Dueno (a quien se le paga)") }, singleLine = true)
        OutlinedTextField(fare, { fare = it }, label = { Text("Pasaje en Bs") }, singleLine = true)
        Button(onClick = { onSetUp(unit, owner, fare) }, enabled = !busy) { Text("Listo") }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
