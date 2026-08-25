package ve.transporte.core.validator

import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.Ec
import ve.transporte.core.crypto.Hash
import ve.transporte.core.crypto.Signer
import ve.transporte.core.crypto.TrustStore
import ve.transporte.core.protocol.AnySpend
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.ReceiptClaim
import ve.transporte.core.protocol.SignedChallenge
import ve.transporte.core.protocol.SignedPresentedSpend
import ve.transporte.core.protocol.SignedReceipt
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.SpendMode
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
    /**
     * Pasajes anteriores que esta unidad todavia acepta en el modo de cobro
     * directo, mientras dura la transicion tras una subida de tarifa.
     *
     * Hace falta porque en el modo directo el telefono firma el monto SIN haber
     * hablado con la unidad: usa el pasaje que tenia guardado la ultima vez que
     * tuvo señal. En un pais donde las tarifas suben seguido, un pasajero sin
     * datos desde hace unos dias llegaria con el monto viejo y se quedaria en
     * tierra, justo en el caso para el que se hizo el sistema.
     *
     * Con la ventana de transicion el pasajero pasa, y la diferencia la absorbe
     * el operador o se le cobra despues. El modo de dos escaneos no necesita
     * esto: ahi el monto lo pone la unidad en el momento.
     */
    val previousFaresCentimos: List<Long> = emptyList(),
    /** Vida del QR del validador. Corto = menos margen para fotografiarlo y reusarlo. */
    val challengeTtlSeconds: Int = 45,
    /** Tolerancia de desfase de reloj del telefono del pasajero. */
    val maxClockSkewSeconds: Int = 120,
    /**
     * Si esta unidad acepta el modo de cobro directo (un solo escaneo).
     *
     * Es mas rapido en la puerta, pero mas debil contra la repeticion: ver
     * [ve.transporte.core.protocol.PresentedSpendToken]. Se deja como decision
     * del operador, unidad por unidad.
     */
    val acceptPresented: Boolean = true,
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

    /** Modo directo: el QR del pasajero ya salio de su ventana de validez. */
    PAGO_VENCIDO,

    /** Esta unidad no acepta el modo de cobro directo. */
    MODO_NO_ACEPTADO,
}

