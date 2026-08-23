pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "pana-pago"

include(":core")

// El modulo :app requiere el Android SDK. Se incluye solo si ANDROID_HOME/ANDROID_SDK_ROOT
// esta configurado, para que `./gradlew :core:test` funcione en cualquier maquina (y en CI)
// sin necesidad de instalar el SDK de Android.
val androidSdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: file("local.properties").takeIf { it.exists() }
        ?.let { java.util.Properties().apply { it.inputStream().use(::load) }.getProperty("sdk.dir") }

if (!androidSdk.isNullOrBlank()) {
    include(":app")
} else {
    logger.lifecycle("[pana-pago] Android SDK no encontrado: se omite el modulo :app. Solo se construye :core.")
}
