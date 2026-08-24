# Herramientas de verificación

Estas herramientas no hacen falta para compilar el proyecto: existen para
comprobar que la demostración web no miente.

## Dependencias

```bash
pip install segno opencv-python-headless zxing-cpp pillow numpy
npm install playwright
```

## `verificar-qr.py`

Verifica el generador de códigos QR de `web/qr.js`:

1. Codificación **idéntica a la biblioteca `segno`** en las 40 versiones,
   forzando las ocho máscaras. Comparar solo el resultado automático no serviría:
   si cada biblioteca elige una máscara distinta, todo difiere aunque ambas
   estén bien.
2. Lectura correcta con **`zxing-cpp`**, el mismo motor que corre en los
   teléfonos Android.
3. Segunda opinión con OpenCV, informativa: su detector se atraganta con
   imágenes sintéticas de versiones altas y falla también con los códigos de
   `segno`.

```bash
python3 tools/verificar-qr.py
```

## `generar-fixtures.mjs`

Genera un escenario completo (recarga, dos pagos encadenados, cobro) firmado por
la implementación **JavaScript** y lo guarda en
`web/fixtures-compatibilidad.json`.

Ese archivo lo verifica `CompatibilidadWebTest` en el módulo `:core`, con el
código Kotlin de producción: mismas firmas, misma serialización canónica, mismo
formato de QR, mismo encadenamiento. Si una implementación se desvía de la otra,
esa prueba falla.

```bash
node tools/generar-fixtures.mjs
./gradlew :core:test --tests '*CompatibilidadWebTest'
```

## `construir-demo.mjs`

Ensambla `web/demo.html` empotrando `web/qr.js`, `web/protocolo.js` y
`web/motor.js`. Se hace con un script y no a mano para que lo que corre en la
página publicada sea exactamente el mismo código que verifican las herramientas
de arriba.

```bash
node tools/construir-demo.mjs
```

## `probar-demo.mjs`

Abre `web/demo.html` en Chromium y ejecuta el recorrido completo (recargar,
cortar el internet, cobrar, pagar, verificar, reconectar, liquidar) más los
cuatro ataques, comprobando en cada paso que la página dice lo que tiene que
decir.

```bash
node tools/probar-demo.mjs           # solo comprobaciones
node tools/probar-demo.mjs --capturas # además guarda capturas de pantalla
```

## `qr-dump.mjs`

Auxiliar: vuelca las matrices de QR que `verificar-qr.py` contrasta. No se
ejecuta por separado.
