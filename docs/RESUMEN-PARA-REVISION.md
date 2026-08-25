# Pana Pago — resumen completo para revisión externa

Documento autocontenido: explica qué se pidió, qué se construyó, cómo funciona,
qué está probado, qué **no** está resuelto y qué preguntas quedan abiertas.
Escrito para que alguien de fuera (otro asistente, un revisor técnico, un
abogado) pueda opinar sin haber visto el código.

Demostración operable en el navegador:
<https://claude.ai/code/artifact/7877490a-c4fb-4728-85d0-0c6313ce555b>

---

## 1. Qué se pidió

Una aplicación Android para pagar el pasaje del transporte público en Venezuela
que **funcione sin internet**, porque la gente muchas veces anda sin saldo de
datos. Los requisitos, tal como los planteó el cliente:

1. La **recarga de saldo** se hace cuando hay internet.
2. Ese saldo queda **bloqueado en el teléfono**.
3. Se gasta **por QR**, y el descuento ocurre en el momento, sin conexión.
4. Cuando vuelve el internet, el saldo ya está descontado y se sincroniza.
5. Al **dueño de la unidad de transporte le llega el dinero en bolívares**.
6. Requisito explícito de un socio: mucho cifrado y protocolos de seguridad,
   **"que no le vayan a agregar saldo fantasma"**.

Parámetros que dio el cliente: el pasaje ronda 0,30 US$, equivalente a unos
235 Bs (de ahí sale un cambio implícito de ~783 Bs/US$).

---

## 2. Qué se construyó

| Componente | Qué es | Estado |
|---|---|---|
| `core` (Kotlin, sin Android) | Protocolo, criptografía, monedero, validador y servidor de liquidación | **Compila, 52 pruebas en verde** |
| `web/` (JavaScript) | El mismo protocolo, para una demostración operable en el navegador | **Probado de punta a punta en Chromium** |
| `app` (Android, Compose) | App con modo pasajero y modo cobrador | **Escrito, NUNCA compilado** |

**Advertencia honesta sobre el módulo Android:** se escribió pero no se ha
compilado ni ejecutado ni una vez, porque el entorno de desarrollo no tenía el
SDK de Android y el acceso a los servidores de Google estaba bloqueado por
política de red. Hay que esperar errores de compilación al abrirlo. Toda la
lógica de dinero y de seguridad vive en `core`, que sí está compilado y probado.

---

## 3. Cómo funciona

### 3.1 Los tres actores

- **Servidor emisor.** Cobra las recargas, guarda el dinero en garantía, firma
  el saldo, reconcilia lo ocurrido sin conexión y paga a los dueños.
- **Teléfono del pasajero.** Guarda el saldo firmado y firma los pagos. Su clave
  privada se genera dentro del Keystore de Android (StrongBox si el equipo lo
  trae, si no el TEE) y **no es extraíble ni con root**.
- **Validador de la unidad.** El aparato del chofer. Cobra y verifica sin
  internet; sube los recibos cuando hay señal.

### 3.2 Los cinco mensajes firmados

| Mensaje | Lo firma | Para qué |
|---|---|---|
| `PurseGrant` (vale de saldo) | Emisor | El saldo. Amarrado a la huella de la clave de **un** teléfono. |
| `ValidatorCert` | Emisor | Acredita al validador. Viaja dentro de su QR. |
| `ValidatorChallenge` (reto) | Validador | El cobro: monto, nonce de un solo uso, caducidad 45 s. |
| `SpendToken` (gasto) | Teléfono | El pago. Lleva número de secuencia y hash del gasto anterior. |
| `ValidatorReceipt` | — | Lo que el validador sube para cobrar en bolívares. |

Firma: **ECDSA P-256 con SHA-256**. Se firma sobre una **serialización canónica**
propia (campos con prefijo de longitud, orden fijo, etiqueta de dominio por tipo
de mensaje), no sobre JSON. Motivo: dos serializadores JSON pueden producir bytes
distintos para el mismo objeto, y peor, se puede mover contenido de un campo a
otro sin cambiar los bytes, lo que abre ataques de confusión de firma.

### 3.3 El intercambio sin internet: dos modos

Hay **dos formas de cobrar**, y no son igual de seguras. La diferencia es lo
bastante importante como para que la decida el operador, unidad por unidad.

