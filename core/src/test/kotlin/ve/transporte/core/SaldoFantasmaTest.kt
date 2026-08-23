package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.crypto.JvmSigner
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.PurseGrant
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.validator.RejectReason
import ve.transporte.core.wallet.DenyReason
import ve.transporte.core.wallet.LoadGrantOutcome
import ve.transporte.core.wallet.SpendOutcome

/**
 * "Que no le vayan a agregar saldo fantasma."
 *
 * El saldo solo existe si lo firmo el servidor. Estas pruebas atacan esa
 * propiedad por todos los flancos.
 */
class SaldoFantasmaTest {

    @Test
    @DisplayName("Un vale firmado por un impostor no vale nada")
    fun valeFirmadoPorImpostor() {
        val w = World()
        val ratero = w.newPassenger("ratero")
        val bus = w.newValidator("bus-01", "don-enrique")

        // El atacante genera SU propia pareja de claves y se auto-emite 1000 Bs,
        // haciendose pasar por el emisor real (mismo issuerKeyId).
        val claveFalsa = JvmSigner.generate("emisor-2026-01")
        val grantFalso = PurseGrant(
            grantId = "g-falso",
            walletId = ratero.walletId,
            deviceKeyFingerprint = ratero.book.deviceFingerprint,
            amountCentimos = Money.fromBolivares("1000.00"),
            currency = Money.CURRENCY,
            issuedAtEpochSec = w.clock.now,
            expiresAtEpochSec = w.clock.now + 86_400,
            offlineSpendCapCentimos = Money.fromBolivares("1000.00"),
            offlineTripCap = 999,
            issuerKeyId = w.issuer.keyId,
            nonce = "x",
        )
        val firmado = SignedGrant(grantFalso, claveFalsa.sign(grantFalso.canonicalBytes()))

        // El propio monedero lo rechaza al cargarlo...
        val carga = ratero.book.addGrant(firmado)
        assertInstanceOf(LoadGrantOutcome.Rejected::class.java, carga)
        assertEquals(DenyReason.FIRMA_DEL_VALE_INVALIDA, (carga as LoadGrantOutcome.Rejected).reason)
        assertEquals(0L, ratero.book.totalBalanceCentimos)

        // ...y aunque el atacante modifique su app para saltarse esa comprobacion
        // y firme un gasto igual, el validador lo rechaza en el autobus.
        val reto = bus.newChallenge()
        val gastoFalso = forgeSpend(
            signer = ratero.signer,
            grant = firmado,
            challenge = reto,
            amountCentimos = 5_00,
            balanceAfterCentimos = Money.fromBolivares("995.00"),
            spentAtEpochSec = w.clock.now,
        )
        val resultado = bus.accept(gastoFalso)
        assertInstanceOf(AcceptResult.Rejected::class.java, resultado)
        assertEquals(RejectReason.FIRMA_DEL_VALE_INVALIDA, (resultado as AcceptResult.Rejected).reason)
        assertEquals(0L, bus.accruedCentimos())
    }

    @Test
    @DisplayName("Inflar el monto de un vale real invalida su firma")
    fun valeAlterado() {
        val w = World()
        val vivo = w.newPassenger("vivo")
        val bus = w.newValidator("bus-02", "don-enrique")

        val real = w.server.topUp(vivo.walletId, Money.fromBolivares("10.00"))
        // Se le cambia el 10 por 10.000 conservando la firma original.
        val inflado = SignedGrant(real.grant.copy(amountCentimos = Money.fromBolivares("10000.00")), real.signature)

        assertInstanceOf(LoadGrantOutcome.Rejected::class.java, vivo.book.addGrant(inflado))

        val gasto = forgeSpend(
            signer = vivo.signer,
            grant = inflado,
            challenge = bus.newChallenge(),
            amountCentimos = 5_00,
            balanceAfterCentimos = Money.fromBolivares("9995.00"),
            spentAtEpochSec = w.clock.now,
        )
        assertEquals(
            RejectReason.FIRMA_DEL_VALE_INVALIDA,
            (bus.accept(gasto) as AcceptResult.Rejected).reason,
        )
    }

