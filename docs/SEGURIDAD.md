# Modelo de seguridad

Este documento dice qué está resuelto, qué está acotado y qué **no** tiene
solución. Si algo aquí no te cuadra con lo que necesita el negocio, es mejor
saberlo ahora que después de repartir mil teléfonos.

## 1. Quién es el atacante

| Atacante | Qué quiere | Estado |
|---|---|---|
| Pasajero con teléfono normal | Viajar gratis | **Imposible** |
| Pasajero con teléfono rooteado | Inventar saldo | **Imposible** |
| Pasajero con teléfono rooteado | Gastar dos veces el mismo saldo | **Acotado y detectable** |
| Cualquiera con una impresora | Cobrar pasajes falsos | **Imposible** |
| Chofer deshonesto | Cobrar de más | **Imposible** |
| Chofer deshonesto | Inventar pasajes que nadie viajó | **Imposible** |
| Quien roba un teléfono | Gastar el saldo ajeno | **Parcial** — ver §6 |

## 2. Lo que está resuelto del todo

### No se puede inventar saldo

El saldo no es un número guardado en el teléfono: es un **vale firmado** por el
emisor. Para fabricar uno hace falta la clave privada del emisor, que nunca sale
del servidor. El validador verifica esa firma con la clave pública que trae
dentro del APK.

Cubierto por `SaldoFantasmaTest`: vale firmado por un impostor, vale real con el
monto alterado, emisor desconocido, cuentas imposibles, pagar de menos.

### El saldo no es transferible entre teléfonos

Cada vale lleva la **huella de la clave del dispositivo**. Esa clave se genera
dentro del Keystore de Android (StrongBox si el equipo lo trae, si no el TEE) y
no es extraíble ni con root: lo único que se puede hacer con ella es pedirle
firmas. Copiar el archivo del monedero a otro teléfono no sirve, porque ese otro
teléfono no puede producir firmas que el validador acepte.

### No se puede pagar con un pantallazo

Cada cobro genera un número de un solo uso que vence en 45 segundos. El vale de
gasto tiene que incluirlo. Un QR guardado de ayer no responde a ningún cobro
abierto. Y si se vuelve a mostrar el mismo QR, el validador lo reconoce como *ya
cobrado*, no como pago nuevo.

### No se puede cobrar sin ser un validador registrado

El QR de cobro trae un **certificado del validador** firmado por el emisor. El
teléfono del pasajero lo verifica sin internet antes de pagar. Un QR pegado en
una pared por un vivo no pasa la verificación, y la app se lo dice al usuario en
esas palabras: *"ese cobrador no está registrado, no pagues"*.

### Atrasar el reloj no revive saldo vencido

El monedero mantiene un **piso de tiempo** que solo avanza, alimentado por
fuentes firmadas: la hora del servidor y la hora que viene dentro del QR firmado
del validador. Como el validador se sincroniza seguido, su hora es confiable.
Atrasar el reloj del teléfono no logra nada.

## 3. Lo que NO se puede resolver: el doble gasto offline

**Nadie puede resolver esto por software en un teléfono de propósito general.**
Ni nosotros ni ningún otro sistema. La razón es sencilla: si el teléfono está
aislado y el validador está aislado, ninguno de los dos tiene forma de saber que
ese mismo saldo se gastó hace diez minutos en otro autobús. No es un defecto de
implementación, es la naturaleza de estar sin conexión.

Con un elemento seguro que lleve un contador monótono inviolable (una tarjeta
tipo MIFARE DESFire, o Android con un applet dedicado) se reduce mucho, pero eso
implica hardware que la gente no tiene.

### Lo que sí se hace

**Se detecta con prueba irrefutable.** Los gastos de un vale forman una cadena:
cada uno lleva un número de secuencia y el hash del anterior. Para gastar dos
veces hay que restaurar un respaldo y emitir un gasto distinto con el mismo
número de secuencia. Eso produce dos vales **firmados por el propio defraudador**
en la misma posición de la cadena. El servidor lo ve al reconciliar y arma un
expediente que el usuario no puede negar: son sus firmas.

**Se bloquea.** El monedero pasa a la lista negra, que se distribuye a los
validadores en cada sincronización. Los validadores se conectan mucho más seguido
que los pasajeros, así que el bloqueo se propaga rápido.

**Se cobra.** La pérdida sale del saldo retenido del defraudador. Si no alcanza,
queda como deuda y no puede volver a recargar.

**Al chofer se le paga igual.** Prestó el servicio de buena fe. Cargarle la
pérdida al chofer sería la manera más rápida de que nadie quiera usar el sistema.

