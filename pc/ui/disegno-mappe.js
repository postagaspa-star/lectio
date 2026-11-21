/*
 * Lectio per il computer: dove va ogni pezzo delle mappe (docs/aspetto.md §45). Solo numeri, niente pagina: le
 * misure dei testi le passa chi disegna. Si prova con Node (pc/prove/js/mappe.test.js).
 *
 * - A riquadri (1.3.8, richiesta di Andrea del 21/11): un riquadro grande con l'argomento in alto, diviso nelle sue
 *   parti (colonne, al massimo 4 per riga); dentro ogni parte le schede dei concetti una sotto l'altra, quelle più in
 *   basso rientrate con una linea guida. Niente frecce tra le parti: le contiene l'argomento. Una freccia col verbo
 *   solo tra due concetti, dove il modello l'ha messa (una causa, un passaggio), mai dall'argomento o da una parte: se
 *   un concetto ha un solo figlio con il verbo, il figlio va subito sotto, in fila, come una catena.
 * - A raggiera: la radice al centro, ogni ramo ha una fetta di cerchio grande quanto le sue foglie; il raggio di ogni
 *   giro cresce finché i concetti dello stesso giro non si toccano.
 * - Linea del tempo: in scala (la distanza conta) o in fila (tutte alla stessa distanza); le schede delle date sopra e
 *   sotto la linea, su corsie, senza sovrapporsi; i periodi come fasce sulla linea. Gli anni avanti Cristo sono
 *   numeri "astronomici": 1 a.C. = 0, 753 a.C. = -752.
 */
