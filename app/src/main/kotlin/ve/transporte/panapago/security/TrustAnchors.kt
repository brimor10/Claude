package ve.transporte.panapago.security

import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.crypto.TrustStore

/**
 * Claves publicas del emisor, empotradas en la app (pinning).
 *
 * Esto es el ancla de TODA la validacion offline: sin internet, la unica razon
 * por la que el telefono cree que un saldo es real es que viene firmado por una
 * de estas claves, que llegaron dentro del APK firmado.
 *
 * Se admite mas de una clave para poder rotar sin invalidar los vales ya
 * emitidos: se publica la nueva, se espera a que caduquen los vales viejos, y
 * recien ahi se retira la anterior.
 *
 * OJO: los valores de abajo son de DEMOSTRACION. Antes de publicar hay que
 * sustituirlos por las claves publicas reales del emisor, y la privada
 * correspondiente debe vivir en un HSM, jamas en el repositorio.
 */
object TrustAnchors {

    private val ISSUER_KEYS: Map<String, String> = mapOf(
        // keyId to clave publica X.509 en base64url
        "emisor-demo-2026-01" to "REEMPLAZAR_CON_LA_CLAVE_PUBLICA_REAL",
    )

    fun trustStore(): TrustStore = TrustStore(
        ISSUER_KEYS
            .filterValues { it != "REEMPLAZAR_CON_LA_CLAVE_PUBLICA_REAL" }
            .mapValues { (_, v) -> Base64Url.decode(v) },
    )

    val isConfigured: Boolean
        get() = ISSUER_KEYS.values.any { it != "REEMPLAZAR_CON_LA_CLAVE_PUBLICA_REAL" }
}
