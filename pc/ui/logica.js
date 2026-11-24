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
  /** §37.1: i 16 colori delle materie nell'ordine in cui si propone il primo libero. */
  const COLORI = ['ambra', 'rosa', 'petrolio', 'viola', 'blu', 'verde', 'arancio', 'oliva', 'giada', 'orchidea', 'senape', 'lavanda', 'cacao', 'prugna', 'salvia', 'lavagna'];
  /** §37.1: l'ordine a schermo, per tinta, così sembrano una sfumatura. */
  const ORDINE_COLORI = ['rosa', 'arancio', 'cacao', 'ambra', 'senape', 'oliva', 'salvia', 'verde', 'giada', 'petrolio', 'blu', 'lavagna', 'viola', 'lavanda', 'orchidea', 'prugna'];
  const NOMI_COLORI = {
    ambra: 'Ambra', rosa: 'Rosa', petrolio: 'Petrolio', viola: 'Viola', blu: 'Blu', verde: 'Verde', arancio: 'Arancio', oliva: 'Oliva',
    giada: 'Giada', senape: 'Senape', orchidea: 'Orchidea', prugna: 'Prugna', cacao: 'Cacao', lavagna: 'Lavagna', salvia: 'Salvia', lavanda: 'Lavanda',
  };
  /** §6: i formati che il server legge. */
  const ESTENSIONI = ['.txt', '.md', '.pdf', '.docx', '.odt', '.rtf', '.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif'];
  // HEIC (iPhone): le fa diventare JPEG il server (dalla 1.3.6).
  const IMMAGINI = ['.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif'];
  const MASSIMO_BYTE = 20 * 1024 * 1024;
  /**
   * §16.4 "Che cos'è" in due famiglie: Da studiare (Lectio lo spiega e ci fa le domande) e Già corretta dal prof
   * (Lectio impara come chiede e corregge). Foto e file partono da Appunti.
   */
  const FAMIGLIE_MATERIALE = [
    { chiave: 'studio', nome: 'Da studiare', frase: 'Lectio lo spiega e ci fa le domande', tipi: [{ tipo: 'appunti', nome: 'Appunti' }, { tipo: 'libro', nome: 'Libro' }] },
    { chiave: 'prof', nome: 'Già corretta dal prof', frase: 'Lectio impara come chiede e corregge', tipi: [{ tipo: 'verifica', nome: 'Verifica' }, { tipo: 'interrogazione', nome: 'Interrogazione' }] },
  ];
  const NOMI_TIPO_MATERIALE = { verifica: 'Verifica corretta', interrogazione: 'Interrogazione', appunti: 'Appunti', libro: 'Libro', altro: 'Altro' };
  /** Il nome breve del tipo nelle righe della Nuova lezione ("Appunti · foto · 4 novembre"). */
  const NOMI_TIPO_BREVE = { verifica: 'Verifica', interrogazione: 'Interrogazione', appunti: 'Appunti', libro: 'Libro', altro: 'Altro' };
  /** §16.1 I tre tipi di lezione, con la frase della loro scheda. */
  const TIPI_LEZIONE = [
    { tipo: 'spiegazione', nome: 'Spiegazione', frase: 'Te lo spiego dall\'inizio, con domande lungo la strada' },
    { tipo: 'ripasso', nome: 'Ripasso', frase: 'Più veloce: i punti chiave e tante domande alla fine' },
    { tipo: 'interrogazione', nome: 'Interrogazione', frase: 'Ti interrogo come il prof, poi ti dico la risposta giusta' },
  ];
  /** §6 Gli spunti a pillola: ognuno aggiunge o toglie una frase dalle indicazioni. */
  const SPUNTI = [
    { etichetta: 'Spiegalo da zero', frase: 'Spiegalo partendo da zero, come se non l\'avessi mai studiato.' },
    { etichetta: 'Insisti su date e nomi', frase: 'Insisti su date e nomi.' },
    { etichetta: 'Confronta gli autori', frase: 'Confronta gli autori tra loro: cosa cambia da uno all\'altro.' },
    { etichetta: 'Descrivi bene le opere', frase: 'Descrivi bene le opere, come se le avessi davanti.' },
    { etichetta: 'Domande più difficili', frase: 'Fammi domande più difficili di quelle del prof.' },
    { etichetta: 'Tanti esempi', frase: 'Usa tanti esempi concreti.' },
  ];
  /** §16.1 Le indicazioni: al massimo 600 caratteri. */
  const MASSIMO_INDICAZIONI = 600;
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

  /** Il materiale che una lezione può usare: solo quello già letto dal server (non in lettura, non in errore). */
  function prontoDaUsare(x) {
    return !!x && (x.stato == null || x.stato === 'pronto');
  }

  // --- Nuova lezione (§6, contratto §16) -------------------------------------------------------

  /** Minuscolo e senza accenti, per cercare nei titoli. */
  function normalizza(testo) {
    return String(testo || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  }

  /** Le domande alla fine di una spiegazione (contratto §9.1): 15→4, 20→5, 30→6, 45→8, altrimenti 3 + durata/10. */
  function finaliSpiegazione(durata) {
    const d = Number(durata) || 20;
    const tabella = { 15: 4, 20: 5, 30: 6, 45: 8 };
    return tabella[d] != null ? tabella[d] : 3 + Math.round(d / 10);
  }

  /**
   * Com'è fatta la lezione, in parole (contratto §16.1, la stessa regola di misure.struttura nel server):
   * spiegazione "4 parti, una domanda dopo ognuna e 5 alla fine"; ripasso "I punti chiave in breve e 8 domande alla
   * fine"; interrogazione "12 domande come le fa il prof, con la risposta giusta dopo ognuna".
   */
  function struttura(tipo, durata) {
    const d = Number(durata) || 20;
    const finali = finaliSpiegazione(d);
    if (tipo === 'ripasso') return 'I punti chiave in breve e ' + Math.round(finali * 1.6) + ' domande alla fine';
    if (tipo === 'interrogazione') return Math.round(d * 0.6) + ' domande come le fa il prof, con la risposta giusta dopo ognuna';
    const blocchi = Math.max(3, Math.round(d / 5));
    return blocchi + ' parti, una domanda dopo ognuna e ' + finali + ' alla fine';
  }

  /** "Spiegazione di 20 minuti". */
  function titoloLezione(tipo, durata) {
    const t = TIPI_LEZIONE.find((x) => x.tipo === tipo) || TIPI_LEZIONE[0];
    return t.nome + ' di ' + (Number(durata) || 20) + ' minuti';
  }

  /**
   * Uno spunto tocca le indicazioni: se la frase c'è la toglie, altrimenti la aggiunge in fondo. Se non ci sta nei
   * 600 caratteri non la aggiunge (ok: false), così nel testo non resta mai una frase a metà.
   */
  function alternaSpunto(testo, frase, massimo) {
    const max = massimo || MASSIMO_INDICAZIONI;
    const ora = String(testo || '');
    if (ora.includes(frase)) {
      return { testo: ora.replace(frase, '').replace(/[ \t]{2,}/g, ' ').replace(/ *\n */g, '\n').trim(), ok: true };
    }
    const base = ora.trim();
    const nuovo = (base ? base + ' ' : '') + frase;
    if (nuovo.length > max) return { testo: ora, ok: false };
    return { testo: nuovo, ok: true };
  }

  /** La riga INDICAZIONI del riepilogo: le prime 140 lettere, o quello che succede senza. */
  function riepilogoIndicazioni(testo) {
    const t = String(testo || '').trim();
    if (!t) return 'Nessuna: segue il materiale e la scheda del prof';
    return t.length > 140 ? t.slice(0, 140).trimEnd() + '…' : t;
  }

  /** La riga MATERIALE del riepilogo: "Mirone e il Discobolo", "2 materiali: A e B", "5 materiali: A, B e altri 3". */
  function riepilogoMateriale(titoli) {
    const t = (titoli || []).map((x) => x || 'Materiale');
    if (!t.length) return 'Scegli almeno un materiale';
    if (t.length === 1) return t[0];
    if (t.length === 2) return '2 materiali: ' + t[0] + ' e ' + t[1];
    const resto = t.length - 2;
    return t.length + ' materiali: ' + t[0] + ', ' + t[1] + (resto === 1 ? ' e un altro' : ' e altri ' + resto);
  }

  /** La riga PER del riepilogo: "La verifica di giovedì 29 ottobre · domande obbligatorie". */
  function riepilogoPer(evento, saltabili) {
    const per = evento ? (evento.tipo === 'orale' ? 'L\'interrogazione di ' : 'La verifica di ') + dataLunga(evento.data) : 'Nessuna data';
    return per + ' · domande ' + (saltabili ? 'che si possono saltare' : 'obbligatorie');
  }

  /** La pillola PER di un evento: "Interrogazione di lunedì 10 novembre". */
  function etichettaEvento(evento) {
    return (evento && evento.tipo === 'orale' ? 'Interrogazione di ' : 'Verifica di ') + dataLunga(evento && evento.data);
  }

  /** I due gruppi della sezione "Da quale materiale": appunti e libro / verifiche e interrogazioni. In errore: fuori. */
  function gruppiMateriale(materiale) {
    const visibili = (materiale || []).filter((x) => x && x.stato !== 'errore');
    return {
      studio: visibili.filter((x) => !['verifica', 'interrogazione'].includes(x.tipo)),
      prof: visibili.filter((x) => ['verifica', 'interrogazione'].includes(x.tipo)),
    };
  }

  /** Il nome della cartella di un materiale (null se è fuori o se la cartella non si conosce). */
  function nomeCartellaDi(x, cartelle) {
    if (!x || x.cartella_id == null) return null;
    const c = (cartelle || []).find((y) => y && y.id === x.cartella_id);
    return c ? c.nome : null;
  }

  /**
   * "Appunti · foto · 4 novembre" sotto il titolo di una riga del materiale. Con le cartelle (§42.7) il nome della
   * cartella va dopo il tipo: "Appunti · Arte greca · foto · 4 novembre".
   */
  function sottoMateriale(x, cartelle) {
    const parti = [NOMI_TIPO_BREVE[x.tipo] || 'Materiale'];
    const cartella = nomeCartellaDi(x, cartelle);
    if (cartella) parti.push(cartella);
    if (x.origine === 'foto') parti.push('foto');
    else if (x.origine === 'file') parti.push('file');
    else if (x.origine === 'scritto') parti.push('scritto');
    const d = dataBreve(x.data || x.creato_il);
    if (d) parti.push(d);
    return parti.join(' · ');
  }

  /** Filtra per titolo (senza badare a maiuscole e accenti); con le cartelle anche per il nome della cartella (§42.7). */
  function filtraPerTitolo(lista, cerca, cartelle) {
    const f = normalizza(cerca).trim();
    if (!f) return lista || [];
    return (lista || []).filter((x) => normalizza(x.titolo || x.nome_file || '').includes(f) || normalizza(nomeCartellaDi(x, cartelle) || '').includes(f));
  }

  // --- Cartelle (§42, contratto §27) -----------------------------------------------------------------

  /** "8 file", "1 file", "Vuota". */
  function contaFile(n) {
    const k = Number(n) || 0;
    return k ? k + ' file' : 'Vuota';
  }

  /** "2 messi ora da Lectio", "1 messo ora da Lectio", '' se nessuno. */
  function messiDaLectio(n) {
    const k = Number(n) || 0;
    if (!k) return '';
    return k === 1 ? '1 messo ora da Lectio' : k + ' messi ora da Lectio';
  }

  /** Il materiale fuori dalle cartelle (null = fuori) e quello dentro una cartella. */
  function materialeFuori(lista) {
    return (lista || []).filter((x) => x && x.cartella_id == null);
  }
  function materialeDentro(lista, cartellaId) {
    return (lista || []).filter((x) => x && x.cartella_id != null && x.cartella_id === cartellaId);
  }

  /**
   * "Metti dei file qui" (§42.4): tutto il materiale che non è già in questa cartella, in due gruppi: fuori dalle
   * cartelle e in altre cartelle (nell'ordine del server).
   */
  function gruppiMetti(lista, cartellaId) {
    const altro = (lista || []).filter((x) => x && x.cartella_id !== cartellaId);
    return { fuori: altro.filter((x) => x.cartella_id == null), altre: altro.filter((x) => x.cartella_id != null) };
  }

  /** Sotto una riga di "Metti dei file qui": "Appunti · 4 novembre" oppure "In Arte romana · Appunti". */
  function sottoMetti(x, cartelle) {
    const tipo = NOMI_TIPO_BREVE[x.tipo] || 'Materiale';
    const cartella = nomeCartellaDi(x, cartelle);
    if (x.cartella_id != null) return 'In ' + (cartella || 'un\'altra cartella') + ' · ' + tipo;
    const d = dataBreve(x.data || x.creato_il);
    return d ? tipo + ' · ' + d : tipo;
  }

  /** "Metti qui 1 file", "Metti qui 3 file". */
  function fraseMettiQui(n) {
    return 'Metti qui ' + (Number(n) || 0) + ' file';
  }

  /** La riga conferma dopo lo spostamento: "Fatto: 3 file in Arte greca". */
  function fraseFattoMetti(n, nome) {
    return 'Fatto: ' + (Number(n) || 0) + ' file in ' + nome;
  }

  /** L'articolo davanti a un numero, come si legge: "gli 8", "gli 11", "i 3", "i 20". */
  function articoloNumero(n) {
    const s = String(Number(n) || 0);
    return s.charAt(0) === '8' || s === '11' ? 'gli' : 'i';
  }

  /** §42.5 "Elimina anche gli 8 file" ("Elimina anche il file" se è uno solo). */
  function fraseEliminaConFile(n) {
    const k = Number(n) || 0;
    return k === 1 ? 'Elimina anche il file' : 'Elimina anche ' + articoloNumero(k) + ' ' + k + ' file';
  }

  /** "Dentro ci sono 8 file." / "Dentro c'è 1 file." */
  function fraseDentro(n) {
    const k = Number(n) || 0;
    return k === 1 ? 'Dentro c\'è 1 file.' : 'Dentro ci sono ' + k + ' file.';
  }

  /** Dopo l'eliminazione: "Cartella eliminata" o "Cartella e 8 file eliminati". */
  function fraseCartellaEliminata(fileEliminati) {
    const k = Number(fileEliminati) || 0;
    return k ? 'Cartella e ' + k + ' file eliminati' : 'Cartella eliminata';
  }

  /** Il nome di una cartella come lo vuole il server: spazi in più tolti, al massimo 40 caratteri. */
  function pulisciNomeCartella(t) {
    return String(t || '').replace(/\s+/g, ' ').trim();
  }

  /**
   * Le pillole CARTELLE della scelta del materiale (§42.7): una per cartella che ha materiale pronto, con gli id del
   * suo materiale pronto, nell'ordine delle cartelle (alfabetico, dal server).
   */
  function cartelleConPronti(materiale, cartelle) {
    const pronti = (materiale || []).filter(prontoDaUsare);
    return (cartelle || []).map((c) => ({ id: c.id, nome: c.nome, ids: pronti.filter((x) => x.cartella_id === c.id).map((x) => x.id) }))
      .filter((c) => c.ids.length > 0);
  }

  /** Una pillola cartella è scelta quando tutto il suo materiale pronto è spuntato. */
  function cartellaTuttaScelta(spuntati, ids) {
    return (ids || []).length > 0 && ids.every((id) => (spuntati || []).includes(id));
  }

  /** Il tocco su una pillola cartella: se era tutto spuntato lo toglie, altrimenti lo spunta tutto. */
  function alternaCartella(spuntati, ids) {
    const ora = (spuntati || []).slice();
    if (cartellaTuttaScelta(ora, ids)) return ora.filter((id) => !ids.includes(id));
    for (const id of ids || []) if (!ora.includes(id)) ora.push(id);
    return ora;
  }

  /**
   * Le caselle spuntate (§16.2): finché Andrea non tocca una casella valgono i suggeriti del server (se il server
   * non sa suggerire, tutto); dopo, la sua scelta. Sempre e solo materiale pronto, nell'ordine dell'elenco.
   */
  function materialiSpuntati(materiale, auto, suggeriti, manuali) {
    const pronti = (materiale || []).filter(prontoDaUsare);
    if (auto) {
      if (suggeriti == null) return pronti.map((x) => x.id);
      return pronti.filter((x) => suggeriti.includes(x.id)).map((x) => x.id);
    }
    const scelti = manuali || [];
    return pronti.filter((x) => scelti.includes(x.id)).map((x) => x.id);
  }

  // --- Un materiale: titolo e che cos'è (§5.1) ----------------------------------------------------

  function famigliaDi(tipo) {
    return FAMIGLIE_MATERIALE.find((f) => f.tipi.some((t) => t.tipo === tipo)) || FAMIGLIE_MATERIALE[0];
  }

  /** Il titolo di un materiale come lo vuole il server: spazi in più tolti. */
  function pulisciTitolo(t) {
    return String(t || '').replace(/\s+/g, ' ').trim();
  }

  /** Sotto il TITOLO: da dove viene. Niente se l'ha scelto Andrea. */
  function notaTitolo(x) {
    if (!x || x.titolo_da === 'utente' || !x.titolo_da) return null;
    const foto = x.origine === 'foto';
    const da = x.nome_file ? (foto ? 'Dalla foto ' : 'Dal file ') + x.nome_file : null;
    if (x.titolo_da === 'lectio') {
      const come = 'titolo scritto da Lectio leggendo ' + (foto ? 'la foto' : x.origine === 'scritto' ? 'gli appunti' : 'il file');
      return da ? da + ' · ' + come : come.charAt(0).toUpperCase() + come.slice(1);
    }
    return da;
  }

  /**
   * PATCH /api/materiale/{id}: solo i campi cambiati ({} se niente). `cartella_id` si manda solo se c'è in `ora` ed è
   * cambiata; null vuol dire fuori dalle cartelle e si manda davvero (contratto §27).
   */
  function campiCambiati(originale, ora) {
    const c = {};
    const t = pulisciTitolo(ora.titolo);
    if (ora.titolo != null && t !== pulisciTitolo(originale.titolo)) c.titolo = t;
    if (ora.tipo && ora.tipo !== originale.tipo) c.tipo = ora.tipo;
    if (Object.prototype.hasOwnProperty.call(ora, 'cartella_id')) {
      const prima = originale.cartella_id == null ? null : originale.cartella_id;
      const dopo = ora.cartella_id == null ? null : ora.cartella_id;
      if (prima !== dopo) c.cartella_id = dopo;
    }
    return c;
  }

  /** I limiti della scheda del prof come li vuole il server (server/app/schemi.py, SchedaPut). */
  const LIMITI_SCHEDA = { argomento: 80, argomenti: 30, voce: 200, voci: 20, lunghezza: 200, note: 5000 };

  /**
   * La scheda da mandare col PUT: argomenti dalle righe (tenendo volte ed esempio di quelli che c'erano; un argomento
   * nuovo ha volte 0 ed esempio vuoto, mai null), elenchi dalle righe, il resto com'è.
   * §5.1: con `campi.verifiche` / `campi.interrogazioni` (i campi della Modifica, testi) manda anche il modulo, intero.
   */
  function schedaDaMandare(sc, campi) {
    const vecchi = new Map(((sc && sc.argomenti) || []).map((a) => [String(a.nome || '').toLowerCase(), a]));
    const c = {
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
    if (campi.verifiche) c.verifiche = moduloDaCampi(CAMPI_VERIFICHE, campi.verifiche);
    if (campi.interrogazioni) c.interrogazioni = moduloDaCampi(CAMPI_INTERROGAZIONI, campi.interrogazioni);
    return c;
  }

  // ------------------------------------------------------------------ §5.1 Il modulo delle verifiche

  /**
   * I campi del modulo, nell'ordine in cui si mostrano: chiave, etichetta, forma ('frase' testo breve, 'voci' una per
   * riga, 'citazioni' una per riga copiate dai documenti) e il limite del server (contratto §5.1).
   */
  const CAMPI_VERIFICHE = [
    { chiave: 'struttura', nome: 'Com\'è fatta', forma: 'frase', max: 300 },
    { chiave: 'consegne', nome: 'Consegne', forma: 'citazioni', max: 300, voci: 6 },
    { chiave: 'punti', nome: 'Punti', forma: 'frase', max: 300 },
    { chiave: 'regole_punti', nome: 'Regole dei punti', forma: 'voci', max: 200, voci: 6 },
    { chiave: 'difficolta', nome: 'Difficoltà', forma: 'frase', max: 300 },
    { chiave: 'approfondimento', nome: 'Approfondimento', forma: 'frase', max: 300 },
    { chiave: 'cosa_da', nome: 'Cosa dà', forma: 'frase', max: 300 },
    { chiave: 'richieste', nome: 'Richieste', forma: 'voci', max: 200, voci: 6 },
  ];
  const CAMPI_INTERROGAZIONI = [
    { chiave: 'inizio', nome: 'Come comincia', forma: 'frase', max: 300 },
    { chiave: 'andamento', nome: 'Come va avanti', forma: 'frase', max: 300 },
    { chiave: 'durata', nome: 'Quanto dura', forma: 'frase', max: 300 },
    { chiave: 'profondita', nome: 'Approfondimento', forma: 'frase', max: 300 },
    { chiave: 'cosa_vuole', nome: 'Cosa vuole', forma: 'voci', max: 200, voci: 6 },
    { chiave: 'esempi', nome: 'Domande', forma: 'citazioni', max: 200, voci: 6 },
  ];
  const LIMITI_MODULO = { puntiMin: 1, puntiMax: 1000 };

  /** Un numero scritto da Andrea: "" → null, "18,5" → 18.5, una scritta non numerica → NaN (la ferma il controllo). */
  function numeroScritto(testo) {
    const t = String(testo == null ? '' : testo).trim().replace(/\s+/g, '');
    if (!t) return null;
    return /^\d+(?:[.,]\d+)?$/.test(t) ? Number(t.replace(',', '.')) : NaN;
  }

  function moduloDaCampi(campi, scritti) {
    const fuori = {};
    for (const c of campi) {
      const v = scritti[c.chiave];
      fuori[c.chiave] = c.forma === 'frase' ? String(v || '').replace(/\s+/g, ' ').trim() : righe(v);
    }
    if (campi === CAMPI_VERIFICHE) {
      fuori.punti_totali = numeroScritto(scritti.punti_totali);
      fuori.sufficienza = numeroScritto(scritti.sufficienza);
    }
    return fuori;
  }

  /** Il modulo come arriva dal server, sempre con tutti i campi (un server vecchio non lo manda: tutto vuoto). */
  function modulo(sc) {
    const pulisci = (campi, o) => {
      const fuori = {};
      for (const c of campi) {
        const v = (o || {})[c.chiave];
        fuori[c.chiave] = c.forma === 'frase' ? (typeof v === 'string' ? v.trim() : '') : (Array.isArray(v) ? v.map((x) => String(x || '').trim()).filter(Boolean) : []);
      }
      return fuori;
    };
    const v = pulisci(CAMPI_VERIFICHE, sc && sc.verifiche);
    const numeroO = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null);
    v.punti_totali = numeroO(sc && sc.verifiche && sc.verifiche.punti_totali);
    v.sufficienza = numeroO(sc && sc.verifiche && sc.verifiche.sufficienza);
    return { verifiche: v, interrogazioni: pulisci(CAMPI_INTERROGAZIONI, sc && sc.interrogazioni) };
  }

  /** "30 in tutto, sufficienza 18" (le parti che ci sono), o null. */
  function frasePuntiModulo(v) {
    const parti = [];
    if (v && v.punti_totali != null) parti.push(voto(v.punti_totali) + ' in tutto');
    if (v && v.sufficienza != null) parti.push('sufficienza ' + voto(v.sufficienza));
    return parti.length ? parti.join(', ') : null;
  }

  /**
   * Le righe da mostrare nella pagina della materia: [{ chiave, nome, voci: [testi], citazioni }], solo i campi pieni.
   * `parte` è 'verifiche' o 'interrogazioni'. Le citazioni vanno tra « ».
   */
  function righeModulo(sc, parte) {
    const mod = modulo(sc)[parte];
    const campi = parte === 'verifiche' ? CAMPI_VERIFICHE : CAMPI_INTERROGAZIONI;
    const fuori = [];
    for (const c of campi) {
      let voci = c.forma === 'frase' ? (mod[c.chiave] ? [mod[c.chiave]] : []) : mod[c.chiave];
      if (c.chiave === 'punti') {
        const numeri = frasePuntiModulo(mod);
        if (numeri) voci = [numeri].concat(voci);
      }
      if (c.forma === 'citazioni') voci = voci.map((x) => '«' + x.replace(/^[«"“]+|[»"”]+$/g, '') + '»');
      if (voci.length) fuori.push({ chiave: c.chiave, nome: c.nome, voci, citazioni: c.forma === 'citazioni' });
    }
    return fuori;
  }

  /** I testi per i campi della Modifica: liste una per riga, numeri all'italiana. */
  function campiModulo(sc) {
    const mod = modulo(sc);
    const testi = (campi, o) => Object.fromEntries(campi.map((c) => [c.chiave, c.forma === 'frase' ? o[c.chiave] : o[c.chiave].join('\n')]));
    const v = testi(CAMPI_VERIFICHE, mod.verifiche);
    v.punti_totali = mod.verifiche.punti_totali == null ? '' : voto(mod.verifiche.punti_totali);
    v.sufficienza = mod.verifiche.sufficienza == null ? '' : voto(mod.verifiche.sufficienza);
    return { verifiche: v, interrogazioni: testi(CAMPI_INTERROGAZIONI, mod.interrogazioni) };
  }

  function controllaModulo(campi, o) {
    for (const c of campi) {
      const v = o[c.chiave];
      if (c.forma === 'frase') {
        if (v.length > c.max) return c.nome + ': al massimo ' + c.max + ' caratteri.';
      } else {
        if (v.length > c.voci) return c.nome + ': al massimo ' + c.voci + ' righe.';
        const x = v.find((r) => r.length > c.max);
        if (x) return c.nome + ': ogni riga al massimo ' + c.max + ' caratteri ("' + x.slice(0, 30) + '…").';
      }
    }
    return null;
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
    if (c.verifiche) {
      const p = controllaModulo(CAMPI_VERIFICHE, c.verifiche);
      if (p) return p;
      const tot = c.verifiche.punti_totali;
      const suf = c.verifiche.sufficienza;
      if (tot != null && (!Number.isInteger(tot) || tot < LIMITI_MODULO.puntiMin || tot > LIMITI_MODULO.puntiMax)) {
        return 'Punti in tutto: un numero intero da ' + LIMITI_MODULO.puntiMin + ' a ' + migliaia(LIMITI_MODULO.puntiMax) + '.';
      }
      if (suf != null) {
        if (!Number.isFinite(suf) || suf <= 0) return 'Sufficienza: un numero più grande di 0.';
        if (tot == null) return 'Sufficienza: scrivi anche i punti in tutto.';
        if (suf >= tot) return 'Sufficienza: meno dei punti in tutto.';
      }
    }
    if (c.interrogazioni) {
      const p = controllaModulo(CAMPI_INTERROGAZIONI, c.interrogazioni);
      if (p) return p;
    }
    return null;
  }

  /** §37.1: la materia che usa già questo colore (la prima), per "già usato da Storia". */
  function coloreUsatoDa(colore, materie, tranneId) {
    return (materie || []).find((m) => m.colore === colore && m.id !== tranneId) || null;
  }

  /** Il primo dei 16 colori non ancora usato da una materia (per "Nuova materia"). */
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
    if (!ESTENSIONI.includes(e)) return 'Questo tipo di file non lo leggo. Usa PDF, Word, ODT, RTF, testo o una foto JPG, PNG, WEBP o HEIC.';
    if (Number(byte) > MASSIMO_BYTE) return 'Il file è troppo grande: il massimo è 20 MB.';
    if (Number(byte) === 0) return 'Il file è vuoto.';
    return null;
  }

  function eImmagine(nome) {
    return IMMAGINI.includes(estensione(nome));
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
        if (parti[2] === 'scheda') return { vista: 'scheda', id: id(parti[1]) };
        // §42.2: la pagina di una cartella sta dentro la sua materia (#materia/1/cartella/3).
        if (parti[2] === 'cartella' && id(parti[3]) != null) return { vista: 'cartella', id: id(parti[1]), cartella: id(parti[3]) };
        // §31.2: da un avviso si parte dal segmento giusto (Schemi o Simulate).
        if (parti[2] === 'schemi' || parti[2] === 'simulate') return { vista: 'materia', id: id(parti[1]), segmento: parti[2] };
        return { vista: 'materia', id: id(parti[1]) };
      case 'lezioni': return { vista: 'lezioni', materia: id(parti[1]) };
      case 'gemini': return { vista: 'gemini' };
      case 'impostazioni': return { vista: 'impostazioni' };
      case 'lettore': return id(parti[1]) == null ? { vista: 'lezioni' } : { vista: 'lettore', id: id(parti[1]) };
      case 'lezione': return id(parti[1]) == null ? { vista: 'lezioni' } : { vista: 'lezione', id: id(parti[1]) };
      case 'racconta': return id(parti[1]) == null ? { vista: 'oggi' } : { vista: 'racconta', id: id(parti[1]) };
      case 'schema': return id(parti[1]) == null ? { vista: 'materie' } : { vista: 'schema', id: id(parti[1]) };
      case 'simulata': return id(parti[1]) == null ? { vista: 'materie' } : { vista: 'simulata', id: id(parti[1]) };
      case 'riassunto': return id(parti[1]) == null ? { vista: 'materie' } : { vista: 'riassunto', id: id(parti[1]) };
      case 'mappa': return id(parti[1]) == null ? { vista: 'materie' } : { vista: 'mappa', id: id(parti[1]) };
      default: return { vista: 'oggi' };
    }
  }

  /** §16.5: "pronta in circa N min". Dalla stima del server se c'è, altrimenti 2 + durata × minuti_per_minuto
   *  (0,5 in su). null se non si sa (server vecchi, voci senza tempo). */
  function prontaIn(voce, durata, stima) {
    if (stima && stima.minuti_preparazione != null) return Number(stima.minuti_preparazione);
    if (voce && voce.minuti_per_minuto != null) return Math.floor(2 + Number(durata) * Number(voce.minuti_per_minuto) + 0.5);
    return null;
  }

  // --- Schemi (contratto §18, guida §32-33) ------------------------------------------------------

  /** §32 Le tre forme: scheda di Nuovo schema, riga del riepilogo, nome nelle righe e nella testata. */
  const FORME_SCHEMA = [
    { forma: 'auto', nome: 'Lo decide Lectio', frase: 'Scelgo io la forma adatta a questo argomento', riepilogo: 'La forma la scelgo io', breve: 'Lo decide Lectio' },
    { forma: 'capitoli', nome: 'A capitoli', frase: 'Una parte dopo l\'altra, dall\'inizio alla fine', riepilogo: 'A capitoli', breve: 'A capitoli' },
    { forma: 'schede', nome: 'Schede per opera o autore', frase: 'Una scheda per ogni opera o autore: dati, descrizione, analisi', riepilogo: 'Schede per opera o autore', breve: 'Schede per opera' },
  ];
  const LIVELLI_SCHEMA = [
    { livello: 'essenziale', nome: 'Essenziale' },
    { livello: 'completo', nome: 'Completo' },
    { livello: 'approfondito', nome: 'Approfondito' },
  ];
  /** §32 Gli spunti di Nuovo schema: ognuno aggiunge o toglie una frase dalle indicazioni. */
  const SPUNTI_SCHEMA = [
    { etichetta: 'Tante immagini', frase: 'Metti tante immagini, quando aiutano a capire.' },
    { etichetta: 'Spiegalo da zero', frase: 'Spiegalo partendo da zero, come se non l\'avessi mai studiato.' },
    { etichetta: 'Insisti su date e nomi', frase: 'Insisti su date e nomi.' },
    { etichetta: 'Confronta gli autori', frase: 'Confronta gli autori tra loro: cosa cambia da uno all\'altro.' },
    { etichetta: 'Descrivi bene le opere', frase: 'Descrivi bene le opere, come se le avessi davanti.' },
    { etichetta: 'Tanti esempi', frase: 'Usa tanti esempi concreti.' },
    { etichetta: 'Segui il prof', frase: 'Dai più spazio a quello che il prof chiede di solito.' },
  ];
  /** Contratto §18: i blocchi che le app sanno disegnare. Gli altri si saltano. */
  const TIPI_BLOCCO = ['paragrafo', 'punti', 'passaggi', 'definizione', 'tabella', 'prof', 'immagine'];
  /** §33.5: i blocchi con "Non ho capito". */
  const BLOCCHI_CHIARIBILI = ['paragrafo', 'punti', 'passaggi', 'definizione', 'prof'];

  /**
   * "Il **Discobolo** è…" → [{ t: 'Il ', forte: false }, { t: 'Discobolo', forte: true }, { t: ' è…', forte: false }].
   * Nel testo c'è solo il grassetto (contratto §18). Due asterischi rimasti spaiati non si fanno vedere.
   */
  function pezziGrassetto(testo) {
    const s = String(testo == null ? '' : testo);
    const pezzi = [];
    const re = /\*\*([\s\S]+?)\*\*/g;
    let dove = 0;
    let m;
    const normale = (x) => { const t = x.replace(/\*\*/g, ''); if (t) pezzi.push({ t, forte: false }); };
    while ((m = re.exec(s))) {
      normale(s.slice(dove, m.index));
      pezzi.push({ t: m[1], forte: true });
      dove = m.index + m[0].length;
    }
    normale(s.slice(dove));
    return pezzi;
  }

  function senzaGrassetto(testo) {
    return pezziGrassetto(testo).map((p) => p.t).join('');
  }

  /** I blocchi di una parte che si sanno disegnare, nell'ordine in cui arrivano. */
  function blocchiNoti(sezione) {
    return ((sezione && sezione.blocchi) || []).filter((b) => b && TIPI_BLOCCO.includes(b.tipo));
  }

  /** §34: il testo del blocco, com'è il `passaggio` da mandare (le voci una per riga, niente asterischi). */
  function passaggioDelBlocco(b) {
    if (!b) return '';
    if (b.tipo === 'punti' || b.tipo === 'passaggi') return (b.voci || []).map(senzaGrassetto).join('\n');
    if (b.tipo === 'definizione') return senzaGrassetto(b.termine) + ': ' + senzaGrassetto(b.testo);
    return senzaGrassetto(b.testo);
  }

  /** Le fonti di un blocco (contratto §17.2): dagli id agli oggetti, senza doppioni e senza id che non si conoscono. */
  function fontiDelBlocco(b, mappa) {
    const viste = new Set();
    const fuori = [];
    for (const id of (b && b.fonti) || []) {
      const f = mappa && mappa[id];
      if (!f || !f.etichetta || viste.has(f.etichetta)) continue;
      viste.add(f.etichetta);
      fuori.push(f);
    }
    return fuori;
  }

  /** §33.4 "Libro, pag. 42 · Verifica del 12/10", al massimo due, poi "+1". */
  function segnoFonti(fonti) {
    const e = (fonti || []).map((f) => f && f.etichetta).filter(Boolean);
    if (!e.length) return '';
    const primi = e.slice(0, 2).join(' · ');
    return e.length > 2 ? primi + ' +' + (e.length - 2) : primi;
  }

  /** §33.4: per ogni blocco le fonti da segnare; blocchi di fila con le stesse fonti hanno il segno solo sull'ultimo. */
  function segniDeiBlocchi(blocchi, mappa) {
    const chiavi = (blocchi || []).map((b) => fontiDelBlocco(b, mappa).map((f) => f.etichetta).join('|'));
    return (blocchi || []).map((b, i) => (chiavi[i] && chiavi[i] !== chiavi[i + 1] ? fontiDelBlocco(b, mappa) : null));
  }

  /** §33.3 Scheda opera: la prima immagine sta con i campi in testa; il resto segue. */
  function testaOpera(sezione) {
    const blocchi = blocchiNoti(sezione);
    const campi = (sezione && sezione.campi) || [];
    if (!campi.length) return { foto: null, campi: [], blocchi };
    const foto = blocchi.find((b) => b.tipo === 'immagine') || null;
    return { foto, campi, blocchi: blocchi.filter((b) => b !== foto) };
  }

  /** Le parole di tutto lo schema, per "12 min di lettura" (circa 180 parole al minuto, da studio). */
  function minutiLettura(contenuto) {
    let parole = 0;
    const conta = (t) => { parole += String(t || '').trim().split(/\s+/).filter(Boolean).length; };
    for (const s of (contenuto && contenuto.sezioni) || []) {
      conta(s.titolo);
      for (const b of blocchiNoti(s)) {
        conta(b.titolo); conta(b.testo); conta(b.termine); conta(b.didascalia);
        (b.voci || []).forEach(conta);
        (b.righe || []).forEach((r) => r.forEach(conta));
      }
    }
    return Math.max(1, Math.ceil(parole / 180));
  }

  function nomeForma(forma) {
    const f = FORME_SCHEMA.find((x) => x.forma === forma);
    return f && f.forma !== 'auto' ? f.breve : '';
  }

  function nomeLivello(livello) {
    const l = LIVELLI_SCHEMA.find((x) => x.livello === livello);
    return l ? l.nome : 'Completo';
  }

  /** §33.2 "A capitoli · Completo · 8 parti · 12 min di lettura" (nell'elenco, senza contenuto, solo le prime due). */
  function riassuntoSchema(s) {
    if (!s) return '';
    const c = s.contenuto;
    const parti = [];
    const forma = nomeForma((c && c.forma) || s.forma);
    if (forma) parti.push(forma);
    parti.push(nomeLivello(s.livello));
    if (c && Array.isArray(c.sezioni) && c.sezioni.length) {
      parti.push(plurale(c.sezioni.length, 'parte', 'parti'));
      parti.push(minutiLettura(c) + ' min di lettura');
    }
    return parti.join(' · ');
  }

  /** Quanto è avanti un lavoro in corso (`fatto` di `totale`), in percentuale. */
  function percentualeLavoro(x) {
    return x && Number(x.totale) > 0 ? percentuale(x.fatto, x.totale) : 0;
  }

  /** §31.2 Riga di uno schema, secondo lo stato. */
  function statoRigaSchema(s) {
    switch (s && s.stato) {
      case 'in_coda': return { tipo: 'coda', frase: 'In coda' };
      case 'in_preparazione': return { tipo: 'lavoro', frase: s.frase || 'In preparazione', percentuale: percentualeLavoro(s) };
      case 'errore': return { tipo: 'errore', frase: 'Non è riuscito' };
      case 'annullato': return { tipo: 'annullato', frase: 'Annullato' };
      default: return { tipo: 'pronto', frase: riassuntoSchema(Object.assign({}, s, { contenuto: null })) };
    }
  }

  /** §33.7: cosa dire di quello che manca. null se è tutto a posto (la scheda non esiste). */
  function mancanzeSchema(s) {
    const c = (s && s.contenuto) || {};
    const mancanti = ((c.copertura && c.copertura.mancanti) || []).filter(Boolean);
    const avvisi = (c.avvisi || []).filter(Boolean);
    const nota = (s && s.nota) || null;
    if (!nota && !mancanti.length && !avvisi.length) return null;
    return { nota, mancanti: mancanti.slice(0, 5), altri: Math.max(0, mancanti.length - 5), avvisi };
  }

  /** Il nome del file da proporre per un PDF: niente caratteri che Windows non vuole. */
  function nomeFilePdf(titolo, riserva) {
    const n = String(titolo || '').replace(/[<>:"/\\|?*\u0000-\u001f]+/g, ' ').replace(/\s+/g, ' ').replace(/[. ]+$/g, '').trim().slice(0, 80).trim();
    return (n || riserva || 'Lectio') + '.pdf';
  }

  // --- Riassunti scritti (contratto §26, guida §41) -----------------------------------------------

  /** §41.2 Le tre forme, nell'ordine in cui si vedono. */
  const FORME_RIASSUNTO = [
    { forma: 'punti', nome: 'Per punti', frase: 'Una cosa per riga' },
    { forma: 'discorsivo', nome: 'Discorsivo', frase: 'Da leggere di seguito' },
    { forma: 'misto', nome: 'Via di mezzo', frase: 'Un po\' di testo e i punti da ricordare' },
  ];

  function riassuntoDellaForma(fatti, forma) {
    return (fatti || []).find((x) => x.forma === forma) || null;
  }

  /** §41.2 A destra di ogni riga del foglio: il prezzo, oppure a che punto è quella forma. */
  function statoFormaRiassunto(fatto, stima, prova) {
    if (fatto) {
      if (fatto.stato === 'pronto') return { tipo: 'pronto', testo: 'Pronto' };
      if (fatto.stato === 'errore') return { tipo: 'errore', testo: 'Si è fermato' };
      return { tipo: 'lavoro', testo: 'Lo sto scrivendo' };
    }
    return { tipo: 'prezzo', testo: prova ? 'Prova' : euro(stima) };
  }

  /** §41.3 "480 parole · 3 min di lettura" (minuti = parole / 200, almeno 1). */
  function fraseParole(parole) {
    const n = Number(parole) || 0;
    return plurale(n, 'parola', 'parole') + ' · ' + Math.max(1, Math.ceil(n / 200)) + ' min di lettura';
  }

  /** §41.1 L'ultimo riassunto fatto di uno schema: quello a cui porta "Apri il riassunto". */
  function ultimoRiassunto(fatti) {
    return (fatti || []).reduce((ultimo, x) => (!ultimo || Number(x.id) > Number(ultimo.id) ? x : ultimo), null);
  }

  // --- Mappe (contratto §29, guida §45) ------------------------------------------------------------

  function mappaDelTipo(fatte, tipo) {
    return (fatte || []).find((x) => x.tipo === tipo) || null;
  }

  /**
   * §45.2 A destra di ogni riga di "Che mappa vuoi?": il prezzo, oppure a che punto è quella mappa. `possibile` = false
   * se nello schema ci sono troppe poche date (la riga è spenta e lo dice).
   */
  function statoTipoMappa(fatta, stima, prova, possibile) {
    if (fatta) {
      if (fatta.stato === 'pronta') return { tipo: 'pronta', testo: 'Pronta' };
      if (fatta.stato === 'errore') return { tipo: 'errore', testo: 'Si è fermata' };
      return { tipo: 'lavoro', testo: 'La sto disegnando' };
    }
    if (possibile === false) return { tipo: 'spenta', testo: null, frase: 'Poche date in questo schema' };
    return { tipo: 'prezzo', testo: prova ? 'Prova' : euro(stima) };
  }

  /** §45.1 L'ultima mappa fatta di uno schema: quella a cui porta "Apri la mappa". */
  function ultimaMappa(fatte) {
    return (fatte || []).reduce((ultima, x) => (!ultima || Number(x.id) > Number(ultima.id) ? x : ultima), null);
  }

  /**
   * Contratto §29.6 La mappa corretta a mano: dal `corretto` si prendono SOLO i testi (concetti: testo, frase, verbo,
   * spiegazione; collegamenti delle mappe di prima: verbo; date: data, cosa, spiegazione); ordine, legami e sezioni restano quelli di
   * `originale`. Il resto (anni, posizione sulla linea) lo ricalcola il server dalla data scritta.
   */
  function contenutoMappaCorretto(originale, corretto) {
    const fuori = copiaJson(originale) || {};
    const c = corretto || {};
    const testi = (lista, nuovi, campi) => (lista || []).forEach((x, i) => {
      const n = (nuovi || []).find((y) => y && y.id === x.id) || (nuovi || [])[i] || {};
      for (const k of campi) if (typeof n[k] === 'string' && (typeof x[k] === 'string' || x[k] == null)) x[k] = pulito(n[k]);
    });
    if (Array.isArray(fuori.concetti)) testi(fuori.concetti, c.concetti, ['testo', 'frase', 'verbo', 'spiegazione']);
    if (Array.isArray(fuori.collegamenti)) {
      fuori.collegamenti.forEach((k, i) => {
        const n = (c.collegamenti || [])[i];
        if (n && typeof n.verbo === 'string') k.verbo = pulito(n.verbo);
      });
    }
    if (Array.isArray(fuori.eventi)) testi(fuori.eventi, c.eventi, ['data', 'cosa', 'spiegazione']);
    if (typeof fuori.domanda === 'string' && typeof c.domanda === 'string') fuori.domanda = pulito(c.domanda);
    return fuori;
  }

  // --- "Non ho capito" (contratto §19, guida §34) -------------------------------------------------

  const SPUNTI_CHIARIMENTO = [
    { etichetta: 'Più semplice', frase: 'Spiegalo in modo più semplice.' },
    { etichetta: 'Un esempio', frase: 'Fammi un esempio.' },
    { etichetta: 'Una parola', frase: 'Cosa vuol dire questa parola?' },
  ];
  const MASSIMO_DOMANDA = 300;
  /** §34: il contatore del campo si vede solo da qui in su. */
  const CONTATORE_DOMANDA_DA = 260;

  /** "Costata meno di 1 cent", oppure il costo. */
  function costoChiarimento(costo) {
    const c = Number(costo) || 0;
    return c < 0.01 ? 'Costata meno di 1 cent' : 'Costata ' + euro(c);
  }

  /** "Al minuto 5:12 · La crisi del Trecento" (il maiuscolo lo mette l'aspetto). */
  function etichettaMinuto(secondi, capitolo) {
    return 'Al minuto ' + durataBreve(secondi) + (capitolo ? ' · ' + capitolo : '');
  }

  // --- Ripasso lampo (contratto §20, guida §35) ---------------------------------------------------

  /** §35.2 I tre esiti, nello stesso ordine dei tasti 1, 2, 3. */
  const ESITI_LAMPO = [
    { esito: 'non_sapevo', parola: 'Non lo sapevo', icona: 'ripeti', classe: 'rivedere', tasto: '1' },
    { esito: 'quasi', parola: 'Quasi', icona: 'quasi', classe: 'quasi', tasto: '2' },
    { esito: 'sapevo', parola: 'Lo sapevo', icona: 'spunta', classe: 'giusta', tasto: '3' },
  ];

  /** §31.2: i minuti di un ripasso della materia (20 secondi a scheda, arrotondati in su). */
  function minutiLampo(schede) {
    return Math.ceil((Number(schede) || 0) * 20 / 60);
  }

  /** "8 schede · circa 3 min", "1 scheda · circa 1 min". */
  function fraseLampo(schede, minuti) {
    return plurale(Number(schede) || 0, 'scheda', 'schede') + ' · circa ' + (minuti != null ? minuti : minutiLampo(schede)) + ' min';
  }

  /** §35.3 "12 schede in 3 minuti". */
  function fraseFineRipasso(schede, ms) {
    const minuti = Math.max(1, Math.ceil((Number(ms) || 0) / 60000));
    return plurale(Number(schede) || 0, 'scheda', 'schede') + ' in ' + plurale(minuti, 'minuto', 'minuti');
  }

  // --- Verifica simulata (contratto §21, guida §36) -----------------------------------------------

  /** §36.1 Le durate che si offrono (il server accetta 10-180). */
  const DURATE_SIMULATA = [15, 30, 50, 60];

  /** §36.4 L'esito di una domanda corretta: parola, colore, icona. Mai rosso. */
  function esitoSimulata(esito) {
    switch (esito) {
      case 'giusta': return { parola: 'Giusta', classe: 'giusta', icona: 'spunta' };
      case 'parziale': return { parola: 'Quasi', classe: 'quasi', icona: 'quasi' };
      case 'non_corretta': return { parola: 'Confronta tu', classe: 'confronta', icona: 'confronta' };
      default: return { parola: 'Da rivedere', classe: 'rivedere', icona: 'ripeti' };
    }
  }

  /** 6.5 → "6,5"; 7 → "7" ("circa" lo scrive la pagina). */
  function voto(v) {
    const x = Number(v);
    if (!Number.isFinite(x)) return '';
    return Number.isInteger(x) ? String(x) : numero(x, 1);
  }

  /** §31.2 Riga di una verifica simulata, secondo lo stato. */
  function statoRigaSimulata(s) {
    switch (s && s.stato) {
      case 'in_coda': return { tipo: 'coda', frase: 'In coda' };
      case 'in_preparazione': return { tipo: 'lavoro', frase: s.frase || 'In preparazione', percentuale: percentualeLavoro(s) };
      case 'pronta': return { tipo: 'pronta', frase: 'Pronta · ' + s.minuti + ' min' };
      case 'in_corso': return { tipo: 'meta', frase: 'A metà' };
      case 'in_correzione': return { tipo: 'correzione', frase: 'La sto correggendo' };
      case 'corretta': return { tipo: 'corretta', frase: 'Corretta' + (s.esito && s.esito.voto_stimato != null ? ' · stima circa ' + voto(s.esito.voto_stimato) : '') };
      case 'errore': return { tipo: 'errore', frase: 'Non è riuscita' };
      case 'annullata': return { tipo: 'annullata', frase: 'Annullata' };
      default: return { tipo: 'pronta', frase: '' };
    }
  }

  /** §36.3 Il tempo: solo minuti, arrotondati in su; "meno di 1 min" alla fine; finito, "+6 min". */
  function testoTempo(restanti) {
    const r = Number(restanti) || 0;
    if (r <= 0) return '+' + Math.max(1, Math.ceil(-r / 60)) + ' min';
    if (r < 60) return 'meno di 1 min';
    return Math.ceil(r / 60) + ' min';
  }

  /** §36.2 "8 domande · 100 punti · 50 minuti". */
  function riassuntoSimulata(s) {
    const domande = (s && s.domande) || [];
    const punti = domande.reduce((t, d) => t + (Number(d.punti) || 0), 0);
    return plurale(domande.length, 'domanda', 'domande') + ' · ' + plurale(punti, 'punto', 'punti') + ' · ' + plurale((s && s.minuti) || 0, 'minuto', 'minuti');
  }

  /**
   * §36.3 Una domanda è fatta se ha la risposta (o i passaggi), un risultato scritto o un file aggiunto (§21.1).
   * `risultati` e `allegati` come nella Simulata: { "3": ["0,07 cm"] }, { "3": [{ id, nome, tipo }] }.
   */
  function rispostaFatta(n, risposte, risultati, allegati) {
    const k = String(n);
    if (String((risposte || {})[k] || '').trim()) return true;
    if (((risultati || {})[k] || []).some((x) => String(x || '').trim())) return true;
    return ((allegati || {})[k] || []).length > 0;
  }

  /** §36.3 Le risposte scritte e quelle vuote: { scritte, vuote, frase: "5 scritte su 8 · 3 vuote" }. */
  function contaRisposte(domande, risposte, risultati, allegati) {
    const tutte = domande || [];
    const scritte = tutte.filter((d) => rispostaFatta(d.n, risposte, risultati, allegati)).length;
    const vuote = tutte.length - scritte;
    return { scritte, vuote, frase: scritte + (scritte === 1 ? ' scritta su ' : ' scritte su ') + tutte.length + ' · ' + vuote + (vuote === 1 ? ' vuota' : ' vuote') };
  }

  /** Le risposte da mostrare: quelle del server e, sopra, quelle sul computer non ancora arrivate al server. */
  function unisciRisposte(server, locali, sporche) {
    const fuori = Object.assign({}, server || {});
    for (const n of sporche || []) if (locali && locali[n] !== undefined) fuori[n] = locali[n];
    return fuori;
  }

  /** §36.4 "62 punti su 100 · la sufficienza è a 58" (la soglia solo se c'è). */
  function frasePunti(esito) {
    if (!esito) return '';
    const p = Number(esito.punti) || 0;
    const base = voto(p) + ' ' + (p === 1 ? 'punto' : 'punti') + ' su ' + voto(Number(esito.punti_totali) || 0);
    return esito.soglia != null ? base + ' · la sufficienza è a ' + voto(esito.soglia) : base;
  }

  /** §36.4 "Consegnata dopo 56 minuti su 50", solo se è andato oltre il tempo. */
  function fraseOltreTempo(secondiUsati, minuti) {
    const usati = Number(secondiUsati) || 0;
    const m = Number(minuti) || 0;
    if (!usati || !m || usati <= m * 60) return null;
    return 'Consegnata dopo ' + Math.ceil(usati / 60) + ' minuti su ' + m;
  }

  /** "8 su 10", "1,5 su 3" di una domanda corretta. Le `non_corretta` non hanno punti (contratto §21). */
  function puntiDomanda(correzione, domanda) {
    if (!correzione || correzione.esito === 'non_corretta') return null;
    return voto(Number(correzione.punti) || 0) + ' su ' + voto(Number(domanda && domanda.punti) || 0);
  }

  // --- Esercizi con i numeri: forma, barra della matematica, anteprima, grafici (contratto §21.1, guida §36.5) ---

  /**
   * §21.2 Il testo da leggere di ogni domanda, solo dove va mostrato: sopra la prima domanda di un gruppo di domande
   * di fila con lo stesso testo, e di nuovo quando cambia. Un array lungo come `domande`: { testo, fonte } o null.
   */
  function braniDaMostrare(domande) {
    let prima = null;
    return (domande || []).map((d) => {
      const b = d && d.brano;
      const testo = b && typeof b.testo === 'string' ? b.testo.trim() : '';
      if (!testo) { prima = null; return null; }
      const uguale = testo === prima;
      prima = testo;
      return uguale ? null : { testo, fonte: typeof b.fonte === 'string' && b.fonte.trim() ? b.fonte.trim() : null };
    });
  }

  /** La forma della risposta: "testo", "formule" o "conti". Una forma che non conosciamo vale "testo". */
  function formaDomanda(d) {
    const f = d && d.forma;
    return f === 'formule' || f === 'conti' ? f : 'testo';
  }

  /** I nomi dei campi del risultato di un esercizio "conti" (contratto: `domanda.risultati`). */
  function campiRisultato(d) {
    if (formaDomanda(d) !== 'conti') return [];
    // Al massimo 6 campi per domanda: il server non ne tiene di più (§21.1).
    const v = Array.isArray(d.risultati) ? d.risultati.slice(0, 6).map((x) => String(x == null ? '' : x).trim()) : [];
    return v.length ? v : ['Risultato'];
  }

  /** I file che si possono aggiungere a una risposta (§21.1): foto e PDF, 20 MB, 4 per domanda. */
  const ESTENSIONI_ALLEGATO = ['.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif', '.pdf'];
  const MASSIMO_ALLEGATI = 4;
  function controllaAllegato(nome, byte, gia) {
    if (!ESTENSIONI_ALLEGATO.includes(estensione(nome))) return 'Questo tipo di file non lo leggo. Usa una foto JPG, PNG, WEBP o HEIC, oppure un PDF.';
    if (Number(byte) > MASSIMO_BYTE) return 'Il file è troppo grande: il massimo è 20 MB.';
    if (Number(byte) === 0) return 'Il file è vuoto.';
    if ((Number(gia) || 0) >= MASSIMO_ALLEGATI) return 'Al massimo ' + MASSIMO_ALLEGATI + ' file per domanda.';
    return null;
  }

  /** Il segno di un risultato controllato dal programma (`controllo[].esito`). Mai rosso, come gli esiti. */
  function esitoControllo(esito) {
    switch (esito) {
      case 'giusto': return { parola: 'Giusto', classe: 'giusta', icona: 'spunta' };
      case 'non_arrotondato': return { parola: 'Da arrotondare', classe: 'quasi', icona: 'quasi' };
      case 'sbagliato': return { parola: 'Da rivedere', classe: 'rivedere', icona: 'ripeti' };
      case 'mancante': return { parola: 'Vuoto', classe: 'rivedere', icona: 'ripeti' };
      default: return null; // da_leggere: decide la correzione, niente segno
    }
  }

  /**
   * I tasti della barra della matematica. Gli schemi mettono il cursore dentro (come i modelli di GeoGebra e
   * MathLive): `prima` + cursore + `dopo`; con del testo selezionato, `avvolgi` dice dove va la selezione.
   */
  const TASTI_FORMULA = [
    { chiave: 'frazione', nome: 'Frazione', prima: '(', dopo: ')/()', avvolgi: ['(', ')/(', ')'] },
    { chiave: 'radice', nome: 'Radice', prima: '√(', dopo: ')', avvolgi: ['√(', ')', ''] },
    { chiave: 'potenza', nome: 'Potenza', prima: '^(', dopo: ')', avvolgi: ['^(', ')', ''] },
    { chiave: 'pedice', nome: 'Pedice', prima: '_(', dopo: ')', avvolgi: ['_(', ')', ''] },
    { chiave: 'parentesi', nome: 'Parentesi', prima: '(', dopo: ')', avvolgi: ['(', ')', ''], segno: '( )' },
    { chiave: 'piumeno', nome: 'Più o meno', segno: '±' },
    { chiave: 'punto', nome: 'Per', segno: '·' },
    { chiave: 'per', nome: 'Per (croce)', segno: '×' },
    { chiave: 'diviso', nome: 'Diviso', segno: '÷' },
    { chiave: 'delta', nome: 'Delta', segno: 'Δ' },
    { chiave: 'pi', nome: 'Pi greco', segno: 'π' },
    { chiave: 'minore', nome: 'Minore o uguale', segno: '≤' },
    { chiave: 'maggiore', nome: 'Maggiore o uguale', segno: '≥' },
    { chiave: 'diverso', nome: 'Diverso', segno: '≠' },
    { chiave: 'circa', nome: 'Circa uguale', segno: '≈' },
    { chiave: 'gradi', nome: 'Gradi', segno: '°' },
  ];

  /**
   * Un tasto premuto con il cursore tra `inizio` e `fine`: il testo nuovo e dove va il cursore.
   * Frazione senza selezione: "()/()" col cursore nella prima parentesi; con la selezione, la selezione sopra e
   * il cursore sotto la linea.
   */
  function inserisciFormula(valore, inizio, fine, chiave) {
    const v = String(valore == null ? '' : valore);
    const a = Math.max(0, Math.min(v.length, Number(inizio) || 0));
    const b = Math.max(a, Math.min(v.length, fine == null ? a : Number(fine) || 0));
    const t = TASTI_FORMULA.find((x) => x.chiave === chiave);
    if (!t) return { valore: v, inizio: a, fine: b };
    const scelto = v.slice(a, b);
    let dentro;
    let cursore;
    if (t.prima === undefined) {
      dentro = t.segno;
      cursore = a + dentro.length;
    } else if (scelto) {
      dentro = t.avvolgi[0] + scelto + t.avvolgi[1] + t.avvolgi[2];
      cursore = a + t.avvolgi[0].length + scelto.length + t.avvolgi[1].length;
    } else {
      dentro = t.prima + t.dopo;
      cursore = a + t.prima.length;
    }
    return { valore: v.slice(0, a) + dentro + v.slice(b), inizio: cursore, fine: cursore };
  }

  /** C'è qualcosa da impaginare "come sul libro" (frazione, potenza, pedice, radice)? */
  function daImpaginare(testo) {
    return /[/^_√]|sqrt\(/.test(String(testo || ''));
  }

  /**
   * La scrittura in riga ("(1,66-1,61)/2", "x^2", "Δx_1", "√(x+1)") come albero da impaginare:
   * { t: 'testo', v } | { t: 'frazione', num: [...], den: [...] } | { t: 'su' | 'giu' | 'radice', v: [...] }.
   * Le parentesi che delimitano numeratore, denominatore, esponente, pedice o radice non si vedono, come sul libro.
   */
  function pezziFormula(testo) {
    const s = String(testo == null ? '' : testo);
    let i = 0;
    const cifra = (c) => c !== undefined && c >= '0' && c <= '9';
    const lettera = (c) => c !== undefined && /[A-Za-zÀ-ÖØ-öø-ÿͰ-Ͽ]/.test(c);
    const diTermine = (k) => {
      const c = s[k];
      if (c === undefined) return false;
      if (cifra(c) || lettera(c) || /[²³¹⁰⁴-⁹₀-₉°%']/.test(c)) return true;
      return (c === ',' || c === '.') && cifra(s[k - 1]) && cifra(s[k + 1]);
    };
    const termine = () => { const a = i; while (i < s.length && diTermine(i)) i++; return { nodi: [{ t: 'testo', v: s.slice(a, i) }] }; };
    const conParentesi = (p) => (p.gruppo ? [{ t: 'testo', v: '(' }, ...p.nodi, ...(p.chiusa ? [{ t: 'testo', v: ')' }] : [])] : p.nodi);

    function gruppo() {
      i++;
      const nodi = sequenza(')');
      const chiusa = s[i] === ')';
      if (chiusa) i++;
      return { nodi, gruppo: true, chiusa };
    }
    function radice() {
      i += s[i] === '√' ? 1 : 4;
      const arg = argomento(false);
      return { nodi: [{ t: 'radice', v: arg ? arg.nodi : [] }] };
    }
    // Dopo ^ _ / √: un gruppo tra parentesi (senza parentesi), una radice o un termine.
    // `stretto` senza parentesi: l'esponente prende le cifre (anche col meno davanti) o una lettera sola ("x^2y" è
    // x² per y); il pedice prende lettere e cifre attaccate ("x_max", "x_12").
    function argomento(stretto) {
      if (s[i] === '(') return gruppo();
      if (s[i] === '√' || s.startsWith('sqrt(', i)) return radice();
      if (!stretto) return diTermine(i) ? termine() : null;
      const a = i;
      if (stretto === 'giu') {
        while (i < s.length && (cifra(s[i]) || lettera(s[i]))) i++;
        return i > a ? { nodi: [{ t: 'testo', v: s.slice(a, i) }] } : null;
      }
      if ((s[i] === '-' || s[i] === '−') && cifra(s[i + 1])) i++;
      if (cifra(s[i])) {
        while (i < s.length && (cifra(s[i]) || ((s[i] === ',' || s[i] === '.') && cifra(s[i + 1])))) i++;
      } else if (lettera(s[i])) i++;
      else { i = a; return null; }
      return { nodi: [{ t: 'testo', v: s.slice(a, i) }] };
    }
    function suGiu(p) {
      let x = p;
      while (s[i] === '^' || s[i] === '_') {
        const segno = s[i];
        const salva = i;
        i++;
        const arg = argomento(segno === '^' ? 'su' : 'giu');
        if (!arg) { i = salva; break; }
        x = { nodi: [...conParentesi(x), { t: segno === '^' ? 'su' : 'giu', v: arg.nodi }] };
      }
      return x;
    }
    function sequenza(fine) {
      const pezzi = [];
      while (i < s.length && s[i] !== fine) {
        const c = s[i];
        let p;
        if (c === '(') p = suGiu(gruppo());
        else if (c === '√' || s.startsWith('sqrt(', i)) p = suGiu(radice());
        else if (diTermine(i)) p = suGiu(termine());
        else if (c === '/') {
          let k = pezzi.length - 1;
          while (k >= 0 && pezzi[k].spazio) k--;
          const salva = i;
          i++;
          while (s[i] === ' ') i++;
          const den = argomento(false);
          if (k >= 0 && den && !pezzi[k].segno) {
            const num = pezzi[k];
            pezzi.splice(k);
            pezzi.push({ nodi: [{ t: 'frazione', num: num.nodi, den: suGiu(den).nodi }] });
            continue;
          }
          i = salva + 1;
          p = { nodi: [{ t: 'testo', v: '/' }], segno: true };
        } else {
          i++;
          p = { nodi: [{ t: 'testo', v: c }], spazio: c === ' ', segno: c !== ' ' };
        }
        pezzi.push(p);
      }
      const nodi = [];
      for (const p of pezzi) {
        for (const n of conParentesi(p)) {
          const ultimo = nodi[nodi.length - 1];
          if (n.t === 'testo' && ultimo && ultimo.t === 'testo') ultimo.v += n.v;
          else nodi.push(n.t === 'testo' ? { t: 'testo', v: n.v } : n);
        }
      }
      return nodi;
    }
    return sequenza(undefined);
  }

  /** Un numero per gli assi dei grafici: "2,5", "−0,04", "1200" (al più 6 cifre significative). */
  function numeroGrafico(v) {
    const x = Number(v);
    if (!Number.isFinite(x)) return '';
    const t = String(Number(x.toPrecision(6)));
    if (/e/i.test(t)) return t.replace('.', ',').replace('-', '−');
    return (x < 0 ? '−' : '') + t.replace('-', '').replace('.', ',');
  }

  /** Le tacche "tonde" di un asse da min a max (passi 1, 2, 2,5, 5 per una potenza di 10). */
  function tacche(min, max, quante) {
    let a = Number(min);
    let b = Number(max);
    if (!Number.isFinite(a) || !Number.isFinite(b)) return { min: 0, max: 1, passo: 1, valori: [0, 1] };
    if (a > b) [a, b] = [b, a];
    if (a === b) { const d = Math.abs(a) > 0 ? Math.abs(a) / 2 : 1; a -= d; b += d; }
    const grezzo = (b - a) / Math.max(1, quante || 5);
    const p10 = Math.pow(10, Math.floor(Math.log10(grezzo)));
    const f = grezzo / p10;
    const passo = (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p10;
    const inizio = Math.floor(a / passo + 1e-9) * passo;
    const fine = Math.ceil(b / passo - 1e-9) * passo;
    const decimali = Math.max(0, -Math.floor(Math.log10(passo)) + 1);
    const valori = [];
    for (let k = 0; inizio + k * passo <= fine + passo * 1e-6 && k < 50; k++) valori.push(Number((inizio + k * passo).toFixed(decimali)));
    return { min: valori[0], max: valori[valori.length - 1], passo, valori };
  }

  /**
   * Gli intervalli di un grafico (§21.1): x e y con le tacche. Le barre partono sempre da 0; per punti e linee lo
   * 0 entra solo se è già vicino (così l'asse passa per lo 0 quando serve, e misure come 1,58-1,75 non si schiacciano).
   */
  function spazioGrafico(g) {
    const tipo = g && g.tipo;
    if (tipo === 'barre') {
      const valori = ((g && g.serie) || []).flatMap((x) => (x.valori || []).map(Number)).filter(Number.isFinite);
      const ymin = Math.min(0, ...valori);
      const ymax = Math.max(0, ...valori);
      return { tipo, categorie: (g.categorie || []).map(String), y: tacche(ymin, ymax === ymin ? ymin + 1 : ymax, 5) };
    }
    const punti = ((g && g.serie) || []).flatMap((x) => x.punti || []).filter((p) => Array.isArray(p) && Number.isFinite(Number(p[0])) && Number.isFinite(Number(p[1])));
    const xs = punti.map((p) => Number(p[0]));
    const ys = punti.map((p) => Number(p[1]));
    const intervallo = (v) => {
      if (!v.length) return [0, 1];
      let a = Math.min(...v);
      let b = Math.max(...v);
      const largo = b - a || Math.abs(a) || 1;
      if (a > 0 && a <= largo * 0.5) a = 0;
      if (b < 0 && -b <= largo * 0.5) b = 0;
      return [a, b];
    };
    const [x0, x1] = intervallo(xs);
    const [y0, y1] = intervallo(ys);
    return { tipo: tipo === 'linee' ? 'linee' : 'punti', x: tacche(x0, x1, 6), y: tacche(y0, y1, 5) };
  }

  /** La riga PER di Nuovo schema e Nuova verifica. */
  function riepilogoData(evento) {
    return evento ? (evento.tipo === 'orale' ? 'L\'interrogazione di ' : 'La verifica di ') + dataLunga(evento.data) : 'Nessuna data';
  }

  // --- Il pulsante Nuovo e "Cosa vuoi fare?" (guida §43) ------------------------------------------

  /** §43.3 Le cose che Lectio sa fare, nell'ordine del foglio (§45.1: Mappa dopo Riassunto). `icona` = nome dell'icona del PC. */
  const SCELTE_NUOVO = [
    { scelta: 'lezione', nome: 'Lezione da ascoltare', frase: 'Te la spiego a voce, con le domande lungo la strada', icona: 'lezioni' },
    { scelta: 'schema', nome: 'Schema', frase: 'Da leggere: i punti chiave, con le immagini', icona: 'testo' },
    { scelta: 'riassunto', nome: 'Riassunto', frase: 'Da uno schema già pronto: per punti o discorsivo', icona: 'riassunto' },
    { scelta: 'mappa', nome: 'Mappa', frase: 'Da uno schema già pronto: le date o i concetti', icona: 'mappa' },
    { scelta: 'simulata', nome: 'Verifica simulata', frase: 'Come quella del prof, poi la correggo', icona: 'verifica' },
    { scelta: 'lampo', nome: 'Ripasso lampo', frase: '', icona: 'lampo' },
  ];

  /** §43.3 Gli schemi pronti di una materia, i più recenti prima (passo 2 di Riassunto). */
  function schemiPronti(lista) {
    return (lista || []).filter((x) => x && x.stato === 'pronto')
      .sort((a, b) => String(b.creato_il || '').localeCompare(String(a.creato_il || '')) || (Number(b.id) || 0) - (Number(a.id) || 0));
  }

  /**
   * §43.3 Le righe di "Cosa vuoi fare?" con la frase e, se sono spente, il perché nella frase.
   * o.lampo = schede del Ripasso lampo di oggi; o.pronti = quanti schemi pronti (null = non si sa ancora: Riassunto
   * aspetta spento); o.senzaRete; o.schedeSalvate = Ripasso lampo possibile senza rete (sul PC mai: §35.5).
   */
  function righeNuovo(o) {
    const x = o || {};
    const lampo = Number(x.lampo) || 0;
    return SCELTE_NUOVO.map((s) => {
      const r = { scelta: s.scelta, nome: s.nome, frase: s.frase, icona: s.icona, spenta: false };
      if (s.scelta === 'lampo') {
        r.frase = lampo > 0 ? fraseLampo(lampo) : 'Niente per oggi';
        r.spenta = !(lampo > 0);
        if (x.senzaRete && !x.schedeSalvate) { r.spenta = true; r.frase = 'Serve la rete'; }
        return r;
      }
      if (x.senzaRete) { r.spenta = true; r.frase = 'Serve la rete'; return r; }
      const daSchema = s.scelta === 'riassunto' || s.scelta === 'mappa';
      if (daSchema && x.pronti === 0) { r.spenta = true; r.frase = 'Prima serve uno schema pronto'; }
      else if (daSchema && x.pronti == null) r.spenta = true;
      return r;
    });
  }

  /** §43.2 Nuovo è spento solo se nel foglio non si potrebbe toccare niente (senza rete e senza schede salvate). */
  function nuovoSpento(o) {
    return righeNuovo(Object.assign({}, o, { pronti: 1, lampo: 1 })).every((r) => r.spenta);
  }

  // --- Modifica, Correggi a mano, Torna a prima (contratto §28, guida §44) ---------------------------

  /** §44.2 La richiesta: da 1 a 600 caratteri; il contatore diventa ambra oltre i 550. */
  const MASSIMO_RICHIESTA = 600;
  const AVVISO_RICHIESTA_DA = 550;
  /** Contratto §28.4: ogni testo corretto a mano al massimo 20.000 caratteri, il titolo 120. */
  const MASSIMO_TESTO_A_MANO = 20000;
  const MASSIMO_TITOLO_A_MANO = 120;

  /** §44.2 Il segnaposto del campo e gli spunti, per tipo (il testo dello spunto è quello che entra nel campo). */
  const MODIFICA_PER_TIPO = {
    // `nel`/`del`: la finestra dice sempre che cosa si cambia (un riassunto ha lo stesso titolo del suo schema).
    schema: { nel: 'nello schema', del: 'dello schema', segnaposto: 'Per esempio: aggiungi le date delle crociate', spunti: ['Aggiungi le date', 'Più semplice', 'Più dettagli', 'Più corto'] },
    riassunto: { nel: 'nel riassunto', del: 'del riassunto', segnaposto: 'Per esempio: più corto, solo le cose da sapere', spunti: ['Più corto', 'Più semplice', 'Più dettagli'] },
    lezione: { nel: 'nella lezione', del: 'della lezione', segnaposto: 'Per esempio: spiega meglio la terza crociata', spunti: ['Spiega meglio', 'Più esempi', 'Più semplice'] },
    simulata: { nel: 'nella verifica', del: 'della verifica', segnaposto: 'Per esempio: più domande aperte, meno crocette', spunti: ['Più difficile', 'Più facile', 'Più domande aperte', 'Meno crocette'] },
    mappa: { nel: 'nella mappa', del: 'della mappa', segnaposto: 'Per esempio: aggiungi i concetti sulle cause', spunti: ['Meno concetti', 'Più concetti', 'Più semplice'] },
    mappa_date: { nel: 'nella mappa', del: 'della mappa', segnaposto: 'Per esempio: aggiungi le date delle battaglie', spunti: ['Meno date', 'Più date', 'Frasi più corte'] },
  };

  /** Una modifica che lavora ancora (in coda o in preparazione): intanto niente altre modifiche né scambi. */
  function modificaAttiva(m) {
    return !!m && (m.stato === 'in_coda' || m.stato === 'in_preparazione');
  }

  /**
   * §44.2 Uno spunto entra nel campo: vuoto → la frase; con del testo → dopo un punto ("Più corto. Più semplice").
   * Se non ci sta nei 600 caratteri non entra (ok: false), così nel campo non resta mai una frase a metà.
   */
  function aggiungiSpuntoModifica(testo, frase, massimo) {
    const max = massimo || MASSIMO_RICHIESTA;
    const ora = String(testo == null ? '' : testo);
    const base = ora.replace(/\s+$/, '');
    if (!base.trim()) return frase.length <= max ? { testo: frase, ok: true } : { testo: ora, ok: false };
    const nuovo = base + (/[.!?…:;]$/.test(base) ? ' ' : '. ') + frase;
    if (nuovo.length > max) return { testo: ora, ok: false };
    return { testo: nuovo, ok: true };
  }

  /** §44.2 "0 / 600" sotto il campo, ambra oltre 550. */
  function contatoreRichiesta(testo) {
    const n = String(testo == null ? '' : testo).length;
    return { testo: n + ' / ' + MASSIMO_RICHIESTA, avviso: n > AVVISO_RICHIESTA_DA };
  }

  /**
   * Contratto §28.3 Le parti da scegliere. Se l'oggetto non le porta (un riassunto preso dall'elenco) si contano dal
   * contenuto; vuota = l'oggetto adesso non si può modificare. Titolo vuoto → "Parte 3".
   */
  function partiModificabili(oggetto, tipo) {
    const o = oggetto || {};
    let lista = o.parti_modificabili;
    if (!Array.isArray(lista)) {
      if (tipo === 'riassunto') lista = ((o.contenuto && o.contenuto.parti) || []).map((p, i) => ({ n: i + 1, titolo: p && p.titolo }));
      else if (tipo === 'schema') lista = ((o.contenuto && o.contenuto.sezioni) || []).map((s) => ({ n: s.n, titolo: s.titolo }));
      else if (tipo === 'lezione') lista = (o.capitoli || []).map((c) => ({ n: c.n, titolo: c.titolo }));
      else if (tipo === 'mappa') lista = o.stato === 'pronta' ? [{ n: 1, titolo: o.titolo || 'La mappa' }] : [];
      else lista = (o.domande || []).map((d) => ({ n: d.n, titolo: d.testo }));
    }
    return lista.filter((p) => p && p.n != null).map((p) => ({ n: Number(p.n), titolo: String(p.titolo || '').trim() || 'Parte ' + p.n }));
  }

  /**
   * §44.1 Modifica, Correggi a mano e Torna a prima sono spenti senza rete, con l'oggetto non pronto e mentre una
   * modifica lavora; la frase dice perché (come le righe spente di §43.3). null = si può.
   * o = { senzaRete, pronto, modifica, conParti } (conParti = false se non ci sono parti da cambiare).
   */
  function motivoSpentaModifica(o) {
    const x = o || {};
    if (x.senzaRete) return 'Serve la rete';
    if (modificaAttiva(x.modifica)) return 'C\'è già una modifica in corso';
    if (x.pronto === false || x.conParti === false) return 'Adesso non si può modificare';
    return null;
  }

  /** §44.5 Il pulsante per la versione messa da parte: ruolo "prima" → Torna a prima, "dopo" → Rimetti la modifica. */
  function pulsanteVersione(vdp) {
    if (!vdp || (vdp.ruolo !== 'prima' && vdp.ruolo !== 'dopo')) return null;
    if (vdp.ruolo === 'prima') {
      return { ruolo: 'prima', testo: 'Torna a prima', domanda: 'Torno alla versione di prima?', frase: 'Quella di adesso resta da parte.' };
    }
    return { ruolo: 'dopo', testo: 'Rimetti la modifica', domanda: 'Rimetto la versione modificata?', frase: 'Quella di adesso resta da parte.' };
  }

  /** §44.3 L'avviso a modifica pronta: "Fatto: ho cambiato le parti 2 e 3", "… la parte 2". */
  function fraseCambiate(cambiate) {
    const n = Array.from(new Set((cambiate || []).map(Number).filter((x) => Number.isFinite(x)))).sort((a, b) => a - b);
    if (!n.length) return 'Non ho cambiato niente.';
    if (n.length === 1) return 'Fatto: ho cambiato la parte ' + n[0];
    return 'Fatto: ho cambiato le parti ' + n.slice(0, -1).join(', ') + ' e ' + n[n.length - 1];
  }

  // --- §44.9 I file aggiunti a una modifica (contratto §28.5) ------------------------------------------------------

  /** La richiesta che Lectio scrive da solo quando si aggiunge il primo file e il campo è ancora vuoto. */
  const RICHIESTA_CON_FILE = 'Aggiungi quello che c\'è nei file che ho allegato.';
  /** Quanti file al massimo per una modifica (il server ne accetta 20). */
  const MASSIMO_FILE_MODIFICA = 20;
  /** Come si chiamano le parti di ogni tipo (una mappa è un pezzo solo). */
  const NOME_PARTE = { schema: ['parte', 'parti'], riassunto: ['parte', 'parti'], lezione: ['capitolo', 'capitoli'], simulata: ['domanda', 'domande'] };

  /** "parte 3", "parti 2 e 5", "capitolo 1"; '' per una mappa o senza parti. Accetta numeri o { n }. */
  function nomeParti(tipo, parti) {
    const nomi = NOME_PARTE[tipo];
    const n = Array.from(new Set((parti || []).map((p) => Number(p && typeof p === 'object' ? p.n : p)).filter((x) => Number.isFinite(x)))).sort((a, b) => a - b);
    if (!nomi || !n.length) return '';
    return (n.length === 1 ? nomi[0] + ' ' + n[0] : nomi[1] + ' ' + n.slice(0, -1).join(', ') + ' e ' + n[n.length - 1]);
  }

  /**
   * La riga di un file nella materia (usato_per): "Ha cambiato: Gli inizi dell'antropologia · parte 3". Finché la
   * modifica lavora "Sta cambiando", se si è fermata "Doveva cambiare".
   */
  function fraseUsatoPer(u) {
    if (!u) return null;
    const verbo = u.stato === 'pronta' ? 'Ha cambiato' : u.stato === 'errore' ? 'Doveva cambiare' : 'Sta cambiando';
    const parti = nomeParti(u.tipo, u.parti);
    return verbo + ': ' + (pulisciTitolo(u.titolo) || 'senza titolo') + (parti ? ' · ' + parti : '');
  }

  /** I file che hanno cambiato la parte n (solo modifiche finite), un file una volta sola. */
  function fileDellaParte(oggetto, n) {
    const visti = new Set();
    return ((oggetto && oggetto.file_delle_modifiche) || []).filter((f) => {
      if (f.stato !== 'pronta' || visti.has(f.materiale_id) || !(f.parti || []).some((p) => Number(p.n) === Number(n))) return false;
      visti.add(f.materiale_id);
      return true;
    });
  }

  /** L'etichetta sulla parte: "Cambiata con 1 file", "Cambiata con 2 file" (null senza file). */
  function fraseFileParte(lista) {
    return lista && lista.length ? 'Cambiata con ' + lista.length + ' file' : null;
  }

  /** Lo stato di un file nella finestra Modifica: f.stato = 'mando' | 'in_lettura' | 'pronto' | 'errore'. */
  function statoFileModifica(f) {
    if (!f) return '';
    if (f.stato === 'mando') return 'Lo mando…';
    if (f.stato === 'in_lettura') return 'Lo sto leggendo…';
    if (f.stato === 'errore') return f.errore || 'Non sono riuscito a leggerlo.';
    return 'Letto';
  }

  /** Perché Modifica è spento per colpa dei file (null = i file vanno bene). */
  function motivoFileModifica(lista) {
    const l = lista || [];
    if (l.some((f) => f.stato === 'mando')) return 'Aspetta che i file siano arrivati.';
    if (l.some((f) => f.stato === 'errore')) return 'Togli i file che non sono entrati, poi tocca Modifica.';
    return null;
  }

  /** §44.2 Il costo nel riepilogo: "circa 0,03 €", con "Tutto" "fino a 0,15 € (di solito meno)", in prova "Prova". */
  function costoModifica(stima, prova) {
    if (prova) return 'Prova';
    if (!stima) return null;
    return stima.fino_a ? 'fino a ' + euroCosto(stima.costo_eur) + ' (di solito meno)' : 'circa ' + euroCosto(stima.costo_eur);
  }

  /** §44.2 Le righe del riepilogo, dopo il costo. o = { tipo, stima, corretta } (corretta = verifica già corretta). */
  function righeRiepilogoModifica(o) {
    const x = o || {};
    const righe = [];
    if (x.stima && x.stima.minuti_preparazione != null) righe.push('Pronta in circa ' + plurale(Number(x.stima.minuti_preparazione) || 1, 'minuto', 'minuti'));
    if (x.tipo === 'lezione') righe.push('Intanto la lezione si ascolta com\'è adesso.');
    if (x.tipo === 'simulata' && x.corretta) righe.push('Diventa una prova nuova: il voto di adesso resta in Torna a prima.');
    return righe;
  }

  const copiaJson = (x) => (x == null ? x : JSON.parse(JSON.stringify(x)));
  const pulito = (t) => String(t == null ? '' : t).trim();
  /** "uno\n\n due \n" → ["uno", "due"]: nelle voci va a capo = voce nuova, le righe vuote si tolgono. */
  function righeVoci(testo) {
    return (Array.isArray(testo) ? testo : String(testo == null ? '' : testo).split('\n')).map(pulito).filter(Boolean);
  }

  /**
   * §44.4 / contratto §28.4 Il contenuto dello schema da mandare dopo la correzione a mano: dal `corretto` si prendono
   * SOLO i testi (titoli delle parti, valori dei campi, e nei blocchi titolo, testo, termine, voci, intestazione, righe,
   * didascalia); tipi, ordine, numeri, foto, crediti e fonti restano quelli di `originale`. Un testo che nell'originale
   * non c'era (titolo null) non si aggiunge. Le voci vuote si tolgono; i blocchi rimasti vuoti li toglie il server.
   */
  function contenutoSchemaCorretto(originale, corretto) {
    const fuori = copiaJson(originale) || {};
    const c = corretto || {};
    (fuori.sezioni || []).forEach((s, i) => {
      const cs = (c.sezioni || [])[i] || {};
      if (typeof s.titolo === 'string' && typeof cs.titolo === 'string') s.titolo = pulito(cs.titolo);
      (s.campi || []).forEach((k, j) => {
        const ck = (cs.campi || [])[j];
        if (k && ck && typeof k.valore === 'string' && typeof ck.valore === 'string') k.valore = pulito(ck.valore);
      });
      (s.blocchi || []).forEach((b, j) => {
        const cb = (cs.blocchi || [])[j];
        if (!b || !cb || cb.tipo !== b.tipo) return;
        for (const k of ['titolo', 'testo', 'termine', 'didascalia']) {
          if (typeof b[k] === 'string' && typeof cb[k] === 'string') b[k] = pulito(cb[k]);
        }
        if (Array.isArray(b.voci) && cb.voci != null) b.voci = righeVoci(cb.voci);
        if (Array.isArray(b.intestazione) && Array.isArray(cb.intestazione)) {
          b.intestazione = b.intestazione.map((v, k) => (typeof cb.intestazione[k] === 'string' ? pulito(cb.intestazione[k]) : v));
        }
        if (Array.isArray(b.righe) && Array.isArray(cb.righe)) {
          b.righe = b.righe.map((r, ri) => (Array.isArray(r) ? r.map((v, ci) => {
            const nuova = (cb.righe[ri] || [])[ci];
            return typeof nuova === 'string' ? pulito(nuova) : v;
          }) : r));
        }
      });
    });
    return fuori;
  }

  /**
   * Contratto §28.4 Il riassunto corretto a mano: stesso numero di parti dell'originale; il titolo di una parte cambia
   * solo se c'era; paragrafi e punti vuoti si tolgono.
   */
  function contenutoRiassuntoCorretto(originale, corretto) {
    const fuori = copiaJson(originale) || {};
    const c = corretto || {};
    fuori.parti = (fuori.parti || []).map((p, i) => {
      const cp = (c.parti || [])[i] || {};
      const x = Object.assign({}, p);
      if (typeof p.titolo === 'string' && typeof cp.titolo === 'string') x.titolo = pulito(cp.titolo);
      x.paragrafi = Array.isArray(cp.paragrafi) ? cp.paragrafi.map(pulito).filter(Boolean) : (p.paragrafi || []);
      x.punti = cp.punti != null ? righeVoci(cp.punti) : (p.punti || []);
      return x;
    });
    return fuori;
  }

  /** §44.4 C'è qualcosa di cambiato rispetto a com'era? (per "Lasciare le correzioni?") */
  function correzioniFatte(tipo, originale, titoloPrima, corretto, titoloOra) {
    if (pulito(titoloPrima) !== pulito(titoloOra)) return true;
    const f = tipo === 'riassunto' ? contenutoRiassuntoCorretto : tipo === 'mappa' ? contenutoMappaCorretto : contenutoSchemaCorretto;
    const ricostruito = f(originale, corretto);
    const base = f(originale, originale);
    return JSON.stringify(ricostruito) !== JSON.stringify(base);
  }

  /**
   * Il punto da cui ripartire in una lezione cambiata (contratto §28.1, lo stesso criterio del server): stesso punto del
   * capitolo se quel capitolo non è cambiato, altrimenti l'inizio del capitolo. null se i capitoli non si corrispondono
   * (allora vale il punto del server). `vecchi`/`nuovi` = capitoli; `cambiate` = numeri dei capitoli cambiati.
   */
  function puntoDopoModifica(vecchi, nuovi, cambiate, t) {
    if (!Array.isArray(vecchi) || !Array.isArray(nuovi) || !vecchi.length || vecchi.length !== nuovi.length) return null;
    const x = Math.max(0, Number(t) || 0);
    const i = capitoloA(vecchi, x);
    const v = vecchi[i];
    const nu = nuovi.find((c) => c.n === v.n) || nuovi[i];
    if (!nu) return null;
    if ((cambiate || []).map(Number).includes(Number(nu.n))) return Number(nu.inizio_s) || 0;
    const dentro = Math.max(0, x - (Number(v.inizio_s) || 0));
    const fine = Math.max(Number(nu.inizio_s) || 0, (Number(nu.fine_s) || 0) - 0.5);
    return Math.round(Math.min((Number(nu.inizio_s) || 0) + dentro, fine) * 10) / 10;
  }

  return {
    GIORNI, MESI, DURATE, VELOCITA, COLORI, ORDINE_COLORI, NOMI_COLORI, coloreUsatoDa, ESTENSIONI, MASSIMO_BYTE, FAMIGLIE_MATERIALE, NOMI_TIPO_MATERIALE, NOMI_TIPO_BREVE, NOMI_TIPI_DOMANDA, FASI,
    TIPI_LEZIONE, SPUNTI, MASSIMO_INDICAZIONI,
    numero, euro, euroTondo, euroCosto, migliaia, limita, percentuale, dimensione, barraMese, spesaInAvviso,
    data, dataLunga, dataBreve, iso, giorniFra, quando, siAzzera,
    tempo, meno, durataBreve, mancano, etichettaVelocita, prossimaVelocita, durataVicina,
    riassuntoLezione, statoLezione, fasi, capitoliStato, capitoloA, etichettaDomanda, piuRecente, puntoDiPartenza, Fermate,
    esitoRisposta, rispostaValida, primoColoreLibero, azioneTastoPlay, modoSalto, domandaAperta, prontoDaUsare,
    normalizza, finaliSpiegazione, struttura, titoloLezione, alternaSpunto, riepilogoIndicazioni, riepilogoMateriale, riepilogoPer, etichettaEvento,
    gruppiMateriale, sottoMateriale, filtraPerTitolo, materialiSpuntati, famigliaDi, pulisciTitolo, notaTitolo, campiCambiati,
    nomeCartellaDi, contaFile, messiDaLectio, materialeFuori, materialeDentro, gruppiMetti, sottoMetti, fraseMettiQui, fraseFattoMetti,
    articoloNumero, fraseEliminaConFile, fraseDentro, fraseCartellaEliminata, pulisciNomeCartella, cartelleConPronti, cartellaTuttaScelta, alternaCartella,
    LIMITI_SCHEDA, schedaDaMandare, controllaScheda,
    CAMPI_VERIFICHE, CAMPI_INTERROGAZIONI, LIMITI_MODULO, numeroScritto, modulo, frasePuntiModulo, righeModulo, campiModulo, braniDaMostrare,
    creaSequenza, voceDi, statoVoci, sceltaVoce, etichettaPrepara, prontaIn,
    estensione, controllaFile, eImmagine, righe, testoInterrogazione, codiceValido, prof, plurale, sottoMateria, basataSu, leggiRotta,
    FORME_SCHEMA, LIVELLI_SCHEMA, SPUNTI_SCHEMA, TIPI_BLOCCO, BLOCCHI_CHIARIBILI, pezziGrassetto, senzaGrassetto, blocchiNoti, passaggioDelBlocco, fontiDelBlocco, segnoFonti, segniDeiBlocchi, testaOpera,
    minutiLettura, nomeForma, nomeLivello, riassuntoSchema, percentualeLavoro, statoRigaSchema, mancanzeSchema, nomeFilePdf,
    SPUNTI_CHIARIMENTO, MASSIMO_DOMANDA, CONTATORE_DOMANDA_DA, costoChiarimento, etichettaMinuto,
    ESITI_LAMPO, minutiLampo, fraseLampo, fraseFineRipasso,
    DURATE_SIMULATA, esitoSimulata, voto, statoRigaSimulata, testoTempo, riassuntoSimulata, rispostaFatta, contaRisposte, unisciRisposte, frasePunti, fraseOltreTempo, puntiDomanda, riepilogoData,
    formaDomanda, campiRisultato, ESTENSIONI_ALLEGATO, MASSIMO_ALLEGATI, controllaAllegato, esitoControllo, TASTI_FORMULA, inserisciFormula, daImpaginare, pezziFormula,
    numeroGrafico, tacche, spazioGrafico,
    FORME_RIASSUNTO, riassuntoDellaForma, statoFormaRiassunto, fraseParole, ultimoRiassunto,
    mappaDelTipo, statoTipoMappa, ultimaMappa, contenutoMappaCorretto,
    SCELTE_NUOVO, schemiPronti, righeNuovo, nuovoSpento,
    MASSIMO_RICHIESTA, AVVISO_RICHIESTA_DA, MASSIMO_TESTO_A_MANO, MASSIMO_TITOLO_A_MANO, MODIFICA_PER_TIPO, modificaAttiva, aggiungiSpuntoModifica,
    contatoreRichiesta, partiModificabili, motivoSpentaModifica, pulsanteVersione, fraseCambiate, costoModifica, righeRiepilogoModifica,
    righeVoci, contenutoSchemaCorretto, contenutoRiassuntoCorretto, correzioniFatte, puntoDopoModifica,
    RICHIESTA_CON_FILE, MASSIMO_FILE_MODIFICA, nomeParti, fraseUsatoPer, fileDellaParte, fraseFileParte, statoFileModifica, motivoFileModifica,
  };
});
