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
    else if (S.motore.tutorial_novita_visto === false) A.avviaVisita(false, 'novita');
    else if (S.motore.novita_nuove === true) A.avviaVisita(false, 'ultime');
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
    if (/^(oggi|materie|lezioni|gemini|impostazioni)$/.test(f) || /^lezione\/\d+$/.test(f) || /^materia\/\d+(\/(schemi|simulate))?$/.test(f) || /^(schema|simulata|riassunto)\/\d+$/.test(f)) A.vai('#' + f);
  };

  /** Novità dal server (lezione pronta, file letto…): la pagina aggiorna quello che mostra. */
  window.lectioNovita = function (lista) {
    if (S.esempio) return;
    const voci = Array.isArray(lista) ? lista : [];
    for (const v of voci) if (v.lezione_id && A.dimenticaDettaglio) A.dimenticaDettaglio(v.lezione_id);
    // §44.3 Una modifica finita o fermata: la pagina che la segue richiede subito e dà lei l'avviso ("Fatto: …").
    const modifiche = voci.some((v) => v.tipo === 'modifica_pronta' || v.tipo === 'modifica_errore');
    const seguita = modifiche && A.novitaModifica ? A.novitaModifica() : false;
    const pronta = voci.find((v) => ['lezione_pronta', 'schema_pronto', 'riassunto_pronto', 'simulata_pronta', 'simulata_corretta'].includes(v.tipo))
      || (seguita ? null : voci.find((v) => v.tipo === 'modifica_pronta'));
    if (pronta && document.hasFocus()) A.avviso(pronta.testo || 'È pronto.');
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
        await vai('#materia/1', '[data-visita="nuovo"]');
        A.nuovaLezione(1);
        await aspetta('[data-costo="espressiva"]');
        await new Promise((r) => setTimeout(r, 200));
        break;
      case 'preparazione': {
        await vai('#materia/1', '[data-visita="nuovo"]');
        const corpo = h('div', { class: 'corpo su-fondo' });
        const piede = h('div', { class: 'piede' });
        const ctl = A.apriFinestra({ etichetta: 'In preparazione', classe: A.classeMateria('ambra'), disegna: (f) => svuota(f, corpo, piede) });
        A.vistaPreparazione(corpo, piede, { id: 99, materia_id: 1, argomento: 'L\'Impero romano da Augusto a Nerone', durata_min: 20, stato: 'in_preparazione', avanzamento: { fase: 'voce', fatto: 3, totale: 7, frase: 'Registro la voce · 3 di 7' } }, { materia: A.materiaDi(1), ctl });
        break;
      }
      case 'pronta': {
        await vai('#materia/1', '[data-visita="nuovo"]');
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
      case 'colori': await vai('#materie', '.scheda-materia'); A.apriMateria(A.materiaDi(1)); await aspetta('.cerchio-colore'); document.querySelector('[data-colore="giada"]').click(); break;
      case 'schemi': await vai('#materia/1/schemi', '[data-schema]'); await aspetta('[data-simulata]'); S.dom.scorre.scrollTop = S.dom.scorre.scrollHeight; S.dom.scorre.style.scrollBehavior = 'auto'; S.dom.scorre.scrollTop = 1e6; break;
      case 'simulate': await vai('#materia/1/simulate', '[data-simulata]'); S.dom.scorre.style.scrollBehavior = 'auto'; S.dom.scorre.scrollTop = 1e6; break;
      case 'nuovo-schema': await vai('#materia/1', '[data-visita="nuovo"]'); A.nuovoSchema(1); await aspetta('[data-riep="immagini"]'); await new Promise((r) => setTimeout(r, 100)); break;
      case 'simulata-nuova': await vai('#materia/1', '[data-visita="nuovo"]'); A.nuovaSimulata(1); await aspetta('[data-riep="pronta"]'); await new Promise((r) => setTimeout(r, 100)); break;
      // §43.3 "Cosa vuoi fare?" e il suo passo 2 (Riassunto: da quale schema).
      case 'cosa-fare': case 'cosa-fare-schema':
        await vai('#materia/1', '[data-schema="3"]');
        document.querySelector('[data-visita="nuovo"]').click();
        await aspetta('[data-scelta="riassunto"]:not(:disabled)');
        if (scena === 'cosa-fare-schema') { document.querySelector('[data-scelta="riassunto"]').click(); await aspetta('[data-schema-scelto]'); }
        break;
      case 'schema': await vai('#schema/3', '.sch-parte'); break;
      case 'schema-sotto': await vai('#schema/3', '.sch-parte'); S.dom.scorre.style.scrollBehavior = 'auto'; S.dom.scorre.scrollTop = 900; await new Promise((r) => setTimeout(r, 200)); break;
      case 'schema-fine': await vai('#schema/3', '.sch-parte'); S.dom.scorre.style.scrollBehavior = 'auto'; S.dom.scorre.scrollTop = 1e6; await new Promise((r) => setTimeout(r, 200)); break;
      case 'schema-opera': await vai('#schema/4', '.sch-parte'); break;
      case 'schema-lavoro': await vai('#schema/5', '[data-vista="lavoro"]'); A.T.stato = 1e9; break;
      case 'schema-errore': await vai('#schema/6', '[data-azione="riprova-schema"]'); break;
      case 'riassunto-scelta': await vai('#schema/3', '.sch-parte'); document.querySelector('[data-azione="riassunto"]').click(); await aspetta('[data-forma-riassunto]'); break;
      case 'riassunto': await vai('#riassunto/4', '[data-riassunto]'); break;
      case 'riassunto-punti': await vai('#riassunto/5', '[data-riassunto]'); break;
      case 'riassunto-manca': await vai('#riassunto/4', '[data-riassunto]'); document.querySelector('[data-forma="discorsivo"]').click(); await aspetta('[data-forma-manca]'); break;
      case 'non-ho-capito': case 'non-c-e': {
        await vai('#schema/3', '.sch-parte');
        document.querySelector('[data-visita="mano"]').click();
        await aspetta('[data-campo="domanda"]');
        if (scena === 'non-c-e') { const c = document.querySelector('[data-campo="domanda"]'); c.value = 'Cosa c\'entra Marte?'; c.dispatchEvent(new Event('input')); }
        document.querySelector('[data-azione="spiegamelo"]').click();
        await aspetta('[data-capito="risposta"], [data-capito="non-c-e"]');
        break;
      }
      case 'capito-domanda': await vai('#schema/3', '.sch-parte'); document.querySelector('[data-visita="mano"]').click(); await aspetta('[data-campo="domanda"]'); break;
      case 'lampo': case 'lampo-risposta': case 'lampo-fine': {
        await vai('#oggi', '[data-azione="inizia-lampo"]');
        A.T.fermoEsito = 0;
        document.querySelector('[data-azione="inizia-lampo"]').click();
        await aspetta('[data-azione="mostra-risposta"]');
        if (scena !== 'lampo') {
          document.querySelector('[data-azione="mostra-risposta"]').click();
          await aspetta('[data-esito]');
        }
        if (scena === 'lampo-fine') {
          const esiti = ['sapevo', 'quasi', 'sapevo', 'non_sapevo', 'sapevo', 'quasi', 'sapevo', 'sapevo'];
          for (const e of esiti) {
            await aspetta('[data-esito]');
            document.querySelector('[data-esito="' + e + '"]').click();
            await new Promise((r) => setTimeout(r, 30));
            const m = document.querySelector('[data-azione="mostra-risposta"]');
            if (m) m.click();
          }
          await aspetta('[data-lampo="fine"]');
        }
        break;
      }
      case 'simulata-pronta': await vai('#simulata/2', '[data-azione="inizia-simulata"]'); break;
      case 'simulata-corso': await vai('#simulata/3', '.sim-domanda'); break;
      case 'simulata-consegna': await vai('#simulata/3', '.sim-domanda'); document.querySelector('[data-azione="consegna"]').click(); await aspetta('[data-consegna="conto"]'); break;
      case 'simulata-risultati': await vai('#simulata/4', '.sim-testata'); break;
      // §44 Modifica, Correggi a mano, Torna a prima.
      case 'modifica-foglio': {
        await vai('#schema/3', '.sch-parte');
        document.querySelector('[data-visita="modifica"]').click();
        await aspetta('[data-campo="richiesta"]');
        const c = document.querySelector('[data-campo="richiesta"]');
        c.value = 'Aggiungi le date di ogni imperatore';
        c.dispatchEvent(new Event('input'));
        document.querySelector('[data-dove="io"]').click();
        document.querySelector('.mod-parte[data-parte="2"]').click();
        document.querySelector('.mod-parte[data-parte="3"]').click();
        await aspetta('[data-riep="costo"]');
        await aspetta('[data-azione="manda-modifica"]:not(:disabled)');
        break;
      }
      case 'modifica-lavoro': case 'modifica-errore': {
        A.T.modifica = 1e9;
        const errore = scena === 'modifica-errore';
        server.stato.modifiche.push({
          id: 50, tipo: 'schema', oggetto_id: 3, materia_id: 1, richiesta: 'Aggiungi le date di ogni imperatore', parti: [2, 3], stato: errore ? 'errore' : 'in_preparazione', fase: errore ? null : 'scrittura',
          fatto: 1, totale: 2, frase: errore ? null : 'Riscrivo la parte 3', errore: errore ? 'Il servizio che scrive i testi non ha risposto. Riprova tra poco.' : null, nota: null, cambiate: [], stima_eur: 0.04, creata_il: new Date().toISOString(), pronta_il: null,
        });
        await vai('#schema/3', '[data-modifica="' + (errore ? 'errore' : 'lavoro') + '"]');
        break;
      }
      case 'modifica-pronta': {
        A.T.modifica = 1e9;
        const md = { id: 51, tipo: 'schema', oggetto_id: 3, materia_id: 1, richiesta: 'Aggiungi le date delle crociate', parti: [], stato: 'in_preparazione', fase: 'scrittura', fatto: 0, totale: 1, frase: 'Riscrivo la parte 1', errore: null, nota: null, cambiate: [], stima_eur: 0.06, creata_il: new Date().toISOString(), pronta_il: null };
        server.stato.modifiche.push(md);
        await vai('#schema/3', '[data-modifica="lavoro"]');
        md._t0 = Date.now() - 60000; // al prossimo giro il server finto la finisce
        A.novitaModifica();
        await aspetta('[data-modifica="nota"]');
        await aspetta('.mod-cambiata');
        // Subito (non a scorrimento morbido), così la foto prende la parte cambiata e non un punto a metà strada.
        S.dom.scorre.style.scrollBehavior = 'auto';
        S.dom.scorre.scrollTop += document.querySelector('.mod-cambiata').getBoundingClientRect().top - 24;
        await new Promise((r) => setTimeout(r, 200));
        break;
      }
      case 'correggi': {
        await vai('#schema/3', '.sch-parte');
        document.querySelector('[data-azione="puntini"]').click();
        await aspetta('[data-azione="correggi-a-mano"]');
        document.querySelector('[data-azione="correggi-a-mano"]').click();
        await aspetta('[data-barra="correggi"]');
        await new Promise((r) => setTimeout(r, 100));
        break;
      }
      case 'schema-menu': {
        await vai('#schema/3', '.sch-parte');
        document.querySelector('.sch-colonna [data-azione="puntini"]').click();
        await aspetta('[data-azione="correggi-a-mano"]');
        break;
      }
      case 'riassunto-correggi': {
        await vai('#riassunto/4', '[data-riassunto]');
        document.querySelector('[data-azione="puntini"]').click();
        await aspetta('[data-azione="correggi-a-mano"]');
        document.querySelector('[data-azione="correggi-a-mano"]').click();
        await aspetta('[data-barra="correggi"]');
        await new Promise((r) => setTimeout(r, 100));
        break;
      }
      case 'riassunto-vecchio': await vai('#riassunto/5', '[data-vecchio]'); break;
      case 'lettore-modifica': {
        A.T.modifica = 1e9;
        server.stato.modifiche.push({ id: 52, tipo: 'lezione', oggetto_id: 12, materia_id: 1, richiesta: 'Spiega meglio Seiano', parti: [2], stato: 'in_coda', fase: null, fatto: 0, totale: 1, frase: null, errore: null, nota: null, cambiate: [], stima_eur: 0.05, creata_il: new Date().toISOString(), pronta_il: null });
        await vai('#lettore/12', '.lettore-pagina');
        await A.ascolta(12, { posizione: 462 });
        await aspetta('[data-modifica="lavoro"]');
        A.lettoreSalto(462, false);
        break;
      }
      case 'lettore-ricarica': {
        A.T.modifica = 1e9;
        const md = { id: 53, tipo: 'lezione', oggetto_id: 12, materia_id: 1, richiesta: 'Spiega meglio Seiano', parti: [2], stato: 'in_preparazione', fase: 'scrittura', fatto: 0, totale: 1, frase: 'Riscrivo il capitolo 2', errore: null, nota: null, cambiate: [], stima_eur: 0.05, creata_il: new Date().toISOString(), pronta_il: null };
        server.stato.modifiche.push(md);
        await vai('#lettore/12', '.lettore-pagina');
        await A.ascolta(12, { posizione: 462 });
        await aspetta('[data-modifica="lavoro"]');
        A.lettoreSalto(462, false);
        document.querySelector('[data-azione="play"]').click();
        md._t0 = Date.now() - 60000; // al prossimo giro il server finto la finisce, mentre la lezione suona
        A.novitaModifica();
        await aspetta('[data-azione="ricarica-versione"]');
        break;
      }
      case 'simulata-modifica': {
        await vai('#simulata/4', '.sim-testata');
        document.querySelector('[data-visita="modifica-simulata"]').click();
        await aspetta('[data-campo="richiesta"]');
        document.querySelector('[data-spunto="Più domande aperte"]').click();
        await aspetta('[data-riep="costo"]');
        await aspetta('[data-azione="manda-modifica"]:not(:disabled)');
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
        await A.avviaVisita(false, parametri.get('modo') || undefined);
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
