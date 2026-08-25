package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.SpendMode
import ve.transporte.core.qr.QrEnvelope
import ve.transporte.core.server.IssuancePolicy
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.validator.RejectReason
import ve.transporte.core.wallet.DenyReason
import ve.transporte.core.wallet.PresentOutcome

/**
 * Modo de cobro directo: un solo escaneo. El pasajero enseña y la unidad lee.
 *
 * Estas pruebas fijan tanto lo que se gana (velocidad) como lo que se pierde
 * frente al modo de dos escaneos, para que nadie lo confunda mas adelante.
 */
class CobroDirectoTest {

    @Test
    @DisplayName("Un solo escaneo: el pasajero enseña y la unidad cobra")
    fun flujoDeUnEscaneo() {
        val w = World()
        val maria = w.newPassenger("maria")
        val bus = w.newValidator("bus-14", "don-enrique", fareCentimos = 235_00)
        w.topUp(maria, Money.fromBolivares("5000.00"))

        // El telefono genera el QR sin haber visto nada de la unidad.
        val presentado = maria.book.present(235_00)
        assertTrue(presentado is PresentOutcome.Approved, "debio generarse: $presentado")
        val qr = (presentado as PresentOutcome.Approved).qr

        // El saldo ya se descuenta aqui, antes de saber si alguien lo leera.
        assertEquals(4765_00L, maria.book.totalBalanceCentimos)

        val r = bus.accept(qr)
        assertTrue(r is AcceptResult.Accepted, "el validador debio aceptarlo: $r")
        assertEquals(SpendMode.PRESENTADO, (r as AcceptResult.Accepted).mode)
        assertEquals(235_00L, r.fareCentimos)
        assertEquals(235_00L, bus.accruedCentimos())

        // El codigo de viaje sigue cuadrando entre los dos aparatos.
        assertEquals(presentado.spend.tripCode(), r.tripCode)

        w.server.ingestValidatorBatch(bus.config.validatorId, bus.pendingReceipts)
        w.server.reconcile()
        val liq = w.server.settle("don-enrique")
        assertEquals(1, liq.receipts)
        assertEquals(235_00L, liq.grossCentimos)
    }

    @Test
    @DisplayName("Los dos modos comparten la misma cadena")
    fun mismaCadena() {
        val w = World()
        val p = w.newPassenger("mixto")
        val bus = w.newValidator("bus-01", "don-enrique", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("100.00"))

        val directo = p.book.present(5_00) as PresentOutcome.Approved
        bus.accept(directo.qr)
        w.clock.advance(300)

        val conReto = p.book.pay(bus.newChallenge()) as ve.transporte.core.wallet.SpendOutcome.Approved
        bus.accept(conReto.spend)

        assertEquals(1L, directo.spend.seq)
        assertEquals(2L, conReto.spend.token.seq)
        assertTrue(
            conReto.spend.token.prevHash.contentEquals(directo.spend.linkHash()),
            "el pago con reto debe encadenar con el cobro directo anterior",
        )

        w.server.ingestValidatorBatch(bus.config.validatorId, bus.pendingReceipts)
        val informe = w.server.reconcile()
        assertTrue(informe.chainIssues.isEmpty(), "la cadena mixta debe cuadrar: ${informe.chainIssues}")
    }

    @Test
    @DisplayName("El QR del cobro directo vence: pasada la ventana no sirve")
    fun ventanaDeValidez() {
        val w = World()
        val p = w.newPassenger("pedro")
        val bus = w.newValidator("bus-02", "don-enrique", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("100.00"))

        val directo = p.book.present(5_00, windowSeconds = 90) as PresentOutcome.Approved
        w.clock.advance(90 + 120 + 1) // ventana + tolerancia de reloj
        val r = bus.accept(directo.qr)
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(RejectReason.PAGO_VENCIDO, (r as AcceptResult.Rejected).reason)
    }

