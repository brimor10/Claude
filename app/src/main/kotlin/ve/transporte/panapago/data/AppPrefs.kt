package ve.transporte.panapago.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Ajustes pequenos, cifrados en reposo (identificadores, no dinero). */
class AppPrefs(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "pana-pago-prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var walletId: String?
        get() = prefs.getString(KEY_WALLET_ID, null)
        set(value) = prefs.edit().putString(KEY_WALLET_ID, value).apply()

    var validatorId: String?
        get() = prefs.getString(KEY_VALIDATOR_ID, null)
        set(value) = prefs.edit().putString(KEY_VALIDATOR_ID, value).apply()

    var unitId: String?
        get() = prefs.getString(KEY_UNIT_ID, null)
        set(value) = prefs.edit().putString(KEY_UNIT_ID, value).apply()

    var ownerId: String?
        get() = prefs.getString(KEY_OWNER_ID, null)
        set(value) = prefs.edit().putString(KEY_OWNER_ID, value).apply()

    private companion object {
        const val KEY_WALLET_ID = "wallet_id"
        const val KEY_VALIDATOR_ID = "validator_id"
        const val KEY_UNIT_ID = "unit_id"
        const val KEY_OWNER_ID = "owner_id"
    }
}
