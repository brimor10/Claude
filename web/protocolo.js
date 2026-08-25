// Protocolo Pana Pago en JavaScript.
//
// Es una reimplementacion del modulo :core (Kotlin) para poder enseñar el
// sistema funcionando en un navegador. No es una maqueta: usa las mismas
// primitivas (ECDSA P-256 con SHA-256), la misma serializacion canonica byte a
// byte y el mismo formato de QR.
//
// Que sea de verdad el mismo protocolo no se da por supuesto: en
// tools/generar-fixtures.mjs se genera un escenario completo con ESTE codigo y
// core/src/test/.../CompatibilidadWebTest.kt lo verifica con la implementacion
// Kotlin de produccion. Si alguna de las dos se desvia, esa prueba falla.

const subtle = globalThis.crypto.subtle;

// --- Bytes, base64url ---------------------------------------------------------

export function b64urlEncode(bytes) {
  let binario = '';
  for (const b of bytes) binario += String.fromCharCode(b);
  return btoa(binario).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function b64urlDecode(texto) {
  const base = texto.replace(/-/g, '+').replace(/_/g, '/');
  const binario = atob(base + '='.repeat((4 - (base.length % 4)) % 4));
  return Uint8Array.from(binario, (c) => c.charCodeAt(0));
}

const concat = (...trozos) => {
  const total = trozos.reduce((n, t) => n + t.length, 0);
  const salida = new Uint8Array(total);
  let offset = 0;
  for (const t of trozos) {
    salida.set(t, offset);
    offset += t.length;
  }
  return salida;
};

const bytesEqual = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

// --- Serializacion ------------------------------------------------------------

const DOMINIO = 've.transporte.panapago/v1';
export const TAG = {
  GRANT: 'purse-grant',
  CHALLENGE: 'validator-challenge',
  SPEND: 'spend-token',
  VALIDATOR_CERT: 'validator-cert',
};

/**
 * Escritor de campos con prefijo de longitud. Es el mismo formato que usa
 * CanonicalWriter/FrameWriter en Kotlin: 4 bytes de longitud en big-endian y
 * despues el contenido. El orden de los campos lo fija el codigo.
 */
class Escritor {
  constructor(tagDominio = null) {
    this.trozos = [];
    if (tagDominio !== null) {
      this.str(DOMINIO);
      this.str(tagDominio);
    }
  }

  bytes(v) {
    const cabecera = new Uint8Array(4);
    new DataView(cabecera.buffer).setUint32(0, v.length, false);
    this.trozos.push(cabecera, v);
    return this;
  }

  str(v) { return this.bytes(new TextEncoder().encode(v)); }

  long(v) {
    const b = new Uint8Array(8);
    new DataView(b.buffer).setBigInt64(0, BigInt(v), false);
    return this.bytes(b);
  }

  int(v) { return this.long(v); }

  build() { return concat(...this.trozos); }
}

class Lector {
  constructor(buf) {
    this.buf = buf;
    this.pos = 0;
  }

  bytes() {
    if (this.pos + 4 > this.buf.length) throw new Error('trama truncada');
    const len = new DataView(this.buf.buffer, this.buf.byteOffset + this.pos, 4).getUint32(0, false);
    this.pos += 4;
    if (this.pos + len > this.buf.length) throw new Error('longitud de campo invalida');
    const out = this.buf.slice(this.pos, this.pos + len);
    this.pos += len;
    return out;
  }

  str() { return new TextDecoder().decode(this.bytes()); }

  long() {
    const b = this.bytes();
    if (b.length !== 8) throw new Error('entero mal formado');
    return Number(new DataView(b.buffer, b.byteOffset, 8).getBigInt64(0, false));
  }

  int() { return this.long(); }

  end() {
    if (this.pos !== this.buf.length) throw new Error('bytes sobrantes en la trama');
  }
}

// --- Criptografia --------------------------------------------------------------

const ALGO = { name: 'ECDSA', namedCurve: 'P-256' };
const FIRMA = { name: 'ECDSA', hash: 'SHA-256' };

export async function generarClaves() {
  const par = await subtle.generateKey(ALGO, true, ['sign', 'verify']);
  const publica = new Uint8Array(await subtle.exportKey('spki', par.publicKey));
  return { privateKey: par.privateKey, publicKey: par.publicKey, publicKeyEncoded: publica };
}

const importarPublica = (spki) =>
  subtle.importKey('spki', spki, ALGO, true, ['verify']);

/**
 * WebCrypto firma en formato crudo (r||s, 64 bytes) y Java/Android en DER.
 * Se convierte para que las firmas de esta implementacion las pueda verificar
 * el core en Kotlin, y al reves.
 */
function rawADer(raw) {
  const entero = (bytes) => {
    let i = 0;
    while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let v = bytes.slice(i);
    if (v[0] & 0x80) v = concat(new Uint8Array([0]), v);
    return concat(new Uint8Array([0x02, v.length]), v);
  };
  const r = entero(raw.slice(0, 32));
  const s = entero(raw.slice(32, 64));
  const cuerpo = concat(r, s);
  return concat(new Uint8Array([0x30, cuerpo.length]), cuerpo);
}

function derARaw(der) {
  if (der[0] !== 0x30) throw new Error('firma DER invalida');
  let pos = 2;
  const leerEntero = () => {
    if (der[pos] !== 0x02) throw new Error('firma DER invalida');
    const len = der[pos + 1];
    let v = der.slice(pos + 2, pos + 2 + len);
    pos += 2 + len;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    const salida = new Uint8Array(32);
    salida.set(v, 32 - v.length);
    return salida;
  };
  return concat(leerEntero(), leerEntero());
}

export async function firmar(privateKey, mensaje) {
  const raw = new Uint8Array(await subtle.sign(FIRMA, privateKey, mensaje));
  return rawADer(raw);
}

export async function verificar(publicKeyEncoded, mensaje, firmaDer) {
  try {
    const key = await importarPublica(publicKeyEncoded);
    return await subtle.verify(FIRMA, key, derARaw(firmaDer), mensaje);
  } catch {
    return false;
  }
}

export const sha256 = async (...partes) =>
  new Uint8Array(await subtle.digest('SHA-256', concat(...partes)));

export const huellaDeClave = async (spki) => b64urlEncode(await sha256(spki));

export const CEROS_32 = new Uint8Array(32);

export function nonce(n = 16) {
  const b = new Uint8Array(n);
  globalThis.crypto.getRandomValues(b);
  return b64urlEncode(b);
}

// --- Dinero ---------------------------------------------------------------------

export const MONEDA = 'VES';

export const Money = {
  desdeBolivares(texto) {
    const limpio = String(texto).trim().replace(',', '.');
    if (!/^-?\d+(\.\d{1,2})?$/.test(limpio)) throw new Error(`Monto invalido: ${texto}`);
    const negativo = limpio.startsWith('-');
    const [entero, decimales = ''] = limpio.replace('-', '').split('.');
    const total = Number(entero) * 100 + Number(decimales.padEnd(2, '0') || 0);
    return negativo ? -total : total;
  },
  /**
   * Formato venezolano: punto para los miles, coma para los decimales.
   * Solo afecta a lo que se muestra; por el cable los montos siempre viajan
   * como enteros de centimos, igual que en Kotlin.
   */
  formato(centimos) {
    const signo = centimos < 0 ? '-' : '';
    const abs = Math.abs(centimos);
    const enteros = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
    return `${signo}${enteros},${String(abs % 100).padStart(2, '0')} Bs`;
  },
};

// --- Mensajes -------------------------------------------------------------------

export const bytesDelVale = (g) => new Escritor(TAG.GRANT)
  .str(g.grantId).str(g.walletId).str(g.deviceKeyFingerprint)
  .long(g.amountCentimos).str(g.currency)
  .long(g.issuedAtEpochSec).long(g.expiresAtEpochSec)
  .long(g.offlineSpendCapCentimos).int(g.offlineTripCap)
  .str(g.issuerKeyId).str(g.nonce)
  .build();

export const bytesDelCertificado = (c) => new Escritor(TAG.VALIDATOR_CERT)
  .str(c.validatorId).str(c.unitId).str(c.ownerId).str(c.publicKeyFingerprint)
  .long(c.issuedAtEpochSec).long(c.expiresAtEpochSec).str(c.issuerKeyId)
  .build();

export const bytesDelReto = (c) => new Escritor(TAG.CHALLENGE)
  .str(c.validatorId).str(c.unitId).str(c.ownerId).str(c.routeId)
  .long(c.fareCentimos).str(c.challengeNonce)
  .long(c.issuedAtEpochSec).int(c.ttlSeconds).str(c.validatorKeyId)
  .build();

export const bytesDelGasto = (t) => new Escritor(TAG.SPEND)
  .str(t.grantId).str(t.walletId).long(t.seq)
  .long(t.amountCentimos).long(t.balanceAfterCentimos)
  .bytes(t.prevHash)
  .str(t.validatorId).str(t.unitId).str(t.ownerId).str(t.routeId)
  .str(t.challengeNonce).long(t.spentAtEpochSec).str(t.deviceKeyFingerprint)
  .build();

export const hashDelEslabon = (gasto) => sha256(bytesDelGasto(gasto.token), gasto.signature);
export const idDelEslabon = async (gasto) => b64urlEncode(await hashDelEslabon(gasto));

/**
 * Codigo de viaje: cuatro cifras que el pasajero y el validador calculan por
 * separado a partir del mismo gasto. Si los dos aparatos muestran el mismo
 * numero, el pasajero sabe que el cobrador leyo exactamente su pago.
 *
 * Es una confirmacion para el ojo humano, no una prueba: la prueba es el
 * recibo firmado que se sube al reconciliar. Ver SignedSpend.tripCode() en el
 * core de Kotlin, que calcula exactamente lo mismo.
 */
export async function codigoDeViaje(gasto) {
  const h = await sha256(await hashDelEslabon(gasto));
  const n = ((h[0] << 24) | (h[1] << 16) | (h[2] << 8) | h[3]) >>> 0;
  return String(n % 10000).padStart(4, '0');
}

// --- Codificacion de los QR -------------------------------------------------------

const PREFIJO = 'PP1';
export const TIPO = { RETO: 'CHL', GASTO: 'SPD', VALE: 'GRT' };

const envolver = (tipo, trama) => `${PREFIJO}:${tipo}:${b64urlEncode(trama)}`;

function desenvolver(texto, tipoEsperado) {
  const partes = String(texto).trim().split(':');
  if (partes.length !== 3 || partes[0] !== PREFIJO) throw new Error('no es un QR de Pana Pago');
  if (partes[1] !== tipoEsperado) throw new Error(`tipo de QR inesperado: ${partes[1]}`);
  return b64urlDecode(partes[2]);
}

export const tipoDeQr = (texto) => {
  const partes = String(texto).trim().split(':');
  return partes.length === 3 && partes[0] === PREFIJO ? partes[1] : null;
};

export function codificarReto(firmado) {
  const c = firmado.challenge;
  const cert = firmado.cert.cert;
  const trama = new Escritor()
    .str(c.validatorId).str(c.unitId).str(c.ownerId).str(c.routeId)
    .long(c.fareCentimos).str(c.challengeNonce)
    .long(c.issuedAtEpochSec).int(c.ttlSeconds).str(c.validatorKeyId)
    .bytes(firmado.signature).bytes(firmado.validatorPublicKey)
    .str(cert.validatorId).str(cert.unitId).str(cert.ownerId).str(cert.publicKeyFingerprint)
    .long(cert.issuedAtEpochSec).long(cert.expiresAtEpochSec)
    .str(cert.issuerKeyId).bytes(firmado.cert.signature)
    .build();
  return envolver(TIPO.RETO, trama);
}

export function decodificarReto(texto) {
  const r = new Lector(desenvolver(texto, TIPO.RETO));
  const challenge = {
    validatorId: r.str(), unitId: r.str(), ownerId: r.str(), routeId: r.str(),
    fareCentimos: r.long(), challengeNonce: r.str(),
    issuedAtEpochSec: r.long(), ttlSeconds: r.int(), validatorKeyId: r.str(),
  };
  const signature = r.bytes();
  const validatorPublicKey = r.bytes();
  const cert = {
    validatorId: r.str(), unitId: r.str(), ownerId: r.str(), publicKeyFingerprint: r.str(),
    issuedAtEpochSec: r.long(), expiresAtEpochSec: r.long(), issuerKeyId: r.str(),
  };
  const certSignature = r.bytes();
  r.end();
  return { challenge, signature, validatorPublicKey, cert: { cert, signature: certSignature } };
}

export function codificarVale(firmado) {
  const g = firmado.grant;
  const trama = new Escritor()
    .str(g.grantId).str(g.walletId).str(g.deviceKeyFingerprint)
    .long(g.amountCentimos).str(g.currency)
    .long(g.issuedAtEpochSec).long(g.expiresAtEpochSec)
    .long(g.offlineSpendCapCentimos).int(g.offlineTripCap)
    .str(g.issuerKeyId).str(g.nonce).bytes(firmado.signature)
    .build();
  return envolver(TIPO.VALE, trama);
}

export function codificarGasto(firmado) {
  const t = firmado.token;
  const g = firmado.grant.grant;
  const trama = new Escritor()
    .str(g.grantId).str(g.walletId).str(g.deviceKeyFingerprint)
    .long(g.amountCentimos).str(g.currency)
    .long(g.issuedAtEpochSec).long(g.expiresAtEpochSec)
    .long(g.offlineSpendCapCentimos).int(g.offlineTripCap)
    .str(g.issuerKeyId).str(g.nonce).bytes(firmado.grant.signature)
    .long(t.seq).long(t.amountCentimos).long(t.balanceAfterCentimos)
    .bytes(t.prevHash)
    .str(t.validatorId).str(t.unitId).str(t.ownerId).str(t.routeId)
    .str(t.challengeNonce).long(t.spentAtEpochSec)
    .bytes(firmado.signature).bytes(firmado.devicePublicKey)
    .build();
  return envolver(TIPO.GASTO, trama);
}

export function decodificarGasto(texto) {
  const r = new Lector(desenvolver(texto, TIPO.GASTO));
  const grant = {
    grantId: r.str(), walletId: r.str(), deviceKeyFingerprint: r.str(),
    amountCentimos: r.long(), currency: r.str(),
    issuedAtEpochSec: r.long(), expiresAtEpochSec: r.long(),
    offlineSpendCapCentimos: r.long(), offlineTripCap: r.int(),
    issuerKeyId: r.str(), nonce: r.str(),
  };
  const grantSignature = r.bytes();
  const token = {
    grantId: grant.grantId,
    walletId: grant.walletId,
    seq: r.long(),
    amountCentimos: r.long(),
    balanceAfterCentimos: r.long(),
    prevHash: r.bytes(),
    validatorId: r.str(), unitId: r.str(), ownerId: r.str(), routeId: r.str(),
    challengeNonce: r.str(),
    spentAtEpochSec: r.long(),
    deviceKeyFingerprint: grant.deviceKeyFingerprint,
  };
  const signature = r.bytes();
  const devicePublicKey = r.bytes();
  r.end();
  return { token, signature, grant: { grant, signature: grantSignature }, devicePublicKey };
}

export { concat, bytesEqual, Escritor, Lector };