    @Test
    @DisplayName("Lo que SE PIERDE: dentro de la ventana el mismo QR cuela en dos unidades")
    fun elCostoDelModoDirecto() {
        val w = World()
        val p = w.newPassenger("vivo")
        val busA = w.newValidator("bus-A", "don-enrique", fareCentimos = 5_00)
        val busB = w.newValidator("bus-B", "dona-carmen", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("100.00"))

        val directo = p.book.present(5_00) as PresentOutcome.Approved

        // El pasajero lo enseña en un autobus y le pasa la captura a un amigo
        // que va en otro, dentro de la misma ventana. Las dos veces cuela.
        assertTrue(busA.accept(directo.qr) is AcceptResult.Accepted)
        assertTrue(busB.accept(directo.qr) is AcceptResult.Accepted, "esta es la debilidad del modo directo")

        // Pero se detecta al reconciliar: dos recibos firmados por el mismo pago.
        w.server.ingestValidatorBatch("a", busA.pendingReceipts)
        w.server.ingestValidatorBatch("b", busB.pendingReceipts)
        val informe = w.server.reconcile()

        assertEquals(1, informe.duplicateClaims.size, "debio detectarse el reclamo duplicado")
        val dup = informe.duplicateClaims.single()
        assertEquals(p.walletId, dup.walletId)
        assertNotEquals(dup.primero.unitId, dup.segundo.unitId)

        // Se le paga al primero que lo reclamo; el segundo no cobra.
        assertEquals(5_00L, w.server.settle("don-enrique").grossCentimos)
        assertEquals(0L, w.server.settle("dona-carmen").grossCentimos)

        // Y el pasajero solo gasto un pasaje: no se le cobro dos veces.
        assertEquals(95_00L, p.book.totalBalanceCentimos)
    }

    @Test
    @DisplayName("Con el modo de dos escaneos ese mismo ataque no funciona")
    fun elModoConRetoNoTieneEsaDebilidad() {
        val w = World()
        val p = w.newPassenger("vivo")
        val busA = w.newValidator("bus-A", "don-enrique", fareCentimos = 5_00)
        val busB = w.newValidator("bus-B", "dona-carmen", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("100.00"))

        val pago = p.book.pay(busA.newChallenge()) as ve.transporte.core.wallet.SpendOutcome.Approved
        assertTrue(busA.accept(pago.qr) is AcceptResult.Accepted)

        busB.newChallenge()
        val r = busB.accept(pago.qr)
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(RejectReason.NO_ES_PARA_ESTA_UNIDAD, (r as AcceptResult.Rejected).reason)
    }

    @Test
    @DisplayName("Un cobro directo que nadie leyo se le devuelve al pasajero")
    fun devolucionDeLoNoCobrado() {
        val w = World(policy = IssuancePolicy(refundGraceSeconds = 3_600))
        val p = w.newPassenger("ana")
        w.topUp(p, Money.fromBolivares("100.00"))

        // Enseña el QR pero el lector no llega a escanearlo.
        val directo = p.book.present(5_00) as PresentOutcome.Approved
        assertEquals(95_00L, p.book.totalBalanceCentimos)

        // Al reconectar sube su gasto; ningun validador lo reclamo.
        w.clock.advance(90 + 3_600 + 1)
        val ack = w.server.ingestWalletSync(p.walletId, listOf(directo.spend))
        val informe = w.server.reconcile()

        assertEquals(listOf(directo.spend.linkId()), informe.refundedLinkIds)
        assertEquals(5_00L, informe.refundedCentimos)

        // Y en la siguiente sincronizacion el saldo vuelve.
        val ack2 = w.server.ingestWalletSync(p.walletId, listOf(directo.spend))
        p.book.applySync(ack2)
        assertEquals(100_00L, p.book.totalBalanceCentimos, "el dinero tiene que volver")
        assertTrue(ack.confirmedLinkIds.isNotEmpty())
    }

