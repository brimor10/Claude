// Monedero, validador y servidor: la misma logica que el modulo :core en Kotlin.
// Ver web/protocolo.js para las primitivas y la nota sobre compatibilidad.

import {
  CEROS_32, MODO, MONEDA, Money, b64urlEncode, bytesDelCertificado,
  bytesDelCobroDirecto, bytesDelGasto, bytesDelRecibo, bytesDelVale, bytesDelReto,
  bytesFirmados, codificarCobroDirecto, codificarGasto, codificarReto,
  firmar, generarClaves, huellaDeClave, idDelEslabon, hashDelEslabon, nonce,
  verificar,
} from './protocolo.js';

export const ahora = () => Math.floor(Date.now() / 1000);

// --- Servidor emisor y liquidador ------------------------------------------------

export const POLITICA_POR_DEFECTO = {
  vigenciaDelValeSegundos: 30 * 24 * 3600,
  vigenciaDelCertificadoSegundos: 365 * 24 * 3600,
  /**
   * Tope de gasto sin sincronizar: la PERDIDA MAXIMA que puede causar un
   * telefono comprometido antes de que el sistema lo bloquee. Por defecto, unos
   * diez pasajes de 235 Bs.
   *
   * Ojo al ajustarlo: si queda por debajo de un pasaje, nadie puede pagar sin
   * señal y el sistema no sirve para lo que se hizo.
   */
  topeOfflineCentimos: 2350_00,
  topeDeViajesOffline: 30,
  comisionPuntosBasicos: 300,
  /**
   * Cuanto se espera, tras vencer la ventana de un cobro directo, antes de
   * devolverle el dinero al pasajero si ningun validador lo reclamo.
   */
  graciaParaDevolverSegundos: 24 * 3600,
};

export class Servidor {
  constructor(politica = POLITICA_POR_DEFECTO, reloj = ahora) {
    this.politica = { ...politica };
    this.reloj = reloj;
    this.cuentas = new Map();
    this.vales = new Map();
    this.libro = new Map();       // "grantId#seq" -> gasto firmado
    this.doblesGastos = [];
    this.recibosSinLiquidar = new Map();
    this.clavesDeValidadores = new Map();
    this.reclamosPorEslabon = new Map();
    this.reclamosDuplicados = [];
    this.recibosRechazados = [];
    this.devueltos = new Set();
    this.contador = 0;
  }

  async iniciar() {
    this.emisor = await generarClaves();
    this.emisorKeyId = 'emisor-demo-2026-01';
    return this;
  }

  get anclas() {
    return new Map([[this.emisorKeyId, this.emisor.publicKeyEncoded]]);
  }

  id(prefijo) { return `${prefijo}-${++this.contador}`; }

  async registrarMonedero(devicePublicKey, walletId = null) {
    const id = walletId || this.id('w');
    this.cuentas.set(id, {
      walletId: id,
      deviceFingerprint: await huellaDeClave(devicePublicKey),
      bloqueado: false,
      deudaCentimos: 0,
    });
    return id;
  }

  async registrarValidador(validatorId, unitId, ownerId, validatorPublicKey) {
    const t = this.reloj();
    const cert = {
      validatorId, unitId, ownerId,
      publicKeyFingerprint: await huellaDeClave(validatorPublicKey),
      issuedAtEpochSec: t,
      expiresAtEpochSec: t + this.politica.vigenciaDelCertificadoSegundos,
      issuerKeyId: this.emisorKeyId,
    };
    this.clavesDeValidadores.set(validatorId, validatorPublicKey);
    return { cert, signature: await firmar(this.emisor.privateKey, bytesDelCertificado(cert)) };
  }

