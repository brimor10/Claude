package ve.transporte.panapago.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import ve.transporte.core.crypto.Hash
import ve.transporte.core.crypto.Signer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Firmante respaldado por el Keystore de Android.
 *
 * La clave privada del monedero se genera DENTRO del elemento seguro y nunca
 * sale de ahi: la app no la ve, y con root tampoco se puede extraer. Lo unico
 * que se puede hacer con ella es pedirle firmas.
 *
 * Esto es lo que hace que un vale de saldo se pueda amarrar a UN telefono: si
 * alguien copia el archivo del monedero a otro aparato, ese aparato no puede
 * producir firmas que el validador acepte.
 *
 * Cuando el equipo tiene StrongBox (chip de seguridad dedicado, API 28+) se usa;
 * si no, cae al TEE. Ambos casos quedan reflejados en [securityLevel] para poder
 * aplicar politicas distintas (p. ej. topes offline mas bajos sin StrongBox).
 */
class KeystoreSigner private constructor(
    override val keyId: String,
    private val privateKey: PrivateKey,
    override val publicKeyEncoded: ByteArray,
    val securityLevel: SecurityLevel,
    val attestationChain: List<Certificate>,
) : Signer {

    enum class SecurityLevel { STRONGBOX, TEE, SOFTWARE }

    val fingerprint: String by lazy { Hash.keyFingerprint(publicKeyEncoded) }

    override fun sign(message: ByteArray): ByteArray =
        Signature.getInstance(ALGORITHM).apply {
            initSign(privateKey)
            update(message)
        }.sign()

    companion object {
        private const val TAG = "KeystoreSigner"
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALGORITHM = "SHA256withECDSA"

        /**
         * Devuelve el firmante del monedero, creandolo la primera vez.
         *
         * @param attestationChallenge reto del servidor para la atestacion de
         *   clave. Al dar de alta el monedero, el servidor pide la cadena de
         *   certificados y comprueba contra la raiz de Google que la clave
         *   nacio de verdad en hardware y que el equipo no viene con el
         *   arranque desbloqueado. Es la barrera contra emuladores y granjas de
         *   telefonos falsos.
         */
        fun getOrCreate(alias: String, attestationChallenge: ByteArray? = null): KeystoreSigner {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }

            store.getEntry(alias, null)?.let { entry ->
                val privateEntry = entry as KeyStore.PrivateKeyEntry
                return KeystoreSigner(
                    keyId = alias,
                    privateKey = privateEntry.privateKey,
                    publicKeyEncoded = privateEntry.certificate.publicKey.encoded,
                    securityLevel = levelOf(privateEntry.privateKey),
                    attestationChain = privateEntry.certificateChain?.toList().orEmpty(),
                )
            }

            // Primero se intenta StrongBox; si el equipo no lo trae, se cae al TEE.
            val pair = try {
                generate(alias, attestationChallenge, strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                Log.i(TAG, "sin StrongBox, se usa el TEE", e)
                generate(alias, attestationChallenge, strongBox = false)
            }

            val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            return KeystoreSigner(
                keyId = alias,
                privateKey = pair.first,
                publicKeyEncoded = pair.second,
                securityLevel = levelOf(pair.first),
                attestationChain = entry.certificateChain?.toList().orEmpty(),
            )
        }

        private fun generate(
            alias: String,
            attestationChallenge: ByteArray?,
            strongBox: Boolean,
        ): Pair<PrivateKey, ByteArray> {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                // Sin autenticacion de usuario: el pago tiene que funcionar con
                // el telefono en la mano, rapido y sin internet. El control de
                // riesgo son los topes offline, no una huella por pasaje.
                .setUserAuthenticationRequired(false)
                .apply {
                    if (attestationChallenge != null) setAttestationChallenge(attestationChallenge)
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setIsStrongBoxBacked(true)
                    }
                }
                .build()

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            generator.initialize(spec)
            val kp = generator.generateKeyPair()
            return kp.private to kp.public.encoded
        }

        @Suppress("DEPRECATION")
        private fun levelOf(key: PrivateKey): SecurityLevel = try {
            val factory = java.security.KeyFactory.getInstance(key.algorithm, PROVIDER)
            val info = factory.getKeySpec(key, KeyInfo::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TEE
                    else -> SecurityLevel.SOFTWARE
                }
            } else {
                if (info.isInsideSecureHardware) SecurityLevel.TEE else SecurityLevel.SOFTWARE
            }
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo determinar el nivel de seguridad de la clave", e)
            SecurityLevel.SOFTWARE
        }
    }
}