    @Test
    @DisplayName("Una unidad puede rechazar el modo directo si prefiere el seguro")
    fun modoDesactivable() {
        val w = World()
        val p = w.newPassenger("ana")
        val bus = w.newValidator("bus-estricto", "don-enrique", fareCentimos = 5_00, acceptPresented = false)
        w.topUp(p, Money.fromBolivares("100.00"))

        val directo = p.book.present(5_00) as PresentOutcome.Approved
        val r = bus.accept(directo.qr)
        assertEquals(RejectReason.MODO_NO_ACEPTADO, (r as AcceptResult.Rejected).reason)
    }

    @Test
    @DisplayName("El cobro directo no permite inventar saldo ni usar el de otro")
    fun lasDefensasQueNoSePierden() {
        val w = World()
        val ana = w.newPassenger("ana")
        val copion = w.newPassenger("copion")
        val bus = w.newValidator("bus-03", "don-enrique", fareCentimos = 5_00)
        w.topUp(ana, Money.fromBolivares("100.00"))

        val directo = ana.book.present(5_00) as PresentOutcome.Approved

        // Cambiarle el monto rompe la firma.
        val alterado = directo.spend.copy(
            token = directo.spend.token.copy(amountCentimos = 1),
        )
        assertEquals(
            RejectReason.FIRMA_DE_GASTO_INVALIDA,
            (bus.acceptPresented(alterado) as AcceptResult.Rejected).reason,
        )

        // Y el monedero del copion no puede generar uno sobre el vale de ana.
        assertTrue(copion.book.present(5_00) is PresentOutcome.Denied)
        assertFalse(QrEnvelope.decodePresented(directo.qr).devicePublicKey.contentEquals(copion.signer.publicKeyEncoded))
    }

    @Test
    @DisplayName("Tras subir la tarifa, quien no ha tenido señal todavia puede pagar")
    fun ventanaDeTransicionDeTarifa() {
        val w = World()
        val p = w.newPassenger("sin-datos")
        w.topUp(p, Money.fromBolivares("5000.00"))

        // El pasajero genera su QR con el pasaje que tenia guardado.
        val directo = p.book.present(235_00) as PresentOutcome.Approved

        // Mientras tanto la tarifa subio. Sin ventana de transicion se quedaria
        // en tierra, que es justo lo que no puede pasar en un sistema pensado
        // para gente sin datos.
        val sinVentana = w.newValidator("bus-nuevo", "don-enrique", fareCentimos = 260_00)
        assertEquals(
            RejectReason.MONTO_NO_COINCIDE_CON_EL_PASAJE,
            (sinVentana.accept(directo.qr) as AcceptResult.Rejected).reason,
        )

        val conVentana = w.newValidator(
            "bus-transicion", "don-enrique",
            fareCentimos = 260_00,
            previousFares = listOf(235_00),
        )
        val r = conVentana.accept(directo.qr)
        assertTrue(r is AcceptResult.Accepted, "con ventana de transicion debio pasar: $r")
        // Se cobra lo que el pasajero firmo, no la tarifa nueva: la diferencia
        // la absorbe el operador durante la transicion.
        assertEquals(235_00L, (r as AcceptResult.Accepted).fareCentimos)
    }

    @Test
    @DisplayName("El monto tiene que ser el pasaje de la unidad")
    fun montoDistintoAlPasaje() {
        val w = World()
        val p = w.newPassenger("vivo")
        val bus = w.newValidator("bus-04", "don-enrique", fareCentimos = 235_00)
        w.topUp(p, Money.fromBolivares("500.00"))

        val directo = p.book.present(1_00) as PresentOutcome.Approved
        val r = bus.accept(directo.qr)
        assertEquals(
            RejectReason.MONTO_NO_COINCIDE_CON_EL_PASAJE,
            (r as AcceptResult.Rejected).reason,
        )
    }
}
