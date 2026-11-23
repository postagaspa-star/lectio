/*
 * Lectio per il computer: i pezzi per gli esercizi con i numeri (contratto §21.1, guida docs/aspetto.md §36.5).
 * La barra della matematica, l'anteprima "come sul libro" della scrittura in riga, la tabella e il grafico delle
 * domande. Niente librerie scaricate e niente HTML da stringhe: solo nodi DOM e SVG fatti qui.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, h } = A;
  const SVG = 'http://www.w3.org/2000/svg';

  // --- Anteprima della formula -----------------------------------------------------------------

  /** I nodi di pezziFormula come DOM: frazioni impilate, esponenti in alto, pedici in basso, radice con la linea. */
  function nodiFormula(nodi) {
    return (nodi || []).map((n) => {
      switch (n.t) {
        case 'frazione': return h('span', { class: 'f-fraz' }, h('span', { class: 'f-num' }, nodiFormula(n.num)), h('span', { class: 'f-den' }, nodiFormula(n.den)));
        case 'su': return h('sup', { class: 'f-su' }, nodiFormula(n.v));
        case 'giu': return h('sub', { class: 'f-giu' }, nodiFormula(n.v));
        case 'radice': return h('span', { class: 'f-rad' }, h('span', { class: 'f-segno', 'aria-hidden': 'true' }, '√'), h('span', { class: 'f-sotto' }, nodiFormula(n.v)));
        default: return document.createTextNode(String(n.v).replace(/\*/g, '·'));
      }
    });
  }

  /** Una riga scritta in riga, impaginata. */
  function formula(testo) {
    return h('span', { class: 'formula' }, nodiFormula(L.pezziFormula(testo)));
  }

  /**
   * L'anteprima di un campo: si vede solo se qualche riga ha qualcosa da impaginare (frazione, potenza, pedice,
   * radice). `aggiorna(testo)` la ridisegna.
   */
  function anteprima() {
    const el = h('div', { class: 'formule-anteprima', 'data-anteprima': 'si', 'aria-hidden': 'true', hidden: true });
    function aggiorna(testo) {
      const t = String(testo || '');
      if (!L.daImpaginare(t)) {
        el.hidden = true;
        el.replaceChildren();
        return;
      }
      el.hidden = false;
      el.replaceChildren(...t.split('\n').filter((r) => r.trim()).map((r) => h('div', { class: 'f-riga' }, formula(r))));
    }
    return { el, aggiorna };
  }

  // --- La barra della matematica ----------------------------------------------------------------

  /** Il disegno di un tasto: per gli schemi un piccolo esempio impaginato, per i segni il segno. */
  function faccia(t) {
    switch (t.chiave) {
      case 'frazione': return h('span', { class: 'f-fraz piccola' }, h('span', { class: 'f-num' }, 'a'), h('span', { class: 'f-den' }, 'b'));
      case 'radice': return h('span', { class: 'f-rad' }, h('span', { class: 'f-segno' }, '√'), h('span', { class: 'f-sotto' }, 'x'));
      case 'potenza': return h('span', null, 'x', h('sup', { class: 'f-su' }, 'n'));
      case 'pedice': return h('span', null, 'x', h('sub', { class: 'f-giu' }, '1'));
      default: return t.segno;
    }
  }

  /**
   * La barra: i tasti scrivono nel campo che ha (o ha avuto per ultimo) il cursore. `campi()` dà i campi della
   * domanda; il primo è quello di partenza. Il clic non toglie il cursore dal campo (mousedown senza focus).
   */
  function barra(campi) {
    let attivo = null;
    const scegli = () => {
      const tutti = campi();
      return tutti.includes(attivo) ? attivo : tutti[0];
    };
    const tasto = (t) => h('button', {
      type: 'button', class: 'tasto-f' + (t.prima !== undefined ? ' schema' : ''), title: t.nome, 'aria-label': t.nome, 'data-tasto': t.chiave,
      onmousedown: (e) => e.preventDefault(),
      onclick: () => {
        const c = scegli();
        if (!c) return;
        const r = L.inserisciFormula(c.value, c.selectionStart, c.selectionEnd, t.chiave);
        c.value = r.valore;
        c.focus();
        try { c.setSelectionRange(r.inizio, r.fine); } catch (e) { /* jsdom vecchio */ }
        c.dispatchEvent(new Event('input', { bubbles: true }));
      },
    }, faccia(t));
    // Due gruppi: gli schemi (frazione, radice, potenza, pedice, parentesi) e i segni; se non stanno in una riga,
    // vanno a capo un gruppo alla volta.
    const el = h('div', { class: 'barra-formule', role: 'toolbar', 'aria-label': 'Simboli', 'data-barra': 'formule' },
      h('div', { class: 'gruppo' }, L.TASTI_FORMULA.filter((t) => t.prima !== undefined).map(tasto)),
      h('div', { class: 'gruppo' }, L.TASTI_FORMULA.filter((t) => t.prima === undefined).map(tasto)));
    return {
      el,
      /** Da chiamare su ogni campo della domanda: tiene il conto di quello col cursore. */
      segui(campo) { campo.addEventListener('focus', () => { attivo = campo; }); },
    };
  }

  // --- Tabella e grafico della domanda ------------------------------------------------------------

  function tabella(t) {
    if (!t || !Array.isArray(t.colonne) || !Array.isArray(t.valori)) return null;
    const colonne = t.colonne.map((c) => String(c == null ? '' : c));
    return h('figure', { class: 'sim-figura', 'data-tabella': 'si' },
      t.titolo ? h('figcaption', null, String(t.titolo)) : null,
      h('div', { class: 'sim-tabella-scorre' }, h('table', { class: 'sim-tabella' },
        h('thead', null, h('tr', null, colonne.map((c) => h('th', { scope: 'col' }, c)))),
        h('tbody', null, t.valori.filter(Array.isArray).map((r) => h('tr', null, colonne.map((_, i) => h('td', null, String(r[i] == null ? '' : r[i])))))))));
  }

  function s(tag, attributi, ...figli) {
    const el = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attributi || {})) if (v !== null && v !== undefined) el.setAttribute(k, String(v));
    for (const f of figli.flat(Infinity)) if (f !== null && f !== undefined) el.append(f instanceof Node ? f : document.createTextNode(String(f)));
    return el;
  }

  const LARGO = 560;
  const BORDO = { sx: 56, dx: 18, su: 26, giu: 44 };

  /** L'altezza per una larghezza: 320 sul computer largo, più bassa (non meno di 240) nella finestra stretta. */
  const altezza = (largo) => Math.round(Math.min(320, Math.max(240, largo * 0.6)));

  /** Le linee, i numeri e i segni del grafico, disegnati per una larghezza vera (così i numeri restano leggibili). */
  function figliGrafico(g, sp, serie, largo, alto) {
    const x0 = BORDO.sx;
    const x1 = largo - BORDO.dx;
    const y0 = alto - BORDO.giu;
    const y1 = BORDO.su;
    const Y = (v) => y0 - ((v - sp.y.min) / (sp.y.max - sp.y.min || 1)) * (y0 - y1);
    const figli = [];
    // Righe orizzontali e numeri dell'asse y.
    for (const v of sp.y.valori) {
      figli.push(s('line', { class: 'gr-griglia', x1: x0, x2: x1, y1: Y(v), y2: Y(v) }));
      figli.push(s('text', { class: 'gr-numero', x: x0 - 8, y: Y(v) + 4, 'text-anchor': 'end' }, L.numeroGrafico(v)));
    }
    if (sp.tipo === 'barre') {
      const cat = sp.categorie.length ? sp.categorie : (serie[0] && serie[0].valori || []).map((_, i) => String(i + 1));
      const fascia = (x1 - x0) / Math.max(1, cat.length);
      const largaBarra = Math.min(48, (fascia * 0.7) / Math.max(1, serie.length));
      const zero = Y(Math.max(sp.y.min, Math.min(0, sp.y.max)));
      cat.forEach((c, i) => {
        const centro = x0 + fascia * (i + 0.5);
        figli.push(s('text', { class: 'gr-numero', x: centro, y: y0 + 18, 'text-anchor': 'middle' }, c));
        serie.forEach((se, k) => {
          const v = Number((se.valori || [])[i]);
          if (!Number.isFinite(v)) return;
          const xb = centro - (largaBarra * serie.length) / 2 + k * largaBarra;
          const yv = Y(v);
          figli.push(s('rect', { class: 'gr-barra gr-serie-' + (k % 4), x: xb + 1, y: Math.min(yv, zero), width: Math.max(1, largaBarra - 2), height: Math.max(1, Math.abs(zero - yv)), rx: 3 }));
        });
      });
      figli.push(s('line', { class: 'gr-asse', x1: x0, x2: x1, y1: zero, y2: zero }));
    } else {
      const X = (v) => x0 + ((v - sp.x.min) / (sp.x.max - sp.x.min || 1)) * (x1 - x0);
      for (const v of sp.x.valori) {
        figli.push(s('line', { class: 'gr-griglia', x1: X(v), x2: X(v), y1: y0, y2: y1 }));
        figli.push(s('text', { class: 'gr-numero', x: X(v), y: y0 + 18, 'text-anchor': 'middle' }, L.numeroGrafico(v)));
      }
      const asseY = sp.x.min <= 0 && sp.x.max >= 0 ? X(0) : x0;
      const asseX = sp.y.min <= 0 && sp.y.max >= 0 ? Y(0) : y0;
      figli.push(s('line', { class: 'gr-asse', x1: x0, x2: x1, y1: asseX, y2: asseX }));
      figli.push(s('line', { class: 'gr-asse', x1: asseY, x2: asseY, y1: y0, y2: y1 }));
      serie.forEach((se, k) => {
        const punti = (se.punti || []).filter((p) => Array.isArray(p) && Number.isFinite(Number(p[0])) && Number.isFinite(Number(p[1])))
          .map((p) => [X(Number(p[0])), Y(Number(p[1]))]);
        if (!punti.length) return;
        const classe = 'gr-serie-' + (k % 4);
        if (sp.tipo === 'linee') figli.push(s('polyline', { class: 'gr-linea ' + classe, points: punti.map((p) => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' ') }));
        // Con tante coordinate (una funzione) i pallini non servono: si vede la linea.
        if (sp.tipo === 'punti' || punti.length <= 12) for (const p of punti) figli.push(s('circle', { class: 'gr-punto ' + classe, cx: p[0].toFixed(1), cy: p[1].toFixed(1), r: sp.tipo === 'punti' ? 4.5 : 3.5 }));
      });
    }
    if (g.asse_x) figli.push(s('text', { class: 'gr-nome-asse', x: x1, y: alto - 6, 'text-anchor': 'end' }, String(g.asse_x)));
    if (g.asse_y) figli.push(s('text', { class: 'gr-nome-asse', x: x0 - 8, y: 14, 'text-anchor': 'start' }, String(g.asse_y)));
    return figli;
  }

  /**
   * Il grafico SVG (§21.1): griglia alle tacche, assi per lo 0 se è dentro l'intervallo (se no sul bordo),
   * numeri all'italiana, punti, linee o barre. I colori vengono dal tema e dalla materia (classi in stile.css).
   * Si ridisegna alla larghezza vera del riquadro: rimpicciolito intero, i numeri diventerebbero minuscoli.
   */
  function grafico(g) {
    if (!g || !['punti', 'linee', 'barre'].includes(g.tipo)) return null;
    const sp = L.spazioGrafico(g);
    const serie = (g.serie || []).filter((x) => x && typeof x === 'object');
    const disegno = s('svg', { class: 'grafico', role: 'img', 'aria-label': g.titolo ? String(g.titolo) : 'Grafico' });
    let largo = 0;
    const disegna = (w) => {
      const nuovo = Math.round(Math.max(280, Math.min(LARGO, w)));
      if (Math.abs(nuovo - largo) < 4) return;
      largo = nuovo;
      const alto = altezza(largo);
      disegno.setAttribute('viewBox', '0 0 ' + largo + ' ' + alto);
      disegno.replaceChildren(...figliGrafico(g, sp, serie, largo, alto));
    };
    disegna(LARGO);
    const nomi = serie.map((se, k) => (se.nome ? h('span', { class: 'gr-voce' }, h('i', { class: 'gr-segno gr-fondo-' + (k % 4) }), String(se.nome)) : null)).filter(Boolean);
    const figura = h('figure', { class: 'sim-figura', 'data-grafico': g.tipo },
      g.titolo ? h('figcaption', null, String(g.titolo)) : null,
      disegno,
      nomi.length ? h('div', { class: 'gr-legenda' }, nomi) : null);
    // La larghezza del riquadro non dipende dal disegno (è un blocco a tutta riga): nessun giro senza fine.
    if (typeof ResizeObserver === 'function') {
      new ResizeObserver((voci) => {
        const w = voci[0] && voci[0].contentRect.width;
        if (w > 0) disegna(w);
      }).observe(figura);
    }
    return figura;
  }

  A.formule = { formula, anteprima, barra, tabella, grafico };
})();
