// Genera un escenario completo con la implementacion JavaScript del protocolo
// y lo guarda en web/fixtures-compatibilidad.json.
//
// Ese archivo lo lee CompatibilidadWebTest.kt, que lo verifica con el core de
// produccion en Kotlin. Es la prueba de que la demostracion web no es una
// imitacion: firma y codifica exactamente igual que la app.
//
// Uso:  node tools/generar-fixtures.mjs

import { writeFileSync } from 'node:fs';
import { Monedero, Servidor, Validador } from '../web/motor.js';
import { b64urlEncode, codificarVale, codigoDeViaje, idDelEslabon } from '../web/protocolo.js';

const RELOJ = () => 1_777_000_000; // hora fija, para que el fixture sea estable

const servidor = await new Servidor(undefined, RELOJ).iniciar();
const monedero = await Monedero.crear(servidor.anclas, RELOJ);
const walletId = await servidor.registrarMonedero(monedero.claves.publicKeyEncoded, 'w-maria');

const vale = await servidor.recargar(walletId, 235_00);
const carga = await monedero.cargarVale(vale);
if (!carga.ok) throw new Error(`el monedero rechazo el vale: ${carga.motivo}`);

const bus = await Validador.crear(servidor, {
  validatorId: 'v-bus14',
  unitId: 'bus-14',
  ownerId: 'don-enrique',
  routeId: 'ruta-01',
  fareCentimos: 2_35,
}, RELOJ);

const pagos = [];
for (let i = 0; i < 2; i++) {
  const reto = await bus.nuevoReto();
  const pago = await monedero.pagar(reto.firmado);
  if (!pago.ok) throw new Error(`el pago ${i + 1} fue rechazado: ${pago.motivo}`);
  const resultado = await bus.aceptar(pago.gasto);
  if (resultado.estado !== 'aceptado') {
    throw new Error(`el validador rechazo el pago ${i + 1}: ${resultado.motivo}`);
  }
  pagos.push({ reto, pago });
}

// Un cobro directo (modo de un solo escaneo) con su recibo firmado, para
// comprobar que el core en Kotlin tambien los entiende byte a byte.
const directo = await monedero.presentar(2_35);
if (!directo.ok) throw new Error(`el cobro directo fue rechazado: ${directo.motivo}`);
const cobroDirecto = await bus.aceptar(directo.gasto);
if (cobroDirecto.estado !== 'aceptado') {
  throw new Error(`el validador rechazo el cobro directo: ${cobroDirecto.motivo}`);
}
const claim = cobroDirecto.recibo.recibo;

const fixtures = {
  _comentario:
    'Generado por tools/generar-fixtures.mjs con la implementacion JavaScript. ' +
    'Lo verifica core/src/test/kotlin/ve/transporte/core/CompatibilidadWebTest.kt.',
  emisorKeyId: servidor.emisorKeyId,
  emisorClavePublica: b64urlEncode(servidor.emisor.publicKeyEncoded),
  valeQr: codificarVale(vale),
  retoQr: pagos[0].reto.qr,
  gastoQr: pagos[0].pago.qr,
  segundoGastoQr: pagos[1].pago.qr,
  esperado: {
    walletId,
    ownerId: 'don-enrique',
    unitId: 'bus-14',
    montoDelValeCentimos: 235_00,
    pasajeCentimos: 2_35,
    saldoTrasElPrimerPagoCentimos: 232_65,
    saldoTrasElSegundoPagoCentimos: 230_30,
    // El segundo eslabon apunta al hash del primero: es la cadena que hace
    // demostrable el doble gasto.
    idDelPrimerEslabon: await idDelEslabon(pagos[0].pago.gasto),
    // Las dos implementaciones tienen que sacar el mismo codigo de viaje: es
    // lo que el pasajero y el cobrador comparan en la puerta del autobus.
    codigoDeViajeDelPrimerPago: await codigoDeViaje(pagos[0].pago.gasto),
    codigoDeViajeDelSegundoPago: await codigoDeViaje(pagos[1].pago.gasto),
  },
  cobroDirecto: {
    qr: directo.qr,
    seq: directo.gasto.token.seq,
    montoCentimos: directo.gasto.token.amountCentimos,
    saldoDespuesCentimos: directo.gasto.token.balanceAfterCentimos,
    ventanaSegundos: directo.gasto.token.windowSeconds,
    codigoDeViaje: await codigoDeViaje(directo.gasto),
  },
  // Recibo firmado por el validador. En el modo directo es lo unico que dice a
  // que unidad y a que dueño va el dinero, porque el pago del pasajero no lo
  // sabia cuando se firmo.
  recibo: {
    linkId: claim.claim.linkId,
    validatorId: claim.claim.validatorId,
    unitId: claim.claim.unitId,
    ownerId: claim.claim.ownerId,
    routeId: claim.claim.routeId,
    amountCentimos: claim.claim.amountCentimos,
    acceptedAtEpochSec: claim.claim.acceptedAtEpochSec,
    mode: claim.claim.mode,
    firma: b64urlEncode(claim.signature),
    clavePublicaDelValidador: b64urlEncode(claim.validatorPublicKey),
  },
};

writeFileSync(
  new URL('../web/fixtures-compatibilidad.json', import.meta.url),
  `${JSON.stringify(fixtures, null, 2)}\n`,
);
console.log('escrito web/fixtures-compatibilidad.json');
console.log(`  vale  ${fixtures.valeQr.length} caracteres`);
console.log(`  reto  ${fixtures.retoQr.length} caracteres`);
console.log(`  gasto ${fixtures.gastoQr.length} caracteres`);
console.log(`  cobro directo ${fixtures.cobroDirecto.qr.length} caracteres`);