| | **Modo reto** (dos escaneos) | **Modo directo** (un escaneo) |
|---|---|---|
| Velocidad en la puerta | Dos lecturas | Una lectura |
| Pantallazo reusado | **Imposible** | Posible en otra unidad, dentro de una ventana de 30 s |
| Mismo QR en dos unidades | **Imposible** | Posible en la ventana; se detecta al reconciliar |
| Saldo inventado / de otro teléfono | Imposible | Imposible |
| Doble gasto por respaldo | Demostrable | Demostrable (misma cadena) |
| Descuento del saldo | Al pagar, sabiendo a quién | Al generar el QR, sin saber si lo leerán |

En el **modo directo** el teléfono firma sin saber todavía a qué unidad le paga,
así que el pago no puede nombrarla; lo único que lo acota es la ventana de
tiempo. Dentro de ella, la misma captura mostrada en dos unidades cuela las dos
veces. Al pasajero se le cobra un solo pasaje y el operador solo paga al primero
que lo reclame, pero un viaje se regala. Se detecta al reconciliar porque los
recibos van firmados por el validador: dos recibos apuntando al mismo eslabón
son un reclamo duplicado.

Además, en el modo directo el saldo se descuenta al generar el QR. Si nadie lo
lee, el servidor lo devuelve al reconciliar, tras un plazo de gracia.

### 3.3.1 El modo seguro: **dos escaneos con la cámara**

No hace falta Bluetooth, ni NFC, ni emparejar aparatos. Los dos teléfonos están
aislados todo el tiempo.

```
  VALIDADOR                                   PASAJERO
  ─────────                                   ────────
  1. Genera un reto firmado
     (monto, nonce único, vence en 45 s)
     y lo muestra como QR  ──────────────►  2. Lo escanea con la cámara.
                                               Verifica sin red:
                                               · el certificado del validador
                                                 está firmado por el emisor
                                               · la firma del reto es de ese
                                                 validador
                                               · tiene saldo y cupo offline
                                               Descuenta el saldo, firma el
                                               gasto y muestra su propio QR
  4. Lo escanea. Verifica sin red: ◄─────── 3. Muestra el QR de pago
     · firma del emisor sobre el vale
     · firma del teléfono sobre el gasto
     · que el pago responda a SU reto vivo
     · que las cuentas cuadren
     Muestra "aceptado" y guarda el recibo
```

**Por qué los 45 segundos.** El reto lleva un número de un solo uso que vence
rápido. Es lo que impide pagar con una captura de pantalla: un QR de ayer no
responde a ningún cobro abierto. Si vence, el chofer simplemente genera otro; no
se pierde nada. El número es corto a propósito: cuanto más largo, más margen hay
para fotografiar el QR del cobrador y montar un ataque de reenvío.

**Por qué no Bluetooth.** Emparejar aparatos añade fricción y tiempo en la puerta
del autobús, y no aporta seguridad: el protocolo no confía en el canal, confía en
las firmas. El canal puede ser cualquiera. **NFC sí** sería una mejora natural
más adelante: un solo toque en vez de dos escaneos, con el mismo protocolo.

### 3.4 Cómo sabe el pasajero que le recibieron el pago

Este es un hueco real que el diseño inicial no cubría, y se cerró así:

Al aceptar, **los dos aparatos muestran el mismo código de viaje de cuatro
cifras**, calculado por separado a partir del mismo pago
(`SHA-256` del eslabón, truncado). Si coinciden, el pasajero sabe que el
validador leyó exactamente *su* pago y no otro, ni un escaneo fallido.

Límites honestos de esa confirmación:

- **No es una prueba criptográfica.** Un chofer deshonesto podría enseñar el
  número sin haber aceptado el cobro. La prueba de verdad es el recibo firmado
  que se sube al reconciliar, y ahí la discrepancia aparece.
- Para una confirmación irrefutable en el momento haría falta un **tercer
  escaneo** (el validador devuelve un acuse firmado) o **NFC**, a costa de
  tiempo en la puerta.
- Si el escaneo falla del todo, el saldo ya se descontó en el teléfono. Se
  resuelve al reconciliar: hay un gasto firmado sin recibo del validador que lo
  respalde. **La política de devolución en ese caso está sin definir** (ver §7).

### 3.5 Qué necesita internet y qué no