  /** Recarga: cobra, retiene el dinero y firma el vale. Requiere internet. */
  async recargar(walletId, amountCentimos) {
    const cuenta = this.cuentas.get(walletId);
    if (!cuenta) throw new Error(`monedero no registrado: ${walletId}`);
    if (cuenta.bloqueado) throw new Error('monedero bloqueado por fraude');
    if (amountCentimos <= 0) throw new Error('la recarga debe ser positiva');

    const t = this.reloj();
    const grant = {
      grantId: this.id('g'),
      walletId,
      deviceKeyFingerprint: cuenta.deviceFingerprint,
      amountCentimos,
      currency: MONEDA,
      issuedAtEpochSec: t,
      expiresAtEpochSec: t + this.politica.vigenciaDelValeSegundos,
      offlineSpendCapCentimos: Math.max(0, Math.min(amountCentimos, this.politica.topeOfflineCentimos)),
      offlineTripCap: this.politica.topeDeViajesOffline,
      issuerKeyId: this.emisorKeyId,
      nonce: nonce(),
    };
    this.vales.set(grant.grantId, {
      grantId: grant.grantId, walletId, amountCentimos,
      garantiaCentimos: amountCentimos, gastadoLiquidadoCentimos: 0,
    });
    return { grant, signature: await firmar(this.emisor.privateKey, bytesDelVale(grant)) };
  }

  /**
   * Registra un gasto y detecta bifurcaciones: dos gastos DISTINTOS en la misma
   * posicion (grantId, seq) de la cadena son doble gasto, y las dos firmas son
   * del propio usuario.
   */
  async registrarGasto(gasto) {
    const t = gasto.token;
    const clave = `${t.grantId}#${t.seq}`;
    const previo = this.libro.get(clave);
    if (!previo) {
      this.libro.set(clave, gasto);
      return;
    }
    const mismoEslabon = (await idDelEslabon(previo)) === (await idDelEslabon(gasto));
    if (mismoEslabon) return;
    const yaVisto = this.doblesGastos.some((d) => d.grantId === t.grantId && d.seq === t.seq);
    if (!yaVisto) {
      this.doblesGastos.push({
        walletId: t.walletId, grantId: t.grantId, seq: t.seq,
        ramaA: previo, ramaB: gasto,
      });
    }
  }

  /**
   * El validador sube sus recibos. Van firmados por el, asi que el servidor no
   * tiene que fiarse del canal: sin eso, en el modo de cobro directo cualquiera
   * podria reclamar el pago de otro, porque el pago del pasajero no dice a que
   * unidad va.
   */
  async subirRecibos(validatorId, recibos) {
    const ids = [];
    for (const recibo of recibos) {
      const c = recibo.recibo.claim;
      const clave = this.clavesDeValidadores.get(c.validatorId);
      if (!clave || !(await verificar(clave, bytesDelRecibo(c), recibo.recibo.signature))) {
        this.recibosRechazados.push([recibo.receiptId, 'recibo no firmado por un validador registrado']);
        continue;
      }
      const previo = this.reclamosPorEslabon.get(c.linkId);
      if (previo && previo.recibo.claim.validatorId !== c.validatorId) {
        // Un mismo pago reclamado por DOS unidades: en el modo directo puede
        // pasar si el pasajero enseño el mismo QR en dos sitios dentro de la
        // ventana. Se paga al primero y queda constancia.
        this.reclamosDuplicados.push({
          linkId: c.linkId,
          walletId: recibo.spend.token.walletId,
          primero: previo.recibo.claim,
          segundo: c,
        });
        continue;
      }
      await this.registrarGasto(recibo.spend);
      if (!this.reclamosPorEslabon.has(c.linkId)) this.reclamosPorEslabon.set(c.linkId, recibo);
      this.recibosSinLiquidar.set(recibo.receiptId, recibo);
      ids.push(recibo.receiptId);
    }
    return ids;
  }

  async sincronizarMonedero(walletId, gastos) {
    const confirmados = [];
    for (const gasto of gastos) {
      if (gasto.token.walletId !== walletId) continue;
      await this.registrarGasto(gasto);
      confirmados.push(await idDelEslabon(gasto));
    }
    const grantId = gastos[0]?.token?.grantId;
    return {
      confirmados,
      tiempoServidorEpochSec: this.reloj(),
      gastadoRealCentimos: grantId ? await this.gastadoReal(grantId) : null,
    };
  }

  /**
   * Lo que de verdad se ha gastado de un vale: todo lo registrado menos lo
   * devuelto. Es la cifra que manda sobre el saldo que muestra el telefono.
   */
  async gastadoReal(grantId) {
    let total = 0;
    for (const gasto of this.libro.values()) {
      if (gasto.token.grantId !== grantId) continue;
      if (this.devueltos.has(await idDelEslabon(gasto))) continue;
      total += gasto.token.amountCentimos;
    }
    return total;
  }

