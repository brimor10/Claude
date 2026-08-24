// Monedero, validador y servidor: la misma logica que el modulo :core en Kotlin.
// Ver web/protocolo.js para las primitivas y la nota sobre compatibilidad.

import {
  CEROS_32, MONEDA, Money, b64urlEncode, bytesDelCertificado, bytesDelGasto,
  bytesDelReto, bytesDelVale, bytesEqual, codificarGasto, codificarReto,
  firmar, generarClaves, huellaDeClave, idDelEslabon, hashDelEslabon, nonce,
  verificar,
} from './protocolo.js';

export const ahora = () => Math.floor(Date.now() / 1000);

// --- Servidor emisor y liquidador ------------------------------------------------

export const POLITICA_POR_DEFECTO = {
  vigenciaDelValeSegundos: 30 * 24 * 3600,
  vigenciaDelCertificadoSegundos: 365 * 24 * 3600,
  topeOfflineCentimos: 200_00,
  topeDeViajesOffline: 30,
  comisionPuntosBasicos: 300,
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

  async subirRecibos(validatorId, recibos) {
    const ids = [];
    for (const recibo of recibos) {
      await this.registrarGasto(recibo.spend);
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
    return { confirmados, tiempoServidorEpochSec: this.reloj() };
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
      const gasto = { token, signature, grant: vale.signedGrant, devicePublicKey: this.claves.publicKeyEncoded };

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
  }

  static async crear(servidor, { validatorId, unitId, ownerId, routeId, fareCentimos, ttlSeconds = 45 }, reloj = ahora) {
    const claves = await generarClaves();
    const cert = await servidor.registrarValidador(validatorId, unitId, ownerId, claves.publicKeyEncoded);
    return new Validador(
      { validatorId, unitId, ownerId, routeId, fareCentimos, ttlSeconds, desfaseMaximoSegundos: 120 },
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

  /** Verifica y cobra un pago. Todo sin internet. */
  async aceptar(gasto) {
    const t = this.reloj();
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
    if (!(await verificar(gasto.devicePublicKey, bytesDelGasto(tk), gasto.signature))) {
      return { estado: 'rechazado', motivo: RECHAZO.FIRMA_INVALIDA };
    }
    if (this.listaNegra.has(tk.walletId)) {
      return { estado: 'rechazado', motivo: RECHAZO.BLOQUEADO };
    }
    if (tk.validatorId !== this.config.validatorId || tk.unitId !== this.config.unitId ||
      tk.ownerId !== this.config.ownerId || tk.routeId !== this.config.routeId) {
      return { estado: 'rechazado', motivo: RECHAZO.OTRA_UNIDAD };
    }

    // La repeticion se mira ANTES que el reto: al aceptar un pago se consume su
    // reto, y un segundo escaneo del mismo QR ya no lo encontraria.
    const clave = `${tk.grantId}#${tk.seq}`;
    const id = await idDelEslabon(gasto);
    const previo = this.eslabonesVistos.get(clave);
    if (previo) {
      return previo === id
        ? { estado: 'ya_cobrado', recibo: this.recibos.get(id) || null }
        : { estado: 'rechazado', motivo: RECHAZO.DOBLE_GASTO };
    }

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
    if (tk.seq < 1 || tk.amountCentimos <= 0 || tk.balanceAfterCentimos < 0 ||
      tk.balanceAfterCentimos + tk.amountCentimos > g.amountCentimos) {
      return { estado: 'rechazado', motivo: RECHAZO.ARITMETICA };
    }

    this.retosAbiertos.delete(tk.challengeNonce);
    this.eslabonesVistos.set(clave, id);
    const recibo = {
      receiptId: id, spend: gasto,
      validatorId: this.config.validatorId, unitId: this.config.unitId,
      ownerId: this.config.ownerId, acceptedAtEpochSec: t,
    };
    this.recibos.set(id, recibo);
    return {
      estado: 'aceptado', recibo,
      montoCentimos: tk.amountCentimos,
      saldoDelPasajeroCentimos: tk.balanceAfterCentimos,
    };
  }

  limpiarLiquidados(ids) { ids.forEach((id) => this.recibos.delete(id)); }
}

export { Money, codificarGasto, codificarReto };
