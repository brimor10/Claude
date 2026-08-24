// Generador de codigos QR: modo byte, nivel de correccion M.
//
// Escrito a mano porque la pagina se publica autocontenida (sin CDN ni npm en
// tiempo de ejecucion) y porque las bibliotecas que se probaron producian
// codigos ilegibles en algunas versiones.
//
// La correccion NO se da por supuesta: tools/verificar-qr.py compara esta
// salida contra la biblioteca segno forzando LAS OCHO mascaras, y ademas
// decodifica el resultado con OpenCV, que es lo que de verdad importa.
//
// Algoritmo segun ISO/IEC 18004. "QR Code" es marca registrada de DENSO WAVE.

// [codewords de correccion por bloque, bloques g1, datos g1, bloques g2, datos g2]
const EC_TABLE_M = [
  [10, 1, 16, 0, 0], [16, 1, 28, 0, 0], [26, 1, 44, 0, 0], [18, 2, 32, 0, 0],
  [24, 2, 43, 0, 0], [16, 4, 27, 0, 0], [18, 4, 31, 0, 0], [22, 2, 38, 2, 39],
  [22, 3, 36, 2, 37], [26, 4, 43, 1, 44], [30, 1, 50, 4, 51], [22, 6, 36, 2, 37],
  [22, 8, 37, 1, 38], [24, 4, 40, 5, 41], [24, 5, 41, 5, 42], [28, 7, 45, 3, 46],
  [28, 10, 46, 1, 47], [26, 9, 43, 4, 44], [26, 3, 44, 11, 45], [26, 3, 41, 13, 42],
  [26, 17, 42, 0, 0], [28, 17, 46, 0, 0], [28, 4, 47, 14, 48], [28, 6, 45, 14, 46],
  [28, 8, 47, 13, 48], [28, 19, 46, 4, 47], [28, 22, 45, 3, 46], [28, 3, 45, 23, 46],
  [28, 21, 45, 7, 46], [28, 19, 47, 10, 48], [28, 2, 46, 29, 47], [28, 10, 46, 23, 47],
  [28, 14, 46, 21, 47], [28, 14, 46, 23, 47], [28, 12, 47, 26, 48], [28, 6, 47, 34, 48],
  [28, 29, 46, 14, 47], [28, 13, 46, 32, 47], [28, 40, 47, 7, 48], [28, 18, 47, 31, 48],
];

const PENALTY_N1 = 3;
const PENALTY_N2 = 3;
const PENALTY_N3 = 40;
const PENALTY_N4 = 10;

// --- GF(256) para Reed-Solomon ----------------------------------------------

const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);
(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    EXP[i] = x;
    LOG[x] = i;
    x = (x << 1) ^ (x & 0x80 ? 0x11d : 0);
    x &= 0xff;
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
})();

const gfMul = (a, b) => (a === 0 || b === 0 ? 0 : EXP[LOG[a] + LOG[b]]);

/** Coeficientes del polinomio generador, sin el termino principal (que es 1). */
function rsDivisor(degree) {
  const result = new Uint8Array(degree);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = gfMul(result[j], root);
      if (j + 1 < degree) result[j] ^= result[j + 1];
    }
    root = gfMul(root, 0x02);
  }
  return result;
}

function rsRemainder(data, divisor) {
  const result = new Uint8Array(divisor.length);
  for (const b of data) {
    const factor = b ^ result[0];
    result.copyWithin(0, 1);
    result[result.length - 1] = 0;
    for (let i = 0; i < divisor.length; i++) result[i] ^= gfMul(divisor[i], factor);
  }
  return result;
}

// --- Datos -------------------------------------------------------------------

const dataCapacity = (version) => {
  const [, b1, d1, b2, d2] = EC_TABLE_M[version - 1];
  return b1 * d1 + b2 * d2;
};

const countBitsFor = (version) => (version <= 9 ? 8 : 16);

function pickVersion(byteLength) {
  for (let v = 1; v <= 40; v++) {
    if (dataCapacity(v) * 8 >= 4 + countBitsFor(v) + byteLength * 8) return v;
  }
  throw new Error('el contenido no cabe en un codigo QR');
}

function dataCodewords(bytes, version) {
  const bits = [];
  const push = (value, length) => {
    for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  };

  push(0b0100, 4); // indicador de modo byte
  push(bytes.length, countBitsFor(version));
  for (const b of bytes) push(b, 8);

  const capacityBits = dataCapacity(version) * 8;
  push(0, Math.min(4, capacityBits - bits.length)); // terminador
  while (bits.length % 8 !== 0) bits.push(0);

  const words = [];
  for (let i = 0; i < bits.length; i += 8) {
    let byte = 0;
    for (let j = 0; j < 8; j++) byte = (byte << 1) | bits[i + j];
    words.push(byte);
  }
  for (let pad = 0xec; words.length < dataCapacity(version); pad ^= 0xec ^ 0x11) {
    words.push(pad);
  }
  return words;
}

