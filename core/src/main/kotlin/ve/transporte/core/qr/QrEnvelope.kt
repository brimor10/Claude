package ve.transporte.core.qr

import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.protocol.FrameFormatException
import ve.transporte.core.protocol.FrameReader
import ve.transporte.core.protocol.FrameWriter
import ve.transporte.core.protocol.PurseGrant
import ve.transporte.core.protocol.SignedChallenge
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.SpendToken
import ve.transporte.core.protocol.ValidatorCert
import ve.transporte.core.protocol.ValidatorChallenge

class QrFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Codificacion de los mensajes que viajan por QR.
 *
 * Formato: `PP1:<tipo>:<base64url(trama)>`
 *
 * El prefijo permite que la camara descarte de inmediato cualquier QR ajeno
 * (un QR de pago movil, un enlace, etc.) sin intentar parsearlo.
 */
object QrEnvelope {
    const val PREFIX = "PP1"
    const val TYPE_CHALLENGE = "CHL"
    const val TYPE_SPEND = "SPD"

    /** Vale de saldo suelto: permite recargar en una taquilla que si tiene internet. */
    const val TYPE_GRANT = "GRT"

    // -- Reto del validador -------------------------------------------------

    fun encode(signed: SignedChallenge): String {
        val c = signed.challenge
        val frame = FrameWriter()
            .str(c.validatorId).str(c.unitId).str(c.ownerId).str(c.routeId)
            .long(c.fareCentimos).str(c.challengeNonce)
            .long(c.issuedAtEpochSec).int(c.ttlSeconds).str(c.validatorKeyId)
            .bytes(signed.signature).bytes(signed.validatorPublicKey)
            // certificado del validador, emitido por el servidor
            .str(signed.cert.cert.validatorId).str(signed.cert.cert.unitId)
            .str(signed.cert.cert.ownerId).str(signed.cert.cert.publicKeyFingerprint)
            .long(signed.cert.cert.issuedAtEpochSec).long(signed.cert.cert.expiresAtEpochSec)
            .str(signed.cert.cert.issuerKeyId).bytes(signed.cert.signature)
            .build()
        return "$PREFIX:$TYPE_CHALLENGE:${Base64Url.encode(frame)}"
    }

    fun decodeChallenge(text: String): SignedChallenge {
        val r = FrameReader(payloadOf(text, TYPE_CHALLENGE))
        return try {
            val challenge = ValidatorChallenge(
                validatorId = r.str(), unitId = r.str(), ownerId = r.str(), routeId = r.str(),
                fareCentimos = r.long(), challengeNonce = r.str(),
                issuedAtEpochSec = r.long(), ttlSeconds = r.int(), validatorKeyId = r.str(),
            )
            val sig = r.bytes()
            val pub = r.bytes()
            val cert = ValidatorCert(
                validatorId = r.str(), unitId = r.str(), ownerId = r.str(),
                publicKeyFingerprint = r.str(),
                issuedAtEpochSec = r.long(), expiresAtEpochSec = r.long(),
                issuerKeyId = r.str(),
            )
            val certSig = r.bytes()
            r.end()
            SignedChallenge(challenge, sig, pub, SignedValidatorCert(cert, certSig))
        } catch (e: FrameFormatException) {
            throw QrFormatException("reto ilegible: ${e.message}", e)
        } catch (e: Exception) {
            throw QrFormatException("reto ilegible", e)
        }
    }

    // -- Vale de gasto ------------------------------------------------------

    fun encode(signed: SignedSpend): String {
        val t = signed.token
        val g = signed.grant.grant
        val frame = FrameWriter()
            // vale de saldo (firmado por el emisor)
            .str(g.grantId).str(g.walletId).str(g.deviceKeyFingerprint)
            .long(g.amountCentimos).str(g.currency)
            .long(g.issuedAtEpochSec).long(g.expiresAtEpochSec)
            .long(g.offlineSpendCapCentimos).int(g.offlineTripCap)
            .str(g.issuerKeyId).str(g.nonce)
            .bytes(signed.grant.signature)
            // gasto (firmado por el dispositivo)
            .long(t.seq).long(t.amountCentimos).long(t.balanceAfterCentimos)
            .bytes(t.prevHash)
            .str(t.validatorId).str(t.unitId).str(t.ownerId).str(t.routeId)
            .str(t.challengeNonce).long(t.spentAtEpochSec)
            .bytes(signed.signature).bytes(signed.devicePublicKey)
            .build()
        return "$PREFIX:$TYPE_SPEND:${Base64Url.encode(frame)}"
    }