| Operación | Internet |
|---|---|
| Dar de alta el monedero | Sí |
| Recargar | Sí |
| **Pagar el pasaje** | **No** |
| **Cobrar y verificar** | **No** |
| Sincronizar y liberar cupo offline | Sí |
| Subir recibos y cobrar en bolívares | Sí |
| Bajar la lista negra | Sí |

---

## 4. Seguridad: qué está resuelto y qué no

### 4.1 Resuelto del todo

**No se puede inventar saldo ("saldo fantasma").** El saldo no es un número
guardado en el teléfono: es un vale firmado por el emisor. Fabricar uno exige la
clave privada del emisor, que solo existe en el servidor. El validador la
verifica con la clave pública que trae empotrada en el APK. Probado contra: vale
firmado por un impostor, vale real con el monto alterado, emisor desconocido,
cuentas imposibles, pagar menos de lo que cuesta el pasaje.

**El saldo no es transferible entre teléfonos.** Cada vale lleva la huella de la
clave del dispositivo. Copiar el archivo del monedero a otro aparato no sirve,
porque ese aparato no puede producir firmas que el validador acepte.

**No se puede pagar con un pantallazo.** Nonce de un solo uso, 45 segundos. Y si
se vuelve a mostrar el mismo QR, el validador lo reconoce como *ya cobrado*, no
como pago nuevo.

**No se puede cobrar sin ser un validador registrado.** El QR del cobrador trae
un certificado firmado por el emisor; el teléfono del pasajero lo verifica sin
internet antes de pagar y avisa: *"ese cobrador no está registrado, no pagues"*.

**Atrasar el reloj no revive saldo vencido.** El monedero mantiene un piso de
tiempo que solo avanza, alimentado por fuentes firmadas (el servidor y la hora
que viene dentro del QR firmado del validador, que se sincroniza seguido).

### 4.2 NO resuelto: el doble gasto sin conexión

**No se puede impedir por software en un teléfono de propósito general.** Si el
teléfono está aislado y el validador está aislado, ninguno de los dos puede
saber que ese mismo saldo se gastó hace diez minutos en otro autobús. No es un
defecto de implementación: es la naturaleza de estar sin conexión. Con un
elemento seguro que lleve un contador monótono inviolable (una tarjeta tipo
DESFire, o un applet dedicado) se reduce mucho, pero eso implica hardware que la
gente no tiene.

Lo que sí se hace, y está implementado y probado:

**Se detecta con prueba irrefutable.** Los gastos de un vale forman una cadena
hash: cada uno lleva un número de secuencia y el hash del anterior. Para gastar
dos veces hay que restaurar un respaldo y emitir un gasto distinto con el mismo
número de secuencia. Eso produce dos vales **firmados por el propio defraudador**
en la misma posición de la cadena — una bifurcación. El servidor la ve al
reconciliar y arma un expediente que el usuario no puede negar.

**Se bloquea.** El monedero pasa a una lista negra que se distribuye a los
validadores en cada sincronización. Los validadores se conectan mucho más seguido
que los pasajeros, así que el bloqueo se propaga rápido.

**Se cobra.** La pérdida sale del saldo retenido del defraudador; si no alcanza,
queda como deuda y no puede volver a recargar.

**Al chofer se le paga igual.** Prestó el servicio de buena fe. Cargarle la
pérdida al chofer sería la forma más rápida de que nadie quiera usar el sistema.

**Y la pérdida tiene techo** — ver la sección siguiente.

### 4.3 Riesgos abiertos

- **Teléfono robado con saldo.** El pago no pide autenticación, a propósito:
  tiene que ser rápido y sin señal. El límite del daño es el tope offline. Se
  podría exigir huella por encima de cierto monto, a costa de fricción.
- **Validador comprometido.** Un aparato con la clave extraída podría emitir
  cobros válidos para su unidad. Como el dinero va al dueño que figura en su
  certificado, el fraude es rastreable. Mitigación: claves en Keystore y alertas
  por volumen anómalo.
- **Colusión chofer-pasajero.** El chofer deja pasar a alguien con un pago
  rechazado. No hay pérdida para el sistema (no se emite recibo, no se paga
  nada); la pérdida es del dueño de la unidad.
- **Rotación de claves del emisor.** Soportada técnicamente (varias claves
  activas a la vez), pero el procedimiento operativo está sin escribir.

---

## 5. Los topes: la perilla del negocio

Aunque el usuario tenga mucho saldo, solo una parte es gastable sin sincronizar.
Al llegar al tope, el saldo sigue ahí pero hay que conectarse para liberarlo. Dos
topes a la vez: por monto y por número de viajes; manda el que se agote primero.

