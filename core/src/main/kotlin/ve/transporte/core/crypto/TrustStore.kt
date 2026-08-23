package ve.transporte.core.crypto

import java.security.PublicKey

/**
 * Anclas de confianza: las claves publicas del emisor (el servidor central).
 *
 * Se empaquetan dentro de la app (pinning). Todo lo que la app cree "sin
 * internet" se reduce a: "esto viene firmado por una clave que ya traia yo de
 * fabrica". Sin esto no hay validacion offline posible.
 *
 * Se soportan varias claves a la vez para permitir rotacion sin dejar
 * inservibles los vales ya emitidos.
 */
class TrustStore(issuerKeys: Map<String, ByteArray>) {
    private val keys: Map<String, PublicKey> = issuerKeys.mapValues { (_, encoded) ->
        Ec.decodePublicKey(encoded)
    }

    fun issuerKey(keyId: String): PublicKey? = keys[keyId]

    fun knowsIssuer(keyId: String): Boolean = keys.containsKey(keyId)

    /** Verifica una firma del emisor identificada por [issuerKeyId]. */
    fun verifyIssuer(issuerKeyId: String, message: ByteArray, signature: ByteArray): Boolean {
        val key = keys[issuerKeyId] ?: return false
        return Ec.verify(key, message, signature)
    }

    companion object {
        fun of(vararg entries: Pair<String, ByteArray>): TrustStore = TrustStore(entries.toMap())
    }
}

/** Reloj inyectable: hace que las reglas de caducidad sean testeables. */
fun interface Clock {
    fun nowEpochSec(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() / 1000 }
    }
}
