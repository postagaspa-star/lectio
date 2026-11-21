/*
 * Lectio per il computer: le mappe di ripasso (docs/aspetto.md §45, contratto §29). Da uno schema pronto: la linea del
 * tempo con le date oppure la mappa dei concetti, a riquadri o a raggiera. Si guarda con lo zoom e trascinando, un clic
 * apre la spiegazione, "Mettiti alla prova" nasconde dei pezzi da scoprire, Scarica PDF stampa quello che si vede.
 * Dove va ogni pezzo lo calcola disegno-mappe.js; qui si disegna (SVG) e si risponde ai gesti.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;
  const M = window.LectioMappe;
  const SVG = 'http://www.w3.org/2000/svg';

  const inLavoro = (x) => x && (x.stato === 'in_coda' || x.stato === 'in_preparazione');
  const pronta = (x) => !!x && x.stato === 'pronta';
  const chiaveForma = (id) => 'lectio.mappa.forma.' + id;

  /** §45.2 I due tipi, nell'ordine in cui si vedono. */
  const TIPI = [
    { tipo: 'date', nome: 'Le date', frase: 'Una linea del tempo: quando e che cosa', icona: 'linea' },
    { tipo: 'concetti', nome: 'I concetti', frase: 'Le idee principali, divise per parti', icona: 'mappa' },
  ];
  A.TIPI_MAPPA = TIPI;
  const nomeTipo = (t) => (TIPI.find((x) => x.tipo === t) || TIPI[1]).nome;

  // --- piccoli pezzi SVG -------------------------------------------------------------------------------------------

  function s(tag, attributi, ...figli) {
    const el = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attributi || {})) {
      if (v === null || v === undefined || v === false) continue;
      if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else el.setAttribute(k, String(v));
    }
    for (const f of figli.flat(Infinity)) if (f !== null && f !== undefined && f !== false) el.append(f instanceof Node ? f : document.createTextNode(String(f)));
    return el;
  }
  const r1 = (x) => Math.round(x * 10) / 10;

  /** Le misure dei testi con il carattere vero (Figtree); senza tela (prove con jsdom) una stima. */
  const tela = (() => { try { return document.createElement('canvas').getContext('2d'); } catch (e) { return null; } })();
  function misura(font, px) {
    return (t) => {
      if (tela) { tela.font = font; return tela.measureText(String(t)).width; }
      return String(t).length * px * 0.56;
    };
  }
  let caratteriPronti = null;
  function aspettaCaratteri() {
    if (!caratteriPronti) {
      const f = document.fonts;
      caratteriPronti = !f || !f.load ? Promise.resolve()
        : Promise.race([Promise.all(['800 21px Figtree', '800 16px Figtree', '700 14.5px Figtree', '500 13px Figtree', '700 12px Figtree'].map((x) => f.load(x).catch(() => null))),
          new Promise((r) => setTimeout(r, 900))]);
    }
    return caratteriPronti;
  }

  /**
   * §45.4 Le misure dei testi dei concetti: il nome sopra, la frase sotto. A riquadri: l'argomento (la fascia in alto),
   * le parti (le colonne), le schede; a raggiera: la radice, il primo giro, gli altri.
   */
  const STILI_RIQ = {
    radice: { nome: '800 21px Figtree', px: 21, lh: 26, padX: 24, padY: 17, centro: true, righe: 2 },
    parte: { nome: '800 16px Figtree', px: 16, lh: 20, frase: '500 13.5px Figtree', fpx: 13.5, flh: 18, padX: 6, padY: 4, righe: 2 },
    scheda: { nome: '700 14.5px Figtree', px: 14.5, lh: 19, frase: '500 13px Figtree', fpx: 13, flh: 17.5, padX: 13, padY: 10, righe: 3 },
  };
  const STILI_GIRO = [
    { nome: '800 17px Figtree', px: 17, lh: 21, padX: 22, padY: 13, max: 230, centro: true, righe: 3 },
    { nome: '700 15px Figtree', px: 15, lh: 19, frase: '500 13px Figtree', fpx: 13, flh: 17, padX: 16, padY: 11, max: 210, centro: true, righe: 3 },
    { nome: '700 14px Figtree', px: 14, lh: 18, frase: '500 12.5px Figtree', fpx: 12.5, flh: 16.5, padX: 14, padY: 10, max: 190, centro: true, righe: 3 },
  ];
  const misuraVerbo = misura('700 12px Figtree', 12);
  const SPAZIO_FRASE = 5;

  /** Le righe del nome e della frase dentro `larghezza` px di testo, e l'altezza del pezzo. */
  function testiConcetto(c, st, larghezza) {
    const mn = misura(st.nome, st.px);
    const nome = M.aCapo(c.testo, larghezza, mn, st.righe);
    const mf = st.frase ? misura(st.frase, st.fpx) : null;
    const frase = mf && c.frase ? M.aCapo(c.frase, larghezza, mf, 5) : [];
    const w = Math.max(...nome.map(mn), ...frase.map(mf || (() => 0)));
    return { nome, frase, w: Math.ceil(w), h: 2 * st.padY + nome.length * st.lh + (frase.length ? SPAZIO_FRASE + frase.length * st.flh : 0) };
  }

  /** Il nome (o il buco da scoprire) e la frase, dentro un pezzo largo `w`. */
  function scritteConcetto(t, st, w, nascosto) {
    const x = st.centro ? r1(w / 2) : st.padX;
    const anchor = st.centro ? 'middle' : 'start';
    const cima = st.padY + st.px * 0.8;
    const altoNome = t.nome.length * st.lh;
    const nome = nascosto
      ? s('g', { class: 'mp-buco' }, s('rect', { x: st.centro ? r1(w / 2 - 30) : st.padX, y: st.padY, width: 60, height: Math.max(18, altoNome - 2), rx: 9 }),
        s('text', { x: st.centro ? r1(w / 2) : st.padX + 30, y: r1(st.padY + altoNome / 2 + 5), 'text-anchor': 'middle' }, '?'))
      : s('text', { class: 'vero', x, y: r1(cima), 'text-anchor': anchor }, t.nome.map((r, i) => s('tspan', { x, dy: i ? st.lh : 0 }, r)));
    const frase = t.frase.length
      ? s('text', { class: 'frase', x, y: r1(st.padY + altoNome + SPAZIO_FRASE + st.fpx * 0.82), 'text-anchor': anchor }, t.frase.map((r, i) => s('tspan', { x, dy: i ? st.flh : 0 }, r)))
      : null;
    return [nome, frase];
  }

  function definizioni(uid) {
    return s('defs', null,
      s('marker', { id: 'mp-punta-' + uid, viewBox: '0 0 10 10', refX: 8.5, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto-start-reverse' },
        s('path', { d: 'M0 0 L10 5 L0 10 z', class: 'mp-punta' })));
  }
  let contaUid = 0;

  // --- §45.4 la mappa dei concetti ---------------------------------------------------------------------------------

  /**
   * Il disegno dei concetti: { mondo (g), larghezza, altezza, nodi: Map(id → g), legami, fuoco }. `forma` = 'riquadri'
   * o 'raggiera'. `prova` = la scelta di Mettiti alla prova (o null) con gli scoperti: di un concetto nascosto resta la
   * frase, da indovinare c'è il nome. `uid` distingue le frecce di due disegni nella stessa pagina.
   */
  function disegnaConcetti(contenuto, forma, prova, uid, conEntrata) {
    const concetti = contenuto.concetti || [];
    const t = M.albero(concetti);
    const per = new Map(concetti.map((c) => [c.id, c]));
    const nascosti = prova ? prova.scelta.concetti : new Set();
    const verbiNascosti = prova ? prova.scelta.verbi : new Set();
    const scoperto = (k) => !!prova && prova.scoperti.has(k);
    const gSotto = s('g', { class: 'mp-cornici' });
    const gLegami = s('g', { class: 'mp-legami' });
    const gVerbi = s('g', { class: 'mp-verbi' });
    const gNodi = s('g', { class: 'mp-nodi' });
    const nodi = new Map();
    const legami = [];

    /** Il verbo su una freccia: `x` è il centro (raggiera) o il bordo sinistro (riquadri). */
    const verbo = (c, x, y, daSinistra) => {
      const chiave = 'v' + c.id;
      const nasc = verbiNascosti.has(c.id) && !scoperto(chiave);
      const scritta = nasc ? '?' : c.verbo;
      const w = Math.ceil(misuraVerbo(scritta)) + 14;
      const sx = daSinistra ? x : x - w / 2;
      const g = s('g', { class: 'mp-verbo' + (nasc ? ' coperto' : ''), transform: 'translate(' + r1(sx) + ' ' + r1(y - 10) + ')', 'data-da': c.genitore, 'data-a': c.id, 'data-chiave': verbiNascosti.has(c.id) ? chiave : null },
        s('rect', { width: w, height: 20, rx: 10 }),
        s('text', { x: r1(w / 2), y: 14, 'text-anchor': 'middle' }, scritta));
      gVerbi.append(g);
      return g;
    };

    /** Un concetto: un gruppo che si clicca, con il fondo e le scritte. */
    const pezzo = (c, n, st, testi, classi, livello) => {
      const nasc = nascosti.has(c.id) && !scoperto('c' + c.id);
      const g = s('g', {
        class: 'mp-nodo ' + classi + (nasc ? ' coperto' : '') + (nascosti.has(c.id) && !nasc ? ' scoperto' : '') + (conEntrata ? ' mp-entra' : ''),
        transform: 'translate(' + r1(n.x) + ' ' + r1(n.y) + ')', 'data-id': c.id, 'data-chiave': nascosti.has(c.id) ? 'c' + c.id : null,
        tabindex: '0', role: 'button', 'aria-label': nasc ? 'Concetto nascosto: fai clic per scoprirlo' : c.testo,
      },
      s('rect', { class: 'fondo', width: r1(n.w), height: r1(n.h), rx: classi.includes('mp-capo') ? 10 : classi.includes('mp-carta') ? 12 : (testi.nome.length === 1 && !testi.frase.length ? r1(n.h / 2) : 18) }),
      scritteConcetto(testi, st, n.w, nasc));
      if (conEntrata) g.style.setProperty('--ritardo', Math.min(8, livello) * 70 + 'ms');
      gNodi.append(g);
      nodi.set(c.id, g);
      return g;
    };

    if (forma === 'raggiera') {
      const dim = {};
      const testi = {};
      for (const c of concetti) {
        const st = STILI_GIRO[Math.min(2, t.per.has(c.id) ? t.per.get(c.id).livello : 2)];
        testi[c.id] = testiConcetto(c, st, st.max);
        dim[c.id] = { w: Math.max(64, testi[c.id].w + 2 * st.padX), h: testi[c.id].h };
      }
      const l = M.layoutRaggiera(concetti, dim);
      for (const c of concetti) {
        const n = l.nodi[c.id];
        if (!n || c.genitore == null || !l.nodi[c.genitore]) continue;
        const p = M.legame(l.nodi[c.genitore], n, 0.5);
        const linea = s('line', { class: 'mp-legame', x1: r1(p.x1), y1: r1(p.y1), x2: r1(p.x2), y2: r1(p.y2), 'marker-end': 'url(#mp-punta-' + uid + ')', 'data-da': c.genitore, 'data-a': c.id });
        gLegami.append(linea);
        // Il verbo solo tra due concetti (§45.4): mai dall'argomento o da una parte.
        legami.push({ da: c.genitore, a: c.id, linea, verbo: M.verboDi(per, c) ? verbo(c, p.mx, p.my, false) : null });
      }
      for (const id of t.ordine) {
        const c = per.get(id);
        const n = l.nodi[id];
        if (!c || !n) continue;
        const st = STILI_GIRO[Math.min(2, n.livello)];
        pezzo(c, { x: n.x - n.w / 2, y: n.y - n.h / 2, w: n.w, h: n.h }, st, testi[id], 'l' + Math.min(2, n.livello), n.livello);
      }
      const mondo = s('g', { class: 'mp-mondo' }, gLegami, gVerbi, gNodi);
      const r = t.radice != null ? l.nodi[t.radice] : null;
      return { mondo, larghezza: l.larghezza, altezza: l.altezza, nodi, legami, fuoco: r ? { x: r.x, y: r.y, h: r.h, alto: false } : null };
    }

    // A riquadri: le altezze dipendono dalla larghezza, che decide il disegno (le schede rientrate sono più strette).
    const testi = {};
    const ruoli = {};
    const alta = (id, larghezza, ruolo) => {
      const st = STILI_RIQ[ruolo];
      ruoli[id] = ruolo;
      testi[id] = testiConcetto(per.get(id), st, larghezza - 2 * st.padX);
      return testi[id].h;
    };
    const l = M.layoutRiquadri(concetti, alta);
    if (l.cornice) {
      const k = l.cornice;
      const raggio = 22;
      gSotto.append(s('rect', { class: 'mp-cornice', x: k.x, y: k.y, width: r1(k.w), height: r1(k.h), rx: raggio }));
      gSotto.append(s('path', { class: 'mp-fascia-testa', d: 'M' + k.x + ' ' + r1(k.y + k.testa) + ' V' + (k.y + raggio) + ' Q' + k.x + ' ' + k.y + ' ' + (k.x + raggio) + ' ' + k.y
        + ' H' + r1(k.x + k.w - raggio) + ' Q' + r1(k.x + k.w) + ' ' + k.y + ' ' + r1(k.x + k.w) + ' ' + (k.y + raggio) + ' V' + r1(k.y + k.testa) + ' Z' }));
      gSotto.append(s('line', { class: 'mp-riga-testa', x1: k.x, y1: r1(k.y + k.testa), x2: r1(k.x + k.w), y2: r1(k.y + k.testa) }));
      for (const p of l.parti) gSotto.append(s('rect', { class: 'mp-colonna', x: r1(p.x), y: r1(p.y), width: p.w, height: r1(p.h), rx: 16, 'data-parte': p.id }));
    }
    for (const leg of l.legami) {
      const linea = s('path', { class: 'mp-legame ' + leg.tipo, d: leg.d, 'marker-end': leg.freccia ? 'url(#mp-punta-' + uid + ')' : null, 'data-da': leg.da, 'data-a': leg.a });
      gLegami.append(linea);
      const c = per.get(leg.a);
      legami.push({ da: leg.da, a: leg.a, linea, verbo: leg.verbo && M.verboDi(per, c) ? verbo(c, leg.verbo.x, leg.verbo.y, true) : null });
    }
    for (const id of t.ordine) {
      const c = per.get(id);
      const n = l.nodi[id];
      if (!c || !n) continue;
      const ruolo = ruoli[id] || 'scheda';
      const classi = ruolo === 'radice' ? 'l0 mp-testa' : ruolo === 'parte' ? 'l1 mp-capo' : 'l2 mp-carta';
      pezzo(c, n, STILI_RIQ[ruolo], testi[id], classi, n.livello);
    }
    const mondo = s('g', { class: 'mp-mondo' }, gSotto, gLegami, gVerbi, gNodi);
    const r = t.radice != null ? l.nodi[t.radice] : null;
    return { mondo, larghezza: l.larghezza, altezza: l.altezza, nodi, legami, fuoco: r ? { x: r.x + r.w / 2, y: r.y + r.h / 2, h: r.h, alto: true } : null };
  }

  // --- §45.5 la linea del tempo ------------------------------------------------------------------------------------

  const misuraData = misura('800 13px Figtree', 13);
  const misuraCosa = misura('600 14px Figtree', 14);

  /** Le misure delle schede delle date: la data sopra (13/800), il fatto sotto (14/600, fino a 3 righe). */
  function schedeDate(eventi) {
    const dim = {};
    const righe = {};
    for (const e of eventi) {
      righe[e.id] = M.aCapo(e.cosa, 168, misuraCosa, 3);
      const w = Math.max(misuraData(e.data), ...righe[e.id].map(misuraCosa));
      dim[e.id] = { w: Math.min(200, Math.max(104, Math.ceil(w) + 26)), h: 12 + 16 + 4 + righe[e.id].length * 18 + 10 };
    }
    return { dim, righe };
  }

  function disegnaLinea(contenuto, modo, prova, uid, conEntrata, opz) {
    const eventi = contenuto.eventi || [];
    const { dim, righe } = schedeDate(eventi);
    const l = M.layoutLinea(eventi, dim, { modo, larghezza: (opz && opz.larghezza) || 900 });
    const dateNasc = prova ? prova.scelta.date : new Set();
    const fattiNasc = prova ? prova.scelta.fatti : new Set();
    const scoperto = (k) => !!prova && prova.scoperti.has(k);
    const gSotto = s('g', { class: 'mp-sotto' });
    const gSchede = s('g', { class: 'mp-schede' });
    gSotto.append(s('line', { class: 'mp-asse', x1: 0, y1: l.asse, x2: r1(l.larghezza), y2: l.asse }));
    for (const t of l.tacche) {
      gSotto.append(s('g', { class: 'mp-tacca' },
        s('line', { x1: r1(t.px), y1: l.asse - 5, x2: r1(t.px), y2: l.asse + 5 }),
        s('text', { x: r1(t.px), y: l.asse - 10, 'text-anchor': 'middle' }, t.testo)));
    }
    for (const f of l.fasce) gSotto.append(s('rect', { class: 'mp-fascia', x: r1(f.da), y: r1(f.y - 4), width: r1(Math.max(8, f.a - f.da)), height: 8, rx: 4, 'data-id': f.id }));
    const nodi = new Map();
    eventi.forEach((e, i) => {
      const p = l.eventi[e.id];
      const d = dim[e.id];
      const bordoY = p.sopra ? p.y + d.h / 2 : p.y - d.h / 2;
      gSotto.append(s('line', { class: 'mp-gambo', x1: r1(p.px), y1: l.asse, x2: r1(p.cx), y2: r1(bordoY), 'data-id': e.id }));
      gSotto.append(s('circle', { class: 'mp-punto' + (e.circa ? ' circa' : ''), cx: r1(p.px), cy: l.asse, r: 6, 'data-id': e.id }));
      const dNasc = dateNasc.has(e.id) && !scoperto('d' + e.id);
      const fNasc = fattiNasc.has(e.id) && !scoperto('f' + e.id);
      const g = s('g', {
        class: 'mp-data' + (dNasc || fNasc ? ' con-nascosti' : '') + (conEntrata ? ' mp-entra' : ''),
        transform: 'translate(' + r1(p.cx - d.w / 2) + ' ' + r1(p.y - d.h / 2) + ')', 'data-id': e.id,
        'data-chiave': dateNasc.has(e.id) ? 'd' + e.id : fattiNasc.has(e.id) ? 'f' + e.id : null,
        tabindex: '0', role: 'button', 'aria-label': (dNasc ? 'Data nascosta' : e.data) + ': ' + (fNasc ? 'fatto nascosto' : e.cosa),
      },
      s('rect', { width: d.w, height: d.h, rx: 14 }),
      dNasc ? s('g', { class: 'mp-buco' }, s('rect', { x: 12, y: 10, width: 56, height: 18, rx: 9 }), s('text', { x: 40, y: 23, 'text-anchor': 'middle' }, '?'))
        : s('text', { class: 'data', x: 13, y: 26 }, e.data),
      fNasc ? s('g', { class: 'mp-buco' }, s('rect', { x: 12, y: 34, width: d.w - 24, height: d.h - 44, rx: 9 }), s('text', { x: r1(d.w / 2), y: r1(34 + (d.h - 44) / 2 + 5), 'text-anchor': 'middle' }, '?'))
        : s('text', { class: 'cosa', x: 13, y: 47 }, righe[e.id].map((r, k) => s('tspan', { x: 13, dy: k ? 18 : 0 }, r))));
      if (conEntrata) g.style.setProperty('--ritardo', Math.min(12, i) * 35 + 'ms');
      gSchede.append(g);
      nodi.set(e.id, g);
    });
    const mondo = s('g', { class: 'mp-mondo' }, gSotto, gSchede);
    return { mondo, larghezza: l.larghezza, altezza: l.altezza, nodi, legami: [], layout: l, dim, fuoco: { sinistra: true, y: l.asse } };
  }

  // --- zoom e trascinamento ----------------------------------------------------------------------------------------

  /** Sotto questa misura i testi diventano troppo piccoli: all'apertura non si scende più in basso (Adatta sì). */
  const LEGGIBILE = 0.74;

  /** §45.3 La vista: rotella = zoom intorno al puntatore, trascinare = spostare, + − e Adatta, tastiera. */
  function vistaMappa(svg, disegno) {
    const st = { k: 1, x: 0, y: 0 };
    let trascinato = false;
    const applica = () => disegno.mondo.setAttribute('transform', 'translate(' + r1(st.x) + ' ' + r1(st.y) + ') scale(' + Math.round(st.k * 1000) / 1000 + ')');
    const misure = () => { const r = svg.getBoundingClientRect(); return { w: r.width || 900, h: r.height || 560, r }; };
    function adatta(soloAltezza) {
      const { w, h: alt } = misure();
      const kw = (w - 40) / Math.max(1, disegno.larghezza);
      const kh = (alt - 40) / Math.max(1, disegno.altezza);
      st.k = Math.max(0.25, Math.min(1.15, soloAltezza ? Math.min(1, kh) : Math.min(kw, kh)));
      st.x = soloAltezza && disegno.larghezza * st.k > w ? 20 : (w - disegno.larghezza * st.k) / 2;
      st.y = (alt - disegno.altezza * st.k) / 2;
      applica();
    }
    /** All'apertura: tutta la mappa se si legge ancora; se no si parte leggibili dalla radice (o dall'inizio). */
    function iniziale() {
      const { w, h: alt } = misure();
      const kTutta = Math.min((w - 40) / Math.max(1, disegno.larghezza), (alt - 40) / Math.max(1, disegno.altezza));
      if (kTutta >= LEGGIBILE || !disegno.fuoco) { adatta(); return; }
      const f = disegno.fuoco;
      st.k = LEGGIBILE;
      st.x = f.sinistra ? 20 : w / 2 - f.x * st.k;
      st.y = f.alto ? 24 - (f.y - f.h / 2) * st.k : alt / 2 - f.y * st.k;
      applica();
    }
    function zoom(f, cx, cy) {
      const { w, h: alt } = misure();
      const px = cx == null ? w / 2 : cx;
      const py = cy == null ? alt / 2 : cy;
      const k = Math.max(0.25, Math.min(3, st.k * f));
      st.x = px - (px - st.x) * (k / st.k);
      st.y = py - (py - st.y) * (k / st.k);
      st.k = k;
      applica();
    }
    function sposta(dx, dy) { st.x += dx; st.y += dy; applica(); }
    function centra(x, y) {
      const { w, h: alt } = misure();
      st.x = w / 2 - x * st.k;
      st.y = alt / 2 - y * st.k;
      applica();
    }
    svg.addEventListener('wheel', (e) => {
      e.preventDefault();
      const { r } = misure();
      if (!e.ctrlKey && Math.abs(e.deltaX) > Math.abs(e.deltaY)) { sposta(-e.deltaX, 0); return; }
      zoom(Math.exp(-e.deltaY * 0.0015), e.clientX - r.left, e.clientY - r.top);
    }, { passive: false });
    let da = null;
    svg.addEventListener('pointerdown', (e) => {
      if (e.button !== 0) return;
      da = { x: e.clientX, y: e.clientY, sx: st.x, sy: st.y, id: e.pointerId };
      trascinato = false;
    });
    svg.addEventListener('pointermove', (e) => {
      if (!da || e.pointerId !== da.id) return;
      const dx = e.clientX - da.x;
      const dy = e.clientY - da.y;
      if (!trascinato && Math.hypot(dx, dy) < 5) return;
      if (!trascinato) { trascinato = true; svg.setPointerCapture(e.pointerId); svg.classList.add('trascina'); }
      st.x = da.sx + dx;
      st.y = da.sy + dy;
      applica();
    });
    const fine = (e) => {
      if (!da || e.pointerId !== da.id) return;
      da = null;
      svg.classList.remove('trascina');
      // Il clic che arriva subito dopo un trascinamento non apre niente.
      if (trascinato) setTimeout(() => { trascinato = false; }, 0);
    };
    svg.addEventListener('pointerup', fine);
    svg.addEventListener('pointercancel', fine);
    svg.addEventListener('keydown', (e) => {
      if (e.target !== svg) return;
      const passo = 60;
      const tasti = { '+': () => zoom(1.2), '=': () => zoom(1.2), '-': () => zoom(1 / 1.2), '0': () => adatta(), ArrowLeft: () => sposta(passo, 0), ArrowRight: () => sposta(-passo, 0), ArrowUp: () => sposta(0, passo), ArrowDown: () => sposta(0, -passo) };
      if (tasti[e.key]) { e.preventDefault(); tasti[e.key](); }
    });
    return { adatta, iniziale, zoom, centra, trascinato: () => trascinato, stato: st };
  }

  // --- §45.2 il foglio di scelta -------------------------------------------------------------------------------------

  /** La miniatura del tipo: una linea con i pallini oppure un riquadro diviso in colonne. */
  function miniatura(tipo) {
    const g = tipo === 'date'
      ? [s('line', { x1: 8, y1: 28, x2: 48, y2: 28, class: 'mm-linea' }), [14, 26, 42].map((x, i) => s('circle', { cx: x, cy: 28, r: 4, class: 'mm-punto' + (i === 1 ? ' pieno' : '') })),
        s('rect', { x: 8, y: 10, width: 14, height: 8, rx: 3, class: 'mm-scheda' }), s('rect', { x: 34, y: 38, width: 16, height: 8, rx: 3, class: 'mm-scheda' })]
      : [s('rect', { x: 6, y: 9, width: 44, height: 38, rx: 6, class: 'mm-scheda' }), s('rect', { x: 6, y: 9, width: 44, height: 10, rx: 5, class: 'mm-scheda pieno' }),
        [10, 24, 38].map((x) => s('rect', { x, y: 23, width: 10, height: 20, rx: 3, class: 'mm-scheda' }))];
    return h('span', { class: 'mp-mini', 'aria-hidden': 'true' }, s('svg', { viewBox: '0 0 56 56', width: 56, height: 56 }, g));
  }

  /** §45.2 "Che mappa vuoi?": un clic crea la mappa e apre la sua pagina; se c'è già, la apre e basta. */
  A.apriSceltaMappa = function (schema, m) {
    const info = schema.mappe || { fatte: [], stima_eur: 0, date_possibili: true };
    let invio = false;
    A.apriFoglio({
      titolo: 'Che mappa vuoi?',
      classe: A.classeMateria(m.colore),
      corpo: (c, ctl) => {
        const sotto = h('div', { 'data-sotto': 'scelta-mappa' });
        const righe = [];
        async function scegli(t, fatta) {
          if (invio) return;
          if (fatta && fatta.stato !== 'errore') { ctl.chiudi(true); A.vai('#mappa/' + fatta.id); return; }
          invio = true;
          righe.forEach((b) => { b.disabled = true; });
          svuota(sotto);
          const r = await A.api.post('/server/api/schemi/' + schema.id + '/mappe', { tipo: t.tipo });
          invio = false;
          if (ctl.chiuso) return;
          if (!r.ok || !r.dati) {
            righe.forEach((b) => { b.disabled = b.dataset.spenta === 'si' || !!S.senzaRete; });
            svuota(sotto, A.messaggio(r.stato === 402 ? 'avviso' : 'errore', r.errore));
            return;
          }
          if (S.motore) S.motore.in_preparazione = true;
          ctl.chiudi(true);
          A.vai('#mappa/' + r.dati.id);
        }
        TIPI.forEach((t) => {
          const fatta = L.mappaDelTipo(info.fatte, t.tipo);
          const st = L.statoTipoMappa(fatta, info.stima_eur, A.modalitaProva(), t.tipo === 'date' ? info.date_possibili !== false : true);
          const spenta = st.tipo === 'spenta' || (S.senzaRete && !(fatta && fatta.stato === 'pronta'));
          const b = h('button', {
            type: 'button', class: 'rs-riga', 'data-tipo-mappa': t.tipo, 'data-spenta': st.tipo === 'spenta' ? 'si' : null, disabled: spenta ? true : null,
            onclick: () => scegli(t, fatta),
          }, miniatura(t.tipo), h('span', { class: 'testi' }, h('span', { class: 'n' }, t.nome), h('span', { class: 'f' }, st.tipo === 'spenta' ? st.frase : t.frase)),
          st.testo ? h('span', { class: 'prezzo', 'data-prezzo': t.tipo }, st.testo) : null);
          righe.push(b);
        });
        svuota(c, h('div', { class: 'rs-righe' }, righe), sotto);
      },
    });
  };

  // --- §45.3 la pagina ---------------------------------------------------------------------------------------------

  A.pagine.mappa = async function (box, rotta, vivo) {
    const pag = h('div', { class: 'pagina mp-pagina-larga' }, A.segnaposto(3), A.segnaposto(3));
    svuota(box, pag);
    let attuale = null;
    let schemaId = null;
    let info = { fatte: [], stima_eur: 0, date_possibili: true };
    let elenco = [];
    let m = null;
    let invio = false;
    let prova = null; // { seme, scelta, scoperti: Set }
    let scelto = null;
    let vista = null;
    let disegno = null;
    let entrata = true;
    const ricorda = (x) => {
      if (!attuale || attuale.id !== x.id) { prova = null; scelto = null; entrata = true; }
      attuale = x;
      schemaId = x.schema_id;
    };
    const delTipo = (t) => L.mappaDelTipo(elenco, t);
    const formaDi = (x) => {
      const salvata = A.memoriaStudio.leggi(chiaveForma(x.id));
      // Prima della 1.3.8 c'era l'albero: chi l'aveva scelto ora vede i riquadri.
      if (x.tipo === 'concetti') return salvata === 'raggiera' ? 'raggiera' : salvata === 'riquadri' ? 'riquadri' : M.formaConsigliata(x.contenuto);
      if (salvata === 'scala' || salvata === 'fila') return salvata;
      const { dim } = schedeDate((x.contenuto && x.contenuto.eventi) || []);
      return M.modoConsigliato((x.contenuto && x.contenuto.eventi) || [], dim, 1000);
    };

    // §44 La modifica della mappa aperta (cambia tipo = cambia mappa: si segue quella nuova).
    let seg = null;
    const aggiornaElenco = (x) => { elenco = elenco.filter((y) => y.id !== x.id).concat([x]); };
    function seguito() {
      if (seg && seg.id === attuale.id) return seg;
      const idQui = attuale.id;
      seg = A.seguiModifica({
        tipo: 'mappa', id: idQui,
        vivo: () => vivo() && !!attuale && attuale.id === idQui,
        ricarica: () => A.api.get('/server/api/mappe/' + idQui),
        pronta: (nuovo) => { aggiornaElenco(nuovo); prova = null; scelto = null; ricorda(nuovo); entrata = true; disegna(false); },
        cambia: (nuovo) => { if (attuale && attuale.id === idQui) attuale.modifica = nuovo.modifica; },
      });
      return seg;
    }

    S.aggiornaPagina = () => {
      if (attuale && inLavoro(attuale)) { richiedi(); return; }
      if (seg && !document.querySelector('[data-correggi]')) { seg.subito(); seg.aggiorna(); }
    };

    async function carica() {
      const r = await A.api.get('/server/api/mappe/' + rotta.id);
      if (!vivo()) return;
      if (!r.ok || !r.dati) {
        if (r.rete) svuota(pag, A.messaggio('avviso', 'Si apre quando torna la rete.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: carica }, 'Riprova')]));
        else svuota(pag, A.erroreConRiprova(r, carica));
        return;
      }
      ricorda(r.dati);
      await Promise.all([S.materie ? null : A.caricaMaterie(), S.salute ? null : A.caricaSalute(), aspettaCaratteri()]);
      m = A.materiaDi(attuale.materia_id) || { id: attuale.materia_id, nome: '', colore: 'ambra' };
      S.materiaAperta = attuale.materia_id;
      A.disegnaMenu();
      A.disegnaStato();
      await caricaIntorno();
      if (!vivo()) return;
      disegna(false);
    }

    /** L'altro tipo dello stesso schema e la stima (quello che non c'è ancora si può chiedere da qui). */
    async function caricaIntorno() {
      const [ri, rs] = await Promise.all([A.api.get('/server/api/schemi/' + schemaId + '/mappe'), A.api.get('/server/api/schemi/' + schemaId)]);
      elenco = ri.ok && Array.isArray(ri.dati) ? ri.dati : [attuale];
      if (rs.ok && rs.dati && rs.dati.mappe) info = rs.dati.mappe;
    }

    async function richiedi() {
      if (!attuale) return;
      const r = await A.api.get('/server/api/mappe/' + attuale.id);
      if (!vivo() || !r.ok || !r.dati || r.dati.id !== attuale.id) return;
      const eraInLavoro = inLavoro(attuale);
      ricorda(r.dati);
      aggiornaElenco(r.dati);
      if (eraInLavoro && !inLavoro(r.dati)) entrata = true;
      disegna(false);
    }

    /** Il cambio di tipo resta nella stessa pagina: l'indirizzo si aggiorna senza ridisegnare tutto. */
    function mostra(x) {
      ricorda(x);
      const nuovo = '#mappa/' + x.id;
      history.replaceState(null, '', nuovo);
      if (S.storia.length) S.storia[S.storia.length - 1] = nuovo;
      entrata = true;
      disegna(true);
    }

    async function chiedi(t) {
      if (invio) return;
      invio = true;
      disegna(false, t);
      const r = await A.api.post('/server/api/schemi/' + schemaId + '/mappe', { tipo: t });
      invio = false;
      if (!vivo()) return;
      if (!r.ok || !r.dati) { disegna(false, t, r); return; }
      if (S.motore) S.motore.in_preparazione = true;
      elenco = elenco.filter((x) => x.tipo !== t).concat([r.dati]);
      mostra(r.dati);
    }

    function apriModifica() {
      const sg = seguito();
      A.apriModifica({ tipo: 'mappa', oggetto: attuale, titolo: (attuale.titolo || 'Mappa') + ' · ' + nomeTipo(attuale.tipo), materia: m, inviata: (md) => sg.avvia(md) });
    }

    function correggi() {
      A.correggiAMano(pag, {
        tipo: 'mappa', oggetto: attuale, materia: m, indietro: () => A.vai('#schema/' + schemaId),
        salvato: (nuovo) => { aggiornaElenco(nuovo); prova = null; ricorda(nuovo); disegna(false); },
        annullato: () => disegna(false),
      });
    }

    async function scambia() {
      const id = attuale.id;
      const nuovo = await A.scambiaVersione('mappa', id, attuale.versione_da_parte);
      if (!nuovo || !vivo() || !attuale || attuale.id !== id) return;
      aggiornaElenco(nuovo);
      prova = null;
      ricorda(nuovo);
      if (seg) seg.evidenzia = new Set();
      disegna(false);
    }

    async function rifai() {
      if (!(await A.conferma({ titolo: 'La rifaccio da capo?', testo: 'Quella che c\'è adesso sparisce.', si: 'Rifalla', pericolo: true }))) return;
      const t = attuale.tipo;
      const d = await A.api.elimina('/server/api/mappe/' + attuale.id);
      if (!d.ok) { A.avviso(d.errore); return; }
      elenco = elenco.filter((x) => x.id !== attuale.id);
      chiedi(t);
    }

    async function elimina() {
      if (!(await A.conferma({ titolo: 'Eliminare la mappa?', testo: 'I soldi già spesi restano nel conto del mese.', si: 'Elimina', pericolo: true }))) return;
      const r = await A.api.elimina('/server/api/mappe/' + attuale.id);
      if (!r.ok) { A.avviso(r.errore); return; }
      A.vai('#schema/' + schemaId);
    }

    // --- §45.7 Scarica PDF: si stampa quello che si vede, sempre in chiaro ---
    let stampa = null;
    function pulsanteStampa() {
      const etichetta = h('span', null, 'Scarica PDF');
      const b = h('button', {
        type: 'button', class: 'pillola-azione', 'data-azione': 'scarica-pdf',
        onclick: async () => {
          if (stampa) return;
          etichetta.textContent = 'Preparo il PDF…';
          b.disabled = true;
          stampa = faiStampa();
          try { await stampa; } finally { stampa = null; etichetta.textContent = 'Scarica PDF'; b.disabled = false; }
        },
      }, icona('scarica'), etichetta);
      return b;
    }

    async function faiStampa() {
      const forma = formaDi(attuale);
      const nome = L.nomeFilePdf((attuale.titolo || 'Mappa') + (prova ? ' - da completare' : ''), 'Mappa');
      const foglio = A.fogliStampaMappa({ mappa: attuale, materia: m, forma, prova });
      document.body.append(foglio);
      document.documentElement.classList.add('stampa-mappa');
      await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
      let r;
      try {
        r = await A.api.post('/locale/stampa-pdf', { nome });
      } finally {
        foglio.remove();
        document.documentElement.classList.remove('stampa-mappa');
      }
      if (r.ok && r.dati && r.dati.annullato) return;
      if (r.ok && r.dati && r.dati.ok) {
        A.avviso('PDF salvato', { dura: 6000, azione: { testo: 'Apri', fai: async () => { const x = await A.api.post('/locale/apri-pdf', { id: r.dati.id }); if (!x.ok) A.avviso(x.errore); } } });
        return;
      }
      A.avviso(r.errore || 'Non sono riuscito a fare il PDF.', { dura: 6000, azione: { testo: 'Riprova', fai: () => { if (!stampa) { stampa = faiStampa(); stampa.finally(() => { stampa = null; }); } } } });
    }

    // --- §45.6 Mettiti alla prova ---
    function avviaProva(nuovoSeme) {
      const seme = nuovoSeme || ((Date.now() % 100000) + 1);
      prova = { seme, scelta: M.sceltaProva(attuale.tipo, attuale.contenuto || {}, seme), scoperti: new Set() };
      scelto = null;
      entrata = false;
      disegna(false);
    }
    const totaleProva = () => (prova ? (attuale.tipo === 'concetti' ? prova.scelta.concetti.size + prova.scelta.verbi.size : prova.scelta.date.size + prova.scelta.fatti.size) : 0);

    function scopri(chiave) {
      if (!prova || prova.scoperti.has(chiave)) return;
      prova.scoperti.add(chiave);
      const visto = vista ? Object.assign({}, vista.stato) : null;
      entrata = false;
      disegna(false, null, null, visto, chiave);
      if (prova && prova.scoperti.size === totaleProva()) A.avviso('Hai scoperto tutto. Rimescola per un altro giro.', { dura: 4000 });
    }

    // --- §45.4 il pannello della spiegazione ---
    function pannello(id) {
      const c = attuale.contenuto || {};
      if (attuale.tipo === 'concetti') {
        const x = (c.concetti || []).find((y) => y.id === id);
        if (!x) return null;
        const vicini = M.vicini(c, id);
        const genitore = (c.concetti || []).find((y) => y.id === x.genitore);
        const etichetta = x.genitore == null ? 'Argomento' : genitore && genitore.genitore == null ? 'Parte' : 'Concetto';
        return [
          h('span', { class: 'etichetta' }, etichetta),
          h('h3', { class: 'mp-p-titolo' }, x.testo),
          x.frase ? h('p', { class: 'mp-p-frase' }, x.frase) : null,
          x.spiegazione ? h('p', { class: 'mp-p-testo' }, x.spiegazione) : null,
          vicini.length ? h('div', { class: 'mp-p-vicini' }, vicini.map((v) => {
            const altro = h('strong', { class: 't' }, v.testo);
            const questo = h('span', { class: 'q' }, x.testo);
            // Senza verbo il legame è "sta dentro": la parte contiene i suoi concetti.
            const righe = v.verbo
              ? (v.verso === 'a' ? [questo, h('span', { class: 'v' }, ' ' + v.verbo + ' '), altro] : [altro, h('span', { class: 'v' }, ' ' + v.verbo + ' '), questo])
              : (v.verso === 'a' ? [questo, h('span', { class: 'v' }, ' comprende '), altro] : [questo, h('span', { class: 'v' }, ' fa parte di '), altro]);
            return h('button', {
              type: 'button', class: 'mp-p-vicino', 'data-vicino': String(v.id),
              'aria-label': 'Vai a ' + v.testo, onclick: () => seleziona(v.id, true),
            }, righe);
          })) : null,
          sezione(x.sezione),
        ];
      }
      const e = (c.eventi || []).find((y) => y.id === id);
      if (!e) return null;
      return [
        h('span', { class: 'etichetta mp-p-data' }, e.data),
        h('h3', { class: 'mp-p-titolo' }, e.cosa),
        e.spiegazione ? h('p', { class: 'mp-p-testo' }, e.spiegazione) : null,
        e.fine != null ? h('p', { class: 'mp-p-durata' }, 'Dal ' + M.annoTesto(e.x) + ' al ' + M.annoTesto(e.x_fine)) : null,
        sezione(e.sezione),
      ];
    }
    function sezione(n) {
      if (n == null) return null;
      return h('button', { type: 'button', class: 'pulsante tenue mp-p-schema', 'data-azione': 'apri-nello-schema', onclick: () => A.apriSchemaAllaParte(schemaId, n) }, 'Apri nello schema · parte ' + n);
    }

    /** Un clic su un concetto o su una data: si accende lui con i vicini, a destra la spiegazione. */
    function seleziona(id, centra) {
      scelto = id;
      S.escPagina = chiudiPannello;
      accendi();
      const p = document.querySelector('[data-mappa-pannello]');
      if (p) {
        const corpo = pannello(id);
        svuota(p.querySelector('.mp-p-corpo'), corpo);
        p.classList.toggle('aperto', !!corpo);
        p.setAttribute('aria-hidden', corpo ? 'false' : 'true');
      }
      if (centra && vista && disegno && disegno.nodi.get(id)) {
        const g = disegno.nodi.get(id);
        const bb = g.getBBox ? g.getBBox() : { x: 0, y: 0, width: 0, height: 0 };
        const t = /translate\(([-\d.]+) ([-\d.]+)\)/.exec(g.getAttribute('transform') || '');
        if (t) vista.centra(Number(t[1]) + bb.width / 2, Number(t[2]) + bb.height / 2);
      }
    }
    function chiudiPannello() {
      scelto = null;
      S.escPagina = null;
      accendi();
      const p = document.querySelector('[data-mappa-pannello]');
      if (p) { p.classList.remove('aperto'); p.setAttribute('aria-hidden', 'true'); }
    }
    function accendi() {
      if (!disegno) return;
      const svg = disegno.mondo.ownerSVGElement;
      if (!svg) return;
      svg.classList.toggle('con-scelta', scelto != null);
      const vicini = new Set(scelto == null ? [] : [scelto]);
      if (scelto != null && attuale.tipo === 'concetti') for (const v of M.vicini(attuale.contenuto || {}, scelto)) vicini.add(v.id);
      for (const [id, g] of disegno.nodi) { g.classList.toggle('acceso', vicini.has(id)); g.classList.toggle('scelto', id === scelto); }
      for (const l of disegno.legami) {
        const si = scelto != null && (l.da === scelto || l.a === scelto);
        l.linea.classList.toggle('acceso', si);
        if (l.verbo) l.verbo.classList.toggle('acceso', si);
      }
      svg.querySelectorAll('.mp-gambo, .mp-punto, .mp-fascia').forEach((el) => el.classList.toggle('acceso', Number(el.getAttribute('data-id')) === scelto));
    }

    // --- la testata e le barre ---
    function testata(sg) {
      const pr = pronta(attuale);
      return h('div', { class: 'sch-testa' },
        A.frecciaIndietro(() => A.vai('#schema/' + schemaId)), A.pillolaMateria(m), h('span', { class: 'spazio' }),
        A.modalitaProva() ? h('span', { class: 'pillola stato prova' }, 'Prova') : null,
        sg ? sg.pillola(apriModifica) : null,
        pr ? pulsanteStampa() : null,
        A.menuPuntini(() => {
          const perche = sg ? sg.motivo() : null;
          return [
            { testo: 'Rifai', azione: 'rifai-mappa', spenta: S.senzaRete || inLavoro(attuale), fai: rifai },
            sg ? { testo: 'Correggi a mano', azione: 'correggi-a-mano', spenta: !!perche, frase: perche, fai: correggi } : null,
            sg ? A.voceVersione(attuale, scambia) : null,
            { testo: 'Elimina', pericolo: true, azione: 'elimina-mappa', spenta: S.senzaRete, fai: elimina },
          ];
        }));
    }

    function barraTipi(tipoVisto) {
      const barra = h('div', { class: 'segmenti binario rs-segmenti mp-tipi', role: 'group', 'aria-label': 'Che mappa' });
      barra.style.setProperty('--n', '2');
      TIPI.forEach((t) => {
        const spenta = t.tipo === 'date' && info.date_possibili === false && !delTipo('date');
        barra.append(h('button', {
          type: 'button', 'aria-pressed': tipoVisto === t.tipo ? 'true' : 'false', 'data-tipo': t.tipo, disabled: spenta ? true : null,
          title: spenta ? 'Poche date in questo schema' : null,
          onclick: () => {
            if (tipoVisto === t.tipo) return;
            const x = delTipo(t.tipo);
            if (x) { mostra(x); return; }
            disegna(true, t.tipo);
          },
        }, t.nome));
      });
      return barra;
    }

    function barraVista(tipi) {
      const concetti = attuale.tipo === 'concetti';
      const forma = formaDi(attuale);
      const scelte = concetti ? [['riquadri', 'A riquadri'], ['raggiera', 'A raggiera']] : [['scala', 'In scala'], ['fila', 'In fila']];
      const seg2 = h('div', { class: 'segmenti mp-forma', role: 'group', 'aria-label': concetti ? 'Forma della mappa' : 'Come mettere le date' });
      seg2.style.setProperty('--n', '2');
      scelte.forEach(([k, t]) => seg2.append(h('button', {
        type: 'button', 'aria-pressed': forma === k ? 'true' : 'false', 'data-forma-mappa': k,
        onclick: () => { if (forma === k) return; A.memoriaStudio.scrivi(chiaveForma(attuale.id), k); scelto = null; entrata = true; disegna(false); },
      }, t)));
      const provaEl = prova
        ? h('div', { class: 'mp-prova-barra', 'data-prova': 'si' },
          h('span', { class: 'mp-conta', 'aria-live': 'polite' }, 'Scoperti ' + prova.scoperti.size + ' su ' + totaleProva()),
          h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'rimescola', onclick: () => avviaProva() }, 'Rimescola'),
          h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'fine-prova', onclick: () => { prova = null; entrata = false; disegna(false); } }, 'Basta così'))
        : h('button', { type: 'button', class: 'pillola-azione mp-prova', 'data-azione': 'mettiti-alla-prova', 'data-visita': 'prova-mappa', onclick: () => avviaProva() }, icona('domanda'), 'Mettiti alla prova');
      return h('div', { class: 'mp-strumenti' }, tipi, seg2, h('span', { class: 'spazio' }), provaEl);
    }

    /** Lo zoom sta dentro la tela, in basso a sinistra (a destra si apre la spiegazione). */
    function pulsantiZoom() {
      return h('div', { class: 'mp-zoom', role: 'group', 'aria-label': 'Zoom' },
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Rimpicciolisci', 'data-azione': 'zoom-meno', onclick: () => vista && vista.zoom(1 / 1.25) }, icona('meno')),
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Ingrandisci', 'data-azione': 'zoom-piu', onclick: () => vista && vista.zoom(1.25) }, icona('piu')),
        h('button', { type: 'button', class: 'pillola-azione', 'data-azione': 'adatta', onclick: () => vista && vista.adatta() }, icona('adatta'), 'Adatta'));
    }

    // --- il disegno sulla tela (vista e pannello) ---
    function telaMappa(visto, appenaScoperto) {
      const forma = formaDi(attuale);
      const uid = ++contaUid;
      const contenuto = attuale.contenuto || {};
      disegno = attuale.tipo === 'concetti'
        ? disegnaConcetti(contenuto, forma, prova, uid, entrata && A.conMovimenti())
        : disegnaLinea(contenuto, forma, prova, uid, entrata && A.conMovimenti(), { larghezza: 900 });
      const svg = s('svg', { class: 'mp-tela' + (attuale.tipo === 'date' ? ' linea' : ''), tabindex: '0', role: 'group', 'aria-label': (attuale.tipo === 'concetti' ? 'Mappa dei concetti: ' : 'Linea del tempo: ') + (attuale.titolo || '') + '. Rotella per lo zoom, trascina per spostarti.' },
        definizioni(uid), disegno.mondo);
      const corpoPannello = h('div', { class: 'mp-p-corpo' });
      const pann = h('aside', { class: 'mp-pannello', 'data-mappa-pannello': 'si', 'aria-hidden': 'true', 'aria-label': 'Spiegazione' },
        h('button', { type: 'button', class: 'tondo mp-p-chiudi', 'aria-label': 'Chiudi', 'data-azione': 'chiudi-spiegazione', onclick: chiudiPannello }, icona('chiudi')),
        corpoPannello);
      const scheda = h('div', { class: 'mp-scheda', 'data-mappa': attuale.tipo, 'data-forma': forma }, svg, pulsantiZoom(), pann);
      // Un clic su un pezzo: scopre (in prova) oppure apre la spiegazione; un clic sul vuoto chiude.
      const pezzo = (e) => e.target.closest && e.target.closest('.mp-nodo, .mp-data, .mp-verbo[data-chiave]');
      svg.addEventListener('click', (e) => {
        if (vista && vista.trascinato()) return;
        const g = pezzo(e);
        if (!g) { chiudiPannello(); return; }
        const chiave = g.getAttribute('data-chiave');
        if (prova && chiave && !prova.scoperti.has(chiave)) { scopri(chiave); return; }
        if (g.classList.contains('mp-verbo')) return;
        const id = Number(g.getAttribute('data-id'));
        if (scelto === id) chiudiPannello(); else seleziona(id, false);
      });
      svg.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        const g = pezzo(e);
        if (!g) return;
        e.preventDefault();
        g.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      });
      requestAnimationFrame(() => {
        if (!svg.isConnected) return;
        vista = vistaMappa(svg, disegno);
        if (visto) { Object.assign(vista.stato, visto); disegno.mondo.setAttribute('transform', 'translate(' + r1(visto.x) + ' ' + r1(visto.y) + ') scale(' + visto.k + ')'); }
        else vista.iniziale();
        if (scelto != null) seleziona(scelto, false);
        if (appenaScoperto) {
          const el = svg.querySelector('[data-chiave="' + appenaScoperto + '"]');
          if (el) el.classList.add('appena');
        }
      });
      return scheda;
    }

    /** `tipoVuoto` = si guarda un tipo che non esiste ancora. `visto` = zoom da tenere (dopo uno scoperto). */
    function disegna(cambio, tipoVuoto, erroreChiedi, visto, appenaScoperto) {
      const vistoTipo = tipoVuoto || attuale.tipo;
      const qui = !tipoVuoto && pronta(attuale);
      const sg = qui ? seguito() : null;
      if (sg) sg.imposta(attuale);
      const sotto = qui ? [M.fraseMappa(attuale.tipo, attuale.contenuto), attuale.tipo === 'concetti' && attuale.contenuto && attuale.contenuto.domanda].filter(Boolean).join(' · ') : null;
      const parti = [testata(sg),
        h('div', { class: 'sch-titoli' }, h('h1', null, attuale.titolo || 'Mappa'), sotto ? h('span', { class: 'sottotitolo', 'data-sotto-mappa': 'si' }, sotto) : null),
        qui && attuale.schema_cambiato ? h('div', { class: 'mod-vecchio', 'data-vecchio': 'si' },
          h('span', null, 'Lo schema è cambiato dopo questa mappa.'),
          h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'rifalla', disabled: S.senzaRete ? true : null, onclick: rifai }, 'Rifalla')) : null,
        sg ? sg.el : null];
      // Con la mappa pronta il tipo sta nella riga degli strumenti; negli altri casi da solo.
      const conTela = !tipoVuoto && !inLavoro(attuale) && attuale.stato !== 'errore';
      if (!conTela) parti.push(barraTipi(vistoTipo));
      if (tipoVuoto) {
        const spesa = invio ? 'Chiedo la mappa…' : A.modalitaProva() ? 'Falla' : 'Falla · ' + L.euro(info.stima_eur || 0);
        parti.push(h('div', { class: 'rs-manca' + (cambio ? ' dissolve' : ''), 'data-tipo-manca': tipoVuoto },
          h('button', { type: 'button', class: 'pulsante pieno medio', 'data-azione': 'fai-mappa', disabled: invio || S.senzaRete ? true : null, onclick: () => chiedi(tipoVuoto) }, spesa),
          erroreChiedi ? A.messaggio(erroreChiedi.stato === 402 ? 'avviso' : 'errore', erroreChiedi.errore) : null));
        disegno = null;
      } else if (inLavoro(attuale)) {
        parti.push(h('div', { class: 'rs-attesa', 'data-vista': 'lavoro' }, h('span', { class: 'frase' }, attuale.frase || 'Disegno la mappa'), A.segnaposto(3)));
        A.ripeti(vivo, richiedi, T.stato);
        disegno = null;
      } else if (attuale.stato === 'errore') {
        parti.push(A.messaggio('errore', attuale.errore || 'La mappa non è riuscita.', [h('button', {
          type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-mappa',
          onclick: async () => {
            const r = await A.api.post('/server/api/mappe/' + attuale.id + '/riprova', {});
            if (!r.ok || !r.dati) { A.avviso(r.errore); return; }
            if (S.motore) S.motore.in_preparazione = true;
            ricorda(r.dati);
            aggiornaElenco(r.dati);
            disegna(false);
          },
        }, 'Riprova')]), h('div', null, h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'elimina-mappa', onclick: elimina }, 'Elimina')));
        disegno = null;
      } else {
        if (attuale.nota) parti.push(h('div', { class: 'sch-nota', 'data-nota': 'mappa' }, h('div', { class: 'testa' }, icona('domanda'), 'Non ho trovato tutto'), h('p', null, attuale.nota)));
        parti.push(barraVista(barraTipi(vistoTipo)));
        parti.push(telaMappa(visto, appenaScoperto));
        entrata = false;
      }
      svuota(pag, h('div', { class: 'mp-pagina ' + A.classeMateria(m.colore) + (cambio ? ' cambia' : '') }, parti));
      S.escPagina = scelto != null ? chiudiPannello : null;
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    await carica();
  };

  // --- §45.7 i fogli per la stampa --------------------------------------------------------------------------------

  /** Il PDF è A4 orizzontale: dentro i margini 1032 × 704 px (a 96 punti per pollice). */
  const PAGINA = { w: 1032, h: 700 };

  /**
   * Il contenitore che si stampa (invisibile a schermo): una o più pagine con la mappa in chiaro, il titolo sulla
   * prima e la firma in bianco in fondo a ognuna, come i PDF del server. La linea del tempo lunga va su più pagine,
   * tagliata dove non c'è una scheda.
   */
  A.fogliStampaMappa = function (o) {
    const x = o.mappa;
    const uid = ++contaUid;
    const colori = M.COLORI_CHIARI[o.materia && o.materia.colore] || M.COLORI_CHIARI.ambra;
    const foglio = h('div', { class: 'mappa-stampa', 'aria-hidden': 'true' });
    foglio.style.setProperty('--m-pieno', colori[0]);
    foglio.style.setProperty('--m-tenue', colori[1]);
    foglio.style.setProperty('--m-testo', colori[2]);
    const sotto = [o.materia && o.materia.nome, x.tipo === 'concetti' ? 'Mappa dei concetti' : 'Linea del tempo', M.fraseMappa(x.tipo, x.contenuto)].filter(Boolean).join(' · ');
    const testa = h('div', { class: 'ms-testa' }, h('h1', null, x.titolo || 'Mappa'), h('p', null, sotto),
      x.tipo === 'concetti' && x.contenuto && x.contenuto.domanda ? h('p', { class: 'ms-domanda' }, x.contenuto.domanda) : null,
      o.prova ? h('p', { class: 'ms-domanda' }, 'Da completare: scrivi al posto dei punti di domanda.') : null);
    const firma = () => h('div', { class: 'ms-firma' }, x.firma || 'Lectio');
    const altezzaUtile = (prima) => PAGINA.h - (prima ? 110 : 30);
    const d = x.tipo === 'concetti' ? disegnaConcetti(x.contenuto || {}, o.forma, o.prova, uid, false) : disegnaLinea(x.contenuto || {}, o.forma, o.prova, uid, false, { larghezza: 900 });
    if (x.tipo === 'concetti') {
      const svg = s('svg', { class: 'mp-tela stampa', viewBox: '0 0 ' + r1(d.larghezza) + ' ' + r1(d.altezza), width: PAGINA.w, height: altezzaUtile(true), preserveAspectRatio: 'xMidYMid meet' }, definizioni(uid), d.mondo);
      foglio.append(h('section', { class: 'ms-pagina' }, testa, svg, firma()));
      return foglio;
    }
    // Linea del tempo: stessa scala su tutte le pagine. Se rimpicciolendo poco ci sta in larghezza, una pagina sola;
    // se no si taglia in più pagine dove non c'è una scheda.
    const inAltezza = Math.min(1, altezzaUtile(true) / d.altezza);
    const inLarghezza = PAGINA.w / Math.max(1, d.larghezza);
    const scala = inLarghezza >= 0.6 ? Math.min(inAltezza, inLarghezza) : inAltezza;
    const pezzi = [];
    for (const [id, p] of Object.entries(d.layout.eventi)) { const w = d.dim[id].w; pezzi.push([p.cx - w / 2, p.cx + w / 2]); }
    for (const f of d.layout.fasce) pezzi.push([f.da, f.a]);
    const tagli = M.pagine(pezzi, d.larghezza, PAGINA.w / scala);
    const unica = tagli.length === 1;
    tagli.forEach(([a, b], i) => {
      const id2 = ++contaUid;
      const copia = d.mondo.cloneNode(true);
      const larga = unica ? PAGINA.w : Math.min(PAGINA.w, (b - a) * scala);
      const svg = s('svg', { class: 'mp-tela linea stampa', viewBox: r1(a) + ' 0 ' + r1(b - a) + ' ' + r1(d.altezza), width: r1(larga), height: r1(unica ? altezzaUtile(true) : d.altezza * scala), preserveAspectRatio: 'xMinYMid meet' },
        definizioni(id2), copia);
      foglio.append(h('section', { class: 'ms-pagina' }, i === 0 ? testa : h('div', { class: 'ms-testa piccola' }, h('p', null, (x.titolo || 'Mappa') + ' · pagina ' + (i + 1) + ' di ' + tagli.length)), svg, firma()));
    });
    return foglio;
  };
})();
