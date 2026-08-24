"""
Verifica el generador de codigos QR de la demostracion web (web/qr.js).

Tres comprobaciones, de la mas estricta a la mas realista:

1. Codificacion exacta contra la biblioteca segno, FORZANDO las ocho mascaras,
   pero solo con textos que llenan la capacidad justa del simbolo. Comparar solo
   el resultado automatico no serviria: si cada biblioteca elige una mascara
   distinta, todo difiere aunque ambas esten bien.

   Se excluyen los textos con relleno a proposito: segno añade ocho bits de
   relleno de mas cuando el flujo ya termina en frontera de codeword, mientras
   que el algoritmo de referencia (y este generador) no lo hacen. Los dos
   simbolos son validos y decodifican igual, porque el lector se detiene en el
   terminador, pero los bytes de relleno distintos cambian toda la matriz.

2. Lectura con zxing-cpp, el mismo motor de decodificacion que usan los
   telefonos Android. Es la prueba que de verdad importa.

3. Lectura con OpenCV, como segunda opinion, SOLO INFORMATIVA. El detector de
   OpenCV es conocido por atragantarse con imagenes sinteticas de versiones
   altas: falla tambien con los codigos de segno. La autoridad aqui es
   zxing-cpp, que es el motor que corre en los telefonos.

Uso:  python3 tools/verificar-qr.py
"""
import json
import subprocess
import sys

import cv2
import numpy as np
import segno
import zxingcpp

ESCALA = 6
MARGEN = 4  # zona tranquila exigida por la norma


def a_imagen(filas):
    size = len(filas)
    total = (size + MARGEN * 2) * ESCALA
    img = np.full((total, total), 255, dtype=np.uint8)
    for r, fila in enumerate(filas):
        for c, valor in enumerate(fila):
            if valor == "1":
                y, x = (r + MARGEN) * ESCALA, (c + MARGEN) * ESCALA
                img[y:y + ESCALA, x:x + ESCALA] = 0
    return img


def leer_zxing(filas):
    resultados = zxingcpp.read_barcodes(a_imagen(filas))
    return resultados[0].text if resultados else None


def leer_opencv(filas):
    ok, textos, _, _ = cv2.QRCodeDetector().detectAndDecodeMulti(a_imagen(filas))
    return textos[0] if ok and textos else None


def matriz_segno(texto, mascara=None):
    qr = segno.make(
        texto, error="m", mode="byte", boost_error=False, micro=False, mask=mascara
    )
    return qr.version, ["".join(str(int(m)) for m in fila) for fila in qr.matrix]


def main():
    casos = json.loads(subprocess.check_output(["node", "tools/qr-dump.mjs"], text=True))

    fallos = 0

    print("=== 1. Codificacion exacta contra segno, mascara por mascara ===")
    comparados = 0
    for caso in casos:
        if not caso["sinRelleno"]:
            continue
        comparados += 1
        texto = caso["text"]
        etiqueta = f"{len(texto.encode()):>5} bytes  v{caso['version']:<2}"
        malas = [
            m for m in range(8)
            if (caso["version"], caso["porMascara"][m]) != matriz_segno(texto, m)
        ]
        if malas:
            print(f"FALLO {etiqueta}: difiere de segno en las mascaras {malas}")
            fallos += 1
        else:
            print(f"ok    {etiqueta}: identico a segno en las 8 mascaras")
    if comparados == 0:
        print("AVISO: ningun caso llenaba la capacidad justa; nada que comparar")

    lectores = [
        ("zxing-cpp (el de Android)", leer_zxing, True),
        ("OpenCV (segunda opinion, informativa)", leer_opencv, False),
    ]
    for numero, (nombre, leer, decisivo) in enumerate(lectores, start=2):
        print()
        print(f"=== {numero}. Lectura con {nombre} ===")
        informativos = 0
        for caso in casos:
            texto = caso["text"]
            etiqueta = (
                f"{len(texto.encode()):>5} bytes  v{caso['version']:<2} "
                f"{caso['size']}x{caso['size']} mascara {caso['mask']}"
            )
            leido = leer(caso["rows"])
            if leido == texto:
                print(f"ok    {etiqueta}")
            else:
                # Solo cuenta como fallo nuestro si el mismo lector si consigue
                # leer la matriz que produce segno para el mismo texto.
                _, referencia = matriz_segno(texto)
                referencia_ok = leer(referencia) == texto
                if not referencia_ok:
                    print(f"-     {etiqueta}: tampoco lee la de segno, es el lector")
                elif decisivo:
                    print(f"FALLO {etiqueta}: no se lee (la de segno si)")
                    fallos += 1
                else:
                    print(f"-     {etiqueta}: este lector no lo lee (zxing si)")
                    informativos += 1

        if not decisivo and informativos:
            print(f"      ({informativos} caso(s) informativos, no cuentan como fallo)")

    print()
    if fallos:
        print(f"{fallos} comprobacion(es) fallaron")
        return 1
    print(f"las {len(casos)} entradas pasan todas las comprobaciones")
    return 0


if __name__ == "__main__":
    sys.exit(main())
