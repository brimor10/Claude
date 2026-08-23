package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.protocol.Money
import ve.transporte.core.validator.AcceptResult
import ve.transporte.core.wallet.SpendOutcome
import ve.transporte.core.wallet.WalletStorageCodec
import ve.transporte.core.wallet.WalletStorageException

class AlmacenamientoTest {

    @Test
    @DisplayName("El monedero se guarda y se recupera identico, y sigue pagando")
    fun idaYVuelta() {
        val w = World()
        val p = w.newPassenger("ana")
        val bus = w.newValidator("bus-01", "don-enrique", fareCentimos = 5_00)
        w.topUp(p, Money.fromBolivares("50.00"))
        w.topUp(p, Money.fromBolivares("30.00")) // dos vales a la vez

        repeat(2) {
            p.book.pay(bus.newChallenge())
            w.clock.advance(300)
        }

        val guardado = WalletStorageCodec.encode(p.book.states())
        val recuperado = WalletStorageCodec.decode(guardado)

        assertEquals(p.book.states().size, recuperado.size)
        p.book.states().zip(recuperado).forEach { (a, b) ->
            assertEquals(a.signedGrant, b.signedGrant)
            assertEquals(a.nextSeq, b.nextSeq)
            assertEquals(a.spentCentimos, b.spentCentimos)
            assertEquals(a.offlineSpentCentimos, b.offlineSpentCentimos)
            assertEquals(a.offlineTrips, b.offlineTrips)
            assertEquals(a.trustedTimeFloorEpochSec, b.trustedTimeFloorEpochSec)
            assertEquals(a.usedChallengeNonces, b.usedChallengeNonces)
            assertEquals(a.pending, b.pending)
            assertTrue(a.lastLinkHash.contentEquals(b.lastLinkHash))
        }

        // Y el estado recuperado sigue encadenando correctamente.
        val engine = p.engine
        val estado = recuperado.first()
        val pago = engine.pay(estado, bus.newChallenge())
        assertTrue(pago is SpendOutcome.Approved)
        assertTrue(bus.accept((pago as SpendOutcome.Approved).spend) is AcceptResult.Accepted)
        assertTrue(pago.spend.token.prevHash.contentEquals(estado.lastLinkHash))
    }

    @Test
    @DisplayName("Un archivo de monedero corrupto no se carga a medias")
    fun archivoCorrupto() {
        val w = World()
        val p = w.newPassenger("ana")
        w.topUp(p, Money.fromBolivares("50.00"))
        val guardado = WalletStorageCodec.encode(p.book.states())

        assertThrows(WalletStorageException::class.java) {
            WalletStorageCodec.decode(guardado.copyOfRange(0, guardado.size - 10))
        }
        assertThrows(WalletStorageException::class.java) {
            WalletStorageCodec.decode(guardado + byteArrayOf(1, 2, 3))
        }
        assertThrows(WalletStorageException::class.java) {
            WalletStorageCodec.decode(ByteArray(0))
        }
    }
}
