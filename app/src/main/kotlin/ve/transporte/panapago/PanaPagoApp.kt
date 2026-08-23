package ve.transporte.panapago

import android.app.Application
import ve.transporte.core.crypto.Clock
import ve.transporte.core.crypto.TrustStore
import ve.transporte.panapago.data.WalletRepository
import ve.transporte.panapago.net.BackendApi
import ve.transporte.panapago.net.DemoBackend
import ve.transporte.panapago.security.TrustAnchors

class PanaPagoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}

/**
 * Cableado de la app.
 *
 * Si [TrustAnchors] no trae claves reales configuradas, la app arranca en MODO
 * DEMOSTRACION con un backend interno. Es la unica forma de que el proyecto se
 * pueda ejecutar y probar antes de que exista el servidor.
 */
object AppContainer {
    lateinit var walletRepository: WalletRepository
        private set

    lateinit var backend: BackendApi
        private set

    lateinit var trustStore: TrustStore
        private set

    var isDemoMode: Boolean = false
        private set

    val clock: Clock = Clock.SYSTEM

    fun init(app: Application) {
        walletRepository = WalletRepository(app)
        if (TrustAnchors.isConfigured) {
            isDemoMode = false
            trustStore = TrustAnchors.trustStore()
            // Aqui va el cliente HTTP contra el backend real. Mientras no exista,
            // la app no debe fingir que funciona: se deja explicito.
            error(
                "Hay claves de emisor configuradas pero falta el cliente del backend real. " +
                    "Implementa BackendApi contra tu servidor y enchufalo aqui.",
            )
        } else {
            isDemoMode = true
            val demo = DemoBackend(clock)
            backend = demo
            trustStore = demo.trustStore
        }
    }

    val demoBackend: DemoBackend? get() = backend as? DemoBackend
}