  /**
   * Devuelve el dinero de los cobros directos que ningun validador reclamo.
   *
   * En el modo directo el telefono descuenta al generar el QR, sin poder saber
   * si alguien llego a leerlo. Si el escaneo fallo, ese dinero tiene que volver.
   */
  async devolverNoReclamados() {
    const t = this.reloj();
    const devueltos = [];
    for (const gasto of this.libro.values()) {
      if (gasto.modo !== MODO.DIRECTO) continue;
      const id = await idDelEslabon(gasto);
      if (this.devueltos.has(id) || this.reclamosPorEslabon.has(id)) continue;
      const vence = gasto.token.validFromEpochSec + gasto.token.windowSeconds;
      if (t < vence + this.politica.graciaParaDevolverSegundos) continue;
      this.devueltos.add(id);
      devueltos.push(gasto);
    }
    return devueltos;
  }

  /** Cierra las cuentas: bloquea a los defraudadores y calcula la perdida. */
  reconciliar() {
    const bloqueados = [];
    let descubierto = 0;
    for (const prueba of this.doblesGastos) {
      const cuenta = this.cuentas.get(prueba.walletId);
      if (!cuenta) continue;
      if (!cuenta.bloqueado) {
        cuenta.bloqueado = true;
        bloqueados.push(cuenta.walletId);
      }
      const perdida = prueba.ramaB.token.amountCentimos;
      const vale = this.vales.get(prueba.grantId);
      const deLaGarantia = Math.min(perdida, vale ? vale.garantiaCentimos : 0);
      if (vale) vale.garantiaCentimos -= deLaGarantia;
      const resto = perdida - deLaGarantia;
      cuenta.deudaCentimos += resto;
      descubierto += resto;
    }
    return {
      gastosProcesados: this.libro.size,
      doblesGastos: this.doblesGastos,
      monederosBloqueados: bloqueados,
      descubiertoCentimos: descubierto,
      reclamosDuplicados: this.reclamosDuplicados,
    };
  }

  listaNegra() {
    return new Set([...this.cuentas.values()].filter((c) => c.bloqueado).map((c) => c.walletId));
  }

  /** Lo que se le paga al dueño de la unidad, en bolivares. */
  liquidar(ownerId) {
    const mios = [...this.recibosSinLiquidar.values()].filter((r) => r.ownerId === ownerId);
    const bruto = mios.reduce((n, r) => n + r.spend.token.amountCentimos, 0);
    const comision = Math.floor((bruto * this.politica.comisionPuntosBasicos) / 10_000);
    for (const r of mios) {
      this.recibosSinLiquidar.delete(r.receiptId);
      const vale = this.vales.get(r.spend.token.grantId);
      if (vale) {
        vale.garantiaCentimos = Math.max(0, vale.garantiaCentimos - r.spend.token.amountCentimos);
      }
    }
    return {
      ownerId, pasajes: mios.length,
      brutoCentimos: bruto, comisionCentimos: comision, netoCentimos: bruto - comision,
    };
  }
}

// --- Monedero del pasajero ---------------------------------------------------------

export const MOTIVO = {
  SIN_SALDO: 'No tienes saldo cargado',
  SALDO_INSUFICIENTE: 'Saldo insuficiente',
  TOPE_OFFLINE: 'Llegaste al tope sin conexion: conectate para liberar mas saldo',
  TOPE_VIAJES: 'Llegaste al tope de viajes sin conexion',
  VALE_CADUCADO: 'Tu saldo vencio',
  RETO_CADUCADO: 'El QR del cobrador ya vencio, pide otro',
  RETO_YA_USADO: 'Ese cobro ya lo pagaste',
  VALIDADOR_NO_REGISTRADO: 'Cuidado: ese cobrador no esta registrado. No pagues.',
  FIRMA_DEL_RETO: 'Cuidado: ese cobrador no esta registrado. No pagues.',
  VALE_DE_OTRO_TELEFONO: 'Ese saldo es de otro telefono',
  FIRMA_DEL_VALE: 'Ese saldo no lo emitio el servidor',
  EMISOR_DESCONOCIDO: 'Ese saldo viene de un emisor desconocido',
};

export class Monedero {
  constructor(claves, huella, anclas, reloj = ahora) {
    this.claves = claves;
    this.huella = huella;
    this.anclas = anclas;
    this.reloj = reloj;
    this.vales = [];
  }

