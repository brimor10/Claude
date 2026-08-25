# Pana Pago — pago de transporte público que funciona sin internet

Saldo prepago que se recarga **con** internet, queda bloqueado en el teléfono y
se gasta **sin** internet mediante QR. El descuento ocurre en el momento, offline.
Cuando vuelve la señal, todo se sincroniza y al dueño de la unidad se le paga en
bolívares.

## Estado del proyecto

| Módulo | Qué es | Estado |
|---|---|---|
| `:core` | Protocolo, criptografía, monedero, validador y servidor de liquidación. Kotlin puro, sin Android. | **Compila y pasa 47 pruebas** |
| `web/` | Demostración operable en el navegador: el mismo protocolo en JavaScript. | **Probada en Chromium de punta a punta** |
| `:app` | App Android (Compose): modo pasajero y modo cobrador. | **Escrito, sin compilar** — ver aviso abajo |

> **Aviso honesto:** el módulo `:app` se escribió pero **no se ha compilado ni
> ejecutado nunca**. El entorno donde se desarrolló no tiene el SDK de Android y
> `dl.google.com` está bloqueado por política de red, así que ni el SDK ni las
> dependencias de AndroidX eran alcanzables. Espera errores de compilación al
> abrirlo por primera vez en Android Studio. Toda la lógica de dinero y de
> seguridad vive en `:core`, que sí está compilado y probado.

## Verlo funcionando

Abre `web/demo.html` en cualquier navegador (o publícalo donde quieras: es un
solo archivo autocontenido). Puedes recargar saldo, **cortar el internet**,
cobrar y pagar por QR, y después reconectar y ver la liquidación en bolívares.
Hay cuatro botones para tratar de robarlo.

No es una maqueta:

- Los QR son códigos reales, con un generador verificado contra `segno` en las
  40 versiones y leído sin fallos por `zxing-cpp`, el motor que usa Android.
  Puedes apuntarle con la cámara del teléfono.
- Las firmas son ECDSA P-256 con SHA-256 hechas con la criptografía del
  navegador, y la serialización es **idéntica byte a byte** a la de la app: la
  prueba `CompatibilidadWebTest` toma un escenario firmado por el JavaScript y
  lo valida con el código Kotlin de producción.

Lo único de mentira es el emisor, que en la página vive dentro del navegador con
la clave privada a la vista.

## Cómo correr las pruebas

```bash
./gradlew :core:test          # el protocolo: 47 pruebas
python3 tools/verificar-qr.py # el generador de QR
node tools/probar-demo.mjs    # la demostración web, en Chromium
```

Las dos últimas necesitan dependencias que se listan en
[`tools/LEEME.md`](tools/LEEME.md).

No hace falta el SDK de Android: `settings.gradle.kts` desactiva el módulo `:app`
cuando no encuentra `ANDROID_HOME`, `ANDROID_SDK_ROOT` ni `sdk.dir` en
`local.properties`. Con el SDK instalado, `./gradlew build` construye también la app.

## El flujo, en cuatro pasos

**1. Recargar (necesita internet).** El servidor cobra la recarga, retiene el
dinero en garantía y le entrega al teléfono un **vale de saldo** firmado, amarrado
a la clave de ese teléfono en concreto.

**2. Pagar (sin internet).** Dos formas:

- **Escaneando** (dos lecturas): la unidad muestra un QR de cobro con un número
  de un solo uso; el pasajero lo escanea y responde con el suyo. Un pantallazo
  no sirve jamás.
- **Cobro directo** (una lectura): el pasajero enseña su QR y el lector de la
  unidad lo lee. Más rápido en la puerta, a cambio de que el pago solo vale 90
  segundos y dentro de esa ventana puede colar en dos unidades. El costo está
  medido y probado; ver `docs/SEGURIDAD.md`.

**3. Verificar (sin internet).** El cobrador escanea y verifica ahí mismo: que el
saldo lo firmó el servidor, que el vale es de ese teléfono, que el pago responde
a *su* cobro y que las cuentas cuadran. El saldo ya quedó descontado en el
teléfono del pasajero.

**4. Liquidar (cuando vuelve la señal).** Ambos suben lo suyo. El servidor
reconcilia, detecta fraudes y le paga al dueño de la unidad en bolívares.

## Documentación

- [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md) — cómo está armado y por qué.
- [`docs/SEGURIDAD.md`](docs/SEGURIDAD.md) — modelo de amenazas, qué está resuelto
  y qué **no** se puede resolver. Léelo antes de tomar decisiones de negocio.
- [`docs/PENDIENTE.md`](docs/PENDIENTE.md) — lo que falta para producción.
- [`tools/LEEME.md`](tools/LEEME.md) — cómo se verifica que la demostración web
  no miente.
- [`docs/RESUMEN-PARA-REVISION.md`](docs/RESUMEN-PARA-REVISION.md) — explicación
  completa y autocontenida, para pasársela a un revisor externo: qué se pidió,
  cómo funciona, qué está probado, qué no está resuelto, y el mapa del asunto
  regulatorio en Venezuela.

## Lo que hay que saber antes de seguir

1. **El saldo fantasma está resuelto.** Nadie puede inventar saldo sin la clave
   privada del emisor, que vive en el servidor (en producción, en un HSM).

2. **El doble gasto offline no se puede impedir del todo, y quien diga lo
   contrario está vendiendo humo.** Un teléfono rooteado puede restaurar un
   respaldo y gastar dos veces. Lo que sí se hace: detectarlo con prueba
   criptográfica firmada por el propio defraudador, bloquearlo, cobrarle, y
   **acotar la pérdida máxima** con topes de gasto offline. Todo eso está
   implementado y probado. Los detalles y los números están en
   `docs/SEGURIDAD.md`.

3. **Modo demostración.** Mientras no exista el servidor, la app arranca con un
   backend interno cuya clave de emisor está escrita en el código. Sirve para
   probar el flujo con dos teléfonos; **no sirve para dinero real** y la app lo
   grita en pantalla.
