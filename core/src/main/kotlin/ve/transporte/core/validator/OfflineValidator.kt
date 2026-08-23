package ve.transporte.core.validator

import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.Ec
import ve.transporte.core.crypto.Hash
import ve.transporte.core.crypto.Signer
import ve.transporte.core.crypto.TrustStore
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.SignedChallenge
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.ValidatorChallenge
import ve.transporte.core.protocol.ValidatorReceipt
import ve.transporte.core.qr.QrEnvelope
import ve.transporte.core.qr.QrFormatException

data class ValidatorConfig(
    val validatorId: String,
    val unitId: String,
    val ownerId: String,
    val routeId: String,
    val fareCentimos: Long,
    /** Vida del QR del validador. Corto = menos margen para fotografiarlo y reusarlo. */
    val challengeTtlSeconds: Int = 45,
    /** Tolerancia de desfase de reloj del telefono del pasajero. */
    val maxClockSkewSeconds: Int = 120,
)

enum class RejectReason {
    QR_ILEGIBLE,
    EMISOR_DESCONOCIDO,
    FIRMA_DEL_VALE_INVALIDA,
    VALE_CADUCADO,
    FIRMA_DE_GASTO_INVALIDA,
    CLAVE_NO_CORRESPONDE_AL_VALE,
    NO_ES_PARA_ESTA_UNIDAD,
    RETO_DESCONOCIDO,
    RETO_CADUCADO,
    RETO_YA_COBRADO,
    MONTO_NO_COINCIDE_CON_EL_PASAJE,
    ARITMETICA_INVALIDA,
    DOBLE_GASTO_DETECTADO,
    MONEDERO_BLOQUEADO,
    FECHA_FUERA_DE_RANGO,
}

sealed interface AcceptResult {
    data class Accepted(
        val receipt: ValidatorReceipt,
        val fareCentimos: Long,
        val passengerBalanceAfterCentimos: Long,
    ) : AcceptResult

    /**
     * Este QR ya se cobro antes. Puede ser un doble escaneo inocente (camara
     * temblorosa) o alguien intentando viajar con una captura de pantalla.
     *
     * NO es un pago valido nuevo: la pantalla del chofer debe distinguirlo
     * claramente de [Accepted]. Es idempotente, no cobra de nuevo.
     *
     * [receipt] puede venir nulo si el recibo ya se liquido y se purgo.
     */
    data class AlreadyAccepted(
        val receiptId: String,
        val receipt: ValidatorReceipt?,
    ) : AcceptResult

    data class Rejected(val reason: RejectReason, val detail: String) : AcceptResult
}

/**
 * Motor del validador de la unidad (el aparato del chofer).
 *
 * Funciona 100% sin internet. Solo necesita traer, de fabrica o de la ultima
 * sincronizacion:
 *   - las claves publicas del emisor ([TrustStore]),
 *   - su propio certificado firmado,
 *   - la lista negra de monederos bloqueados.
 *
 * Al reconectar sube los recibos, que es lo que dispara el pago en bolivares al
 * dueno de la unidad.
 */
