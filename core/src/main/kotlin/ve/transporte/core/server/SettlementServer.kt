package ve.transporte.core.server

import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.Ec
import ve.transporte.core.crypto.Hash
import ve.transporte.core.crypto.Signer
import ve.transporte.core.protocol.AnySpend
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.PurseGrant
import ve.transporte.core.protocol.ReceiptClaim
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedPresentedSpend
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SpendMode
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.ValidatorCert
import ve.transporte.core.protocol.ValidatorReceipt
import ve.transporte.core.wallet.SyncAck

/** Parametros de emision. Son las perillas con las que se acota el riesgo offline. */
data class IssuancePolicy(
    val grantValiditySeconds: Long = 30L * 24 * 3600,
    val validatorCertValiditySeconds: Long = 365L * 24 * 3600,
    /** Tope de gasto sin sincronizar, como fraccion del monto recargado. */
    val offlineCapFraction: Double = 1.0,
    /**
     * Tope absoluto de gasto sin sincronizar, gane el que sea menor.
     *
     * Es la PERDIDA MAXIMA que puede causar un telefono comprometido antes de
     * que el sistema lo bloquee. Por defecto, unos diez pasajes de 235 Bs.
     *
     * Ojo al ajustarlo: si queda por debajo de un pasaje, nadie puede pagar sin
     * señal y el sistema no sirve para lo que se hizo.
     */
    val offlineCapCeilingCentimos: Long = 2_350_00,
    val offlineTripCap: Int = 30,
    /** Comision del operador, en puntos basicos (100 pb = 1 %). */
    val commissionBasisPoints: Int = 300,
    /**
     * Cuanto se espera, tras vencer la ventana de un cobro directo, antes de
     * devolverle el dinero al pasajero si ningun validador lo reclamo.
     *
     * En el modo directo el telefono descuenta al generar el QR, sin saber si
     * alguien llego a leerlo. Si el escaneo fallo, o el pasajero se arrepintio,
     * ese dinero tiene que volver. La espera existe porque el validador puede
     * tardar en tener señal para subir su recibo.
     */
    val refundGraceSeconds: Long = 24 * 3600,
)

data class WalletAccount(
    val walletId: String,
    val devicePublicKey: ByteArray,
    val deviceFingerprint: String,
    var blocked: Boolean = false,
    /** Deuda por doble gasto comprobado. */
    var debtCentimos: Long = 0,
) {
    override fun equals(other: Any?): Boolean = other is WalletAccount && walletId == other.walletId
    override fun hashCode(): Int = walletId.hashCode()
}

data class Purse(
    val grantId: String,
    val walletId: String,
    val amountCentimos: Long,
    /** Dinero retenido en garantia hasta que se liquide o se devuelva. */
    var escrowCentimos: Long,
    var settledSpentCentimos: Long = 0,
)

/** Prueba de doble gasto: dos gastos distintos con el mismo numero de secuencia. */
data class DoubleSpendEvidence(
    val walletId: String,
    val grantId: String,
    val seq: Long,
    val branchA: AnySpend,
    val branchB: AnySpend,
) {
    /** Ambas ramas van firmadas por el propio usuario. No hay como negarlo. */
    fun humanSummary(): String =
        "Monedero $walletId gasto dos veces el movimiento #$seq del vale $grantId: " +
            "${Money.format(branchA.amountCentimos)} y ${Money.format(branchB.amountCentimos)}, " +
            "en dos cobros distintos."
}

/**
 * Un mismo pago reclamado por dos unidades distintas.
 *
 * Es el precio del modo de cobro directo: dentro de la ventana de validez, la
 * misma captura de pantalla puede colar en dos unidades. Se paga al primero que
 * lo reclamo y queda constancia contra ese monedero.
 */
data class DuplicateClaim(
    val linkId: String,
    val walletId: String,
    val primero: ReceiptClaim,
    val segundo: ReceiptClaim,
) {
    fun humanSummary(): String =
        "El pago $linkId del monedero $walletId lo reclamaron ${primero.unitId} y ${segundo.unitId}; " +
            "se le paga a ${primero.unitId}."
}

data class ChainIssue(val grantId: String, val seq: Long, val problem: String)

