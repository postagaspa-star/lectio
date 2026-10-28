/*
 * Lectio per il computer: la modalità esempio (§12). Un server finto IN MEMORIA, con dati finti
 * (Storia del Prof. Rossi, Diritto del Prof. Bianchi…): serve alla visita guidata e alle foto delle pagine.
 * Non fa nessuna chiamata di rete e non salva niente: chiusa la visita, sparisce tutto.
 */
(function (radice, fabbrica) {
  'use strict';
  const E = fabbrica();
  if (typeof module === 'object' && module.exports) module.exports = E;
  if (radice) radice.LectioEsempio = E;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  const OGGI = '2026-10-27';
  const copia = (x) => (x === undefined ? undefined : JSON.parse(JSON.stringify(x)));

  const TESTO_GEMINI = [
    'Trascrivi questa verifica in un file di testo, senza inventare niente. Usa sempre questo schema:',
    'MATERIA: … | PROF: … | DATA: … | TIPO: scritta/orale | VOTO: …',
    'Per ogni domanda:',
    '## Domanda N (punti: …)',
    'TESTO: la domanda parola per parola',
    'MIA RISPOSTA: quello che ho scritto io',
    'CORREZIONI DEL PROF: tutto quello che ha segnato o scritto, anche a penna o a margine, indicando a quale punto si riferisce',
    'PUNTI PRESI: …',
    'Alla fine: COMMENTO DEL PROF: …',
    'Se una parte non si legge scrivi [illeggibile], non indovinare.',
  ].join('\n');

  function materie() {
    return [
      { id: 2, nome: 'Diritto ed economia', prof: 'Bianchi', colore: 'petrolio', conteggi: { verifiche: 3, interrogazioni: 1, appunti: 2, lezioni: 1 }, centratura: { previste_uscite: 7, uscite_totali: 12, percentuale: 58 } },
      { id: 4, nome: 'Inglese', prof: 'Neri', colore: 'verde', conteggi: { verifiche: 2, interrogazioni: 0, appunti: 1, lezioni: 1 }, centratura: { previste_uscite: 3, uscite_totali: 6, percentuale: 50 } },
      { id: 5, nome: 'Matematica', prof: 'Russo', colore: 'blu', conteggi: { verifiche: 1, interrogazioni: 0, appunti: 2, lezioni: 0 }, centratura: null },
      { id: 3, nome: 'Scienze umane', prof: 'Verdi', colore: 'viola', conteggi: { verifiche: 2, interrogazioni: 1, appunti: 1, lezioni: 0 }, centratura: null },
      { id: 1, nome: 'Storia', prof: 'Rossi', colore: 'ambra', conteggi: { verifiche: 4, interrogazioni: 1, appunti: 3, lezioni: 2 }, centratura: { previste_uscite: 10, uscite_totali: 16, percentuale: 63 } },
    ];
  }

  function schede() {
    return {
      1: {
        materia_id: 1, aggiornata_il: '2026-10-24T18:10:00+02:00', basata_su: { verifiche: 4, interrogazioni: 1, esiti: 2 },
        argomenti: [
          { nome: 'Cause e conseguenze', volte: 4, esempio: 'Perché cadde la Repubblica?' },
          { nome: 'Date chiave', volte: 3, esempio: 'In che anno morì Augusto?' },
          { nome: 'Confronti tra imperatori', volte: 2, esempio: 'Confronta Tiberio e Claudio.' },
        ],
        tipi_domanda: [{ tipo: 'aperta', quota: 0.6 }, { tipo: 'definizione', quota: 0.25 }, { tipo: 'date', quota: 0.15 }],
        cosa_corregge: ['Vuole le date precise, non "verso il".', 'Toglie punti se manca il perché di un fatto.'],
        cosa_premia: ['I collegamenti tra fatti', 'Le parole giuste: principato, senato, pretoriani'],
        lunghezza_risposte: '5-8 righe per le domande aperte',
        consigli: ['Parti dalla definizione, poi un esempio'],
        note_mie: 'Chiede quasi sempre di Seiano.',
        stato: 'pronta',
      },
      2: {
        materia_id: 2, aggiornata_il: '2026-10-20T17:00:00+02:00', basata_su: { verifiche: 3, interrogazioni: 1, esiti: 1 },
        argomenti: [{ nome: 'Gerarchia delle fonti', volte: 3, esempio: 'Che cosa viene prima della legge?' }, { nome: 'Definizioni', volte: 2, esempio: 'Che cos\'è una consuetudine?' }],
        tipi_domanda: [{ tipo: 'definizione', quota: 0.5 }, { tipo: 'aperta', quota: 0.3 }, { tipo: 'vero_falso', quota: 0.2 }],
        cosa_corregge: ['Vuole le parole tecniche giuste.'], cosa_premia: ['Gli esempi presi dalla vita di tutti i giorni'],
        lunghezza_risposte: '3-5 righe', consigli: ['Comincia sempre dalla definizione'], note_mie: '', stato: 'pronta',
      },
    };
  }

  function materiale() {
    return {
      1: [
        { id: 10, materia_id: 1, tipo: 'appunti', titolo: 'Appunti: Tiberio e Caligola', data: '2026-10-24', origine: 'scritto', nome_file: null, caratteri: 4210, stato: 'pronto', errore: null, costo_eur: 0, creato_il: '2026-10-24T17:00:00+02:00' },
        { id: 9, materia_id: 1, tipo: 'appunti', titolo: 'Appunti: Augusto e il principato', data: '2026-10-22', origine: 'file', nome_file: 'augusto.docx', caratteri: 5320, stato: 'pronto', errore: null, costo_eur: 0, creato_il: '2026-10-22T17:00:00+02:00' },
        { id: 8, materia_id: 1, tipo: 'interrogazione', titolo: 'Interrogazione su Augusto', data: '2026-10-20', origine: 'scritto', nome_file: null, caratteri: 1210, stato: 'pronto', errore: null, costo_eur: 0, creato_il: '2026-10-20T15:00:00+02:00' },
        { id: 7, materia_id: 1, tipo: 'verifica', titolo: 'Verifica sulla Repubblica', data: '2026-10-12', origine: 'file', nome_file: 'verifica.txt', caratteri: 5321, stato: 'pronto', errore: null, costo_eur: 0, creato_il: '2026-10-13T16:00:00+02:00' },
      ],
      2: [
        { id: 20, materia_id: 2, tipo: 'verifica', titolo: 'Verifica sulle fonti', data: '2026-10-05', origine: 'file', nome_file: 'fonti.pdf', caratteri: 3900, stato: 'pronto', errore: null, costo_eur: 0, creato_il: '2026-10-06T16:00:00+02:00' },
      ],
    };
  }

  function eventi() {
    return [
      { id: 4, materia_id: 4, tipo: 'scritta', argomento: 'Present perfect e past simple', data: '2026-10-26', esito: null, lezione_id: 9 },
      { id: 3, materia_id: 1, tipo: 'scritta', argomento: 'Impero romano da Augusto a Nerone', data: '2026-10-29', esito: null, lezione_id: 12 },
      { id: 5, materia_id: 3, tipo: 'orale', argomento: 'Freud e la psicoanalisi', data: '2026-11-02', esito: null, lezione_id: null },
      { id: 6, materia_id: 5, tipo: 'scritta', argomento: 'Equazioni di secondo grado', data: '2026-11-04', esito: null, lezione_id: null },
      { id: 7, materia_id: 2, tipo: 'scritta', argomento: 'Le fonti del diritto', data: '2026-11-09', esito: null, lezione_id: 10 },
    ];
  }

  function capitoliEDomande(durata, titoli, domande, risposte) {
    const n = titoli.length;
    const passo = durata / n;
    const capitoli = titoli.map((t, i) => ({ n: i + 1, titolo: t, inizio_s: Math.round(i * passo * 10) / 10, fine_s: Math.round((i + 1) * passo * 10) / 10 }));
    const dom = [];
    let id = 100;
    capitoli.forEach((c, i) => {
      if (i < n - 1) {
        const fine = c.fine_s;
        dom.push({ id: id++, tipo: 'blocco', testo: domande[i], risposta: risposte[i], inizio_s: fine - 20.6, fine_domanda_s: fine - 14.1, inizio_risposta_s: fine - 11.6, fine_s: fine });
      }
    });
    return { capitoli, domande: dom };
  }

  function lezioni() {
    const l12 = {
      id: 12, materia_id: 1, evento_id: 3, argomento: 'L\'Impero romano da Augusto a Nerone', durata_min: 20, durata_s: 1187, voce: 'mistral:voice_482', domande_saltabili: false,
      stato: 'pronta', avanzamento: { fase: null, fatto: 7, totale: 7, frase: null }, costo_eur: 0.24, creata_il: '2026-10-27T18:02:00+01:00', pronta_il: '2026-10-27T18:09:00+01:00',
      errore: null, nota: null, ascolto: { posizione_s: 0, finita: false, aggiornato_il: '2026-10-27T18:09:00+01:00' }, audio: { byte: 9496000, sha256: 'a'.repeat(64) },
      capitoli: [
        { n: 1, titolo: 'Augusto e il principato', inizio_s: 0, fine_s: 301 },
        { n: 2, titolo: 'Tiberio e i processi', inizio_s: 301, fine_s: 589 },
        { n: 3, titolo: 'Caligola e Claudio', inizio_s: 589, fine_s: 901 },
        { n: 4, titolo: 'Interrogazione', inizio_s: 901, fine_s: 1187 },
      ],
      domande: [
        { id: 31, tipo: 'blocco', n: 1, di: 6, testo: 'Perché Augusto non si fece mai chiamare re?', risposta: 'Perché a Roma la parola re ricordava la tirannia: Augusto preferì presentarsi come il primo cittadino, il princeps.', inizio_s: 280.4, fine_domanda_s: 286.9, inizio_risposta_s: 289.4, fine_s: 301.0 },
        { id: 32, tipo: 'blocco', n: 2, di: 6, testo: 'Perché Tiberio passò gli ultimi anni a Capri, lontano da Roma?', risposta: 'Perché non si fidava del senato e temeva le congiure: lasciò Roma a Seiano, il capo dei pretoriani, che però tramò contro di lui.', inizio_s: 566.0, fine_domanda_s: 572.8, inizio_risposta_s: 575.3, fine_s: 589.0 },
        { id: 33, tipo: 'blocco', n: 3, di: 6, testo: 'Che cosa fece Claudio per la Britannia?', risposta: 'La conquistò in parte nel 43 dopo Cristo e ne fece una provincia romana.', inizio_s: 878.0, fine_domanda_s: 884.0, inizio_risposta_s: 886.5, fine_s: 901.0 },
        { id: 34, tipo: 'interrogazione', n: 4, di: 6, testo: 'Che differenza c\'è tra principato e monarchia?', risposta: 'Nel principato le istituzioni della Repubblica restano in piedi, ma il potere vero è nelle mani del princeps.', inizio_s: 925.0, fine_domanda_s: 931.0, inizio_risposta_s: 933.5, fine_s: 985.0 },
        { id: 35, tipo: 'interrogazione', n: 5, di: 6, testo: 'Chi era Seiano e perché cadde?', risposta: 'Era il prefetto del pretorio di Tiberio. Cadde nel 31 dopo Cristo, quando Tiberio scoprì che voleva prendere il potere.', inizio_s: 995.0, fine_domanda_s: 1001.0, inizio_risposta_s: 1003.5, fine_s: 1060.0 },
        { id: 36, tipo: 'interrogazione', n: 6, di: 6, testo: 'Quali errori di Caligola gli costarono la vita?', risposta: 'Governò da padrone assoluto, umiliò il senato e svuotò le casse: i pretoriani lo uccisero nel 41.', inizio_s: 1070.0, fine_domanda_s: 1076.0, inizio_risposta_s: 1078.5, fine_s: 1140.0 },
      ],
      testo: [
        { capitolo: 1, paragrafi: ['Oggi vediamo come Roma passa dalla Repubblica all\'Impero, e perché il prof Rossi lo chiede quasi sempre: è una storia di cause e conseguenze.', 'Nel 27 avanti Cristo Ottaviano riceve dal senato il nome di Augusto. Non si fa chiamare re: si presenta come il primo cittadino.', 'Occhio: questo lo chiede quasi sempre. Il principato tiene in piedi le istituzioni della Repubblica, ma il potere vero è suo.'] },
        { capitolo: 2, paragrafi: ['Immagina un imperatore che non si fida di nessuno. È Tiberio, il figlio adottivo di Augusto.', 'Tiberio governa con attenzione ai conti, ma i processi per lesa maestà riempiono Roma di paura.', 'Dal 26 dopo Cristo vive a Capri e lascia Roma a Seiano, il capo dei pretoriani.'] },
        { capitolo: 3, paragrafi: ['Caligola parte bene e finisce malissimo: governa da padrone assoluto e umilia il senato.', 'Claudio, suo zio, sembra debole ma organizza bene lo Stato e conquista la Britannia nel 43.'] },
        { capitolo: 4, paragrafi: ['Adesso ti interrogo come farebbe il prof Rossi. Rispondi a voce, poi senti la risposta.', 'Tre errori da non fare con il prof Rossi: date vaghe, fatti senza il perché, imperatori confusi tra loro.'] },
      ],
    };
    const g10 = capitoliEDomande(1150, ['Che cos\'è una fonte', 'La gerarchia', 'La Costituzione', 'Interrogazione'],
      ['Che cos\'è una fonte del diritto?', 'Quale fonte sta più in alto?', 'Quando è entrata in vigore la Costituzione?'],
      ['È un atto o un fatto che crea norme giuridiche.', 'La Costituzione, poi le leggi, poi i regolamenti.', 'Il primo gennaio 1948.']);
    const g9 = capitoliEDomande(905, ['Il present perfect', 'Il past simple', 'Interrogazione'],
      ['Quando si usa il present perfect?', 'Che cosa cambia con yesterday?'],
      ['Per azioni passate legate al presente.', 'Con un tempo finito si usa il past simple.']);
    const g8 = capitoliEDomande(1790, ['I Gracchi', 'Mario e Silla', 'Pompeo e Cesare', 'La fine della Repubblica', 'Interrogazione'],
      ['Che cosa volevano i Gracchi?', 'Che cosa cambiò Mario nell\'esercito?', 'Che cos\'era il primo triumvirato?', 'Perché Cesare fu ucciso?'],
      ['Dare terre ai poveri.', 'Aprì l\'esercito ai nullatenenti.', 'Un accordo privato tra Cesare, Pompeo e Crasso.', 'Perché i senatori temevano che diventasse re.']);
    const testoDi = (caps) => caps.map((c) => ({ capitolo: c.n, paragrafi: ['Testo d\'esempio del capitolo "' + c.titolo + '".'] }));
    return [
      l12,
      Object.assign({
        id: 10, materia_id: 2, evento_id: 7, argomento: 'Le fonti del diritto', durata_min: 20, durata_s: 1150, voce: 'azure:it-IT-IsabellaMultilingualNeural', stato: 'pronta', domande_saltabili: true,
        avanzamento: { fase: null, fatto: 0, totale: 0, frase: null }, costo_eur: 0.02, creata_il: '2026-10-25T17:00:00+02:00', pronta_il: '2026-10-25T17:06:00+02:00', errore: null, nota: 'Gli appunti bastavano per circa 12 minuti: ho aggiunto esempi e domande.',
        ascolto: { posizione_s: 667, finita: false, aggiornato_il: '2026-10-26T08:10:00+01:00' }, audio: { byte: 9200000, sha256: 'b'.repeat(64) },
      }, g10, { testo: testoDi(g10.capitoli) }),
      Object.assign({
        id: 9, materia_id: 4, evento_id: 4, argomento: 'Present perfect e past simple', durata_min: 15, durata_s: 905, voce: 'mistral:voice_482', stato: 'pronta',
        avanzamento: { fase: null, fatto: 0, totale: 0, frase: null }, costo_eur: 0.20, creata_il: '2026-10-24T17:00:00+02:00', pronta_il: '2026-10-24T17:05:00+02:00', errore: null, nota: null,
        ascolto: { posizione_s: 905, finita: true, aggiornato_il: '2026-10-26T20:00:00+01:00' }, audio: { byte: 7200000, sha256: 'c'.repeat(64) },
      }, g9, { testo: testoDi(g9.capitoli) }),
      Object.assign({
        id: 8, materia_id: 1, evento_id: null, argomento: 'La crisi della Repubblica', durata_min: 30, durata_s: 1790, voce: 'mistral:voice_482', stato: 'pronta',
        avanzamento: { fase: null, fatto: 0, totale: 0, frase: null }, costo_eur: 0.36, creata_il: '2026-10-10T17:00:00+02:00', pronta_il: '2026-10-10T17:09:00+02:00', errore: null, nota: null,
        ascolto: { posizione_s: 1790, finita: true, aggiornato_il: '2026-10-11T20:00:00+02:00' }, audio: { byte: 14300000, sha256: 'd'.repeat(64) },
      }, g8, { testo: testoDi(g8.capitoli) }),
    ];
  }

  const VOCI = [
    { id: 'mistral:voice_482', nome: 'Lorenzo', tipo: 'espressiva', fornitore: 'mistral', euro_al_minuto: 0.0126, disponibile: true },
    { id: 'azure:it-IT-IsabellaMultilingualNeural', nome: 'Isabella', tipo: 'gratis', fornitore: 'azure', euro_al_minuto: 0, disponibile: true },
  ];

  /** Un server finto nuovo, coi dati di partenza. `opzioni.vuoto` = primo uso (nessuna materia). */
  function crea(opzioni) {
    const o = opzioni || {};
    const s = {
      oggi: OGGI,
      materie: o.vuoto ? [] : materie(),
      schede: o.vuoto ? {} : schede(),
      materiale: o.vuoto ? {} : materiale(),
      eventi: o.vuoto ? [] : eventi(),
      lezioni: o.vuoto ? [] : lezioni(),
      impostazioni: { tetto_eur: 2.0, voce_predefinita: 'mistral:voice_482', durata_predefinita: 20, fermati_alle_domande: true, risposte_scritte: true, domande_saltabili: false, velocita: 1.0, nome: 'Andrea' },
      ripassare: o.vuoto ? [] : [
        { domanda_id: 32, lezione_id: 12, lezione: 'L\'Impero romano da Augusto a Nerone', testo: 'Perché Tiberio passò gli ultimi anni a Capri, lontano da Roma?', esito: 'parziale', commento: 'Giusto: non si fidava del senato. Manca chi governava Roma al posto suo.', quando: '2026-10-26T18:30:00+01:00' },
        { domanda_id: 35, lezione_id: 12, lezione: 'L\'Impero romano da Augusto a Nerone', testo: 'Chi era Seiano e perché cadde?', esito: 'sbagliata', commento: 'Seiano non era un senatore: era il capo dei pretoriani.', quando: '2026-10-26T18:40:00+01:00' },
        { domanda_id: 33, lezione_id: 12, lezione: 'L\'Impero romano da Augusto a Nerone', testo: 'Che cosa fece Claudio per la Britannia?', esito: 'saltata', commento: null, quando: '2026-10-26T18:35:00+01:00' },
      ],
      spesa: { mese: '2026-10', speso_eur: o.speso != null ? o.speso : 0.8, tetto_eur: 2.0, si_azzera_il: '2026-11-01', voce_gratis: { caratteri_usati: 88000, caratteri_mese: 500000 }, voci: [] },
      prossimoId: 200,
      chiamate: [],
    };

    const materia = (id) => s.materie.find((m) => m.id === Number(id)) || null;
    const breve = (m) => (m ? { id: m.id, nome: m.nome, colore: m.colore } : null);
    const listaLezione = (l) => {
      const c = copia(l);
      delete c.capitoli; delete c.domande; delete c.testo;
      return c;
    };
    const avanza = (l) => {
      // In esempio una lezione chiesta avanza da sola, una fase ogni pochi secondi.
      if (!l._t0 || (l.stato !== 'in_coda' && l.stato !== 'in_preparazione')) return;
      const passati = (Date.now() - l._t0) / 1000;
      const fasi = [['in_coda', null, 0, 0], ['in_preparazione', 'lettura', 0, 0], ['in_preparazione', 'scrittura', 0, 0], ['in_preparazione', 'voce', 3, 7], ['in_preparazione', 'voce', 6, 7], ['in_preparazione', 'montaggio', 0, 0]];
      const i = Math.floor(passati / 2.5);
      if (i >= fasi.length) {
        Object.assign(l, lezioni()[0], { id: l.id, materia_id: l.materia_id, evento_id: l.evento_id, argomento: l.argomento, durata_min: l.durata_min, stato: 'pronta', ascolto: { posizione_s: 0, finita: false, aggiornato_il: new Date().toISOString() } });
        delete l._t0;
        return;
      }
      const [stato, fase, fatto, totale] = fasi[i];
      l.stato = stato;
      l.avanzamento = { fase, fatto, totale, frase: fase === 'voce' ? 'Registro la voce · ' + fatto + ' di ' + totale : null };
    };
    const spesa = () => {
      const p = Math.round(s.spesa.speso_eur / s.spesa.tetto_eur * 100);
      return Object.assign(copia(s.spesa), { percentuale: p });
    };
    const stima = (corpo) => {
      const d = Number(corpo.durata_min) || 20;
      const voce = VOCI.find((v) => v.id === corpo.voce) || VOCI[0];
      const testo = Math.ceil(d / 10) / 100 + (d >= 30 ? 0 : 0.01) * (d < 30 ? 1 : 0);
      const costoTesto = d <= 20 ? 0.02 : d <= 30 ? 0.03 : 0.04;
      const costoVoce = voce.tipo === 'espressiva' ? Math.ceil(d * 1.15) / 100 : 0;
      const costo = Math.round((costoTesto + costoVoce) * 100) / 100;
      const dopo = Math.round((s.spesa.speso_eur + costo) * 100) / 100;
      const consentita = dopo <= s.impostazioni.tetto_eur + 1e-9;
      void testo;
      return {
        caratteri_stimati: Math.round(d * 145 * 6.3), costo_testo_eur: costoTesto, costo_voce_eur: costoVoce, costo_eur: costo,
        spesa_mese_eur: s.spesa.speso_eur, tetto_eur: s.impostazioni.tetto_eur, dopo_eur: dopo, consentita,
        motivo: consentita ? null : 'Sforerebbe il tetto di ' + String(s.impostazioni.tetto_eur).replace('.', ',') + ' €: usa la voce gratis',
        voce_gratis_caratteri_rimasti: 412000, si_azzera_il: s.spesa.si_azzera_il,
      };
    };
    const daRipassare = (materiaId) => copia(s.ripassare.filter((x) => {
      const l = s.lezioni.find((y) => y.id === x.lezione_id);
      return l && l.materia_id === materiaId;
    }));
    /**
     * Il correttore finto dell'esempio (come la modalità prova del server): conta quante parole importanti della
     * risposta giusta ci sono nella risposta scritta.
     */
    const correggi = (d, testo) => {
      const importanti = (t) => String(t || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').split(/[^a-z0-9]+/).filter((w) => w.length >= 5);
      const attese = Array.from(new Set(importanti(d.risposta)));
      const scritte = new Set(importanti(testo));
      const prese = attese.filter((w) => scritte.has(w));
      const quota = attese.length ? prese.length / attese.length : 0;
      const mancanti = attese.filter((w) => !scritte.has(w)).slice(0, 3);
      if (quota >= 0.5) return { esito: 'giusta', commento: 'Esatto: hai detto le cose che contano.', mancava: [] };
      if (quota >= 0.15) return { esito: 'parziale', commento: 'Ci sei in parte. Aggiungi le parole che mancano.', mancava: mancanti };
      return { esito: 'sbagliata', commento: 'Questa volta non ci siamo: ascolta la risposta giusta.', mancava: mancanti };
    };
    const tra = (data) => Math.round((new Date(data + 'T00:00:00') - new Date(s.oggi + 'T00:00:00')) / 86400000);
    const oggi = () => {
      const futuri = s.eventi.filter((e) => tra(e.data) >= 0).sort((a, b) => a.data.localeCompare(b.data));
      const p = futuri[0] || null;
      const lezioneDi = (e) => {
        const l = e && e.lezione_id ? s.lezioni.find((x) => x.id === e.lezione_id) : null;
        if (l) avanza(l);
        return l ? listaLezione(l) : null;
      };
      const ascoltate = s.lezioni.filter((l) => l.stato === 'pronta' && l.ascolto && l.ascolto.posizione_s > 0 && !l.ascolto.finita)
        .sort((a, b) => String(b.ascolto.aggiornato_il).localeCompare(String(a.ascolto.aggiornato_il)));
      return {
        oggi: s.oggi,
        prossima: p ? { evento: copia(p), materia: breve(materia(p.materia_id)), lezione: lezioneDi(p), tra_giorni: tra(p.data) } : null,
        riprendi: ascoltate[0] ? listaLezione(ascoltate[0]) : null,
        da_raccontare: s.eventi.filter((e) => tra(e.data) < 0 && tra(e.data) >= -30 && !e.esito && e.lezione_id).map((e) => ({ evento: copia(e), materia: breve(materia(e.materia_id)) })),
        piu_avanti: futuri.slice(1, 5).map((e) => ({ evento: copia(e), materia: breve(materia(e.materia_id)), tra_giorni: tra(e.data) })),
        spesa: Object.assign(spesa(), { avviso: s.spesa.speso_eur >= 0.8 * s.spesa.tetto_eur }),
        modalita_prova: false,
      };
    };

    function rotta(metodo, percorso, corpo) {
      const [p, q] = percorso.split('?');
      const qs = new URLSearchParams(q || '');
      let m;
      if (p === '/server/api/oggi') return [200, oggi()];
      if (p === '/server/api/salute') return [200, { ok: true, versione: '1.0.0', modalita_prova: false, testo: { stato: 'pronto', fornitore: 'mistral' }, voce_espressiva: { stato: 'pronta', fornitore: 'mistral' }, voce_gratis: { stato: 'pronta', fornitore: 'azure' }, mancano: [], coda: { lezioni_in_preparazione: 0 } }];
      if (p === '/server/api/materie' && metodo === 'GET') {
        return [200, copia(s.materie).sort((a, b) => a.nome.localeCompare(b.nome)).map((x) => Object.assign(x, { da_ripassare: daRipassare(x.id).length }))];
      }
      if ((m = /^\/server\/api\/materie\/(\d+)\/da_ripassare$/.exec(p))) return [200, daRipassare(Number(m[1]))];
      if ((m = /^\/server\/api\/lezioni\/(\d+)\/domande\/(\d+)\/risposta$/.exec(p)) && metodo === 'POST') {
        const l = s.lezioni.find((x) => x.id === Number(m[1]));
        const d = l && (l.domande || []).find((x) => x.id === Number(m[2]));
        if (!d) return [404, { errore: 'Questa domanda non c\'è più.', codice: 'non_trovato' }];
        const esito = corpo.saltata ? { esito: 'saltata', commento: null, mancava: [] } : correggi(d, corpo.testo);
        const r = Object.assign({ id: s.prossimoId++, domanda_id: d.id, punteggio: esito.esito === 'giusta' ? 1 : esito.esito === 'parziale' ? 0.5 : 0, risposta_attesa: d.risposta, costo_eur: 0, quando: new Date().toISOString() }, esito);
        d.mia_risposta = { testo: corpo.testo || '', esito: r.esito, commento: r.commento, mancava: r.mancava };
        s.ripassare = s.ripassare.filter((x) => !(x.lezione_id === l.id && x.domanda_id === d.id));
        if (r.esito !== 'giusta') s.ripassare.unshift({ domanda_id: d.id, lezione_id: l.id, lezione: l.argomento, testo: d.testo, esito: r.esito, commento: r.commento, quando: r.quando });
        return [200, r];
      }
      if (p === '/server/api/materie' && metodo === 'POST') {
        const nome = String(corpo.nome || '').trim();
        if (!nome) return [422, { errore: 'Scrivi il nome della materia.', codice: 'non_valido' }];
        if (s.materie.some((x) => x.nome.toLowerCase() === nome.toLowerCase())) return [409, { errore: 'C\'è già una materia con questo nome.', codice: 'conflitto' }];
        const nuova = { id: s.prossimoId++, nome, prof: corpo.prof || '', colore: corpo.colore || 'ambra', conteggi: { verifiche: 0, interrogazioni: 0, appunti: 0, lezioni: 0 }, centratura: null };
        s.materie.push(nuova);
        return [201, copia(nuova)];
      }
      if ((m = /^\/server\/api\/materie\/(\d+)$/.exec(p))) {
        const x = materia(m[1]);
        if (!x) return [404, { errore: 'Questa materia non c\'è più.', codice: 'non_trovato' }];
        if (metodo === 'PATCH') { Object.assign(x, corpo); return [200, copia(x)]; }
        if (metodo === 'DELETE') { s.materie = s.materie.filter((y) => y !== x); return [200, { ok: true }]; }
        return [200, copia(x)];
      }
      if ((m = /^\/server\/api\/materie\/(\d+)\/scheda$/.exec(p))) {
        const sc = s.schede[m[1]] || { materia_id: Number(m[1]), aggiornata_il: null, basata_su: { verifiche: 0, interrogazioni: 0, esiti: 0 }, argomenti: [], tipi_domanda: [], cosa_corregge: [], cosa_premia: [], lunghezza_risposte: '', consigli: [], note_mie: '', stato: 'vuota' };
        if (metodo === 'PUT') { s.schede[m[1]] = Object.assign(sc, corpo, { stato: sc.stato === 'vuota' ? 'pronta' : sc.stato }); return [200, copia(s.schede[m[1]])]; }
        return [200, copia(sc)];
      }
      if ((m = /^\/server\/api\/materie\/(\d+)\/scheda\/aggiorna$/.exec(p))) return [200, { ok: true }];
      if ((m = /^\/server\/api\/materie\/(\d+)\/materiale$/.exec(p))) {
        const lista = s.materiale[m[1]] || (s.materiale[m[1]] = []);
        if (metodo === 'POST') {
          const nuovo = { id: s.prossimoId++, materia_id: Number(m[1]), tipo: corpo.tipo || 'appunti', titolo: corpo.titolo || 'Appunti', data: corpo.data || s.oggi, origine: 'scritto', nome_file: null, caratteri: String(corpo.testo || '').length, stato: 'pronto', errore: null, costo_eur: 0, creato_il: new Date().toISOString() };
          lista.unshift(nuovo);
          return [201, copia(nuovo)];
        }
        return [200, copia(lista)];
      }
      if ((m = /^\/server\/api\/materiale\/(\d+)$/.exec(p))) {
        const tutti = Object.values(s.materiale).flat();
        const x = tutti.find((y) => y.id === Number(m[1]));
        if (!x) return [404, { errore: 'Questo materiale non c\'è più.', codice: 'non_trovato' }];
        if (metodo === 'DELETE') { s.materiale[x.materia_id] = s.materiale[x.materia_id].filter((y) => y !== x); return [200, { ok: true }]; }
        return [200, Object.assign(copia(x), { testo: 'Testo d\'esempio letto dal server per "' + x.titolo + '".\n\nMATERIA: Storia | PROF: Rossi | DATA: 12/10 | TIPO: scritta | VOTO: 7\n## Domanda 1 (punti: 2)\nTESTO: Perché cadde la Repubblica?' })];
      }
      if (p === '/server/api/eventi' && metodo === 'GET') return [200, copia(s.eventi)];
      if ((m = /^\/server\/api\/materie\/(\d+)\/eventi$/.exec(p)) && metodo === 'POST') {
        const e = { id: s.prossimoId++, materia_id: Number(m[1]), tipo: corpo.tipo || 'scritta', argomento: corpo.argomento || '', data: corpo.data, esito: null, lezione_id: null };
        s.eventi.push(e);
        return [201, copia(e)];
      }
      if ((m = /^\/server\/api\/eventi\/(\d+)\/esito$/.exec(p))) {
        const e = s.eventi.find((x) => x.id === Number(m[1]));
        const uscite = (corpo.domande_uscite || []).length;
        const altre = String(corpo.altre_domande || '').split('\n').filter((r) => r.trim()).length;
        const esito = { previste_uscite: uscite, totale_vere: uscite + altre, percentuale: uscite + altre ? Math.round(uscite / (uscite + altre) * 100) : 0 };
        if (e) e.esito = esito;
        return [200, esito];
      }
      if ((m = /^\/server\/api\/eventi\/(\d+)$/.exec(p))) {
        const e = s.eventi.find((x) => x.id === Number(m[1]));
        if (!e) return [404, { errore: 'Questa data non c\'è più.', codice: 'non_trovato' }];
        if (metodo === 'DELETE') { s.eventi = s.eventi.filter((x) => x !== e); return [200, { ok: true }]; }
        if (metodo === 'PATCH') Object.assign(e, corpo);
        return [200, copia(e)];
      }
      if (p === '/server/api/voci') return [200, copia(VOCI)];
      if (p === '/server/api/lezioni/stima') return [200, stima(corpo || {})];
      if (p === '/server/api/spesa') return [200, spesa()];
      if (p === '/server/api/impostazioni') {
        if (metodo === 'PATCH') Object.assign(s.impostazioni, corpo);
        return [200, copia(s.impostazioni)];
      }
      if (p === '/server/api/gemini') return [200, { testo: TESTO_GEMINI, aggiornato_il: '2026-10-01T10:00:00+02:00' }];
      if (p === '/server/api/novita') return [200, []];
      if (p === '/server/api/lezioni' && metodo === 'GET') {
        s.lezioni.forEach(avanza);
        const id = Number(qs.get('materia_id')) || null;
        return [200, s.lezioni.filter((l) => !id || l.materia_id === id).map(listaLezione)];
      }
      if (p === '/server/api/lezioni' && metodo === 'POST') {
        const st = stima(corpo || {});
        if (!st.consentita) return [402, { errore: st.motivo, codice: 'tetto_raggiunto' }];
        const l = {
          id: s.prossimoId++, materia_id: Number(corpo.materia_id), evento_id: corpo.evento_id || null, argomento: corpo.argomento || 'Lezione', durata_min: Number(corpo.durata_min) || 20, durata_s: null,
          voce: corpo.voce, domande_saltabili: corpo.domande_saltabili != null ? !!corpo.domande_saltabili : !!s.impostazioni.domande_saltabili, stato: 'in_coda', avanzamento: { fase: null, fatto: 0, totale: 0, frase: null }, costo_eur: 0, creata_il: new Date().toISOString(), pronta_il: null, errore: null, nota: null,
          ascolto: { posizione_s: 0, finita: false, aggiornato_il: null }, audio: null, _t0: Date.now(),
        };
        s.lezioni.unshift(l);
        return [201, listaLezione(l)];
      }
      if ((m = /^\/server\/api\/lezioni\/(\d+)$/.exec(p))) {
        const l = s.lezioni.find((x) => x.id === Number(m[1]));
        if (!l) return [404, { errore: 'Questa lezione non c\'è più.', codice: 'non_trovato' }];
        if (metodo === 'DELETE') { s.lezioni = s.lezioni.filter((x) => x !== l); return [200, { ok: true }]; }
        if (metodo === 'PATCH') { if (corpo.domande_saltabili != null) l.domande_saltabili = !!corpo.domande_saltabili; return [200, listaLezione(l)]; }
        avanza(l);
        const c = copia(l);
        delete c._t0;
        return [200, c];
      }
      if ((m = /^\/server\/api\/lezioni\/(\d+)\/ascolto$/.exec(p))) {
        const l = s.lezioni.find((x) => x.id === Number(m[1]));
        if (l) l.ascolto = { posizione_s: corpo.posizione_s, finita: !!corpo.finita, aggiornato_il: corpo.aggiornato_il || new Date().toISOString() };
        return [200, { ok: true }];
      }
      if ((m = /^\/server\/api\/lezioni\/(\d+)\/(annulla|riprova)$/.exec(p))) {
        const l = s.lezioni.find((x) => x.id === Number(m[1]));
        if (l && m[2] === 'annulla') l.stato = 'annullata';
        if (l && m[2] === 'riprova') { l.stato = 'in_coda'; l.errore = null; l._t0 = Date.now(); }
        return [200, l ? listaLezione(l) : {}];
      }
      // Il programma, in esempio: niente scaricamenti e niente tasti; il lettore usa un orologio finto.
      if (p === '/locale/stato') return [200, { abbinato: true, server: 'https://lectio.esempio.ts.net', versione: '1.0.0', tutorial_visto: true, tema: 'sistema', in_preparazione: false, scaricate: [12, 10], byte_scaricate: 18696000, aggiornamento: { attivo: false } }];
      if ((m = /^\/locale\/lezioni\/(\d+)\/scarica$/.exec(p))) return [200, { ok: true, url: null, finto: true, byte: 0 }];
      if ((m = /^\/locale\/lezioni\/(\d+)\/scaricamento$/.exec(p))) return [200, { in_corso: false, percentuale: 100, scaricata: true }];
      if (p === '/locale/lezioni/scaricate') return [200, { lezioni: [{ id: 12, byte: 9496000 }, { id: 10, byte: 9200000 }], byte: 18696000 }];
      if (p.startsWith('/locale/')) return [200, { ok: true }];
      return [404, { errore: 'Nell\'esempio questa parte non c\'è.', codice: 'non_trovato' }];
    }

    return {
      stato: s,
      richiesta(metodo, percorso, corpo) {
        s.chiamate.push(metodo + ' ' + percorso);
        const [stato, dati] = rotta(metodo, percorso, corpo === undefined ? {} : copia(corpo) || {});
        return { stato, dati: copia(dati) };
      },
    };
  }

  return { crea, OGGI, TESTO_GEMINI };
});
