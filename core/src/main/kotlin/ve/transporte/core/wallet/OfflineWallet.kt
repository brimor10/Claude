package ve.transporte.core.wallet

import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.Ec
import ve.transporte.core.crypto.Hash
import ve.transporte.core.crypto.Signer
import ve.transporte.core.crypto.TrustStore
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.SignedChallenge
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SpendToken
import ve.transporte.core.qr.QrEnvelope

/**
 * Estado persistente del monedero. Inmutable: cada gasto produce un estado nuevo.
 *
 * En Android se guarda cifrado en reposo (SQLCipher / EncryptedFile), pero el
 * cifrado del almacenamiento NO es lo que impide el doble gasto: lo que lo
 * impide (o mejor dicho, lo hace demostrable) es la cadena hash de [lastLinkHash]
 * mas la firma en hardware.
 */
data class PurseState(
    val signedGrant: SignedGrant,
    /** Numero de secuencia del PROXIMO gasto. Estrictamente creciente. */
    val nextSeq: Long = 1,
    /** Hash del ultimo eslabon de la cadena. Ceros si aun no se ha gastado nada. */
    val lastLinkHash: ByteArray = Hash.ZERO_32,
    val spentCentimos: Long = 0,
    /** Gastado desde la ultima sincronizacion. Se compara contra el tope offline. */
    val offlineSpentCentimos: Long = 0,
    val offlineTrips: Int = 0,
    /** Gastos aun no confirmados por el servidor. Se suben al reconectar. */
    val pending: List<SignedSpend> = emptyList(),
    /** Cobros directos pendientes de subir. Algunos pueden acabar devueltos. */
    val presented: List<ve.transporte.core.protocol.SignedPresentedSpend> = emptyList(),
    /** Retos ya consumidos, para no firmar dos veces el mismo cobro. */
    val usedChallengeNonces: Set<String> = emptySet(),
    /**
     * Piso de tiempo confiable. Se mueve solo hacia adelante, alimentado por
     * fuentes firmadas (servidor y retos de validador). Evita que el usuario
     * atrase el reloj del telefono para revivir un vale caducado.
     */
    val trustedTimeFloorEpochSec: Long = 0,
) {
    val grantAmountCentimos: Long get() = signedGrant.grant.amountCentimos
    val balanceCentimos: Long get() = grantAmountCentimos - spentCentimos
    val offlineRemainingCentimos: Long
        get() = (signedGrant.grant.offlineSpendCapCentimos - offlineSpentCentimos).coerceAtLeast(0)
    val offlineTripsRemaining: Int
        get() = (signedGrant.grant.offlineTripCap - offlineTrips).coerceAtLeast(0)

    /** Todo lo que hay que subir al reconectar, en los dos modos. */
    val pendingAll: List<ve.transporte.core.protocol.AnySpend> get() = pending + presented
}

enum class DenyReason {
    SIN_SALDO_CARGADO,
    VALE_CADUCADO,
    VALE_NO_ES_DE_ESTE_TELEFONO,
    EMISOR_DESCONOCIDO,
    FIRMA_DEL_VALE_INVALIDA,
    CERTIFICADO_DE_VALIDADOR_INVALIDO,
    CERTIFICADO_NO_CORRESPONDE_AL_VALIDADOR,
    CERTIFICADO_DE_VALIDADOR_CADUCADO,
    FIRMA_DEL_RETO_INVALIDA,
    RETO_CADUCADO,
    RETO_YA_USADO,
    PASAJE_INVALIDO,
    MONEDA_DISTINTA,
    SALDO_INSUFICIENTE,
    TOPE_OFFLINE_ALCANZADO,
    TOPE_DE_VIAJES_OFFLINE_ALCANZADO,
}

sealed interface SpendOutcome {
    data class Approved(
        val spend: SignedSpend,
        /** Texto listo para pintar como QR en la pantalla del pasajero. */
        val qr: String,
        val newState: PurseState,
    ) : SpendOutcome

    data class Denied(val reason: DenyReason, val detail: String) : SpendOutcome
}

sealed interface PresentOutcome {
    data class Approved(
        val spend: ve.transporte.core.protocol.SignedPresentedSpend,
        /** Texto listo para pintar como QR en la pantalla del pasajero. */
        val qr: String,
        val newState: PurseState,
        /** Momento en que este QR deja de valer. */
        val expiresAtEpochSec: Long,
    ) : PresentOutcome

    data class Denied(val reason: DenyReason, val detail: String) : PresentOutcome
}

sealed interface LoadGrantOutcome {
    data class Loaded(val state: PurseState) : LoadGrantOutcome
    data class Rejected(val reason: DenyReason, val detail: String) : LoadGrantOutcome
}

