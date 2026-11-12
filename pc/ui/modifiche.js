/*
 * Lectio per il computer: Modifica, Correggi a mano e Torna a prima (docs/aspetto.md §44, contratto §28).
 * I pezzi comuni alle quattro pagine (schema, riassunto, verifica simulata, lettore): la finestra "Cosa vuoi
 * cambiare?", la scheda della modifica che lavora con il suo giro ogni 4 secondi, lo scambio con la versione messa da
 * parte e la modalità "Correggi a mano" di schemi e riassunti. Le pagine decidono solo dove metterli.
 * Il testo che arriva dal server entra come testo; i campi della correzione mostrano il grassetto come **parola**.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  const CARTELLA = { lezione: 'lezioni', schema: 'schemi', riassunto: 'riassunti', simulata: 'simulate' };
  const percorso = (tipo, id) => '/server/api/' + CARTELLA[tipo] + '/' + id;
  A.percorsoOggetto = percorso;
  const chiaveNota = (tipo, id) => 'lectio.modifica.nota.' + tipo + '.' + id;
  const chiaveVista = (tipo, id) => 'lectio.modifica.vista.' + tipo + '.' + id;
  const UN_GIORNO = 24 * 3600 * 1000;
  const copia = (x) => (x == null ? x : JSON.parse(JSON.stringify(x)));

  /** L'oggetto si può cambiare adesso (contratto §28.1: fuori da questi stati il server risponde 409). */
  function pronto(tipo, x) {
    if (!x) return false;
    if (tipo === 'lezione') return x.stato === 'pronta';
    if (tipo === 'simulata') return ['pronta', 'in_corso', 'corretta'].includes(x.stato);
    return x.stato === 'pronto';
  }

  /** §44.1 Perché Modifica e Correggi a mano sono spenti (null = si può). */
  A.motivoModifica = (tipo, x) => L.motivoSpentaModifica({
    senzaRete: !!S.senzaRete, pronto: pronto(tipo, x), modifica: x && x.modifica, conParti: L.partiModificabili(x, tipo).length > 0,
  });
  /** Perché Torna a prima è spento: senza rete o mentre una modifica lavora (contratto §28.2, il 409). */
  A.motivoVersione = (x) => (S.senzaRete ? 'Serve la rete' : L.modificaAttiva(x && x.modifica) ? 'C\'è già una modifica in corso' : null);

  // --- §44.2 La finestra "Cosa vuoi cambiare?" -------------------------------------------------------------------

  /**
   * o = { tipo, oggetto, titolo, materia, inviata(modifica) }. La stima si chiede 400 ms dopo l'ultimo cambiamento;
   * Modifica si accende solo con la stima di quella richiesta arrivata e consentita.
   */
  A.apriModifica = function (o) {
    const tipo = o.tipo;
    const x = o.oggetto || {};
    const m = o.materia || { colore: 'ambra' };
    const cfg = L.MODIFICA_PER_TIPO[tipo];
    const parti = L.partiModificabili(x, tipo);
    const corretta = tipo === 'simulata' && x.stato === 'corretta';
    const seq = L.creaSequenza();
    const st = { dove: 'tutto', scelte: new Set(), stima: null, chiaveStima: null, erroreStima: null, invio: false, errore: null, statoErrore: null, timer: null, chiusa: false };

    const corpo = h('div', { class: 'corpo bianco' });
    const piede = h('div', { class: 'piede' });
    const ctl = A.apriFinestra({
      stretta: true, etichetta: 'Cosa vuoi cambiare?', classe: 'finestra-nuovo finestra-modifica ' + A.classeMateria(m.colore),
      disegna: (f) => svuota(f, corpo, piede),
      suChiusura: () => { st.chiusa = true; clearTimeout(st.timer); },
    });
    ctl.tipo = 'modifica';

    const campo = h('textarea', { class: 'mod-campo', 'data-campo': 'richiesta', maxlength: String(L.MASSIMO_RICHIESTA), placeholder: cfg.segnaposto, 'aria-label': 'Cosa vuoi cambiare', rows: '4' });
    const conta = h('span', { class: 'mod-conta', 'data-conta': 'richiesta' });
    const sceltaDove = h('div', { class: 'segmenti mod-dove', role: 'group', 'aria-label': 'Dove?' });
    sceltaDove.style.setProperty('--n', '2');
    const sottoDove = h('div', { class: 'mod-sotto-dove' });
    const riepilogo = h('div', { class: 'mod-riepilogo', 'data-riepilogo': 'modifica', 'aria-live': 'polite' });
    const pulsante = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'manda-modifica', onclick: invia }, 'Modifica');
    const sotto = h('div', { class: 'mod-sotto', 'data-sotto': 'modifica' });

    campo.addEventListener('input', cambiato);
    campo.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); invia(); } });

    svuota(corpo,
      h('div', { class: 'testa-finestra' },
        h('div', { class: 'mod-titoli' }, h('h2', { class: 'titolo-sezione titolo-finestra' }, 'Cosa vuoi cambiare?'), o.titolo ? h('span', { class: 'mod-oggetto', 'data-oggetto': 'titolo' }, o.titolo) : null),
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', 'data-azione': 'chiudi-modifica', onclick: () => ctl.chiudi() }, icona('chiudi'))),
      h('div', { class: 'mod-gruppo' }, campo, conta),
      h('div', { class: 'mod-spunti', 'data-spunti': tipo }, cfg.spunti.map((f) => h('button', { type: 'button', class: 'mod-spunto', 'data-spunto': f, onclick: () => spunto(f) }, f))),
      h('div', { class: 'mod-gruppo' }, h('span', { class: 'mod-etichetta' }, 'Dove?'), sceltaDove, sottoDove),
      riepilogo);
    svuota(piede, pulsante, sotto);

    const richiesta = () => campo.value.trim();
    const partiScelte = () => (st.dove === 'tutto' ? [] : Array.from(st.scelte).sort((a, b) => a - b));
    const completa = () => !!richiesta() && (st.dove === 'tutto' || st.scelte.size > 0);
    const chiave = () => [richiesta(), st.dove, partiScelte().join(',')].join('|');

    function cambiato() {
      const c = L.contatoreRichiesta(campo.value);
      conta.textContent = c.testo;
      conta.classList.toggle('avviso', c.avviso);
      st.errore = null;
      clearTimeout(st.timer);
      if (!completa()) {
        seq.nuova(); // una stima ancora in viaggio non vale più
        st.stima = null;
        st.chiaveStima = null;
        st.erroreStima = null;
      } else if (chiave() !== st.chiaveStima) {
        st.timer = setTimeout(chiediStima, T.stimaModifica);
      }
      disegnaRiepilogo();
    }

    async function chiediStima() {
      clearTimeout(st.timer);
      if (!completa() || st.chiusa) return;
      const k = chiave();
      const n = seq.nuova();
      const r = await A.api.post('/server/api/modifiche/stima', { tipo, id: x.id, richiesta: richiesta(), parti: partiScelte() });
      if (st.chiusa || !seq.valida(n)) return;
      st.chiaveStima = k;
      st.stima = r.ok && r.dati ? r.dati : null;
      st.erroreStima = r.ok ? null : r.errore;
      disegnaRiepilogo();
    }

    function spunto(frase) {
      const r = L.aggiungiSpuntoModifica(campo.value, frase);
      if (!r.ok) { A.avviso('La richiesta è già lunga: togli qualcosa prima.'); return; }
      campo.value = r.testo;
      cambiato();
      campo.focus();
      campo.setSelectionRange(campo.value.length, campo.value.length);
    }

    function scegliDove(d) {
      if (st.dove === d) return;
      st.dove = d;
      disegnaDove();
      cambiato();
    }

    function disegnaDove() {
      svuota(sceltaDove,
        h('button', { type: 'button', 'aria-pressed': st.dove === 'tutto' ? 'true' : 'false', 'data-dove': 'tutto', onclick: () => scegliDove('tutto') }, 'Tutto'),
        h('button', { type: 'button', 'aria-pressed': st.dove === 'io' ? 'true' : 'false', 'data-dove': 'io', onclick: () => scegliDove('io') }, 'Scelgo io'));
      if (st.dove === 'tutto') {
        svuota(sottoDove, h('p', { class: 'mod-tutto' }, 'Lectio sceglie da solo le parti che servono.'));
        return;
      }
      svuota(sottoDove, h('div', { class: 'mod-parti', role: 'group', 'aria-label': 'Le parti da cambiare' }, parti.map((p) => {
        const b = h('button', {
          type: 'button', class: 'mod-parte', role: 'checkbox', 'aria-checked': st.scelte.has(p.n) ? 'true' : 'false', 'data-parte': String(p.n), title: p.titolo,
          onclick: () => {
            if (st.scelte.has(p.n)) st.scelte.delete(p.n); else st.scelte.add(p.n);
            b.setAttribute('aria-checked', st.scelte.has(p.n) ? 'true' : 'false');
            cambiato();
          },
        }, h('span', { class: 'nl-casella', 'aria-hidden': 'true' }, icona('spunta')), h('span', { class: 'mod-n', 'aria-hidden': 'true' }, String(p.n)), h('span', { class: 't' }, p.titolo));
        return b;
      })));
    }

    function disegnaRiepilogo() {
      if (st.chiusa) return;
      const s = completa() ? st.stima : null;
      const costo = completa() ? L.costoModifica(s, A.modalitaProva()) : null;
      const righe = L.righeRiepilogoModifica({ tipo, stima: s, corretta });
      svuota(riepilogo,
        costo ? h('span', { class: 'mod-costo', 'data-riep': 'costo' }, costo) : null,
        righe.map((t) => h('span', { class: 'mod-riga-riep' }, t)));
      riepilogo.hidden = !costo && !righe.length;

      const consentita = !s || s.consentita !== false;
      const arrivata = !!s && st.chiaveStima === chiave();
      pulsante.disabled = st.invio || !completa() || !arrivata || !consentita || !!S.senzaRete;
      pulsante.textContent = st.invio ? 'Chiedo la modifica…' : 'Modifica';
      let frase = null;
      if (st.errore) frase = A.messaggio(st.statoErrore === 402 ? 'avviso' : 'errore', st.errore);
      else if (completa() && st.erroreStima) frase = A.messaggio('errore', st.erroreStima, [h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-stima', onclick: chiediStima }, 'Riprova')]);
      else if (s && !consentita) frase = h('p', { class: 'mod-motivo', 'data-motivo': 'modifica' }, s.motivo || 'Sforerebbe il tetto del mese.');
      else if (S.senzaRete) frase = h('p', { class: 'mod-motivo', 'data-motivo': 'modifica' }, 'Serve la rete');
      svuota(sotto, frase);
    }

    async function invia() {
      if (st.invio || pulsante.disabled) return;
      st.invio = true;
      st.errore = null;
      disegnaRiepilogo();
      const r = await A.api.post('/server/api/modifiche', { tipo, id: x.id, richiesta: richiesta(), parti: partiScelte() });
      st.invio = false;
      if (st.chiusa) return;
      if (!r.ok || !r.dati) {
        st.errore = r.errore || 'La modifica non è partita. Riprova.';
        st.statoErrore = r.stato;
        disegnaRiepilogo();
        return;
      }
      if (S.motore) S.motore.in_preparazione = true;
      ctl.chiudi(true);
      if (o.inviata) o.inviata(r.dati);
    }

    cambiato();
    disegnaDove();
    setTimeout(() => { if (!st.chiusa) campo.focus(); }, 30);
    return ctl;
  };

  // --- §44.3 La scheda della modifica e il suo giro --------------------------------------------------------------

  const seguiti = new Set();

  /**
   * La modifica dell'oggetto aperto. La pagina mette `el` sotto la testata e chiama `imposta(oggetto)` a ogni disegno;
   * mentre la modifica lavora si richiede l'oggetto ogni 4 s. o = { tipo, id, vivo(), ricarica() → risposta,
   * pronta(nuovo, modifica) (la pagina si ridisegna con l'oggetto nuovo), cambia(nuovo) (stato cambiato, contenuto no) }.
   * `evidenzia` = le parti cambiate da mostrare per questa visita.
   */
  A.seguiModifica = function (o) {
    const el = h('div', { class: 'mod-posto', 'data-modifica-posto': o.tipo });
    const ctl = { tipo: o.tipo, id: o.id, el, oggetto: null, modifica: null, evidenzia: new Set() };
    const spegnibili = [];
    let timer = null;
    let giro = 0;
    let conferma = false;
    let chiaveDisegno = null;
    let pezzi = {};
    seguiti.add(ctl);

    const vivo = () => {
      const si = !!o.vivo();
      if (si) seguiti.add(ctl);
      else { seguiti.delete(ctl); clearTimeout(timer); timer = null; }
      return si;
    };
    ctl.vivo = vivo;
    ctl.attiva = () => L.modificaAttiva(ctl.modifica);
    ctl.motivo = () => A.motivoModifica(o.tipo, ctl.oggetto);
    ctl.motivoVersione = () => A.motivoVersione(ctl.oggetto);

    ctl.imposta = function (x) {
      ctl.oggetto = x;
      ctl.modifica = (x && x.modifica) || null;
      const m = ctl.modifica;
      // Per questa visita: la prima volta che si apre dopo una modifica appena finita, le parti cambiate si vedono.
      if (m && m.stato === 'pronta' && !ctl.evidenzia.size && A.memoriaStudio.leggi(chiaveVista(o.tipo, o.id)) !== m.id) {
        const quando = Date.parse(m.pronta_il || '');
        if (Number.isFinite(quando) && Date.now() - quando < UN_GIORNO) ctl.evidenzia = new Set((m.cambiate || []).map(Number));
        A.memoriaStudio.scrivi(chiaveVista(o.tipo, o.id), m.id);
      }
      conferma = conferma && ctl.attiva();
      disegna();
      aggiornaSpegnibili();
      programma();
    };

    /** Una modifica appena chiesta (o ripresa con Riprova): la scheda compare e parte il giro. */
    ctl.avvia = function (m) {
      ctl.modifica = m;
      if (ctl.oggetto) ctl.oggetto.modifica = m;
      conferma = false;
      giro++;
      disegna();
      aggiornaSpegnibili();
      programma();
      if (o.cambia && ctl.oggetto) o.cambia(ctl.oggetto);
    };

    /** Una novità dal server o la rete tornata: si richiede subito. */
    ctl.subito = function () {
      if (!ctl.attiva() || !vivo()) return;
      clearTimeout(timer);
      timer = null;
      passo();
    };
    ctl.aggiorna = aggiornaSpegnibili;

    /** Un pulsante che si spegne (con la frase nel suggerimento) quando adesso non si può. */
    ctl.spegnibile = function (b, motivo) {
      const voce = { b, motivo: motivo || ctl.motivo, visto: false };
      spegnibili.push(voce);
      applica(voce);
      return b;
    };
    /** §44.1 La pillola Modifica della testata (Superficie 2, icona matita, come Scarica PDF). */
    ctl.pillola = (fai) => ctl.spegnibile(h('button', { type: 'button', class: 'pillola-azione mod-pillola', 'data-azione': 'modifica', 'data-visita': 'modifica', onclick: fai }, icona('matita'), 'Modifica'));

    function applica(v) {
      const perche = v.motivo();
      v.b.disabled = !!perche;
      if (perche) { v.b.title = perche; v.b.setAttribute('data-perche', perche); } else { v.b.removeAttribute('title'); v.b.removeAttribute('data-perche'); }
    }
    function aggiornaSpegnibili() {
      for (let i = spegnibili.length - 1; i >= 0; i--) {
        const v = spegnibili[i];
        if (v.b.isConnected) v.visto = true;
        else if (v.visto) { spegnibili.splice(i, 1); continue; }
        applica(v);
      }
    }

    function programma() {
      clearTimeout(timer);
      timer = null;
      if (!ctl.attiva() || !vivo()) return;
      timer = setTimeout(passo, T.modifica);
    }

    async function passo() {
      timer = null;
      if (!vivo()) return;
      const mio = ++giro;
      const r = await o.ricarica();
      if (!vivo() || mio !== giro) return;
      if (!r || !r.ok || !r.dati) { programma(); return; }
      ricevi(r.dati);
    }

    function ricevi(nuovo) {
      const prima = ctl.modifica;
      const m = nuovo.modifica || null;
      if (L.modificaAttiva(prima) && m && m.id === prima.id && m.stato === 'pronta') {
        ctl.evidenzia = new Set((m.cambiate || []).map(Number));
        A.memoriaStudio.scrivi(chiaveVista(o.tipo, o.id), m.id);
        ctl.oggetto = nuovo;
        ctl.modifica = m;
        conferma = false;
        // Via la scheda "Sto modificando" anche se la pagina aspetta a ridisegnarsi (lettore che suona: "Ricarica").
        disegna();
        aggiornaSpegnibili();
        A.avviso(L.fraseCambiate(m.cambiate), { dura: 4000 });
        o.pronta(nuovo, m);
        return;
      }
      const cambiata = !prima || !m || prima.id !== m.id || prima.stato !== m.stato;
      ctl.oggetto = nuovo;
      ctl.modifica = m;
      if (cambiata && o.cambia) o.cambia(nuovo);
      disegna();
      aggiornaSpegnibili();
      programma();
    }

    function cheScheda(m) {
      if (!m) return null;
      if (L.modificaAttiva(m)) return 'lavoro';
      if (m.stato === 'errore') return 'errore';
      if (m.stato === 'pronta' && m.nota && A.memoriaStudio.leggi(chiaveNota(o.tipo, o.id)) !== m.id) return 'nota';
      return null;
    }

    function disegna() {
      const m = ctl.modifica;
      const quale = cheScheda(m);
      const k = quale ? [m.id, quale, conferma].join('|') : '';
      if (k === chiaveDisegno) {
        if (quale === 'lavoro') aggiornaLavoro(m);
        return;
      }
      chiaveDisegno = k;
      pezzi = {};
      if (!quale) svuota(el);
      else if (quale === 'lavoro') svuota(el, schedaLavoro(m));
      else if (quale === 'errore') svuota(el, schedaErrore(m));
      else svuota(el, schedaNota(m));
    }

    const fraseLavoro = (m) => (m.stato === 'in_coda' ? 'In coda' : m.frase || 'In preparazione');
    const percento = (m) => (m.stato === 'in_coda' || !(Number(m.totale) > 0) ? 0 : L.percentuale(Number(m.fatto) || 0, Number(m.totale)));

    function schedaLavoro(m) {
      const frase = h('span', { class: 'mod-frase', 'data-frase': 'modifica', 'aria-live': 'polite' });
      const riemp = h('span');
      const barra = h('div', { class: 'barra sottile mod-barra', role: 'progressbar', 'aria-label': 'Avanzamento della modifica', 'aria-valuemin': '0', 'aria-valuemax': '100' }, riemp);
      pezzi = { frase, riemp, barra };
      aggiornaLavoro(m);
      const destra = conferma
        ? h('div', { class: 'mod-conferma', 'data-conferma': 'annulla-modifica' },
          h('span', null, 'Annullare la modifica?'),
          h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'annulla-modifica-si', onclick: annulla }, 'Sì'),
          h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'annulla-modifica-no', onclick: () => { conferma = false; disegna(); } }, 'No'))
        : h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'annulla-modifica', onclick: () => { conferma = true; disegna(); } }, 'Annulla');
      return h('div', { class: 'mod-scheda', 'data-modifica': 'lavoro' },
        h('div', { class: 'mod-riga' }, h('div', { class: 'mod-testi' }, h('span', { class: 'etichetta mod-sto' }, 'Sto modificando'), frase), destra),
        barra);
    }

    function aggiornaLavoro(m) {
      if (!pezzi.frase) return;
      pezzi.frase.textContent = fraseLavoro(m);
      const p = Math.max(0, Math.min(100, percento(m)));
      pezzi.riemp.style.width = p + '%';
      pezzi.barra.setAttribute('aria-valuenow', String(Math.round(p)));
    }

    function schedaErrore(m) {
      return h('div', { class: 'mod-scheda errore', 'data-modifica': 'errore', role: 'alert' },
        h('span', { class: 'mod-frase' }, m.errore || 'La modifica non è riuscita.'),
        h('div', { class: 'azioni' },
          h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-modifica', disabled: S.senzaRete ? true : null, onclick: riprova }, 'Riprova'),
          h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'lascia-modifica', onclick: annulla }, 'Lascia stare')));
    }

    function schedaNota(m) {
      return h('div', { class: 'sch-nota mod-nota', 'data-modifica': 'nota' },
        h('div', { class: 'mod-nota-riga' }, icona('domanda'), h('p', null, m.nota),
          h('button', {
            type: 'button', class: 'tondo mod-nota-chiudi', 'aria-label': 'Chiudi', 'data-azione': 'chiudi-nota-modifica',
            onclick: () => { A.memoriaStudio.scrivi(chiaveNota(o.tipo, o.id), m.id); disegna(); },
          }, icona('chiudi'))));
    }

    async function annulla() {
      const m = ctl.modifica;
      if (!m) return;
      const r = await A.api.post('/server/api/modifiche/' + m.id + '/annulla', {});
      conferma = false;
      if (!r.ok) {
        A.avviso(r.errore);
        disegna();
        ctl.subito(); // forse nel frattempo è finita: si guarda subito
        return;
      }
      giro++; // un giro già partito non riaccende la scheda
      clearTimeout(timer);
      timer = null;
      ctl.modifica = r.dati && r.dati.id ? r.dati : Object.assign({}, m, { stato: 'annullata' });
      if (ctl.oggetto) ctl.oggetto.modifica = ctl.modifica;
      disegna();
      aggiornaSpegnibili();
      if (o.cambia && ctl.oggetto) o.cambia(ctl.oggetto);
    }

    async function riprova() {
      const m = ctl.modifica;
      if (!m) return;
      const r = await A.api.post('/server/api/modifiche/' + m.id + '/riprova', {});
      if (!r.ok || !r.dati) { A.avviso(r.errore); return; }
      if (S.motore) S.motore.in_preparazione = true;
      ctl.avvia(r.dati);
    }

    return ctl;
  };

  /**
   * Una novità di modifica (pronta o fermata) dal programma: chi segue una modifica che lavora richiede subito.
   * true = una pagina aperta la sta seguendo (allora l'avviso lo dà lei).
   */
  A.novitaModifica = function () {
    let seguita = false;
    for (const c of Array.from(seguiti)) {
      if (!c.vivo()) continue;
      if (c.attiva()) { seguita = true; c.subito(); }
    }
    return seguita;
  };

  /** La rete è andata o tornata: i pulsanti di modifica delle pagine aperte si spengono o si riaccendono subito. */
  A.reteModifiche = function () {
    for (const c of Array.from(seguiti)) if (c.vivo()) c.aggiorna();
  };

  // --- §44.5 Torna a prima / Rimetti la modifica ------------------------------------------------------------------

  /** Conferma, scambio, avviso "Fatto". Ritorna l'oggetto nuovo, oppure null (no, o errore già detto). */
  A.scambiaVersione = async function (tipo, id, vdp) {
    const p = L.pulsanteVersione(vdp);
    if (!p) return null;
    if (!(await A.conferma({ titolo: p.domanda, testo: p.frase, si: 'Sì', no: 'No' }))) return null;
    const r = await A.api.post(percorso(tipo, id) + '/scambia-versione', {});
    if (!r.ok || !r.dati) { A.avviso(r.errore || 'Non sono riuscito a cambiare versione.'); return null; }
    A.avviso('Fatto');
    return r.dati;
  };

  /** La voce di menu per la versione da parte (Torna a prima o Rimetti la modifica), o null se non c'è. */
  A.voceVersione = function (x, fai) {
    const p = L.pulsanteVersione(x && x.versione_da_parte);
    if (!p) return null;
    const perche = A.motivoVersione(x);
    return { testo: p.testo, azione: 'scambia-versione', icona: 'torna', spenta: !!perche, frase: perche, fai };
  };

  // --- §44.4 Correggi a mano --------------------------------------------------------------------------------------

  /** Un campo che cresce con il testo. */
  function adatta(a) {
    a.style.height = 'auto';
    if (a.scrollHeight) a.style.height = (a.scrollHeight + 3) + 'px';
  }

  /**
   * La pagina in correzione (schemi e riassunti), disegnata in `box`: barra ferma con Annulla e Salva, ogni testo in un
   * campo. Esc e l'uscita dalla pagina chiedono "Lasciare le correzioni?" se c'è qualcosa di cambiato.
   * o = { tipo: 'schema'|'riassunto', oggetto, materia, indietro, parte, foto(blocco) → elemento, salvato(nuovo), annullato() }.
   */
  A.correggiAMano = function (box, o) {
    const tipo = o.tipo;
    const x = o.oggetto;
    const m = o.materia;
    const originale = x.contenuto || {};
    const titoloPrima = x.titolo || x.argomento || '';
    const corretto = copia(originale) || {};
    let titoloOra = titoloPrima;
    let invio = false;
    let finita = false;
    const campi = [];
    const errore = h('div', { class: 'mod-errore-correggi', 'data-errore': 'correggi' });

    const salva = h('button', { type: 'button', class: 'pulsante pieno mod-salva', 'data-azione': 'salva-correzioni', onclick: salvaOra }, 'Salva');
    const barra = h('div', { class: 'mod-barra-correggi', 'data-barra': 'correggi' },
      h('span', { class: 'mod-correggi-t' }, 'Stai correggendo a mano'),
      h('span', { class: 'spazio' }),
      h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'annulla-correzioni', onclick: annulla }, 'Annulla'),
      salva);

    function campo(valore, scrivi, opz) {
      const p = opz || {};
      const a = h('textarea', {
        class: 'mod-correggi-campo' + (p.classe ? ' ' + p.classe : ''), rows: '1', maxlength: String(p.max || L.MASSIMO_TESTO_A_MANO),
        'aria-label': p.etichetta || 'Testo', 'data-correggi-campo': p.nome || 'testo',
      });
      a.value = valore == null ? '' : String(valore);
      if (p.unaRiga) a.addEventListener('keydown', (e) => { if (e.key === 'Enter') e.preventDefault(); });
      a.addEventListener('input', () => {
        if (p.unaRiga && /\n/.test(a.value)) a.value = a.value.replace(/\s*\n+\s*/g, ' ');
        scrivi(a.value);
        adatta(a);
      });
      campi.push(a);
      return a;
    }

    const titoloCampo = campo(titoloPrima, (v) => { titoloOra = v; }, { classe: 'mod-titolo', max: L.MASSIMO_TITOLO_A_MANO, etichetta: 'Titolo', nome: 'titolo', unaRiga: true });

    function blocco(b, ...figli) {
      return h('div', { class: 'sch-blocco mod-correggi-blocco', 'data-tipo': b.tipo }, figli);
    }

    function bloccoSchema(b) {
      const titolo = () => (typeof b.titolo === 'string' ? campo(b.titolo, (v) => { b.titolo = v; }, { classe: 'mod-titolo-blocco', etichetta: 'Titolo', unaRiga: true }) : null);
      switch (b.tipo) {
        case 'paragrafo': return blocco(b, titolo(), campo(b.testo, (v) => { b.testo = v; }, { etichetta: 'Paragrafo' }));
        case 'punti': case 'passaggi': {
          if (!Array.isArray(b.voci)) return null;
          const righe = b.voci.join('\n');
          b.voci = righe; // nel campo una voce per riga: va a capo = voce nuova
          return blocco(b, titolo(), campo(righe, (v) => { b.voci = v; }, { etichetta: b.tipo === 'punti' ? 'Punti, uno per riga' : 'Passaggi, uno per riga', nome: 'voci' }));
        }
        case 'definizione': return blocco(b,
          typeof b.termine === 'string' ? campo(b.termine, (v) => { b.termine = v; }, { classe: 'mod-titolo-blocco', etichetta: 'Termine', unaRiga: true }) : null,
          typeof b.testo === 'string' ? campo(b.testo, (v) => { b.testo = v; }, { etichetta: 'Spiegazione' }) : null);
        case 'prof': return blocco(b, h('span', { class: 'mod-dal-prof' }, icona('verifica'), 'DAL PROF'), campo(b.testo, (v) => { b.testo = v; }, { etichetta: 'Detto dal prof' }));
        case 'tabella': {
          const titoli = Array.isArray(b.intestazione) ? b.intestazione : [];
          const colonne = Math.max(titoli.length, ...(b.righe || []).map((r) => (Array.isArray(r) ? r.length : 0)), 1);
          const griglia = h('div', { class: 'mod-tabella', stile: { gridTemplateColumns: 'repeat(' + colonne + ', minmax(0, 1fr))' } },
            titoli.map((t, k) => (typeof t === 'string' ? campo(t, (v) => { b.intestazione[k] = v; }, { classe: 'mod-titolo-blocco', etichetta: 'Colonna ' + (k + 1), unaRiga: true }) : h('span'))),
            (b.righe || []).map((r, ri) => (Array.isArray(r) ? r.map((c, ci) => (typeof c === 'string' ? campo(c, (v) => { b.righe[ri][ci] = v; }, { etichetta: 'Riga ' + (ri + 1) + ', colonna ' + (ci + 1) }) : h('span'))) : null)));
          return blocco(b, titolo(), griglia);
        }
        case 'immagine': return blocco(b,
          o.foto ? o.foto(b) : null,
          typeof b.didascalia === 'string' ? campo(b.didascalia, (v) => { b.didascalia = v; }, { classe: 'mod-didascalia', etichetta: 'Didascalia' }) : null,
          b.credito ? h('span', { class: 'sch-credito' }, b.credito) : null);
        default: return null; // i blocchi che non si conoscono restano come sono
      }
    }

    function testoSchema() {
      return (corretto.sezioni || []).map((sz) => h('section', { class: 'sch-parte mod-correggi-parte', 'data-parte': String(sz.n) },
        h('div', { class: 'sch-testa-parte' },
          h('span', { class: 'sch-num', 'aria-hidden': 'true' }, String(sz.n)),
          typeof sz.titolo === 'string' ? campo(sz.titolo, (v) => { sz.titolo = v; }, { classe: 'mod-titolo-parte', etichetta: 'Titolo della parte ' + sz.n, unaRiga: true }) : null),
        (sz.campi || []).length ? h('div', { class: 'sch-campi' }, sz.campi.map((k) => (k && typeof k.valore === 'string'
          ? h('div', { class: 'sch-campo' }, h('span', { class: 'k' }, k.nome), campo(k.valore, (v) => { k.valore = v; }, { etichetta: k.nome || 'Campo', unaRiga: true }))
          : null))) : null,
        h('div', { class: 'sch-blocchi' }, (sz.blocchi || []).map((b) => (b ? bloccoSchema(b) : null)))));
    }

    function testoRiassunto() {
      return h('article', { class: 'rs-scheda mod-correggi-rs' }, (corretto.parti || []).map((p, i) => {
        const conPunti = Array.isArray(p.punti) && p.punti.length > 0;
        if (Array.isArray(p.punti)) p.punti = p.punti.join('\n');
        return h('section', { class: 'rs-parte', 'data-parte': String(i + 1) },
          typeof p.titolo === 'string' ? campo(p.titolo, (v) => { p.titolo = v; }, { classe: 'mod-titolo-parte', etichetta: 'Titolo della parte ' + (i + 1), unaRiga: true }) : null,
          (Array.isArray(p.paragrafi) ? p.paragrafi : []).map((t, k) => campo(t, (v) => { p.paragrafi[k] = v; }, { etichetta: 'Paragrafo' })),
          conPunti ? campo(p.punti, (v) => { p.punti = v; }, { etichetta: 'Punti, uno per riga', nome: 'voci' }) : null);
      }));
    }

    const testa = h('div', { class: 'sch-testa' }, A.frecciaIndietro(o.indietro), A.pillolaMateria(m));
    if (tipo === 'riassunto') {
      svuota(box, h('div', { class: 'rs-pagina mod-correggendo ' + A.classeMateria(m.colore), 'data-correggi': tipo }, testa, barra, errore, titoloCampo, testoRiassunto()));
    } else {
      svuota(box, h('div', { class: 'schema-contenitore mod-correggendo ' + A.classeMateria(m.colore), 'data-correggi': tipo },
        h('div', { class: 'sch-griglia' }, h('div', { class: 'sch-colonna' }, testa, barra, errore, titoloCampo, testoSchema()))));
    }
    campi.forEach(adatta);
    requestAnimationFrame(() => campi.forEach(adatta));
    // Subito, non a scorrimento morbido: i campi che crescono (sotto) cambierebbero il punto d'arrivo a metà strada.
    const sc = S.dom.scorre;
    if (sc) sc.style.scrollBehavior = 'auto';
    const parte = o.parte != null ? box.querySelector('[data-parte="' + o.parte + '"]') : null;
    if (parte && parte.scrollIntoView) parte.scrollIntoView({ block: 'start', behavior: 'instant' });
    else if (sc) sc.scrollTop = 0;
    if (sc) sc.style.scrollBehavior = '';

    const haCambiamenti = () => L.correzioniFatte(tipo, originale, titoloPrima, corretto, titoloOra);
    const chiediDiLasciare = () => A.conferma({ titolo: 'Lasciare le correzioni?', si: 'Lascia', no: 'Continua', pericolo: true });
    S.guardia = { hash: location.hash, chiedi: async () => !haCambiamenti() || chiediDiLasciare() };
    S.escPagina = annulla;

    function esci() {
      finita = true;
      if (S.escPagina === annulla) S.escPagina = null;
      S.guardia = null;
    }

    function mostraErrore(t) {
      svuota(errore, A.messaggio('avviso', t));
      if (errore.scrollIntoView) errore.scrollIntoView({ block: 'nearest' });
    }

    async function annulla() {
      if (invio || finita) return;
      if (haCambiamenti() && !(await chiediDiLasciare())) return;
      if (finita) return;
      esci();
      o.annullato();
    }

    async function salvaOra() {
      if (invio || finita) return;
      const titolo = String(titoloOra || '').replace(/\s+/g, ' ').trim();
      if (!titolo) { mostraErrore('Scrivi il titolo.'); titoloCampo.focus(); return; }
      // Niente di cambiato: si torna a leggere senza mettere da parte una versione uguale.
      if (!haCambiamenti()) { esci(); o.annullato(); return; }
      invio = true;
      salva.disabled = true;
      salva.textContent = 'Salvo…';
      const contenuto = tipo === 'riassunto' ? L.contenutoRiassuntoCorretto(originale, corretto) : L.contenutoSchemaCorretto(originale, corretto);
      const r = await A.api.put(percorso(tipo, x.id) + '/testo', { titolo, contenuto });
      invio = false;
      salva.disabled = false;
      salva.textContent = 'Salva';
      if (finita || !box.isConnected) return;
      if (!r.ok || !r.dati) { mostraErrore(r.errore || 'Non sono riuscito a salvare. Riprova.'); return; }
      esci();
      A.avviso('Salvato. Se serve, Torna a prima è nel menu.', { dura: 5000 });
      o.salvato(r.dati);
    }

    return { annulla, salva: salvaOra, cambiato: haCambiamenti };
  };

  /** §44.3 Accanto al titolo di una parte cambiata: "CAMBIATA" nel colore accento. */
  A.etichettaCambiata = () => h('span', { class: 'mod-cambiata-t', 'data-cambiata': 'si' }, 'Cambiata');
})();