data class ReconciliationReport(
    val processedSpends: Int,
    val doubleSpends: List<DoubleSpendEvidence>,
    val chainIssues: List<ChainIssue>,
    val newlyBlockedWallets: List<String>,
    /** Perdida no cubierta por el saldo del pasajero (la asume la reserva de fraude). */
    val uncoveredCentimos: Long,
    /** Un mismo pago reclamado por dos unidades. */
    val duplicateClaims: List<DuplicateClaim> = emptyList(),
    /** Cobros directos que nadie reclamo: se le devuelven al pasajero. */
    val refundedLinkIds: List<String> = emptyList(),
    val refundedCentimos: Long = 0,
)

data class OwnerSettlement(
    val ownerId: String,
    val receipts: Int,
    val grossCentimos: Long,
    val commissionCentimos: Long,
    val netCentimos: Long,
) {
    fun humanSummary(): String =
        "Dueno $ownerId: $receipts pasajes, bruto ${Money.format(grossCentimos)}, " +
            "comision ${Money.format(commissionCentimos)}, a pagar ${Money.format(netCentimos)}."
}

private class LedgerEntry(
    val spend: AnySpend,
    val sources: MutableSet<String> = mutableSetOf(),
)

/**
 * Servidor central. Emite el saldo, reconcilia lo que ocurrio sin internet y
 * paga en bolivares al dueno de cada unidad.
 *
 * Es una implementacion de referencia en memoria: la logica de dinero y de
 * deteccion de fraude esta completa y probada, pero la persistencia, la
 * autenticacion de usuarios y la pasarela de pago son responsabilidad del
 * backend real.
 */
