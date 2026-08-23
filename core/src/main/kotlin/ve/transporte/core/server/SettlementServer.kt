package ve.transporte.core.server

import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.Ec
import ve.transporte.core.crypto.Hash
import ve.transporte.core.crypto.Signer
import ve.transporte.core.protocol.Money
import ve.transporte.core.protocol.PurseGrant
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedSpend
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
    /** Tope absoluto de gasto offline, gane el que sea menor. */
    val offlineCapCeilingCentimos: Long = 200_00,
    val offlineTripCap: Int = 30,
    /** Comision del operador, en puntos basicos (100 pb = 1 %). */
    val commissionBasisPoints: Int = 300,
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
    val branchA: SignedSpend,
    val branchB: SignedSpend,
) {
    /** Ambas ramas van firmadas por el propio usuario. No hay como negarlo. */
    fun humanSummary(): String =
        "Monedero $walletId gasto dos veces el movimiento #$seq del vale $grantId: " +
            "${Money.format(branchA.token.amountCentimos)} en ${branchA.token.unitId} y " +
            "${Money.format(branchB.token.amountCentimos)} en ${branchB.token.unitId}."
}

data class ChainIssue(val grantId: String, val seq: Long, val problem: String)

data class ReconciliationReport(
    val processedSpends: Int,
    val doubleSpends: List<DoubleSpendEvidence>,
    val chainIssues: List<ChainIssue>,
    val newlyBlockedWallets: List<String>,
    /** Perdida no cubierta por el saldo del pasajero (la asume la reserva de fraude). */
    val uncoveredCentimos: Long,
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
    val spend: SignedSpend,
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
    private val purses = HashMap<String, Purse>()

    /** (grantId#seq) -> primer gasto visto para esa posicion de la cadena. */
    private val ledger = HashMap<String, LedgerEntry>()

    private val _doubleSpends = mutableListOf<DoubleSpendEvidence>()
    private val _chainIssues = mutableListOf<ChainIssue>()
    private val unsettledReceipts = LinkedHashMap<String, ValidatorReceipt>()
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

    /** El validador sube sus recibos al reconectar. Esto es lo que genera el cobro. */
    fun ingestValidatorBatch(validatorId: String, receipts: List<ValidatorReceipt>): List<String> {
        val accepted = mutableListOf<String>()
        for (r in receipts) {
            record(r.spend, source = "validator:$validatorId")
            if (r.receiptId !in settledReceiptIds) {
                unsettledReceipts[r.receiptId] = r
            }
            accepted += r.receiptId
        }
        return accepted
    }

    /** El pasajero sube sus gastos pendientes al reconectar. */
    fun ingestWalletSync(walletId: String, spends: List<SignedSpend>): SyncAck {
        val confirmed = mutableListOf<String>()
        for (s in spends) {
            if (s.token.walletId != walletId) continue
            record(s, source = "wallet:$walletId")
            confirmed += s.linkId()
        }
        val authoritative = spends.firstOrNull()?.token?.grantId?.let { grantId ->
            ledger.entries
                .filter { it.value.spend.token.grantId == grantId }
                .sumOf { it.value.spend.token.amountCentimos }
        }
        return SyncAck(
            confirmedLinkIds = confirmed,
            serverTimeEpochSec = clock.nowEpochSec(),
            authoritativeSpentCentimos = authoritative,
        )
    }

    /**
     * Registra un gasto y detecta bifurcaciones.
     *
     * Aqui esta el corazon del anti-doble-gasto: si ya existe un gasto DISTINTO
     * en la misma posicion (grantId, seq) de la cadena, es doble gasto, y las
     * dos firmas del propio usuario son la prueba.
     */
    private fun record(spend: SignedSpend, source: String) {
        val t = spend.token
        val key = "${t.grantId}#${t.seq}"
        val existing = ledger[key]
        if (existing == null) {
            ledger[key] = LedgerEntry(spend, mutableSetOf(source))
            return
        }
        existing.sources += source
        if (existing.spend.linkId() != spend.linkId()) {
            val already = _doubleSpends.any { it.grantId == t.grantId && it.seq == t.seq }
            if (!already) {
                _doubleSpends += DoubleSpendEvidence(
                    walletId = t.walletId,
                    grantId = t.grantId,
                    seq = t.seq,
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

        val byGrant = ledger.values.groupBy { it.spend.token.grantId }
        for ((grantId, entries) in byGrant) {
            val purse = purses[grantId]
            if (purse == null) {
                _chainIssues += ChainIssue(grantId, 0, "vale desconocido para el servidor")
                continue
            }

            val ordered = entries.map { it.spend }.sortedBy { it.token.seq }
            var expectedSeq = 1L
            var prevHash = Hash.ZERO_32
            var continuityKnown = true
            var running = 0L

            for (spend in ordered) {
                val t = spend.token
                if (t.seq != expectedSeq) {
                    // Un hueco no es fraude por si solo: puede ser un gasto que
                    // el pasajero aun no ha subido. Pero rompe la verificacion
                    // del encadenamiento hasta el siguiente eslabon conocido.
                    _chainIssues += ChainIssue(
                        grantId, expectedSeq,
                        "falta el movimiento #$expectedSeq (el siguiente subido es #${t.seq})",
                    )
                    continuityKnown = false
                }
                if (continuityKnown && !t.prevHash.contentEquals(prevHash)) {
                    _chainIssues += ChainIssue(grantId, t.seq, "el eslabon no apunta al anterior")
                }
                running += t.amountCentimos
                if (continuityKnown && t.balanceAfterCentimos != purse.amountCentimos - running) {
                    _chainIssues += ChainIssue(
                        grantId, t.seq,
                        "saldo declarado ${Money.format(t.balanceAfterCentimos)} != " +
                            Money.format(purse.amountCentimos - running),
                    )
                }
                // Tras un hueco se retoma la verificacion desde este eslabon.
                prevHash = spend.linkHash()
                continuityKnown = true
                expectedSeq = t.seq + 1
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
            val loss = evidence.branchB.token.amountCentimos
            val purse = purses[evidence.grantId]
            val fromEscrow = minOf(loss, purse?.escrowCentimos ?: 0)
            purse?.let { it.escrowCentimos -= fromEscrow }
            val remaining = loss - fromEscrow
            account.debtCentimos += remaining
            uncovered += remaining
        }

        return ReconciliationReport(
            processedSpends = ledger.size,
            doubleSpends = _doubleSpends.toList(),
            chainIssues = _chainIssues.toList(),
            newlyBlockedWallets = blocked,
            uncoveredCentimos = uncovered,
        )
    }

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
            purses[it.spend.token.grantId]?.let { p ->
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