  static async crear(anclas, reloj = ahora) {
    const claves = await generarClaves();
    return new Monedero(claves, await huellaDeClave(claves.publicKeyEncoded), anclas, reloj);
  }

  get saldoCentimos() {
    return this.vales.reduce((n, v) => n + v.saldoCentimos, 0);
  }

  get gastableOfflineCentimos() {
    return this.vales.reduce(
      (n, v) => n + Math.min(v.saldoCentimos, Math.max(0, v.signedGrant.grant.offlineSpendCapCentimos - v.offlineGastadoCentimos)),
      0,
    );
  }

  get pendientes() {
    return this.vales.flatMap((v) => v.pendientes);
  }

  async cargarVale(signedGrant) {
    const g = signedGrant.grant;
    const ancla = this.anclas.get(g.issuerKeyId);
    if (!ancla) return { ok: false, motivo: MOTIVO.EMISOR_DESCONOCIDO };
    if (!(await verificar(ancla, bytesDelVale(g), signedGrant.signature))) {
      return { ok: false, motivo: MOTIVO.FIRMA_DEL_VALE };
    }
    if (g.deviceKeyFingerprint !== this.huella) {
      return { ok: false, motivo: MOTIVO.VALE_DE_OTRO_TELEFONO };
    }
    this.vales.push({
      signedGrant,
      siguienteSeq: 1,
      ultimoHash: CEROS_32,
      saldoCentimos: g.amountCentimos,
      offlineGastadoCentimos: 0,
      offlineViajes: 0,
      pendientes: [],
      noncesUsados: new Set(),
      pisoDeTiempo: g.issuedAtEpochSec,
    });
    return { ok: true };
  }

  /** Instantanea del estado, para simular un respaldo del telefono. */
  respaldar() {
    return this.vales.map((v) => ({
      ...v,
      pendientes: [...v.pendientes],
      noncesUsados: new Set(v.noncesUsados),
    }));
  }

  restaurar(respaldo) {
    this.vales = respaldo.map((v) => ({
      ...v,
      pendientes: [...v.pendientes],
      noncesUsados: new Set(v.noncesUsados),
    }));
  }

