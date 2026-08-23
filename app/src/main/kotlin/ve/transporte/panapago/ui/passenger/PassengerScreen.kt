package ve.transporte.panapago.ui.passenger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ve.transporte.core.qr.QrEnvelope
import ve.transporte.panapago.ui.QrCode
import ve.transporte.panapago.ui.QrScanner

@Composable
fun PassengerScreen(viewModel: PassengerViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    if (state.scanning) {
        Box(Modifier.fillMaxSize()) {
            QrScanner(
                expectedType = QrEnvelope.TYPE_CHALLENGE,
                onScanned = viewModel::onValidatorQrScanned,
            )
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Apunta al QR del cobrador", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = viewModel::cancelScanning) { Text("Cancelar") }
            }
        }
        return
    }

    state.paymentQr?.let { qr ->
        PaymentQrDialog(qr, state.lastPaidCentimos, viewModel::dismissPaymentQr)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Saldo disponible", style = MaterialTheme.typography.labelLarge)
                Text(
                    state.balance,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Gastable sin internet: ${state.offlineSpendable}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.pendingCount > 0) {
                    Text(
                        "${state.pendingCount} viaje(s) por sincronizar",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.securityLevel?.let {
                    Text("Clave protegida por: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Button(
            onClick = viewModel::startScanning,
            enabled = !state.busy && state.balanceCentimos > 0,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("Pagar pasaje", style = MaterialTheme.typography.titleMedium)
        }

        TopUpCard(enabled = !state.busy, onTopUp = viewModel::topUp)

        OutlinedButton(
            onClick = viewModel::sync,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sincronizar (necesita internet)")
        }

        if (state.busy) CircularProgressIndicator()

        state.message?.let { Notice(it, isError = false, onDismiss = viewModel::clearMessages) }
        state.error?.let { Notice(it, isError = true, onDismiss = viewModel::clearMessages) }
    }
}

@Composable
private fun TopUpCard(enabled: Boolean, onTopUp: (String) -> Unit) {
    var amount by remember { mutableStateOf("50.00") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recargar", style = MaterialTheme.typography.titleMedium)
            Text(
                "La recarga necesita internet. Despues el saldo queda bloqueado en el " +
                    "telefono y se puede gastar sin senal.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Bolivares") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onTopUp(amount) }, enabled = enabled) { Text("Recargar") }
            }
        }
    }
}

@Composable
private fun PaymentQrDialog(qr: String, paidCentimos: Long?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        title = { Text("Muestrale este QR al cobrador") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(qr, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(12.dp))
                paidCentimos?.let {
                    Text(
                        ve.transporte.core.protocol.Money.format(it),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text(
                    "El saldo ya se descontó. Al recuperar internet se sincroniza solo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun Notice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onDismiss) { Text("Entendido") }
        }
    }
}
