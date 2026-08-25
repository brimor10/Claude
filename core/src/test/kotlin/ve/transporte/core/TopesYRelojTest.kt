package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.protocol.Money
import ve.transporte.core.server.IssuancePolicy
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.validator.RejectReason
import ve.transporte.core.wallet.DenyReason
import ve.transporte.core.wallet.SpendOutcome

/**
 * Los topes offline son lo que convierte "el fraude es detectable despues" en
 * "el fraude tiene un techo de perdida conocido".
 */
class TopesYRelojTest {

    @Test
    @DisplayName("El tope de gasto sin sincronizar frena aunque quede saldo")
    fun topeDeMonto() {
        val w = World(policy = IssuancePolicy(offlineCapCeilingCentimos = 20_00, offlineTripCap = 99))
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-01", "don-enrique", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("500.00"))

        // Solo 20 Bs son gastables sin internet, de 500 Bs recargados.
        assertEquals(500_00L, p.book.totalBalanceCentimos)
        assertEquals(20_00L, p.book.offlineSpendableCentimos)

        repeat(4) {
            assertTrue(p.book.pay(bus.newChallenge()) is SpendOutcome.Approved, "viaje $it")
            w.clock.advance(300)
        }
        val quinto = p.book.pay(bus.newChallenge())
        assertEquals(DenyReason.TOPE_OFFLINE_ALCANZADO, (quinto as SpendOutcome.Denied).reason)
        assertEquals(480_00L, p.book.totalBalanceCentimos) // el saldo sigue ahi

        // Al sincronizar se repone el cupo offline.
        val ack = w.server.ingestWalletSync(p.walletId, p.book.pendingSpends)
        p.book.applySync(ack)
        assertEquals(20_00L, p.book.offlineSpendableCentimos)
        assertTrue(p.book.pay(bus.newChallenge()) is SpendOutcome.Approved)
    }

    @Test
    @DisplayName("El tope de viajes sin sincronizar frena aunque sobre cupo de monto")
    fun topeDeViajes() {
        val w = World(policy = IssuancePolicy(offlineCapCeilingCentimos = 1000_00, offlineTripCap = 3))
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-02", "don-enrique", fareCentimos = 1_00)
        w.topUp(p, Money.fromBolivares("100.00"))

        repeat(3) {
            assertTrue(p.book.pay(bus.newChallenge()) is SpendOutcome.Approved)
            w.clock.advance(300)
        }
        val cuarto = p.book.pay(bus.newChallenge())
        assertEquals(DenyReason.TOPE_DE_VIAJES_OFFLINE_ALCANZADO, (cuarto as SpendOutcome.Denied).reason)
    }

    @Test
    @DisplayName("El cupo offline solo se repone por lo que el servidor confirmo")
    fun reposicionParcial() {
        val w = World(policy = IssuancePolicy(offlineCapCeilingCentimos = 20_00, offlineTripCap = 99))
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-03", "don-enrique", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("100.00"))

        repeat(3) {
            p.book.pay(bus.newChallenge())
            w.clock.advance(300)
        }
        assertEquals(5_00L, p.book.offlineSpendableCentimos) // 20 - 15

        // Sincronizacion a medias: el servidor solo confirma el primer gasto.
        val pendientes = p.book.pendingSpends
        val ack = ve.transporte.core.wallet.SyncAck(
            confirmedLinkIds = listOf(pendientes.first().linkId()),
            serverTimeEpochSec = w.clock.now,
        )
        p.book.applySync(ack)
        assertEquals(10_00L, p.book.offlineSpendableCentimos) // solo se libero 1 de 3
        assertEquals(2, p.book.pendingSpends.size)
    }

