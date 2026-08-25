package ve.transporte.core.protocol

import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.crypto.Hash

/**
 * Todo el dinero se maneja en CENTIMOS de bolivar como entero de 64 bits.
 * Nunca en punto flotante: 0.1 + 0.2 != 0.3 y en un sistema de pagos eso es
 * dinero que aparece o desaparece.
 */
object Money {
    const val CURRENCY = "VES"

    fun fromBolivares(bs: String): Long {
        val clean = bs.trim().replace(",", ".")
        require(Regex("""^-?\d+(\.\d{1,2})?$""").matches(clean)) { "Monto invalido: $bs" }
        val negative = clean.startsWith("-")
        val body = clean.removePrefix("-")
        val parts = body.split(".")
        val whole = parts[0].toLong()
        val cents = when (parts.size) {
            1 -> 0L
            else -> parts[1].padEnd(2, '0').toLong()
        }
        val total = whole * 100 + cents
        return if (negative) -total else total
    }

    fun format(centimos: Long): String {
        val sign = if (centimos < 0) "-" else ""
        val abs = kotlin.math.abs(centimos)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')} Bs"
    }
}

// ---------------------------------------------------------------------------
// 1. Recarga (ONLINE): el servidor emite un "vale de monedero" firmado.
// ---------------------------------------------------------------------------

/**
 * Saldo bloqueado. Es el objeto que representa "el saldo esta ahi, congelado, y
 * solo se puede gastar con QR".
 *
 * Se emite UNICAMENTE con conexion, tras cobrar la recarga. El servidor guarda
 * el dinero en garantia (escrow); el monedero del telefono solo tiene un espejo
 * local de ese saldo. Como esta firmado por el emisor, el telefono no puede
 * inventar saldo fantasma: fabricar un [PurseGrant] valido requiere la clave
 * privada del emisor.
 *
 * @param deviceKeyFingerprint amarra el vale a UNA clave de dispositivo concreta.
 *   Copiar el vale a otro telefono no sirve: ese telefono no puede producir
 *   firmas de gasto que el validador acepte.
 * @param offlineSpendCapCentimos tope de gasto sin sincronizar. Acota la perdida
 *   maxima posible de un fraude offline.
 */
data class PurseGrant(
    val grantId: String,
    val walletId: String,
    val deviceKeyFingerprint: String,
    val amountCentimos: Long,
    val currency: String,
    val issuedAtEpochSec: Long,
    val expiresAtEpochSec: Long,
    val offlineSpendCapCentimos: Long,
    val offlineTripCap: Int,
    val issuerKeyId: String,
    val nonce: String,
) {
    fun canonicalBytes(): ByteArray = CanonicalWriter(CanonicalWriter.TAG_GRANT)
        .str(grantId)
        .str(walletId)
        .str(deviceKeyFingerprint)
        .long(amountCentimos)
        .str(currency)
        .long(issuedAtEpochSec)
        .long(expiresAtEpochSec)
        .long(offlineSpendCapCentimos)
        .int(offlineTripCap)
        .str(issuerKeyId)
        .str(nonce)
        .build()
}

data class SignedGrant(val grant: PurseGrant, val signature: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SignedGrant && grant == other.grant && signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * grant.hashCode() + signature.contentHashCode()
}

// ---------------------------------------------------------------------------
// 2. Cobro (OFFLINE): el validador de la unidad emite un reto.
// ---------------------------------------------------------------------------

/**
 * Reto del validador (el aparato del chofer / la unidad).
 *
 * Es la pieza anti-repeticion: el pasajero no puede tener un QR guardado y
 * reusarlo, porque su QR de pago debe incluir un [challengeNonce] que este
 * validador acaba de generar y que caduca en [ttlSeconds].
 *
 * Lleva tambien a quien hay que pagarle: [ownerId] es el dueno de la unidad.
 */
