package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.protocol.Money
import ve.transporte.core.qr.QrEnvelope
import ve.transporte.core.qr.QrFormatException
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.validator.RejectReason
import ve.transporte.core.wallet.SpendOutcome

class QrYMonedaTest {

    @Test
    @DisplayName("Los QR sobreviven la ida y vuelta sin perder un solo byte")
    fun idaYVuelta() {
        val w = World()
        val p = w.newPassenger("ana")
        val bus = w.newValidator("bus-01", "don-enrique")
        w.topUp(p, Money.fromBolivares("50.00"))

        val reto = bus.newChallenge()
        val retoDecodificado = QrEnvelope.decodeChallenge(QrEnvelope.encode(reto))
        assertEquals(reto, retoDecodificado)

        val pago = p.book.pay(retoDecodificado) as SpendOutcome.Approved
        val pagoDecodificado = QrEnvelope.decodeSpend(pago.qr)
        assertEquals(pago.spend, pagoDecodificado)
        assertTrue(bus.accept(pagoDecodificado) is AcceptResult.Accepted)
    }

    @Test
    @DisplayName("Los QR caben comodamente en un codigo escaneable")
    fun tamanoDelQr() {
        val w = World()
        val p = w.newPassenger("ana")
        val bus = w.newValidator("bus-01", "don-enrique")
        w.topUp(p, Money.fromBolivares("50.00"))

        val reto = bus.newChallenge()
        val retoQr = QrEnvelope.encode(reto)
        val pagoQr = (p.book.pay(reto) as SpendOutcome.Approved).qr

        // Referencia: un QR version 20 con correccion M admite ~1000 bytes.
        assertTrue(retoQr.length < 900, "el QR del validador mide ${retoQr.length}")
        assertTrue(pagoQr.length < 1100, "el QR de pago mide ${pagoQr.length}")
        println("QR reto = ${retoQr.length} caracteres, QR pago = ${pagoQr.length} caracteres")
    }

    @Test
    @DisplayName("Cualquier QR ajeno o corrupto se rechaza sin reventar")
    fun basura() {
        val basuras = listOf(
            "",
            "hola",
            "https://ejemplo.com/pago",
            "PP1:SPD:no-es-base64-valido!!!",
            "PP1:CHL:",
            "PP2:SPD:AAAA",
            "PP1:XXX:AAAA",
            "PP1:SPD:${"A".repeat(400)}",
        )
        for (b in basuras) {
            assertThrows(QrFormatException::class.java, { QrEnvelope.decodeSpend(b) }, "no reventó con: $b")
        }
        assertNull(QrEnvelope.typeOf("hola"))
        assertEquals(QrEnvelope.TYPE_SPEND, QrEnvelope.typeOf("PP1:SPD:AAAA"))
    }

    @Test
    @DisplayName("Un QR de pago truncado no se acepta")
    fun qrTruncado() {
        val w = World()
        val p = w.newPassenger("ana")
        val bus = w.newValidator("bus-01", "don-enrique")
        w.topUp(p, Money.fromBolivares("50.00"))
        val pago = (p.book.pay(bus.newChallenge()) as SpendOutcome.Approved).qr

        val truncado = pago.substring(0, pago.length - 20)
        val r = bus.accept(truncado)
        assertTrue(r is AcceptResult.Rejected)
        assertEquals(RejectReason.QR_ILEGIBLE, (r as AcceptResult.Rejected).reason)
    }

    @Test
    @DisplayName("El dinero se maneja en centimos enteros, sin decimales flotantes")
    fun montos() {
        assertEquals(5_00L, Money.fromBolivares("5"))
        assertEquals(5_00L, Money.fromBolivares("5.00"))
        assertEquals(5_50L, Money.fromBolivares("5.5"))
        assertEquals(5_05L, Money.fromBolivares("5.05"))
        assertEquals(5_50L, Money.fromBolivares("5,50"))
        assertEquals(-3_25L, Money.fromBolivares("-3.25"))
        assertEquals(0L, Money.fromBolivares("0"))

        assertEquals("5.00 Bs", Money.format(5_00))
        assertEquals("0.07 Bs", Money.format(7))
        assertEquals("-3.25 Bs", Money.format(-3_25))
        assertEquals("1234.56 Bs", Money.format(123_456))

        // 0.1 + 0.2 debe dar exactamente 0.3, cosa que con Double no pasa.
        assertEquals(Money.fromBolivares("0.30"), Money.fromBolivares("0.10") + Money.fromBolivares("0.20"))

        listOf("", "abc", "5.123", "5.", ".5", "1e3").forEach {
            assertThrows(IllegalArgumentException::class.java, { Money.fromBolivares(it) }, "acepto: $it")
        }
    }
}
