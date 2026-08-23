package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.protocol.Money
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.validator.RejectReason
import ve.transporte.core.wallet.SpendOutcome

/**
 * Doble gasto: el ataque que NO se puede impedir del todo sin internet, pero que
 * si se puede detectar con prueba irrefutable, atribuir y acotar.
 */
class DobleGastoTest {

    @Test
    @DisplayName("Repetir el mismo QR en el mismo validador no cobra dos veces")
    fun mismoQrDosVeces() {
        val w = World()
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-11", "don-enrique")
        w.topUp(p, Money.fromBolivares("50.00"))

        val pago = p.book.pay(bus.newChallenge()) as SpendOutcome.Approved
        assertTrue(bus.accept(pago.qr) is AcceptResult.Accepted)

        // Segundo escaneo del MISMO QR: idempotente, no suma plata.
        val repetido = bus.accept(pago.qr)
        assertTrue(repetido is AcceptResult.AlreadyAccepted)
        assertEquals(5_00L, bus.accruedCentimos())
    }

    @Test
    @DisplayName("Un pantallazo de un pago viejo no sirve en otro cobro")
    fun pantallazoViejo() {
        val w = World()
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-12", "don-enrique")
        w.topUp(p, Money.fromBolivares("50.00"))

        val pago = p.book.pay(bus.newChallenge()) as SpendOutcome.Approved
        bus.accept(pago.qr)

        // Al dia siguiente el pasajero muestra la captura de pantalla guardada.
        w.clock.advance(86_400)
        bus.newChallenge() // el validador abre un cobro nuevo
        val r = bus.accept(pago.qr)
        // Se reconoce como ya cobrado: el chofer ve "ya pagado", no "pago aceptado".
        assertTrue(r is AcceptResult.AlreadyAccepted, "se esperaba ya-cobrado, fue: $r")
        assertEquals(5_00L, bus.accruedCentimos(), "no debe cobrar dos veces")

        // Y sigue sin colar despues de liquidar y purgar los recibos.
        bus.clearSettled(listOf(pago.spend.linkId()))
        val r2 = bus.accept(pago.qr)
        assertTrue(r2 is AcceptResult.AlreadyAccepted, "se esperaba ya-cobrado, fue: $r2")
        assertEquals(0L, bus.accruedCentimos())

        // Un QR de un vale que este validador nunca vio, respondiendo a un reto
        // muerto, si se rechaza de plano.
        val otro = w.newPassenger("otro")
        w.topUp(otro, Money.fromBolivares("50.00"))
        val retoViejo = bus.newChallenge()
        val pagoTardio = otro.book.pay(retoViejo) as SpendOutcome.Approved
        w.clock.advance(3_600)
        bus.newChallenge()
        val r3 = bus.accept(pagoTardio.qr)
        assertTrue(r3 is AcceptResult.Rejected)
        assertEquals(RejectReason.RETO_DESCONOCIDO, (r3 as AcceptResult.Rejected).reason)
    }

    @Test
    @DisplayName("El QR de pago caduca en segundos")
    fun retoCaducado() {
        val w = World()
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-13", "don-enrique", ttlSeconds = 45)
        w.topUp(p, Money.fromBolivares("50.00"))

        val reto = bus.newChallenge()
        w.clock.advance(60) // el pasajero se tardo mas de la cuenta
        val outcome = p.book.pay(reto)
        assertTrue(outcome is SpendOutcome.Denied)
        assertEquals(
            ve.transporte.core.wallet.DenyReason.RETO_CADUCADO,
            (outcome as SpendOutcome.Denied).reason,
        )
    }

    @Test
    @DisplayName("El QR de un autobus no sirve para pagar en otro")
    fun retoDeOtraUnidad() {
        val w = World()
        val p = w.newPassenger("pedro")
        val busA = w.newValidator("bus-A", "don-enrique")
        val busB = w.newValidator("bus-B", "dona-carmen")
        w.topUp(p, Money.fromBolivares("50.00"))

        val pagoParaA = p.book.pay(busA.newChallenge()) as SpendOutcome.Approved
        busB.newChallenge()
        val r = busB.accept(pagoParaA.qr)
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(RejectReason.NO_ES_PARA_ESTA_UNIDAD, (r as AcceptResult.Rejected).reason)
    }

