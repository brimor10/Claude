// Los plugins de Android se declaran directamente en :app para que este build
// no necesite descargar AGP cuando el modulo :app esta desactivado (sin Android SDK).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