  /** Paga un pasaje SIN INTERNET. */
  async pagar(retoFirmado) {
    if (this.vales.length === 0) return { ok: false, motivo: MOTIVO.SIN_SALDO };

    const c = retoFirmado.challenge;
    const cert = retoFirmado.cert.cert;

    const ancla = this.anclas.get(cert.issuerKeyId);
    if (!ancla || !(await verificar(ancla, bytesDelCertificado(cert), retoFirmado.cert.signature))) {
      return { ok: false, motivo: MOTIVO.VALIDADOR_NO_REGISTRADO };
    }
    const huellaReto = await huellaDeClave(retoFirmado.validatorPublicKey);
    if (cert.publicKeyFingerprint !== huellaReto ||
      cert.validatorId !== c.validatorId || cert.unitId !== c.unitId || cert.ownerId !== c.ownerId) {
      return { ok: false, motivo: MOTIVO.VALIDADOR_NO_REGISTRADO };
    }
    if (!(await verificar(retoFirmado.validatorPublicKey, bytesDelReto(c), retoFirmado.signature))) {
      return { ok: false, motivo: MOTIVO.FIRMA_DEL_RETO };
    }

    // Ordenados por vencimiento: primero el que caduca antes (FEFO).
    const candidatos = [...this.vales].sort(
      (a, b) => a.signedGrant.grant.expiresAtEpochSec - b.signedGrant.grant.expiresAtEpochSec,
    );
    const motivos = [];

    for (const vale of candidatos) {
      const g = vale.signedGrant.grant;
      // La hora del reto viene firmada: sirve de fuente de tiempo confiable y
      // empuja el piso, de modo que atrasar el reloj del telefono no sirve.
      const piso = Math.max(vale.pisoDeTiempo, c.issuedAtEpochSec);
      const t = Math.max(this.reloj(), piso);

      if (t > c.issuedAtEpochSec + c.ttlSeconds) return { ok: false, motivo: MOTIVO.RETO_CADUCADO };
      if (vale.noncesUsados.has(c.challengeNonce)) { motivos.push(MOTIVO.RETO_YA_USADO); continue; }
      if (t >= g.expiresAtEpochSec) { motivos.push(MOTIVO.VALE_CADUCADO); continue; }
      if (c.fareCentimos > vale.saldoCentimos) { motivos.push(MOTIVO.SALDO_INSUFICIENTE); continue; }
      if (c.fareCentimos > g.offlineSpendCapCentimos - vale.offlineGastadoCentimos) {
        motivos.push(MOTIVO.TOPE_OFFLINE);
        continue;
      }
      if (vale.offlineViajes >= g.offlineTripCap) { motivos.push(MOTIVO.TOPE_VIAJES); continue; }

      const token = {
        grantId: g.grantId,
        walletId: g.walletId,
        seq: vale.siguienteSeq,
        amountCentimos: c.fareCentimos,
        balanceAfterCentimos: vale.saldoCentimos - c.fareCentimos,
        prevHash: vale.ultimoHash,
        validatorId: c.validatorId, unitId: c.unitId, ownerId: c.ownerId, routeId: c.routeId,
        challengeNonce: c.challengeNonce,
        spentAtEpochSec: t,
        deviceKeyFingerprint: this.huella,
      };
      const signature = await firmar(this.claves.privateKey, bytesDelGasto(token));
      const gasto = {
        modo: MODO.RETO, token, signature,
        grant: vale.signedGrant, devicePublicKey: this.claves.publicKeyEncoded,
      };

      vale.siguienteSeq += 1;
      vale.ultimoHash = await hashDelEslabon(gasto);
      vale.saldoCentimos -= c.fareCentimos;
      vale.offlineGastadoCentimos += c.fareCentimos;
      vale.offlineViajes += 1;
      vale.pendientes.push(gasto);
      vale.noncesUsados.add(c.challengeNonce);
      vale.pisoDeTiempo = t;

      return { ok: true, gasto, qr: codificarGasto(gasto) };
    }

    const prioridad = [
      MOTIVO.RETO_YA_USADO, MOTIVO.VALE_CADUCADO, MOTIVO.TOPE_VIAJES,
      MOTIVO.TOPE_OFFLINE, MOTIVO.SALDO_INSUFICIENTE,
    ];
    const motivo = prioridad.find((m) => motivos.includes(m)) || MOTIVO.SIN_SALDO;
    return { ok: false, motivo };
  }

  /**
   * Modo de COBRO DIRECTO: genera el QR que el pasajero enseña para que lo lea
   * el lector de la unidad, sin haber visto nada del cobrador.
   *
   * Ojo con lo que implica: el saldo se descuenta AQUI, al generar el QR, sin
   * saber todavia si alguien va a leerlo. Si el escaneo no ocurre, el servidor
   * devuelve el dinero al reconciliar, porque no habra ningun recibo firmado que
   * reclame ese pago. Por eso el QR vale solo unos segundos.
   */
  async presentar(fareCentimos, ventanaSegundos = 90) {
    if (this.vales.length === 0) return { ok: false, motivo: MOTIVO.SIN_SALDO };

    const candidatos = [...this.vales].sort(
      (a, b) => a.signedGrant.grant.expiresAtEpochSec - b.signedGrant.grant.expiresAtEpochSec,
    );
    const motivos = [];

    for (const vale of candidatos) {
      const g = vale.signedGrant.grant;
      const t = Math.max(this.reloj(), vale.pisoDeTiempo);

      if (t >= g.expiresAtEpochSec) { motivos.push(MOTIVO.VALE_CADUCADO); continue; }
      if (fareCentimos > vale.saldoCentimos) { motivos.push(MOTIVO.SALDO_INSUFICIENTE); continue; }
      if (fareCentimos > g.offlineSpendCapCentimos - vale.offlineGastadoCentimos) {
        motivos.push(MOTIVO.TOPE_OFFLINE);
        continue;
      }
      if (vale.offlineViajes >= g.offlineTripCap) { motivos.push(MOTIVO.TOPE_VIAJES); continue; }

      const token = {
        grantId: g.grantId,
        walletId: g.walletId,
        seq: vale.siguienteSeq,
        amountCentimos: fareCentimos,
        balanceAfterCentimos: vale.saldoCentimos - fareCentimos,
        prevHash: vale.ultimoHash,
        validFromEpochSec: t,
        windowSeconds: ventanaSegundos,
        nonce: nonce(),
        deviceKeyFingerprint: this.huella,
      };
      const signature = await firmar(this.claves.privateKey, bytesDelCobroDirecto(token));
      const gasto = {
        modo: MODO.DIRECTO, token, signature,
        grant: vale.signedGrant, devicePublicKey: this.claves.publicKeyEncoded,
      };

      vale.siguienteSeq += 1;
      vale.ultimoHash = await hashDelEslabon(gasto);
      vale.saldoCentimos -= fareCentimos;
      vale.offlineGastadoCentimos += fareCentimos;
      vale.offlineViajes += 1;
      vale.pendientes.push(gasto);
      vale.pisoDeTiempo = t;

      return {
        ok: true, gasto,
        qr: codificarCobroDirecto(gasto),
        venceEn: t + ventanaSegundos,
      };
    }

    const prioridad = [MOTIVO.VALE_CADUCADO, MOTIVO.TOPE_VIAJES, MOTIVO.TOPE_OFFLINE, MOTIVO.SALDO_INSUFICIENTE];
    return { ok: false, motivo: prioridad.find((m) => motivos.includes(m)) || MOTIVO.SIN_SALDO };
  }