/**
 * Motor del monedero del pasajero. Es codigo puro: sin Android, sin red, sin
 * base de datos. Recibe estado y devuelve estado, para poder probarlo entero.
 */
class OfflineWallet(
    private val signer: Signer,
    private val trust: TrustStore,
    private val clock: Clock = Clock.SYSTEM,
    private val nonceFactory: () -> String = { Ec.randomNonce() },
) {
    val deviceFingerprint: String = Hash.keyFingerprint(signer.publicKeyEncoded)

    /**
     * Carga un vale de saldo recien recargado (esto ocurre CON internet).
     * Verifica que venga firmado por un emisor conocido y que este amarrado a
     * la clave de este telefono.
     */
    fun loadGrant(signedGrant: SignedGrant, previous: PurseState? = null): LoadGrantOutcome {
        val g = signedGrant.grant
        if (!trust.knowsIssuer(g.issuerKeyId)) {
            return LoadGrantOutcome.Rejected(DenyReason.EMISOR_DESCONOCIDO, g.issuerKeyId)
        }
        if (!trust.verifyIssuer(g.issuerKeyId, g.canonicalBytes(), signedGrant.signature)) {
            return LoadGrantOutcome.Rejected(DenyReason.FIRMA_DEL_VALE_INVALIDA, g.grantId)
        }
        if (g.deviceKeyFingerprint != deviceFingerprint) {
            return LoadGrantOutcome.Rejected(
                DenyReason.VALE_NO_ES_DE_ESTE_TELEFONO,
                "el vale es para otro dispositivo",
            )
        }
        if (g.currency != Money.CURRENCY) {
            return LoadGrantOutcome.Rejected(DenyReason.MONEDA_DISTINTA, g.currency)
        }
        val floor = maxOf(previous?.trustedTimeFloorEpochSec ?: 0, g.issuedAtEpochSec)
        if (g.expiresAtEpochSec <= floor) {
            return LoadGrantOutcome.Rejected(DenyReason.VALE_CADUCADO, g.grantId)
        }
        return LoadGrantOutcome.Loaded(
            PurseState(signedGrant = signedGrant, trustedTimeFloorEpochSec = floor),
        )
    }

    /**
     * Paga un pasaje SIN INTERNET.
     *
     * Recibe el reto que el validador acaba de mostrar en su pantalla y
     * devuelve el QR firmado que el pasajero le enseña al validador.
     */
    fun pay(state: PurseState?, signedChallenge: SignedChallenge): SpendOutcome {
        if (state == null) return SpendOutcome.Denied(DenyReason.SIN_SALDO_CARGADO, "no hay vale cargado")

        val c = signedChallenge.challenge
        val cert = signedChallenge.cert.cert

        // 1. El validador esta acreditado por el emisor.
        if (!trust.verifyIssuer(cert.issuerKeyId, cert.canonicalBytes(), signedChallenge.cert.signature)) {
            return SpendOutcome.Denied(DenyReason.CERTIFICADO_DE_VALIDADOR_INVALIDO, cert.validatorId)
        }
        val challengeKeyFp = Hash.keyFingerprint(signedChallenge.validatorPublicKey)
        if (cert.publicKeyFingerprint != challengeKeyFp ||
            cert.validatorId != c.validatorId ||
            cert.unitId != c.unitId ||
            cert.ownerId != c.ownerId
        ) {
            return SpendOutcome.Denied(
                DenyReason.CERTIFICADO_NO_CORRESPONDE_AL_VALIDADOR,
                "el certificado no coincide con el reto",
            )
        }

        // 2. El reto lo firmo ese validador y no esta vencido.
        val validatorKey = try {
            Ec.decodePublicKey(signedChallenge.validatorPublicKey)
        } catch (e: Exception) {
            return SpendOutcome.Denied(DenyReason.FIRMA_DEL_RETO_INVALIDA, "clave ilegible: ${e.message}")
        }
        if (!Ec.verify(validatorKey, c.canonicalBytes(), signedChallenge.signature)) {
            return SpendOutcome.Denied(DenyReason.FIRMA_DEL_RETO_INVALIDA, c.validatorId)
        }

        // El reto viene firmado, asi que su hora es una fuente de tiempo confiable:
        // sirve para adelantar el piso y detectar un reloj atrasado a proposito.
        val timeFloor = maxOf(state.trustedTimeFloorEpochSec, c.issuedAtEpochSec)
        val now = maxOf(clock.nowEpochSec(), timeFloor)

        if (now > c.expiresAtEpochSec()) {
            return SpendOutcome.Denied(
                DenyReason.RETO_CADUCADO,
                "el QR del validador ya vencio (${now - c.expiresAtEpochSec()}s tarde)",
            )
        }
        if (c.challengeNonce in state.usedChallengeNonces) {
            return SpendOutcome.Denied(DenyReason.RETO_YA_USADO, c.challengeNonce)
        }
        if (cert.expiresAtEpochSec <= now) {
            return SpendOutcome.Denied(DenyReason.CERTIFICADO_DE_VALIDADOR_CADUCADO, cert.validatorId)
        }

        // 3. El vale de saldo sigue vigente.
        val g = state.signedGrant.grant
        if (now >= g.expiresAtEpochSec) {
            return SpendOutcome.Denied(DenyReason.VALE_CADUCADO, "vence ${g.expiresAtEpochSec}, ahora $now")
        }

        // 4. Reglas de monto.
        if (c.fareCentimos <= 0) {
            return SpendOutcome.Denied(DenyReason.PASAJE_INVALIDO, c.fareCentimos.toString())
        }
        if (c.fareCentimos > state.balanceCentimos) {
            return SpendOutcome.Denied(
                DenyReason.SALDO_INSUFICIENTE,
                "saldo ${Money.format(state.balanceCentimos)}, pasaje ${Money.format(c.fareCentimos)}",
            )
        }
        if (c.fareCentimos > state.offlineRemainingCentimos) {
            return SpendOutcome.Denied(
                DenyReason.TOPE_OFFLINE_ALCANZADO,
                "conectate para liberar mas saldo (quedan ${Money.format(state.offlineRemainingCentimos)} offline)",
            )
        }
        if (state.offlineTripsRemaining <= 0) {
            return SpendOutcome.Denied(
                DenyReason.TOPE_DE_VIAJES_OFFLINE_ALCANZADO,
                "${g.offlineTripCap} viajes sin sincronizar",
            )
        }

        // 5. Firmar el eslabon.
        val token = SpendToken(
            grantId = g.grantId,
            walletId = g.walletId,
            seq = state.nextSeq,
            amountCentimos = c.fareCentimos,
            balanceAfterCentimos = state.balanceCentimos - c.fareCentimos,
            prevHash = state.lastLinkHash,
            validatorId = c.validatorId,
            unitId = c.unitId,
            ownerId = c.ownerId,
            routeId = c.routeId,
            challengeNonce = c.challengeNonce,
            spentAtEpochSec = now,
            deviceKeyFingerprint = deviceFingerprint,
        )
        val signature = signer.sign(token.canonicalBytes())
        val spend = SignedSpend(token, signature, state.signedGrant, signer.publicKeyEncoded)

        val newState = state.copy(
            nextSeq = state.nextSeq + 1,
            lastLinkHash = spend.linkHash(),
            spentCentimos = state.spentCentimos + c.fareCentimos,
            offlineSpentCentimos = state.offlineSpentCentimos + c.fareCentimos,
            offlineTrips = state.offlineTrips + 1,
            pending = state.pending + spend,
            usedChallengeNonces = state.usedChallengeNonces + c.challengeNonce,
            trustedTimeFloorEpochSec = now,
        )
        return SpendOutcome.Approved(spend, QrEnvelope.encode(spend), newState)
    }

    /**
     * Modo de COBRO DIRECTO: genera un QR de pago sin haber visto al cobrador,
     * para que el lector de la unidad lo escanee de un tiron.
     *
     * Ojo con lo que implica: el saldo se descuenta AQUI, al generar el QR, sin
     * saber todavia si alguien va a leerlo. Si el escaneo no llega a ocurrir, el
     * servidor devuelve el dinero al reconciliar, porque no habra ningun recibo
     * firmado que reclame ese pago. Por eso el QR vale solo unos segundos: cuanto
     * mas corta la ventana, menos dinero queda en el aire y menos margen hay para
     * enseñar el mismo QR en dos unidades.
     *
     * La ventana por defecto son 30 segundos. Es corta a proposito: es el unico
     * limite que tiene la repeticion en este modo. Alargarla da tiempo a
     * mandarle la captura a alguien que va en otra unidad; acortarla mas obliga
     * a regenerar el QR en la cola, y cada regeneracion consume un eslabon de la
     * cadena que despues hay que devolver.
     *
     * @param fareCentimos el pasaje, que en este modo el telefono tiene que
     *   saber de antemano (tarifa plana de la ruta, actualizada al sincronizar).
     */
    fun present(
        state: PurseState?,
        fareCentimos: Long,
        windowSeconds: Int = 30,
    ): PresentOutcome {
        if (state == null) {
            return PresentOutcome.Denied(DenyReason.SIN_SALDO_CARGADO, "no hay vale cargado")
        }
        val g = state.signedGrant.grant
        val now = maxOf(clock.nowEpochSec(), state.trustedTimeFloorEpochSec)

        if (now >= g.expiresAtEpochSec) {
            return PresentOutcome.Denied(DenyReason.VALE_CADUCADO, "vence ${g.expiresAtEpochSec}, ahora $now")
        }
        if (fareCentimos <= 0) {
            return PresentOutcome.Denied(DenyReason.PASAJE_INVALIDO, fareCentimos.toString())
        }
        if (fareCentimos > state.balanceCentimos) {
            return PresentOutcome.Denied(
                DenyReason.SALDO_INSUFICIENTE,
                "saldo ${Money.format(state.balanceCentimos)}, pasaje ${Money.format(fareCentimos)}",
            )
        }
        if (fareCentimos > state.offlineRemainingCentimos) {
            return PresentOutcome.Denied(
                DenyReason.TOPE_OFFLINE_ALCANZADO,
                "conectate para liberar mas saldo (quedan ${Money.format(state.offlineRemainingCentimos)} offline)",
            )
        }
        if (state.offlineTripsRemaining <= 0) {
            return PresentOutcome.Denied(
                DenyReason.TOPE_DE_VIAJES_OFFLINE_ALCANZADO,
                "${g.offlineTripCap} viajes sin sincronizar",
            )
        }

        val token = ve.transporte.core.protocol.PresentedSpendToken(
            grantId = g.grantId,
            walletId = g.walletId,
            seq = state.nextSeq,
            amountCentimos = fareCentimos,
            balanceAfterCentimos = state.balanceCentimos - fareCentimos,
            prevHash = state.lastLinkHash,
            validFromEpochSec = now,
            windowSeconds = windowSeconds,
            nonce = nonceFactory(),
            deviceKeyFingerprint = deviceFingerprint,
        )
        val spend = ve.transporte.core.protocol.SignedPresentedSpend(
            token = token,
            signature = signer.sign(token.canonicalBytes()),
            grant = state.signedGrant,
            devicePublicKey = signer.publicKeyEncoded,
        )
        val newState = state.copy(
            nextSeq = state.nextSeq + 1,
            lastLinkHash = spend.linkHash(),
            spentCentimos = state.spentCentimos + fareCentimos,
            offlineSpentCentimos = state.offlineSpentCentimos + fareCentimos,
            offlineTrips = state.offlineTrips + 1,
            presented = state.presented + spend,
            trustedTimeFloorEpochSec = now,
        )
        return PresentOutcome.Approved(spend, QrEnvelope.encode(spend), newState, token.expiresAtEpochSec())
    }

    /**
     * Se llama al reconectar, con la respuesta del servidor.
     *
     * Reponer el cupo offline exige haber subido los gastos pendientes: es lo
     * que garantiza que el fraude offline tenga un techo, porque para seguir
     * gastando hay que pasar por la reconciliacion del servidor.
     */
    fun applySync(state: PurseState, ack: SyncAck): PurseState {
        val confirmed = ack.confirmedLinkIds.toSet()
        val stillPending = state.pending.filter { it.linkId() !in confirmed }
        val stillPresented = state.presented.filter { it.linkId() !in confirmed }
        val floor = maxOf(state.trustedTimeFloorEpochSec, ack.serverTimeEpochSec)
        val enVuelo = stillPending.sumOf { it.amountCentimos } +
            stillPresented.sumOf { it.amountCentimos }
        return state.copy(
            pending = stillPending,
            presented = stillPresented,
            // El cupo offline se repone solo por lo que el servidor confirmo.
            offlineSpentCentimos = enVuelo,
            offlineTrips = stillPending.size + stillPresented.size,
            trustedTimeFloorEpochSec = floor,
            // El servidor manda sobre el saldo: aqui entran tanto las
            // correcciones por fraude como las devoluciones de cobros directos
            // que ningun validador llego a reclamar.
            spentCentimos = ack.authoritativeSpentCentimos ?: state.spentCentimos,
            usedChallengeNonces = if (stillPending.isEmpty()) emptySet() else state.usedChallengeNonces,
        )
    }
}

/** Respuesta del servidor tras subir los gastos pendientes. */
data class SyncAck(
    val confirmedLinkIds: List<String>,
    val serverTimeEpochSec: Long,
    /** Si el servidor corrige el saldo (p. ej. tras detectar un fraude o una devolucion). */
    val authoritativeSpentCentimos: Long? = null,
    /** Cobros directos que nadie reclamo y que se le devuelven al pasajero. */
    val refundedLinkIds: List<String> = emptyList(),
)
