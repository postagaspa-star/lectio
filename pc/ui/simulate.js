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
    S.aggiornaPagina = () => { if (esame) esame.manda(); else carica(); };

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
      esame = null;
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
        h('h1', { class: 'titolo-grande' }, s.titolo || s.argomento),
        h('span', { class: 'riassunto-sim', 'data-riassunto': 'simulata' }, L.riassuntoSimulata(s)),
        s.nota ? A.messaggio('avviso', s.nota) : null,
        inizia,
        A.pulsantePdf('api/simulate/' + id + '/pdf', L.nomeFilePdf(s.titolo, 'Verifica'), {
          testo: 'Fai su carta', classe: 'pulsante secondario-alto', azione: 'fai-su-carta', senzaIcona: true,
          dopo: () => svuota(sotto, A.messaggio('info', 'Quando hai finito, premi Inizia e copia qui le risposte: te le correggo.')),
        }),
        sotto,
        h('div', null, h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'elimina-simulata', onclick: elimina }, 'Elimina'))));
    }

    // --- §36.3 Mentre la fai -------------------------------------------------------------------------------------

    function disegnaEsame() {
      const chiave = chiaveLocale(id);
      const salvato = A.memoriaStudio.leggi(chiave) || {};
      const locali = salvato.risposte || {};
      const sporche = new Set((salvato.sporche || []).map(String));
      const risposte = L.unisciRisposte(s.risposte, locali, Array.from(sporche));
      let usati = Number(salvato.usati) || 0;
      let nascosto = A.memoriaStudio.leggi(CHIAVE_TEMPO) === true;
      let timerManda = null;
      let invioInCorso = false;
      let ultimoSalvataggio = Date.now();
      let avvisoFine = null;
      let consegnata = false;
      const totale = (Number(s.minuti) || 0) * 60;
      const statiEl = new Map();
      const domandeEl = new Map();
      const rigaEl = new Map();

      function salvaLocale() {
        A.memoriaStudio.scrivi(chiave, { risposte, sporche: Array.from(sporche), usati });
      }

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
        for (const n of sporche) qui[n] = risposte[n] || '';
        const r = await A.api.patch('/server/api/simulate/' + id, { risposte: qui, iniziata: true });
        invioInCorso = false;
        if (r.ok) {
          for (const n of Object.keys(qui)) {
            if ((risposte[n] || '') === qui[n]) {
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

      function scritto(d, area) {
        const n = String(d.n);
        risposte[n] = area.value;
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
          const si = String(risposte[n] || '').trim();
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
        if (!vivo() || consegnata) { clearInterval(timerTempo); return; }
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
            type: 'button', class: 'sim-numero' + (String(risposte[String(d.n)] || '').trim() ? ' scritta' : ''), 'aria-label': 'Domanda ' + d.n, onclick: () => { ctl.chiudi(true); salta(d.n); },
          }, String(d.n)))),
          h('button', { type: 'button', class: 'pulsante pieno largo', onclick: () => { ctl.chiudi(true); foglioConsegna(); } }, 'Consegna')),
        });
      }

      function foglioConsegna() {
        const conto = L.contaRisposte(s.domande, risposte);
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

      const sezioniViste = new Set();
      const schede = [];
      for (const d of s.domande || []) {
        const n = String(d.n);
        if (d.sezione && !sezioniViste.has(d.sezione)) {
          sezioniViste.add(d.sezione);
          schede.push(h('h2', { class: 'sim-sezione' }, d.sezione));
        }
        const area = h('textarea', { class: 'sim-risposta', rows: '5', placeholder: 'Scrivi la tua risposta', 'aria-label': 'Risposta alla domanda ' + d.n, 'data-risposta': n });
        area.value = risposte[n] || '';
        area.addEventListener('input', () => { adatta(area); scritto(d, area); });
        const stato = h('div', { class: 'sim-stato', 'data-stato-risposta': n, 'aria-live': 'polite' });
        statiEl.set(n, stato);
        const scheda = h('section', { class: 'sim-domanda', 'data-domanda': n },
          h('span', { class: 'etichetta' }, 'Domanda ' + d.n + ' · ' + L.plurale(Number(d.punti) || 0, 'punto', 'punti')),
          h('p', { class: 'testo-d' }, d.testo), area, stato);
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
        h('h1', { class: 'titolo-grande' }, s.titolo || s.argomento),
        barraStretta,
        s.nota ? A.messaggio('avviso', s.nota) : null,
        schede,
        h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'consegna-fondo', onclick: foglioConsegna }, 'Consegna')))));

      for (const a of pag.querySelectorAll('.sim-risposta')) adatta(a);
      disegnaTempo();
      aggiornaColonna();
      const timerTempo = setInterval(tic, 1000);
      if (sporche.size && !S.senzaRete) manda();
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
      return { manda };
    }

    // --- §36.4 Risultati -----------------------------------------------------------------------------------------

    function disegnaRisultati() {
      const e = s.esito || {};
      const locale = A.memoriaStudio.leggi(chiaveLocale(id)) || {};
      const oltre = L.fraseOltreTempo(locale.usati, s.minuti);
      const correzioni = new Map((e.domande || []).map((x) => [x.n, x]));

      function rigaDomanda(d) {
        const c = correzioni.get(d.n) || { esito: 'non_corretta' };
        const v = L.esitoSimulata(c.esito);
        const risposta = String((s.risposte || {})[String(d.n)] || '').trim();
        const punti = L.puntiDomanda(c, d);
        let aperta = c.esito !== 'giusta';
        const dentro = () => h('div', { class: 'dentro' },
          h('div', { class: 'blocco' }, h('span', { class: 'etichetta' }, 'La tua risposta'), risposta ? h('p', { class: 'mia' }, risposta) : h('p', { class: 'mia vuota' }, 'Non hai risposto')),
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
      svuota(pag, h('div', { class: 'sim-risultati ' + A.classeMateria(m.colore), 'data-simulata': 'corretta' },
        h('div', { class: 'sch-testa' }, A.frecciaIndietro(indietro), A.pillolaMateria(m), h('span', { class: 'spazio' }),
          A.pulsantePdf('api/simulate/' + id + '/pdf', L.nomeFilePdf(s.titolo, 'Verifica')),
          A.menuPuntini(() => [{ testo: 'Elimina', pericolo: true, azione: 'elimina-simulata', fai: elimina }])),
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
            h('button', { type: 'button', class: 'pulsante tenue alto', 'data-azione': 'rifai-simulata', onclick: () => A.nuovaSimulata(m.id, { argomento: s.argomento, materiali: s.materiali, minuti: s.minuti, indicazioni: s.indicazioni, eventoId: s.evento_id }) }, 'Rifai la verifica')))));
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    await carica();
  };
})();
