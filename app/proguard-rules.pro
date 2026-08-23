# El core no usa reflexion, pero se conservan los nombres para que los
# expedientes de fraude y los registros sean legibles en produccion.
-keep class ve.transporte.core.protocol.** { *; }
-keepattributes SourceFile,LineNumberTable