data class ValidatorChallenge(
    val validatorId: String,
    val unitId: String,
    val ownerId: String,
    val routeId: String,
    val fareCentimos: Long,
    val challengeNonce: String,
    val issuedAtEpochSec: Long,
    val ttlSeconds: Int,
    val validatorKeyId: String,
) {
    fun canonicalBytes(): ByteArray = CanonicalWriter(CanonicalWriter.TAG_CHALLENGE)
        .str(validatorId)
        .str(unitId)
        .str(ownerId)
        .str(routeId)
        .long(fareCentimos)
        .str(challengeNonce)
        .long(issuedAtEpochSec)
        .int(ttlSeconds)
        .str(validatorKeyId)
        .build()

    fun expiresAtEpochSec(): Long = issuedAtEpochSec + ttlSeconds
}

data class SignedChallenge(
    val challenge: ValidatorChallenge,
    val signature: ByteArray,
    /** Clave publica del validador, para que el monedero pueda verificar sin red. */
    val validatorPublicKey: ByteArray,
    /** Certificado del emisor que acredita a este validador. Ver [ValidatorCert]. */
    val cert: SignedValidatorCert,
) {
    override fun equals(other: Any?): Boolean = other is SignedChallenge &&
        challenge == other.challenge &&
        signature.contentEquals(other.signature) &&
        validatorPublicKey.contentEquals(other.validatorPublicKey) &&
        cert == other.cert

    override fun hashCode(): Int =
        ((31 * challenge.hashCode() + signature.contentHashCode()) * 31 +
            validatorPublicKey.contentHashCode()) * 31 + cert.hashCode()
}

// ---------------------------------------------------------------------------
// 3. Gasto (OFFLINE): el monedero firma un pago encadenado.
// ---------------------------------------------------------------------------

/**
 * Vale de gasto. Firmado por la clave del dispositivo (no extraible del
 * Keystore).
 *
 * La defensa clave contra el doble gasto esta en [seq] + [prevHash]: los gastos
 * de un mismo vale forman una CADENA HASH. Cada gasto n apunta al hash del gasto
 * n-1. Para gastar dos veces el mismo saldo hay que restaurar un respaldo viejo
 * y emitir un gasto distinto con el mismo [seq]. Eso produce dos vales firmados
 * por el propio usuario con el mismo (grantId, seq) y contenido distinto: una
 * BIFURCACION. El servidor la detecta al reconciliar y tiene prueba
 * criptografica irrefutable, firmada por el defraudador, para bloquear el
 * monedero y descontar.
 *
 * @param balanceAfterCentimos permite al validador comprobar la aritmetica sin
 *   conocer la cadena completa.
 */
data class SpendToken(
    val grantId: String,
    val walletId: String,
    val seq: Long,
    val amountCentimos: Long,
    val balanceAfterCentimos: Long,
    val prevHash: ByteArray,
    val validatorId: String,
    val unitId: String,
    val ownerId: String,
    val routeId: String,
    val challengeNonce: String,
    val spentAtEpochSec: Long,
    val deviceKeyFingerprint: String,
) {
    fun canonicalBytes(): ByteArray = CanonicalWriter(CanonicalWriter.TAG_SPEND)
        .str(grantId)
        .str(walletId)
        .long(seq)
        .long(amountCentimos)
        .long(balanceAfterCentimos)
        .bytes(prevHash)
        .str(validatorId)
        .str(unitId)
        .str(ownerId)
        .str(routeId)
        .str(challengeNonce)
        .long(spentAtEpochSec)
        .str(deviceKeyFingerprint)
        .build()

    override fun equals(other: Any?): Boolean = other is SpendToken &&
        canonicalBytes().contentEquals(other.canonicalBytes())

    override fun hashCode(): Int = canonicalBytes().contentHashCode()
}

