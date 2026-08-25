// Prueba la pagina de demostracion en un navegador de verdad.
//
// Abre web/demo.html en Chromium, ejecuta el recorrido completo (recargar,
// cortar el internet, cobrar, pagar, verificar, reconectar, liquidar) y despues
// los cuatro ataques, comprobando en cada paso que la pagina dice lo que tiene
// que decir. Falla si algo no cuadra.
//
// Uso:  node tools/probar-demo.mjs [--capturas]

import { chromium } from 'playwright';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// El entorno trae Chromium preinstalado, pero puede no ser la compilacion que
// espera la version de Playwright que haya instalada. Si esta, se usa esa.
const CHROMIUM_LOCAL = [
  '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  '/opt/pw-browsers/chromium/chrome-linux/chrome',
].find(existsSync);

const capturas = process.argv.includes('--capturas');
const archivo = fileURLToPath(new URL('../web/demo.html', import.meta.url));

const fallos = [];
function comprobar(condicion, descripcion) {
  if (condicion) {
    console.log(`ok    ${descripcion}`);
  } else {
    console.log(`FALLO ${descripcion}`);
    fallos.push(descripcion);
  }
}

const navegador = await chromium.launch(
  CHROMIUM_LOCAL ? { executablePath: CHROMIUM_LOCAL } : {},
);
const pagina = await navegador.newPage({ viewport: { width: 1280, height: 1400 } });

// Los fallos de red no cuentan: este entorno no le da salida al navegador, y lo
// unico que la pagina pide fuera es la hoja de tipografias de Google Fonts.
const esDeRed = (t) => /Failed to load resource|ERR_(CONNECTION|NAME|INTERNET)/.test(t);
const erroresDeConsola = [];
pagina.on('pageerror', (e) => erroresDeConsola.push(String(e)));
pagina.on('console', (m) => {
  if (m.type() === 'error' && !esDeRed(m.text())) erroresDeConsola.push(m.text());
});

await pagina.goto(`file://${archivo}`);
await pagina.waitForSelector('.linea');

const texto = (sel) => pagina.textContent(sel);
const bitacora = () => pagina.textContent('#lineas');
const pulsar = async (sel) => { await pagina.click(sel); await pagina.waitForTimeout(120); };

// --- recorrido normal ---------------------------------------------------------
comprobar((await texto('#m-saldo')).trim() === '0,00 Bs', 'arranca sin saldo');

await pulsar('#btn-recargar');
comprobar((await texto('#m-saldo')).includes('5.000,00') || (await texto('#m-saldo')).includes('5000,00'),
  'la recarga carga el saldo');
comprobar((await texto('#m-offline')).includes('2.350,00') || (await texto('#m-offline')).includes('2350,00'),
  'el gastable sin señal respeta el tope, no el saldo total');

await pulsar('#btn-senal');
comprobar((await texto('#txt-senal')).includes('Sin señal'), 'se puede cortar el internet');
comprobar(await pagina.isDisabled('#btn-recargar'), 'sin señal no se puede recargar');

await pulsar('#btn-cobrar');
comprobar(await pagina.$('#pantalla-validador canvas') !== null, 'el validador dibuja un QR de cobro');

await pulsar('#btn-pagar');
comprobar(await pagina.$('#pantalla-pasajero canvas') !== null, 'el teléfono dibuja el QR de pago');
comprobar((await texto('#m-saldo')).includes('4.765,00') || (await texto('#m-saldo')).includes('4765,00'),
  'el saldo se descuenta sin internet');

const codigoPasajero = await pagina.textContent('#pantalla-pasajero .codigo b');
await pulsar('#btn-leer');
comprobar((await texto('#pantalla-validador')).includes('Pago aceptado'), 'el validador acepta el pago sin internet');
comprobar((await texto('#m-acumulado')).includes('235,00'), 'el cobrador acumula el pasaje');

const codigoValidador = await pagina.textContent('#pantalla-validador .codigo b');
comprobar(/^\d{4}$/.test(codigoPasajero), 'el teléfono muestra un código de viaje de cuatro cifras');
comprobar(codigoPasajero === codigoValidador,
  `los dos aparatos calculan el mismo código de viaje (${codigoPasajero} / ${codigoValidador})`);

if (capturas) await pagina.screenshot({ path: 'web/captura-offline.png', fullPage: true });

// --- vuelta de la señal --------------------------------------------------------
await pulsar('#btn-senal');
await pulsar('#btn-subir');
comprobar((await texto('#liq-neto')).includes('227,95'), 'la liquidación descuenta la comisión del 3%');
await pulsar('#btn-sincronizar');
comprobar((await texto('#m-pendientes')).trim() === '0', 'al sincronizar no quedan viajes pendientes');

// --- ataques ---------------------------------------------------------------------
await pulsar('#atq-saldo');
comprobar((await bitacora()).includes('no lo emitió el servidor') ||
  (await bitacora()).includes('no lo emitio el servidor') ||
  (await bitacora()).includes('rechaza'), 'el saldo inventado se rechaza');

const saldoAntes = await texto('#m-saldo');
await pulsar('#atq-cobrador');
comprobar((await texto('#pantalla-pasajero')).includes('No pagues'), 'el cobrador falso se detecta antes de pagar');
comprobar((await texto('#m-saldo')) === saldoAntes, 'el cobrador falso no toca el saldo');

await pulsar('#btn-cobrar');
await pulsar('#btn-pagar');
await pulsar('#btn-leer');
await pulsar('#atq-pantallazo');
comprobar((await texto('#pantalla-validador')).includes('No sirve'), 'el pantallazo viejo no cuela');

// --- modo de cobro directo (un solo escaneo) -------------------------------------
await pulsar('#btn-directo');
comprobar(await pagina.$('#pantalla-pasajero canvas') !== null, 'el cobro directo dibuja un QR sin pedir nada al cobrador');
const codigoDirecto = await pagina.textContent('#pantalla-pasajero .codigo b');
await pulsar('#btn-leer');
comprobar((await texto('#pantalla-validador')).includes('Pago aceptado'), 'la unidad cobra de un solo escaneo');
comprobar(await pagina.textContent('#pantalla-validador .codigo b') === codigoDirecto,
  'el código de viaje también cuadra en el modo directo');
comprobar((await bitacora()).includes('de un solo escaneo'), 'la bitácora distingue el modo de cobro');

await pulsar('#atq-directo');
const trasDirecto = await bitacora();
comprobar(trasDirecto.includes('dos recibos firmados por el mismo pago'),
  'el cobro duplicado del modo directo se detecta al reconciliar');
comprobar(trasDirecto.includes('no cobra'), 'la segunda unidad no cobra el pago duplicado');

await pulsar('#atq-doble');
const finalBitacora = await bitacora();
comprobar(finalBitacora.includes('bifurcación') || finalBitacora.includes('bifurcacion'),
  'el doble gasto se detecta al reconciliar');
comprobar(finalBitacora.includes('bloqueado'), 'el monedero defraudador queda bloqueado');

if (capturas) await pagina.screenshot({ path: 'web/captura-ataques.png', fullPage: true });

comprobar(erroresDeConsola.length === 0, `sin errores de JavaScript${erroresDeConsola.length ? `: ${erroresDeConsola[0]}` : ''}`);

await navegador.close();

console.log();
if (fallos.length) {
  console.log(`${fallos.length} comprobacion(es) fallaron`);
  process.exit(1);
}
console.log('la demostracion funciona de punta a punta en Chromium');
