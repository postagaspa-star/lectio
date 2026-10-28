/*
 * Lectio per il computer: il lettore (docs/aspetto.md §7, prototipo Lettore.dc.html e Pc.dc.html).
 *
 * - L'audio è sul computer: il programma lo scarica col token e la pagina lo legge da
 *   https://audio.lectio.locale/ (cartella virtuale di WebView2). In modalità esempio un orologio finto.
 * - Fermate alle domande (logica.js, Fermate): pausa alla fine della domanda, scheda che sale con il campo per
 *   scrivere la risposta (Invia, Ctrl+Invio), Salta / Salta comunque, Ripeti la domanda; correzione dal server,
 *   poi la risposta giusta in audio e Avanti.
 * - Mentre si scrive nel campo, barra spaziatrice e tasti del lettore NON comandano l'audio.
 * - Il punto di ascolto va sul server ogni ~15 s, in pausa e alla chiusura (vince il più recente).
 * - Senza rete: punto e risposte restano in coda sul computer e partono appena torna la rete.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  const CHIAVE_POS = 'lectio.pos.';
  const CHIAVE_CODA = 'lectio.coda.risposte';
  const CHIAVE_VELOCITA = 'lectio.velocita';

  function leggi(chiave) {
    try { return JSON.parse(localStorage.getItem(chiave) || 'null'); } catch (e) { return null; }
  }
  function scrivi(chiave, valore) {
    try { localStorage.setItem(chiave, JSON.stringify(valore)); } catch (e) { /* senza memoria del browser va bene lo stesso */ }
  }

  // --- L'orologio finto (modalità esempio e prove) --------------------------------------------------

  /** Un "audio" che avanza da solo: stesse proprietà e stessi eventi che usa il lettore. */
  function AudioFinto(durata) {
    this.duration = durata || 60;
    this.currentTime = 0;
    this.paused = true;
    this.ended = false;
    this.playbackRate = 1;
    this.readyState = 4;
    this.finto = true;
    this._ascolto = {};
    this._timer = null;
  }
  AudioFinto.prototype.addEventListener = function (t, f) { (this._ascolto[t] = this._ascolto[t] || []).push(f); };
  AudioFinto.prototype.removeEventListener = function (t, f) { this._ascolto[t] = (this._ascolto[t] || []).filter((x) => x !== f); };
  AudioFinto.prototype._lancia = function (t) { (this._ascolto[t] || []).slice().forEach((f) => f({ type: t })); };
  AudioFinto.prototype.play = function () {
    if (!this.paused) return Promise.resolve();
    if (this.currentTime >= this.duration) this.currentTime = 0;
    this.paused = false;
    this.ended = false;
    this._lancia('play');
    const passo = 100;
    // A mano (nelle prove): il tempo avanza solo con avanza().
    if (!this.aMano) this._timer = setInterval(() => this.avanza(passo / 1000 * this.playbackRate), passo);
    return Promise.resolve();
  };
  AudioFinto.prototype.pause = function () {
    if (this.paused) return;
    this.paused = true;
    clearInterval(this._timer);
    this._lancia('pause');
  };
  /** Avanza come se suonasse (le prove lo chiamano per andare veloci). */
  AudioFinto.prototype.avanza = function (secondi) {
    this.currentTime = Math.min(this.duration, this.currentTime + secondi);
    this._lancia('timeupdate');
    if (this.currentTime >= this.duration) {
      this.paused = true;
      this.ended = true;
      clearInterval(this._timer);
      this._lancia('pause');
      this._lancia('ended');
    }
  };
  AudioFinto.prototype.load = function () {};
  AudioFinto.prototype.removeAttribute = function () {};
  A.AudioFinto = AudioFinto;

  A.creaAudio = function (url, durata) {
    if (window.LectioCreaAudio) return window.LectioCreaAudio(url, durata);
    if (!url || S.esempio) return new AudioFinto(durata);
    const a = new Audio();
    a.preload = 'auto';
    a.src = url;
    return a;
  };

  // --- Lo stato del lettore ----------------------------------------------------------------------

  const P = {
    lezione: null,      // il dettaglio (capitoli, domande, testo)
    id: null,
    audio: null,
    fermate: null,
    caricando: false,
    percentuale: 0,
    errore: null,
    domanda: null,      // { d, testo, fase: 'scrivi'|'correggo'|'corretta', correzione, conferma }
    bozze: {},          // testo scritto per ogni domanda (resta se si ripete la domanda)
    torna: null,        // { da, timer }
    finita: false,
    testo: false,       // mostra il testo della lezione
    ultimaSinc: 0,
    sincInCorso: null,
    ticker: null,
    ui: {},
  };
  A.lettore = P;

  const durataDi = () => (P.audio && Number.isFinite(P.audio.duration) && P.audio.duration > 0 ? P.audio.duration : (P.lezione && P.lezione.durata_s) || 0);
  const adesso = () => (P.audio ? Number(P.audio.currentTime) || 0 : 0);
  const materia = () => (P.lezione && A.materiaDi(P.lezione.materia_id)) || { nome: '', colore: 'ambra' };
  const risposteScritte = () => !S.impostazioni || S.impostazioni.risposte_scritte !== false;
  const fermateAttive = () => !S.impostazioni || S.impostazioni.fermati_alle_domande !== false;
  const saltabili = () => !!(P.lezione && P.lezione.domande_saltabili);

  function velocitaPreferita() {
    const v = Number(leggi(CHIAVE_VELOCITA));
    if (L.VELOCITA.some((x) => Math.abs(x - v) < 0.01)) return v;
    return (S.impostazioni && Number(S.impostazioni.velocita)) || 1;
  }

  // --- Caricare una lezione ------------------------------------------------------------------------

  /**
   * Apre una lezione nel lettore: dettaglio, audio sul computer, punto di partenza (il più recente tra server e
   * computer). `suona` = parte subito; `apri` = va alla pagina grande; `domandaId` = fermo su quella domanda.
   */
  A.ascolta = function (id, opzioni) {
    const o = opzioni || {};
    if (o.apri && S.rotta.vista !== 'lettore') A.vai('#lettore/' + id);
    if (P.id === id && P.audio && !P.errore && !P.caricando) {
      return (async () => {
        // Già caricata ma ferma: il telefono può averla ascoltata nel frattempo (vince il più recente).
        if (!o.domandaId && P.audio.paused && !P.domanda) await allineaAlServer(id);
        if (P.id !== id || !P.audio) return;
        if (o.domandaId) apriDomandaId(o.domandaId);
        else if (o.suona && P.audio.paused && !P.domanda) suona();
        disegnaTutto();
      })();
    }
    if (P.idInCarico === id && P.caricando && P.promessa) {
      // La stessa lezione si sta già aprendo: si aggiungono solo le richieste (suona, domanda).
      P.inAttesa = Object.assign(P.inAttesa || {}, o);
      return P.promessa;
    }
    P.promessa = carica(id, o);
    return P.promessa;
  };

  async function carica(id, o) {
    const mio = (P.giro = (P.giro || 0) + 1);
    const vivo = () => P.giro === mio;
    P.inAttesa = null;
    const primaSalvata = scarica(true); // il punto della lezione di prima va sul server (prima di "sto caricando")
    P.idInCarico = id;
    P.caricando = true;
    await primaSalvata;
    if (!vivo()) return;
    if (P.audio) { P.audio.pause(); scollega(P.audio); }
    Object.assign(P, { lezione: null, id, audio: null, fermate: null, caricando: true, percentuale: 0, errore: null, domanda: null, finita: false, torna: null });
    disegnaTutto();
    if (!S.impostazioni) await A.caricaImpostazioni();
    if (!S.materie) await A.caricaMaterie();
    const det = await A.dettaglioLezione(id, true);
    if (!vivo()) return;
    if (!det.ok || !det.dati) {
      P.caricando = false;
      P.errore = det.errore || 'Non riesco ad aprire la lezione.';
      disegnaTutto();
      return;
    }
    P.lezione = det.dati;
    disegnaTutto();
    // L'audio sul computer: il programma lo scarica (col token) se non c'è; intanto la percentuale.
    const controllo = setInterval(async () => {
      const r = await A.api.get('/locale/lezioni/' + id + '/scaricamento');
      if (r.ok && r.dati && P.id === id && P.caricando) { P.percentuale = r.dati.percentuale || 0; disegnaTutto(); }
    }, 500);
    const r = await A.api.post('/locale/lezioni/' + id + '/scarica', {});
    clearInterval(controllo);
    if (!vivo()) return;
    if (!r.ok) {
      P.caricando = false;
      P.errore = r.errore;
      disegnaTutto();
      return;
    }
    A.aggiornaMotore();
    const audio = A.creaAudio(r.dati && r.dati.url, P.lezione.durata_s);
    P.audio = audio;
    P.fermate = new L.Fermate(P.lezione.domande || [], { attive: fermateAttive() });
    const locale = S.esempio ? null : leggi(CHIAVE_POS + id);
    const partenza = o.posizione != null ? o.posizione : L.puntoDiPartenza(L.piuRecente(P.lezione.ascolto, locale), P.lezione.durata_s);
    audio.playbackRate = velocitaPreferita();
    collega(audio);
    const pronto = () => {
      if (!vivo()) return;
      const oo = Object.assign({}, o, P.inAttesa || {});
      P.inAttesa = null;
      try { audio.currentTime = partenza; } catch (e) { /* audio non ancora pronto */ }
      P.fermate.salto(partenza);
      P.caricando = false;
      const aperta = !oo.domandaId && fermateAttive() && o.posizione == null ? L.domandaAperta(P.lezione.domande, partenza) : null;
      if (oo.domandaId) apriDomandaId(oo.domandaId);
      else if (aperta) apriDomandaId(aperta.id); // lasciata a metà: si riparte da lì, senza saltarla
      else if (oo.suona) suona();
      disegnaTutto();
    };
    if (audio.readyState >= 1) pronto();
    else {
      const una = () => { audio.removeEventListener('loadedmetadata', una); pronto(); };
      audio.addEventListener('loadedmetadata', una);
      audio.addEventListener('error', () => {
        if (P.audio !== audio) return;
        P.caricando = false;
        P.errore = 'Il file della lezione non si apre. Toglila dal computer e riprova.';
        disegnaTutto();
      });
    }
    sessioneMedia();
  };

  /** "Da ripassare": apre il lettore fermo su quella domanda, per rispondere di nuovo. */
  A.apriDomanda = function (lezioneId, domandaId) {
    A.ascolta(lezioneId, { apri: true, domandaId });
  };

  function apriDomandaId(domandaId) {
    const d = (P.lezione && P.lezione.domande || []).find((x) => x.id === domandaId);
    if (!d || !P.fermate) return;
    if (P.audio && !P.audio.paused) P.audio.pause();
    const t = P.fermate.apri(d);
    try { P.audio.currentTime = t; } catch (e) { /* va bene lo stesso */ }
    P.domanda = { d, testo: P.bozze[d.id] || '', fase: 'scrivi', correzione: null, conferma: false };
    aggiornaTasti();
    disegnaTutto();
  }

  /** Chiude il lettore se ha quella lezione (eliminata). */
  A.lettoreChiudiSe = function (id) {
    if (P.id !== id) return;
    chiudiLettore(false);
  };

  /** Chiude il lettore; con `salva` prima il punto va sul server (la promessa finisce quando è andato). */
  function chiudiLettore(salva) {
    const salvato = salva ? scarica(true) : Promise.resolve();
    if (P.audio) { P.audio.pause(); scollega(P.audio); try { P.audio.removeAttribute('src'); P.audio.load(); } catch (e) { /* niente */ } }
    clearInterval(P.ticker);
    P.giro = (P.giro || 0) + 1; // un'apertura ancora in corso si ferma
    Object.assign(P, { lezione: null, id: null, idInCarico: null, audio: null, fermate: null, domanda: null, errore: null, caricando: false, promessa: null });
    aggiornaTasti();
    disegnaTutto();
    return salvato;
  }
  A.chiudiLettore = chiudiLettore;

  /**
   * I tasti multimediali della tastiera li prende il programma solo mentre l'audio suona o è fermo a una domanda:
   * in pausa tornano agli altri programmi.
   */
  function aggiornaTasti() {
    const voglio = !!(P.audio && P.id && (!P.audio.paused || P.domanda));
    if (voglio === !!P.tastiAttivi) return;
    P.tastiAttivi = voglio;
    if (!S.esempio) A.api.post('/locale/tasti', { attivi: voglio });
  }
  A.lettoreAggiornaTasti = aggiornaTasti;

  /** Riprendendo una lezione già caricata: se sul server c'è un punto più recente (il telefono), ci si sposta lì. */
  async function allineaAlServer(id) {
    if (S.esempio || !P.lezione) return;
    const r = await A.dettaglioLezione(id, true);
    if (!r.ok || !r.dati || r.memoria || P.id !== id || !P.audio || !P.audio.paused || P.domanda) return;
    const mio = P.lezione.ascolto;
    const server = r.dati.ascolto;
    if (!server || L.piuRecente(mio, server) !== server) return;
    if (mio && mio.aggiornato_il === server.aggiornato_il) return;
    P.lezione.ascolto = server;
    const t = L.puntoDiPartenza(server, durataDi());
    if (Math.abs(t - adesso()) < 1) return;
    salto(t, false);
    const aperta = fermateAttive() ? L.domandaAperta(P.lezione.domande, t) : null;
    if (aperta) apriDomandaId(aperta.id);
  }

  // --- Eventi dell'audio -------------------------------------------------------------------------

  const ascoltatori = {
    play: () => { avviaTicker(); disegnaComandi(); disegnaDomanda(); aggiornaTasti(); },
    pause: () => { fermaTicker(); disegnaComandi(); disegnaDomanda(); sinc(false); aggiornaTasti(); },
    ended: () => {
      P.finita = true;
      fermaTicker();
      sinc(true);
      disegnaTutto();
      aggiornaTasti();
    },
    timeupdate: () => passo(),
  };
  function collega(audio) { for (const [k, f] of Object.entries(ascoltatori)) audio.addEventListener(k, f); }
  function scollega(audio) { for (const [k, f] of Object.entries(ascoltatori)) audio.removeEventListener(k, f); }

  function avviaTicker() {
    clearInterval(P.ticker);
    P.ticker = setInterval(passo, 200);
  }
  function fermaTicker() { clearInterval(P.ticker); }

  /** A ogni passo: le fermate, il punto sul server ogni ~15 s, i tempi a schermo. */
  function passo() {
    if (!P.audio || !P.fermate) return;
    const t = adesso();
    if (!P.audio.paused) {
      P.fermate.attiva(fermateAttive());
      const ev = P.fermate.osserva(t, P.audio.playbackRate);
      if (ev && ev.tipo === 'domanda') {
        P.audio.pause();
        P.domanda = { d: ev.domanda, testo: P.bozze[ev.domanda.id] || '', fase: 'scrivi', correzione: null, conferma: false };
        disegnaDomanda();
        disegnaComandi();
        aggiornaTasti();
      } else if (ev && ev.tipo === 'fine_risposta') {
        P.audio.pause();
        if (P.domanda) P.domanda.finitaRisposta = true;
        disegnaDomanda();
        // A finestra nascosta non si può premere Avanti: si riparte da soli dopo 3 secondi.
        if (document.hidden) setTimeout(() => { if (P.fermate && P.fermate.stato === 'dopo' && document.hidden) avanti(); }, T.riparte);
      }
      if (Date.now() - P.ultimaSinc >= T.sincronia) sinc(false);
    }
    aggiornaTempi();
  }

  // --- Comandi ---------------------------------------------------------------------------------

  function suona() {
    if (!P.audio) return;
    const p = P.audio.play();
    if (p && typeof p.catch === 'function') p.catch(() => { /* l'utente rifà clic */ });
  }

  function playPausa() {
    if (!P.audio) return;
    if (P.audio.paused) {
      if (P.finita && adesso() >= durataDi() - 0.5) { salto(0, false); P.finita = false; }
      if (P.fermate && P.fermate.stato === 'dopo') { avanti(); return; }
      suona();
    } else {
      P.audio.pause();
    }
  }

  /** Un salto (capitolo, barra, -15/+30): le fermate non scattano per quello che si è saltato. */
  function salto(t, torna) {
    if (!P.audio) return;
    const da = adesso();
    const dove = Math.max(0, Math.min(durataDi() - 0.25, t));
    P.fermate.salto(dove);
    if (P.domanda && P.fermate.stato === 'ascolto') { P.domanda = null; aggiornaTasti(); }
    try { P.audio.currentTime = dove; } catch (e) { /* audio non pronto */ }
    if (dove < durataDi() - 1) P.finita = false;
    if (torna) {
      clearTimeout(P.torna && P.torna.timer);
      P.torna = { da, timer: setTimeout(() => { P.torna = null; disegnaTutto(); }, T.torna) };
    }
    disegnaTutto();
  }
  A.lettoreSalto = salto;

  function cambiaVelocita(v) {
    const nuova = v != null ? v : L.prossimaVelocita(P.audio ? P.audio.playbackRate : velocitaPreferita());
    if (!S.esempio) scrivi(CHIAVE_VELOCITA, nuova);
    if (P.audio) P.audio.playbackRate = nuova;
    disegnaTutto();
  }
  A.lettoreVelocita = cambiaVelocita;

  // --- La domanda ------------------------------------------------------------------------------

  async function mandaRisposta(saltata) {
    const q = P.domanda;
    if (!q || q.fase !== 'scrivi') return;
    const testo = saltata ? '' : String(q.testo || '').trim();
    if (!saltata && !L.rispostaValida(testo)) return;
    const d = q.d;
    const lezioneId = P.id;
    if (!saltata) P.fermate.inCorrezione();
    q.fase = saltata ? 'corretta' : 'correggo';
    q.conferma = false;
    if (saltata) q.correzione = { esito: 'saltata', commento: 'La ritrovi nella pagina di ' + materia().nome + ', sotto Da ripassare.', mancava: [] };
    disegnaDomanda();
    if (A.visitaEvento && !saltata) A.visitaEvento('invia-risposta');
    const corpo = { testo, saltata: !!saltata };
    const r = await A.api.post('/server/api/lezioni/' + lezioneId + '/domande/' + d.id + '/risposta', corpo);
    if (P.domanda !== q) return;
    delete P.bozze[d.id];
    const sconosciuto = !r.ok && r.dati && r.dati.codice === 'esito_sconosciuto';
    if (!saltata) {
      if (r.ok) q.correzione = r.dati || {};
      else if (sconosciuto) {
        // Il server ci ha messo troppo: forse ha corretto lo stesso. Si guarda prima di rimandarla (e ripagarla).
        const gia = await rispostaGiaArrivata(lezioneId, d.id, testo, false);
        if (P.domanda !== q) return;
        if (gia) q.correzione = Object.assign({ risposta_attesa: d.risposta }, gia);
        else {
          accoda({ lezione_id: lezioneId, domanda_id: d.id, testo, saltata: false, quando: new Date().toISOString() });
          q.correzione = { incerta: true };
        }
      } else if (r.rete) {
        // Senza rete: la risposta resta in coda e si vede subito la risposta giusta per confrontare.
        accoda({ lezione_id: lezioneId, domanda_id: d.id, testo, saltata: false, quando: new Date().toISOString() });
        q.correzione = { offline: true };
      } else q.correzione = { errore: r.errore };
    } else if (!r.ok && (r.rete || sconosciuto)) {
      accoda({ lezione_id: lezioneId, domanda_id: d.id, testo: '', saltata: true, quando: new Date().toISOString() });
    }
    q.fase = 'corretta';
    // Poi parte la risposta giusta, col suo testo sotto.
    const da = P.fermate.sentiRisposta();
    if (da != null) {
      try { P.audio.currentTime = da; } catch (e) { /* niente */ }
      suona();
    }
    disegnaDomanda();
  }

  /** Il vecchio "Senti la risposta" (Risposte scritte spento). */
  function sentiRisposta() {
    if (!P.domanda) return;
    const da = P.fermate.sentiRisposta();
    if (da == null) return;
    P.domanda.fase = 'corretta';
    P.domanda.correzione = { vecchio: true };
    try { P.audio.currentTime = da; } catch (e) { /* niente */ }
    suona();
    disegnaDomanda();
  }

  function ripetiDomanda() {
    if (!P.domanda || !P.fermate) return;
    P.bozze[P.domanda.d.id] = P.domanda.testo || '';
    const t = P.fermate.ripetiDomanda();
    P.domanda = null;
    if (t == null) return;
    try { P.audio.currentTime = t; } catch (e) { /* niente */ }
    suona();
    disegnaTutto();
  }

  function avanti() {
    if (!P.fermate) return;
    P.fermate.avanti();
    P.domanda = null;
    if (P.audio && P.audio.paused) suona();
    disegnaTutto();
  }

  function accoda(voce) {
    if (S.esempio) return;
    const coda = leggi(CHIAVE_CODA) || [];
    coda.push(voce);
    scrivi(CHIAVE_CODA, coda.slice(-200));
  }

  /** Torna la rete: partono le risposte in coda e i punti di ascolto non mandati. */
  /**
   * La risposta è già arrivata al server (e corretta)? Si legge la lezione: se la domanda ha già `mia_risposta` con lo
   * stesso testo (o saltata), sì. null = no, oppure non si sa (senza rete): in quel caso `rete` è vero.
   */
  async function rispostaGiaArrivata(lezioneId, domandaId, testo, saltata) {
    const r = await A.api.get('/server/api/lezioni/' + lezioneId);
    if (!r.ok || !r.dati || r.memoria) return null;
    const d = (r.dati.domande || []).find((x) => x.id === domandaId);
    const mia = d && d.mia_risposta;
    if (!mia) return null;
    if (saltata) return mia.esito === 'saltata' ? mia : null;
    return String(mia.testo || '').trim() === String(testo || '').trim() ? mia : null;
  }

  /** Scollegando (o ricollegando) il computer: via i punti di ascolto e le risposte in coda del collegamento di prima. */
  A.svuotaMemoriaLocale = function () {
    try {
      const chiavi = [];
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (k && (k.startsWith(CHIAVE_POS) || k === CHIAVE_CODA)) chiavi.push(k);
      }
      chiavi.forEach((k) => localStorage.removeItem(k));
    } catch (e) { /* senza memoria del browser non c'è niente da togliere */ }
  };

  let mandando = false;
  A.mandaCode = async function () {
    if (S.esempio || mandando) return;
    mandando = true;
    try {
      for (;;) {
        const coda = leggi(CHIAVE_CODA) || [];
        if (!coda.length) break;
        const v = coda[0];
        const togli = () => scrivi(CHIAVE_CODA, (leggi(CHIAVE_CODA) || []).filter((x) => !(x.quando === v.quando && x.domanda_id === v.domanda_id)));
        // Prima si guarda se è già arrivata (tempo scaduto la volta prima): così non si paga due volte.
        if (await rispostaGiaArrivata(v.lezione_id, v.domanda_id, v.testo, v.saltata)) { togli(); continue; }
        if (S.senzaRete) break;
        const r = await A.api.post('/server/api/lezioni/' + v.lezione_id + '/domande/' + v.domanda_id + '/risposta', { testo: v.testo, saltata: v.saltata });
        if (!r.ok && (r.rete || (r.dati && r.dati.codice === 'esito_sconosciuto'))) break; // si riprova dopo, ricontrollando
        togli();
      }
      await mandaPunti();
    } finally {
      mandando = false;
    }
  };

  async function mandaPunti() {
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const k = localStorage.key(i);
        if (!k || !k.startsWith(CHIAVE_POS)) continue;
        const p = leggi(k);
        if (p && !p.inviato) {
          const id = Number(k.slice(CHIAVE_POS.length));
          const r = await A.api.put('/server/api/lezioni/' + id + '/ascolto', { posizione_s: p.posizione_s, finita: p.finita, aggiornato_il: p.aggiornato_il });
          if (r.ok) scrivi(k, Object.assign(p, { inviato: true }));
        }
      }
    } catch (e) { /* senza memoria del browser niente coda */ }
  }
  A.dopoRitornoRete = () => { A.mandaCode(); if (S.aggiornaPagina) S.aggiornaPagina(); };

  // --- Il punto di ascolto sul server (vince il più recente) --------------------------------------

  /** Il punto da salvare: con una domanda ancora senza risposta, l'inizio della domanda (alla ripresa si rifà). */
  function puntoDaSalvare() {
    const st = P.fermate ? P.fermate.stato : 'ascolto';
    if (P.domanda && (st === 'domanda' || st === 'correzione')) return P.domanda.d.inizio_s;
    return adesso();
  }

  function sinc(finita) {
    if (!P.id || !P.audio || P.caricando) return Promise.resolve();
    const id = P.id;
    const punto = { posizione_s: Math.round(puntoDaSalvare() * 10) / 10, finita: !!finita || P.finita, aggiornato_il: new Date().toISOString(), inviato: false };
    // In modalità esempio (visita guidata) niente sul computer: la lezione 12 vera resta pulita.
    if (!S.esempio) scrivi(CHIAVE_POS + id, punto);
    P.ultimaSinc = Date.now();
    if (P.lezione) P.lezione.ascolto = { posizione_s: punto.posizione_s, finita: punto.finita, aggiornato_il: punto.aggiornato_il };
    const promessa = A.api.put('/server/api/lezioni/' + id + '/ascolto', { posizione_s: punto.posizione_s, finita: punto.finita, aggiornato_il: punto.aggiornato_il })
      .then((r) => { if (r.ok && !S.esempio) { const ora = leggi(CHIAVE_POS + id); if (ora && ora.aggiornato_il === punto.aggiornato_il) scrivi(CHIAVE_POS + id, Object.assign(ora, { inviato: true })); } })
      .finally(() => { if (P.sincInCorso === promessa) P.sincInCorso = null; });
    P.sincInCorso = promessa;
    return promessa;
  }
  /** Prima di cambiare lezione o chiudere: il punto va sul server. */
  function scarica(attendi) {
    if (!P.id || !P.audio) return Promise.resolve();
    const p = sinc(false);
    return attendi ? p : Promise.resolve();
  }

  // Il programma chiede alla pagina di salvare prima di chiudere la finestra.
  let chiusura = null;
  window.lectioPrimaDiChiudere = () => {
    if (!P.id || !P.audio || S.esempio) return 'pulito';
    if (P.audio && !P.audio.paused) P.audio.pause();
    const stato = { finito: false };
    chiusura = stato;
    sinc(false).then(() => { stato.finito = true; }, () => { stato.finito = true; });
    return 'in_corso';
  };
  window.lectioStatoChiusura = () => (chiusura && !chiusura.finito ? 'in_corso' : 'pulito');
  window.addEventListener('pagehide', () => { if (P.id && P.audio) sinc(false); });

  // --- Tastiera e tasti multimediali ---------------------------------------------------------------

  function esegui(azione) {
    switch (azione) {
      case 'senti': sentiRisposta(); break;
      case 'fuoco': { const c = document.getElementById('risposta-domanda'); if (c) c.focus(); break; }
      case 'salta': mandaRisposta(true); break;
      case 'ripeti': ripetiDomanda(); break;
      case 'avanti': avanti(); break;
      case 'playpausa': playPausa(); break;
      default: break;
    }
  }

  function azioneTasto() {
    const st = P.fermate ? P.fermate.stato : 'ascolto';
    return L.azioneTastoPlay({
      stato: P.domanda ? st : (st === 'dopo' ? 'dopo' : 'ascolto'),
      scrivendo: document.hasFocus() && A.staScrivendo(),
      finestraAttiva: document.hasFocus() && !document.hidden,
      risposteScritte: risposteScritte(),
      saltabili: saltabili(),
    });
  }
  A.azioneTastoLettore = azioneTasto;

  /** I tasti della tastiera quando la finestra è davanti (dal keydown della pagina). */
  A.tastoLettore = function (e) {
    if (!P.audio) return;
    if (e.key === 'Escape' && P.domanda) {
      // La scheda della domanda copre la finestra: Esc chiude la lezione (il punto resta all'inizio della domanda).
      e.preventDefault();
      chiudiDallaDomanda();
      return;
    }
    const interattivo = e.target && e.target.closest && e.target.closest('button, a, input, textarea, select, [contenteditable], [role="slider"]');
    if (e.key === ' ' || e.key === 'MediaPlayPause') {
      if (A.staScrivendo()) return; // si sta scrivendo: lo spazio scrive, niente audio
      if (e.key === ' ' && interattivo) return; // lo spazio su un pulsante lo preme
      e.preventDefault();
      esegui(azioneTasto());
      return;
    }
    if (A.staScrivendo() || P.domanda) return;
    if (e.key === 'MediaTrackNext') { e.preventDefault(); salto(adesso() + 30, false); }
    if (e.key === 'MediaTrackPrevious') { e.preventDefault(); salto(adesso() - 15, false); }
  };

  /** "Chiudi la lezione" dalla scheda della domanda (o Esc): il punto va sul server all'inizio della domanda. */
  function chiudiDallaDomanda() {
    const vista = S.rotta.vista;
    const fatto = chiudiLettore(true);
    if (vista === 'lettore') A.indietro();
    return fatto;
  }
  A.chiudiDallaDomanda = chiudiDallaDomanda;

  /** I tasti multimediali dal programma (RegisterHotKey), anche con la finestra dietro. */
  window.lectioTasto = function (tasto) {
    if (!P.audio) return;
    if (document.hasFocus() && A.staScrivendo()) return;
    if (tasto === 'play') esegui(azioneTasto());
    else if (tasto === 'avanti' && !P.domanda) salto(adesso() + 30, false);
    else if (tasto === 'indietro' && !P.domanda) salto(adesso() - 15, false);
  };

  function sessioneMedia() {
    if (!('mediaSession' in navigator) || !P.lezione) return;
    try {
      if (window.MediaMetadata) navigator.mediaSession.metadata = new window.MediaMetadata({ title: P.lezione.argomento, artist: materia().nome, album: 'Lectio' });
      // Il riquadro multimediale di Windows segue le stesse regole dei tasti: niente salti con una domanda aperta.
      navigator.mediaSession.setActionHandler('play', () => window.lectioTasto('play'));
      navigator.mediaSession.setActionHandler('pause', () => window.lectioTasto('play'));
      navigator.mediaSession.setActionHandler('seekbackward', () => window.lectioTasto('indietro'));
      navigator.mediaSession.setActionHandler('seekforward', () => window.lectioTasto('avanti'));
      navigator.mediaSession.setActionHandler('previoustrack', () => window.lectioTasto('indietro'));
      navigator.mediaSession.setActionHandler('nexttrack', () => window.lectioTasto('avanti'));
    } catch (e) { /* non tutte le azioni ci sono */ }
  }

  // --- Disegno: barra in basso ---------------------------------------------------------------------

  function disegnaTutto() {
    A.disegnaBarraLettore();
    if (S.rotta.vista === 'lettore' && P.ui.pagina && P.ui.pagina.isConnected) disegnaPagina();
    disegnaDomanda();
  }
  A.lettoreRidisegna = disegnaTutto;

  function capitoloCorrente() {
    const caps = (P.lezione && P.lezione.capitoli) || [];
    if (!caps.length) return null;
    return { i: L.capitoloA(caps, adesso()), c: caps[L.capitoloA(caps, adesso())] };
  }

  A.disegnaBarraLettore = function () {
    const box = S.dom.barraLettore;
    if (!box) return;
    if (!P.id || S.rotta.vista === 'lettore') { svuota(box); P.ui.barra = null; return; }
    const m = materia();
    const cap = capitoloCorrente();
    const sotto = P.errore ? P.errore : P.caricando ? (P.percentuale > 0 && P.percentuale < 100 ? 'Scarico la lezione · ' + P.percentuale + '%' : 'Apro la lezione…') : cap ? 'Capitolo ' + (cap.i + 1) + ' · ' + cap.c.titolo : '';
    const play = h('button', { type: 'button', class: 'tondo play', 'data-azione': 'barra-play', 'aria-label': P.audio && !P.audio.paused ? 'Pausa' : 'Riproduci', disabled: !P.audio || P.caricando ? true : null, onclick: () => esegui(P.domanda ? azioneTasto() : 'playpausa') }, icona(P.audio && !P.audio.paused ? 'pausa' : 'play'));
    const passato = h('span', null, L.tempo(adesso()));
    const manca = h('span', null, L.meno(durataDi() - adesso()));
    const riemp = h('span', { stile: { width: L.percentuale(adesso(), durataDi()) + '%', transition: 'none' } });
    const barra = h('div', { class: 'barra media', 'aria-hidden': 'true' }, riemp);
    const nodo = h('div', { class: 'barra-lettore ' + A.classeMateria(m.colore), role: 'region', 'aria-label': 'Lettore' },
      h('button', { type: 'button', class: 'apri', 'data-azione': 'apri-lettore', onclick: () => A.vai('#lettore/' + P.id) }, A.piastrella(m.colore),
        h('span', { class: 'testi' }, h('span', { class: 't1' }, P.lezione ? P.lezione.argomento : 'Lezione'), h('span', { class: 't2' }, sotto))),
      h('div', { class: 'comandi' },
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Indietro di 15 secondi', disabled: !P.audio ? true : null, onclick: () => salto(adesso() - 15, false) }, '-15'),
        play,
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Avanti di 30 secondi', disabled: !P.audio ? true : null, onclick: () => salto(adesso() + 30, false) }, '+30')),
      h('div', { class: 'tempi' }, passato, barra, manca),
      h('button', { type: 'button', class: 'pillola-velocita', 'aria-label': 'Velocità', onclick: () => cambiaVelocita() }, L.etichettaVelocita(P.audio ? P.audio.playbackRate : velocitaPreferita())));
    const nuova = !P.ui.barra;
    if (!nuova) nodo.style.animation = 'none';
    svuota(box, nodo);
    P.ui.barra = { passato, manca, riemp, play };
  };

  function disegnaComandi() {
    A.disegnaBarraLettore();
    if (P.ui.play && P.ui.play.isConnected) {
      svuota(P.ui.play, icona(P.audio && !P.audio.paused ? 'pausa' : 'play'));
      P.ui.play.setAttribute('aria-label', P.audio && !P.audio.paused ? 'Pausa' : 'Riproduci');
      if (P.ui.pagina) P.ui.pagina.classList.toggle('suona', !!(P.audio && !P.audio.paused));
    }
  }

  /** Solo i numeri e le barre (a ogni passo): niente ridisegno completo. */
  function aggiornaTempi() {
    const t = adesso();
    const d = durataDi();
    const b = P.ui.barra;
    if (b && b.passato.isConnected) {
      b.passato.textContent = L.tempo(t);
      b.manca.textContent = L.meno(d - t);
      b.riemp.style.width = L.percentuale(t, d) + '%';
    }
    const u = P.ui;
    if (u.pagina && u.pagina.isConnected) {
      if (!u.trascinando) {
        const pct = d > 0 ? Math.min(100, t / d * 100) : 0;
        u.pieno.style.width = pct + '%';
        u.pomello.style.left = pct + '%';
        u.traccia.setAttribute('aria-valuenow', String(Math.round(t)));
        u.traccia.setAttribute('aria-valuetext', L.tempo(t));
      }
      u.passato.textContent = L.tempo(t);
      u.manca.textContent = L.meno(d - t);
      const stati = L.capitoliStato((P.lezione && P.lezione.capitoli) || [], t);
      if (stati.join() !== u.statiCapitoli) {
        u.statiCapitoli = stati.join();
        disegnaPagina();
      }
    }
  }

  // --- Disegno: la pagina grande -------------------------------------------------------------------

  A.pagine.lettore = function (box, rotta) {
    const pagina = h('div', { class: 'pagina lettore-pagina' });
    svuota(box, pagina);
    P.ui.pagina = pagina;
    P.ui.statiCapitoli = null;
    if (P.id !== rotta.id) A.ascolta(rotta.id, {});
    disegnaPagina();
  };

  function disegnaPagina() {
    const pag = P.ui.pagina;
    if (!pag) return;
    const m = materia();
    const l = P.lezione;
    if (!l) {
      svuota(pag, P.errore ? A.messaggio('errore', P.errore, [h('button', { type: 'button', class: 'pulsante bianco', onclick: () => { const id = P.id; P.id = null; A.ascolta(id, {}); } }, 'Riprova')]) : A.segnaposto(5));
      return;
    }
    const t = adesso();
    const d = durataDi();
    const caps = l.capitoli || [];
    const stati = L.capitoliStato(caps, t);
    P.ui.statiCapitoli = stati.join();
    const scaricata = S.motore && (S.motore.scaricate || []).includes(l.id);

    // In alto: chiudi, materia, scarica, menu.
    const menu = h('div', { class: 'menu-piccolo' });
    menu.append(h('button', { type: 'button', class: 'tondo', 'aria-label': 'Altre azioni', 'data-azione': 'menu-lettore', onclick: (e) => { e.stopPropagation(); tendinaLettore(menu); } }, icona('puntini')));
    const alto = h('div', { class: 'alto' },
      h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi il lettore', onclick: () => A.indietro() }, icona('giu')),
      A.pillolaMateria(m, () => A.vai('#materia/' + m.id)),
      h('div', { class: 'destra' },
        h('button', { type: 'button', class: 'tondo', 'aria-label': scaricata ? 'Scaricata sul computer' : 'Scarica sul computer', title: scaricata ? 'Scaricata sul computer' : 'Scarica sul computer', stile: { color: scaricata ? 'var(--fatto)' : '' },
          onclick: async () => { if (scaricata) return; const r = await A.api.post('/locale/lezioni/' + l.id + '/scarica', {}); A.avviso(r.ok ? 'Scaricata sul computer.' : r.errore); await A.aggiornaMotore(); disegnaPagina(); } }, icona(scaricata ? 'spunta' : 'scarica')),
        menu));
    const piastrella = h('div', { class: 'piastrella-grande ' + A.classeMateria(m.colore) },
      h('div', { class: 'barrette', 'aria-hidden': 'true' }, h('i'), h('i'), h('i')),
      h('span', { class: 'titolo' }, l.argomento),
      h('span', { class: 'sotto' }, L.riassuntoLezione(l)));

    // Barra trascinabile, coi segni delle domande.
    const pieno = h('div', { class: 'pieno', stile: { width: (d ? Math.min(100, t / d * 100) : 0) + '%' } });
    const pomello = h('div', { class: 'pomello', stile: { left: (d ? Math.min(100, t / d * 100) : 0) + '%' } });
    const segni = (l.domande || []).map((q) => h('span', { class: 'segno-domanda', stile: { left: (d ? q.fine_domanda_s / d * 100 : 0) + '%' } }));
    const traccia = h('div', { class: 'traccia', role: 'slider', tabindex: '0', 'aria-label': 'Punto della lezione', 'aria-valuemin': '0', 'aria-valuemax': String(Math.round(d)), 'aria-valuenow': String(Math.round(t)) },
      h('div', { class: 'binario' }, pieno, segni, pomello));
    const daX = (x) => { const r = traccia.getBoundingClientRect(); return r.width ? Math.max(0, Math.min(1, (x - r.left) / r.width)) * d : 0; };
    traccia.addEventListener('pointerdown', (e) => {
      if (!P.audio) return;
      P.ui.trascinando = true;
      traccia.setPointerCapture(e.pointerId);
      const muovi = (x) => { const pct = d ? daX(x) / d * 100 : 0; pieno.style.width = pct + '%'; pomello.style.left = pct + '%'; passato.textContent = L.tempo(daX(x)); };
      muovi(e.clientX);
      const mv = (ev) => muovi(ev.clientX);
      const su = (ev) => {
        traccia.removeEventListener('pointermove', mv);
        traccia.removeEventListener('pointerup', su);
        traccia.removeEventListener('pointercancel', su);
        P.ui.trascinando = false;
        salto(daX(ev.clientX), true);
      };
      traccia.addEventListener('pointermove', mv);
      traccia.addEventListener('pointerup', su);
      traccia.addEventListener('pointercancel', su);
    });
    traccia.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowRight') { e.preventDefault(); salto(adesso() + 5, false); }
      if (e.key === 'ArrowLeft') { e.preventDefault(); salto(adesso() - 5, false); }
    });
    const passato = h('span', null, L.tempo(t));
    const manca = h('span', null, L.meno(d - t));

    const play = h('button', { type: 'button', class: 'tondo play', 'data-azione': 'play', 'aria-label': P.audio && !P.audio.paused ? 'Pausa' : 'Riproduci', disabled: !P.audio || P.caricando ? true : null, onclick: () => esegui(P.domanda ? azioneTasto() : 'playpausa') },
      icona(P.audio && !P.audio.paused ? 'pausa' : 'play'));
    const sinistra = h('div', { class: 'sinistra' },
      alto, piastrella,
      P.errore ? A.messaggio('errore', P.errore) : null,
      P.caricando ? h('div', { class: 'scaricamento' }, h('span', { class: 'secondario' }, P.percentuale > 0 && P.percentuale < 100 ? 'Scarico la lezione · ' + P.percentuale + '%' : 'Apro la lezione…'), A.barra(P.percentuale, 'sottile')) : null,
      h('div', { class: 'avanzamento' }, traccia, h('div', { class: 'tempi-lettore' }, passato, manca)),
      P.torna ? h('button', { type: 'button', class: 'pillola accento torna', 'data-azione': 'torna', onclick: () => { const da = P.torna.da; clearTimeout(P.torna.timer); P.torna = null; salto(da, false); } }, icona('ripeti'), 'Torna dov\'eri') : null,
      h('div', { class: 'comandi-grandi' },
        h('button', { type: 'button', class: 'tondo salto', 'aria-label': 'Indietro di 15 secondi', disabled: !P.audio ? true : null, onclick: () => salto(adesso() - 15, false) }, '-15'),
        play,
        h('button', { type: 'button', class: 'tondo salto', 'aria-label': 'Avanti di 30 secondi', disabled: !P.audio ? true : null, onclick: () => salto(adesso() + 30, false) }, '+30')),
      h('div', { class: 'comandi-piccoli' },
        h('button', { type: 'button', 'aria-label': 'Velocità', 'data-azione': 'velocita', onclick: () => cambiaVelocita() }, L.etichettaVelocita(P.audio ? P.audio.playbackRate : velocitaPreferita())),
        h('button', { type: 'button', 'aria-pressed': P.testo ? 'true' : 'false', onclick: () => { P.testo = !P.testo; disegnaPagina(); } }, 'Testo')),
      P.finita ? h('div', { class: 'finita' }, icona('spunta'), 'Finita',
        l.evento_id ? h('span', { class: 'piu' }, 'Dopo la verifica raccontami com\'è andata.') : null) : null);

    const destra = h('div', { class: 'colonna' });
    if (P.testo) {
      const sezioni = (l.testo || []).map((s) => {
        const i = caps.findIndex((c) => c.n === s.capitolo);
        const cap = caps[i];
        return h('section', { class: stati[i] === 'corso' ? 'corso' : '', 'data-capitolo': String(s.capitolo) },
          h('h3', null, cap ? cap.titolo : 'Capitolo ' + s.capitolo), (s.paragrafi || []).map((p) => h('p', null, p.replace(/^\s*(\[[a-z]+=[a-z]+\])+\s*/i, ''))));
      });
      destra.append(h('div', { class: 'testo-lezione' }, sezioni.length ? sezioni : h('p', { class: 'secondario' }, 'Il testo di questa lezione non c\'è.')));
    } else {
      destra.append(h('div', { class: 'sezione' }, h('span', { class: 'etichetta' }, 'Capitoli'),
        h('div', { class: 'capitoli' }, caps.map((c, i) => h('button', { type: 'button', class: 'capitolo ' + stati[i], 'data-capitolo': String(c.n), onclick: () => salto(c.inizio_s, true) },
          h('span', { class: 'segno', 'aria-hidden': 'true' }, stati[i] === 'fatto' ? icona('spunta') : stati[i] === 'corso' ? h('span', { class: 'barrette-corso' }, h('i'), h('i'), h('i')) : null),
          h('span', { class: 'titolo' }, c.titolo), h('span', { class: 'durata' }, L.durataBreve(c.fine_s - c.inizio_s)))))));
      if (l.nota) destra.append(h('p', { class: 'secondario' }, l.nota));
    }
    svuota(pag, h('div', { class: 'lettore' + (P.audio && !P.audio.paused ? ' suona' : '') }, sinistra, destra));
    pag.classList.toggle('suona', !!(P.audio && !P.audio.paused));
    Object.assign(P.ui, { pieno, pomello, traccia, passato, manca, play });
    if (P.testo) {
      const corso = pag.querySelector('.testo-lezione section.corso');
      if (corso && corso.scrollIntoView) corso.scrollIntoView({ block: 'nearest' });
    }
  }

  function tendinaLettore(menu) {
    const esistente = menu.querySelector('.tendina');
    if (esistente) { esistente.remove(); return; }
    const l = P.lezione;
    if (!l) return;
    const scaricata = S.motore && (S.motore.scaricate || []).includes(l.id);
    const t = h('div', { class: 'tendina', role: 'menu' },
      h('button', { type: 'button', 'data-azione': 'cambia-saltabili', onclick: async () => {
        chiudi();
        const nuovo = !l.domande_saltabili;
        const r = await A.api.patch('/server/api/lezioni/' + l.id, { domande_saltabili: nuovo });
        if (!r.ok) { A.avviso(r.errore); return; }
        l.domande_saltabili = nuovo;
        A.avviso(nuovo ? 'Domande: si possono saltare.' : 'Domande: obbligatorie.');
        disegnaTutto();
      } }, l.domande_saltabili ? 'Domande obbligatorie' : 'Domande che si possono saltare'),
      scaricata ? h('button', { type: 'button', onclick: async () => {
        chiudi();
        chiudiLettore(true);
        const r = await A.api.elimina('/locale/lezioni/' + l.id);
        A.avviso(r.ok ? 'Tolta dal computer.' : r.errore);
        await A.aggiornaMotore();
        A.vai('#lezioni');
      } }, 'Togli dal computer') : null,
      h('button', { type: 'button', onclick: () => { chiudi(); chiudiLettore(true); A.indietro(); } }, 'Chiudi la lezione'));
    function chiudi() { t.remove(); document.removeEventListener('mousedown', fuori, true); }
    function fuori(e) { if (!menu.contains(e.target)) chiudi(); }
    document.addEventListener('mousedown', fuori, true);
    menu.append(t);
  }

  // --- Disegno: la scheda della domanda --------------------------------------------------------------

  function disegnaDomanda() {
    let velo = document.getElementById('domanda-velo');
    const q = P.domanda;
    if (!q || !P.id) {
      if (velo) velo.remove();
      return;
    }
    const d = q.d;
    const scritte = risposteScritte();
    // Si ridisegna solo se cambia qualcosa: mai sotto le dita mentre si scrive.
    const inAscoltoOra = !!(P.audio && !P.audio.paused && P.fermate && P.fermate.stato === 'risposta');
    const chiave = [d.id, q.fase, q.conferma, scritte, saltabili(), inAscoltoOra, q.correzione ? JSON.stringify(q.correzione) : ''].join('|');
    if (velo && velo.dataset.chiave === chiave) return;
    const nuova = !velo;
    if (!velo) {
      velo = h('div', { class: 'domanda-velo', id: 'domanda-velo' });
      document.body.append(velo);
    }
    velo.dataset.chiave = chiave;
    const modo = L.modoSalto(saltabili());
    const parti = [
      h('div', { class: 'maniglia', 'aria-hidden': 'true' }),
      h('div', { class: 'testa-sezione', stile: { minHeight: '0' } },
        h('span', { class: 'etichetta' }, L.etichettaDomanda(d)),
        h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'chiudi-lezione', stile: { fontSize: '14px', height: '32px' }, onclick: chiudiDallaDomanda }, 'Chiudi la lezione')),
      h('span', { class: 'testo-domanda', id: 'testo-domanda' }, d.testo),
    ];
    if (q.fase === 'scrivi' && scritte) {
      const campo = h('textarea', { class: 'campo', id: 'risposta-domanda', rows: '4', maxlength: '2000', placeholder: 'Scrivi la tua risposta', 'aria-labelledby': 'testo-domanda' });
      campo.value = q.testo || '';
      const invia = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'invia-risposta', disabled: L.rispostaValida(q.testo) ? null : true, onclick: () => mandaRisposta(false) },
        'Invia risposta', h('span', { class: 'tasto-ctrl', 'aria-hidden': 'true' }, 'Ctrl+Invio'));
      campo.addEventListener('input', () => {
        q.testo = campo.value;
        P.bozze[d.id] = campo.value;
        invia.disabled = !L.rispostaValida(campo.value);
      });
      campo.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); mandaRisposta(false); }
      });
      const salta = modo.conferma
        ? h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'salta-comunque', stile: { fontSize: '14px', fontWeight: '600', color: 'var(--moltotenue)' }, onclick: () => { q.conferma = true; disegnaDomanda(); } }, modo.etichetta)
        : h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'salta', onclick: () => mandaRisposta(true) }, modo.etichetta);
      parti.push(h('div', { 'data-visita': 'risposta', stile: { display: 'flex', flexDirection: 'column', gap: '12px' } }, campo, invia),
        h('div', { class: 'azioni fra' },
          h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'ripeti-domanda', onclick: ripetiDomanda }, 'Ripeti la domanda'), salta));
      if (q.conferma && modo.conferma) {
        parti.push(h('div', { class: 'risposta-giusta', 'data-conferma': 'salto' },
          h('span', { class: 'forte', stile: { fontSize: '15px' } }, modo.domanda),
          h('div', { stile: { display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: '8px' } },
            h('button', { type: 'button', class: 'pulsante grigio', 'data-azione': 'si-salta', onclick: () => mandaRisposta(true) }, modo.si),
            h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'rispondo', onclick: () => { q.conferma = false; disegnaDomanda(); setTimeout(() => { const c = document.getElementById('risposta-domanda'); if (c) c.focus(); }, 20); } }, modo.no))));
      }
    } else if (q.fase === 'scrivi') {
      parti.push(h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'senti-risposta', onclick: sentiRisposta }, 'Senti la risposta'),
        h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'ripeti-domanda', onclick: ripetiDomanda }, 'Ripeti la domanda'));
    } else if (q.fase === 'correggo') {
      parti.push(h('div', { class: 'correggo', role: 'status' }, 'Correggo…'));
    } else {
      const c = q.correzione || {};
      const inAscolto = P.audio && !P.audio.paused && P.fermate && P.fermate.stato === 'risposta';
      const attesa = c.risposta_attesa || d.risposta;
      if (c.vecchio) {
        parti.push(h('div', { class: 'risposta-giusta vecchio' }, h('p', null, d.risposta)));
      } else {
        const blocco = h('div', { class: 'correzione' });
        if (c.offline) blocco.append(A.messaggio('avviso', 'Senza rete: la risposta resta in coda e la correggo quando torna la rete.'));
        else if (c.incerta) blocco.append(A.messaggio('avviso', 'Il server ci ha messo troppo: non so se l\'ha corretta. Ricontrollo appena posso, senza fartela pagare due volte.'));
        else if (c.errore) blocco.append(A.messaggio('errore', c.errore));
        else {
          const e = L.esitoRisposta(c.esito);
          const parola = c.esito === 'saltata' ? 'Saltata · in Da ripassare' : e.parola;
          blocco.append(h('span', { class: 'esito grande ' + e.classe, 'data-esito': c.esito || '', stile: { alignSelf: 'flex-start' } }, c.esito === 'giusta' ? icona('spunta') : null, parola));
          if (c.commento) blocco.append(h('p', { class: 'commento' }, c.commento));
          if ((c.mancava || []).length) blocco.append(h('div', { class: 'mancava' }, h('span', { class: 'etichetta-m' }, 'Mancava:'), c.mancava.map((x) => h('span', { class: 'pillola' }, x))));
        }
        parti.push(blocco, h('div', { class: 'risposta-giusta' }, h('span', { class: 'etichetta' }, inAscolto ? 'Risposta giusta · in ascolto' : 'Risposta giusta'), h('p', null, attesa)));
      }
      parti.push(h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'avanti', onclick: avanti }, 'Avanti'));
    }
    const scheda = h('div', { class: 'scheda-domanda', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'testo-domanda' }, parti);
    if (!nuova) scheda.style.animation = 'none';
    svuota(velo, scheda);
    if (!nuova) velo.style.animation = 'none';
    if (q.fase === 'scrivi' && scritte && !q.conferma) {
      const c = document.getElementById('risposta-domanda');
      // Il cursore già nel campo (§7): con Win+H parte la dettatura di Windows.
      if (c) setTimeout(() => { if (c.isConnected) { c.focus(); c.setSelectionRange(c.value.length, c.value.length); } }, 30);
    } else {
      const f = scheda.querySelector('[data-azione="avanti"], [data-azione="senti-risposta"]');
      if (f && nuova) setTimeout(() => f.focus(), 30);
    }
    if (A.visitaDopoDisegno) A.visitaDopoDisegno();
  }

  // Per la visita guidata e le foto: un lettore d'esempio fermo alla domanda.
  A.lettoreFermoAllaDomanda = async function (lezioneId, domandaId) {
    await A.ascolta(lezioneId, { domandaId });
    if (P.audio && P.audio.finto) P.audio.pause();
  };
  A.lettoreStato = () => ({ id: P.id, fase: P.domanda ? P.domanda.fase : null, stato: P.fermate ? P.fermate.stato : null, tempo: adesso(), suona: !!(P.audio && !P.audio.paused), audio: P.audio });
})();