    @Test
    @DisplayName("Un emisor que la app no conoce no puede crear saldo")
    fun emisorDesconocido() {
        val w = World()
        val ratero = w.newPassenger("ratero")
        val otroEmisor = JvmSigner.generate("emisor-pirata")
        val grant = PurseGrant(
            grantId = "g-pirata", walletId = ratero.walletId,
            deviceKeyFingerprint = ratero.book.deviceFingerprint,
            amountCentimos = 500_00, currency = Money.CURRENCY,
            issuedAtEpochSec = w.clock.now, expiresAtEpochSec = w.clock.now + 86_400,
            offlineSpendCapCentimos = 500_00, offlineTripCap = 99,
            issuerKeyId = otroEmisor.keyId, nonce = "y",
        )
        val firmado = SignedGrant(grant, otroEmisor.sign(grant.canonicalBytes()))
        val carga = ratero.book.addGrant(firmado)
        assertEquals(DenyReason.EMISOR_DESCONOCIDO, (carga as LoadGrantOutcome.Rejected).reason)

        val bus = w.newValidator("bus-03", "don-enrique")
        assertEquals(
            RejectReason.EMISOR_DESCONOCIDO,
            (bus.accept(
                forgeSpend(
                    ratero.signer, firmado, bus.newChallenge(),
                    amountCentimos = 5_00, balanceAfterCentimos = 495_00,
                    spentAtEpochSec = w.clock.now,
                ),
            ) as AcceptResult.Rejected).reason,
        )
    }

    @Test
    @DisplayName("Copiar el saldo de otro telefono no sirve: esta amarrado a la clave del dispositivo")
    fun valeDeOtroTelefono() {
        val w = World()
        val duenoReal = w.newPassenger("ana")
        val copion = w.newPassenger("copion")
        val bus = w.newValidator("bus-04", "don-enrique")

        val valeDeAna = w.server.topUp(duenoReal.walletId, Money.fromBolivares("100.00"))

        // El atacante copia el archivo del vale a su telefono.
        val carga = copion.book.addGrant(valeDeAna)
        assertEquals(DenyReason.VALE_NO_ES_DE_ESTE_TELEFONO, (carga as LoadGrantOutcome.Rejected).reason)

        // Y si fuerza la firma con SU clave, el validador nota que la clave no
        // corresponde a la huella que el servidor grabo en el vale.
        val gasto = forgeSpend(
            signer = copion.signer, grant = valeDeAna, challenge = bus.newChallenge(),
            amountCentimos = 5_00, balanceAfterCentimos = 95_00,
            spentAtEpochSec = w.clock.now,
        )
        assertEquals(
            RejectReason.CLAVE_NO_CORRESPONDE_AL_VALE,
            (bus.accept(gasto) as AcceptResult.Rejected).reason,
        )
    }

    @Test
    @DisplayName("No se puede gastar mas de lo que dice el vale")
    fun aritmeticaImposible() {
        val w = World()
        val vivo = w.newPassenger("vivo")
        val bus = w.newValidator("bus-05", "don-enrique", fareCentimos = 5_00)
        w.topUp(vivo, Money.fromBolivares("3.00")) // menos que el pasaje

        // Por la via honesta: saldo insuficiente.
        val outcome = vivo.book.pay(bus.newChallenge())
        assertEquals(DenyReason.SALDO_INSUFICIENTE, (outcome as SpendOutcome.Denied).reason)

        // Por la via tramposa: declarar un saldo restante imposible.
        val vale = vivo.book.states().single().signedGrant
        val gasto = forgeSpend(
            signer = vivo.signer, grant = vale, challenge = bus.newChallenge(),
            amountCentimos = 5_00,
            balanceAfterCentimos = 1_000_00, // "me quedan 1000 Bs" sobre un vale de 3
            spentAtEpochSec = w.clock.now,
        )
        assertEquals(
            RejectReason.ARITMETICA_INVALIDA,
            (bus.accept(gasto) as AcceptResult.Rejected).reason,
        )
        assertEquals(0L, bus.accruedCentimos())
    }

    @Test
    @DisplayName("Pagar menos de lo que cuesta el pasaje se rechaza")
    fun pagarDeMenos() {
        val w = World()
        val vivo = w.newPassenger("vivo")
        val bus = w.newValidator("bus-06", "don-enrique", fareCentimos = 5_00)
        w.topUp(vivo, Money.fromBolivares("50.00"))
        val vale = vivo.book.states().single().signedGrant

        val gasto = forgeSpend(
            signer = vivo.signer, grant = vale, challenge = bus.newChallenge(),
            amountCentimos = 1, balanceAfterCentimos = 49_99,
            spentAtEpochSec = w.clock.now,
        )
        val r = bus.accept(gasto)
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(RejectReason.MONTO_NO_COINCIDE_CON_EL_PASAJE, (r as AcceptResult.Rejected).reason)
    }
}