**Y sobre todo: la pérdida tiene techo.**

### Los topes: de dónde sale el techo

Aunque el usuario tenga 500 Bs recargados, solo una parte es gastable sin
sincronizar. Cuando llega al tope, el saldo sigue ahí pero hay que conectarse
para liberarlo. Dos topes a la vez:

| Perilla | Por defecto | Qué acota |
|---|---|---|
| `offlineCapCeilingCentimos` | 200,00 Bs | Monto máximo sin sincronizar |
| `offlineTripCap` | 30 viajes | Viajes máximos sin sincronizar |
| `grantValiditySeconds` | 30 días | Vida del vale |

**La pérdida máxima por teléfono comprometido es el tope offline**, no el saldo
recargado. Con el tope por defecto: 200 Bs por ciclo de fraude, y al primer ciclo
el monedero queda bloqueado. Para el defraudador, rootear un teléfono y arriesgar
su cuenta por 200 Bs no da la cuenta.

**Esta es la perilla de negocio.** Bajar el tope reduce el riesgo y molesta más al
usuario honesto que anda sin señal; subirlo hace lo contrario. Se puede ajustar
por usuario: a quien tiene historial limpio y equipo con StrongBox, tope alto; a
un teléfono recién dado de alta, tope bajo.

## 4. Qué frena a la mayoría antes de llegar ahí

Para ejecutar el ataque de doble gasto hace falta: rootear el teléfono, entender
el formato del monedero, respaldar el archivo cifrado en el momento justo y
restaurarlo entre viajes. Y el premio son unos pocos pasajes antes del bloqueo.
En la práctica esto lo intentan muy pocos, y son justo los que el expediente
identifica de una.

Además, la **atestación de clave** al dar de alta el monedero (la cadena de
certificados del Keystore, validada contra la raíz de Google) permite exigir que
la clave sea de hardware y que el arranque esté bloqueado. Eso deja fuera
emuladores y granjas de teléfonos falsos. El gancho está puesto en
`KeystoreSigner`; **la validación en el servidor está pendiente** (ver
`docs/PENDIENTE.md`).

## 5. Detalles de diseño que importan

**Serialización canónica.** Lo que se firma no es JSON. Dos serializadores JSON
pueden producir bytes distintos para el mismo objeto, y peor, se puede mover
contenido de un campo a otro sin cambiar los bytes. Aquí cada campo va con
prefijo de longitud y en orden fijo, y cada tipo de mensaje lleva su etiqueta de
dominio, así que una firma válida para un tipo nunca se puede reinterpretar como
válida para otro.

**Dinero en enteros.** Céntimos de bolívar en `Long`. Nunca punto flotante: con
`Double`, `0.1 + 0.2` no da `0.3`, y en un sistema de pagos eso es dinero que
aparece o desaparece. Hay una prueba que lo fija.

**Respaldo de Android desactivado.** `allowBackup="false"` más reglas explícitas
de exclusión. Restaurar un respaldo del monedero *es* el ataque de doble gasto:
no tiene sentido dejar que el propio sistema operativo lo facilite.

**Cifrado en reposo, sin ilusiones.** El monedero se guarda con AES-256-GCM y
clave del Keystore. Eso protege contra que alguien lea o manipule el archivo. **No**
protege contra que el dueño guarde una copia del archivo cifrado y la restaure:
contra eso actúa la cadena hash, no el cifrado. Está dicho así en el código para
que nadie se confunda.

**Escritura atómica.** Archivo temporal y rename, para que un apagón no deje el
monedero a medio escribir.

## 6. Riesgos abiertos

**Teléfono robado con saldo cargado.** Hoy el pago no pide autenticación, a
propósito: tiene que funcionar rápido y sin señal. El límite del daño es el tope
offline. Si se quiere más, se puede exigir huella por encima de cierto monto, a
costa de fricción. Es una decisión de producto.

**Validador comprometido.** Un aparato de validador con la clave extraída podría
emitir cobros válidos para su unidad. Como el dinero va al `ownerId` que está en
su certificado, el fraude es rastreable hasta el dueño registrado. Mitigación:
sus claves también en Keystore, y alertas por volumen anómalo en el servidor.

**Colusión chofer-pasajero.** El chofer acepta un pago rechazado y deja pasar al
pasajero. No hay pérdida para el sistema (no se emite recibo, no se paga nada);
la pérdida es del dueño de la unidad, y se detecta comparando pasajeros contados
contra recibos subidos.

**Rotación de claves del emisor.** Está soportada (varias claves activas a la
vez), pero el procedimiento operativo hay que escribirlo.
