package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.crypto.JvmSigner
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.SignedChallenge
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.ValidatorCert
import ve.transporte.core.protocol.ValidatorChallenge
import ve.transporte.core.wallet.DenyReason
import ve.transporte.core.wallet.SpendOutcome

/**
 * El otro lado del fraude: que no le cobren al pasajero con un QR pegado en la
 * pared por cualquiera.
 */
class ValidadorFalsoTest {

    private fun retoFalso(
        w: World,
        firmante: JvmSigner,
        certificado: SignedValidatorCert,
        validatorId: String = "v-pirata",
        unitId: String = "bus-pirata",
        ownerId: String = "el-vivo",
    ): SignedChallenge {
        val c = ValidatorChallenge(
            validatorId = validatorId, unitId = unitId, ownerId = ownerId,
            routeId = "ruta-01", fareCentimos = 5_00,
            challengeNonce = "nonce-pirata", issuedAtEpochSec = w.clock.now,
            ttlSeconds = 45, validatorKeyId = firmante.keyId,
        )
        return SignedChallenge(c, firmante.sign(c.canonicalBytes()), firmante.publicKeyEncoded, certificado)
    }

    @Test
    @DisplayName("Un QR de cobro sin certificado del emisor no cobra nada")
    fun validadorSinRegistrar() {
        val w = World()
        val p = w.newPassenger("ana")
        w.topUp(p, Money.fromBolivares("50.00"))

        // El estafador se auto-firma su propio certificado.
        val pirata = JvmSigner.generate("pirata")
        val cert = ValidatorCert(
            validatorId = "v-pirata", unitId = "bus-pirata", ownerId = "el-vivo",
            publicKeyFingerprint = ve.transporte.core.crypto.Hash.keyFingerprint(pirata.publicKeyEncoded),
            issuedAtEpochSec = w.clock.now, expiresAtEpochSec = w.clock.now + 86_400,
            issuerKeyId = w.issuer.keyId,
        )
        val certFalso = SignedValidatorCert(cert, pirata.sign(cert.canonicalBytes()))

        val outcome = p.book.pay(retoFalso(w, pirata, certFalso))
        assertTrue(outcome is SpendOutcome.Denied)
        assertEquals(
            DenyReason.CERTIFICADO_DE_VALIDADOR_INVALIDO,
            (outcome as SpendOutcome.Denied).reason,
        )
        assertEquals(50_00L, p.book.totalBalanceCentimos) // no se toco el saldo
    }

    @Test
    @DisplayName("Reusar el certificado de un validador real con otra clave no cuela")
    fun certificadoPrestado() {
        val w = World()
        val p = w.newPassenger("ana")
        w.topUp(p, Money.fromBolivares("50.00"))

        // Certificado legitimo de una unidad real (es publico, va en su QR).
        val real = JvmSigner.generate("val-real")
        val certReal = w.server.enrollValidator("v-real", "bus-real", "don-enrique", real.publicKeyEncoded)

        // El estafador lo copia pero firma el reto con SU clave.
        val pirata = JvmSigner.generate("pirata")
        val outcome = p.book.pay(
            retoFalso(w, pirata, certReal, validatorId = "v-real", unitId = "bus-real", ownerId = "don-enrique"),
        )
        assertEquals(
            DenyReason.CERTIFICADO_NO_CORRESPONDE_AL_VALIDADOR,
            (outcome as SpendOutcome.Denied).reason,
        )
        assertEquals(50_00L, p.book.totalBalanceCentimos)
    }

    @Test
    @DisplayName("Alterar el pasaje de un reto real rompe su firma")
    fun retoAlterado() {
        val w = World()
        val p = w.newPassenger("ana")
        val bus = w.newValidator("bus-09", "don-enrique", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("50.00"))

        val real = bus.newChallenge()
        // El chofer intenta cobrar 500 Bs en vez de 5 conservando la firma.
        val alterado = real.copy(challenge = real.challenge.copy(fareCentimos = 500_00))
        val outcome = p.book.pay(alterado)
        assertEquals(DenyReason.FIRMA_DEL_RETO_INVALIDA, (outcome as SpendOutcome.Denied).reason)
        assertEquals(50_00L, p.book.totalBalanceCentimos)
    }

    @Test
    @DisplayName("El mismo reto no se paga dos veces desde el mismo telefono")
    fun retoReusado() {
        val w = World()
        val p = w.newPassenger("ana")
        val bus = w.newValidator("bus-10", "don-enrique")
        w.topUp(p, Money.fromBolivares("50.00"))

        val reto = bus.newChallenge()
        assertTrue(p.book.pay(reto) is SpendOutcome.Approved)
        val segundo = p.book.pay(reto)
        assertEquals(DenyReason.RETO_YA_USADO, (segundo as SpendOutcome.Denied).reason)
        assertEquals(45_00L, p.book.totalBalanceCentimos) // se descontó una sola vez
    }
}
