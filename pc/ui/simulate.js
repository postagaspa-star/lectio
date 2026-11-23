/*
 * Lectio per il computer: la verifica simulata (docs/aspetto.md §36, contratto §21). Un allenamento, non un esame: il
 * tempo si può nascondere e non blocca niente, ogni risposta si scrive prima qui e poi si manda, il voto è una stima.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  const chiaveLocale = (id) => 'lectio.simulata.' + id;
  const CHIAVE_TEMPO = 'lectio.simulata.tempoNascosto';
  const inLavoro = (s) => s.stato === 'in_coda' || s.stato === 'in_preparazione';

  A.pagine.simulata = async function (box, rotta, vivo) {
    const id = rotta.id;
    const pag = h('div', { class: 'pagina' }, A.segnaposto(3), A.segnaposto(3));
    svuota(box, pag);
    let s = null;
    let m = null;
    let esame = null;

    const indietro = () => A.tornaIndietroDa(s && s.materia_id, 'simulate');
    // §44.3 La modifica della verifica: scheda sotto la testata e giro ogni 4 s finché lavora. Se arriva mentre la si
    // sta facendo, prima vanno sul server le risposte scritte, poi si riapre la verifica cambiata.
    const seg = A.seguiModifica({
      tipo: 'simulata', id, vivo,
      ricarica: () => A.api.get('/server/api/simulate/' + id),
      pronta: async (nuovo) => {
        let x = nuovo;
        if (esame) {
          await esame.manda();
          if (!vivo()) return;
          const r = await A.api.get('/server/api/simulate/' + id);
          if (!vivo()) return;
          if (r.ok && r.dati) x = r.dati;
        }
        // Una verifica corretta diventata una prova nuova: il tempo e le risposte della volta prima non valgono più.
        if (s && s.stato === 'corretta' && x.stato !== 'corretta') A.memoriaStudio.togli(chiaveLocale(id));
        s = x;
        disegna();
      },
      cambia: (nuovo) => { if (s) s.modifica = nuovo.modifica; },
    });
    S.aggiornaPagina = () => {
      if (seg.attiva()) { seg.subito(); return; }
      if (esame) esame.manda(); else carica();
    };

    function apriModifica() {
      A.apriModifica({ tipo: 'simulata', oggetto: s, titolo: s.titolo || s.argomento, materia: m, inviata: (md) => seg.avvia(md) });
    }

    async function scambia() {
      const nuovo = await A.scambiaVersione('simulata', id, s.versione_da_parte);
      if (!nuovo || !vivo()) return;
      A.memoriaStudio.togli(chiaveLocale(id));
      s = nuovo;
      seg.evidenzia = new Set();
      disegna();
    }

    /** §44.1 Torna a prima / Rimetti la modifica: pulsante testo in fondo, se c'è una versione da parte. */
    function pulsanteVersione() {
      const p = L.pulsanteVersione(s.versione_da_parte);
      if (!p) return null;
      return seg.spegnibile(h('button', { type: 'button', class: 'pulsante testo mod-testo-icona', 'data-azione': 'scambia-versione', onclick: scambia }, icona('torna'), p.testo), seg.motivoVersione);
    }

    async function carica() {
      const [r] = await Promise.all([A.api.get('/server/api/simulate/' + id), S.materie ? null : A.caricaMaterie(), S.salute ? null : A.caricaSalute()]);
      if (!vivo()) return;
      if (!r.ok || !r.dati) {
        if (r.rete) svuota(pag, A.messaggio('avviso', 'Si apre quando torna la rete.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: carica }, 'Riprova')]));
        else svuota(pag, A.erroreConRiprova(r, carica));
        return;
      }
      s = r.dati;
      m = A.materiaDi(s.materia_id) || { id: s.materia_id, nome: '', colore: 'ambra', conteggi: {} };
      S.materiaAperta = s.materia_id;
      A.disegnaMenu();
      A.disegnaStato();
      disegna();
    }

    function disegna() {
      if (esame && esame.ferma) esame.ferma();
      esame = null;
      seg.imposta(s);
      if (inLavoro(s)) {
        svuota(pag, A.vistaLavoro({
          materia: m, titolo: s.titolo || s.argomento, frase: s.stato === 'in_coda' ? 'In coda' : s.frase || 'In preparazione', percentuale: s.stato === 'in_coda' ? 0 : L.percentualeLavoro(s), indietro,
          annulla: async () => {
            if (!(await A.conferma({ titolo: 'Annullare la verifica?', testo: 'Le spese già fatte restano nel conto del mese.', si: 'Annulla la verifica', no: 'Continua', pericolo: true }))) return;
            const r = await A.api.post('/server/api/simulate/' + id + '/annulla', {});
            if (!r.ok) { A.avviso(r.errore); return; }
            s = Object.assign({}, s, r.dati || {}, { stato: 'annullata' });
            disegna();
          },
        }));
        A.ripeti(vivo, carica, T.stato);
        return;
      }
      if (s.stato === 'in_correzione') {
        svuota(pag, A.vistaLavoro({ materia: m, titolo: s.titolo || s.argomento, frase: 'La sto correggendo', indietro }));
        A.ripeti(vivo, carica, T.stato);
        return;
      }
      if (s.stato === 'errore' || s.stato === 'annullata') { disegnaFermo(); return; }
      if (s.stato === 'pronta') { disegnaPronta(); return; }
      if (s.stato === 'in_corso') { esame = disegnaEsame(); return; }
      if (s.stato === 'corretta') { disegnaRisultati(); return; }
      svuota(pag, A.messaggio('avviso', 'Questa verifica non si può aprire.'));
    }

    function testaSemplice() {
      return h('div', { class: 'sch-testa' }, A.frecciaIndietro(indietro), A.pillolaMateria(m));
    }

    async function elimina() {
      if (!(await A.conferma({ titolo: 'Eliminare la verifica simulata?', testo: '"' + (s.titolo || s.argomento) + '" sparisce, con le tue risposte.', si: 'Elimina', pericolo: true }))) return;
      const r = await A.api.elimina('/server/api/simulate/' + id);
      if (!r.ok) { A.avviso(r.errore); return; }
      A.memoriaStudio.togli(chiaveLocale(id));
      await A.caricaMaterie();
      A.vai('#materia/' + m.id + '/simulate');
    }

    function disegnaFermo() {
      const riprova = async () => {
        const r = await A.api.post('/server/api/simulate/' + id + '/riprova', {});
        if (!r.ok) { A.avviso(r.errore); return; }
        if (S.motore) S.motore.in_preparazione = true;
        carica();
      };
      svuota(pag, h('div', { class: 'vista-lavoro' }, testaSemplice(),
        h('h1', { class: 'titolo-pagina' }, s.titolo || s.argomento),
        s.stato === 'errore'
          ? A.messaggio('errore', s.errore || 'La verifica non è riuscita.', [h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-simulata', onclick: riprova }, 'Riprova')])
          : A.messaggio('avviso', 'Verifica annullata.'),
        h('div', null, h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'elimina-simulata', onclick: elimina }, 'Elimina'))));
    }

    // --- §36.2 Pronta --------------------------------------------------------------------------------------------

    function disegnaPronta() {
      const sotto = h('div', { 'data-pronta': 'sotto' });
      const inizia = h('button', {
        type: 'button', class: 'pulsante pieno largo', 'data-azione': 'inizia-simulata', disabled: S.senzaRete ? true : null,
        onclick: async () => {
          inizia.disabled = true;
          const r = await A.api.patch('/server/api/simulate/' + id, { risposte: {}, iniziata: true });
          if (!vivo()) return;
          if (!r.ok || !r.dati) { inizia.disabled = false; A.avviso(r.errore); return; }
          s = r.dati;
          disegna();
        },
      }, 'Inizia');
      svuota(pag, h('div', { class: 'sim-centro ' + A.classeMateria(m.colore), 'data-simulata': 'pronta' },
        testaSemplice(),
        seg.el,
        h('h1', { class: 'titolo-grande' }, s.titolo || s.argomento),
        h('span', { class: 'riassunto-sim', 'data-riassunto': 'simulata' }, L.riassuntoSimulata(s)),
        s.nota ? A.messaggio('avviso', s.nota) : null,
        inizia,
        A.pulsantePdf('api/simulate/' + id + '/pdf', L.nomeFilePdf(s.titolo, 'Verifica'), {
          testo: 'Fai su carta', classe: 'pulsante secondario-alto', azione: 'fai-su-carta', senzaIcona: true,
          dopo: () => svuota(sotto, A.messaggio('info', 'Quando hai finito, premi Inizia e copia qui le risposte: te le correggo.')),
        }),
        h('div', null, seg.spegnibile(h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'modifica', 'data-visita': 'modifica-simulata', onclick: apriModifica }, 'Modifica le domande'))),
        sotto,
        h('div', { class: 'azioni' }, pulsanteVersione(), h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'elimina-simulata', onclick: elimina }, 'Elimina'))));
    }

    // --- §36.3 Mentre la fai -------------------------------------------------------------------------------------

    function disegnaEsame() {
      const chiave = chiaveLocale(id);
      const salvato = A.memoriaStudio.leggi(chiave) || {};
      const locali = salvato.risposte || {};
      const sporche = new Set((salvato.sporche || []).map(String));
      const risposte = L.unisciRisposte(s.risposte, locali, Array.from(sporche));
      // §21.1: i campi del risultato degli esercizi "conti" e i file aggiunti (questi vanno subito al server).
      const risultati = L.unisciRisposte(s.risultati, salvato.risultati || {}, Array.from(sporche));
      const allegati = Object.assign({}, s.allegati || {});
      const conti = new Set((s.domande || []).filter((d) => L.formaDomanda(d) === 'conti').map((d) => String(d.n)));
      const allegatiEl = new Map();
      const inCarica = new Map();
      let usati = Number(salvato.usati) || 0;
      let nascosto = A.memoriaStudio.leggi(CHIAVE_TEMPO) === true;
      let timerManda = null;
      let invioInCorso = false;
      let ultimoSalvataggio = Date.now();
      let avvisoFine = null;
      let consegnata = false;
      let fermato = false;
      const totale = (Number(s.minuti) || 0) * 60;
      const statiEl = new Map();
      const domandeEl = new Map();
      const rigaEl = new Map();

      function salvaLocale() {
        A.memoriaStudio.scrivi(chiave, { risposte, risultati, sporche: Array.from(sporche), usati });
      }

      const stessi = (a, b) => JSON.stringify(a || []) === JSON.stringify(b || []);

      function statoDi(n, testo, riprova) {
        const el = statiEl.get(String(n));
        if (!el) return;
        svuota(el, h('span', { 'data-salvataggio': testo }, testo), riprova ? h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'riprova-salva', onclick: manda }, 'Riprova') : null);
      }

      async function manda() {
        clearTimeout(timerManda);
        if (invioInCorso || !sporche.size || consegnata) return true;
        invioInCorso = true;
        const qui = {};
        const quiRisultati = {};
        for (const n of sporche) {
          qui[n] = risposte[n] || '';
          if (conti.has(n)) quiRisultati[n] = (risultati[n] || []).slice();
        }
        const corpo = { risposte: qui, iniziata: true };
        if (Object.keys(quiRisultati).length) corpo.risultati = quiRisultati;
        const r = await A.api.patch('/server/api/simulate/' + id, corpo);
        invioInCorso = false;
        if (r.ok) {
          for (const n of Object.keys(qui)) {
            if ((risposte[n] || '') === qui[n] && (!quiRisultati[n] || stessi(risultati[n], quiRisultati[n]))) {
              sporche.delete(n);
              statoDi(n, 'Salvata');
            }
          }
          salvaLocale();
          if (sporche.size) timerManda = setTimeout(manda, T.salvaRisposta);
          return !sporche.size;
        }
        if (r.rete) {
          for (const n of Object.keys(qui)) statoDi(n, 'Salvata sul computer');
          return false;
        }
        for (const n of Object.keys(qui)) statoDi(n, 'Non riesco a salvare', true);
        return false;
      }

      /** Un campo della domanda `n` è cambiato (risposta, passaggi o risultato): si salva qui e poi si manda. */
      function scritto(n) {
        sporche.add(n);
        salvaLocale();
        statoDi(n, 'Salvo…');
        aggiornaColonna();
        clearTimeout(timerManda);
        timerManda = setTimeout(manda, T.salvaRisposta);
      }

      function adatta(area) {
        area.style.height = 'auto';
        if (area.scrollHeight) area.style.height = area.scrollHeight + 4 + 'px';
      }

      function aggiornaColonna() {
        for (const [n, r] of rigaEl) {
          const si = L.rispostaFatta(n, risposte, risultati, allegati);
          const segno = r.querySelector('[data-segno]');
          svuota(segno, si ? icona('spunta') : null);
        }
      }

      const tempoEl = [];
      function pillolaTempo() {
        const b = h('button', { type: 'button', class: 'sim-tempo', 'data-azione': 'tempo', 'data-visita': 'tempo', onclick: () => {
          nascosto = !nascosto;
          A.memoriaStudio.scrivi(CHIAVE_TEMPO, nascosto);
          disegnaTempo();
          if (A.visitaEvento) A.visitaEvento('tempo');
        } });
        tempoEl.push(b);
        return b;
      }
      function disegnaTempo() {
        const restanti = totale - usati;
        tempoEl.forEach((b) => {
          b.classList.toggle('nascosto-t', nascosto);
          b.setAttribute('aria-label', nascosto ? 'Mostra il tempo' : 'Nascondi il tempo');
          svuota(b, icona('orologio'), nascosto ? null : h('span', { 'data-tempo': 'si' }, L.testoTempo(restanti)));
        });
      }
      function tic() {
        if (!vivo() || consegnata || fermato) { clearInterval(timerTempo); return; }
        if (document.hidden) return;
        const prima = totale - usati;
        usati += 1;
        const dopo = totale - usati;
        if (!nascosto && prima > 300 && dopo <= 300 && dopo > 0) A.avviso('Mancano 5 minuti', { dura: 4000 });
        if (prima > 0 && dopo <= 0 && !avvisoFine) avvisoFine = A.avviso('Il tempo è finito. Puoi continuare.', { resta: true });
        if (Math.floor(prima / 60) !== Math.floor(dopo / 60) || dopo <= 60) disegnaTempo();
        if (Date.now() - ultimoSalvataggio >= T.tempoSalvato) { ultimoSalvataggio = Date.now(); salvaLocale(); }
      }

      function salta(n) {
        const el = domandeEl.get(String(n));
        const c = S.dom.scorre;
        if (!el || !c) return;
        c.scrollTop += el.getBoundingClientRect().top - c.getBoundingClientRect().top - 16;
      }

      function foglioDomande() {
        A.apriFoglio({
          titolo: 'Domande',
          corpo: (c, ctl) => svuota(c, h('div', { class: 'sim-griglia-numeri' }, s.domande.map((d) => h('button', {
            type: 'button', class: 'sim-numero' + (L.rispostaFatta(d.n, risposte, risultati, allegati) ? ' scritta' : ''), 'aria-label': 'Domanda ' + d.n, onclick: () => { ctl.chiudi(true); salta(d.n); },
          }, String(d.n)))),
          h('button', { type: 'button', class: 'pulsante pieno largo', onclick: () => { ctl.chiudi(true); foglioConsegna(); } }, 'Consegna')),
        });
      }

      function foglioConsegna() {
        const conto = L.contaRisposte(s.domande, risposte, risultati, allegati);
        A.apriFoglio({
          titolo: 'Consegnare?',
          corpo: (c, ctl) => {
            const sotto = h('div', { 'data-consegna': 'esito' });
            const invia = h('button', {
              type: 'button', class: 'pulsante pieno largo', 'data-azione': 'consegna-si', disabled: S.senzaRete ? true : null,
              onclick: async () => {
                invia.disabled = true;
                svuota(sotto);
                await manda();
                const r = await A.api.post('/server/api/simulate/' + id + '/consegna', {});
                if (ctl.chiuso || !vivo()) return;
                if (!r.ok || !r.dati) {
                  invia.disabled = false;
                  svuota(sotto, A.messaggio(r.rete ? 'avviso' : 'errore', r.rete ? 'Senza rete non posso correggerla. Le risposte restano salvate.' : 'Non sono riuscito a consegnare. Le risposte sono salve.', r.rete ? null : [h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-consegna', onclick: () => invia.click() }, 'Riprova')]));
                  return;
                }
                consegnata = true;
                clearInterval(timerTempo);
                A.memoriaStudio.scrivi(chiave, { usati, consegnata: true });
                if (avvisoFine) avvisoFine.remove();
                if (S.motore) S.motore.in_preparazione = true;
                ctl.chiudi(true);
                s = r.dati;
                disegna();
              },
            }, 'Consegna');
            svuota(c,
              h('p', { class: 'sim-conta-consegna', 'data-consegna': 'conto' }, conto.frase),
              S.senzaRete ? A.messaggio('avviso', 'Senza rete non posso correggerla. Le risposte restano salvate.') : null,
              sotto,
              invia,
              h('button', { type: 'button', class: 'pulsante tenue alto largo-p', 'data-azione': 'torna-domande', onclick: () => ctl.chiudi() }, 'Torna alle domande'));
          },
        });
      }

      async function esci() {
        const ok = await A.conferma({ titolo: 'Uscire? Le risposte restano salvate.', si: 'Esci', no: 'Resto' });
        if (!ok) return;
        salvaLocale();
        manda();
        if (avvisoFine) avvisoFine.remove();
        A.tornaIndietroDa(m.id, 'simulate');
      }

      // --- §21.1 Foto o file aggiunti a una risposta: vanno subito al server, 4 al massimo per domanda ---

      function rigaAllegati(n) {
        const scelta = h('input', {
          type: 'file', class: 'sr', accept: L.ESTENSIONI_ALLEGATO.join(','), multiple: true, tabindex: '-1', 'aria-hidden': 'true', 'data-scelta-file': n,
          onchange: async () => {
            const file = Array.from(scelta.files || []);
            scelta.value = '';
            for (const f of file) await allega(n, f);
          },
        });
        const pulsante = h('button', { type: 'button', class: 'pulsante tenue piccolo sim-allega', 'data-azione': 'allega', 'data-allega': n, onclick: () => scelta.click() }, icona('clip'), 'Foto o file');
        const pillole = h('div', { class: 'sim-allegati-pillole' });
        allegatiEl.set(n, { pillole, pulsante });
        disegnaAllegati(n);
        return h('div', { class: 'sim-allegati', 'data-allegati': n }, pulsante, scelta, pillole);
      }

      function disegnaAllegati(n) {
        const el = allegatiEl.get(n);
        if (!el) return;
        const lista = allegati[n] || [];
        const caricano = inCarica.get(n) || [];
        el.pulsante.disabled = lista.length + caricano.length >= L.MASSIMO_ALLEGATI;
        svuota(el.pillole,
          lista.map((a) => h('span', { class: 'pillola sim-allegato', 'data-allegato': a.id },
            icona(a.tipo === 'pdf' ? 'file' : 'foto'), h('span', { class: 'nome' }, a.nome),
            h('button', { type: 'button', class: 'togli', 'aria-label': 'Togli ' + a.nome, 'data-azione': 'togli-allegato', onclick: () => togli(n, a) }, icona('chiudi')))),
          caricano.map((nome) => h('span', { class: 'pillola sim-allegato in-carica', 'data-in-carica': 'si' }, icona('carica'), h('span', { class: 'nome' }, nome))));
      }

      async function allega(n, file) {
        const nome = (file && file.name) || 'foto.png';
        const caricano = inCarica.get(n) || [];
        const no = L.controllaAllegato(nome, file && file.size, (allegati[n] || []).length + caricano.length);
        if (no) { A.avviso(no); return; }
        inCarica.set(n, caricano.concat(nome));
        disegnaAllegati(n);
        let r;
        try {
          r = await A.api.allega(await file.arrayBuffer(), { simulata_id: id, n: Number(n), nome });
        } catch (e) {
          r = { ok: false, errore: 'Non sono riuscito a leggere il file. Riprova.' };
        }
        const resta = (inCarica.get(n) || []).slice();
        resta.splice(resta.indexOf(nome), 1);
        inCarica.set(n, resta);
        if (!vivo() || consegnata || fermato) return;
        if (!r.ok || !r.dati) {
          A.avviso(r.errore);
          disegnaAllegati(n);
          return;
        }
        allegati[n] = (allegati[n] || []).concat(r.dati);
        s.allegati = allegati;
        disegnaAllegati(n);
        aggiornaColonna();
      }

      async function togli(n, a) {
        const r = await A.api.elimina('/server/api/simulate/' + id + '/allegati/' + encodeURIComponent(a.id));
        if (!vivo() || consegnata || fermato) return;
        if (!r.ok) { A.avviso(r.errore); return; }
        allegati[n] = (allegati[n] || []).filter((x) => x.id !== a.id);
        s.allegati = allegati;
        disegnaAllegati(n);
        aggiornaColonna();
      }

      const sezioniViste = new Set();
      const schede = [];
      for (const d of s.domande || []) {
        const n = String(d.n);
        if (d.sezione && !sezioniViste.has(d.sezione)) {
          sezioniViste.add(d.sezione);
          schede.push(h('h2', { class: 'sim-sezione' }, d.sezione));
        }
        const forma = L.formaDomanda(d);
        const conFormule = forma !== 'testo';
        const area = h('textarea', {
          class: 'sim-risposta', rows: '5', placeholder: forma === 'conti' ? null : 'Scrivi la tua risposta', id: 'sim-risposta-' + n,
          'aria-label': (forma === 'conti' ? 'Passaggi della domanda ' : 'Risposta alla domanda ') + d.n, 'data-risposta': n, spellcheck: conFormule ? 'false' : null,
        });
        area.value = risposte[n] || '';
        const stato = h('div', { class: 'sim-stato', 'data-stato-risposta': n, 'aria-live': 'polite' });
        statiEl.set(n, stato);
        const cambiata = seg.evidenzia.has(Number(d.n));
        let campiRisposta = [area];
        if (!conFormule) {
          area.addEventListener('input', () => { adatta(area); risposte[n] = area.value; scritto(n); });
        } else {
          // §36.5 La barra della matematica scrive nel campo col cursore; sotto ogni campo l'anteprima come sul libro.
          const campi = [area];
          const barra = A.formule.barra(() => campi);
          const ant = A.formule.anteprima();
          ant.aggiorna(area.value);
          barra.segui(area);
          area.addEventListener('input', () => { adatta(area); risposte[n] = area.value; ant.aggiorna(area.value); scritto(n); });
          const pezzi = [barra.el];
          if (forma === 'conti') pezzi.push(h('label', { class: 'etichetta sim-campo-nome', for: area.id }, 'Passaggi'));
          pezzi.push(area, ant.el);
          if (forma === 'conti') {
            const nomi = L.campiRisultato(d);
            pezzi.push(h('div', { class: 'sim-risultati-campi' }, nomi.map((nome, i) => {
              const input = h('input', {
                type: 'text', class: 'sim-risultato', id: 'sim-risultato-' + n + '-' + i, autocomplete: 'off', spellcheck: 'false', maxlength: '200',
                'data-risultato': n + ':' + i, value: ((risultati[n] || [])[i]) || '',
              });
              const antR = A.formule.anteprima();
              antR.aggiorna(input.value);
              barra.segui(input);
              campi.push(input);
              input.addEventListener('input', () => {
                const v = (risultati[n] || []).slice();
                while (v.length < nomi.length) v.push('');
                v[i] = input.value;
                risultati[n] = v;
                antR.aggiorna(input.value);
                scritto(n);
              });
              return h('div', { class: 'sim-campo-risultato' }, h('label', { class: 'etichetta sim-campo-nome', for: input.id }, nome), input, antR.el);
            })));
          }
          pezzi.push(rigaAllegati(n));
          campiRisposta = pezzi;
        }
        const scheda = h('section', { class: 'sim-domanda' + (cambiata ? ' mod-cambiata' : '') + (conFormule ? ' con-formule' : ''), 'data-domanda': n, 'data-forma': forma },
          h('span', { class: 'mod-titolo-cambiata' }, h('span', { class: 'etichetta' }, 'Domanda ' + d.n + ' · ' + L.plurale(Number(d.punti) || 0, 'punto', 'punti')), cambiata ? A.etichettaCambiata() : null,
            A.etichettaFile(s, d.n, A.materiaDi(s.materia_id))),
          h('p', { class: 'testo-d' }, d.testo), A.formule.tabella(d.tabella), A.formule.grafico(d.grafico), campiRisposta, stato);
        if (conFormule) {
          // Ctrl+V di una foto dentro la domanda: diventa un file aggiunto alla risposta.
          scheda.addEventListener('paste', (e) => {
            const file = Array.from((e.clipboardData && e.clipboardData.files) || []).filter((f) => /^image\//.test(f.type || ''));
            if (!file.length) return;
            e.preventDefault();
            (async () => { for (const f of file) await allega(n, f); })();
          });
        }
        domandeEl.set(n, scheda);
        schede.push(scheda);
        if (sporche.has(n)) statoDi(n, 'Salvata sul computer');
      }

      const righe = (s.domande || []).map((d) => {
        const n = String(d.n);
        const r = h('button', { type: 'button', class: 'sim-riga-domanda', 'data-salta': n, onclick: () => salta(d.n) }, String(d.n), h('span', { class: 'pt' }, d.punti + ' pt'), h('span', { 'data-segno': 'si' }));
        rigaEl.set(n, r);
        return r;
      });

      const colonnaSx = h('aside', { class: 'sim-sx' }, pillolaTempo(), h('span', { class: 'etichetta' }, 'Domande'), righe,
        h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'consegna', onclick: foglioConsegna }, 'Consegna'));
      const barraStretta = h('div', { class: 'sim-barra-stretta' }, pillolaTempo(), h('button', { type: 'button', class: 'pillola-azione', 'data-azione': 'domande-foglio', onclick: foglioDomande }, icona('indice'), 'Domande'), h('span', { class: 'spazio' }),
        h('button', { type: 'button', class: 'pulsante tenue', onclick: foglioConsegna }, 'Consegna'));

      svuota(pag, h('div', { class: 'schema-contenitore ' + A.classeMateria(m.colore), 'data-simulata': 'in_corso' }, h('div', { class: 'sch-griglia' }, colonnaSx, h('div', { class: 'sch-colonna' },
        h('div', { class: 'sch-testa' }, h('button', { type: 'button', class: 'tondo grande', 'aria-label': 'Esci', 'data-azione': 'esci-simulata', onclick: esci }, icona('chiudi')), A.pillolaMateria(m)),
        seg.el,
        h('h1', { class: 'titolo-grande' }, s.titolo || s.argomento),
        barraStretta,
        s.nota ? A.messaggio('avviso', s.nota) : null,
        schede,
        h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'consegna-fondo', onclick: foglioConsegna }, 'Consegna')))));

      for (const a of pag.querySelectorAll('.sim-risposta')) adatta(a);
      // Alla prima apertura lo stile può arrivare un attimo dopo: si rimisura al disegno successivo.
      requestAnimationFrame(() => { if (vivo() && !fermato) for (const a of pag.querySelectorAll('.sim-risposta')) adatta(a); });
      disegnaTempo();
      aggiornaColonna();
      const timerTempo = setInterval(tic, 1000);
      if (sporche.size && !S.senzaRete) manda();
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
      /** La pagina si ridisegna (verifica cambiata): il tempo resta sul computer e questo esame si ferma. */
      function ferma() {
        if (fermato) return;
        salvaLocale();
        fermato = true;
        clearInterval(timerTempo);
        clearTimeout(timerManda);
      }
      return { manda, ferma };
    }

    // --- §36.4 Risultati -----------------------------------------------------------------------------------------

    function disegnaRisultati() {
      const e = s.esito || {};
      const locale = A.memoriaStudio.leggi(chiaveLocale(id)) || {};
      const oltre = L.fraseOltreTempo(locale.usati, s.minuti);
      const correzioni = new Map((e.domande || []).map((x) => [x.n, x]));

      /** La risposta com'è scritta; con la barra della matematica, le righe con le formule impaginate. */
      function testoMio(testo, forma) {
        if (forma === 'testo' || !L.daImpaginare(testo)) return h('p', { class: 'mia' }, testo);
        return h('div', { class: 'mia con-formule' }, testo.split('\n').filter((r) => r.trim()).map((r) => h('div', { class: 'f-riga' }, A.formule.formula(r))));
      }

      /** §21.1 Esercizio "conti": i passaggi e ogni risultato con il segno del controllo del programma. */
      function bloccoConti(d, c, passaggi) {
        const scritti = (s.risultati || {})[String(d.n)] || [];
        const controllo = Array.isArray(c.controllo) ? c.controllo : [];
        const nomi = L.campiRisultato(d);
        return [
          h('div', { class: 'blocco' }, h('span', { class: 'etichetta' }, 'Passaggi'), passaggi ? testoMio(passaggi, 'conti') : h('p', { class: 'mia vuota' }, 'Nessun passaggio')),
          h('div', { class: 'blocco sim-risultati-esito' }, nomi.map((nome, i) => {
            const k = controllo.find((x) => x && x.cosa === nome) || controllo[i] || {};
            const scritto = String(scritti[i] || k.scritto || '').trim();
            const v = L.esitoControllo(k.esito);
            return h('div', { class: 'sim-riga-risultato', 'data-risultato-esito': k.esito || 'nessuno' },
              h('span', { class: 'etichetta' }, nome),
              h('span', { class: 'valore' + (scritto ? '' : ' vuota') }, scritto ? A.formule.formula(scritto) : 'Vuoto'),
              v ? h('span', { class: 'esito ' + v.classe }, icona(v.icona), v.parola) : null);
          })),
        ];
      }

      /** I nomi dei file aggiunti alla risposta (§21.1). */
      function bloccoFile(d) {
        const lista = (s.allegati || {})[String(d.n)] || [];
        if (!lista.length) return null;
        return h('div', { class: 'blocco' }, h('span', { class: 'etichetta' }, 'Foto o file'),
          h('div', { class: 'sim-allegati-pillole' }, lista.map((a) => h('span', { class: 'pillola sim-allegato', 'data-allegato': a.id }, icona(a.tipo === 'pdf' ? 'file' : 'foto'), h('span', { class: 'nome' }, a.nome)))));
      }

      function rigaDomanda(d) {
        const c = correzioni.get(d.n) || { esito: 'non_corretta' };
        const v = L.esitoSimulata(c.esito);
        const risposta = String((s.risposte || {})[String(d.n)] || '').trim();
        const punti = L.puntiDomanda(c, d);
        const forma = L.formaDomanda(d);
        let aperta = c.esito !== 'giusta';
        const dentro = () => h('div', { class: 'dentro' },
          A.formule.tabella(d.tabella), A.formule.grafico(d.grafico),
          forma === 'conti' ? bloccoConti(d, c, risposta) : h('div', { class: 'blocco' }, h('span', { class: 'etichetta' }, 'La tua risposta'),
            risposta ? testoMio(risposta, forma) : h('p', { class: 'mia vuota' }, 'Non hai risposto')),
          bloccoFile(d),
          c.esito === 'non_corretta' && !c.commento && !c.risposta_attesa ? h('p', { class: 'confronta-t' }, 'Nei tuoi documenti non c\'è la soluzione. Confronta tu con il libro o con il prof.') : null,
          c.commento ? h('div', { class: 'blocco' }, h('span', { class: 'etichetta' }, 'Commento'), h('p', { class: 'commento' }, c.commento)) : null,
          (c.mancava || []).length ? h('div', { class: 'blocco' }, h('span', { class: 'etichetta' }, 'Mancava'), h('div', { class: 'mancava-p' }, c.mancava.map((x) => h('span', { class: 'pillola' }, x)))) : null,
          c.risposta_attesa ? h('div', { class: 'sim-attesa' }, h('span', { class: 'etichetta' }, 'Risposta attesa'), h('p', null, c.risposta_attesa)) : null,
          A.segnoFonte(c.fonti));
        const box = h('div', { class: 'sim-esito', 'data-domanda': String(d.n), 'data-esito': c.esito });
        const disegnaRiga = () => {
          box.classList.toggle('aperta', aperta);
          svuota(box, h('button', { type: 'button', class: 'intesta', 'aria-expanded': aperta ? 'true' : 'false', onclick: () => { aperta = !aperta; disegnaRiga(); } },
            h('span', { class: 'etichetta' }, 'Domanda ' + d.n),
            h('span', { class: 'riga-su' }, h('span', { class: 'esito ' + v.classe }, icona(v.icona), v.parola), h('span', { class: 'spazio' }), punti ? h('span', { class: 'pt' }, punti) : null, icona('giu', 'freccia')),
            h('span', { class: 'testo-d' }, d.testo)),
          aperta ? dentro() : null);
        };
        disegnaRiga();
        return box;
      }

      const daRifare = Number(e.da_rifare) || 0;
      const versione = pulsanteVersione();
      svuota(pag, h('div', { class: 'sim-risultati ' + A.classeMateria(m.colore), 'data-simulata': 'corretta' },
        h('div', { class: 'sch-testa' }, A.frecciaIndietro(indietro), A.pillolaMateria(m), h('span', { class: 'spazio' }),
          A.pulsantePdf('api/simulate/' + id + '/pdf', L.nomeFilePdf(s.titolo, 'Verifica')),
          A.menuPuntini(() => [{ testo: 'Elimina', pericolo: true, azione: 'elimina-simulata', fai: elimina }])),
        seg.el,
        h('h1', { class: 'titolo-grande' }, s.titolo || s.argomento),
        h('section', { class: 'sim-testata', 'data-visita': 'stima' },
          h('span', { class: 'etichetta' }, 'Voto stimato'),
          h('span', { class: 'sim-voto', 'data-voto': 'si' }, 'circa ' + L.voto(e.voto_stimato)),
          h('span', { class: 'punti', 'data-punti': 'si' }, L.frasePunti(e)),
          h('span', { class: 'stima' }, 'È una stima di Lectio. Il voto vero lo dà il prof.'),
          oltre ? h('span', { class: 'stima', 'data-oltre': 'si' }, oltre) : null),
        (s.domande || []).map(rigaDomanda),
        h('div', { class: 'sim-fondo' },
          daRifare > 0 ? h('span', { class: 'frase-rifare', 'data-rifare': 'si' }, daRifare === 1 ? '1 domanda da rifare è già in Ripasso lampo.' : daRifare + ' domande da rifare sono già in Ripasso lampo.') : null,
          h('div', { class: 'azioni' },
            daRifare > 0 ? h('button', { type: 'button', class: 'pulsante pieno medio', 'data-azione': 'ripassa-adesso', onclick: () => A.apriLampo({ materiaId: m.id }) }, 'Ripassa adesso') : null,
            h('button', { type: 'button', class: 'pulsante tenue alto', 'data-azione': 'rifai-simulata', onclick: () => A.nuovaSimulata(m.id, { argomento: s.argomento, materiali: s.materiali, minuti: s.minuti, indicazioni: s.indicazioni, eventoId: s.evento_id }) }, 'Rifai la verifica'),
            seg.spegnibile(h('button', { type: 'button', class: 'pulsante tenue alto', 'data-azione': 'modifica', 'data-visita': 'modifica-simulata', onclick: apriModifica }, 'Modifica e rifai')))),
        versione ? h('div', null, versione) : null));
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    await carica();
  };
})();
