package ve.transporte.panapago.ui.validator

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ve.transporte.core.protocol.Money
import ve.transporte.core.qr.QrEnvelope
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.validator.OfflineValidator
import ve.transporte.core.validator.RejectReason
import ve.transporte.core.validator.ValidatorConfig
import ve.transporte.panapago.AppContainer
import ve.transporte.panapago.data.AppPrefs
import ve.transporte.panapago.security.KeystoreSigner

data class ValidatorUiState(
    val ready: Boolean = false,
    val unitId: String = "",
    val ownerId: String = "",
    val fareCentimos: Long = 5_00,
    val challengeQr: String? = null,
    val scanning: Boolean = false,
    val accruedCentimos: Long = 0,
    val pendingReceipts: Int = 0,
    val lastResult: ChargeResult? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val fare: String get() = Money.format(fareCentimos)
    val accrued: String get() = Money.format(accruedCentimos)
}

sealed interface ChargeResult {
    data class Ok(val amount: String, val passengerBalance: String) : ChargeResult
    data class AlreadyPaid(val detail: String) : ChargeResult
    data class Rejected(val title: String, val detail: String) : ChargeResult
}

/**
 * Modo cobrador: el aparato que va en la unidad.
 *
 * Muestra un QR de cobro nuevo para cada pasajero, lee el QR de pago y lo
 * verifica sin internet. Solo necesita red para subir los recibos (que es lo
 * que dispara el pago al dueno) y para bajar la lista negra.
 */
class ValidatorViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val backend = AppContainer.backend

    private var validator: OfflineValidator? = null

    private val _state = MutableStateFlow(ValidatorUiState())
    val state: StateFlow<ValidatorUiState> = _state.asStateFlow()

    fun setUp(unitId: String, ownerId: String, fareBolivares: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val fare = Money.fromBolivares(fareBolivares)
                val validatorId = prefs.validatorId ?: "v-$unitId"
                val signer = withContext(Dispatchers.IO) {
                    KeystoreSigner.getOrCreate("pana-pago-validador-v1")
                }
                val cert = AppContainer.demoBackend?.enrollValidatorLocal(
                    validatorId, unitId, ownerId, signer.publicKeyEncoded,
                ) ?: backend.validatorCert(validatorId)

                prefs.validatorId = validatorId
                prefs.unitId = unitId
                prefs.ownerId = ownerId

                validator = OfflineValidator(
                    config = ValidatorConfig(
                        validatorId = validatorId,
                        unitId = unitId,
                        ownerId = ownerId,
                        routeId = "ruta-01",
                        fareCentimos = fare,
                    ),
                    signer = signer,
                    cert = cert,
                    trust = AppContainer.trustStore,
                    clock = AppContainer.clock,
                )
                _state.update {
                    it.copy(ready = true, unitId = unitId, ownerId = ownerId, fareCentimos = fare)
                }
                refreshHotlist()
            } catch (e: Exception) {
                Log.e(TAG, "no se pudo configurar el validador", e)
                _state.update { it.copy(error = e.message ?: "No se pudo configurar") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Abre un cobro: genera el QR con nonce nuevo y caducidad corta. */
    fun startCharge() {
        val v = validator ?: return
        val challenge = v.newChallenge()
        _state.update {
            it.copy(
                challengeQr = QrEnvelope.encode(challenge),
                lastResult = null,
                error = null,
                message = null,
            )
        }
    }

    fun startScanning() {
        _state.update { it.copy(scanning = true) }
    }

    fun onPaymentQrScanned(qr: String) {
        val v = validator ?: return
        val result = when (val r = v.accept(qr)) {
            is AcceptResult.Accepted -> ChargeResult.Ok(
                amount = Money.format(r.fareCentimos),
                passengerBalance = Money.format(r.passengerBalanceAfterCentimos),
            )

            is AcceptResult.AlreadyAccepted -> ChargeResult.AlreadyPaid(
                "Ese QR ya se cobro. No es un pago nuevo.",
            )

            is AcceptResult.Rejected -> ChargeResult.Rejected(humanize(r.reason), r.detail)
        }
        _state.update {
            it.copy(
                scanning = false,
                challengeQr = null,
                lastResult = result,
                accruedCentimos = v.accruedCentimos(),
                pendingReceipts = v.pendingReceipts.size,
            )
        }
    }

    fun cancelScanning() {
        _state.update { it.copy(scanning = false) }
    }

    /** Sube los recibos: esto es lo que le hace llegar el dinero al dueno. */
    fun uploadReceipts() {
        val v = validator ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                val receipts = v.pendingReceipts
                if (receipts.isEmpty()) {
                    _state.update { it.copy(message = "No hay recibos que subir") }
                    return@launch
                }
                val ids = backend.uploadReceipts(v.config.validatorId, receipts)
                v.clearSettled(ids)
                refreshHotlist()
                _state.update {
                    it.copy(
                        message = "Subidos ${ids.size} pasaje(s) por ${Money.format(receipts.sumOf { r -> r.amountCentimos })}",
                        accruedCentimos = v.accruedCentimos(),
                        pendingReceipts = v.pendingReceipts.size,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "no se pudieron subir los recibos", e)
                _state.update { it.copy(error = "Sin conexion. Los recibos quedan guardados.") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun refreshHotlist() {
        try {
            validator?.updateHotlist(backend.hotlist())
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo bajar la lista negra", e)
        }
    }

    fun clearMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun humanize(reason: RejectReason): String = when (reason) {
        RejectReason.QR_ILEGIBLE -> "QR ilegible"
        RejectReason.FIRMA_DEL_VALE_INVALIDA,
        RejectReason.EMISOR_DESCONOCIDO,
        -> "SALDO FALSO — no lo dejes pasar"

        RejectReason.CLAVE_NO_CORRESPONDE_AL_VALE -> "Saldo copiado de otro telefono"
        RejectReason.DOBLE_GASTO_DETECTADO -> "Pago repetido — ya se cobro"
        RejectReason.MONEDERO_BLOQUEADO -> "Monedero bloqueado por fraude"
        RejectReason.VALE_CADUCADO -> "El saldo del pasajero vencio"
        RejectReason.RETO_DESCONOCIDO -> "Ese QR no responde a este cobro"
        RejectReason.RETO_CADUCADO -> "El cobro vencio, genera otro"
        RejectReason.MONTO_NO_COINCIDE_CON_EL_PASAJE -> "El monto no es el del pasaje"
        RejectReason.NO_ES_PARA_ESTA_UNIDAD -> "Ese pago era para otra unidad"
        RejectReason.FECHA_FUERA_DE_RANGO -> "Hora del pago fuera de rango"
        RejectReason.ARITMETICA_INVALIDA -> "Cuentas del pago invalidas"
        RejectReason.FIRMA_DE_GASTO_INVALIDA -> "Firma del pago invalida"
    }

    private companion object {
        const val TAG = "ValidatorViewModel"
    }
}
