package ve.transporte.core.qr

import ve.transporte.core.crypto.Base64Url
import ve.transporte.core.protocol.PurseGrant
import ve.transporte.core.protocol.SignedChallenge
import ve.transporte.core.protocol.SignedGrant
import ve.transporte.core.protocol.SignedSpend
import ve.transporte.core.protocol.SignedValidatorCert
import ve.transporte.core.protocol.SpendToken
import ve.transporte.core.protocol.ValidatorCert
import ve.transporte.core.protocol.ValidatorChallenge
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class QrFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Escritor de trama de transporte: campos con prefijo de longitud. */
private class FrameWriter {
    private val out = ByteArrayOutputStream(512)
    fun bytes(v: ByteArray) = apply {
        out.write(ByteBuffer.allocate(4).putInt(v.size).array()); out.write(v)
    }

    fun str(v: String) = bytes(v.toByteArray(StandardCharsets.UTF_8))
    fun long(v: Long) = bytes(ByteBuffer.allocate(8).putLong(v).array())
    fun int(v: Int) = long(v.toLong())
    fun build(): ByteArray = out.toByteArray()
}

private class FrameReader(private val buf: ByteArray) {
    private var pos = 0

    fun bytes(): ByteArray {
        if (pos + 4 > buf.size) throw QrFormatException("trama truncada en el prefijo de longitud")
        val len = ByteBuffer.wrap(buf, pos, 4).int
        pos += 4
        if (len < 0 || pos + len > buf.size) throw QrFormatException("longitud de campo invalida: $len")
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun str(): String = String(bytes(), StandardCharsets.UTF_8)

    fun long(): Long {
        val b = bytes()
        if (b.size != 8) throw QrFormatException("entero mal formado (${b.size} bytes)")
        return ByteBuffer.wrap(b).long
    }

    fun int(): Int {
        val v = long()
        if (v > Int.MAX_VALUE || v < Int.MIN_VALUE) throw QrFormatException("entero fuera de rango: $v")
        return v.toInt()
    }

    fun end() {
        if (pos != buf.size) throw QrFormatException("bytes sobrantes en la trama (${buf.size - pos})")
    }
}

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
        } catch (e: QrFormatException) {
            throw e
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
        } catch (e: QrFormatException) {
            throw e
        } catch (e: Exception) {
            throw QrFormatException("vale de gasto ilegible", e)
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
