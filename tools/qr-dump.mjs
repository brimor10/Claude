// Vuelca matrices de codigos QR para que tools/verificar-qr.py las contraste.
import { qrMatrix, _internos } from '../web/qr.js';

const { dataCapacity, countBitsFor } = _internos;

/**
 * Longitud, en bytes, que llena EXACTAMENTE el simbolo de esta version: el
 * mensaje mas el terminador ocupan toda la capacidad y no se añade ni un byte
 * de relleno. Son los unicos casos comparables byte a byte con segno.
 */
function largoSinRelleno(version) {
  const capacidadBits = dataCapacity(version) * 8;
  const cabecera = 4 + countBitsFor(version);
  return Math.floor((capacidadBits - cabecera) / 8);
}

const casos = [];

// Un caso exacto por version: cubre las 40 versiones y las dos longitudes de
// indicador de cuenta (8 bits hasta la version 9, 16 bits a partir de la 10).
for (let v = 1; v <= 40; v++) {
  casos.push({ text: 'Q'.repeat(largoSinRelleno(v)), esperada: v });
}

// Barrido general, con relleno, para las pruebas de lectura.
for (const n of [1, 5, 20, 27, 60, 120, 206, 283, 300, 339, 416, 500, 667, 708, 782, 801, 892, 900, 1200, 1500, 2000]) {
  casos.push({ text: 'P'.repeat(n) });
}
casos.push({ text: 'PP1:SPD:' + 'x'.repeat(700) });
casos.push({ text: 'hola mundo, ñandú y acentós' });

const salida = casos.map(({ text }) => {
  const auto = qrMatrix(text);
  const bytes = new TextEncoder().encode(text).length;
  const capacidadBits = dataCapacity(auto.version) * 8;
  const bitsMensaje = 4 + countBitsFor(auto.version) + bytes * 8;

  return {
    text,
    version: auto.version,
    size: auto.size,
    mask: auto.mask,
    sinRelleno: capacidadBits - bitsMensaje < 8,
    rows: auto.modules.map((r) => r.join('')),
    porMascara: Array.from({ length: 8 }, (_, m) => qrMatrix(text, m).modules.map((r) => r.join(''))),
  };
});

console.log(JSON.stringify(salida));
