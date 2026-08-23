package ve.transporte.panapago.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import ve.transporte.core.wallet.PurseState
import ve.transporte.core.wallet.WalletStorageCodec
import ve.transporte.core.wallet.WalletStorageException
import java.io.File

/**
 * Guarda el monedero en disco, cifrado con AES-256-GCM y clave del Keystore.
 *
 * El cifrado protege contra lectura y manipulacion del archivo. NO protege
 * contra que el dueno del telefono guarde una copia y la restaure para revivir
 * saldo gastado: contra eso actua la cadena hash y la reconciliacion del
 * servidor. Por eso ademas el respaldo de Android esta desactivado en el
 * manifiesto.
 *
 * La escritura es atomica (archivo temporal + rename) para que un apagon o un
 * cierre forzado no deje el monedero a medio escribir.
 */
class WalletRepository(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val walletFile: File get() = File(context.filesDir, FILE_NAME)

    fun load(): List<PurseState> {
        val file = walletFile
        if (!file.exists()) return emptyList()
        return try {
            val bytes = encryptedFile(file).openFileInput().use { it.readBytes() }
            WalletStorageCodec.decode(bytes)
        } catch (e: WalletStorageException) {
            // El archivo esta corrupto. NO se borra: se aparta para poder
            // reclamarle al servidor, que es quien tiene la verdad del saldo.
            Log.e(TAG, "monedero ilegible, se aparta para revision", e)
            file.renameTo(File(context.filesDir, "$FILE_NAME.roto"))
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "no se pudo leer el monedero", e)
            emptyList()
        }
    }

    fun save(states: List<PurseState>) {
        val bytes = WalletStorageCodec.encode(states)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        if (temp.exists()) temp.delete()
        encryptedFile(temp).openFileOutput().use { it.write(bytes) }
        val target = walletFile
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            throw IllegalStateException("no se pudo guardar el monedero")
        }
    }

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    companion object {
        private const val TAG = "WalletRepository"
        private const val FILE_NAME = "monedero.bin"
    }
}