class SettlementServer(
    private val issuer: Signer,
    private val clock: Clock = Clock.SYSTEM,
    private val policy: IssuancePolicy = IssuancePolicy(),
    private val idFactory: () -> String = { Ec.randomNonce(12) },
) {
    val issuerKeyId: String get() = issuer.keyId
    val issuerPublicKey: ByteArray get() = issuer.publicKeyEncoded

    private val accounts = HashMap<String, WalletAccount>()

    /** Clave publica de cada validador dado de alta, para verificar sus recibos. */
    private val validatorKeys = HashMap<String, ByteArray>()
    private val purses = HashMap<String, Purse>()

    /** (grantId#seq) -> primer gasto visto para esa posicion de la cadena. */
    private val ledger = HashMap<String, LedgerEntry>()

    private val _doubleSpends = mutableListOf<DoubleSpendEvidence>()
    private val _chainIssues = mutableListOf<ChainIssue>()
    private val unsettledReceipts = LinkedHashMap<String, ValidatorReceipt>()

    /** Primer recibo visto por cada eslabon, para detectar reclamos duplicados. */
    private val claimsByLink = LinkedHashMap<String, ValidatorReceipt>()

    private val _duplicateClaims = mutableListOf<DuplicateClaim>()
    private val _rejectedReceipts = mutableListOf<Pair<String, String>>()
    private val refundedLinks = HashSet<String>()

    /** Un mismo pago reclamado por dos unidades distintas. */
    val duplicateClaims: List<DuplicateClaim> get() = _duplicateClaims.toList()

    /** Recibos que no se pudieron aceptar, con el motivo. */
    val rejectedReceipts: List<Pair<String, String>> get() = _rejectedReceipts.toList()
    private val settledReceiptIds = HashSet<String>()

    val doubleSpends: List<DoubleSpendEvidence> get() = _doubleSpends.toList()

    // -- Alta de participantes ----------------------------------------------

    fun enrollWallet(devicePublicKey: ByteArray, walletId: String = "w-${idFactory()}"): WalletAccount {
        val account = WalletAccount(
            walletId = walletId,
            devicePublicKey = devicePublicKey,
            deviceFingerprint = Hash.keyFingerprint(devicePublicKey),
        )
        accounts[walletId] = account
        return account
    }

    fun enrollValidator(
        validatorId: String,
        unitId: String,
        ownerId: String,
        validatorPublicKey: ByteArray,
    ): SignedValidatorCert {
        val now = clock.nowEpochSec()
        val cert = ValidatorCert(
            validatorId = validatorId,
            unitId = unitId,
            ownerId = ownerId,
            publicKeyFingerprint = Hash.keyFingerprint(validatorPublicKey),
            issuedAtEpochSec = now,
            expiresAtEpochSec = now + policy.validatorCertValiditySeconds,
            issuerKeyId = issuer.keyId,
        )
        validatorKeys[validatorId] = validatorPublicKey
        return SignedValidatorCert(cert, issuer.sign(cert.canonicalBytes()))
    }

    // -- Recarga (ONLINE) ----------------------------------------------------

    /**
     * Recarga. El dinero se cobra por el medio de pago del usuario y queda
     * RETENIDO aqui; lo que se le entrega al telefono es un vale firmado.
     *
     * Cada recarga crea un monedero (cadena) nuevo e independiente. Asi no hace
     * falta revocar el vale anterior, cosa que offline seria poco fiable.
     */
    fun topUp(walletId: String, amountCentimos: Long): SignedGrant {
        val account = accounts[walletId] ?: error("monedero no registrado: $walletId")
        require(!account.blocked) { "monedero bloqueado por fraude: $walletId" }
        require(amountCentimos > 0) { "la recarga debe ser positiva" }

        val now = clock.nowEpochSec()
        val offlineCap = minOf(
            (amountCentimos * policy.offlineCapFraction).toLong(),
            policy.offlineCapCeilingCentimos,
        ).coerceAtLeast(0)

        val grant = PurseGrant(
            grantId = "g-${idFactory()}",
            walletId = walletId,
            deviceKeyFingerprint = account.deviceFingerprint,
            amountCentimos = amountCentimos,
            currency = Money.CURRENCY,
            issuedAtEpochSec = now,
            expiresAtEpochSec = now + policy.grantValiditySeconds,
            offlineSpendCapCentimos = offlineCap,
            offlineTripCap = policy.offlineTripCap,
            issuerKeyId = issuer.keyId,
            nonce = Ec.randomNonce(),
        )
        purses[grant.grantId] = Purse(
            grantId = grant.grantId,
            walletId = walletId,
            amountCentimos = amountCentimos,
            escrowCentimos = amountCentimos,
        )
        return SignedGrant(grant, issuer.sign(grant.canonicalBytes()))
    }

    // -- Subida de datos -----------------------------------------------------

    /**
     * El validador sube sus recibos al reconectar. Esto es lo que genera el cobro.
     *
     * Cada recibo va firmado por el validador que lo emitio, asi que el servidor
     * no tiene que fiarse del canal: comprueba con la clave publica que registro
     * al darlo de alta. Sin eso, en el modo de cobro directo cualquiera podria
     * reclamar el pago de otro, porque el pago del pasajero no dice a que unidad
     * va.
     */
    fun ingestValidatorBatch(validatorId: String, receipts: List<ValidatorReceipt>): List<String> {
        val accepted = mutableListOf<String>()
        for (r in receipts) {
            val claim = r.receipt.claim
            val key = validatorKeys[claim.validatorId]
            if (key == null || !key.contentEquals(r.receipt.validatorPublicKey)) {
                _rejectedReceipts += r.receiptId to "validador no registrado: ${claim.validatorId}"
                continue
            }
            val ok = try {
                Ec.verify(
                    Ec.decodePublicKey(r.receipt.validatorPublicKey),
                    claim.canonicalBytes(),
                    r.receipt.signature,
                )
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                _rejectedReceipts += r.receiptId to "firma del recibo invalida"
                continue
            }
            if (claim.linkId != r.spend.linkId() || claim.amountCentimos != r.spend.amountCentimos) {
                _rejectedReceipts += r.receiptId to "el recibo no corresponde al pago que acompaña"
                continue
            }

            // Un mismo pago reclamado por DOS unidades: en el modo directo puede
            // pasar si el pasajero enseño el mismo QR en dos sitios dentro de la
            // ventana. Se paga al primero que lo reclamo y se deja constancia.
            val previo = claimsByLink[claim.linkId]
            if (previo != null && previo.receipt.claim.validatorId != claim.validatorId) {
                _duplicateClaims += DuplicateClaim(
                    linkId = claim.linkId,
                    walletId = r.spend.walletId,
                    primero = previo.receipt.claim,
                    segundo = claim,
                )
                continue
            }

            record(r.spend, source = "validator:${claim.validatorId}")
            claimsByLink.putIfAbsent(claim.linkId, r)
            if (r.receiptId !in settledReceiptIds) {
                unsettledReceipts[r.receiptId] = r
            }
            accepted += r.receiptId
        }
        return accepted
    }

    /** El pasajero sube sus gastos pendientes al reconectar. */
    fun ingestWalletSync(walletId: String, spends: List<AnySpend>): SyncAck {
        val confirmed = mutableListOf<String>()
        for (s in spends) {
            if (s.walletId != walletId) continue
            record(s, source = "wallet:$walletId")
            confirmed += s.linkId()
        }
        val authoritative = spends.firstOrNull()?.grantId?.let { grantId -> gastadoReal(grantId) }
        return SyncAck(
            confirmedLinkIds = confirmed,
            serverTimeEpochSec = clock.nowEpochSec(),
            authoritativeSpentCentimos = authoritative,
            refundedLinkIds = spends.map { it.linkId() }.filter { it in refundedLinks },
        )
    }

    /**
     * Registra un gasto y detecta bifurcaciones.
     *
     * Aqui esta el corazon del anti-doble-gasto: si ya existe un gasto DISTINTO
     * en la misma posicion (grantId, seq) de la cadena, es doble gasto, y las
     * dos firmas del propio usuario son la prueba.
     */
    private fun record(spend: AnySpend, source: String) {
        val key = "${spend.grantId}#${spend.seq}"
        val existing = ledger[key]
        if (existing == null) {
            ledger[key] = LedgerEntry(spend, mutableSetOf(source))
            return
        }
        existing.sources += source
        if (existing.spend.linkId() != spend.linkId()) {
            val already = _doubleSpends.any { it.grantId == spend.grantId && it.seq == spend.seq }
            if (!already) {
                _doubleSpends += DoubleSpendEvidence(
                    walletId = spend.walletId,
                    grantId = spend.grantId,
                    seq = spend.seq,
                    branchA = existing.spend,
                    branchB = spend,
                )
            }
        }
    }

    // -- Reconciliacion ------------------------------------------------------

    /**
     * Recorre cada cadena, comprueba el encadenamiento y la aritmetica, bloquea
     * a los defraudadores y calcula la perdida real.
     */
    fun reconcile(): ReconciliationReport {
        _chainIssues.clear()
        val blocked = mutableListOf<String>()

        val byGrant = ledger.values.groupBy { it.spend.grantId }
        for ((grantId, entries) in byGrant) {
            val purse = purses[grantId]
            if (purse == null) {
                _chainIssues += ChainIssue(grantId, 0, "vale desconocido para el servidor")
                continue
            }

            val ordered = entries.map { it.spend }.sortedBy { it.seq }
            var expectedSeq = 1L
            var prevHash = Hash.ZERO_32
            var continuityKnown = true
            var running = 0L

            for (spend in ordered) {
                if (spend.seq != expectedSeq) {
                    // Un hueco no es fraude por si solo: puede ser un gasto que
                    // el pasajero aun no ha subido. Pero rompe la verificacion
                    // del encadenamiento hasta el siguiente eslabon conocido.
                    _chainIssues += ChainIssue(
                        grantId, expectedSeq,
                        "falta el movimiento #$expectedSeq (el siguiente subido es #${spend.seq})",
                    )
                    continuityKnown = false
                }
                if (continuityKnown && !spend.prevHash.contentEquals(prevHash)) {
                    _chainIssues += ChainIssue(grantId, spend.seq, "el eslabon no apunta al anterior")
                }
                running += spend.amountCentimos
                if (continuityKnown && spend.balanceAfterCentimos != purse.amountCentimos - running) {
                    _chainIssues += ChainIssue(
                        grantId, spend.seq,
                        "saldo declarado ${Money.format(spend.balanceAfterCentimos)} != " +
                            Money.format(purse.amountCentimos - running),
                    )
                }
                // Tras un hueco se retoma la verificacion desde este eslabon.
                prevHash = spend.linkHash()
                continuityKnown = true
                expectedSeq = spend.seq + 1
            }
            purse.settledSpentCentimos = running
        }

        var uncovered = 0L
        for (evidence in _doubleSpends) {
            val account = accounts[evidence.walletId] ?: continue
            if (!account.blocked) {
                account.blocked = true
                blocked += account.walletId
            }
            // Las dos ramas se pagan al dueno (el chofer si presto el servicio);
            // la rama duplicada es la perdida, que se le carga al pasajero.
            val loss = evidence.branchB.amountCentimos
            val purse = purses[evidence.grantId]
            val fromEscrow = minOf(loss, purse?.escrowCentimos ?: 0)
            purse?.let { it.escrowCentimos -= fromEscrow }
            val remaining = loss - fromEscrow
            account.debtCentimos += remaining
            uncovered += remaining
        }

        val devueltos = devolverCobrosNoReclamados()

        return ReconciliationReport(
            processedSpends = ledger.size,
            doubleSpends = _doubleSpends.toList(),
            chainIssues = _chainIssues.toList(),
            newlyBlockedWallets = blocked,
            uncoveredCentimos = uncovered,
            duplicateClaims = _duplicateClaims.toList(),
            refundedLinkIds = devueltos.map { it.linkId() },
            refundedCentimos = devueltos.sumOf { it.amountCentimos },
        )
    }

    /**
     * Devuelve el dinero de los cobros directos que ningun validador reclamo.
     *
     * En el modo directo el telefono descuenta al generar el QR, sin poder saber
     * si alguien llego a leerlo. Si el escaneo fallo, o el pasajero cambio de
     * idea, ese dinero tiene que volver. Se espera [IssuancePolicy.refundGraceSeconds]
     * desde que vencio la ventana, porque el validador puede tardar en subir su
     * recibo.
     */
    private fun devolverCobrosNoReclamados(): List<AnySpend> {
        val now = clock.nowEpochSec()
        val devueltos = mutableListOf<AnySpend>()
        for (entry in ledger.values) {
            val spend = entry.spend
            if (spend !is SignedPresentedSpend) continue
            val linkId = spend.linkId()
            if (linkId in refundedLinks || claimsByLink.containsKey(linkId)) continue
            if (now < spend.token.expiresAtEpochSec() + policy.refundGraceSeconds) continue
            refundedLinks += linkId
            devueltos += spend
        }
        return devueltos
    }

    /**
     * Lo que de verdad se ha gastado de un vale: todo lo registrado menos lo
     * devuelto. Es la cifra que manda sobre el saldo que muestra el telefono.
     */
    private fun gastadoReal(grantId: String): Long = ledger.values
        .filter { it.spend.grantId == grantId && it.spend.linkId() !in refundedLinks }
        .sumOf { it.spend.amountCentimos }

    /** Lista negra que se distribuye a los validadores en cada sincronizacion. */
    fun hotlist(): Set<String> = accounts.values.filter { it.blocked }.map { it.walletId }.toSet()

    fun account(walletId: String): WalletAccount? = accounts[walletId]

    /** Un monedero bloqueado por fraude no puede volver a recargar. */
    fun topUpAllowed(walletId: String): Boolean = accounts[walletId]?.blocked == false

    fun purse(grantId: String): Purse? = purses[grantId]

    // -- Liquidacion en bolivares -------------------------------------------

    /**
     * Calcula lo que hay que pagarle al dueno de la unidad y marca los recibos
     * como liquidados. El pago efectivo (transferencia / pago movil en Bs) lo
     * ejecuta el backend real con estos numeros.
     */
    fun settle(ownerId: String): OwnerSettlement {
        val mine = unsettledReceipts.values.filter { it.ownerId == ownerId }
        val gross = mine.sumOf { it.amountCentimos }
        val commission = gross * policy.commissionBasisPoints / 10_000
        mine.forEach {
            unsettledReceipts.remove(it.receiptId)
            settledReceiptIds += it.receiptId
            purses[it.spend.grantId]?.let { p ->
                p.escrowCentimos = (p.escrowCentimos - it.amountCentimos).coerceAtLeast(0)
            }
        }
        return OwnerSettlement(
            ownerId = ownerId,
            receipts = mine.size,
            grossCentimos = gross,
            commissionCentimos = commission,
            netCentimos = gross - commission,
        )
    }

    fun pendingSettlementFor(ownerId: String): Long =
        unsettledReceipts.values.filter { it.ownerId == ownerId }.sumOf { it.amountCentimos }

    /** Exporta las pruebas de fraude, p. ej. para un reclamo formal. */
    fun fraudDossier(): String = buildString {
        appendLine("Expediente de doble gasto - ${_doubleSpends.size} caso(s)")
        _doubleSpends.forEach { e ->
            appendLine("- ${e.humanSummary()}")
            appendLine("    rama A: ${Base64Url.encode(e.branchA.linkHash())}")
            appendLine("    rama B: ${Base64Url.encode(e.branchB.linkHash())}")
        }
    }
}