sealed interface AcceptResult {
    data class Accepted(
        val receipt: ValidatorReceipt,
        val fareCentimos: Long,
        val passengerBalanceAfterCentimos: Long,
        val tripCode: String,
        val mode: SpendMode,
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
 * sincronizacion: las claves publicas del emisor ([TrustStore]), su propio
 * certificado firmado, y la lista negra de monederos bloqueados.
 *
 * Acepta los dos modos de cobro:
 *
 *  - **Modo reto** (dos escaneos): el validador enseña su QR, el pasajero
 *    responde. Es el mas seguro; una captura de pantalla no sirve nunca.
 *  - **Modo directo** (un escaneo): el pasajero enseña su QR y el lector de la
 *    unidad lo lee. Es mas rapido, a cambio de que el pago solo esta amarrado a
 *    una ventana de tiempo corta en vez de a un reto concreto.
 *
 * Al reconectar sube los recibos firmados, que es lo que dispara el pago en
 * bolivares al dueño de la unidad.
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
    private val _fraudEvidence = mutableListOf<Pair<AnySpend, String>>()
    val fraudEvidence: List<Pair<AnySpend, String>> get() = _fraudEvidence.toList()

    val pendingReceipts: List<ValidatorReceipt> get() = receiptsByLink.values.toList()

    /** Total acumulado a favor del dueño de la unidad, aun sin liquidar. */
    fun accruedCentimos(): Long = receiptsByLink.values.sumOf { it.amountCentimos }

    /**
     * Genera el QR que se muestra al pasajero en el modo de dos escaneos. Cada
     * cobro lleva un nonce nuevo: eso es lo que impide que alguien pague con una
     * captura de pantalla vieja.
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

    /** Lee cualquiera de los dos tipos de QR de pago. */
    fun accept(qrText: String): AcceptResult = when (QrEnvelope.typeOf(qrText)) {
        QrEnvelope.TYPE_SPEND -> parse(qrText, QrEnvelope::decodeSpend)?.let(::accept)
        QrEnvelope.TYPE_PRESENTED -> parse(qrText, QrEnvelope::decodePresented)?.let(::acceptPresented)
        else -> null
    } ?: AcceptResult.Rejected(RejectReason.QR_ILEGIBLE, "no es un QR de pago de Pana Pago")

    private fun <T> parse(qrText: String, decoder: (String) -> T): T? = try {
        decoder(qrText)
    } catch (_: QrFormatException) {
        null
    }

    /** Modo reto: el pago responde a un cobro que este aparato acaba de abrir. */
    fun accept(spend: SignedSpend): AcceptResult {
        val now = clock.nowEpochSec()
        purgeExpired(now)
        val t = spend.token

        comprobacionesComunes(spend, now)?.let { return it }

        if (t.validatorId != config.validatorId || t.unitId != config.unitId ||
            t.ownerId != config.ownerId || t.routeId != config.routeId
        ) {
            return AcceptResult.Rejected(
                RejectReason.NO_ES_PARA_ESTA_UNIDAD,
                "${t.unitId}/${t.validatorId}",
            )
        }

        repeticion(spend)?.let { return it }

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

        openChallenges.remove(t.challengeNonce)
        return cobrar(spend, now)
    }

    /**
     * Modo directo: el pasajero enseña su QR sin haber visto nada de esta
     * unidad, y el lector lo escanea de un tiron.
     *
     * Aqui no hay reto al que responder, asi que lo que acota la repeticion es
     * la ventana de tiempo del propio pago, contrastada contra el reloj de este
     * aparato (que se sincroniza seguido), mas el registro local de eslabones ya
     * cobrados.
     */
    fun acceptPresented(spend: SignedPresentedSpend): AcceptResult {
        if (!config.acceptPresented) {
            return AcceptResult.Rejected(
                RejectReason.MODO_NO_ACEPTADO,
                "esta unidad solo cobra mostrando su propio QR primero",
            )
        }
        val now = clock.nowEpochSec()
        purgeExpired(now)
        val t = spend.token

        comprobacionesComunes(spend, now)?.let { return it }
        repeticion(spend)?.let { return it }

        val pasajesAceptados = listOf(config.fareCentimos) + config.previousFaresCentimos
        if (t.amountCentimos !in pasajesAceptados) {
            return AcceptResult.Rejected(
                RejectReason.MONTO_NO_COINCIDE_CON_EL_PASAJE,
                "pago ${Money.format(t.amountCentimos)}, pasaje ${Money.format(config.fareCentimos)}",
            )
        }
        if (now > t.expiresAtEpochSec() + config.maxClockSkewSeconds) {
            return AcceptResult.Rejected(
                RejectReason.PAGO_VENCIDO,
                "el QR del pasajero vencio hace ${now - t.expiresAtEpochSec()}s, que genere otro",
            )
        }
        if (now < t.validFromEpochSec - config.maxClockSkewSeconds) {
            return AcceptResult.Rejected(
                RejectReason.FECHA_FUERA_DE_RANGO,
                "el QR dice ser del futuro (${t.validFromEpochSec}, aqui son $now)",
            )
        }

        return cobrar(spend, now)
    }

    /** Lo que se comprueba igual en los dos modos. Devuelve null si todo va bien. */
    private fun comprobacionesComunes(spend: AnySpend, now: Long): AcceptResult.Rejected? {
        val g = spend.grant.grant

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

        val deviceFp = Hash.keyFingerprint(spend.devicePublicKey)
        if (deviceFp != g.deviceKeyFingerprint || deviceFp != spend.deviceKeyFingerprint) {
            return AcceptResult.Rejected(
                RejectReason.CLAVE_NO_CORRESPONDE_AL_VALE,
                "el vale no es de este dispositivo",
            )
        }
        val deviceKey = try {
            Ec.decodePublicKey(spend.devicePublicKey)
        } catch (_: Exception) {
            return AcceptResult.Rejected(RejectReason.FIRMA_DE_GASTO_INVALIDA, "clave ilegible")
        }
        if (!Ec.verify(deviceKey, spend.signedBytes(), spend.signature)) {
            return AcceptResult.Rejected(RejectReason.FIRMA_DE_GASTO_INVALIDA, spend.grantId)
        }
        if (spend.walletId != g.walletId || spend.grantId != g.grantId) {
            return AcceptResult.Rejected(RejectReason.ARITMETICA_INVALIDA, "vale y gasto no casan")
        }
        if (spend.walletId in hotlist) {
            return AcceptResult.Rejected(RejectReason.MONEDERO_BLOQUEADO, spend.walletId)
        }
        if (spend.seq < 1 ||
            spend.amountCentimos <= 0 ||
            spend.balanceAfterCentimos < 0 ||
            spend.balanceAfterCentimos + spend.amountCentimos > g.amountCentimos
        ) {
            return AcceptResult.Rejected(
                RejectReason.ARITMETICA_INVALIDA,
                "seq=${spend.seq} monto=${spend.amountCentimos} despues=${spend.balanceAfterCentimos}",
            )
        }
        return null
    }

    /**
     * Repeticion y bifurcacion vistas por este mismo aparato.
     *
     * Va ANTES de mirar el reto: al aceptar un pago se consume su reto, asi que
     * un segundo escaneo del mismo QR (camara temblorosa) ya no lo encontraria y
     * se reportaria como "reto desconocido" en vez de como duplicado inofensivo.
     */
    private fun repeticion(spend: AnySpend): AcceptResult? {
        val chainKey = "${spend.grantId}#${spend.seq}"
        val linkId = spend.linkId()
        val previous = seenLinks[chainKey] ?: return null
        return if (previous == linkId) {
            AcceptResult.AlreadyAccepted(linkId, receiptsByLink[linkId])
        } else {
            // Dos gastos distintos con el mismo numero de secuencia: el pasajero
            // restauro un respaldo. Queda la prueba firmada por el.
            _fraudEvidence += spend to "bifurcacion en $chainKey"
            AcceptResult.Rejected(
                RejectReason.DOBLE_GASTO_DETECTADO,
                "ya se cobro el movimiento ${spend.seq} de este vale",
            )
        }
    }

    /** Anota el cobro y firma el recibo con el que se reclamara el dinero. */
    private fun cobrar(spend: AnySpend, now: Long): AcceptResult.Accepted {
        val linkId = spend.linkId()
        seenLinks["${spend.grantId}#${spend.seq}"] = linkId

        val claim = ReceiptClaim(
            linkId = linkId,
            validatorId = config.validatorId,
            unitId = config.unitId,
            ownerId = config.ownerId,
            routeId = config.routeId,
            amountCentimos = spend.amountCentimos,
            acceptedAtEpochSec = now,
            mode = spend.mode,
        )
        val receipt = ValidatorReceipt(
            spend = spend,
            receipt = SignedReceipt(
                claim = claim,
                signature = signer.sign(claim.canonicalBytes()),
                validatorPublicKey = signer.publicKeyEncoded,
            ),
        )
        receiptsByLink[linkId] = receipt
        return AcceptResult.Accepted(
            receipt = receipt,
            fareCentimos = spend.amountCentimos,
            passengerBalanceAfterCentimos = spend.balanceAfterCentimos,
            tripCode = spend.tripCode(),
            mode = spend.mode,
        )
    }

    /** Se llama al subir los recibos: el servidor confirma cuales recibio. */
    fun clearSettled(receiptIds: Collection<String>) {
        receiptIds.forEach(receiptsByLink::remove)
    }

    private fun purgeExpired(now: Long) {
        openChallenges.entries.removeAll { (_, c) -> now > c.expiresAtEpochSec() }
    }
}
