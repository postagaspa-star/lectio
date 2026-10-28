/*
 * Lectio per il computer: l'avvio. Collegamento (abbinamento) al primo avvio, poi le pagine; la visita guidata
 * parte da sola la prima volta. Qui anche quello che il programma chiede alla pagina (window.lectio*), la prova
 * di diagnosi (--diagnosi) e la modalità foto (?prova=1&scena=…&tema=…): dati finti, nessuna chiamata.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;
  const ApiBase = window.LectioApi;

  A.aggiornaMotore = async function () {
    const r = await A.api.get('/locale/stato');
    if (r.ok && r.dati) S.motore = Object.assign(S.motore || {}, r.dati);
    return r;
  };

  /** Il tema scelto (sistema / chiaro / scuro): la pagina lo applica subito, il programma lo ricorda. */
  A.applicaTema = function (tema) {
    if (tema === 'chiaro' || tema === 'scuro') document.documentElement.setAttribute('data-tema', tema);
    else document.documentElement.removeAttribute('data-tema');
  };

  const parametri = new URLSearchParams(location.search);
  S.prova = parametri.get('prova') === '1';

  A.avvia = async function () {
    if (S.prova) { await avviaProva(); return; }
    const r = await ApiBase.get('/locale/stato');
    if (!r.ok || !r.dati) {
      const app = document.getElementById('app');
      svuota(app, h('div', { class: 'abbina' }, h('div', { class: 'scheda' }, A.simbolo(true),
        h('h1', null, 'Il programma non risponde'),
        A.messaggio('errore', 'Chiudi Lectio e riaprilo.'))));
      return;
    }
    S.motore = r.dati;
    A.applicaTema(S.motore.tema);
    // Una pagina appena aperta non ha lezioni nel lettore: i tasti multimediali tornano agli altri programmi.
    ApiBase.post('/locale/tasti', { attivi: false });
    if (S.motore.diagnosi) diagnosi();
    if (!S.motore.abbinato) { mostraAbbinamento(); return; }
    S.storia = [];
    A.disegnaStruttura();
    A.caricaSalute();
    A.caricaSpesa();
    await Promise.all([A.caricaImpostazioni(), A.caricaMaterie()]);
    // Senza frammento: lo si mette e disegna l'evento hashchange (un solo disegno, niente finestre chiuse).
    if (!location.hash || location.hash === '#') location.replace('#oggi');
    else A.disegna();
    A.mandaCode();
    if (S.motore.tutorial_visto === false) A.avviaVisita(true);
  };

  // --- Primo avvio: collegare il computer al server di casa (§10) -------------------------------

  function mostraAbbinamento() {
    const app = document.getElementById('app');
    app.removeAttribute('aria-busy');
    S.dom = {};
    // Nessun indirizzo scritto nel codice: il campo parte vuoto (o con quello di prima) e il segnaposto mostra la forma.
    const server = h('input', { class: 'campo', id: 'abbina-server', value: (S.motore && S.motore.server) || '', placeholder: 'http://192.168.x.x:8500', autocomplete: 'off', spellcheck: 'false' });
    const codice = h('input', { class: 'campo codice-campo', id: 'abbina-codice', inputmode: 'numeric', autocomplete: 'off', maxlength: '10' });
    const esito = h('div');
    const collega = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'collega' }, 'Collega');
    const pronto = () => { collega.disabled = !L.codiceValido(codice.value) || !server.value.trim(); };
    codice.addEventListener('input', () => { codice.value = codice.value.replace(/[^\d ]/g, ''); pronto(); });
    server.addEventListener('input', pronto);
    codice.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !collega.disabled) collega.click(); });
    collega.addEventListener('click', async () => {
      collega.disabled = true;
      collega.textContent = 'Collego…';
      svuota(esito);
      const r = await ApiBase.post('/locale/abbina', { server: server.value.trim(), codice: codice.value.replace(/\s/g, '') });
      collega.textContent = 'Collega';
      pronto();
      if (!r.ok) {
        svuota(esito, A.messaggio('errore', r.errore || 'Il collegamento non è riuscito. Riprova.'));
        (r.dati && r.dati.codice === 'indirizzo' ? server : codice).focus();
        return;
      }
      if (S.prova) return;
      // Un collegamento nuovo: i punti e le risposte in coda di prima non valgono più.
      if (A.svuotaMemoriaLocale) A.svuotaMemoriaLocale();
      A.avvia();
    });
    pronto();
    svuota(app, h('div', { class: 'abbina' }, h('div', { class: 'scheda' },
      A.simbolo(true),
      h('h1', null, 'Collega Lectio al server di casa'),
      h('label', { class: 'campo-gruppo', for: 'abbina-server' }, h('span', { class: 'etichetta' }, 'Indirizzo del server'), server),
      h('label', { class: 'campo-gruppo', for: 'abbina-codice' }, h('span', { class: 'etichetta' }, 'Codice di 8 cifre'), codice),
      esito, collega)));
    setTimeout(() => codice.focus(), 50);
  }
  A.mostraAbbinamento = mostraAbbinamento;

  // --- Quello che il programma chiede alla pagina ------------------------------------------------

  /** Il clic su un avviso di Windows: una pagina o una lezione. */
  window.lectioApri = function (frammento) {
    if (!S.dom.scorre) return;
    const f = String(frammento || '');
    if (/^(oggi|materie|lezioni|gemini|impostazioni)$/.test(f) || /^lezione\/\d+$/.test(f)) A.vai('#' + f);
  };

  /** Novità dal server (lezione pronta, file letto…): la pagina aggiorna quello che mostra. */
  window.lectioNovita = function (lista) {
    if (S.esempio) return;
    const voci = Array.isArray(lista) ? lista : [];
    for (const v of voci) if (v.lezione_id && A.dimenticaDettaglio) A.dimenticaDettaglio(v.lezione_id);
    const pronta = voci.find((v) => v.tipo === 'lezione_pronta');
    if (pronta && document.hasFocus()) A.avviso(pronta.testo || 'La lezione è pronta.');
    A.aggiornaMotore();
    A.caricaMaterie();
    if (S.aggiornaPagina) S.aggiornaPagina();
  };

  // Senza rete: si riprova ogni tanto (quando torna, partono le code).
  setInterval(() => { if (S.senzaRete && !S.esempio && S.dom.scorre) A.api.get('/server/api/salute'); }, T.rete);

  // --- Diagnosi (--diagnosi): prova l'audio dalla cartella virtuale e l'invio dei byte ---------------

  async function diagnosi() {
    const esito = { pagina: 'ok' };
    try {
      esito.audio = await new Promise((fine) => {
        const a = new Audio();
        const t = setTimeout(() => fine('tempo scaduto'), 8000);
        a.addEventListener('loadedmetadata', () => { clearTimeout(t); fine('ok, ' + Math.round(a.duration * 10) / 10 + ' s'); });
        a.addEventListener('error', () => { clearTimeout(t); fine('errore ' + (a.error ? a.error.code : '?')); });
        a.preload = 'auto';
        a.src = 'https://audio.lectio.locale/diagnosi.wav';
      });
    } catch (e) { esito.audio = 'eccezione ' + e.message; }
    try {
      const byte = new Uint8Array(150000);
      for (let i = 0; i < byte.length; i++) byte[i] = i % 251;
      const r = await fetch('/locale/diagnosi-corpo', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: byte.buffer });
      const d = await r.json();
      esito.corpo = d.byte === byte.length ? 'ok, ' + d.byte + ' byte' : 'arrivati ' + d.byte + ' byte su ' + byte.length;
    } catch (e) { esito.corpo = 'eccezione ' + e.message; }
    esito.appunti = navigator.clipboard ? 'api presente' : 'api assente';
    await ApiBase.post('/locale/diagnosi', esito);
  }

  // --- Modalità foto: ?prova=1&scena=oggi&tema=scuro (dati finti, niente rete) ------------------------

  async function avviaProva() {
    const scena = parametri.get('scena') || 'oggi';
    A.applicaTema(parametri.get('tema') || 'chiaro');
    document.documentElement.classList.add('foto');
    A.T.preparazione = 1e9;
    const server = window.LectioEsempio.crea({ vuoto: scena === 'vuota', speso: scena === 'tetto' ? 1.85 : undefined });
    ApiBase.modoEsempio(server);
    S.esempio = true;
    S.motore = { abbinato: scena !== 'abbinamento', server: scena === 'abbinamento' ? null : 'http://192.168.0.20:8500', indirizzo_fuori: 'https://lectio.esempio.ts.net', versione: '1.0.0', tutorial_visto: true, tema: parametri.get('tema') || 'chiaro', scaricate: [12, 10], byte_scaricate: 18696000, nome_dispositivo: 'Computer di Andrea', aggiornamento: { attivo: true } };
    if (scena === 'abbinamento') { mostraAbbinamento(); segnaPronto(); return; }
    A.disegnaStruttura();
    S.salute = { modalita_prova: false };
    await Promise.all([A.caricaImpostazioni(), A.caricaMaterie(), A.caricaSpesa()]);
    const aspetta = async (sel, ms) => {
      const fine = Date.now() + (ms || 3000);
      while (Date.now() < fine) { if (document.querySelector(sel)) return true; await new Promise((r) => setTimeout(r, 30)); }
      return false;
    };
    const vai = async (hash, sel) => {
      if (location.hash === hash) A.disegna();
      else await new Promise((fatto) => { window.addEventListener('hashchange', () => setTimeout(fatto, 0), { once: true }); location.replace(hash); });
      if (sel) await aspetta(sel);
    };
    switch (scena) {
      case 'materie': await vai('#materie', '.scheda-materia'); break;
      case 'materia':
        await A.ascolta(12, { posizione: 462 });
        await vai('#materia/1', '.scheda-prof .pillola');
        break;
      case 'aggiungi': await vai('#materia/1', '[data-visita="aggiungi"]'); A.apriAggiungi(A.materiaDi(1)); break;
      case 'nuova-materia': {
        await vai('#materie', '.scheda-materia');
        A.apriMateria(null);
        await aspetta('#materia-nome');
        const n = document.getElementById('materia-nome');
        n.value = 'Italiano';
        n.dispatchEvent(new Event('input'));
        const p = document.getElementById('materia-prof');
        p.value = 'Bianchi';
        p.dispatchEvent(new Event('input'));
        break;
      }
      case 'nuova': case 'tetto':
        await vai('#materia/1', '[data-visita="nuova-lezione"]');
        A.nuovaLezione(1);
        await aspetta('[data-costo="espressiva"]');
        await new Promise((r) => setTimeout(r, 200));
        break;
      case 'preparazione': {
        await vai('#materia/1', '[data-visita="nuova-lezione"]');
        const corpo = h('div', { class: 'corpo su-fondo' });
        const piede = h('div', { class: 'piede' });
        const ctl = A.apriFinestra({ etichetta: 'In preparazione', classe: A.classeMateria('ambra'), disegna: (f) => svuota(f, corpo, piede) });
        A.vistaPreparazione(corpo, piede, { id: 99, materia_id: 1, argomento: 'L\'Impero romano da Augusto a Nerone', durata_min: 20, stato: 'in_preparazione', avanzamento: { fase: 'voce', fatto: 3, totale: 7, frase: 'Registro la voce · 3 di 7' } }, { materia: A.materiaDi(1), ctl });
        break;
      }
      case 'pronta': {
        await vai('#materia/1', '[data-visita="nuova-lezione"]');
        const corpo = h('div', { class: 'corpo su-fondo' });
        const piede = h('div', { class: 'piede' });
        const ctl = A.apriFinestra({ etichetta: 'Lezione pronta', disegna: (f) => svuota(f, corpo, piede) });
        A.vistaPreparazione(corpo, piede, { id: 12, materia_id: 1, argomento: 'L\'Impero romano da Augusto a Nerone', durata_min: 20, stato: 'pronta', avanzamento: {} }, { materia: A.materiaDi(1), ctl });
        break;
      }
      case 'lettore':
        await vai('#lettore/12', '.lettore-pagina');
        await A.ascolta(12, { posizione: 462 });
        await aspetta('.capitolo.corso');
        break;
      case 'lettore-domanda':
        await vai('#lettore/12', '.lettore-pagina');
        await A.lettoreFermoAllaDomanda(12, 32);
        await aspetta('#risposta-domanda');
        break;
      case 'lettore-saltabili':
        server.richiesta('PATCH', '/server/api/lezioni/12', { domande_saltabili: true });
        await vai('#lettore/12', '.lettore-pagina');
        await A.lettoreFermoAllaDomanda(12, 32);
        await aspetta('#risposta-domanda');
        break;
      case 'lettore-correzione': {
        await vai('#lettore/12', '.lettore-pagina');
        await A.lettoreFermoAllaDomanda(12, 32);
        await aspetta('#risposta-domanda');
        const c = document.getElementById('risposta-domanda');
        c.value = 'Perché non si fidava del senato e temeva le congiure.';
        c.dispatchEvent(new Event('input'));
        document.querySelector('[data-azione="invia-risposta"]').click();
        await aspetta('.correzione .esito');
        if (A.lettore.audio) A.lettore.audio.pause();
        break;
      }
      case 'gemini': await vai('#gemini', '.testo-gemini'); await new Promise((r) => setTimeout(r, 100)); break;
      case 'impostazioni': await vai('#impostazioni', '.voce-imp'); break;
      case 'lezioni': await vai('#lezioni', '.riga-lezione-elenco'); break;
      case 'racconta': await vai('#racconta/4', '.domande-esito'); break;
      case 'senza-rete': S.forzaSenzaRete = true; S.senzaRete = true; A.disegnaStato(); await vai('#lezioni', '.riga-lezione-elenco'); await new Promise((r) => setTimeout(r, 100)); A.disegnaStato(); break;
      case 'vuota': await vai('#oggi', '.vuoto'); break;
      case 'visita': {
        await vai('#oggi', '[data-visita="prossima"]');
        S.prova = true;
        await A.avviaVisita(false);
        const n = Number(parametri.get('passo') || 1) - 1;
        if (n > 0) {
          for (let i = 1; i <= n; i++) await A.visitaVai(i);
        }
        await new Promise((r) => setTimeout(r, 300));
        break;
      }
      default: await vai('#oggi', '[data-visita="prossima"]'); break;
    }
    await new Promise((r) => setTimeout(r, 150));
    segnaPronto();
  }

  function segnaPronto() {
    document.documentElement.setAttribute('data-pronto', '1');
  }

  if (!window.LectioNonAvviare) A.avvia();
})();
