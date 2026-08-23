package ve.transporte.panapago.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.JvmSigner
import ve.transporte.core.crypto.TrustStore
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.ValidatorReceipt
import ve.transporte.core.server.SettlementServer
import ve.transporte.core.wallet.SyncAck

/**
 * Backend de DEMOSTRACION que corre dentro del propio telefono.
 *
 * Existe para que la app se pueda probar de punta a punta sin levantar un
 * servidor: recargar, pagar sin internet, cobrar y liquidar. La logica de
 * dinero es la misma [SettlementServer] que se probo en el modulo core.
 *
 * ADVERTENCIA: esto NO es un backend real y no debe llegar a produccion.
 *
 *  - La clave privada del emisor esta escrita aqui abajo, en el codigo. Quien
 *    tenga el APK la tiene, y con ella se emite todo el saldo que quiera. En
 *    produccion esa clave vive en un HSM y solo el servidor la usa.
 *  - El estado se pierde al cerrar la app.
 *  - No hay cobro real ni pago real en bolivares.
 *
 * El modo demostracion se activa solo cuando [ve.transporte.panapago.security.TrustAnchors]
 * no trae claves reales configuradas.
 */
class DemoBackend(private val clock: Clock = Clock.SYSTEM) : BackendApi {

    private val issuer = JvmSigner.fromEncoded(
        keyId = ISSUER_KEY_ID,
        pkcs8Private = Base64Url.decode(DEMO_ISSUER_PRIVATE),
        x509Public = Base64Url.decode(DEMO_ISSUER_PUBLIC),
    )

    private val server = SettlementServer(issuer, clock)
    private val mutex = Mutex()

    val trustStore: TrustStore = TrustStore.of(ISSUER_KEY_ID to issuer.publicKeyEncoded)

    override suspend fun enrollWallet(
        devicePublicKey: ByteArray,
        attestationChain: List<ByteArray>,
    ): String = mutex.withLock {
        // Un backend real validaria aqui la cadena de atestacion contra la raiz
        // de Google: que la clave sea de hardware y el arranque este bloqueado.
        server.enrollWallet(devicePublicKey).walletId
    }

    override suspend fun topUp(walletId: String, amountCentimos: Long): SignedGrant = mutex.withLock {
        server.topUp(walletId, amountCentimos)
    }

    override suspend fun syncWallet(walletId: String, spends: List<SignedSpend>): SyncAck =
        mutex.withLock {
            val ack = server.ingestWalletSync(walletId, spends)
            server.reconcile()
            ack
        }

    override suspend fun uploadReceipts(
        validatorId: String,
        receipts: List<ValidatorReceipt>,
    ): List<String> = mutex.withLock {
        val ids = server.ingestValidatorBatch(validatorId, receipts)
        server.reconcile()
        ids
    }

    override suspend fun hotlist(): Set<String> = mutex.withLock { server.hotlist() }

    override suspend fun validatorCert(validatorId: String): SignedValidatorCert = mutex.withLock {
        error("en modo demostracion el certificado se emite con enrollValidatorLocal()")
    }

    /** Alta local de un validador, para poder probar el modo cobrador. */
    suspend fun enrollValidatorLocal(
        validatorId: String,
        unitId: String,
        ownerId: String,
        validatorPublicKey: ByteArray,
    ): SignedValidatorCert = mutex.withLock {
        server.enrollValidator(validatorId, unitId, ownerId, validatorPublicKey)
    }

    /** Lo que se le pagaria al dueno de la unidad, en bolivares. */
    suspend fun settle(ownerId: String) = mutex.withLock { server.settle(ownerId) }

    suspend fun fraudDossier(): String = mutex.withLock { server.fraudDossier() }

    companion object {
        const val ISSUER_KEY_ID = "emisor-demo-2026-01"

        // Claves de juguete. Publicas a proposito: esto es una demostracion.
        private const val DEMO_ISSUER_PRIVATE =
            "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBb5TUqCrU6c_Mus9TjiU5khU-w-K_EeNC215-L8pmYvA"
        private const val DEMO_ISSUER_PUBLIC =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE6yP5f8HMiqEMdOUjS25eRXSn5JvDAMEAnZEO3Pdcm069WAHBhgWSHVbEXglioA67v0cMfsq0gTX8YACLXmuS1A"
    }
}