/** Reparte en bloques, calcula la correccion de errores y entrelaza. */
function addEccAndInterleave(words, version) {
  const [ecLen, b1, d1, b2, d2] = EC_TABLE_M[version - 1];
  const divisor = rsDivisor(ecLen);

  const blocks = [];
  let offset = 0;
  for (let i = 0; i < b1 + b2; i++) {
    const len = i < b1 ? d1 : d2;
    const dat = words.slice(offset, offset + len);
    offset += len;
    blocks.push({ dat, ecc: rsRemainder(dat, divisor) });
  }

  const out = [];
  const maxData = Math.max(d1, b2 > 0 ? d2 : 0);
  for (let i = 0; i < maxData; i++) {
    for (const block of blocks) if (i < block.dat.length) out.push(block.dat[i]);
  }
  for (let i = 0; i < ecLen; i++) {
    for (const block of blocks) out.push(block.ecc[i]);
  }
  return out;
}

// --- Matriz ------------------------------------------------------------------

function alignmentPositions(version) {
  if (version === 1) return [];
  const count = Math.floor(version / 7) + 2;
  const step = version === 32 ? 26 : Math.ceil((version * 4 + 17 - 13) / (count * 2 - 2)) * 2;
  const result = [6];
  for (let pos = version * 4 + 17 - 7; result.length < count; pos -= step) result.splice(1, 0, pos);
  return result;
}

class Canvas {
  constructor(version) {
    this.version = version;
    this.size = version * 4 + 17;
    this.modules = Array.from({ length: this.size }, () => new Uint8Array(this.size));
    this.isFunction = Array.from({ length: this.size }, () => new Uint8Array(this.size));
  }

  set(row, col, dark) {
    this.modules[row][col] = dark ? 1 : 0;
    this.isFunction[row][col] = 1;
  }

  inside(row, col) {
    return row >= 0 && row < this.size && col >= 0 && col < this.size;
  }