    @Test
    @DisplayName("El tope offline se gana con historial: una cuenta nueva casi no puede gastar sin señal")
    fun topePorHistorial() {
        // Un tope alto e igual para todos desde el primer dia invita a fabricar
        // cuentas desechables: cada una quema el tope y se tira. Con tramos, la
        // cuenta recien nacida solo alcanza para un par de pasajes.
        val w = World()
        val nuevo = w.newPassenger("recien-llegado")
        val bus = w.newValidator("bus-01", "don-enrique", fareCentimos = 235_00)
        w.topUp(nuevo, Money.fromBolivares("5000.00"))

        assertEquals(5000_00L, nuevo.book.totalBalanceCentimos)
        assertEquals(470_00L, nuevo.book.offlineSpendableCentimos, "cuenta nueva: dos pasajes")

        // Gasta los dos y el tercero se traba pidiendo conexion.
        repeat(2) {
            val pago = nuevo.book.pay(bus.newChallenge())
            assertTrue(pago is SpendOutcome.Approved)
            assertTrue(bus.accept((pago as SpendOutcome.Approved).spend) is AcceptResult.Accepted)
            w.clock.advance(600)
        }
        val tercero = nuevo.book.pay(bus.newChallenge())
        assertEquals(DenyReason.TOPE_OFFLINE_ALCANZADO, (tercero as SpendOutcome.Denied).reason)

        // Al subir los recibos, esos viajes cuentan como historial.
        w.server.ingestValidatorBatch(bus.config.validatorId, bus.pendingReceipts)
        w.server.reconcile()
        assertEquals(2, w.server.account(nuevo.walletId)!!.settledTrips)

        // Con mas historial, el tope sube en la siguiente recarga.
        val cuenta = w.server.account(nuevo.walletId)!!
        cuenta.settledTrips = 25
        assertEquals(2350_00L, w.server.offlineCapFor(cuenta), "usuario asentado: diez pasajes")
    }

    @Test
    @DisplayName("Atrasar el reloj del telefono no revive un vale vencido")
    fun relojAtrasado() {
        val relojDelTelefono = TestClock()
        val w = World()
        val p = w.newPassenger("tramposo", deviceClock = relojDelTelefono)
        val bus = w.newValidator("bus-04", "don-enrique")
        w.topUp(p, Money.fromBolivares("100.00"))

        val emision = w.clock.now
        // Pasa mas de un mes: el vale ya vencio segun el reloj real.
        w.clock.advance(31L * 24 * 3600)
        // El tramposo atrasa el reloj de SU telefono al dia de la recarga.
        relojDelTelefono.now = emision

        // El reto del validador viene firmado con la hora real, y eso empuja el
        // piso de tiempo del monedero: la trampa no sirve.
        val outcome = p.book.pay(bus.newChallenge())
        assertTrue(outcome is SpendOutcome.Denied, "debio rechazarse: $outcome")
        assertEquals(DenyReason.VALE_CADUCADO, (outcome as SpendOutcome.Denied).reason)
    }

    @Test
    @DisplayName("Adelantar la hora del gasto lo delata en el validador")
    fun relojAdelantado() {
        val w = World()
        val p = w.newPassenger("tramposo")
        val bus = w.newValidator("bus-05", "don-enrique", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("100.00"))
        val vale = p.book.states().single().signedGrant

        val gasto = forgeSpend(
            signer = p.signer, grant = vale, challenge = bus.newChallenge(),
            amountCentimos = 5_00, balanceAfterCentimos = 95_00,
            spentAtEpochSec = w.clock.now + 10_000, // "pague dentro de 3 horas"
        )
        val r = bus.accept(gasto)
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(RejectReason.FECHA_FUERA_DE_RANGO, (r as AcceptResult.Rejected).reason)
    }

    @Test
    @DisplayName("Un vale vencido no se puede cobrar en el validador")
    fun valeVencidoEnElValidador() {
        val w = World(policy = IssuancePolicy(grantValiditySeconds = 3_600))
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-06", "don-enrique")
        w.topUp(p, Money.fromBolivares("100.00"))
        val vale = p.book.states().single().signedGrant

        w.clock.advance(7_200)
        val gasto = forgeSpend(
            signer = p.signer, grant = vale, challenge = bus.newChallenge(),
            amountCentimos = 5_00, balanceAfterCentimos = 95_00,
            spentAtEpochSec = w.clock.now,
        )
        assertEquals(
            RejectReason.VALE_CADUCADO,
            (bus.accept(gasto) as AcceptResult.Rejected).reason,
        )
    }
}