    @Test
    @DisplayName("Restaurar un respaldo para gastar dos veces deja prueba firmada y bloquea el monedero")
    fun restaurarRespaldo() {
        val w = World()
        val tramposo = w.newPassenger("tramposo")
        val busA = w.newValidator("bus-A", "don-enrique")
        val busB = w.newValidator("bus-B", "dona-carmen")
        w.topUp(tramposo, Money.fromBolivares("20.00"))

        // Estado ANTES de gastar: esto es lo que el atacante respalda.
        val respaldo = tramposo.book.states().single()

        // Gasta en el autobus A.
        val pagoA = tramposo.engine.pay(respaldo, busA.newChallenge()) as SpendOutcome.Approved
        assertTrue(busA.accept(pagoA.spend) is AcceptResult.Accepted)

        // Restaura el respaldo y vuelve a gastar el MISMO movimiento en el autobus B.
        val pagoB = tramposo.engine.pay(respaldo, busB.newChallenge()) as SpendOutcome.Approved
        assertTrue(busB.accept(pagoB.spend) is AcceptResult.Accepted)

        // Offline nadie lo detecto: los dos choferes cobraron de buena fe.
        assertEquals(1L, pagoA.spend.token.seq)
        assertEquals(1L, pagoB.spend.token.seq)
        assertNotEquals(pagoA.spend.linkId(), pagoB.spend.linkId())

        // Al reconectar los validadores, el servidor ve la bifurcacion.
        w.server.ingestValidatorBatch(busA.config.validatorId, busA.pendingReceipts)
        w.server.ingestValidatorBatch(busB.config.validatorId, busB.pendingReceipts)
        val reporte = w.server.reconcile()

        assertEquals(1, reporte.doubleSpends.size)
        val prueba = reporte.doubleSpends.single()
        assertEquals(tramposo.walletId, prueba.walletId)
        assertEquals(1L, prueba.seq)
        assertTrue(reporte.newlyBlockedWallets.contains(tramposo.walletId))
        assertTrue(w.server.account(tramposo.walletId)!!.blocked)
        assertTrue(w.server.hotlist().contains(tramposo.walletId))

        // Las dos ramas van firmadas por el propio tramposo: es innegable.
        assertTrue(w.server.fraudDossier().contains(tramposo.walletId))

        // A los DOS duenos se les paga igual: prestaron el servicio de buena fe.
        assertEquals(5_00L, w.server.settle("don-enrique").grossCentimos)
        assertEquals(5_00L, w.server.settle("dona-carmen").grossCentimos)

        // La perdida sale del saldo retenido del tramposo, no del chofer.
        assertEquals(0L, reporte.uncoveredCentimos)
        assertEquals(0L, w.server.account(tramposo.walletId)!!.debtCentimos)
    }

    @Test
    @DisplayName("Un monedero bloqueado deja de servir en cuanto el validador recibe la lista negra")
    fun listaNegra() {
        val w = World()
        val tramposo = w.newPassenger("tramposo")
        val bus = w.newValidator("bus-Z", "don-enrique")
        w.topUp(tramposo, Money.fromBolivares("50.00"))

        // El servidor ya lo tiene bloqueado y el validador se sincroniza.
        val respaldo = tramposo.book.states().single()
        val busA = w.newValidator("bus-A", "don-enrique")
        val busB = w.newValidator("bus-B", "don-enrique")
        busA.accept((tramposo.engine.pay(respaldo, busA.newChallenge()) as SpendOutcome.Approved).spend)
        busB.accept((tramposo.engine.pay(respaldo, busB.newChallenge()) as SpendOutcome.Approved).spend)
        w.server.ingestValidatorBatch("a", busA.pendingReceipts)
        w.server.ingestValidatorBatch("b", busB.pendingReceipts)
        w.server.reconcile()

        bus.updateHotlist(w.server.hotlist())

        val pago = tramposo.book.pay(bus.newChallenge()) as SpendOutcome.Approved
        val r = bus.accept(pago.qr)
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(RejectReason.MONEDERO_BLOQUEADO, (r as AcceptResult.Rejected).reason)
        assertFalse(w.server.topUpAllowed(tramposo.walletId))
    }

    @Test
    @DisplayName("El pasajero honesto que sube su propia cadena no genera falsos positivos")
    fun honestoNoEsFalsoPositivo() {
        val w = World()
        val p = w.newPassenger("honesto")
        val bus = w.newValidator("bus-H", "don-enrique")
        w.topUp(p, Money.fromBolivares("30.00"))

        repeat(3) {
            val pago = p.book.pay(bus.newChallenge()) as SpendOutcome.Approved
            bus.accept(pago.spend)
            w.clock.advance(300)
        }

        // Los mismos gastos llegan por DOS vias: el validador y el telefono.
        w.server.ingestValidatorBatch(bus.config.validatorId, bus.pendingReceipts)
        w.server.ingestWalletSync(p.walletId, p.book.pendingSpends)

        val reporte = w.server.reconcile()
        assertTrue(reporte.doubleSpends.isEmpty(), "no debia detectar fraude: ${reporte.doubleSpends}")
        assertTrue(reporte.chainIssues.isEmpty(), "la cadena debia cuadrar: ${reporte.chainIssues}")
        assertFalse(w.server.account(p.walletId)!!.blocked)
    }
}