    fun decodeSpend(text: String): SignedSpend {
        val r = FrameReader(payloadOf(text, TYPE_SPEND))
        return try {
            val grant = PurseGrant(
                grantId = r.str(), walletId = r.str(), deviceKeyFingerprint = r.str(),
                amountCentimos = r.long(), currency = r.str(),
                issuedAtEpochSec = r.long(), expiresAtEpochSec = r.long(),
                offlineSpendCapCentimos = r.long(), offlineTripCap = r.int(),
                issuerKeyId = r.str(), nonce = r.str(),
            )
            val grantSig = r.bytes()
            val token = SpendToken(
                grantId = grant.grantId,
                walletId = grant.walletId,
                seq = r.long(),
                amountCentimos = r.long(),
                balanceAfterCentimos = r.long(),
                prevHash = r.bytes(),
                validatorId = r.str(), unitId = r.str(), ownerId = r.str(), routeId = r.str(),
                challengeNonce = r.str(),
                spentAtEpochSec = r.long(),
                deviceKeyFingerprint = grant.deviceKeyFingerprint,
            )
            val spendSig = r.bytes()
            val devicePub = r.bytes()
            r.end()
            SignedSpend(token, spendSig, SignedGrant(grant, grantSig), devicePub)
        } catch (e: FrameFormatException) {
            throw QrFormatException("vale de gasto ilegible: ${e.message}", e)
        } catch (e: Exception) {
            throw QrFormatException("vale de gasto ilegible", e)
        }
    }

    // -- Vale de saldo suelto ------------------------------------------------

    fun encode(signed: SignedGrant): String {
        val g = signed.grant
        val frame = FrameWriter()
            .str(g.grantId).str(g.walletId).str(g.deviceKeyFingerprint)
            .long(g.amountCentimos).str(g.currency)
            .long(g.issuedAtEpochSec).long(g.expiresAtEpochSec)
            .long(g.offlineSpendCapCentimos).int(g.offlineTripCap)
            .str(g.issuerKeyId).str(g.nonce)
            .bytes(signed.signature)
            .build()
        return "$PREFIX:$TYPE_GRANT:${Base64Url.encode(frame)}"
    }

    fun decodeGrant(text: String): SignedGrant {
        val r = FrameReader(payloadOf(text, TYPE_GRANT))
        return try {
            val grant = PurseGrant(
                grantId = r.str(), walletId = r.str(), deviceKeyFingerprint = r.str(),
                amountCentimos = r.long(), currency = r.str(),
                issuedAtEpochSec = r.long(), expiresAtEpochSec = r.long(),
                offlineSpendCapCentimos = r.long(), offlineTripCap = r.int(),
                issuerKeyId = r.str(), nonce = r.str(),
            )
            val sig = r.bytes()
            r.end()
            SignedGrant(grant, sig)
        } catch (e: FrameFormatException) {
            throw QrFormatException("vale de saldo ilegible: ${e.message}", e)
        } catch (e: Exception) {
            throw QrFormatException("vale de saldo ilegible", e)
        }
    }

    fun typeOf(text: String): String? {
        val parts = text.split(":", limit = 3)
        return if (parts.size == 3 && parts[0] == PREFIX) parts[1] else null
    }

    private fun payloadOf(text: String, expectedType: String): ByteArray {
        val parts = text.trim().split(":", limit = 3)
        if (parts.size != 3 || parts[0] != PREFIX) {
            throw QrFormatException("no es un QR de Pana Pago")
        }
        if (parts[1] != expectedType) {
            throw QrFormatException("tipo de QR inesperado: ${parts[1]} (se esperaba $expectedType)")
        }
        return try {
            Base64Url.decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw QrFormatException("base64 invalido en el QR", e)
        }
    }
}
