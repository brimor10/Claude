package ve.transporte.panapago.ui.passenger

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
import ve.transporte.core.qr.QrFormatException
import ve.transporte.core.wallet.OfflineWallet
import ve.transporte.core.wallet.SpendOutcome
import ve.transporte.core.wallet.WalletBook
import ve.transporte.panapago.AppContainer
import ve.transporte.panapago.data.AppPrefs
import ve.transporte.panapago.security.KeystoreSigner

data class PassengerUiState(
    val walletId: String? = null,
    val balanceCentimos: Long = 0,
    val offlineSpendableCentimos: Long = 0,
    val pendingCount: Int = 0,
    val securityLevel: KeystoreSigner.SecurityLevel? = null,
    /** QR de pago listo para mostrarle al cobrador. */
    val paymentQr: String? = null,
    val lastPaidCentimos: Long? = null,
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    val balance: String get() = Money.format(balanceCentimos)
    val offlineSpendable: String get() = Money.format(offlineSpendableCentimos)
}

class PassengerViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = AppPrefs(app)
    private val repository = AppContainer.walletRepository
    private val backend = AppContainer.backend

    private lateinit var signer: KeystoreSigner
    private lateinit var book: WalletBook

    private val _state = MutableStateFlow(PassengerUiState())
    val state: StateFlow<PassengerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    signer = KeystoreSigner.getOrCreate(KEY_ALIAS)
                    val engine = OfflineWallet(signer, AppContainer.trustStore, AppContainer.clock)
                    book = WalletBook(engine, repository.load())
                }
                _state.update { it.copy(securityLevel = signer.securityLevel) }
                ensureEnrolled()
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "no se pudo iniciar el monedero", e)
                _state.update { it.copy(error = "No se pudo iniciar el monedero: ${e.message}") }
            }
        }
    }

    /** El alta necesita internet. Sin ella la app sigue sirviendo con lo ya cargado. */
    private suspend fun ensureEnrolled() {
        if (prefs.walletId != null) return
        try {
            val id = backend.enrollWallet(
                signer.publicKeyEncoded,
                signer.attestationChain.map { it.encoded },
            )
            prefs.walletId = id
        } catch (e: Exception) {
            Log.w(TAG, "alta pendiente, hace falta internet", e)
        }
    }

    fun topUp(bolivares: String) = launchBusy {
        val centimos = try {
            Money.fromBolivares(bolivares)
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = "Monto invalido") }
            return@launchBusy
        }
        ensureEnrolled()
        val walletId = prefs.walletId
            ?: run {
                _state.update { it.copy(error = "Hace falta internet para dar de alta el monedero") }
                return@launchBusy
            }
        val grant = backend.topUp(walletId, centimos)
        when (val outcome = book.addGrant(grant)) {
            is ve.transporte.core.wallet.LoadGrantOutcome.Loaded -> {
                persist()
                _state.update { it.copy(message = "Recarga de ${Money.format(centimos)} lista") }
            }

            is ve.transporte.core.wallet.LoadGrantOutcome.Rejected ->
                _state.update { it.copy(error = "Recarga rechazada: ${outcome.reason} (${outcome.detail})") }
        }
    }

    fun startScanning() {
        _state.update { it.copy(scanning = true, paymentQr = null, error = null, message = null) }
    }

    fun cancelScanning() {
        _state.update { it.copy(scanning = false) }
    }

    /** Aqui ocurre el pago, sin internet: se lee el QR del cobrador y se firma. */
    fun onValidatorQrScanned(qr: String) {
        val challenge = try {
            QrEnvelope.decodeChallenge(qr)
        } catch (e: QrFormatException) {
            _state.update { it.copy(scanning = false, error = "Ese QR no es de cobro: ${e.message}") }
            return
        }
        when (val outcome = book.pay(challenge)) {
            is SpendOutcome.Approved -> {
                persist()
                _state.update {
                    it.copy(
                        scanning = false,
                        paymentQr = outcome.qr,
                        lastPaidCentimos = outcome.spend.token.amountCentimos,
                        message = "Pagado ${Money.format(outcome.spend.token.amountCentimos)}. " +
                            "Muestrale este QR al cobrador.",
                    )
                }
                refresh()
            }

            is SpendOutcome.Denied ->
                _state.update {
                    it.copy(scanning = false, error = "${humanize(outcome.reason)} — ${outcome.detail}")
                }
        }
    }

    fun dismissPaymentQr() {
        _state.update { it.copy(paymentQr = null) }
    }

    fun sync() = launchBusy {
        val walletId = prefs.walletId ?: return@launchBusy
        val pending = book.pendingSpends
        if (pending.isEmpty()) {
            _state.update { it.copy(message = "No hay nada que sincronizar") }
            return@launchBusy
        }
        val ack = backend.syncWallet(walletId, pending)
        book.applySync(ack)
        persist()
        _state.update { it.copy(message = "Sincronizados ${ack.confirmedLinkIds.size} viaje(s)") }
    }

    private fun persist() {
        try {
            repository.save(book.states())
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo guardar el monedero", e)
            _state.update { it.copy(error = "No se pudo guardar el monedero: ${e.message}") }
        }
        refresh()
    }

    private fun refresh() {
        _state.update {
            it.copy(
                walletId = prefs.walletId,
                balanceCentimos = book.totalBalanceCentimos,
                offlineSpendableCentimos = book.offlineSpendableCentimos,
                pendingCount = book.pendingSpends.size,
            )
        }
    }

    fun clearMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "operacion fallida", e)
                _state.update { it.copy(error = e.message ?: "Fallo la operacion") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun humanize(reason: ve.transporte.core.wallet.DenyReason): String = when (reason) {
        ve.transporte.core.wallet.DenyReason.SIN_SALDO_CARGADO -> "No tienes saldo cargado"
        ve.transporte.core.wallet.DenyReason.SALDO_INSUFICIENTE -> "Saldo insuficiente"
        ve.transporte.core.wallet.DenyReason.TOPE_OFFLINE_ALCANZADO ->
            "Llegaste al tope sin conexion: conectate para liberar mas saldo"

        ve.transporte.core.wallet.DenyReason.TOPE_DE_VIAJES_OFFLINE_ALCANZADO ->
            "Llegaste al tope de viajes sin conexion"

        ve.transporte.core.wallet.DenyReason.VALE_CADUCADO -> "Tu saldo vencio"
        ve.transporte.core.wallet.DenyReason.RETO_CADUCADO -> "El QR del cobrador ya vencio, pide otro"
        ve.transporte.core.wallet.DenyReason.RETO_YA_USADO -> "Ese cobro ya lo pagaste"
        ve.transporte.core.wallet.DenyReason.CERTIFICADO_DE_VALIDADOR_INVALIDO,
        ve.transporte.core.wallet.DenyReason.CERTIFICADO_NO_CORRESPONDE_AL_VALIDADOR,
        ve.transporte.core.wallet.DenyReason.CERTIFICADO_DE_VALIDADOR_CADUCADO,
        ve.transporte.core.wallet.DenyReason.FIRMA_DEL_RETO_INVALIDA,
        -> "Cuidado: ese cobrador no esta registrado. No pagues."

        else -> "No se pudo pagar ($reason)"
    }

    private companion object {
        const val TAG = "PassengerViewModel"
        const val KEY_ALIAS = "pana-pago-monedero-v1"
    }
}
