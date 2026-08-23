package ve.transporte.core.wallet

import ve.transporte.core.protocol.FrameFormatException
import ve.transporte.core.protocol.FrameReader
import ve.transporte.core.protocol.FrameWriter
import ve.transporte.core.qr.QrEnvelope

class WalletStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Serializa el estado del monedero para guardarlo en disco.
 *
 * En Android estos bytes se escriben con EncryptedFile (AES-256-GCM, clave en
 * el Keystore), asi que en reposo van cifrados y autenticados.
 *
 * Aviso importante: el cifrado en reposo protege contra que alguien LEA o
 * MANIPULE el archivo. NO protege contra que el dueno del telefono guarde una
 * copia del archivo cifrado y la restaure despues para revivir saldo ya
 * gastado. Contra eso lo que actua es la cadena hash mas la reconciliacion del
 * servidor, no el cifrado.
 */
object WalletStorageCodec {
    private const val VERSION = 1

    fun encode(states: List<PurseState>): ByteArray {
        val w = FrameWriter().int(VERSION).int(states.size)
        for (s in states) {
            w.str(QrEnvelope.encode(s.signedGrant))
            w.long(s.nextSeq)
            w.bytes(s.lastLinkHash)
            w.long(s.spentCentimos)
            w.long(s.offlineSpentCentimos)
            w.int(s.offlineTrips)
            w.long(s.trustedTimeFloorEpochSec)
            w.int(s.pending.size)
            s.pending.forEach { w.str(QrEnvelope.encode(it)) }
            w.int(s.usedChallengeNonces.size)
            s.usedChallengeNonces.forEach { w.str(it) }
        }
        return w.build()
    }

    fun decode(bytes: ByteArray): List<PurseState> {
        val r = FrameReader(bytes)
        return try {
            val version = r.int()
            if (version != VERSION) throw WalletStorageException("version de almacenamiento $version no soportada")
            val count = r.int()
            if (count < 0 || count > 10_000) throw WalletStorageException("numero de vales absurdo: $count")
            val out = ArrayList<PurseState>(count)
            repeat(count) {
                val grant = QrEnvelope.decodeGrant(r.str())
                val nextSeq = r.long()
                val lastLinkHash = r.bytes()
                val spent = r.long()
                val offlineSpent = r.long()
                val offlineTrips = r.int()
                val floor = r.long()
                val pending = (0 until r.int()).map { QrEnvelope.decodeSpend(r.str()) }
                val nonces = (0 until r.int()).map { r.str() }.toSet()
                out += PurseState(
                    signedGrant = grant,
                    nextSeq = nextSeq,
                    lastLinkHash = lastLinkHash,
                    spentCentimos = spent,
                    offlineSpentCentimos = offlineSpent,
                    offlineTrips = offlineTrips,
                    pending = pending,
                    usedChallengeNonces = nonces,
                    trustedTimeFloorEpochSec = floor,
                )
            }
            r.end()
            out
        } catch (e: WalletStorageException) {
            throw e
        } catch (e: FrameFormatException) {
            throw WalletStorageException("estado del monedero ilegible: ${e.message}", e)
        } catch (e: Exception) {
            throw WalletStorageException("estado del monedero ilegible", e)
        }
    }
}