  aplicarSincronizacion(ack) {
    const confirmados = new Set(ack.confirmados);
    for (const vale of this.vales) {
      vale.pendientesConfirmados = vale.pendientes.filter((p) => confirmados.has(p.__id));
    }
  }

  /** Version simple para la demo: se repone el cupo de lo que el servidor confirmo. */
  async aplicarAck(ack) {
    const confirmados = new Set(ack.confirmados);
    for (const vale of this.vales) {
      const quedan = [];
      for (const p of vale.pendientes) {
        if (!confirmados.has(await idDelEslabon(p))) quedan.push(p);
      }
      vale.pendientes = quedan;
      vale.offlineGastadoCentimos = quedan.reduce((n, p) => n + p.token.amountCentimos, 0);
      vale.offlineViajes = quedan.length;
      vale.pisoDeTiempo = Math.max(vale.pisoDeTiempo, ack.tiempoServidorEpochSec);
      if (quedan.length === 0) vale.noncesUsados = new Set();
      // El servidor manda sobre el saldo: aqui entran tanto las correcciones por
      // fraude como las devoluciones de cobros directos que nadie reclamo.
      if (ack.gastadoRealCentimos !== null && ack.gastadoRealCentimos !== undefined) {
        vale.saldoCentimos = vale.signedGrant.grant.amountCentimos - ack.gastadoRealCentimos;
      }
    }
  }
}

// --- Validador de la unidad ----------------------------------------------------------

export const RECHAZO = {
  QR_ILEGIBLE: 'QR ilegible',
  SALDO_FALSO: 'SALDO FALSO — no lo dejes pasar',
  EMISOR_DESCONOCIDO: 'SALDO FALSO — no lo dejes pasar',
  CLAVE_AJENA: 'Saldo copiado de otro telefono',
  FIRMA_INVALIDA: 'Firma del pago invalida',
  OTRA_UNIDAD: 'Ese pago era para otra unidad',
  DOBLE_GASTO: 'Pago repetido — ya se cobro',
  BLOQUEADO: 'Monedero bloqueado por fraude',
  VALE_CADUCADO: 'El saldo del pasajero vencio',
  RETO_DESCONOCIDO: 'Ese QR no responde a este cobro',
  RETO_CADUCADO: 'El cobro vencio, genera otro',
  MONTO: 'El monto no es el del pasaje',
  ARITMETICA: 'Cuentas del pago invalidas',
  FECHA: 'Hora del pago fuera de rango',
  PAGO_VENCIDO: 'El QR del pasajero venció, que genere otro',
  MODO_NO_ACEPTADO: 'Esta unidad cobra mostrando su propio QR primero',
};

export class Validador {
  constructor(config, claves, cert, anclas, reloj = ahora) {
    this.config = config;
    this.claves = claves;
    this.cert = cert;
    this.anclas = anclas;
    this.reloj = reloj;
    this.retosAbiertos = new Map();
    this.eslabonesVistos = new Map();
    this.recibos = new Map();
    this.listaNegra = new Set();
    this.eslabonesVistos = this.eslabonesVistos || new Map();
  }

