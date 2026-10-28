/*
 * Lectio per il computer: la logica pura dell'interfaccia (niente pagina, niente rete).
 * Si prova con Node (pc/prove/js/logica.test.js): date e soldi in italiano, fasi della preparazione,
 * fermate alle domande del lettore, "vince il più recente" per il punto di ascolto, stime e tetto.
 */
(function (radice, fabbrica) {
  'use strict';
  const L = fabbrica();
  if (typeof module === 'object' && module.exports) module.exports = L;
  if (radice) radice.LectioLogica = L;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  const GIORNI = ['domenica', 'lunedì', 'martedì', 'mercoledì', 'giovedì', 'venerdì', 'sabato'];
  const MESI = ['gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno', 'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre'];
  /** §6: le durate che le app offrono (il server accetta 15-60). */
  const DURATE = [15, 20, 30, 45];
  /** §7: tocco sulla pillola della velocità = 0,8 / 1,0 / 1,2 / 1,4 / 1,6. */
  const VELOCITA = [0.8, 1.0, 1.2, 1.4, 1.6];
  /** §4: i colori delle materie, nell'ordine del contratto. */
  const COLORI = ['ambra', 'rosa', 'petrolio', 'viola', 'blu', 'verde', 'arancio', 'oliva'];
  const NOMI_COLORI = { ambra: 'Ambra', rosa: 'Rosa', petrolio: 'Petrolio', viola: 'Viola', blu: 'Blu', verde: 'Verde', arancio: 'Arancio', oliva: 'Oliva' };
  /** §6: i formati che il server legge. */
  const ESTENSIONI = ['.txt', '.md', '.pdf', '.docx', '.odt', '.rtf', '.jpg', '.jpeg', '.png', '.webp'];
  const IMMAGINI = ['.jpg', '.jpeg', '.png', '.webp'];
  const MASSIMO_BYTE = 20 * 1024 * 1024;
  const TIPI_MATERIALE = [
    { tipo: 'verifica', nome: 'Verifica corretta' },
    { tipo: 'interrogazione', nome: 'Interrogazione' },
    { tipo: 'appunti', nome: 'Appunti' },
    { tipo: 'libro', nome: 'Libro' },
  ];
  const NOMI_TIPO_MATERIALE = { verifica: 'Verifica corretta', interrogazione: 'Interrogazione', appunti: 'Appunti', libro: 'Libro', altro: 'Altro' };
  const NOMI_TIPI_DOMANDA = {
    aperta: 'Aperte', definizione: 'Definizioni', date: 'Date', esercizio: 'Esercizi', vero_falso: 'Vero o falso',
    scelta_multipla: 'Scelta multipla', collegamento: 'Collegamenti', traduzione: 'Traduzioni', altro: 'Altro',
  };
  /** §6 "In preparazione": quattro fasi con spunta; le fasi del server (§9) ci finiscono dentro così. */
  const FASI = [
    { chiavi: ['lettura', 'piano'], nome: 'Leggo il materiale' },
    { chiavi: ['scrittura'], nome: 'Scrivo la lezione' },
    { chiavi: ['voce'], nome: 'Registro la voce' },
    { chiavi: ['montaggio'], nome: 'Monto l\'audio' },
  ];

  // --- Numeri e soldi --------------------------------------------------------------------------

  function numero(n, decimali) {
    const v = Number(n) || 0;
    return v.toFixed(decimali).replace('.', ',');
  }

  /** 0.25 → "0,25 €" (sempre due decimali: sono soldi). */
  function euro(n) {
    return numero(n, 2) + ' €';
  }

  /** Il tetto e gli importi tondi: 2 → "2 €", 0.5 → "0,50 €". */
  function euroTondo(n) {
    const v = Math.round((Number(n) || 0) * 100) / 100;
    return Number.isInteger(v) ? v + ' €' : euro(v);
  }

  /** Il costo di una lezione: zero si scrive "0 €", il resto con i centesimi. */
  function euroCosto(n) {
    return (Number(n) || 0) === 0 ? '0 €' : euro(n);
  }

  /** 412000 → "412.000". */
  function migliaia(n) {
    return String(Math.round(Number(n) || 0)).replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  }

  function limita(x, min, max) {
    return Math.min(max, Math.max(min, x));
  }

  function percentuale(parte, totale) {
    const t = Number(totale) || 0;
    if (t <= 0) return 0;
    return limita(Math.round((Number(parte) || 0) / t * 100), 0, 100);
  }

  /** "12,4 MB" per le lezioni scaricate. */
  function dimensione(byte) {
    const b = Number(byte) || 0;
    if (b < 1024 * 1024) return numero(Math.max(0.1, b / 1024 / 1024), 1) + ' MB';
    return numero(b / 1024 / 1024, b >= 100 * 1024 * 1024 ? 0 : 1) + ' MB';
  }

  /**
   * La barra del mese a due toni (§6): speso e "questa lezione", in percentuale del tetto. Insieme non
   * superano il 100%: se la lezione sforerebbe, la parte di questa si ferma al bordo.
   */
  function barraMese(speso, questa, tetto) {
    const s = percentuale(speso, tetto);
    const q = limita(percentuale(Number(speso || 0) + Number(questa || 0), tetto) - s, 0, 100 - s);
    return { speso: s, questa: q };
  }

  /** §3: all'80% del tetto la riga diventa un avviso tenue. */
  function spesaInAvviso(spesa) {
    if (!spesa) return false;
    if (spesa.avviso === true) return true;
    const t = Number(spesa.tetto_eur) || 0;
    return t > 0 && Number(spesa.speso_eur || 0) >= 0.8 * t;
  }

  // --- Date ------------------------------------------------------------------------------------

  /** "2026-10-30" (o una data ISO con l'ora) → Date a mezzanotte, nell'ora locale. */
  function data(testo) {
    if (!testo) return null;
    if (testo instanceof Date) return new Date(testo.getFullYear(), testo.getMonth(), testo.getDate());
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(testo));
    if (!m) return null;
    return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  }

  /** "giovedì 30 ottobre". */
  function dataLunga(testo) {
    const d = data(testo);
    if (!d) return '';
    return GIORNI[d.getDay()] + ' ' + d.getDate() + ' ' + MESI[d.getMonth()];
  }

  /** "30 ottobre". */
  function dataBreve(testo) {
    const d = data(testo);
    if (!d) return '';
    return d.getDate() + ' ' + MESI[d.getMonth()];
  }

  /** Oggi (o una data) come "YYYY-MM-DD" nell'ora locale. */
  function iso(d) {
    const x = d || new Date();
    const due = (n) => String(n).padStart(2, '0');
    return x.getFullYear() + '-' + due(x.getMonth() + 1) + '-' + due(x.getDate());
  }

  function giorniFra(da, a) {
    const x = data(da);
    const y = data(a);
    if (!x || !y) return null;
    return Math.round((y - x) / 86400000);
  }

  /** "oggi", "domani", "tra 2 giorni"; nel passato "ieri", "3 giorni fa". */
  function quando(giorni) {
    const g = Number(giorni);
    if (!Number.isFinite(g)) return '';
    if (g === 0) return 'oggi';
    if (g === 1) return 'domani';
    if (g === -1) return 'ieri';
    if (g < 0) return (-g) + ' giorni fa';
    return 'tra ' + g + ' giorni';
  }

  /** "Si azzera il 1° novembre". */
  function siAzzera(testo) {
    const d = data(testo);
    if (!d) return '';
    return 'Si azzera il ' + d.getDate() + (d.getDate() === 1 ? '°' : '') + ' ' + MESI[d.getMonth()];
  }

  // --- Tempi del lettore -----------------------------------------------------------------------

  /** 462 → "07:42"; oltre l'ora "1:02:03". Cifre tabellari nella pagina. */
  function tempo(secondi) {
    const s = Math.max(0, Math.floor(Number(secondi) || 0));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const r = s % 60;
    const due = (n) => String(n).padStart(2, '0');
    return h > 0 ? h + ':' + due(m) + ':' + due(r) : due(m) + ':' + due(r);
  }

  /** Il tempo che manca col segno meno: "-12:05". */
  function meno(secondi) {
    return '-' + tempo(Math.ceil(Math.max(0, Number(secondi) || 0)));
  }

  /** La durata di un capitolo: 301 → "5:01". */
  function durataBreve(secondi) {
    const s = Math.max(0, Math.round(Number(secondi) || 0));
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  }

  /** "mancano 8 min" (almeno 1). */
  function mancano(durata, posizione) {
    const m = Math.max(1, Math.round((Math.max(0, (Number(durata) || 0) - (Number(posizione) || 0))) / 60));
    return 'mancano ' + m + ' min';
  }

  function etichettaVelocita(v) {
    return numero(v || 1, 1) + '×';
  }

  /** La velocità dopo un clic sulla pillola: 0,8 → 1,0 → … → 1,6 → 0,8. */
  function prossimaVelocita(v) {
    const attuale = Number(v) || 1;
    const i = VELOCITA.findIndex((x) => Math.abs(x - attuale) < 0.01);
    return VELOCITA[(i + 1) % VELOCITA.length];
  }

  /** La durata predefinita delle impostazioni, portata alla più vicina tra 15/20/30/45. */
  function durataVicina(d) {
    const v = Number(d) || 20;
    return DURATE.reduce((meglio, x) => (Math.abs(x - v) < Math.abs(meglio - v) ? x : meglio), DURATE[0]);
  }

  // --- Lezioni ---------------------------------------------------------------------------------

  /** "20 min · 4 capitoli · 6 domande" (senza dettagli, solo la durata). */
  function riassuntoLezione(lez) {
    if (!lez) return '';
    const parti = [(lez.durata_min || Math.round((lez.durata_s || 0) / 60)) + ' min'];
    if (Array.isArray(lez.capitoli)) parti.push(lez.capitoli.length + (lez.capitoli.length === 1 ? ' capitolo' : ' capitoli'));
    if (Array.isArray(lez.domande)) parti.push(lez.domande.length + (lez.domande.length === 1 ? ' domanda' : ' domande'));
    return parti.join(' · ');
  }

  /** Lo stato di una lezione in elenco: pronta, a metà (con la barra), finita, in preparazione, in coda, errore. */
  function statoLezione(lez) {
    if (!lez) return { tipo: 'pronta', frase: '' };
    const av = lez.avanzamento || {};
    switch (lez.stato) {
      case 'in_coda': return { tipo: 'coda', frase: 'In coda' };
      case 'in_preparazione': return { tipo: 'preparazione', frase: av.frase || 'In preparazione' };
      case 'errore': return { tipo: 'errore', frase: 'Non riuscita' };
      case 'annullata': return { tipo: 'annullata', frase: 'Annullata' };
      default: break;
    }
    const a = lez.ascolto || {};
    if (a.finita) return { tipo: 'finita', frase: 'Finita' };
    const durata = lez.durata_s || (lez.durata_min || 0) * 60;
    if (a.posizione_s > 5 && durata > 0) {
      return { tipo: 'meta', frase: mancano(durata, a.posizione_s), percentuale: percentuale(a.posizione_s, durata) };
    }
    return { tipo: 'pronta', frase: 'Pronta' };
  }

  /**
   * Le quattro fasi di "In preparazione" con spunta (fatto), in corso o da fare, e la barra.
   * Con l'errore, la fase dove si è fermata è segnata "errore".
   */
  function fasi(lez) {
    const stato = (lez && lez.stato) || 'in_coda';
    const av = (lez && lez.avanzamento) || {};
    let indice = FASI.findIndex((f) => f.chiavi.includes(av.fase));
    if (stato === 'pronta') indice = FASI.length;
    else if (stato === 'in_coda') indice = -1;
    else if (indice < 0) indice = 0;
    const righe = FASI.map((f, i) => {
      let nome = f.nome;
      if (i === 2 && i === indice && Number(av.totale) > 0) nome = 'Registro la voce · ' + (Number(av.fatto) || 0) + ' di ' + Number(av.totale);
      let s = i < indice ? 'fatto' : i === indice ? 'corso' : 'dafare';
      if (stato === 'errore' && i === indice) s = 'errore';
      return { nome, stato: s };
    });
    let frazione = 0;
    if (indice >= 0 && indice < FASI.length && Number(av.totale) > 0) frazione = limita((Number(av.fatto) || 0) / Number(av.totale), 0, 1);
    const pct = stato === 'pronta' ? 100 : indice < 0 ? 0 : Math.round((indice + frazione) / FASI.length * 100);
    return { righe, percentuale: pct, inCoda: stato === 'in_coda', pronta: stato === 'pronta', errore: stato === 'errore' };
  }

  /** Stato di ogni capitolo al tempo t: fatto (spunta), corso (accento), dafare. */
  function capitoliStato(capitoli, t) {
    const x = Number(t) || 0;
    return (capitoli || []).map((c, i, tutti) => {
      const ultimo = i === tutti.length - 1;
      if (x >= c.fine_s && !ultimo) return 'fatto';
      if (x >= c.inizio_s && (x < c.fine_s || ultimo)) return 'corso';
      return x >= c.fine_s ? 'fatto' : 'dafare';
    });
  }

  function capitoloA(capitoli, t) {
    const stati = capitoliStato(capitoli, t);
    const i = stati.indexOf('corso');
    return i >= 0 ? i : 0;
  }

  /** "DOMANDA 2 DI 6"; quelle a fine blocco "DOMANDA DEL BLOCCO · 2 DI 6". */
  function etichettaDomanda(d) {
    if (!d) return '';
    const conta = d.n && d.di ? d.n + ' DI ' + d.di : '';
    if (d.tipo === 'blocco') return 'DOMANDA DEL BLOCCO' + (conta ? ' · ' + conta : '');
    return 'DOMANDA' + (conta ? ' ' + conta : '');
  }

  /**
   * "Vince il più recente" (§9): tra il punto salvato sul server e quello tenuto sul computer vale quello con
   * aggiornato_il più recente. A pari data vale il server.
   */
  function piuRecente(server, locale) {
    if (!server) return locale || null;
    if (!locale) return server;
    const ts = Date.parse(server.aggiornato_il || '') || 0;
    const tl = Date.parse(locale.aggiornato_il || '') || 0;
    return tl > ts ? locale : server;
  }

  /**
   * La domanda dentro cui cade un punto (tra inizio_s e inizio_risposta_s): riprendendo da lì la domanda si riapre,
   * invece di saltarla senza risposta.
   */
  function domandaAperta(domande, t) {
    const x = Number(t);
    if (!Number.isFinite(x)) return null;
    return (domande || []).find((d) => x >= d.inizio_s - 0.05 && x < d.inizio_risposta_s) || null;
  }

  /** Il punto da cui ripartire: dall'ascolto più recente; una lezione finita riparte dall'inizio. */
  function puntoDiPartenza(ascolto, durata) {
    if (!ascolto || ascolto.finita) return 0;
    const p = Number(ascolto.posizione_s) || 0;
    if (durata && p >= durata - 2) return 0;
    return Math.max(0, p);
  }

  /**
   * Le fermate alle domande (§7, §15.2). Il lettore chiama osserva(t, velocità) mentre suona; se l'audio passa
   * fine_domanda_s ascoltando di fila (non con un salto), si ferma: 'domanda' (si scrive la risposta).
   * Invia → 'correzione' (il server corregge) → 'risposta' (parte l'audio della risposta giusta, da
   * inizio_risposta_s); Salta e "Senti la risposta" vanno subito a 'risposta'. Alla fine della risposta (fine_s)
   * si ferma di nuovo ('dopo') con "Avanti". "Ripeti la domanda" torna a inizio_s e la fermata scatta di nuovo.
   * Un salto non fa scattare niente.
   */
  function Fermate(domande, opzioni) {
    this.domande = (domande || []).slice().sort((a, b) => a.fine_domanda_s - b.fine_domanda_s);
    this.attive = !opzioni || opzioni.attive !== false;
    this.stato = 'ascolto';
    this.corrente = null;
    this.ultimo = null;
  }

  /** Quanto può avanzare l'audio tra due osservazioni per contare come ascolto di fila. */
  Fermate.limite = function (velocita) {
    return 1.5 * Math.max(1, Number(velocita) || 1) + 0.75;
  };

  Fermate.prototype.osserva = function (t, velocita) {
    const prima = this.ultimo;
    this.ultimo = t;
    if (prima === null || prima === undefined) return null;
    const delta = t - prima;
    if (delta <= 0 || delta > Fermate.limite(velocita)) return null;
    if (this.stato === 'risposta') {
      const d = this.corrente;
      if (d && prima < d.fine_s && t >= d.fine_s) {
        this.stato = 'dopo';
        return { tipo: 'fine_risposta', domanda: d };
      }
      return null;
    }
    if (this.stato !== 'ascolto' || !this.attive) return null;
    for (const d of this.domande) {
      if (prima < d.fine_domanda_s && t >= d.fine_domanda_s) {
        this.stato = 'domanda';
        this.corrente = d;
        return { tipo: 'domanda', domanda: d };
      }
    }
    return null;
  };

  /** Un salto (barra, capitolo, -15/+30): niente fermate per quello che si è saltato, e la scheda si chiude. */
  Fermate.prototype.salto = function (t) {
    this.ultimo = t;
    if (this.stato === 'risposta' && this.corrente && t >= this.corrente.inizio_risposta_s - 0.01 && t < this.corrente.fine_s) return;
    this.stato = 'ascolto';
    this.corrente = null;
  };

  /** Apre direttamente una domanda (da "Da ripassare", dalla visita guidata): lettore fermo, scheda aperta. */
  Fermate.prototype.apri = function (d) {
    if (!d) return null;
    this.stato = 'domanda';
    this.corrente = d;
    this.ultimo = d.fine_domanda_s;
    return d.fine_domanda_s;
  };

  /** Risposta inviata: si aspetta la correzione (l'audio resta fermo). */
  Fermate.prototype.inCorrezione = function () {
    if (this.stato === 'domanda' && this.corrente) this.stato = 'correzione';
    return this.stato === 'correzione';
  };

  /**
   * Parte la risposta giusta (dopo la correzione, con Salta o con "Senti la risposta"): restituisce il punto da
   * cui far ripartire l'audio.
   */
  Fermate.prototype.sentiRisposta = function () {
    if ((this.stato !== 'domanda' && this.stato !== 'correzione') || !this.corrente) return null;
    this.stato = 'risposta';
    this.ultimo = this.corrente.inizio_risposta_s;
    return this.corrente.inizio_risposta_s;
  };

  /** "Ripeti la domanda": torna a inizio_s; arrivati di nuovo alla fine della domanda ci si riferma. */
  Fermate.prototype.ripetiDomanda = function () {
    const d = this.corrente;
    if (!d) return null;
    this.stato = 'ascolto';
    this.corrente = null;
    this.ultimo = d.inizio_s;
    return d.inizio_s;
  };

  /** "Avanti" dopo la risposta: si continua da dove si è. */
  Fermate.prototype.avanti = function () {
    this.stato = 'ascolto';
    this.corrente = null;
  };

  Fermate.prototype.attiva = function (si) {
    this.attive = !!si;
  };

  /**
   * Cosa fa il tasto play (multimediale della tastiera, barra spaziatrice fuori dai campi) nel lettore (§7, §15.4):
   *  - mentre si scrive in un campo: niente (la barra spaziatrice scrive, i tasti non toccano l'audio);
   *  - alla domanda con "Risposte scritte" spento: 'senti' (la risposta);
   *  - alla domanda con la finestra davanti: 'fuoco' (il cursore va nel campo: si può scrivere);
   *  - alla domanda con la finestra dietro: domande saltabili → 'salta'; obbligatorie → 'ripeti' (la domanda
   *    si risente e l'audio resta fermo);
   *  - durante la correzione: niente; dopo la risposta: 'avanti'; altrimenti 'playpausa'.
   */
  function azioneTastoPlay(o) {
    const x = o || {};
    if (x.scrivendo) return 'niente';
    switch (x.stato) {
      case 'domanda':
        if (x.risposteScritte === false) return 'senti';
        if (x.finestraAttiva) return 'fuoco';
        return x.saltabili ? 'salta' : 'ripeti';
      case 'correzione': return 'niente';
      case 'dopo': return 'avanti';
      default: return 'playpausa';
    }
  }

  /**
   * Il salto alla domanda, secondo la scelta della lezione (§15.4): con domande saltabili un "Salta" normale;
   * con domande obbligatorie un "Salta comunque" piccolo che prima chiede conferma in linea.
   */
  function modoSalto(saltabili) {
    return saltabili
      ? { etichetta: 'Salta', conferma: false }
      : { etichetta: 'Salta comunque', conferma: true, domanda: 'Saltarla? Finisce in Da ripassare.', si: 'Sì, salta', no: 'Rispondo' };
  }

  /**
   * L'esito della correzione a parole e colore (§7): Giusta = verde, Quasi = ambra, Da rivedere = ardesia.
   * Mai rosso: non è un errore del programma.
   */
  function esitoRisposta(esito) {
    switch (esito) {
      case 'giusta': return { parola: 'Giusta', classe: 'giusta' };
      case 'parziale': return { parola: 'Quasi', classe: 'quasi' };
      case 'sbagliata': return { parola: 'Da rivedere', classe: 'rivedere' };
      case 'saltata': return { parola: 'Saltata', classe: 'rivedere' };
      case 'non_corretta': return { parola: 'Da confrontare', classe: 'rivedere' };
      default: return { parola: 'Da rivedere', classe: 'rivedere' };
    }
  }

  /** Una risposta scritta va bene da mandare: non vuota, al massimo 2000 caratteri (§15.2). */
  function rispostaValida(testo) {
    const t = String(testo || '').trim();
    return t.length > 0 && t.length <= 2000;
  }

  /**
   * §6 "Userò": "4 verifiche, 1 interrogazione e 3 appunti di Storia" dal materiale della materia. Le verifiche
   * e le interrogazioni si usano sempre; gli appunti e il libro solo quelli accesi (tutti, se non si è toccato Cambia).
   */
  /** Il materiale che una lezione può usare: solo quello già letto dal server (non in lettura, non in errore). */
  function prontoDaUsare(x) {
    return !!x && (x.stato == null || x.stato === 'pronto');
  }

  /**
   * I `materiali` da mandare (§15.1): null finché non si tocca Cambia (= tutti); dopo, le verifiche e interrogazioni
   * pronte più gli appunti/libri accesi e pronti. Un elenco vuoto il server lo rifiuta: la pagina lo blocca prima.
   */
  function materialiScelti(materiale, accesi) {
    if (accesi == null) return null;
    return (materiale || []).filter((x) => prontoDaUsare(x) && (['verifica', 'interrogazione'].includes(x.tipo) || accesi.includes(x.id))).map((x) => x.id);
  }

  function frasiUsero(materiale, nomeMateria, accesi) {
    const pronti = (materiale || []).filter(prontoDaUsare);
    const conta = (tipo) => pronti.filter((x) => x.tipo === tipo && (accesi == null || tipo === 'verifica' || tipo === 'interrogazione' || accesi.includes(x.id))).length;
    const parti = [];
    const v = conta('verifica');
    const i = conta('interrogazione');
    const a = conta('appunti') + conta('altro');
    const b = conta('libro');
    if (v) parti.push(plurale(v, 'verifica', 'verifiche'));
    if (i) parti.push(plurale(i, 'interrogazione', 'interrogazioni'));
    if (a) parti.push(plurale(a, 'appunto', 'appunti'));
    if (b) parti.push(plurale(b, 'libro', 'libri'));
    if (!parti.length) return 'Ancora niente materiale di ' + (nomeMateria || 'questa materia');
    const elenco = parti.length === 1 ? parti[0] : parti.slice(0, -1).join(', ') + ' e ' + parti[parti.length - 1];
    return elenco + ' di ' + (nomeMateria || 'questa materia');
  }

  /** I limiti della scheda del prof come li vuole il server (server/app/schemi.py, SchedaPut). */
  const LIMITI_SCHEDA = { argomento: 80, argomenti: 30, voce: 200, voci: 20, lunghezza: 200, note: 5000 };

  /**
   * La scheda da mandare col PUT: argomenti dalle righe (tenendo volte ed esempio di quelli che c'erano; un argomento
   * nuovo ha volte 0 ed esempio vuoto, mai null), elenchi dalle righe, il resto com'è.
   */
  function schedaDaMandare(sc, campi) {
    const vecchi = new Map(((sc && sc.argomenti) || []).map((a) => [String(a.nome || '').toLowerCase(), a]));
    return {
      argomenti: righe(campi.argomenti).map((n) => {
        const v = vecchi.get(n.toLowerCase()) || {};
        return { nome: n, volte: Number.isInteger(v.volte) ? v.volte : 0, esempio: typeof v.esempio === 'string' ? v.esempio : '' };
      }),
      tipi_domanda: (sc && sc.tipi_domanda) || [],
      cosa_corregge: righe(campi.corregge),
      cosa_premia: righe(campi.premia),
      lunghezza_risposte: String(campi.lunghezza || '').trim(),
      consigli: righe(campi.consigli),
      note_mie: String(campi.note || ''),
    };
  }

  /** null se la scheda va bene per il server, altrimenti la frase con cosa accorciare. */
  function controllaScheda(c) {
    const lungo = (elenco, max) => elenco.find((x) => x.length > max);
    if (c.argomenti.length > LIMITI_SCHEDA.argomenti) return 'Argomenti che tornano: al massimo ' + LIMITI_SCHEDA.argomenti + ' righe.';
    const a = c.argomenti.find((x) => x.nome.length > LIMITI_SCHEDA.argomento);
    if (a) return 'Argomenti che tornano: ogni riga al massimo ' + LIMITI_SCHEDA.argomento + ' caratteri ("' + a.nome.slice(0, 30) + '…").';
    for (const [campo, nome] of [['cosa_corregge', 'Cosa corregge'], ['cosa_premia', 'Cosa premia'], ['consigli', 'Consigli']]) {
      if (c[campo].length > LIMITI_SCHEDA.voci) return nome + ': al massimo ' + LIMITI_SCHEDA.voci + ' righe.';
      const x = lungo(c[campo], LIMITI_SCHEDA.voce);
      if (x) return nome + ': ogni riga al massimo ' + LIMITI_SCHEDA.voce + ' caratteri ("' + x.slice(0, 30) + '…").';
    }
    if (c.lunghezza_risposte.length > LIMITI_SCHEDA.lunghezza) return 'Quanto scrivere: al massimo ' + LIMITI_SCHEDA.lunghezza + ' caratteri.';
    if (c.note_mie.length > LIMITI_SCHEDA.note) return 'Le tue note: al massimo ' + migliaia(LIMITI_SCHEDA.note) + ' caratteri.';
    return null;
  }

  /** Il primo degli 8 colori non ancora usato da una materia (per "Nuova materia"). */
  function primoColoreLibero(materie) {
    const usati = new Set((materie || []).map((m) => m.colore));
    return COLORI.find((c) => !usati.has(c)) || COLORI[(materie || []).length % COLORI.length];
  }

  // --- Stime, voci e tetto ---------------------------------------------------------------------

  /**
   * Una sequenza per le richieste che si accavallano (le stime mentre si cambia durata): vale solo la
   * risposta dell'ultima richiesta partita, quelle arrivate dopo ma partite prima si buttano.
   */
  function creaSequenza() {
    let n = 0;
    return { nuova: () => ++n, valida: (x) => x === n };
  }

  function voceDi(voci, tipo) {
    return (voci || []).find((v) => v.tipo === tipo && v.disponibile !== false) || null;
  }

  /**
   * Le due schede della voce (§6) dalle stime: Lorenzo col costo della voce per questa durata, la voce gratis a
   * 0 € coi caratteri gratis rimasti. Se la voce espressiva sforerebbe il tetto, la sua scheda è spenta con
   * "Sforerebbe il tetto di 2 €". In modalità prova c'è solo la voce di prova, a 0.
   */
  function statoVoci(voci, stime, tetto) {
    const st = stime || {};
    const prova = voceDi(voci, 'prova');
    const frase = 'Sforerebbe il tetto di ' + euroTondo(tetto != null ? tetto : 2);
    if (prova) {
      return {
        prova: { voce: prova, costo: 0, stima: st.prova || null, spenta: false },
        espressiva: null, gratis: null,
      };
    }
    const espr = voceDi(voci, 'espressiva');
    const grat = voceDi(voci, 'gratis');
    const se = st.espressiva || null;
    const sg = st.gratis || null;
    return {
      prova: null,
      espressiva: {
        voce: espr,
        stima: se,
        costoVoce: se ? Number(se.costo_voce_eur || 0) : null,
        costo: se ? Number(se.costo_eur || 0) : null,
        spenta: !espr || (se ? se.consentita === false : false),
        motivo: !espr ? 'Non disponibile adesso' : se && se.consentita === false ? frase : null,
      },
      gratis: {
        voce: grat,
        stima: sg,
        costo: sg ? Number(sg.costo_eur || 0) : null,
        caratteri: sg && sg.voce_gratis_caratteri_rimasti != null ? Number(sg.voce_gratis_caratteri_rimasti) : null,
        spenta: !grat || (sg ? sg.consentita === false : false),
        motivo: !grat ? 'Non disponibile adesso' : sg && sg.consentita === false ? (sg.motivo || frase) : null,
      },
    };
  }

  /** La voce scelta davvero: quella voluta se si può, altrimenti l'altra; null se nessuna. */
  function sceltaVoce(voluta, stato) {
    if (!stato) return null;
    if (stato.prova) return 'prova';
    const ordine = voluta === 'gratis' ? ['gratis', 'espressiva'] : ['espressiva', 'gratis'];
    return ordine.find((k) => stato[k] && stato[k].voce && !stato[k].spenta) || null;
  }

  /** "Prepara la lezione · 0,25 €". */
  function etichettaPrepara(costo) {
    return costo == null ? 'Prepara la lezione' : 'Prepara la lezione · ' + euroCosto(costo);
  }

  // --- File e testi ----------------------------------------------------------------------------

  function estensione(nome) {
    const m = /\.[^.\\/]+$/.exec(String(nome || '').toLowerCase());
    return m ? m[0] : '';
  }

  /** null se il file va bene, altrimenti la frase del perché no. */
  function controllaFile(nome, byte) {
    const e = estensione(nome);
    if (e === '.heic' || e === '.heif') return 'Le foto HEIC non le leggo: salvala come JPG e riprova.';
    if (!ESTENSIONI.includes(e)) return 'Questo tipo di file non lo leggo. Usa PDF, Word, ODT, RTF, testo o una foto JPG, PNG o WEBP.';
    if (Number(byte) > MASSIMO_BYTE) return 'Il file è troppo grande: il massimo è 20 MB.';
    if (Number(byte) === 0) return 'Il file è vuoto.';
    return null;
  }

  function eImmagine(nome) {
    return IMMAGINI.includes(estensione(nome));
  }

  /** Il titolo predefinito da un nome di file: "verifica_storia-12.pdf" → "verifica storia 12". */
  function titoloDaFile(nome) {
    const senza = String(nome || '').replace(/\.[^.]+$/, '');
    return senza.replace(/[_-]+/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120) || 'Senza titolo';
  }

  /** Le righe non vuote di un testo (argomenti, cose che corregge, altre domande uscite). */
  function righe(testo) {
    return String(testo || '').split(/\r?\n/).map((r) => r.trim()).filter(Boolean);
  }

  /** §5: l'interrogazione orale scritta come testo per il server. */
  function testoInterrogazione(chiesto, risposto, detto) {
    const parti = [];
    if (String(chiesto || '').trim()) parti.push('COSA MI HA CHIESTO:\n' + String(chiesto).trim());
    if (String(risposto || '').trim()) parti.push('COSA HO RISPOSTO:\n' + String(risposto).trim());
    if (String(detto || '').trim()) parti.push('COSA HA DETTO IL PROF:\n' + String(detto).trim());
    return parti.join('\n\n');
  }

  function codiceValido(c) {
    return /^\d{8}$/.test(String(c || '').replace(/[\s-]/g, ''));
  }

  /** "Rossi" → "Prof. Rossi" (se c'è già "Prof" resta com'è). */
  function prof(nome) {
    const n = String(nome || '').trim();
    if (!n) return '';
    return /^prof/i.test(n) ? n : 'Prof. ' + n;
  }

  function plurale(n, uno, tanti) {
    return n + ' ' + (n === 1 ? uno : tanti);
  }

  /** "Prof. Rossi · 4 verifiche · centratura 63%". */
  function sottoMateria(m, lette) {
    const parti = [];
    if (m.prof) parti.push(prof(m.prof));
    const c = m.conteggi || {};
    parti.push(plurale(c.verifiche || 0, 'verifica', 'verifiche') + (lette ? ' lette' : ''));
    if (m.centratura && m.centratura.percentuale != null) parti.push('centratura ' + m.centratura.percentuale + '%');
    return parti.join(' · ');
  }

  /** "Basata su 4 verifiche e 1 interrogazione". */
  function basataSu(b) {
    if (!b) return '';
    const v = b.verifiche || 0;
    const i = b.interrogazioni || 0;
    if (!v && !i) return '';
    const parti = [];
    if (v) parti.push(plurale(v, 'verifica', 'verifiche'));
    if (i) parti.push(plurale(i, 'interrogazione', 'interrogazioni'));
    return 'Basata su ' + parti.join(' e ');
  }

  /** La rotta della pagina dal frammento: #oggi, #materia/3, #materia/3/scheda, #lettore/12, #racconta/4… */
  function leggiRotta(hash) {
    const parti = String(hash || '').replace(/^#\/?/, '').split('/').filter(Boolean);
    const id = (x) => (/^\d{1,12}$/.test(x || '') ? Number(x) : null);
    switch (parti[0]) {
      case 'materie': return { vista: 'materie' };
      case 'materia':
        if (id(parti[1]) == null) return { vista: 'materie' };
        return parti[2] === 'scheda' ? { vista: 'scheda', id: id(parti[1]) } : { vista: 'materia', id: id(parti[1]) };
      case 'lezioni': return { vista: 'lezioni', materia: id(parti[1]) };
      case 'gemini': return { vista: 'gemini' };
      case 'impostazioni': return { vista: 'impostazioni' };
      case 'lettore': return id(parti[1]) == null ? { vista: 'lezioni' } : { vista: 'lettore', id: id(parti[1]) };
      case 'lezione': return id(parti[1]) == null ? { vista: 'lezioni' } : { vista: 'lezione', id: id(parti[1]) };
      case 'racconta': return id(parti[1]) == null ? { vista: 'oggi' } : { vista: 'racconta', id: id(parti[1]) };
      default: return { vista: 'oggi' };
    }
  }

  return {
    GIORNI, MESI, DURATE, VELOCITA, COLORI, NOMI_COLORI, ESTENSIONI, MASSIMO_BYTE, TIPI_MATERIALE, NOMI_TIPO_MATERIALE, NOMI_TIPI_DOMANDA, FASI,
    numero, euro, euroTondo, euroCosto, migliaia, limita, percentuale, dimensione, barraMese, spesaInAvviso,
    data, dataLunga, dataBreve, iso, giorniFra, quando, siAzzera,
    tempo, meno, durataBreve, mancano, etichettaVelocita, prossimaVelocita, durataVicina,
    riassuntoLezione, statoLezione, fasi, capitoliStato, capitoloA, etichettaDomanda, piuRecente, puntoDiPartenza, Fermate,
    esitoRisposta, rispostaValida, frasiUsero, primoColoreLibero, azioneTastoPlay, modoSalto, domandaAperta, materialiScelti, prontoDaUsare,
    LIMITI_SCHEDA, schedaDaMandare, controllaScheda,
    creaSequenza, voceDi, statoVoci, sceltaVoce, etichettaPrepara,
    estensione, controllaFile, eImmagine, titoloDaFile, righe, testoInterrogazione, codiceValido, prof, plurale, sottoMateria, basataSu, leggiRotta,
  };
});
