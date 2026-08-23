# Arquitectura

## Los tres actores

```
   ┌──────────────┐   recarga (online)   ┌──────────────┐
   │   SERVIDOR   │─────────────────────>│   PASAJERO   │
   │  (emisor)    │   vale firmado       │  (monedero)  │
   └──────────────┘                      └──────────────┘
        ▲   ▲                                   │  ▲
        │   │                                   │  │  1. reto (QR del cobrador)
        │   │ recibos (online)                  │  │  2. pago  (QR del pasajero)
        │   │                                   ▼  │     ← ambos SIN INTERNET →
        │   │                            ┌──────────────┐
        │   └────────────────────────────│  VALIDADOR   │
        │       liquidación en Bs        │  (la unidad) │
        └───────────────────────────────>└──────────────┘
                    al dueño
```

## Los cinco mensajes

| Mensaje | Lo firma | Para qué |
|---|---|---|
| `PurseGrant` | Emisor | El saldo bloqueado. Amarrado a la clave del teléfono. |
| `ValidatorCert` | Emisor | Acredita al validador. Va dentro de su QR. |
| `ValidatorChallenge` | Validador | El cobro: monto, número de un solo uso, caducidad. |
| `SpendToken` | Teléfono | El pago. Lleva número de secuencia y hash del anterior. |
| `ValidatorReceipt` | — | Lo que el validador sube para cobrar en bolívares. |

Cada uno se firma sobre su forma canónica con etiqueta de dominio propia
(`protocol/Canonical.kt`), con ECDSA P-256 y SHA-256.

## La cadena de gastos

```
vale (500,00 Bs, firmado por el emisor)
  │
  ├─ gasto #1  prevHash=000…  quedan 495,00   firmado por el teléfono
  │      │
  │      └─ hash del eslabón ─┐
  │                           ▼
  ├─ gasto #2  prevHash=<hash #1>  quedan 490,00
  │      │
  │      └─ hash del eslabón ─┐
  │                           ▼
  └─ gasto #3  prevHash=<hash #2>  quedan 485,00
```

Restaurar un respaldo y emitir otro gasto #2 distinto crea una **bifurcación**:
dos vales firmados por el mismo usuario en la misma posición. El servidor la
detecta en `SettlementServer.record()` y arma el expediente.

## Qué necesita internet y qué no

| Operación | Internet |
|---|---|
| Dar de alta el monedero | Sí |
| Recargar | Sí |
| **Pagar el pasaje** | **No** |
| **Cobrar y verificar** | **No** |
| Sincronizar gastos / liberar cupo offline | Sí |
| Subir recibos y cobrar en Bs | Sí |
| Bajar la lista negra | Sí |

## Estructura del código

```
core/                                    Kotlin puro, sin Android. Probado.
  crypto/     Crypto.kt        ECDSA P-256, hashes, huellas de clave
              TrustStore.kt    Claves del emisor, reloj inyectable
  protocol/   Canonical.kt     Serialización canónica para firmar
              Frame.kt         Trama binaria para transporte y disco
              Model.kt         Los cinco mensajes + aritmética de dinero
  qr/         QrEnvelope.kt    Codificación de los QR (PP1:TIPO:base64url)
  wallet/     OfflineWallet.kt Motor del monedero: reglas de pago
              WalletBook.kt    Varios vales a la vez (FEFO)
              WalletStorageCodec.kt  Serialización para disco cifrado
  validator/  OfflineValidator.kt    Motor del validador: verificación offline
  server/     SettlementServer.kt    Emisión, reconciliación, liquidación

app/                                     Android + Compose. SIN COMPILAR.
  security/   KeystoreSigner.kt  Clave en hardware, no extraíble
              TrustAnchors.kt    Claves del emisor empotradas (pinning)
  data/       WalletRepository.kt  EncryptedFile, escritura atómica
  net/        BackendApi.kt      Lo único que necesita red
              DemoBackend.kt     Servidor de juguete, para probar sin backend
  ui/         Pantallas de pasajero y de cobrador, cámara y QR
```

## Decisiones y por qué

**Varios vales a la vez, no uno solo.** Cada recarga crea un vale independiente
con su propia cadena. La alternativa —reemplazar el vale anterior— exigiría
revocarlo, y revocar algo sin internet es poco fiable. Se consume primero el que
vence antes, para que al usuario no se le caduque saldo teniendo otro sin tocar.

**El reto va antes del pago, no al revés.** El validador muestra su QR primero.
Son dos escaneos en vez de uno, pero es lo que hace que un pantallazo no sirva.
Sin eso, todo el esquema se cae.

**El vale de saldo viaja dentro del QR de pago.** Así el validador puede
verificar sin haber visto nunca a ese pasajero y sin consultar nada. Medido: el
QR del cobrador son 667 caracteres y el de pago 782 — entran cómodos en un QR
estándar (versión 20 con corrección M admite ~1000 bytes). Hay una prueba que
vigila que no se pasen.

**El dueño cobra por recibos, no por lo que diga el pasajero.** El dinero se
mueve cuando el validador sube sus recibos firmados. El teléfono del pasajero
también sube los suyos, y comparar ambas vías es lo que detecta las discrepancias.