**La pérdida máxima por teléfono comprometido es el tope offline**, no el saldo
recargado. Y al primer ciclo de fraude el monedero queda bloqueado.

### Un problema aritmético en los números que se propusieron

Con un pasaje de **235 Bs**, el cliente propuso un tope de **230**:

- Si eran **230 bolívares**: es *menos que un pasaje*. Nadie podría pagar ni un
  viaje sin señal — el sistema quedaría inservible justo en el caso para el que
  se hizo.
- Si eran **230 dólares**: son ~180.000 Bs, unos 766 pasajes. Esa sería la
  pérdida máxima de un solo teléfono rooteado. Demasiado.

Recomendación, y lo que la demostración trae por defecto: **unos 2.350 Bs, diez
pasajes, ~3 US$**. Cubre casi una semana de ida y vuelta sin conectarse nunca, y
el techo del fraude por teléfono son 3 dólares, una sola vez. A nadie le compra
rootear un teléfono y quemar su cuenta por eso.

El tope puede ser **por perfil de riesgo**: alto para quien tiene historial limpio
y equipo con StrongBox, bajo para un teléfono recién dado de alta.

---

## 6. Cómo se verificó (evidencia, no promesas)

- **52 pruebas** en el módulo `core` cubren el flujo completo y los ataques:
  saldo fantasma, vale alterado, vale de otro teléfono, emisor desconocido,
  doble gasto entre dos unidades, lista negra, validador falso, reloj
  manipulado, topes offline, QR corrupto, persistencia.
- **El generador de códigos QR** de la demostración se verifica contra la
  biblioteca `segno` en las **40 versiones, forzando las ocho máscaras**, y se
  comprueba que **`zxing-cpp`** (el motor que corre en los teléfonos Android)
  lee los 63 casos. Se descartó una biblioteca de npm precisamente porque
  producía códigos ilegibles en varias versiones.
- **Compatibilidad entre las dos implementaciones:** un escenario firmado por el
  JavaScript se valida con el código Kotlin de producción — firmas,
  encadenamiento hash, código de viaje y codificación de los QR — y se comprueba
  que al recodificar en Kotlin sale el mismo texto. La igualdad va en los dos
  sentidos.
- **La demostración web** se ejecuta en Chromium en cada cambio: el recorrido
  completo más los cuatro ataques, comprobando cada paso.

---

## 7. Lo que falta para producción

**Bloqueante**

1. **Compilar el módulo Android.** Nunca se compiló.
2. **El backend real.** Existe la lógica de dinero y de fraude completa y
   probada, pero en memoria. Falta persistencia, autenticación, API y el cliente
   en la app.
3. **La clave del emisor en un HSM.** Hoy la de demostración está en el código a
   propósito. Esa clave *es* el sistema.
4. **Cobro real y pago real en bolívares.**

**Importante**

5. **Validar la atestación de clave en el servidor.** La app ya envía la cadena
   de certificados del Keystore al dar de alta; falta validarla contra la raíz de
   Google (clave de hardware, arranque bloqueado). Es lo que deja fuera
   emuladores y granjas de teléfonos falsos.
6. **Persistencia del validador.** Hoy guarda recibos y eslabones vistos en
   memoria: si se reinicia el aparato, se pierden los recibos sin subir.
7. **Sincronización en segundo plano** (WorkManager).
8. **Política de devolución** cuando hay un gasto firmado sin recibo del
   validador (escaneo fallido, o chofer que no sube).
9. **Cierre de vales y devolución del saldo que vence sin gastarse.**

---

## 8. El asunto regulatorio en Venezuela

*Esto no es asesoría legal. Es el mapa del problema para llevárselo a un abogado
venezolano especializado en el sector bancario.*

### 8.1 Lo que decide todo: ¿quién guarda el dinero?

El diseño actual **retiene el dinero del usuario en garantía** entre la recarga y
la liquidación al dueño de la unidad. Manejar fondos de terceros es, casi con
seguridad, actividad regulada. Esa es la línea que hay que mirar antes que
ninguna otra.

La normativa relevante es la **Resolución 001.21 de SUDEBAN**, "Normas que
Regulan los Servicios de Tecnología Financiera (FINTECH)", vigente desde junio de
2021. Puntos que importan para este proyecto:

