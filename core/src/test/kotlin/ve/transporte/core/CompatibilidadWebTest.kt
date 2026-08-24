package ve.transporte.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.crypto.Ec
import ve.transporte.core.crypto.Hash
import ve.transporte.core.crypto.TrustStore
import ve.transporte.core.qr.QrEnvelope
import java.io.File

/**
 * Compatibilidad entre la demostracion web y el core de produccion.
 *
 * La demostracion de web/ reimplementa el protocolo en JavaScript para poder
 * enseñarlo en un navegador. Esta prueba comprueba que no se ha desviado: toma
 * un escenario firmado y codificado por la implementacion JavaScript y lo
 * verifica con ESTE codigo, el que va en la app.
 *
 * Si alguien cambia la serializacion canonica, el formato de los QR o el de las
 * firmas en un lado y no en el otro, esta prueba falla.
 *
 * El fixture se regenera con:  node tools/generar-fixtures.mjs
 */
class CompatibilidadWebTest {

    private val fixture: String by lazy { ARCHIVO.readText() }

    private fun texto(clave: String): String =
        Regex("\"$clave\"\\s*:\\s*\"([^\"]*)\"").find(fixture)?.groupValues?.get(1)
            ?: error("falta el campo \"$clave\" en el fixture")

    private fun numero(clave: String): Long =
        Regex("\"$clave\"\\s*:\\s*(-?\\d+)").find(fixture)?.groupValues?.get(1)?.toLong()
            ?: error("falta el campo numerico \"$clave\" en el fixture")

    private val trust: TrustStore by lazy {
        TrustStore.of(texto("emisorKeyId") to Base64Url.decode(texto("emisorClavePublica")))
    }

    @Test
    @DisplayName("El core acepta un vale de saldo emitido por la implementacion web")
    fun valeDeSaldo() {
        val signed = QrEnvelope.decodeGrant(texto("valeQr"))
        val g = signed.grant

        assertTrue(
            trust.verifyIssuer(g.issuerKeyId, g.canonicalBytes(), signed.signature),
            "el core no reconoce la firma del emisor que produjo JavaScript",
        )
        assertEquals(texto("walletId"), g.walletId)
        assertEquals(numero("montoDelValeCentimos"), g.amountCentimos)
        assertEquals("VES", g.currency)

        // Volver a codificarlo con el core tiene que dar exactamente el mismo
        // texto: eso prueba que los dos codificadores son byte a byte iguales.
        assertEquals(texto("valeQr"), QrEnvelope.encode(signed))
    }

    @Test
    @DisplayName("El core acepta un reto de cobro emitido por la implementacion web")
    fun retoDeCobro() {
        val signed = QrEnvelope.decodeChallenge(texto("retoQr"))
        val c = signed.challenge
        val cert = signed.cert.cert

        assertTrue(
            trust.verifyIssuer(cert.issuerKeyId, cert.canonicalBytes(), signed.cert.signature),
            "el certificado del validador no lo valida el core",
        )
        assertEquals(cert.publicKeyFingerprint, Hash.keyFingerprint(signed.validatorPublicKey))

        val validatorKey = Ec.decodePublicKey(signed.validatorPublicKey)
        assertTrue(
            Ec.verify(validatorKey, c.canonicalBytes(), signed.signature),
            "la firma del reto no la valida el core",
        )
        assertEquals(numero("pasajeCentimos"), c.fareCentimos)
        assertEquals(texto("ownerId"), c.ownerId)
        assertEquals(texto("unitId"), c.unitId)

        assertEquals(texto("retoQr"), QrEnvelope.encode(signed))
    }

    @Test
    @DisplayName("El core valida los gastos y su encadenamiento")
    fun cadenaDeGastos() {
        val primero = QrEnvelope.decodeSpend(texto("gastoQr"))
        val segundo = QrEnvelope.decodeSpend(texto("segundoGastoQr"))

        for ((n, gasto) in listOf(primero, segundo).withIndex()) {
            val t = gasto.token
            val deviceKey = Ec.decodePublicKey(gasto.devicePublicKey)
            assertTrue(
                Ec.verify(deviceKey, t.canonicalBytes(), gasto.signature),
                "la firma del gasto ${n + 1} no la valida el core",
            )
            assertEquals(
                Hash.keyFingerprint(gasto.devicePublicKey),
                gasto.grant.grant.deviceKeyFingerprint,
                "el gasto ${n + 1} no esta amarrado al dispositivo del vale",
            )
            assertTrue(
                trust.verifyIssuer(
                    gasto.grant.grant.issuerKeyId,
                    gasto.grant.grant.canonicalBytes(),
                    gasto.grant.signature,
                ),
                "el vale que respalda el gasto ${n + 1} no lo valida el core",
            )
        }

        assertEquals(1L, primero.token.seq)
        assertEquals(numero("saldoTrasElPrimerPagoCentimos"), primero.token.balanceAfterCentimos)
        assertTrue(
            primero.token.prevHash.contentEquals(Hash.ZERO_32),
            "el primer eslabon de la cadena debe apuntar a ceros",
        )
        assertEquals(texto("idDelPrimerEslabon"), primero.linkId())

        assertEquals(2L, segundo.token.seq)
        assertEquals(numero("saldoTrasElSegundoPagoCentimos"), segundo.token.balanceAfterCentimos)
        assertTrue(
            segundo.token.prevHash.contentEquals(primero.linkHash()),
            "el segundo eslabon no apunta al primero: la cadena no cuadra entre las dos implementaciones",
        )

        assertEquals(texto("gastoQr"), QrEnvelope.encode(primero))
        assertEquals(texto("segundoGastoQr"), QrEnvelope.encode(segundo))
    }

    private companion object {
        val ARCHIVO = File("../web/fixtures-compatibilidad.json")
    }
}