  drawFunctionPatterns() {
    for (let i = 0; i < this.size; i++) {
      this.set(6, i, i % 2 === 0);
      this.set(i, 6, i % 2 === 0);
    }
    // Localizadores (con su separador): los pinta encima del sincronismo.
    for (const [row, col] of [[3, 3], [3, this.size - 4], [this.size - 4, 3]]) {
      for (let dr = -4; dr <= 4; dr++) {
        for (let dc = -4; dc <= 4; dc++) {
          const dist = Math.max(Math.abs(dr), Math.abs(dc));
          if (this.inside(row + dr, col + dc)) {
            this.set(row + dr, col + dc, dist !== 2 && dist !== 4);
          }
        }
      }
    }
    // Patrones de alineacion, saltando los que chocan con los localizadores.
    const pos = alignmentPositions(this.version);
    for (let i = 0; i < pos.length; i++) {
      for (let j = 0; j < pos.length; j++) {
        const esquina = (i === 0 && j === 0) ||
          (i === 0 && j === pos.length - 1) ||
          (i === pos.length - 1 && j === 0);
        if (esquina) continue;
        for (let dr = -2; dr <= 2; dr++) {
          for (let dc = -2; dc <= 2; dc++) {
            this.set(pos[i] + dr, pos[j] + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
          }
        }
      }
    }
    // Mascara provisional: sirve para marcar como funcion las celdas de formato.
    this.drawFormatBits(0);
    this.drawVersion();
  }

  drawFormatBits(mask) {
    const data = (0b00 << 3) | mask; // 00 = nivel M
    let rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412;
    const bit = (i) => (bits >>> i) & 1;

    // Primera copia, junto al localizador superior izquierdo.
    for (let i = 0; i <= 5; i++) this.set(i, 8, bit(i));
    this.set(7, 8, bit(6));
    this.set(8, 8, bit(7));
    this.set(8, 7, bit(8));
    for (let i = 9; i < 15; i++) this.set(8, 14 - i, bit(i));

    // Segunda copia, repartida entre los otros dos localizadores.
    for (let i = 0; i < 8; i++) this.set(8, this.size - 1 - i, bit(i));
    for (let i = 8; i < 15; i++) this.set(this.size - 15 + i, 8, bit(i));

    // Modulo siempre oscuro: va al final porque pisa un bit de la segunda copia.
    this.set(this.size - 8, 8, true);
  }

  drawVersion() {
    if (this.version < 7) return;
    let rem = this.version;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const bits = (this.version << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const dark = ((bits >>> i) & 1) !== 0;
      const a = this.size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      this.set(a, b, dark);
      this.set(b, a, dark);
    }
  }

  drawCodewords(words) {
    let i = 0;
    const totalBits = words.length * 8;
    for (let right = this.size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5; // la columna 6 es de sincronismo
      for (let vert = 0; vert < this.size; vert++) {
        for (let j = 0; j < 2; j++) {
          const col = right - j;
          const upward = ((right + 1) & 2) === 0;
          const row = upward ? this.size - 1 - vert : vert;
          if (!this.isFunction[row][col] && i < totalBits) {
            this.modules[row][col] = (words[i >>> 3] >>> (7 - (i & 7))) & 1;
            i++;
          }
        }
      }
    }
  }

  /** La mascara es su propia inversa: aplicarla dos veces la deshace. */
  applyMask(mask) {
    for (let row = 0; row < this.size; row++) {
      for (let col = 0; col < this.size; col++) {
        if (this.isFunction[row][col]) continue;
        let invert;
        switch (mask) {
          case 0: invert = (col + row) % 2 === 0; break;
          case 1: invert = row % 2 === 0; break;
          case 2: invert = col % 3 === 0; break;
          case 3: invert = (col + row) % 3 === 0; break;
          case 4: invert = (Math.floor(col / 3) + Math.floor(row / 2)) % 2 === 0; break;
          case 5: invert = ((col * row) % 2) + ((col * row) % 3) === 0; break;
          case 6: invert = (((col * row) % 2) + ((col * row) % 3)) % 2 === 0; break;
          case 7: invert = (((col + row) % 2) + ((col * row) % 3)) % 2 === 0; break;
          default: throw new Error('mascara invalida');
        }
        if (invert) this.modules[row][col] ^= 1;
      }
    }
  }

  penalty() {
    let result = 0;
    const size = this.size;

    const addHistory = (run, history) => {
      if (history[0] === 0) run += size; // borde claro implicito al inicio
      history.pop();
      history.unshift(run);
    };
    const countPatterns = (h) => {
      const n = h[1];
      const core = n > 0 && h[2] === n && h[3] === n * 3 && h[4] === n && h[5] === n;
      return (core && h[0] >= n * 4 && h[6] >= n ? 1 : 0) +
        (core && h[6] >= n * 4 && h[0] >= n ? 1 : 0);
    };
    const terminate = (color, run, history) => {
      if (color) {
        addHistory(run, history);
        run = 0;
      }
      addHistory(run + size, history);
      return countPatterns(history);
    };

    for (const porFilas of [true, false]) {
      for (let i = 0; i < size; i++) {
        const at = (j) => (porFilas ? this.modules[i][j] : this.modules[j][i]);
        const history = [0, 0, 0, 0, 0, 0, 0];
        let color = 0;
        let run = 0;
        for (let j = 0; j < size; j++) {
          if (at(j) === color) {
            run++;
            if (run === 5) result += PENALTY_N1;
            else if (run > 5) result++;
          } else {
            addHistory(run, history);
            if (!color) result += countPatterns(history) * PENALTY_N3;
            color = at(j);
            run = 1;
          }
        }
        result += terminate(color, run, history) * PENALTY_N3;
      }
    }

    for (let row = 0; row < size - 1; row++) {
      for (let col = 0; col < size - 1; col++) {
        const v = this.modules[row][col];
        if (v === this.modules[row][col + 1] &&
          v === this.modules[row + 1][col] &&
          v === this.modules[row + 1][col + 1]) {
          result += PENALTY_N2;
        }
      }
    }

    let dark = 0;
    for (let row = 0; row < size; row++) {
      for (let col = 0; col < size; col++) dark += this.modules[row][col];
    }
    const total = size * size;
    const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
    return result + k * PENALTY_N4;
  }
}

// Se exportan para que las herramientas de verificacion puedan inspeccionar
// los pasos intermedios, no para uso general.
export const _internos = { dataCodewords, addEccAndInterleave, pickVersion, dataCapacity, countBitsFor };

/**
 * @param {string} text
 * @param {number|null} forcedMask mascara fija (0-7); null para elegir la mejor.
 * @returns {{ version: number, size: number, mask: number, modules: number[][] }}
 */
export function qrMatrix(text, forcedMask = null) {
  const bytes = Array.from(new TextEncoder().encode(text));
  const version = pickVersion(bytes.length);

  const canvas = new Canvas(version);
  canvas.drawFunctionPatterns();
  canvas.drawCodewords(addEccAndInterleave(dataCodewords(bytes, version), version));

  let mask = forcedMask;
  if (mask === null) {
    let best = Infinity;
    for (let i = 0; i < 8; i++) {
      canvas.applyMask(i);
      canvas.drawFormatBits(i);
      const score = canvas.penalty();
      if (score < best) {
        best = score;
        mask = i;
      }
      canvas.applyMask(i); // deshacer
    }
  }
  canvas.applyMask(mask);
  canvas.drawFormatBits(mask);

  return {
    version,
    size: canvas.size,
    mask,
    modules: canvas.modules.map((fila) => Array.from(fila)),
  };
}

/** Dibuja el codigo en un canvas, con la zona tranquila que exige la norma. */
export function drawQr(canvasEl, text, { scale = 4, quiet = 4 } = {}) {
  const { size, modules } = qrMatrix(text);
  const total = (size + quiet * 2) * scale;
  canvasEl.width = total;
  canvasEl.height = total;
  const ctx = canvasEl.getContext('2d');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, total, total);
  ctx.fillStyle = '#000000';
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) {
      if (modules[r][c]) ctx.fillRect((c + quiet) * scale, (r + quiet) * scale, scale, scale);
    }
  }
  return { size, total };
}
