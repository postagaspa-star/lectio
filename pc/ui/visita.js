/*
 * Lectio per il computer: la visita guidata (docs/aspetto.md §12) dentro le schermate vere.
 *
 * Modalità esempio: dati finti in memoria (esempio.js), NESSUNA chiamata al server, pillola "Esempio".
 * Un riflettore con il buco arrotondato che scivola sul pezzo giusto (velo nero 55%, al buio 65%, anello
 * accento che pulsa piano), un fumetto con "3 di 8", titolo corto, una frase; Indietro sempre, Avanti solo nei
 * passi da leggere, "Salta la visita" sempre. Nei passi pratici si va avanti solo facendo l'azione vera: il
 * resto della pagina non si può toccare, e un tasto disegnato mostra cosa premere.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { S, h, svuota, icona } = A;
  const ApiBase = window.LectioApi;

  const STORIA = 1;

  async function aspetta(condizione, ms) {
    const fine = Date.now() + (ms || 3000);
    while (Date.now() < fine) {
      const x = condizione();
      if (x) return x;
      await new Promise((r) => setTimeout(r, 40));
    }
    return null;
  }

  async function vaiA(hash, selettore) {
    if (location.hash !== hash) A.vai(hash);
    if (selettore) await aspetta(() => document.querySelector(selettore), 3000);
  }

  function foglioAperto() {
    return S.velo.find((v) => v.riceviFile && v.foglio);
  }

  const PASSI = [
    {
      titolo: 'La prossima verifica', frase: 'Qui c\'è sempre la prossima verifica e la sua lezione.', leggi: true,
      bersaglio: ['[data-visita="prossima"]'],
      prepara: async () => { A.chiudiTuttiIVeli(); A.chiudiLettore(false); await vaiA('#oggi', '[data-visita="prossima"]'); },
    },
    {
      titolo: 'Apri la materia', frase: 'Fai clic su Storia.', evento: 'apri-materia', tasto: 'clic',
      bersaglio: ['[data-visita="pillola-prossima"]'],
      prepara: async () => { A.chiudiTuttiIVeli(); await vaiA('#oggi', '[data-visita="pillola-prossima"]'); },
    },
    {
      titolo: 'Cosa chiede il prof', frase: 'L\'ho scritta io dalle tue verifiche. Puoi correggerla.', leggi: true,
      bersaglio: ['[data-visita="scheda-prof"]'],
      prepara: async () => { A.chiudiTuttiIVeli(); await vaiA('#materia/' + STORIA, '[data-visita="scheda-prof"] .pillola'); },
    },
    {
      titolo: 'Aggiungi una verifica', frase: 'Fai clic su Aggiungi.', evento: 'foglio-aggiungi', tasto: 'clic',
      bersaglio: ['[data-visita="aggiungi"]'],
      prepara: async () => { A.chiudiTuttiIVeli(); await vaiA('#materia/' + STORIA, '[data-visita="aggiungi"]'); },
    },
    {
      titolo: 'Testo per Gemini', frase: 'Copialo, dallo a Gemini con la foto, poi trascina qui il file.', leggi: true,
      bersaglio: ['[data-visita="gemini-riga"]', '[data-visita="trascina-foglio"]'],
      prepara: async () => {
        if (!foglioAperto()) {
          await vaiA('#materia/' + STORIA, '[data-visita="aggiungi"]');
          const m = A.materiaDi(STORIA);
          if (m) A.apriAggiungi(m);
        }
        await aspetta(() => document.querySelector('[data-visita="gemini-riga"]'), 2000);
        await new Promise((r) => setTimeout(r, A.conMovimenti() ? 320 : 0));
      },
    },
    {
      titolo: 'Nuova lezione', frase: 'Fai clic su Nuova lezione.', evento: 'nuova-lezione', tasto: 'clic',
      bersaglio: ['[data-visita="nuova-lezione"]'],
      prepara: async () => { A.chiudiTuttiIVeli(); await vaiA('#materia/' + STORIA, '[data-visita="nuova-lezione"]'); },
    },
    {
      titolo: 'Durata e costo', frase: 'Scegli quanto dura. Il costo lo vedi prima.', leggi: true,
      bersaglio: ['[data-visita="durata"]', '[data-visita="prepara"]'],
      prepara: async () => {
        if (!S.velo.some((v) => v.tipo === 'nuova-lezione')) {
          A.chiudiTuttiIVeli();
          A.chiudiLettore(false);
          await vaiA('#materia/' + STORIA, '[data-visita="nuova-lezione"]');
          A.nuovaLezione(STORIA);
        }
        await aspetta(() => document.querySelector('[data-visita="prepara"]') && document.querySelector('[data-costo="espressiva"]') && !/…/.test(document.querySelector('[data-costo="espressiva"]').textContent), 3000);
      },
    },
    {
      titolo: 'La domanda', frase: 'Scrivi la tua risposta e premi Invia: te la correggo.', evento: 'invia-risposta', tasto: 'invia',
      bersaglio: ['#testo-domanda', '[data-visita="risposta"]'],
      prepara: async () => {
        A.chiudiTuttiIVeli();
        await vaiA('#lettore/12', '.lettore-pagina');
        await A.lettoreFermoAllaDomanda(12, 32);
        await aspetta(() => document.querySelector('[data-visita="risposta"]'), 3000);
      },
    },
    { fine: true },
  ];
  const DA_CONTARE = PASSI.length - 1;

  // --- Entrare e uscire dalla modalità esempio -----------------------------------------------------

  let salvato = null;

  /** Parte da sola al primo avvio dopo l'abbinamento; si riapre da Impostazioni (e dalla pagina Oggi vuota). */
  A.avviaVisita = async function (primoAvvio) {
    if (S.visita) return;
    // La lezione vera nel lettore: si salva il punto e si chiude (nella visita c'è quella d'esempio).
    if (A.lettore && A.lettore.id && !S.esempio) {
      if (window.lectioPrimaDiChiudere) window.lectioPrimaDiChiudere();
      A.chiudiLettore(false);
    }
    salvato = { materie: S.materie, impostazioni: S.impostazioni, spesa: S.spesa, salute: S.salute, voci: S.voci, senzaRete: S.senzaRete };
    ApiBase.modoEsempio(window.LectioEsempio.crea());
    S.esempio = true;
    S.senzaRete = false;
    S.materie = null;
    S.voci = null;
    S.salute = { modalita_prova: false };
    await Promise.all([A.caricaMaterie(), A.caricaImpostazioni(), A.caricaSpesa()]);
    S.visita = { passo: 0, primoAvvio: !!primoAvvio };
    A.disegnaStato();
    costruisci();
    await vai(0);
  };

  A.chiudiVisita = async function (segna) {
    const v = S.visita;
    if (!v) return;
    clearInterval(v.giro);
    clearTimeout(v.timer);
    if (S.dom.visita) S.dom.visita.remove();
    S.dom.visita = null;
    S.visita = null;
    A.chiudiTuttiIVeli();
    A.chiudiLettore(false);
    ApiBase.modoEsempio(null);
    S.esempio = false;
    if (salvato) Object.assign(S, { impostazioni: salvato.impostazioni, spesa: salvato.spesa, salute: salvato.salute, voci: salvato.voci, senzaRete: salvato.senzaRete });
    S.materie = null;
    salvato = null;
    A.disegnaStato();
    if (segna !== false && !S.prova) ApiBase.post('/locale/tutorial-visto', {}).then((r) => { if (r.ok && S.motore) S.motore.tutorial_visto = true; });
    await A.caricaMaterie();
    if (location.hash === '#oggi') A.disegna();
    else A.vai('#oggi');
  };

  // --- Il velo, il buco, il fumetto ----------------------------------------------------------------

  function costruisci() {
    const v = S.visita;
    const blocchi = ['alto', 'basso', 'sinistra', 'destra'].map((k) => h('div', { class: 'visita-blocco', 'data-blocco': k, onmousedown: (e) => e.preventDefault(), onclick: (e) => e.stopPropagation() }));
    v.blocchi = blocchi;
    v.buco = h('div', { class: 'visita-buco', 'aria-hidden': 'true' });
    const SVGNS = 'http://www.w3.org/2000/svg';
    v.velo = document.createElementNS(SVGNS, 'svg');
    v.velo.setAttribute('class', 'visita-velo');
    v.velo.setAttribute('aria-hidden', 'true');
    v.tracciato = document.createElementNS(SVGNS, 'path');
    v.velo.append(v.tracciato);
    v.tappo = h('div', { class: 'visita-tappo', 'aria-hidden': 'true', onclick: (e) => e.stopPropagation() });
    v.fumetto = h('div', { class: 'visita-fumetto', role: 'dialog', 'aria-modal': 'false', 'aria-live': 'polite', 'aria-label': 'Come funziona Lectio' });
    v.tasto = h('div', { class: 'tasto-disegnato', 'aria-hidden': 'true' });
    S.dom.visita = h('div', { class: 'visita' }, v.velo, blocchi, v.buco, v.tappo, v.tasto, v.fumetto);
    document.body.append(S.dom.visita);
    // Il buco segue il pezzo anche se la pagina si muove (scorrimento, finestra ridimensionata, fogli che salgono).
    v.giro = setInterval(posiziona, 120);
    window.addEventListener('resize', posiziona);
  }

  async function vai(n) {
    const v = S.visita;
    if (!v) return;
    clearTimeout(v.timer);
    v.passo = Math.max(0, Math.min(PASSI.length - 1, n));
    v.fatto = false;
    const p = PASSI[v.passo];
    S.dom.visita.classList.toggle('fine', !!p.fine);
    disegnaFumetto();
    if (p.fine) {
      A.chiudiTuttiIVeli();
      A.chiudiLettore(false);
      posiziona();
      return;
    }
    v.preparando = true;
    try { await p.prepara(); } catch (e) { /* si mostra lo stesso */ }
    if (S.visita !== v) return;
    v.preparando = false;
    posiziona();
    disegnaFumetto();
  }

  function disegnaFumetto() {
    const v = S.visita;
    const p = PASSI[v.passo];
    const f = v.fumetto;
    f.classList.toggle('centrato', !!p.fine);
    if (p.fine) {
      svuota(f,
        h('span', { class: 'titolo' }, 'Ecco fatto'),
        h('button', { type: 'button', class: 'pulsante pieno largo', 'data-visita-azione': 'inizia', onclick: () => A.chiudiVisita(true) }, 'Inizia'));
      setTimeout(() => { const b = f.querySelector('button'); if (b) b.focus(); }, 30);
      v.tasto.style.display = 'none';
      return;
    }
    svuota(f,
      h('span', { class: 'passo-n' }, (v.passo + 1) + ' di ' + DA_CONTARE),
      h('span', { class: 'titolo' }, p.titolo),
      h('p', { class: 'frase' }, p.frase),
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-visita-azione': 'salta', onclick: () => A.chiudiVisita(true) }, 'Salta la visita'),
        h('div', { class: 'destra' },
          v.passo > 0 ? h('button', { type: 'button', class: 'pulsante grigio', 'data-visita-azione': 'indietro', onclick: () => vai(v.passo - 1) }, 'Indietro') : null,
          p.leggi ? h('button', { type: 'button', class: 'pulsante pieno medio', 'data-visita-azione': 'avanti', disabled: v.preparando ? true : null, onclick: () => vai(v.passo + 1) }, 'Avanti') : null)));
    if (p.leggi) setTimeout(() => { const b = f.querySelector('[data-visita-azione="avanti"]'); if (b && !A.staScrivendo()) b.focus(); }, 30);
    // Il tasto disegnato (sul PC al posto della mano): il clic del mouse, o Ctrl+Invio per mandare la risposta.
    if (p.tasto === 'clic') {
      svuota(v.tasto, h('span', { class: 'tasto' }, icona('mouse')), 'Clic');
      v.tasto.style.display = '';
    } else if (p.tasto === 'invia') {
      svuota(v.tasto, h('span', { class: 'tasto' }, 'Ctrl'), '+', h('span', { class: 'tasto' }, 'Invio'));
      v.tasto.style.display = '';
    } else {
      v.tasto.style.display = 'none';
    }
  }

  function rettangolo(p) {
    const rr = (p.bersaglio || []).map((s) => document.querySelector(s)).filter(Boolean).map((el) => el.getBoundingClientRect()).filter((r) => r.width > 0 || r.height > 0);
    if (!rr.length) return null;
    const x1 = Math.min(...rr.map((r) => r.left));
    const y1 = Math.min(...rr.map((r) => r.top));
    const x2 = Math.max(...rr.map((r) => r.right));
    const y2 = Math.max(...rr.map((r) => r.bottom));
    const m = 8;
    return { x: x1 - m, y: y1 - m, w: x2 - x1 + 2 * m, h: y2 - y1 + 2 * m };
  }

  const px = (n) => Math.round(n) + 'px';

  /** Il velo con il buco arrotondato: un rettangolo pieno meno un rettangolo coi bordi tondi (pari-dispari). */
  function tracciaVelo(v, W, H, r) {
    let d = 'M0 0H' + W + 'V' + H + 'H0Z';
    if (r && r.w > 0 && r.h > 0) {
      const k = Math.min(18, r.w / 2, r.h / 2);
      const x = r.x;
      const y = r.y;
      const x2 = r.x + r.w;
      const y2 = r.y + r.h;
      d += ' M' + (x + k) + ' ' + y + 'H' + (x2 - k) + 'A' + k + ' ' + k + ' 0 0 1 ' + x2 + ' ' + (y + k) + 'V' + (y2 - k) + 'A' + k + ' ' + k + ' 0 0 1 ' + (x2 - k) + ' ' + y2
        + 'H' + (x + k) + 'A' + k + ' ' + k + ' 0 0 1 ' + x + ' ' + (y2 - k) + 'V' + (y + k) + 'A' + k + ' ' + k + ' 0 0 1 ' + (x + k) + ' ' + y + 'Z';
    }
    v.tracciato.setAttribute('d', d);
    v.dim = W + 'x' + H;
  }

  /** Il buco scivola dal punto di prima a quello nuovo (300 ms), o salta subito con meno animazioni. */
  function muoviBuco(v, W, H, r) {
    const da = v.rettangolo;
    v.rettangolo = r;
    cancelAnimationFrame(v.animazione);
    if (!r || !da || !A.conMovimenti() || document.documentElement.classList.contains('foto')) { tracciaVelo(v, W, H, r); return; }
    if (Math.abs(da.x - r.x) + Math.abs(da.y - r.y) + Math.abs(da.w - r.w) + Math.abs(da.h - r.h) < 1) { tracciaVelo(v, W, H, r); return; }
    const inizio = performance.now();
    const passo = (t) => {
      const p = Math.min(1, (t - inizio) / 300);
      const e = 1 - Math.pow(1 - p, 3);
      const mezzo = { x: da.x + (r.x - da.x) * e, y: da.y + (r.y - da.y) * e, w: da.w + (r.w - da.w) * e, h: da.h + (r.h - da.h) * e };
      tracciaVelo(v, W, H, mezzo);
      if (p < 1) v.animazione = requestAnimationFrame(passo);
    };
    v.animazione = requestAnimationFrame(passo);
  }
  function metti(el, x, y, w, hh) {
    Object.assign(el.style, { left: px(x), top: px(y), width: px(Math.max(0, w)), height: px(Math.max(0, hh)) });
  }

  function posiziona() {
    const v = S.visita;
    if (!v || !S.dom.visita) return;
    const p = PASSI[v.passo];
    const W = window.innerWidth;
    const H = window.innerHeight;
    const r = p.fine || v.preparando ? null : rettangolo(p);
    const [alto, basso, sinistra, destra] = v.blocchi;
    if (!r) {
      // Niente buco: il velo copre tutto (e alla fine si scioglie).
      metti(alto, 0, 0, W, H);
      metti(basso, 0, 0, 0, 0); metti(sinistra, 0, 0, 0, 0); metti(destra, 0, 0, 0, 0);
      metti(v.buco, W / 2, H / 2, 0, 0);
      metti(v.tappo, 0, 0, 0, 0);
      muoviBuco(v, W, H, null);
      if (p.fine) metti(alto, 0, 0, 0, 0);
      if (!p.fine) { v.fumetto.style.left = px((W - 380) / 2); v.fumetto.style.top = px(H / 2 - 80); }
      v.tasto.style.display = 'none';
      return;
    }
    metti(v.buco, r.x, r.y, r.w, r.h);
    if (!v.rettangolo || v.rettangolo.x !== r.x || v.rettangolo.y !== r.y || v.rettangolo.w !== r.w || v.rettangolo.h !== r.h || v.dim !== W + 'x' + H) muoviBuco(v, W, H, r);
    // Fuori dal buco non si clicca niente; dentro sì, ma solo nei passi pratici.
    metti(alto, 0, 0, W, r.y);
    metti(basso, 0, r.y + r.h, W, H - r.y - r.h);
    metti(sinistra, 0, r.y, r.x, r.h);
    metti(destra, r.x + r.w, r.y, W - r.x - r.w, r.h);
    if (p.leggi) metti(v.tappo, r.x, r.y, r.w, r.h);
    else metti(v.tappo, 0, 0, 0, 0);
    // Il fumetto vicino al buco: sotto, sopra, a destra o a sinistra, sempre dentro la finestra.
    const fw = 380;
    const fh = v.fumetto.offsetHeight || 170;
    let fx;
    let fy;
    if (r.y + r.h + 16 + fh <= H - 12) { fy = r.y + r.h + 16; fx = r.x; }
    else if (r.y - 16 - fh >= 12) { fy = r.y - 16 - fh; fx = r.x; }
    else if (r.x + r.w + 16 + fw <= W - 12) { fx = r.x + r.w + 16; fy = r.y; }
    else { fx = r.x - 16 - fw; fy = r.y; }
    fx = Math.max(12, Math.min(W - fw - 12, fx));
    fy = Math.max(12, Math.min(H - fh - 12, fy));
    v.fumetto.style.left = px(fx);
    v.fumetto.style.top = px(fy);
    if (v.tasto.style.display !== 'none') {
      // Il tasto disegnato accanto al buco (a destra se c'è posto), senza coprire il pezzo da cliccare.
      const tw = v.tasto.offsetWidth || 130;
      const fuori = r.x + r.w + 14 + tw <= W - 12;
      v.tasto.style.left = px(fuori ? r.x + r.w + 14 : Math.max(12, r.x + r.w - tw));
      v.tasto.style.top = px(fuori ? r.y + r.h / 2 - 21 : Math.min(H - 54, r.y + r.h + 8));
    }
  }

  // --- Avanzare solo con l'azione giusta -----------------------------------------------------------

  /** Le azioni vere chiamano qui: se è quella che il passo aspetta, si va avanti. */
  A.visitaEvento = function (nome) {
    const v = S.visita;
    if (!v || v.fatto) return;
    const p = PASSI[v.passo];
    if (p.evento !== nome) return;
    v.fatto = true;
    const attesa = nome === 'invia-risposta' ? 1600 : 350;
    v.timer = setTimeout(() => { if (S.visita === v) vai(v.passo + 1); }, attesa);
  };

  A.visitaDopoDisegno = function () {
    if (S.visita) setTimeout(posiziona, 0);
  };

  /** Nella visita: Esc la chiude; il resto dei tasti va dove deve (si scrive nel campo della domanda). */
  A.tastoVisita = function (e) {
    if (e.key === 'Escape') {
      e.preventDefault();
      A.chiudiVisita(true);
      return true;
    }
    return false;
  };

  A.visitaPasso = () => (S.visita ? S.visita.passo : null);
  A.visitaVai = (n) => vai(n);
})();