(function (radice, fabbrica) {
  'use strict';
  const M = fabbrica();
  if (typeof module === 'object' && module.exports) module.exports = M;
  if (radice) radice.LectioMappe = M;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  const MARGINE = 40;
  const r1 = (x) => Math.round(x * 10) / 10;

  // --- testi -------------------------------------------------------------------------------------------------------

  /** Va a capo per stare in `massima` px; al massimo `righe` righe, l'ultima chiusa con "…". `misura(testo)` → px. */
  function aCapo(testo, massima, misura, righe) {
    const parole = String(testo || '').split(/\s+/).filter(Boolean);
    const max = righe || 3;
    const uscita = [];
    let riga = '';
    for (const p of parole) {
      const prova = riga ? riga + ' ' + p : p;
      if (!riga || misura(prova) <= massima) { riga = prova; continue; }
      uscita.push(riga);
      riga = p;
    }
    if (riga) uscita.push(riga);
    if (uscita.length > max) {
      const tenute = uscita.slice(0, max);
      let ultima = tenute[max - 1] + ' ' + uscita.slice(max).join(' ');
      while (ultima.length > 1 && misura(ultima + '…') > massima) ultima = ultima.slice(0, -1).trimEnd();
      tenute[max - 1] = ultima + '…';
      return tenute;
    }
    return uscita.length ? uscita : [''];
  }

  // --- concetti ----------------------------------------------------------------------------------------------------

  /** Figli in ordine e livelli, partendo dalla radice (il concetto senza genitore). */
  function albero(concetti) {
    const per = new Map();
    let radiceId = null;
    for (const c of concetti || []) per.set(c.id, { id: c.id, figli: [], livello: 0, genitore: c.genitore });
    for (const c of concetti || []) {
      if (c.genitore == null || !per.has(c.genitore)) { if (radiceId == null) radiceId = c.id; continue; }
      per.get(c.genitore).figli.push(c.id);
    }
    const ordine = [];
    const coda = radiceId == null ? [] : [radiceId];
    const visti = new Set();
    while (coda.length) {
      const id = coda.shift();
      if (visti.has(id)) continue;
      visti.add(id);
      ordine.push(id);
      for (const f of per.get(id).figli) { per.get(f).livello = per.get(id).livello + 1; coda.push(f); }
    }
    return { per, radice: radiceId, ordine };
  }

  /**
   * Il verbo che si vede: solo tra due concetti. Sotto l'argomento o sotto una parte non c'è (sono titoli di gruppo: lo
   * dice il riquadro), nemmeno nelle mappe di prima che l'avevano. `per` = Map(id → concetto).
   */
  function verboDi(per, c) {
    if (!c || !c.verbo) return null;
    const g = per.get(c.genitore);
    if (!g || g.genitore == null) return null;
    const n = per.get(g.genitore);
    return n && n.genitore == null ? null : c.verbo;
  }

  /** Il riquadro di tutto: { x, y, larghezza, altezza } con il margine. */
  function cornice(nodi) {
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const n of Object.values(nodi)) {
      x0 = Math.min(x0, n.x - n.w / 2); x1 = Math.max(x1, n.x + n.w / 2);
      y0 = Math.min(y0, n.y - n.h / 2); y1 = Math.max(y1, n.y + n.h / 2);
    }
    if (!Number.isFinite(x0)) return { x: 0, y: 0, larghezza: 0, altezza: 0 };
    return { x: x0 - MARGINE, y: y0 - MARGINE, larghezza: x1 - x0 + 2 * MARGINE, altezza: y1 - y0 + 2 * MARGINE };
  }

  /** Sposta tutto perché il riquadro cominci da (0, 0). */
  function allOrigine(nodi) {
    const c = cornice(nodi);
    for (const n of Object.values(nodi)) { n.x -= c.x; n.y -= c.y; }
    return { nodi, larghezza: c.larghezza, altezza: c.altezza };
  }

  /** §45.4 Le misure dei riquadri. */
  const RIQ = { colonna: 292, pad: 18, gap: 16, padParte: 16, dopoTesta: 12, gapSchede: 10, gapVerbo: 34, rientro: 24, rientriMassimi: 3 };

  /** Quante parti per riga: tutte fino a 4, poi righe pari (5 e 6 → 3, 7 e 8 → 4). */
  function colonneRiquadri(n) {
    if (n <= 4) return Math.max(1, n);
    return Math.min(4, Math.ceil(n / Math.ceil(n / 4)));
  }

  /**
   * A riquadri. `alta(id, larghezza, ruolo)` → l'altezza del pezzo largo così (ruolo: 'radice', 'parte', 'scheda').
   * Ritorna { larghezza, altezza, cornice, parti: [{ id, x, y, w, h }], nodi: { id: { x, y, w, h, ruolo, livello } }
   * (x e y = angolo in alto a sinistra), legami: [{ da, a, tipo, d, freccia, verbo: { x, y } | null }] } con il margine.
   * Tipi dei legami: 'guida' (figli rientrati, dal lato del genitore, con la punta se c'è il verbo), 'catena' (un figlio
   * solo, con il verbo, subito sotto). Dalla parte ai suoi concetti niente legami: stanno nella colonna.
   */
  function layoutRiquadri(concetti, alta, opz) {
    const o = Object.assign({}, RIQ, opz || {});
    const t = albero(concetti);
    if (t.radice == null) return { larghezza: 0, altezza: 0, cornice: null, parti: [], nodi: {}, legami: [] };
    const per = new Map((concetti || []).map((c) => [c.id, c]));
    const parti = t.per.get(t.radice).figli;
    const colonne = colonneRiquadri(parti.length);
    const W = colonne * o.colonna + (colonne - 1) * o.gap + 2 * o.pad;
    const X = MARGINE;
    const Y = MARGINE;
    const nodi = {};
    const legami = [];
    const cornici = [];
    const hR = alta(t.radice, W - 2 * o.pad, 'radice');
    nodi[t.radice] = { x: X, y: Y, w: W, h: hR, ruolo: 'radice', livello: 0 };
    const wInt = o.colonna - 2 * o.padParte;

    /** Una colonna, con le misure relative al suo angolo. */
    function colonna(pid) {
      const hT = alta(pid, wInt, 'parte');
      const pezzi = [];
      let y = o.padParte + hT;
      const visita = (id, rientro, genitore, livello) => {
        const c = per.get(id);
        const verbo = verboDi(per, c);
        y += verbo ? o.gapVerbo : (genitore || pezzi.length ? o.gapSchede : o.dopoTesta);
        const r = Math.min(o.rientriMassimi, rientro);
        const w = wInt - r * o.rientro;
        const h = alta(id, w, 'scheda');
        const pezzo = { id, x: o.padParte + r * o.rientro, y, w, h, rientro: r, genitore, verbo, livello };
        pezzi.push(pezzo);
        y += h;
        const figli = t.per.get(id).figli;
        const catena = figli.length === 1 && !!verboDi(per, per.get(figli[0]));
        for (const f of figli) visita(f, catena ? rientro : rientro + 1, pezzo, livello + 1);
      };
      for (const f of t.per.get(pid).figli) visita(f, 0, null, 2);
      return { testa: { x: o.padParte, y: o.padParte, w: wInt, h: hT }, pezzi, h: y + o.padParte };
    }

    let y = Y + hR + o.pad;
    for (let i = 0; i < parti.length; i += colonne) {
      const riga = parti.slice(i, i + colonne);
      const col = riga.map(colonna);
      const hRiga = Math.max(...col.map((c) => c.h));
      const larga = riga.length * o.colonna + (riga.length - 1) * o.gap;
      let x = X + o.pad + (W - 2 * o.pad - larga) / 2;
      riga.forEach((pid, k) => {
        const c = col[k];
        cornici.push({ id: pid, x, y, w: o.colonna, h: hRiga });
        nodi[pid] = { x: x + c.testa.x, y: y + c.testa.y, w: c.testa.w, h: c.testa.h, ruolo: 'parte', livello: 1 };
        for (const p of c.pezzi) {
          const n = { x: x + p.x, y: y + p.y, w: p.w, h: p.h, ruolo: 'scheda', livello: p.livello, rientro: p.rientro };
          nodi[p.id] = n;
          if (p.genitore && p.rientro > p.genitore.rientro) {
            const g = nodi[p.genitore.id];
            const gx = g.x + 14;
            const fine = n.y + Math.min(n.h / 2, 21);
            legami.push({ da: p.genitore.id, a: p.id, tipo: 'guida', d: 'M' + r1(gx) + ' ' + r1(g.y + g.h) + ' V' + r1(fine) + ' H' + r1(n.x),
              freccia: !!p.verbo, verbo: p.verbo ? { x: gx + 10, y: n.y - o.gapVerbo / 2 } : null });
          } else if (p.genitore) {
            const g = nodi[p.genitore.id];
            const ax = n.x + 22;
            legami.push({ da: p.genitore.id, a: p.id, tipo: 'catena', d: 'M' + r1(ax) + ' ' + r1(g.y + g.h) + ' V' + r1(n.y),
              freccia: true, verbo: p.verbo ? { x: ax + 10, y: (g.y + g.h + n.y) / 2 } : null });
          }
        }
        x += o.colonna + o.gap;
      });
      y += hRiga + o.gap;
    }
    const H = parti.length ? y - o.gap + o.pad - Y : hR;
    return { larghezza: W + 2 * MARGINE, altezza: H + 2 * MARGINE, cornice: { x: X, y: Y, w: W, h: H, testa: hR }, parti: cornici, nodi, legami };
  }

  /** A raggiera: fette di cerchio grandi quanto le foglie di ogni ramo; il primo ramo in alto, poi in senso orario. */
  function layoutRaggiera(concetti, dim, opz) {
    const o = opz || {};
    const gap = o.gap || 30;
    const t = albero(concetti);
    if (t.radice == null) return { nodi: {}, larghezza: 0, altezza: 0 };
    const foglie = new Map();
    const conta = (id) => {
      const n = t.per.get(id);
      const f = n.figli.length ? n.figli.reduce((s, x) => s + conta(x), 0) : 1;
      foglie.set(id, f);
      return f;
    };
    conta(t.radice);
    const fetta = new Map([[t.radice, [-Math.PI / 2, 3 * Math.PI / 2]]]);
    for (const id of t.ordine) {
      const n = t.per.get(id);
      const [a0, a1] = fetta.get(id);
      const tot = foglie.get(id) || 1;
      let a = a0;
      for (const f of n.figli) {
        const quota = (a1 - a0) * (foglie.get(f) / tot);
        fetta.set(f, [a, a + quota]);
        a += quota;
      }
    }
    // Il raggio di ogni giro: abbastanza lontano dal giro prima e abbastanza largo per i concetti del giro. Lontano
    // quanto serve nella direzione di ognuno: un concetto largo in alto o in basso sporge poco verso il centro.
    const livelli = [];
    for (const id of t.ordine) { const l = t.per.get(id).livello; (livelli[l] = livelli[l] || []).push(id); }
    const angolo = (id) => { const [a0, a1] = fetta.get(id); return (a0 + a1) / 2; };
    const sporge = (id, a) => Math.abs(Math.cos(a)) * dim[id].w / 2 + Math.abs(Math.sin(a)) * dim[id].h / 2;
    const raggi = [0];
    for (let l = 1; l < livelli.length; l++) {
      const prima = l === 1 ? 0 : raggi[l - 1] + Math.max(...livelli[l - 1].map((id) => sporge(id, angolo(id))));
      let r = 0;
      for (const id of livelli[l]) {
        const a = angolo(id);
        r = Math.max(r, (l === 1 ? sporge(t.radice, a) : prima) + sporge(id, a) + 64);
      }
      for (const id of livelli[l]) {
        const [a0, a1] = fetta.get(id);
        const ampiezza = Math.max(1e-3, a1 - a0);
        const a = (a0 + a1) / 2;
        // La misura che conta lungo il giro: la larghezza in alto e in basso, l'altezza ai lati.
        const lungo = Math.abs(Math.sin(a)) * dim[id].w + Math.abs(Math.cos(a)) * dim[id].h;
        r = Math.max(r, (lungo + gap) / ampiezza);
      }
      raggi[l] = Math.min(r, prima + 900);
    }
    const nodi = {};
    for (const id of t.ordine) {
      const l = t.per.get(id).livello;
      const [a0, a1] = fetta.get(id);
      const a = l === 0 ? 0 : (a0 + a1) / 2;
      nodi[id] = { x: raggi[l] * Math.cos(a), y: raggi[l] * Math.sin(a), w: dim[id].w, h: dim[id].h, livello: l, angolo: a };
    }
    return allOrigine(nodi);
  }

  /** La forma della mappa: quella scelta dal modello; le mappe di prima della 1.3.8 ("albero") vanno a riquadri. */
  function formaConsigliata(contenuto) {
    return contenuto && contenuto.forma === 'raggiera' ? 'raggiera' : 'riquadri';
  }

  /** Dove la retta dal centro del nodo verso (vx, vy) esce dal suo riquadro (con un po' di aria). */
  function puntoSulBordo(n, vx, vy, aria) {
    const dx = vx - n.x;
    const dy = vy - n.y;
    const hw = n.w / 2 + (aria || 0);
    const hh = n.h / 2 + (aria || 0);
    if (!dx && !dy) return { x: n.x, y: n.y };
    const s = Math.min(dx ? hw / Math.abs(dx) : Infinity, dy ? hh / Math.abs(dy) : Infinity);
    return { x: n.x + dx * s, y: n.y + dy * s };
  }

  /** Il legame tra due nodi: da bordo a bordo, e il punto dove scrivere il verbo (un po' più vicino al figlio). */
  function legame(da, a, quota) {
    const p0 = puntoSulBordo(da, a.x, a.y, 4);
    const p1 = puntoSulBordo(a, da.x, da.y, 7);
    const q = quota == null ? 0.52 : quota;
    return { x1: p0.x, y1: p0.y, x2: p1.x, y2: p1.y, mx: p0.x + (p1.x - p0.x) * q, my: p0.y + (p1.y - p0.y) * q };
  }

  // --- linea del tempo ---------------------------------------------------------------------------------------------

  /** L'anno da leggere: 1492 → "1492", -752 → "753 a.C.", 0 → "1 a.C.". */
  function annoTesto(x) {
    const n = Math.round(Number(x) || 0);
    return n <= 0 ? (1 - n) + ' a.C.' : String(n);
  }

  /** Il passo delle tacche: 1, 2, 5, 10, 20, 50… perché due tacche stiano ad almeno `minimo` px. */
  function passoTacche(anni, pixel, minimo) {
    const perPx = anni / Math.max(1, pixel);
    const voluto = perPx * (minimo || 96);
    const base = Math.pow(10, Math.floor(Math.log10(Math.max(voluto, 1e-9))));
    for (const k of [1, 2, 5, 10]) if (base * k >= voluto) return Math.max(1, base * k);
    return Math.max(1, base * 10);
  }

  /** Le tacche tonde tra x0 e x1: 1500, 1550, 1600 dopo Cristo; 500 a.C., 400 a.C. prima. */
  function tacche(x0, x1, passo) {
    const uscita = [];
    for (let k = Math.ceil(x0 / passo); k * passo <= x1; k++) { const x = k * passo; if (x >= 1) uscita.push(x); }
    for (let k = 1; 1 - k * passo >= x0; k++) { const x = 1 - k * passo; if (x <= x1) uscita.unshift(x); }
    return uscita.sort((a, b) => a - b);
  }

  /**
   * Le date sulla linea. eventi = [{ id, x, x_fine }] in ordine; dim = { id: { w, h } } delle schede.
   * opz = { modo: 'scala'|'fila', larghezza (px della linea, almeno), corsie (per lato, 2) }.
   * Ritorna { eventi: { id: { px, pxFine, cx, sopra, corsia, y } }, larghezza, altezza, asse, tacche, pigiato, fasce }.
   */
  function layoutLinea(eventi, dim, opz) {
    const o = opz || {};
    const modo = o.modo === 'fila' ? 'fila' : 'scala';
    const lista = (eventi || []).slice();
    const corsiePerLato = o.corsie || 2;
    const gap = 14;
    const largaScheda = Math.max(120, ...lista.map((e) => dim[e.id].w));
    const altaScheda = Math.max(40, ...lista.map((e) => dim[e.id].h));
    const bordo = largaScheda / 2 + 24;
    let x0 = Infinity, x1 = -Infinity;
    for (const e of lista) { x0 = Math.min(x0, e.x); x1 = Math.max(x1, e.x_fine != null ? e.x_fine : e.x); }
    if (!Number.isFinite(x0)) { x0 = 0; x1 = 1; }
    if (x1 - x0 < 1) { x0 -= 1; x1 += 1; }
    const passoFila = largaScheda * 0.62 + gap;
    let W = modo === 'fila' ? Math.max(o.larghezza || 0, passoFila * Math.max(1, lista.length - 1))
      : Math.max(o.larghezza || 0, 600);
    let risultato = null;
    for (let giro = 0; giro < 8; giro++) {
      const px = (e, i) => bordo + (modo === 'fila' ? (lista.length > 1 ? i * (W / (lista.length - 1)) : W / 2) : (e.x - x0) / (x1 - x0) * W);
      const fine = (e) => (modo === 'scala' && e.x_fine != null ? bordo + (e.x_fine - x0) / (x1 - x0) * W : null);
      const ultimi = [];
      for (let c = 0; c < corsiePerLato * 2; c++) ultimi.push(-Infinity);
      const pos = {};
      let pigiato = false;
      lista.forEach((e, i) => {
        const p = px(e, i);
        const w = dim[e.id].w;
        let sinistra = p - w / 2;
        let scelta = -1;
        for (let c = 0; c < ultimi.length; c++) if (sinistra >= ultimi[c] + gap) { scelta = c; break; }
        if (scelta < 0) {
          pigiato = true;
          scelta = ultimi.indexOf(Math.min(...ultimi));
          sinistra = Math.max(sinistra, ultimi[scelta] + gap);
        }
        ultimi[scelta] = sinistra + w;
        pos[e.id] = { px: p, pxFine: fine(e), cx: sinistra + w / 2, sopra: scelta % 2 === 0, corsia: Math.floor(scelta / 2) };
      });
      risultato = { pos, pigiato };
      if (!pigiato || modo === 'fila') break;
      W *= 1.35;
    }
    // Le fasce dei periodi, sotto la linea, su righe che non si toccano.
    const righeFasce = [];
    const fasce = [];
    for (const e of lista) {
      const p = risultato.pos[e.id];
      if (p.pxFine == null) continue;
      let r = righeFasce.findIndex((fineRiga) => p.px >= fineRiga + 6);
      if (r < 0) { r = righeFasce.length; righeFasce.push(-Infinity); }
      righeFasce[r] = p.pxFine;
      fasce.push({ id: e.id, da: p.px, a: p.pxFine, riga: r });
    }
    const gambo = 26;
    const corsia = altaScheda + 12;
    const spazioFasce = righeFasce.length ? righeFasce.length * 12 + 6 : 0;
    const asse = corsiePerLato * corsia + gambo + 16;
    for (const e of lista) {
      const p = risultato.pos[e.id];
      const h = dim[e.id].h;
      p.y = p.sopra ? asse - gambo - p.corsia * corsia - h / 2 : asse + spazioFasce + gambo + p.corsia * corsia + h / 2;
    }
    for (const f of fasce) f.y = asse + 8 + f.riga * 12;
    let destra = 0;
    for (const e of lista) destra = Math.max(destra, risultato.pos[e.id].cx + dim[e.id].w / 2, risultato.pos[e.id].pxFine || 0);
    const larghezza = Math.max(destra + 24, W + 2 * bordo);
    const altezza = asse + spazioFasce + gambo + corsiePerLato * corsia + 16;
    let segni = [];
    if (modo === 'scala') {
      const passo = passoTacche(x1 - x0, W, 110);
      segni = tacche(x0, x1, passo).map((x) => ({ x, px: bordo + (x - x0) / (x1 - x0) * W, testo: annoTesto(x) }));
    }
    return { eventi: risultato.pos, larghezza, altezza, asse, tacche: segni, pigiato: risultato.pigiato, fasce, modo };
  }

  /** "In scala" se ci sta senza allungarsi troppo, altrimenti "In fila" (per esempio dalla preistoria a oggi). */
  function modoConsigliato(eventi, dim, larghezzaVista) {
    const l = layoutLinea(eventi, dim, { modo: 'scala', larghezza: larghezzaVista });
    return l.larghezza > larghezzaVista * 3.2 ? 'fila' : 'scala';
  }

  /**
   * Dove tagliare una linea del tempo lunga in pagine: mai in mezzo a una scheda o a una fascia, se si può.
   * pezzi = [[da, a]] (le schede e le fasce in px); ritorna [[x0, x1], …].
   */
  function pagine(pezzi, larghezza, perPagina) {
    if (larghezza <= perPagina * 1.12) return [[0, larghezza]];
    const tagli = [[0, 0]];
    let inizio = 0;
    while (larghezza - inizio > perPagina * 1.05) {
      let fine = inizio + perPagina;
      for (let x = fine; x > inizio + perPagina * 0.55; x -= 4) {
        if (!pezzi.some(([a, b]) => a < x && b > x)) { fine = x; break; }
      }
      tagli[tagli.length - 1] = [inizio, fine];
      tagli.push([fine, fine]);
      inizio = fine;
    }
    tagli[tagli.length - 1] = [inizio, larghezza];
    return tagli;
  }

  // --- mettiti alla prova ------------------------------------------------------------------------------------------

  /** Un numero a caso che si può ripetere: lo stesso seme dà le stesse scelte. */
  function casuale(seme) {
    let s = (Number(seme) || 1) >>> 0;
    return () => {
      s = (s + 0x6D2B79F5) >>> 0;
      let t = s;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  /** Quali nascondere: circa `quota` degli id (almeno 1 se ce ne sono almeno 2), sempre gli stessi con lo stesso seme. */
  function daNascondere(ids, quota, seme) {
    const lista = (ids || []).slice();
    if (lista.length < 2) return new Set();
    const caso = casuale(seme);
    for (let i = lista.length - 1; i > 0; i--) { const j = Math.floor(caso() * (i + 1)); [lista[i], lista[j]] = [lista[j], lista[i]]; }
    return new Set(lista.slice(0, Math.max(1, Math.round(lista.length * quota))));
  }

  /**
   * Mettiti alla prova (§45.6). Concetti: un terzo dei concetti (mai la radice) e un quarto dei verbi degli altri.
   * Date: in quattro schede su dieci si nasconde la data, in due su dieci il fatto (mai tutte e due nella stessa).
   * Ritorna { concetti, verbi } oppure { date, fatti }: insiemi di id.
   */
  function sceltaProva(tipo, contenuto, seme) {
    if (tipo === 'concetti') {
      const nodi = (contenuto.concetti || []).filter((c) => c.genitore != null);
      const per = new Map((contenuto.concetti || []).map((c) => [c.id, c]));
      const concetti = daNascondere(nodi.map((c) => c.id), 1 / 3, seme);
      const verbi = daNascondere(nodi.filter((c) => verboDi(per, c) && !concetti.has(c.id)).map((c) => c.id), 1 / 4, seme * 7 + 3);
      return { concetti, verbi };
    }
    const ids = (contenuto.eventi || []).map((e) => e.id);
    const date = daNascondere(ids, 0.4, seme);
    const fatti = daNascondere(ids.filter((i) => !date.has(i)), 1 / 3, seme * 5 + 1);
    return { date, fatti };
  }

  // --- testi della pagina ------------------------------------------------------------------------------------------

  /** Sotto il titolo: "14 concetti" oppure "9 date · dal 753 a.C. al 476". */
  function fraseMappa(tipo, contenuto) {
    if (!contenuto) return '';
    if (tipo === 'concetti') {
      const n = (contenuto.concetti || []).length;
      return n + (n === 1 ? ' concetto' : ' concetti');
    }
    const ev = contenuto.eventi || [];
    if (!ev.length) return '';
    const primo = ev[0];
    let ultimo = ev[0];
    for (const e of ev) if ((e.x_fine != null ? e.x_fine : e.x) >= (ultimo.x_fine != null ? ultimo.x_fine : ultimo.x)) ultimo = e;
    const fineX = ultimo.x_fine != null ? ultimo.x_fine : ultimo.x;
    const n = ev.length;
    return n + (n === 1 ? ' data' : ' date') + (n > 1 ? ' · dal ' + annoTesto(primo.x) + ' al ' + annoTesto(fineX) : '');
  }

  /**
   * I concetti vicini a uno (il genitore e i figli), con il verbo e il verso: per il pannello e per accendere. Le frecce
   * tra rami delle mappe di prima della 1.3.8 non contano più.
   */
  function vicini(contenuto, id) {
    const per = new Map((contenuto.concetti || []).map((c) => [c.id, c]));
    const uscita = [];
    const c = per.get(id);
    if (!c) return uscita;
    const verbo = (f) => verboDi(per, f);
    if (c.genitore != null && per.has(c.genitore)) uscita.push({ id: c.genitore, testo: per.get(c.genitore).testo, verbo: verbo(c), verso: 'da' });
    for (const f of contenuto.concetti || []) if (f.genitore === id) uscita.push({ id: f.id, testo: f.testo, verbo: verbo(f), verso: 'a' });
    return uscita;
  }

  /** I colori chiari delle materie (pieno, tenue, testo): il PDF si stampa sempre chiaro, anche col tema scuro. */
  const COLORI_CHIARI = {
    ambra: ['#C27C0E', '#FBF1DE', '#8A5506'], rosa: ['#D6336C', '#FCE8EF', '#A61E4D'], petrolio: ['#0C8599', '#E3F6F8', '#0B6474'],
    viola: ['#7048E8', '#F1ECFE', '#5532C9'], blu: ['#1C7ED6', '#E7F1FC', '#1864AB'], verde: ['#2B8A3E', '#EAF6EC', '#1F6A30'],
    arancio: ['#E8590C', '#FDEDE3', '#B4440A'], oliva: ['#5C940D', '#EFF6E3', '#466F0B'], giada: ['#00A089', '#DFF6F0', '#026C5C'],
    senape: ['#928100', '#F4F1DD', '#695D02'], orchidea: ['#C04EC8', '#FAEBFA', '#8A2C91'], prugna: ['#A34A80', '#FEEAF4', '#883E6B'],
    cacao: ['#916748', '#F9EEE6', '#76543A'], lavagna: ['#636F8E', '#ECF0FA', '#525C75'], salvia: ['#7B9575', '#EBF3EA', '#4E634A'],
    lavanda: ['#A380C8', '#F4ECFE', '#694D86'],
  };

  return {
    MARGINE, COLORI_CHIARI,
    RIQ, aCapo, albero, verboDi, colonneRiquadri, layoutRiquadri, layoutRaggiera, formaConsigliata, puntoSulBordo, legame,
    annoTesto, passoTacche, tacche, layoutLinea, modoConsigliato, pagine,
    casuale, daNascondere, sceltaProva, fraseMappa, vicini,
  };
});