data class SignedSpend(
    val token: SpendToken,
    val signature: ByteArray,
    /** El vale que respalda el saldo. Va en el QR para validar 100% sin red. */
    val grant: SignedGrant,
    /** Clave publica del dispositivo del pasajero. */
    val devicePublicKey: ByteArray,
) {
    /**
     * Hash del eslabon: es lo que apunta el siguiente gasto de la cadena.
     * Incluye la firma, asi que no se puede re-firmar el mismo contenido para
     * romper el encadenamiento.
     */
    fun linkHash(): ByteArray = Hash.sha256(token.canonicalBytes(), signature)

    /** Identidad del eslabon para deteccion de bifurcaciones en el servidor. */
    fun linkId(): String = Base64Url.encode(linkHash())

    /**
     * Codigo de viaje: cuatro cifras que el pasajero y el validador calculan
     * por separado, cada uno de su lado, a partir de este mismo gasto.
     *
     * Resuelve un problema practico que la criptografia sola no resuelve: el
     * pasajero descuenta el saldo al firmar, pero sin internet no tiene forma
     * de saber si el aparato del chofer llego a procesar SU pago o si el
     * escaneo fallo. Si los dos telefonos muestran el mismo numero, el pasajero
     * sabe que el validador leyo exactamente ese pago y no otro.
     *
     * Es una confirmacion para el ojo humano, no una prueba: un chofer
     * deshonesto podria enseñar el numero sin haber aceptado el cobro. La
     * prueba de verdad es el recibo firmado que se sube al reconciliar. Para
     * una confirmacion irrefutable en el momento haria falta un tercer
     * escaneo (o NFC), a costa de tiempo en la puerta del autobus.
     */
    fun tripCode(): String {
        val h = Hash.sha256(linkHash())
        val n = ((h[0].toInt() and 0xFF) shl 24) or
            ((h[1].toInt() and 0xFF) shl 16) or
            ((h[2].toInt() and 0xFF) shl 8) or
            (h[3].toInt() and 0xFF)
        return ((n.toLong() and 0xFFFFFFFFL) % 10_000).toString().padStart(4, '0')
    }

    override fun equals(other: Any?): Boolean = other is SignedSpend &&
        token == other.token &&
        signature.contentEquals(other.signature) &&
        grant == other.grant &&
        devicePublicKey.contentEquals(other.devicePublicKey)

    override fun hashCode(): Int = linkHash().contentHashCode()
}

// ---------------------------------------------------------------------------
// 4. Recibo del validador: lo que se sube para liquidar en bolivares.
// ---------------------------------------------------------------------------

data class ValidatorReceipt(
    val receiptId: String,
    val spend: SignedSpend,
    val validatorId: String,
    val unitId: String,
    val ownerId: String,
    val acceptedAtEpochSec: Long,
) {
    val amountCentimos: Long get() = spend.token.amountCentimos
}

// ---------------------------------------------------------------------------
// 5. Certificado de validador.
// ---------------------------------------------------------------------------

/**
 * Certificado que el servidor emite a cada validador (aparato de la unidad).
 *
 * Sin esto, cualquiera podria imprimir un QR de "reto" falso y cobrarle pasajes
 * a la gente sin prestar el servicio. Como el certificado viaja dentro del QR
 * del validador y esta firmado por el emisor, el telefono del pasajero puede
 * comprobar SIN INTERNET que ese validador esta registrado, a que unidad
 * pertenece y a que dueno se le va a pagar.
 */
data class ValidatorCert(
    val validatorId: String,
    val unitId: String,
    val ownerId: String,
    val publicKeyFingerprint: String,
    val issuedAtEpochSec: Long,
    val expiresAtEpochSec: Long,
    val issuerKeyId: String,
) {
    fun canonicalBytes(): ByteArray = CanonicalWriter(CanonicalWriter.TAG_VALIDATOR_CERT)
        .str(validatorId)
        .str(unitId)
        .str(ownerId)
        .str(publicKeyFingerprint)
        .long(issuedAtEpochSec)
        .long(expiresAtEpochSec)
        .str(issuerKeyId)
        .build()
}

data class SignedValidatorCert(val cert: ValidatorCert, val signature: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SignedValidatorCert && cert == other.cert && signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * cert.hashCode() + signature.contentHashCode()
}