- Crea la figura de **ITFB** (Institución de Tecnología Financiera del Sector
  Bancario), que requiere **autorización previa de SUDEBAN**, con opinión
  vinculante del OSFIN.
- Las ITFB son **proveedoras de tecnología para las instituciones bancarias**,
  no entidades que capten fondos del público. En la versión final se eliminó el
  artículo que preveía la administración de fondos de terceros.
- Requisitos societarios: sociedad anónima con acciones nominativas, mínimo
  cinco accionistas, domicilio en el país, y las siglas **ITFB** en la
  denominación social.
- **Fianza de fiel cumplimiento** no menor al equivalente de **20.000 €** al
  cambio del BCV, emitida por un banco o una aseguradora.
- Los contratos con clientes deben usar modelos **aprobados previamente por
  SUDEBAN**.

**La consecuencia de diseño** es concreta: si Pana Pago no puede guardar el
dinero, la garantía tiene que vivir en **un banco aliado**, y Pana Pago ser el
proveedor tecnológico. Eso cambia dónde vive el "escrow" del servidor, pero **no
cambia el protocolo**: el vale de saldo lo seguiría firmando el emisor, solo que
el emisor sería, o estaría respaldado por, la institución bancaria.

La alternativa —ser uno mismo la entidad que guarda el dinero— es un camino
mucho más largo y caro.

### 8.2 Lo del IVA: son dos servicios distintos, no uno

Aquí hay una confusión que conviene deshacer:

- **El pasaje** (el transporte terrestre nacional de pasajeros) está **exento de
  IVA** por la Ley del IVA venezolana. Eso es del transportista, y aplica tanto
  al transporte público como al privado.
- **La comisión que cobra Pana Pago** por el servicio tecnológico es **otro
  servicio distinto**, y no hereda esa exención por el hecho de estar cobrando
  algo que sí lo está.

Es decir: que el pasaje esté exento no hace exenta a la pasarela. Hay que
preguntarlo explícitamente.

### 8.3 Preguntas concretas para el abogado

1. Con este diseño, ¿Pana Pago es una **ITFB** (proveedor tecnológico de un
   banco) o estaría **captando fondos del público**? ¿Qué hay que cambiar en la
   arquitectura para caer del lado bueno?
2. ¿Un instrumento **prepago de circuito cerrado** —saldo que solo sirve para
   pagar pasaje, no convertible ni transferible entre usuarios— recibe un
   tratamiento más liviano?
3. ¿Qué figura hace falta para **pagar en bolívares a los dueños de las
   unidades**? ¿Se puede hacer a través del banco aliado?
4. Régimen de IVA e ISLR de **la comisión**.
5. Obligaciones de **prevención de legitimación de capitales** (conocimiento del
   cliente, reportes) con montos tan pequeños.
6. ¿Hace falta permiso del **INTT** o de la autoridad municipal de transporte
   para cobrar pasaje por medios electrónicos?
7. **Protección de datos** del usuario y retención de los registros de viaje.

---

## 9. Preguntas abiertas para quien revise

Son las que de verdad interesan; no hace falta opinar sobre el resto.

1. **¿Hay algún ataque que no se haya considerado?** Sobre todo en el modo de
   cobro directo y en el manejo del tiempo sin fuente confiable.
1b. **¿La ventana de 30 segundos del modo directo es el punto correcto?** Más
   corta obliga a regenerar el QR y deja dinero en el aire; más larga amplía el
   margen para pasarle la captura a un amigo.
2. **El código de viaje de cuatro cifras** como confirmación para el pasajero:
   ¿es suficiente en la práctica, o hace falta el tercer escaneo con acuse
   firmado desde el principio?
3. **La política de reparto de pérdidas** (pagarle al chofer siempre, cargarle la
   pérdida al defraudador, y que el descubierto lo cubra una reserva de fraude):
   ¿aguanta cuando el volumen crece?
4. **El tope offline**: ¿10 pasajes es el punto correcto para Venezuela, donde la
   conectividad es mala y perder un viaje duele?
5. **La ruta regulatoria**: ¿banco aliado como emisor y Pana Pago como ITFB es el
   camino más corto?
6. **Qué pasa con los pasajeros sin teléfono inteligente.** Hoy el diseño no los
   contempla, y en transporte público son muchos. Una tarjeta NFC barata usaría
   el mismo protocolo, pero es trabajo adicional.
