package ve.transporte.panapago.net

import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.ValidatorReceipt
import ve.transporte.core.wallet.SyncAck

/**
 * Lo unico que necesita internet.
 *
 * Todo lo demas (pagar, cobrar, verificar) ocurre sin red. Estas llamadas se
 * hacen cuando hay senal, y si fallan la app sigue funcionando con lo que ya
 * tiene guardado.
 */
interface BackendApi {

    /** Alta del monedero: se envia la clave publica y su atestacion de hardware. */
    suspend fun enrollWallet(devicePublicKey: ByteArray, attestationChain: List<ByteArray>): String

    /** Recarga. Devuelve el vale firmado que queda bloqueado en el telefono. */
    suspend fun topUp(walletId: String, amountCentimos: Long): SignedGrant

    /** Sube los gastos hechos sin internet y libera cupo offline. */
    suspend fun syncWallet(walletId: String, spends: List<SignedSpend>): SyncAck

    /** El validador sube sus recibos; esto dispara el pago en bolivares al dueno. */
    suspend fun uploadReceipts(validatorId: String, receipts: List<ValidatorReceipt>): List<String>

    /** Lista negra de monederos bloqueados por fraude. */
    suspend fun hotlist(): Set<String>

    /** Certificado vigente del validador. */
    suspend fun validatorCert(validatorId: String): SignedValidatorCert
}

class BackendUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
