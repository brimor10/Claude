package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.protocol.Money
import ve.transporte.core.qr.QrEnvelope
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.wallet.SpendOutcome

/** El flujo que pidio el usuario, de punta a punta. */
class HappyPathTest {

    @Test
    @DisplayName("Recarga con internet, se gasta sin internet, y el dueno cobra en bolivares")
    fun flujoCompleto() {
        val w = World()
        val maria = w.newPassenger("maria")
        val bus = w.newValidator(unitId = "bus-14", ownerId = "don-enrique", fareCentimos = 5_00)

        // 1. CON INTERNET: recarga de 50 Bs. Queda bloqueada en el telefono.
        w.topUp(maria, Money.fromBolivares("50.00"))
        assertEquals(50_00L, maria.book.totalBalanceCentimos)

        // 2. SE VA EL INTERNET. El telefono y el validador quedan aislados.
        //    (Ninguna de las operaciones siguientes toca el servidor.)

        // 3. El validador muestra su QR de cobro.
        val challenge = bus.newChallenge()
        val challengeQr = QrEnvelope.encode(challenge)

        // 4. El pasajero lo escanea y su telefono firma el pago.
        val outcome = maria.book.pay(QrEnvelope.decodeChallenge(challengeQr))
        assertTrue(outcome is SpendOutcome.Approved, "el pago debio aprobarse: $outcome")
        val approved = outcome as SpendOutcome.Approved

        // El saldo ya quedo descontado en el telefono, sin internet.
        assertEquals(45_00L, maria.book.totalBalanceCentimos)

        // 5. El validador escanea el QR del pasajero y verifica offline.
        val accepted = bus.accept(approved.qr)
        assertTrue(accepted is AcceptResult.Accepted, "el validador debio aceptar: $accepted")
        assertEquals(5_00L, (accepted as AcceptResult.Accepted).fareCentimos)
        assertEquals(45_00L, accepted.passengerBalanceAfterCentimos)
        assertEquals(5_00L, bus.accruedCentimos())

        // 6. VUELVE EL INTERNET. Ambos suben lo suyo.
        w.server.ingestValidatorBatch(bus.config.validatorId, bus.pendingReceipts)
        val ack = w.server.ingestWalletSync(maria.walletId, maria.book.pendingSpends)
        maria.book.applySync(ack)

        val report = w.server.reconcile()
        assertTrue(report.doubleSpends.isEmpty(), "no debia haber doble gasto")
        assertTrue(report.chainIssues.isEmpty(), "la cadena debia cuadrar: ${report.chainIssues}")

        // El saldo del telefono coincide con el del servidor: nada aparecio ni desaparecio.
        assertEquals(45_00L, maria.book.totalBalanceCentimos)
        assertEquals(5_00L, w.server.purse(approved.spend.token.grantId)!!.settledSpentCentimos)
        assertTrue(maria.book.pendingSpends.isEmpty(), "ya no debe quedar nada pendiente")

        // 7. Al dueno de la unidad se le paga en bolivares (3 % de comision).
        val settlement = w.server.settle("don-enrique")
        assertEquals(1, settlement.receipts)
        assertEquals(5_00L, settlement.grossCentimos)
        assertEquals(15L, settlement.commissionCentimos)
        assertEquals(4_85L, settlement.netCentimos)
        assertEquals("4.85 Bs", Money.format(settlement.netCentimos))
    }

    @Test
    @DisplayName("Varios viajes offline seguidos encadenan correctamente")
    fun variosViajesOffline() {
        val w = World()
        val jose = w.newPassenger("jose")
        val bus = w.newValidator(unitId = "bus-07", ownerId = "dona-carmen", fareCentimos = 3_50)
        w.topUp(jose, Money.fromBolivares("20.00"))

        repeat(5) { i ->
            val outcome = jose.book.pay(bus.newChallenge())
            assertTrue(outcome is SpendOutcome.Approved, "viaje $i rechazado: $outcome")
            assertTrue(bus.accept((outcome as SpendOutcome.Approved).qr) is AcceptResult.Accepted)
            w.clock.advance(600)
        }

        assertEquals(2_50L, jose.book.totalBalanceCentimos) // 20.00 - 5 x 3.50
        assertEquals(17_50L, bus.accruedCentimos())

        // Los numeros de secuencia van 1..5 y cada uno apunta al anterior.
        val spends = jose.book.pendingSpends.sortedBy { it.token.seq }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), spends.map { it.token.seq })
        for (i in 1 until spends.size) {
            assertTrue(
                spends[i].token.prevHash.contentEquals(spends[i - 1].linkHash()),
                "el eslabon ${i + 1} no apunta al ${i}",
            )
        }

        w.server.ingestValidatorBatch(bus.config.validatorId, bus.pendingReceipts)
        w.server.ingestWalletSync(jose.walletId, jose.book.pendingSpends)
        assertTrue(w.server.reconcile().chainIssues.isEmpty())
        assertEquals(17_50L, w.server.settle("dona-carmen").grossCentimos)
    }
}