  static async crear(servidor, { validatorId, unitId, ownerId, routeId, fareCentimos, ttlSeconds = 45, aceptaDirecto = true }, reloj = ahora) {
    const claves = await generarClaves();
    const cert = await servidor.registrarValidador(validatorId, unitId, ownerId, claves.publicKeyEncoded);
    return new Validador(
      {
        validatorId, unitId, ownerId, routeId, fareCentimos, ttlSeconds,
        desfaseMaximoSegundos: 120,
        // Si esta unidad acepta el modo de un solo escaneo. Mas rapido en la
        // puerta, mas debil contra la repeticion: decision del operador.
        aceptaDirecto,
      },
      claves, cert, servidor.anclas, reloj,
    );
  }

  get acumuladoCentimos() {
    return [...this.recibos.values()].reduce((n, r) => n + r.spend.token.amountCentimos, 0);
  }

  get recibosPendientes() { return [...this.recibos.values()]; }

  /** Abre un cobro: nonce nuevo y caducidad corta. */
  async nuevoReto(fareCentimos = this.config.fareCentimos) {
    const t = this.reloj();
    for (const [n, c] of this.retosAbiertos) {
      if (t > c.issuedAtEpochSec + c.ttlSeconds) this.retosAbiertos.delete(n);
    }
    const challenge = {
      validatorId: this.config.validatorId,
      unitId: this.config.unitId,
      ownerId: this.config.ownerId,
      routeId: this.config.routeId,
      fareCentimos,
      challengeNonce: nonce(),
      issuedAtEpochSec: t,
      ttlSeconds: this.config.ttlSeconds,
      validatorKeyId: this.config.validatorId,
    };
    this.retosAbiertos.set(challenge.challengeNonce, challenge);
    const firmado = {
      challenge,
      signature: await firmar(this.claves.privateKey, bytesDelReto(challenge)),
      validatorPublicKey: this.claves.publicKeyEncoded,
      cert: this.cert,
    };
    return { firmado, qr: codificarReto(firmado) };
  }

  /**
   * Verifica y cobra un pago, en cualquiera de los dos modos. Todo sin internet.
   *
   *  - **Modo reto** (dos escaneos): el pago responde a un cobro que este
   *    aparato acaba de abrir. Es el mas seguro: un pantallazo no sirve nunca.
   *  - **Modo directo** (un escaneo): el pasajero enseña y esta unidad lee. Es
   *    mas rapido, a cambio de que el pago solo esta amarrado a una ventana de
   *    tiempo corta en vez de a un reto concreto.
   */
  async aceptar(gasto) {
    const t = this.reloj();
    const g = gasto.grant.grant;
    const tk = gasto.token;

    const comun = await this.comprobacionesComunes(gasto, t);
    if (comun) return comun;

    if (gasto.modo === MODO.DIRECTO) {
      if (!this.config.aceptaDirecto) {
        return { estado: 'rechazado', motivo: RECHAZO.MODO_NO_ACEPTADO };
      }
      const repetido = await this.repeticion(gasto);
      if (repetido) return repetido;
      if (tk.amountCentimos !== this.config.fareCentimos) {
        return { estado: 'rechazado', motivo: RECHAZO.MONTO };
      }
      const vence = tk.validFromEpochSec + tk.windowSeconds;
      if (t > vence + this.config.desfaseMaximoSegundos) {
        return { estado: 'rechazado', motivo: RECHAZO.PAGO_VENCIDO };
      }
      if (t < tk.validFromEpochSec - this.config.desfaseMaximoSegundos) {
        return { estado: 'rechazado', motivo: RECHAZO.FECHA };
      }
      return this.cobrar(gasto, t);
    }

    if (tk.validatorId !== this.config.validatorId || tk.unitId !== this.config.unitId ||
      tk.ownerId !== this.config.ownerId || tk.routeId !== this.config.routeId) {
      return { estado: 'rechazado', motivo: RECHAZO.OTRA_UNIDAD };
    }

    const repetido = await this.repeticion(gasto);
    if (repetido) return repetido;

    const reto = this.retosAbiertos.get(tk.challengeNonce);
    if (!reto) return { estado: 'rechazado', motivo: RECHAZO.RETO_DESCONOCIDO };
    if (t > reto.issuedAtEpochSec + reto.ttlSeconds) {
      this.retosAbiertos.delete(tk.challengeNonce);
      return { estado: 'rechazado', motivo: RECHAZO.RETO_CADUCADO };
    }
    if (tk.amountCentimos !== reto.fareCentimos) {
      return { estado: 'rechazado', motivo: RECHAZO.MONTO };
    }
    if (tk.spentAtEpochSec > t + this.config.desfaseMaximoSegundos ||
      tk.spentAtEpochSec < reto.issuedAtEpochSec - this.config.desfaseMaximoSegundos) {
      return { estado: 'rechazado', motivo: RECHAZO.FECHA };
    }

    this.retosAbiertos.delete(tk.challengeNonce);
    return this.cobrar(gasto, t);
  }

