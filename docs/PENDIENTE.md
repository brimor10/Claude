# Lo que falta para producción

Ordenado por lo que bloquea de verdad.

## Bloqueante

1. **Compilar el módulo `:app`.** Nunca se compiló: el entorno de desarrollo no
   tenía SDK de Android ni acceso a `dl.google.com`. Ábrelo en Android Studio y
   corrige lo que salte. La lógica de dinero está en `:core`, que sí está probado.

2. **El backend real.** Existe `SettlementServer` como implementación de
   referencia, con la lógica de dinero y de fraude completa y probada, pero en
   memoria. Falta: persistencia, autenticación de usuarios, API HTTP, y la
   implementación de `BackendApi` en la app contra esa API.

3. **La clave del emisor en un HSM.** Hoy la de demostración está escrita en el
   código a propósito, para poder probar. Esa clave es el sistema entero: quien la
   tenga puede emitir todo el saldo que quiera. Va en un HSM, con separación de
   funciones y registro de cada emisión.

4. **Poner las claves reales en `TrustAnchors`.** Mientras diga
   `REEMPLAZAR_CON_LA_CLAVE_PUBLICA_REAL`, la app arranca en modo demostración.

5. **Cobro real y pago real en bolívares.** El servidor calcula cuánto se le debe
   a cada dueño; falta conectar la pasarela que cobra la recarga y la que ejecuta
   la transferencia.

## Importante

6. **Validar la atestación de clave en el servidor.** La app ya envía la cadena de
   certificados del Keystore al dar de alta. Falta validarla contra la raíz de
   Google: que la clave sea de hardware y que el arranque esté bloqueado. Es lo
   que deja fuera emuladores y granjas de teléfonos falsos.

7. **Persistencia del validador.** Hoy `OfflineValidator` guarda recibos y gastos
   vistos en memoria: si se reinicia el aparato, se pierden los recibos sin subir
   y se olvida qué QR ya cobró. Necesita una base de datos local (Room o SQLite),
   podable por la fecha de vencimiento de los vales.

8. **Sincronización en segundo plano.** Con `WorkManager`, para que subir gastos y
   bajar la lista negra ocurra solo en cuanto haya señal, sin que el usuario tenga
   que apretar un botón.

9. **Topes por perfil de riesgo.** Hoy los topes offline son globales. Deberían
   depender del historial del usuario y del nivel de seguridad de su equipo
   (StrongBox, TEE o software). `KeystoreSigner.securityLevel` ya lo expone.

10. **Cierre de vales y devolución.** Qué pasa con el saldo de un vale que vence
    sin gastarse: hoy queda retenido. Hace falta la política y su implementación.

## Deseable

11. **NFC además de QR.** Un toque en vez de dos escaneos. El protocolo no cambia:
    el reto y el pago viajan igual, solo cambia el medio.

12. **Recarga en taquilla sin internet del lado del usuario.** Ya está el
    `QrEnvelope.encode(SignedGrant)`: una taquilla con conexión puede entregar el
    vale por QR a un teléfono sin datos. Falta la pantalla.

13. **Panel para el dueño de la unidad.** Ver pasajes del día, acumulado y
    liquidaciones.

14. **Compartir la lista negra entre validadores cercanos** (Bluetooth / Wi-Fi
    Direct), para que un monedero bloqueado se propague sin esperar a que cada
    aparato tenga señal.

15. **Auditoría de seguridad externa** antes de mover dinero real.