class OfflineValidator(
    val config: ValidatorConfig,
    private val signer: Signer,
    private val cert: SignedValidatorCert,
    private val trust: TrustStore,
    private val clock: Clock = Clock.SYSTEM,
    private val nonceFactory: () -> String = { Ec.randomNonce() },
) {
    /** Retos emitidos y aun no consumidos. */
    private val openChallenges = LinkedHashMap<String, ValidatorChallenge>()

    /**
     * (grantId, seq) -> linkId del gasto ya visto. Detecta repeticion y bifurcacion.
     *
     * Sobrevive a [clearSettled] a proposito: si se borrara al liquidar, una
     * captura de pantalla vieja volveria a colar. En produccion esto es una
     * tabla en disco, podable por la fecha de vencimiento del vale.
     */
    private val seenLinks = HashMap<String, String>()

    private val receiptsByLink = LinkedHashMap<String, ValidatorReceipt>()

    private var hotlist: Set<String> = emptySet()

    /** Bifurcaciones detectadas en sitio: prueba de doble gasto para subir. */
    private val _fraudEvidence = mutableListOf<Pair<SignedSpend, String>>()
    val fraudEvidence: List<Pair<SignedSpend, String>> get() = _fraudEvidence.toList()

    val pendingReceipts: List<ValidatorReceipt> get() = receiptsByLink.values.toList()

    /** Total acumulado a favor del dueno de la unidad, aun sin liquidar. */
    fun accruedCentimos(): Long = receiptsByLink.values.sumOf { it.amountCentimos }

    /**
     * Genera el QR que se muestra al pasajero. Cada cobro lleva un nonce nuevo:
     * eso es lo que impide que alguien pague con una captura de pantalla vieja.
     */
    fun newChallenge(fareCentimos: Long = config.fareCentimos): SignedChallenge {
        val now = clock.nowEpochSec()
        purgeExpired(now)
        val challenge = ValidatorChallenge(
            validatorId = config.validatorId,
            unitId = config.unitId,
            ownerId = config.ownerId,
            routeId = config.routeId,
            fareCentimos = fareCentimos,
            challengeNonce = nonceFactory(),
            issuedAtEpochSec = now,
            ttlSeconds = config.challengeTtlSeconds,
            validatorKeyId = signer.keyId,
        )
        openChallenges[challenge.challengeNonce] = challenge
        return SignedChallenge(
            challenge = challenge,
            signature = signer.sign(challenge.canonicalBytes()),
            validatorPublicKey = signer.publicKeyEncoded,
            cert = cert,
        )
    }

    fun updateHotlist(blockedWalletIds: Collection<String>) {
        hotlist = blockedWalletIds.toSet()
    }

    /** Verifica y cobra un QR de pago. Todo offline. */
    fun accept(qrText: String): AcceptResult {
        val spend = try {
            QrEnvelope.decodeSpend(qrText)
        } catch (e: QrFormatException) {
            return AcceptResult.Rejected(RejectReason.QR_ILEGIBLE, e.message ?: "formato")
        }
        return accept(spend)
    }

    fun accept(spend: SignedSpend): AcceptResult {
        val now = clock.nowEpochSec()
        purgeExpired(now)

        val g = spend.grant.grant
        val t = spend.token

        // -- 1. El saldo lo emitio el servidor y no ha vencido. -----------------
        if (!trust.knowsIssuer(g.issuerKeyId)) {
            return AcceptResult.Rejected(RejectReason.EMISOR_DESCONOCIDO, g.issuerKeyId)
        }
        if (!trust.verifyIssuer(g.issuerKeyId, g.canonicalBytes(), spend.grant.signature)) {
            // Aqui muere el "saldo fantasma": sin la clave privada del servidor
            // no se puede fabricar un vale que pase por aqui.
            return AcceptResult.Rejected(RejectReason.FIRMA_DEL_VALE_INVALIDA, g.grantId)
        }
        if (now >= g.expiresAtEpochSec) {
            return AcceptResult.Rejected(RejectReason.VALE_CADUCADO, "vencio en ${g.expiresAtEpochSec}")
        }
        if (g.currency != Money.CURRENCY) {
            return AcceptResult.Rejected(RejectReason.ARITMETICA_INVALIDA, "moneda ${g.currency}")
        }

        // -- 2. El gasto lo firmo el telefono al que el servidor le dio el saldo.
        val deviceFp = Hash.keyFingerprint(spend.devicePublicKey)
        if (deviceFp != g.deviceKeyFingerprint || deviceFp != t.deviceKeyFingerprint) {
            return AcceptResult.Rejected(
                RejectReason.CLAVE_NO_CORRESPONDE_AL_VALE,
                "el vale no es de este dispositivo",
            )
        }
        val deviceKey = try {
            Ec.decodePublicKey(spend.devicePublicKey)
        } catch (e: Exception) {
            return AcceptResult.Rejected(RejectReason.FIRMA_DE_GASTO_INVALIDA, "clave ilegible")
        }
        if (!Ec.verify(deviceKey, t.canonicalBytes(), spend.signature)) {
            return AcceptResult.Rejected(RejectReason.FIRMA_DE_GASTO_INVALIDA, t.grantId)
        }
        if (t.walletId != g.walletId || t.grantId != g.grantId) {
            return AcceptResult.Rejected(RejectReason.ARITMETICA_INVALIDA, "vale y gasto no casan")
        }

        // -- 3. Lista negra ------------------------------------------------------
        if (t.walletId in hotlist) {
            return AcceptResult.Rejected(RejectReason.MONEDERO_BLOQUEADO, t.walletId)
        }

        // -- 4. Es para esta unidad ---------------------------------------------
        if (t.validatorId != config.validatorId || t.unitId != config.unitId ||
            t.ownerId != config.ownerId || t.routeId != config.routeId
        ) {
            return AcceptResult.Rejected(
                RejectReason.NO_ES_PARA_ESTA_UNIDAD,
                "${t.unitId}/${t.validatorId}",
            )
        }

        // -- 5. Repeticion / bifurcacion vista por este mismo aparato -----------
        // Va ANTES de mirar el reto: al aceptar un pago se consume su reto, asi
        // que un segundo escaneo del mismo QR (camara temblorosa) ya no lo
        // encontraria y se reportaria como "reto desconocido" en vez de como
        // duplicado inofensivo.
        val chainKey = "${t.grantId}#${t.seq}"
        val linkId = spend.linkId()
        val previous = seenLinks[chainKey]
        if (previous != null) {
            return if (previous == linkId) {
                AcceptResult.AlreadyAccepted(linkId, receiptsByLink[linkId])
            } else {
                // Dos gastos distintos con el mismo numero de secuencia: el
                // pasajero restauro un respaldo. Queda la prueba firmada por el.
                _fraudEvidence += spend to "bifurcacion en $chainKey"
                AcceptResult.Rejected(
                    RejectReason.DOBLE_GASTO_DETECTADO,
                    "ya se cobro el movimiento ${t.seq} de este vale",
                )
            }
        }

        // -- 6. Responde a un reto vivo de ESTE aparato -------------------------
        val challenge = openChallenges[t.challengeNonce]
            ?: return AcceptResult.Rejected(
                RejectReason.RETO_DESCONOCIDO,
                "el QR no responde a ningun cobro abierto (¿pantallazo viejo?)",
            )
        if (now > challenge.expiresAtEpochSec()) {
            openChallenges.remove(t.challengeNonce)
            return AcceptResult.Rejected(RejectReason.RETO_CADUCADO, t.challengeNonce)
        }
        if (t.amountCentimos != challenge.fareCentimos) {
            return AcceptResult.Rejected(
                RejectReason.MONTO_NO_COINCIDE_CON_EL_PASAJE,
                "pago ${Money.format(t.amountCentimos)}, pasaje ${Money.format(challenge.fareCentimos)}",
            )
        }
        if (t.spentAtEpochSec > now + config.maxClockSkewSeconds ||
            t.spentAtEpochSec < challenge.issuedAtEpochSec - config.maxClockSkewSeconds
        ) {
            return AcceptResult.Rejected(
                RejectReason.FECHA_FUERA_DE_RANGO,
                "hora del gasto ${t.spentAtEpochSec}, aqui son $now",
            )
        }

        // -- 7. Aritmetica comprobable sin conocer toda la cadena ---------------
        if (t.seq < 1 ||
            t.amountCentimos <= 0 ||
            t.balanceAfterCentimos < 0 ||
            t.balanceAfterCentimos + t.amountCentimos > g.amountCentimos
        ) {
            return AcceptResult.Rejected(
                RejectReason.ARITMETICA_INVALIDA,
                "seq=${t.seq} monto=${t.amountCentimos} despues=${t.balanceAfterCentimos}",
            )
        }

        // -- 8. Aceptado --------------------------------------------------------
        openChallenges.remove(t.challengeNonce)
        seenLinks[chainKey] = linkId
        val receipt = ValidatorReceipt(
            receiptId = linkId,
            spend = spend,
            validatorId = config.validatorId,
            unitId = config.unitId,
            ownerId = config.ownerId,
            acceptedAtEpochSec = now,
        )
        receiptsByLink[linkId] = receipt
        return AcceptResult.Accepted(receipt, t.amountCentimos, t.balanceAfterCentimos)
    }

    /** Se llama al subir los recibos: el servidor confirma cuales recibio. */
    fun clearSettled(receiptIds: Collection<String>) {
        receiptIds.forEach(receiptsByLink::remove)
    }

    private fun purgeExpired(now: Long) {
        openChallenges.entries.removeAll { (_, c) -> now > c.expiresAtEpochSec() }
    }
}
