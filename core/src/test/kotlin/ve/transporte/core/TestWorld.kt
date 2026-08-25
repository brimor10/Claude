package ve.transporte.core

import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.JvmSigner
import ve.transporte.core.crypto.TrustStore
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.server.IssuancePolicy
import ve.transporte.core.server.SettlementServer
import ve.transporte.core.validator.OfflineValidator
import ve.transporte.core.validator.ValidatorConfig
import ve.transporte.core.wallet.OfflineWallet
import ve.transporte.core.wallet.WalletBook

class TestClock(var now: Long = 1_750_000_000L) : Clock {
    override fun nowEpochSec(): Long = now
    fun advance(seconds: Long) { now += seconds }
}

/** Un pasajero con su telefono: clave de dispositivo + monedero offline. */
class Passenger(
    val walletId: String,
    val signer: JvmSigner,
    val engine: OfflineWallet,
    val book: WalletBook,
)

/**
 * Monta el sistema completo en memoria: servidor emisor, pasajeros y unidades.
 * Es el escenario sobre el que corren todas las pruebas.
 */
class World(
    val clock: TestClock = TestClock(),
    policy: IssuancePolicy = IssuancePolicy(),
) {
    val issuer = JvmSigner.generate("emisor-2026-01")
    private var counter = 0
    val server = SettlementServer(issuer, clock, policy) { "id${++counter}" }
    val trust: TrustStore = TrustStore.of(issuer.keyId to issuer.publicKeyEncoded)

    /**
     * @param deviceClock permite simular un telefono con el reloj manipulado.
     */
    fun newPassenger(name: String, deviceClock: Clock = clock): Passenger {
        val signer = JvmSigner.generate("dev-$name")
        val account = server.enrollWallet(signer.publicKeyEncoded, walletId = "w-$name")
        val engine = OfflineWallet(signer, trust, deviceClock)
        return Passenger(account.walletId, signer, engine, WalletBook(engine))
    }

    fun newValidator(
        unitId: String,
        ownerId: String,
        routeId: String = "ruta-01",
        fareCentimos: Long = 5_00,
        ttlSeconds: Int = 45,
        acceptPresented: Boolean = true,
    ): OfflineValidator {
        val signer = JvmSigner.generate("val-$unitId")
        val validatorId = "v-$unitId"
        val cert: SignedValidatorCert =
            server.enrollValidator(validatorId, unitId, ownerId, signer.publicKeyEncoded)
        return OfflineValidator(
            config = ValidatorConfig(
                validatorId = validatorId,
                unitId = unitId,
                ownerId = ownerId,
                routeId = routeId,
                fareCentimos = fareCentimos,
                challengeTtlSeconds = ttlSeconds,
                acceptPresented = acceptPresented,
            ),
            signer = signer,
            cert = cert,
            trust = trust,
            clock = clock,
        )
    }

    /** Recarga con internet: el saldo queda firmado y bloqueado en el telefono. */
    fun topUp(passenger: Passenger, centimos: Long) {
        val grant = server.topUp(passenger.walletId, centimos)
        val outcome = passenger.book.addGrant(grant)
        check(outcome is ve.transporte.core.wallet.LoadGrantOutcome.Loaded) {
            "la recarga fue rechazada: $outcome"
        }
    }
}

/**
 * Fabrica un vale de gasto saltandose las reglas del monedero honesto.
 * Sirve para simular una app modificada / un telefono rooteado.
 */
fun forgeSpend(
    signer: JvmSigner,
    grant: ve.transporte.core.protocol.SignedGrant,
    challenge: ve.transporte.core.protocol.SignedChallenge,
    amountCentimos: Long,
    balanceAfterCentimos: Long,
    seq: Long = 1,
    prevHash: ByteArray = ve.transporte.core.crypto.Hash.ZERO_32,
    spentAtEpochSec: Long,
    deviceFingerprintOverride: String? = null,
): ve.transporte.core.protocol.SignedSpend {
    val c = challenge.challenge
    val token = ve.transporte.core.protocol.SpendToken(
        grantId = grant.grant.grantId,
        walletId = grant.grant.walletId,
        seq = seq,
        amountCentimos = amountCentimos,
        balanceAfterCentimos = balanceAfterCentimos,
        prevHash = prevHash,
        validatorId = c.validatorId,
        unitId = c.unitId,
        ownerId = c.ownerId,
        routeId = c.routeId,
        challengeNonce = c.challengeNonce,
        spentAtEpochSec = spentAtEpochSec,
        deviceKeyFingerprint = deviceFingerprintOverride
            ?: ve.transporte.core.crypto.Hash.keyFingerprint(signer.publicKeyEncoded),
    )
    return ve.transporte.core.protocol.SignedSpend(
        token = token,
        signature = signer.sign(token.canonicalBytes()),
        grant = grant,
        devicePublicKey = signer.publicKeyEncoded,
    )
}
