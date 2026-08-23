package ve.transporte.core.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Primitivas criptograficas del sistema.
 *
 * Curva: NIST P-256 (secp256r1). Firma: ECDSA con SHA-256, codificacion DER.
 * Se eligio P-256 porque el Keystore de Android la soporta con respaldo en
 * hardware (TEE/StrongBox) en practicamente todos los dispositivos desde API 23,
 * lo que permite que la clave privada del monedero NUNCA sea extraible, ni
 * siquiera con root.
 */
object Ec {
    const val CURVE = "secp256r1"
    const val SIGN_ALG = "SHA256withECDSA"

    private val rng = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(CURVE), rng)
        return gen.generateKeyPair()
    }

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance(SIGN_ALG).apply {
            initSign(privateKey)
            update(message)
        }.sign()

    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        try {
            Signature.getInstance(SIGN_ALG).apply {
                initVerify(publicKey)
                update(message)
            }.verify(signature)
        } catch (_: java.security.SignatureException) {
            // Firma malformada => invalida, no es una excepcion que deba propagarse.
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    /** Decodifica una clave publica en formato X.509 / SubjectPublicKeyInfo. */
    fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    /** Decodifica una clave privada en formato PKCS#8. Solo para pruebas y demos. */
    fun decodePrivateKey(encoded: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    fun randomNonce(bytes: Int = 16): String {
        val b = ByteArray(bytes)
        rng.nextBytes(b)
        return Base64Url.encode(b)
    }
}

object Hash {
    val ZERO_32: ByteArray = ByteArray(32)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach(md::update)
        return md.digest()
    }

    /**
     * Huella de una clave publica: SHA-256 sobre su codificacion X.509, en base64url.
     * Es el identificador estable con el que el servidor amarra un monedero a un
     * dispositivo concreto.
     */
    fun keyFingerprint(publicKeyEncoded: ByteArray): String =
        Base64Url.encode(sha256(publicKeyEncoded))
}

/** Base64 URL-safe sin relleno. Es lo que viaja dentro del QR. */
object Base64Url {
    private val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    private val dec = java.util.Base64.getUrlDecoder()

    fun encode(b: ByteArray): String = enc.encodeToString(b)
    fun decode(s: String): ByteArray = dec.decode(s)
}

/** Comparacion en tiempo constante, para no filtrar informacion por temporizacion. */
fun ByteArray.constantTimeEquals(other: ByteArray): Boolean =
    java.security.MessageDigest.isEqual(this, other)

/**
 * Abstraccion de firma. En el JVM/servidor la implementa [JvmSigner]; en Android
 * la implementa un firmante respaldado por el Keystore, de modo que el core no
 * toca nunca material de clave privada.
 */
interface Signer {
    /** Identificador de la clave (p. ej. "issuer-2026-01" o el id del monedero). */
    val keyId: String

    /** Clave publica en formato X.509, para publicarla o incrustarla en el QR. */
    val publicKeyEncoded: ByteArray

    fun sign(message: ByteArray): ByteArray
}

class JvmSigner(
    override val keyId: String,
    private val keyPair: KeyPair,
) : Signer {
    override val publicKeyEncoded: ByteArray get() = keyPair.public.encoded
    override fun sign(message: ByteArray): ByteArray = Ec.sign(keyPair.private, message)

    val fingerprint: String get() = Hash.keyFingerprint(publicKeyEncoded)

    companion object {
        fun generate(keyId: String): JvmSigner = JvmSigner(keyId, Ec.generateKeyPair())

        /**
         * Reconstruye un firmante a partir de claves ya codificadas.
         * Pensado para pruebas y para el modo demostracion: en produccion la
         * clave del emisor vive en un HSM y nunca se materializa asi.
         */
        fun fromEncoded(keyId: String, pkcs8Private: ByteArray, x509Public: ByteArray): JvmSigner =
            JvmSigner(
                keyId,
                KeyPair(Ec.decodePublicKey(x509Public), Ec.decodePrivateKey(pkcs8Private)),
            )
    }
}
