package ve.transporte.core.wallet

import ve.transporte.core.protocol.SignedChallenge
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedSpend

/**
 * Conjunto de monederos (vales) del pasajero.
 *
 * Cada recarga genera un vale nuevo con su propia cadena de gastos. Se hace asi
 * porque revocar un vale anterior seria poco fiable sin internet: es mas simple
 * y mas seguro que los vales convivan y se vayan consumiendo.
 *
 * Regla de consumo: primero el que vence antes (FEFO), para que al usuario no se
 * le caduque saldo teniendo otro mas nuevo sin tocar.
 */
class WalletBook(
    private val engine: OfflineWallet,
    initialStates: List<PurseState> = emptyList(),
) {
    private val purses = initialStates.toMutableList()

    val deviceFingerprint: String get() = engine.deviceFingerprint

    fun states(): List<PurseState> = purses.toList()

    val totalBalanceCentimos: Long get() = purses.sumOf { it.balanceCentimos }

    /** Lo que realmente se puede gastar ahora mismo sin internet. */
    val offlineSpendableCentimos: Long
        get() = purses.sumOf { minOf(it.balanceCentimos, it.offlineRemainingCentimos) }

    val pendingSpends: List<SignedSpend> get() = purses.flatMap { it.pending }

    fun addGrant(signedGrant: SignedGrant): LoadGrantOutcome {
        val existing = purses.find { it.signedGrant.grant.grantId == signedGrant.grant.grantId }
        if (existing != null) return LoadGrantOutcome.Loaded(existing)
        return when (val r = engine.loadGrant(signedGrant, purses.maxByOrNull { it.trustedTimeFloorEpochSec })) {
            is LoadGrantOutcome.Loaded -> {
                purses += r.state
                r
            }

            is LoadGrantOutcome.Rejected -> r
        }
    }

    /**
     * Paga un pasaje. Un pasaje se cubre con UN solo vale (el vale de gasto
     * pertenece a una cadena); no se parte entre varios.
     */
    fun pay(challenge: SignedChallenge): SpendOutcome {
        if (purses.isEmpty()) {
            return SpendOutcome.Denied(DenyReason.SIN_SALDO_CARGADO, "no hay saldo recargado")
        }
        val denials = mutableListOf<SpendOutcome.Denied>()
        val candidates = purses.sortedBy { it.signedGrant.grant.expiresAtEpochSec }
        for (state in candidates) {
            when (val outcome = engine.pay(state, challenge)) {
                is SpendOutcome.Approved -> {
                    purses[purses.indexOf(state)] = outcome.newState
                    return outcome
                }

                is SpendOutcome.Denied -> denials += outcome
            }
        }
        return bestDenial(denials)
    }

    fun applySync(ack: SyncAck) {
        for (i in purses.indices) {
            purses[i] = engine.applySync(purses[i], ack)
        }
        // Un vale totalmente gastado y sincronizado ya no ocupa espacio.
        purses.removeAll { it.balanceCentimos == 0L && it.pending.isEmpty() }
    }

    /**
     * Elige el motivo mas util para mostrarle al usuario: los problemas de
     * configuracion/seguridad pesan mas que "este vale no alcanzaba".
     */
    private fun bestDenial(denials: List<SpendOutcome.Denied>): SpendOutcome.Denied {
        val priority = listOf(
            DenyReason.CERTIFICADO_DE_VALIDADOR_INVALIDO,
            DenyReason.CERTIFICADO_NO_CORRESPONDE_AL_VALIDADOR,
            DenyReason.CERTIFICADO_DE_VALIDADOR_CADUCADO,
            DenyReason.FIRMA_DEL_RETO_INVALIDA,
            DenyReason.RETO_CADUCADO,
            DenyReason.RETO_YA_USADO,
            DenyReason.PASAJE_INVALIDO,
            DenyReason.TOPE_DE_VIAJES_OFFLINE_ALCANZADO,
            DenyReason.TOPE_OFFLINE_ALCANZADO,
            DenyReason.SALDO_INSUFICIENTE,
        )
        return priority.firstNotNullOfOrNull { reason -> denials.find { it.reason == reason } }
            ?: denials.firstOrNull()
            ?: SpendOutcome.Denied(DenyReason.SIN_SALDO_CARGADO, "no hay vale utilizable")
    }
}
