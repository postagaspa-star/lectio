/*
 * Lectio per il computer: le fondamenta dell'interfaccia (docs/aspetto.md, prototipo docs/prototipo/Pc.dc.html).
 *
 * Una finestra sul server di casa: qui non si decide niente sulle lezioni, si mostra quello che il server sa e
 * si mandano i gesti di Andrea. Parla SOLO col programma, attraverso api.js (/locale/* e /server/*).
 * Regole: il testo che arriva dal server entra nella pagina come testo, mai come codice; niente frasi d'aiuto
 * fisse (§11); ogni esito si vede (errori con la frase del server e cosa fare).
 *
 * Questo file prepara lo spazio comune (window.LectioApp); le pagine stanno in pagine.js, la nuova lezione in
 * lezione.js, il lettore in lettore.js, la visita guidata in visita.js, l'avvio in avvio.js.
 */
(function () {
  'use strict';

  const L = window.LectioLogica;
  const ApiBase = window.LectioApi;
  // I tempi si accorciano solo nei test (window.LectioTempi): nel programma valgono questi.
  const T = Object.assign({
    preparazione: 20000, // §11: ogni 20 secondi mentre una lezione è in preparazione
    sincronia: 15000,    // il punto di ascolto sul server ogni ~15 secondi
    torna: 5000,         // "Torna dov'eri"
    copiato: 2000,       // "Copiato" con la spunta
    lettura: 5000,       // materiale che il server sta leggendo
    rete: 30000,         // senza rete: si riprova
    riparte: 3000,       // a finestra nascosta, dopo la risposta si riparte da soli
    avviso: 3500,
  }, window.LectioTempi || {});

  const S = {
    motore: null,          // GET /locale/stato
    impostazioni: null,    // GET /server/api/impostazioni
    materie: null,         // GET /server/api/materie
    spesa: null,           // dalla pagina Oggi o da /api/spesa
    salute: null,          // GET /server/api/salute
    voci: null,
    rotta: { vista: 'oggi' },
    storia: [],            // le rotte visitate, per capire avanti/indietro
    esempio: false,        // modalità esempio (visita guidata o foto): niente rete
    senzaRete: false,
    memoriaDel: null,
    nonRiconosciuto: false,
    velo: [],              // fogli e finestre aperti, l'ultimo sopra
    giro: 0,               // il disegno della pagina in corso (quelli vecchi si buttano)
    trascina: 0,
    dom: {},
  };

  const A = window.LectioApp = { L, T, S, pagine: {} };

  // --- Le chiamate ----------------------------------------------------------------------------

  /** Come api.js, più due attenzioni: senza rete (pillola in alto) e computer non più riconosciuto. */
  async function dopo(percorso, promessa) {
    const r = await promessa;
    // Solo le chiamate che vanno davvero al server (un audio già scaricato si apre anche senza rete).
    if (percorso.startsWith('/server/') || percorso.startsWith('/locale/carica')) {
      const prima = S.senzaRete;
      if (r.rete) S.senzaRete = true;
      else if (r.ok || (r.stato >= 400 && r.stato < 500)) S.senzaRete = false;
      if (r.ok && r.memoria) S.senzaRete = true;
      if (S.prova && S.forzaSenzaRete) S.senzaRete = true; // solo la foto 'senza rete'
      if (prima !== S.senzaRete) {
        A.disegnaStato();
        if (!S.senzaRete && A.dopoRitornoRete) A.dopoRitornoRete();
      }
      if (r.stato === 401 && r.dati && (r.dati.codice === 'non_autorizzato' || r.dati.codice === 'errore' || r.dati.codice === 'non_abbinato')) {
        if (!S.nonRiconosciuto) {
          S.nonRiconosciuto = true;
          A.disegnaStato();
        }
      }
    }
    return r;
  }

  A.api = {
    get: (p) => dopo(p, ApiBase.get(p)),
    post: (p, c) => dopo(p, ApiBase.post(p, c)),
    put: (p, c) => dopo(p, ApiBase.put(p, c)),
    patch: (p, c) => dopo(p, ApiBase.patch(p, c)),
    elimina: (p, c) => dopo(p, ApiBase.elimina(p, c)),
    carica: (byte, campi) => dopo('/locale/carica', ApiBase.carica(byte, campi)),
  };

  // --- Costruire la pagina senza HTML da stringhe ------------------------------------------------

  function h(tag, attributi, ...figli) {
    const el = document.createElement(tag);
    if (attributi) {
      for (const [nome, valore] of Object.entries(attributi)) {
        if (valore === null || valore === undefined || valore === false) continue;
        if (nome === 'class') el.className = valore;
        else if (nome === 'stile') Object.assign(el.style, valore);
        else if (nome.startsWith('on') && typeof valore === 'function') el.addEventListener(nome.slice(2).toLowerCase(), valore);
        else if (nome === 'value') el.value = valore;
        else if (nome === 'checked') el.checked = !!valore;
        else if (valore === true) el.setAttribute(nome, '');
        else el.setAttribute(nome, String(valore));
      }
    }
    for (const figlio of figli.flat(Infinity)) {
      if (figlio === null || figlio === undefined || figlio === false) continue;
      el.append(figlio instanceof Node ? figlio : document.createTextNode(String(figlio)));
    }
    return el;
  }

  function svuota(el, ...figli) {
    el.replaceChildren();
    for (const f of figli.flat(Infinity)) if (f !== null && f !== undefined && f !== false) el.append(f instanceof Node ? f : document.createTextNode(String(f)));
    return el;
  }

  const SVG = 'http://www.w3.org/2000/svg';
  const ICONE = {
    oggi: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
    materie: 'M4 5a2 2 0 0 1 2-2h12v16H6a2 2 0 0 0-2 2z M4 21V5 M8 7h6',
    lezioni: 'M4 14v-2a8 8 0 0 1 16 0v2 M5 14h1a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z M18 14h1a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z',
    gemini: 'M10 8h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z M16 8V5a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h2',
    impostazioni: 'M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6 M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
    indietro: 'M15 18l-6-6 6-6',
    freccia: 'M9 18l6-6-6-6',
    giu: 'M6 9l6 6 6-6',
    piu: 'M12 5v14M5 12h14',
    spunta: 'M5 12l5 5L20 7',
    scarica: 'M12 3v12 M7 10l5 5 5-5 M5 21h14',
    carica: 'M12 15V3 M7 8l5-5 5 5 M5 21h14',
    file: 'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6',
    verifica: 'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6 M9 15l2 2 4-4',
    interrogazione: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
    appunti: 'M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z',
    libro: 'M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z',
    foto: 'M3 8a2 2 0 0 1 2-2h2l2-3h6l2 3h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z M12 9a4 4 0 1 0 0 8a4 4 0 1 0 0-8',
    altro: 'M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z M14 3v6h6',
    puntini: 'M5 12h.01 M12 12h.01 M19 12h.01',
    chiudi: 'M6 6l12 12 M18 6L6 18',
    testo: 'M5 6.5h14 M5 11h14 M5 15.5h9',
    mouse: 'M12 3a6 6 0 0 1 6 6v6a6 6 0 0 1-12 0V9a6 6 0 0 1 6-6z M12 7v4',
    esterno: 'M14 4h6v6 M20 4l-9 9 M19 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5',
    rete: 'M2 2l20 20 M8.5 16.5a5 5 0 0 1 7 0 M5 12.5a10 10 0 0 1 5.2-2.8 M19 12.5a10 10 0 0 0-2.3-1.7 M12 20h.01',
    calendario: 'M6.5 5h11a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3h-11a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z M3.5 10h17 M8 3v4 M16 3v4',
    ripeti: 'M3 12a9 9 0 1 0 3-6.7 M3 4v5h5',
    cestino: 'M4 7h16 M10 11v6 M14 11v6 M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12 M9 7V4h6v3',
  };
  const PIENE = {
    play: 'M7 4.5v15a1 1 0 0 0 1.5.9l12-7.5a1 1 0 0 0 0-1.7l-12-7.5A1 1 0 0 0 7 4.5z',
    pausa: 'M7 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M14 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z',
  };

  function icona(nome, classe) {
    const s = document.createElementNS(SVG, 'svg');
    const piena = PIENE[nome] != null;
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('class', 'icona' + (piena ? ' piena' : '') + (classe ? ' ' + classe : ''));
    s.setAttribute('aria-hidden', 'true');
    const p = document.createElementNS(SVG, 'path');
    p.setAttribute('d', piena ? PIENE[nome] : (ICONE[nome] || ICONE.file));
    if (nome === 'puntini') p.setAttribute('stroke-width', '3');
    s.append(p);
    return s;
  }

  // --- Pezzi ricorrenti -------------------------------------------------------------------------

  const simbolo = (grande) => h('span', { class: 'simbolo' + (grande ? ' grande' : ''), 'aria-hidden': 'true' }, h('i'), h('i'), h('i'));
  const classeMateria = (colore) => 'm-' + (L.COLORI.includes(colore) ? colore : 'ambra');
  const piastrella = (colore) => h('span', { class: 'piastrella ' + classeMateria(colore), 'aria-hidden': 'true' }, h('i'), h('i'), h('i'));
  const pallino = (colore, grande) => h('span', { class: 'pallino ' + classeMateria(colore) + (grande ? ' grande' : ''), 'aria-hidden': 'true' });

  function pillolaMateria(m, onclick, extra) {
    if (!m) return null;
    const attr = Object.assign({ class: 'pillola pillola-materia ' + classeMateria(m.colore) }, extra || {});
    if (onclick) {
      attr.type = 'button';
      attr.onclick = onclick;
    }
    return h(onclick ? 'button' : 'span', attr, pallino(m.colore), m.nome);
  }

  function barra(percentuale, classe) {
    const riemp = h('span');
    const b = h('div', { class: 'barra' + (classe ? ' ' + classe : ''), role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-valuenow': String(Math.round(percentuale || 0)) }, riemp);
    // La barra si riempie (§13): parte vuota e si allarga al giro dopo.
    requestAnimationFrame(() => { riemp.style.width = Math.max(0, Math.min(100, percentuale || 0)) + '%'; });
    return b;
  }

  /** Un numero che conta fino al valore (costo, centratura), in 400 ms; subito se si chiedono meno animazioni. */
  function contaNumero(el, valore, formato) {
    const fmt = formato || ((x) => String(Math.round(x)));
    const fine = Number(valore) || 0;
    if (!conMovimenti() || A.S.esempio && A.S.prova) { el.textContent = fmt(fine); return el; }
    const inizio = performance.now();
    const da = Number(el.dataset.valore || 0);
    el.dataset.valore = String(fine);
    const passo = (t) => {
      const p = Math.min(1, (t - inizio) / 400);
      const x = da + (fine - da) * (1 - Math.pow(1 - p, 3));
      el.textContent = fmt(p >= 1 ? fine : x);
      if (p < 1 && el.isConnected) requestAnimationFrame(passo);
    };
    el.textContent = fmt(da);
    requestAnimationFrame(passo);
    return el;
  }

  function conMovimenti() {
    return !(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  function messaggio(tipo, testo, azioni) {
    return h('div', { class: 'messaggio ' + tipo, role: tipo === 'errore' ? 'alert' : 'status' },
      h('div', null, h('span', null, testo), azioni && azioni.length ? h('div', { class: 'azioni' }, azioni) : null));
  }

  /** Un avviso breve in basso al centro. */
  function avviso(testo) {
    const box = document.getElementById('avvisi');
    if (!box) return;
    const el = h('div', { class: 'avviso-breve' }, testo);
    box.append(el);
    setTimeout(() => el.remove(), T.avviso);
  }

  function segnaposto(n) {
    return h('div', { class: 'scheda', 'aria-busy': 'true' }, Array.from({ length: n || 3 }, (_, i) => h('div', { class: 'segnaposto riga-finta', stile: { width: (70 - i * 15) + '%' } })));
  }

  /** L'errore di una pagina: la frase del server e Riprova. */
  function erroreConRiprova(r, riprova) {
    return messaggio('errore', (r && r.errore) || ApiBase.FRASE_RETE, riprova ? [h('button', { type: 'button', class: 'pulsante bianco', onclick: riprova }, 'Riprova')] : null);
  }

  function materiaDi(id) {
    return (S.materie || []).find((m) => m.id === Number(id)) || null;
  }

  function staScrivendo() {
    const a = document.activeElement;
    return !!a && (a.tagName === 'TEXTAREA' || (a.tagName === 'INPUT' && !['checkbox', 'radio', 'button'].includes(a.type)) || a.isContentEditable);
  }

  Object.assign(A, { h, svuota, icona, simbolo, classeMateria, piastrella, pallino, pillolaMateria, barra, contaNumero, conMovimenti, messaggio, avviso, segnaposto, erroreConRiprova, materiaDi, staScrivendo });

  // --- Dati comuni ----------------------------------------------------------------------------

  A.caricaMaterie = async function () {
    const r = await A.api.get('/server/api/materie');
    if (r.ok && Array.isArray(r.dati)) {
      S.materie = r.dati;
      A.disegnaMenu();
    }
    return r;
  };

  A.caricaImpostazioni = async function () {
    const r = await A.api.get('/server/api/impostazioni');
    if (r.ok && r.dati) S.impostazioni = r.dati;
    return r;
  };

  A.caricaSpesa = async function () {
    const r = await A.api.get('/server/api/spesa');
    if (r.ok && r.dati) {
      S.spesa = r.dati;
      A.disegnaMenu();
    }
    return r;
  };

  A.caricaSalute = async function () {
    const r = await A.api.get('/server/api/salute');
    if (r.ok && r.dati) {
      S.salute = r.dati;
      A.disegnaStato();
    }
    return r;
  };

  A.modalitaProva = () => !!(S.salute && S.salute.modalita_prova);

  // --- Struttura: menu laterale, pillole in alto, contenuto, barra del lettore --------------------

  A.disegnaStruttura = function () {
    const app = document.getElementById('app');
    app.removeAttribute('aria-busy');
    S.dom.menu = h('nav', { class: 'menu', 'aria-label': 'Menu' });
    S.dom.stato = h('div', { class: 'fascia-stato' });
    S.dom.scorre = h('div', { class: 'scorre', id: 'contenuto' });
    S.dom.barraLettore = h('div', { class: 'contenitore-barra' });
    svuota(app, S.dom.menu, h('main', { class: 'principale' }, S.dom.stato, S.dom.scorre, S.dom.barraLettore));
    A.disegnaMenu();
    A.disegnaStato();
  };

  A.disegnaMenu = function () {
    const menu = S.dom.menu;
    if (!menu) return;
    const v = S.rotta.vista;
    const attiva = (nome) => (nome === v || (nome === 'materie' && (v === 'materia' || v === 'scheda')) || (nome === 'lezioni' && (v === 'lettore' || v === 'lezione')) || (nome === 'oggi' && v === 'racconta'));
    const voce = (nome, etichetta, icona_) => h('button', {
      type: 'button', class: 'voce-menu' + (attiva(nome) ? ' attiva' : ''), 'data-vista': nome, 'aria-current': attiva(nome) ? 'page' : null,
      onclick: () => A.vai('#' + nome),
    }, icona(icona_), etichetta);
    const materie = (S.materie || []).map((m) => h('button', {
      type: 'button', class: 'voce-materia ' + classeMateria(m.colore) + ((v === 'materia' || v === 'scheda') && S.rotta.id === m.id ? ' attiva' : ''),
      'data-materia': String(m.id), onclick: () => A.vai('#materia/' + m.id),
    }, pallino(m.colore), h('span', { class: 'n' }, m.nome)));
    const spesa = S.spesa;
    const inAvviso = L.spesaInAvviso(spesa);
    svuota(menu,
      h('div', { class: 'marchio' }, simbolo(), h('span', { class: 'nome' }, 'Lectio')),
      voce('oggi', 'Oggi', 'oggi'),
      voce('materie', 'Materie', 'materie'),
      h('div', { class: 'sotto-menu' }, materie,
        h('button', { type: 'button', class: 'voce-materia nuova', 'data-azione': 'nuova-materia-menu', onclick: () => A.apriMateria(null) }, icona('piu'), h('span', { class: 'n' }, 'Nuova materia'))),
      voce('lezioni', 'Lezioni', 'lezioni'),
      voce('gemini', 'Per Gemini', 'gemini'),
      h('div', { class: 'spazio' }),
      spesa ? h('button', { type: 'button', class: 'spesa-menu' + (inAvviso ? ' avviso' : ''), onclick: () => A.vai('#impostazioni'), 'aria-label': 'Spesa del mese' },
        h('span', { class: 'riga-spesa' }, 'Questo mese ' + L.euro(spesa.speso_eur) + ' di ' + L.euroTondo(spesa.tetto_eur)),
        barra(L.percentuale(spesa.speso_eur, spesa.tetto_eur), 'media'),
        inAvviso ? h('span', { class: 'riga-spesa' }, L.siAzzera(spesa.si_azzera_il)) : null) : null,
      h('button', {
        type: 'button', class: 'voce-menu basso' + (v === 'impostazioni' ? ' attiva' : ''), 'data-vista': 'impostazioni',
        onclick: () => A.vai('#impostazioni'),
      }, icona('impostazioni'), 'Impostazioni'));
  };

  /** Le pillole in alto: senza rete, esempio, prova, computer non riconosciuto. */
  A.disegnaStato = function () {
    const box = S.dom.stato;
    if (!box) return;
    const pillole = [];
    if (S.esempio && !S.prova) pillole.push(h('span', { class: 'pillola stato esempio', 'data-stato': 'esempio' }, 'Esempio'));
    if (S.senzaRete && (!S.esempio || S.prova)) pillole.push(h('span', { class: 'pillola stato rete', 'data-stato': 'rete' }, icona('rete'), 'Senza rete: vedi solo le lezioni scaricate'));
    if (A.modalitaProva() && !S.esempio) {
      pillole.push(h('button', { type: 'button', class: 'pillola stato prova', 'data-stato': 'prova', onclick: spiegaProva }, 'Prova'));
    }
    if (S.nonRiconosciuto && !S.esempio) {
      pillole.push(h('button', { type: 'button', class: 'pillola stato rete', 'data-stato': 'non-riconosciuto', onclick: () => A.ricollega() }, 'Il server non riconosce più questo computer: collegalo di nuovo'));
    }
    svuota(box, pillole);
  };

  function spiegaProva() {
    A.apriFoglio({
      titolo: 'Modalità prova',
      corpo: (c) => svuota(c,
        h('p', { class: 'forte' }, 'Voce di prova, robotica: serve la chiave Mistral per quella vera.'),
        h('p', { class: 'secondario' }, 'Finché il server di casa è in prova, le lezioni sono finte e non costano niente.')),
    });
  }

  A.ricollega = async function () {
    if (!(await A.conferma({ titolo: 'Collegare di nuovo questo computer?', testo: 'Serve un codice nuovo dal server di casa. Le lezioni scaricate su questo computer si tolgono.', si: 'Collega di nuovo' }))) return;
    await A.scollegaComputer();
  };

  /**
   * Scollega: prima si chiude il lettore salvando il punto (così il file audio non resta aperto e si può togliere),
   * poi il programma dimentica collegamento e lezioni scaricate; qui si svuotano punti e risposte in coda.
   */
  A.scollegaComputer = async function () {
    if (A.lettore && A.lettore.id && A.chiudiLettore) await A.chiudiLettore(true);
    const r = await ApiBase.post('/locale/scollega', {});
    if (A.svuotaMemoriaLocale) A.svuotaMemoriaLocale();
    if (r.dati && r.dati.avviso) A.avviso(r.dati.avviso);
    S.nonRiconosciuto = false;
    if (A.avvia) A.avvia();
    return r;
  };

  // --- Navigazione -----------------------------------------------------------------------------

  A.vai = function (hash) {
    if (location.hash === hash) {
      A.disegna();
      return;
    }
    location.hash = hash;
  };

  A.indietro = function () {
    if (S.storia.length > 1) history.back();
    else A.vai('#oggi');
  };

  A.disegna = function () {
    const rotta = L.leggiRotta(location.hash);
    const chiave = location.hash || '#oggi';
    let verso = 'avanti';
    const i = S.storia.lastIndexOf(chiave);
    if (i >= 0 && i === S.storia.length - 2) {
      verso = 'indietro';
      S.storia.pop();
    } else if (S.storia[S.storia.length - 1] !== chiave) {
      S.storia.push(chiave);
      if (S.storia.length > 50) S.storia.shift();
    }
    S.rotta = rotta;
    clearTimeout(S.timerPagina);
    S.aggiornaPagina = null;
    A.chiudiTuttiIVeli();
    A.disegnaMenu();
    const giro = ++S.giro;
    const box = S.dom.scorre;
    if (!box) return;
    const pagina = h('div', { class: verso === 'indietro' ? 'entra-indietro' : 'entra' });
    svuota(box, pagina);
    box.scrollTop = 0;
    const disegnatore = A.pagine[rotta.vista] || A.pagine.oggi;
    try {
      const esito = disegnatore(pagina, rotta, () => giro === S.giro);
      if (esito && typeof esito.catch === 'function') esito.catch((e) => console.error(e));
    } catch (e) {
      console.error(e);
      svuota(pagina, h('div', { class: 'pagina' }, messaggio('errore', 'Questa pagina non si è aperta. Riprova.')));
    }
    if (A.disegnaBarraLettore) A.disegnaBarraLettore();
    if (A.visitaDopoDisegno) A.visitaDopoDisegno();
  };

  window.addEventListener('hashchange', () => { if (S.dom.scorre) A.disegna(); });

  // --- Fogli dal basso e finestre al centro -----------------------------------------------------

  function apriVelo(classeVelo, contenuto, opzioni) {
    const o = opzioni || {};
    const velo = h('div', { class: 'velo' + (classeVelo ? ' ' + classeVelo : '') });
    const ctl = {
      el: velo,
      chiuso: false,
      chiudi(senzaAnimazione) {
        if (ctl.chiuso) return;
        ctl.chiuso = true;
        S.velo = S.velo.filter((x) => x !== ctl);
        if (o.suChiusura) o.suChiusura();
        if (senzaAnimazione || !conMovimenti()) { velo.remove(); return; }
        velo.classList.add('chiude');
        setTimeout(() => velo.remove(), 200);
      },
    };
    velo.addEventListener('mousedown', (e) => { if (e.target === velo && o.chiudibile !== false) ctl.chiudi(); });
    velo.append(contenuto);
    document.body.append(velo);
    S.velo.push(ctl);
    return ctl;
  }

  /** Un foglio che sale dal basso (§13): si chiude col velo, con Esc o trascinando giù la maniglia. */
  A.apriFoglio = function (opzioni) {
    const o = opzioni || {};
    const corpo = h('div', { class: 'corpo-foglio', stile: { display: 'flex', flexDirection: 'column', gap: '14px' } });
    const maniglia = h('div', { class: 'maniglia-area', 'aria-hidden': 'true' }, h('div', { class: 'maniglia' }));
    const titolo = h('h2', { id: 'titolo-foglio-' + Date.now() }, o.titolo || '');
    const foglio = h('div', { class: 'foglio' + (o.classe ? ' ' + o.classe : ''), role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': titolo.id },
      maniglia,
      h('div', { class: 'testa-foglio' }, titolo, h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => ctl.chiudi() }, icona('chiudi'))),
      corpo);
    const ctl = apriVelo('', foglio, o);
    ctl.corpo = corpo;
    ctl.foglio = foglio;
    ctl.titolo = (t) => { titolo.textContent = t; };
    // Trascinando giù la maniglia il foglio si chiude.
    let da = null;
    maniglia.addEventListener('pointerdown', (e) => { da = e.clientY; maniglia.setPointerCapture(e.pointerId); foglio.style.transition = 'none'; });
    maniglia.addEventListener('pointermove', (e) => { if (da != null) foglio.style.transform = 'translateY(' + Math.max(0, e.clientY - da) + 'px)'; });
    const rilascia = (e) => {
      if (da == null) return;
      const dy = e.clientY - da;
      da = null;
      foglio.style.transition = '';
      foglio.style.transform = '';
      if (dy > 80) ctl.chiudi();
    };
    maniglia.addEventListener('pointerup', rilascia);
    maniglia.addEventListener('pointercancel', rilascia);
    if (o.corpo) o.corpo(corpo, ctl);
    const primo = foglio.querySelector('[data-fuoco]') || foglio.querySelector('input, textarea, button.riga-gemini, button');
    if (primo && o.fuoco !== false) setTimeout(() => primo.focus(), 30);
    return ctl;
  };

  /** Una finestra al centro (Nuova lezione, Nuova materia, conferme). */
  A.apriFinestra = function (opzioni) {
    const o = opzioni || {};
    const finestra = h('div', { class: 'finestra' + (o.stretta ? ' stretta' : '') + (o.classe ? ' ' + o.classe : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': o.etichetta || 'Finestra' });
    const ctl = apriVelo('centro', finestra, o);
    ctl.finestra = finestra;
    if (o.disegna) o.disegna(finestra, ctl);
    return ctl;
  };

  A.chiudiTuttiIVeli = function () {
    for (const v of S.velo.slice()) v.chiudi(true);
  };

  /** Una conferma: Promise<true/false>. Il pulsante del sì dice cosa fa ("Elimina la lezione"). */
  A.conferma = function (o) {
    return new Promise((fine) => {
      let esito = false;
      A.apriFinestra({
        stretta: true,
        etichetta: o.titolo,
        suChiusura: () => fine(esito),
        disegna: (f, ctl) => svuota(f,
          h('div', { class: 'corpo bianco' }, h('h2', { class: 'titolo-sezione' }, o.titolo), o.testo ? h('p', { class: 'secondario', stile: { fontSize: '15px' } }, o.testo) : null),
          h('div', { class: 'piede' }, h('div', { class: 'azioni', stile: { justifyContent: 'flex-end' } },
            h('button', { type: 'button', class: 'pulsante grigio', 'data-fuoco': true, onclick: () => ctl.chiudi() }, o.no || 'Annulla'),
            h('button', { type: 'button', class: o.pericolo ? 'pulsante pericolo-pieno' : 'pulsante pieno medio', 'data-azione': 'conferma', onclick: () => { esito = true; ctl.chiudi(); } }, o.si || 'Va bene')))),
      });
      setTimeout(() => { const b = document.querySelector('.velo:last-of-type [data-fuoco]'); if (b) b.focus(); }, 30);
    });
  };

  // --- Tastiera ---------------------------------------------------------------------------------

  document.addEventListener('keydown', (e) => {
    if (S.visita && A.tastoVisita && A.tastoVisita(e)) return;
    if (e.key === 'Escape' && S.velo.length) {
      const sopra = S.velo[S.velo.length - 1];
      e.preventDefault();
      sopra.chiudi();
      return;
    }
    if (A.tastoLettore) A.tastoLettore(e);
  });

  // --- Trascinare i file (§2: nella pagina di una materia e in Per Gemini) -----------------------

  const conFile = (e) => !!(e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files'));
  const paginaConFile = () => ['materia', 'gemini', 'scheda'].includes(S.rotta.vista) && !S.velo.some((v) => !v.accettaFile);
  function accendi(si) {
    document.querySelectorAll('.zona-trascina').forEach((z) => z.classList.toggle('accesa', !!si));
  }
  window.addEventListener('dragenter', (e) => {
    if (!conFile(e)) return;
    e.preventDefault();
    S.trascina++;
    accendi(paginaConFile());
  });
  window.addEventListener('dragover', (e) => {
    if (!conFile(e)) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = paginaConFile() ? 'copy' : 'none';
  });
  window.addEventListener('dragleave', (e) => {
    if (!conFile(e)) return;
    S.trascina = Math.max(0, S.trascina - 1);
    if (S.trascina === 0) accendi(false);
  });
  window.addEventListener('drop', (e) => {
    // Mai lasciare che un file trascinato apra una pagina nuova nella finestra.
    e.preventDefault();
    S.trascina = 0;
    accendi(false);
    const file = e.dataTransfer && e.dataTransfer.files ? Array.from(e.dataTransfer.files) : [];
    if (!file.length) return;
    if (!paginaConFile()) {
      avviso('Trascina i file nella pagina di una materia o in Per Gemini.');
      return;
    }
    if (A.suFileTrascinati) A.suFileTrascinati(file);
  });
})();