  /** Lo que se comprueba igual en los dos modos. Devuelve null si todo va bien. */
  async comprobacionesComunes(gasto, t) {
    const g = gasto.grant.grant;
    const tk = gasto.token;

    const ancla = this.anclas.get(g.issuerKeyId);
    if (!ancla) return { estado: 'rechazado', motivo: RECHAZO.EMISOR_DESCONOCIDO };
    if (!(await verificar(ancla, bytesDelVale(g), gasto.grant.signature))) {
      return { estado: 'rechazado', motivo: RECHAZO.SALDO_FALSO };
    }
    if (t >= g.expiresAtEpochSec) return { estado: 'rechazado', motivo: RECHAZO.VALE_CADUCADO };

    const huella = await huellaDeClave(gasto.devicePublicKey);
    if (huella !== g.deviceKeyFingerprint || huella !== tk.deviceKeyFingerprint) {
      return { estado: 'rechazado', motivo: RECHAZO.CLAVE_AJENA };
    }
    if (!(await verificar(gasto.devicePublicKey, bytesFirmados(gasto), gasto.signature))) {
      return { estado: 'rechazado', motivo: RECHAZO.FIRMA_INVALIDA };
    }
    if (this.listaNegra.has(tk.walletId)) {
      return { estado: 'rechazado', motivo: RECHAZO.BLOQUEADO };
    }
    if (tk.seq < 1 || tk.amountCentimos <= 0 || tk.balanceAfterCentimos < 0 ||
      tk.balanceAfterCentimos + tk.amountCentimos > g.amountCentimos) {
      return { estado: 'rechazado', motivo: RECHAZO.ARITMETICA };
    }
    return null;
  }

  /**
   * Repeticion y bifurcacion vistas por este mismo aparato. Va ANTES de mirar el
   * reto: al aceptar un pago se consume su reto, y un segundo escaneo del mismo
   * QR ya no lo encontraria.
   */
  async repeticion(gasto) {
    const clave = `${gasto.token.grantId}#${gasto.token.seq}`;
    const id = await idDelEslabon(gasto);
    const previo = this.eslabonesVistos.get(clave);
    if (!previo) return null;
    return previo === id
      ? { estado: 'ya_cobrado', recibo: this.recibos.get(id) || null }
      : { estado: 'rechazado', motivo: RECHAZO.DOBLE_GASTO };
  }

  /** Anota el cobro y FIRMA el recibo con el que se reclamara el dinero. */
  async cobrar(gasto, t) {
    const id = await idDelEslabon(gasto);
    this.eslabonesVistos.set(`${gasto.token.grantId}#${gasto.token.seq}`, id);

    const claim = {
      linkId: id,
      validatorId: this.config.validatorId,
      unitId: this.config.unitId,
      ownerId: this.config.ownerId,
      routeId: this.config.routeId,
      amountCentimos: gasto.token.amountCentimos,
      acceptedAtEpochSec: t,
      mode: gasto.modo,
    };
    const recibo = {
      receiptId: id,
      spend: gasto,
      ownerId: this.config.ownerId,
      recibo: {
        claim,
        signature: await firmar(this.claves.privateKey, bytesDelRecibo(claim)),
        validatorPublicKey: this.claves.publicKeyEncoded,
      },
    };
    this.recibos.set(id, recibo);
    return {
      estado: 'aceptado',
      recibo,
      modo: gasto.modo,
      montoCentimos: gasto.token.amountCentimos,
      saldoDelPasajeroCentimos: gasto.token.balanceAfterCentimos,
    };
  }

  limpiarLiquidados(ids) { ids.forEach((id) => this.recibos.delete(id)); }
}

export { MODO, Money, codificarCobroDirecto, codificarGasto, codificarReto };
