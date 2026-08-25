package ve.transporte.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Serializacion canonica para firmar.
 *
 * Por que no JSON: dos serializadores JSON pueden producir bytes distintos para
 * el mismo objeto (orden de claves, espacios, escapes), y peor, se puede mover
 * contenido de un campo a otro sin cambiar los bytes ("a|b" + "c" vs "a" +
 * "b|c"). Eso permite ataques de confusion de firma.
 *
 * Aqui cada campo se escribe con prefijo de longitud (4 bytes big-endian) y en
 * un orden fijo definido por el codigo. El resultado es unico e inyectivo: dos
 * mensajes distintos jamas producen los mismos bytes.
 *
 * Ademas todo mensaje empieza por una etiqueta de dominio ([DOMAIN] + tipo), de
 * forma que una firma valida para un tipo de mensaje nunca puede reinterpretarse
 * como valida para otro.
 */
class CanonicalWriter(domainTag: String) {
    private val out = ByteArrayOutputStream(256)

    init {
        str(DOMAIN)
        str(domainTag)
    }

    fun str(value: String): CanonicalWriter = bytes(value.toByteArray(StandardCharsets.UTF_8))

    fun long(value: Long): CanonicalWriter {
        out.write(ByteBuffer.allocate(4).putInt(8).array())
        out.write(ByteBuffer.allocate(8).putLong(value).array())
        return this
    }

    fun int(value: Int): CanonicalWriter = long(value.toLong())

    fun bytes(value: ByteArray): CanonicalWriter {
        out.write(ByteBuffer.allocate(4).putInt(value.size).array())
        out.write(value)
        return this
    }

    fun build(): ByteArray = out.toByteArray()

    companion object {
        /** Separador de dominio global. Cambiarlo invalida todas las firmas del sistema. */
        const val DOMAIN = "ve.transporte.panapago/v1"

        const val TAG_GRANT = "purse-grant"
        const val TAG_CHALLENGE = "validator-challenge"
        const val TAG_SPEND = "spend-token"
        const val TAG_RECEIPT = "validator-receipt"
        const val TAG_VALIDATOR_CERT = "validator-cert"
        const val TAG_PRESENTED = "presented-spend"
    }
}
