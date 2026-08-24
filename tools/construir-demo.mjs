// Ensambla web/demo.html a partir de la plantilla y de los modulos verificados.
//
// La pagina se publica autocontenida (sin CDN ni modulos externos), asi que el
// codigo hay que empotrarlo. Se hace con este script y no a mano para que lo
// que corre en la demostracion sea EXACTAMENTE el mismo codigo que verifican
// tools/verificar-qr.py y CompatibilidadWebTest.kt.
//
// Uso:  node tools/construir-demo.mjs

import { readFileSync, writeFileSync } from 'node:fs';

const raiz = new URL('..', import.meta.url);
const leer = (ruta) => readFileSync(new URL(ruta, raiz), 'utf8');

/** Quita imports y exports para poder concatenar los modulos en uno solo. */
function aplanar(fuente, nombre) {
  const sinImports = fuente.replace(/import\s+[\s\S]*?from\s+'\.[^']*';\n?/g, '');
  const sinReexports = sinImports.replace(/^export\s*\{[^}]*\};\s*$/gm, '');
  const sinExports = sinReexports.replace(/^export\s+(const|let|function|async|class)\b/gm, '$1');
  return `// ===== ${nombre} =====\n${sinExports.trim()}\n`;
}

const modulos = ['web/qr.js', 'web/protocolo.js', 'web/motor.js'];
const codigo = modulos.map((m) => aplanar(leer(m), m)).join('\n');

const plantilla = leer('web/demo.plantilla.html');
if (!plantilla.includes('/*__MODULOS__*/')) {
  throw new Error('la plantilla no tiene el marcador /*__MODULOS__*/');
}

const salida = plantilla.replace('/*__MODULOS__*/', () => codigo);
writeFileSync(new URL('web/demo.html', raiz), salida);

// Se deja tambien el modulo suelto para poder pasarle `node --check`.
writeFileSync(new URL('web/demo.bundle.js', raiz), codigo);

console.log(`web/demo.html escrito (${Math.round(salida.length / 1024)} KB)`);
