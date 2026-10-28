/*
 * Lectio per il computer: Nuova lezione (docs/aspetto.md §6, prototipo NuovaLezione.dc.html) come finestra
 * larga, e "In preparazione" (nella stessa finestra e nella pagina #lezione/12 aperta da un avviso).
 * Il costo si vede prima (stime dal server), il tetto non è un muro: la voce gratis è la strada.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  /** "Nuova lezione" dalla pagina Lezioni: se c'è più di una materia, prima si sceglie quale. */
  A.nuovaLezioneScegli = async function () {
    if (!S.materie) await A.caricaMaterie();
    const materie = S.materie || [];
    if (!materie.length) { A.apriMateria(null); return; }
    if (materie.length === 1) { A.nuovaLezione(materie[0].id); return; }
    A.apriFoglio({
      titolo: 'Per quale materia?',
      corpo: (c, ctl) => svuota(c, h('div', null, materie.map((m) => h('button', { type: 'button', class: 'riga-foglio ' + A.classeMateria(m.colore), onclick: () => { ctl.chiudi(true); A.nuovaLezione(m.id); } },
        A.pallino(m.colore, true), m.nome)))),
    });
  };

  /** Apre la finestra "Nuova lezione" per una materia; `opzioni.eventoId` = la verifica già scelta. */
  A.nuovaLezione = async function (materiaId, opzioni) {
    const o = opzioni || {};
    if (!S.materie) await A.caricaMaterie();
    const m = A.materiaDi(materiaId);
    if (!m) { A.avviso('Questa materia non c\'è più.'); return null; }
    const st = {
      argomento: '', eventoId: o.eventoId != null ? o.eventoId : undefined, durata: 20, saltabili: false, voluta: 'espressiva',
      eventi: [], materiale: [], accesi: null, cambia: false, voci: null, stime: {}, caricando: true, invio: false, errore: null,
    };
    const seq = L.creaSequenza();
    let ctl;
    const corpo = h('div', { class: 'corpo su-fondo' });
    const piede = h('div', { class: 'piede' });
    ctl = A.apriFinestra({
      etichetta: 'Nuova lezione',
      classe: 'finestra-lezione ' + A.classeMateria(m.colore),
      suChiusura: () => { clearTimeout(st.timer); st.chiusa = true; },
      disegna: (f) => svuota(f, corpo, piede),
    });
    ctl.tipo = 'nuova-lezione';
    svuota(corpo, testa(), A.segnaposto(5));

    function testa() {
      return h('div', { class: 'testa-finestra' },
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => ctl.chiudi() }, icona('chiudi')),
        A.pillolaMateria(m),
        A.modalitaProva() ? h('span', { class: 'pillola stato prova' }, 'Prova') : null);
    }

    const oggi = L.iso();
    const [ri, rm, re, rv] = await Promise.all([
      S.impostazioni ? null : A.caricaImpostazioni(),
      A.api.get('/server/api/materie/' + m.id + '/materiale'),
      A.api.get('/server/api/eventi?da=' + oggi),
      A.api.get('/server/api/voci'),
      S.salute ? null : A.caricaSalute(),
    ]);
    void ri;
    if (st.chiusa) return ctl;
    const imp = S.impostazioni || {};
    st.durata = L.durataVicina(imp.durata_predefinita || 20);
    st.saltabili = !!imp.domande_saltabili;
    st.materiale = rm.ok ? rm.dati || [] : [];
    st.eventi = re.ok ? (re.dati || []).filter((e) => e.materia_id === m.id && L.giorniFra(oggi, e.data) >= 0).sort((a, b) => a.data.localeCompare(b.data)) : [];
    st.voci = rv.ok ? rv.dati || [] : [];
    S.voci = st.voci;
    if (st.eventoId === undefined) st.eventoId = st.eventi.length ? st.eventi[0].id : null;
    const ev = st.eventi.find((e) => e.id === st.eventoId);
    st.argomento = ev && ev.argomento ? ev.argomento : '';
    const predefinita = st.voci.find((v) => v.id === imp.voce_predefinita);
    st.voluta = predefinita && predefinita.tipo === 'gratis' ? 'gratis' : 'espressiva';
    st.caricando = false;
    if (!rv.ok) st.errore = rv.errore;
    disegna();
    stima();
    return ctl;

    // --- stime (vince la più recente) ---------------------------------------------------------

    async function stima() {
      const n = seq.nuova();
      const tipi = L.voceDi(st.voci, 'prova') ? ['prova'] : ['espressiva', 'gratis'];
      // §15.5: la stima conta solo il materiale scelto (se si è toccato Cambia); con niente scelto non si chiede.
      const materiali = L.materialiScelti(st.materiale, st.accesi);
      if (materiali && !materiali.length) {
        st.stime = {};
        disegna();
        return;
      }
      const risposte = await Promise.all(tipi.map((t) => {
        const v = L.voceDi(st.voci, t);
        const corpo = { materia_id: m.id, durata_min: st.durata, voce: v ? v.id : null };
        if (materiali) corpo.materiali = materiali;
        return v ? A.api.post('/server/api/lezioni/stima', corpo) : Promise.resolve(null);
      }));
      if (!seq.valida(n) || st.chiusa) return; // una stima partita prima e arrivata dopo: si butta
      const nuove = {};
      tipi.forEach((t, i) => { if (risposte[i] && risposte[i].ok) nuove[t] = risposte[i].dati; });
      st.stime = nuove;
      const r0 = risposte.find((r) => r && !r.ok);
      st.erroreStima = r0 && !Object.keys(nuove).length ? r0.errore : null;
      disegna();
    }

    // --- disegno --------------------------------------------------------------------------------

    function stato() {
      return L.statoVoci(st.voci, st.stime, (st.stime.espressiva || st.stime.gratis || st.stime.prova || {}).tetto_eur || (imp.tetto_eur != null ? imp.tetto_eur : 2));
    }

    function disegna() {
      if (st.chiusa) return;
      const sv = stato();
      const scelta = L.sceltaVoce(st.voluta, sv);
      const stimaScelta = scelta ? st.stime[scelta] : null;
      const argomento = h('input', { class: 'campo', id: 'nl-argomento', maxlength: '200', value: st.argomento, placeholder: 'Per esempio L\'Impero romano da Augusto a Nerone', 'aria-label': 'Argomento' });
      argomento.addEventListener('input', () => { st.argomento = argomento.value; aggiornaPulsante(); });
      const per = h('div', { class: 'pillole', role: 'radiogroup', 'aria-label': 'Per' },
        st.eventi.map((e) => h('button', { type: 'button', class: 'pillola scelta', 'aria-pressed': st.eventoId === e.id ? 'true' : 'false', onclick: () => {
          st.eventoId = e.id;
          if (!st.argomento.trim() && e.argomento) st.argomento = e.argomento;
          disegna();
        } }, (e.tipo === 'orale' ? 'Interrogazione di ' : 'Verifica di ') + L.dataLunga(e.data))),
        h('button', { type: 'button', class: 'pillola scelta', 'aria-pressed': st.eventoId == null ? 'true' : 'false', onclick: () => { st.eventoId = null; disegna(); } }, 'Nessuna'));
      const appunti = st.materiale.filter((x) => ['appunti', 'libro', 'altro'].includes(x.tipo) && L.prontoDaUsare(x));
      const usero = h('div', { class: 'riga-usero' },
        h('div', { class: 'testi' }, h('span', { class: 'etichetta' }, 'Userò'), h('span', { class: 'forte', stile: { fontSize: '15px' } }, L.frasiUsero(st.materiale, m.nome, st.accesi))),
        appunti.length ? h('button', { type: 'button', class: 'pulsante testo', 'aria-expanded': st.cambia ? 'true' : 'false', onclick: () => { st.cambia = !st.cambia; if (st.cambia && !st.accesi) st.accesi = appunti.map((x) => x.id); disegna(); } }, st.cambia ? 'Fatto' : 'Cambia') : null);
      const pillolaAppunti = st.cambia ? h('div', { class: 'pillole' }, appunti.map((x) => h('button', {
        type: 'button', class: 'pillola scelta', 'aria-pressed': st.accesi.includes(x.id) ? 'true' : 'false',
        onclick: () => { st.accesi = st.accesi.includes(x.id) ? st.accesi.filter((y) => y !== x.id) : st.accesi.concat([x.id]); disegna(); stima(); },
      }, x.titolo || 'Appunti'))) : null;
      const durata = h('div', { class: 'segmenti', role: 'group', 'aria-label': 'Durata', 'data-visita': 'durata' },
        L.DURATE.map((d) => h('button', { type: 'button', 'aria-pressed': st.durata === d ? 'true' : 'false', 'data-durata': String(d), onclick: () => { if (st.durata === d) return; st.durata = d; disegna(); stima(); } }, d + ' min')));
      const domande = h('div', { class: 'segmenti', stile: { '--n': '2' }, role: 'group', 'aria-label': 'Domande' },
        [[false, 'Obbligatorie'], [true, 'Si possono saltare']].map(([v, n]) => h('button', { type: 'button', 'aria-pressed': st.saltabili === v ? 'true' : 'false', 'data-saltabili': String(v), onclick: () => { st.saltabili = v; disegna(); } }, n)));
      domande.style.setProperty('--n', '2');

      let voce;
      if (sv.prova) {
        voce = h('div', { class: 'schede-voce', stile: { gridTemplateColumns: '1fr' } },
          h('button', { type: 'button', class: 'scheda-voce', 'aria-pressed': 'true' }, h('span', { class: 'nome' }, 'Voce di prova'),
            h('span', { class: 'secondario' }, 'Voce di prova, robotica: serve la chiave Mistral per quella vera'), h('span', { class: 'costo' }, '0 €')));
      } else {
        const e = sv.espressiva;
        const g = sv.gratis;
        const costoE = h('span', { class: 'costo', 'data-costo': 'espressiva' }, e.costoVoce == null ? '…' : '');
        if (e.costoVoce != null) A.contaNumero(costoE, e.costoVoce, (x) => L.euro(x));
        voce = h('div', { class: 'schede-voce' },
          h('button', { type: 'button', class: 'scheda-voce', 'data-voce': 'espressiva', 'aria-pressed': scelta === 'espressiva' ? 'true' : 'false', disabled: e.spenta ? true : null, onclick: () => { st.voluta = 'espressiva'; disegna(); } },
            h('span', { class: 'nome' }, e.voce ? e.voce.nome : 'Lorenzo'), h('span', { class: 'secondario' }, 'voce espressiva'),
            e.spenta && e.motivo ? h('span', { class: 'motivo' }, e.motivo) : costoE),
          h('button', { type: 'button', class: 'scheda-voce', 'data-voce': 'gratis', 'aria-pressed': scelta === 'gratis' ? 'true' : 'false', disabled: g.spenta ? true : null, onclick: () => { st.voluta = 'gratis'; disegna(); } },
            h('span', { class: 'nome' }, 'Voce gratis'), h('span', { class: 'secondario' }, (g.voce ? g.voce.nome : 'Isabella') + (g.caratteri != null ? ' · ' + L.migliaia(g.caratteri) + ' caratteri gratis' : '')),
            g.spenta && g.motivo ? h('span', { class: 'motivo' }, g.motivo) : h('span', { class: 'costo' }, '0 €')));
      }

      const sStima = stimaScelta || st.stime.espressiva || st.stime.gratis || st.stime.prova;
      let mese = null;
      if (sStima) {
        const questa = stimaScelta ? Number(stimaScelta.costo_eur || 0) : 0;
        const speso = Number(sStima.spesa_mese_eur || 0);
        const tetto = Number(sStima.tetto_eur || 2);
        const dopo = Math.round((speso + questa) * 100) / 100;
        const b = L.barraMese(speso, questa, tetto);
        const barra = h('div', { class: 'barra spessa due' }, h('span', { stile: { width: b.speso + '%' } }), h('span', { class: 'questa', stile: { width: b.questa + '%' } }));
        mese = h('div', { class: 'riga-mese' + (dopo > tetto ? ' avviso' : '') },
          h('div', { class: 'righe' }, h('span', null, 'Questo mese ' + L.euro(speso)), h('span', null, 'dopo questa ' + L.euro(dopo) + ' di ' + L.euroTondo(tetto))),
          // §15.5: la spesa del mese comprende i lavori già partiti (lezioni in coda, letture): si dice quanto.
          Number(sStima.impegnato_eur) > 0 ? h('span', { class: 'secondario', 'data-impegnato': 'si' }, 'di cui ' + L.euro(sStima.impegnato_eur) + ' per lavori già partiti') : null,
          barra, h('span', { class: 'moltotenue' }, L.siAzzera(sStima.si_azzera_il)));
      }

      const campo = (etichetta, el, extra) => h('div', Object.assign({ class: 'campo-gruppo' }, extra || {}), h('span', { class: 'etichetta' }, etichetta), el);
      // Finestra larga (§6, PC): a sinistra cosa e quanto, a destra voce e soldi.
      svuota(corpo,
        testa(),
        h('h1', { class: 'titolo-pagina' }, 'Nuova lezione'),
        h('div', { class: 'griglia-nuova' },
          h('div', { class: 'colonna' },
            campo('Argomento', argomento),
            campo('Per', per),
            h('div', { class: 'campo-gruppo' }, usero, pillolaAppunti),
            campo('Durata', durata),
            campo('Domande', domande)),
          h('div', { class: 'colonna' },
            campo('Voce', voce),
            mese,
            st.erroreStima ? A.messaggio('errore', st.erroreStima, [h('button', { type: 'button', class: 'pulsante bianco', onclick: stima }, 'Riprova')]) : null)));

      const niente = (L.materialiScelti(st.materiale, st.accesi) || [1]).length === 0;
      const errore = st.errore ? A.messaggio('errore', st.errore)
        : niente ? A.messaggio('avviso', 'Scegli almeno un appunto o una verifica: senza materiale non posso preparare la lezione.')
          : S.senzaRete ? A.messaggio('avviso', 'Senza rete non si può preparare una lezione.') : null;
      const pulsante = h('button', { type: 'button', class: 'pulsante pieno largo pulsante-prepara', 'data-azione': 'prepara', 'data-visita': 'prepara', onclick: prepara });
      svuota(piede, errore, pulsante);
      st.pulsante = pulsante;
      st.scelta = scelta;
      st.costo = stimaScelta ? Number(stimaScelta.costo_eur || 0) : null;
      st.consentita = stimaScelta ? stimaScelta.consentita !== false : false;
      aggiornaPulsante();
      if (!st.primoFuoco) { st.primoFuoco = true; setTimeout(() => { if (!st.argomento) argomento.focus(); }, 40); }
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    function aggiornaPulsante() {
      const p = st.pulsante;
      if (!p) return;
      const vuoto = (L.materialiScelti(st.materiale, st.accesi) || [1]).length === 0;
      p.textContent = st.invio ? 'Chiedo la lezione…' : vuoto ? 'Prepara la lezione' : L.etichettaPrepara(st.costo);
      p.disabled = st.invio || vuoto || !st.scelta || !st.consentita || !st.argomento.trim() || S.senzaRete;
    }

    async function prepara() {
      if (st.invio) return;
      const voce = L.voceDi(st.voci, st.scelta);
      if (!voce) return;
      const corpoRichiesta = {
        materia_id: m.id, evento_id: st.eventoId, argomento: st.argomento.trim(), durata_min: st.durata, voce: voce.id, domande_saltabili: st.saltabili,
      };
      // §15.1: i materiali si mandano solo se Andrea ha toccato Cambia (verifiche e interrogazioni sempre).
      // §15.1: i materiali si mandano solo se Andrea ha toccato Cambia, e solo quelli già letti (pronti).
      const materiali = L.materialiScelti(st.materiale, st.accesi);
      if (materiali) {
        if (!materiali.length) return;
        corpoRichiesta.materiali = materiali;
      }
      st.invio = true;
      st.errore = null;
      aggiornaPulsante();
      const r = await A.api.post('/server/api/lezioni', corpoRichiesta);
      st.invio = false;
      if (!r.ok) {
        st.errore = r.errore;
        if (r.stato === 402) st.voluta = 'gratis';
        disegna();
        if (r.stato === 402) stima();
        return;
      }
      if (S.motore) S.motore.in_preparazione = true;
      A.vistaPreparazione(corpo, piede, r.dati, { materia: m, ctl, durata: st.durata });
    }
  };

  /**
   * "In preparazione" (§6): quattro fasi con spunta, barra, "Puoi uscire: ti avviso io". Si chiede al server ogni
   * 20 secondi; a fine lavoro la spunta grande e Ascolta; con l'errore la frase del server e Riprova.
   */
  A.vistaPreparazione = function (corpo, piede, lezione, opzioni) {
    const o = opzioni || {};
    const m = o.materia || A.materiaDi(lezione.materia_id) || { nome: '', colore: 'ambra' };
    let l = lezione;
    let timer = null;
    const vivo = () => corpo.isConnected;

    async function aggiorna() {
      const r = await A.api.get('/server/api/lezioni/' + l.id);
      if (!vivo()) return;
      if (r.ok && r.dati) {
        l = r.dati;
        disegna();
      } else {
        disegna(r.errore);
      }
    }

    function disegna(erroreRete) {
      clearTimeout(timer);
      const f = L.fasi(l);
      const titolo = h('div', { stile: { display: 'flex', flexDirection: 'column', gap: '4px' } },
        h('span', { class: 'etichetta etichetta-materia ' + A.classeMateria(m.colore) }, m.nome + ' · ' + (l.durata_min || o.durata || '') + ' min'),
        h('h1', { class: 'titolo-pagina', stile: { fontSize: '28px', letterSpacing: '-0.5px' } }, l.argomento || 'Lezione'));
      const chiudi = o.ctl ? h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => o.ctl.chiudi() }, icona('chiudi')) : null;
      const parti = [h('div', { class: 'testa-finestra' }, chiudi, A.pillolaMateria(m), A.modalitaProva() ? h('span', { class: 'pillola stato prova' }, 'Prova') : null), titolo];
      const bottoni = [];
      if (f.pronta) {
        parti.push(h('div', { class: 'spunta-grande', 'aria-hidden': 'true' }, icona('spunta')),
          h('p', { class: 'forte', stile: { textAlign: 'center', fontSize: '19px' } }, 'La lezione è pronta'),
          l.nota ? h('p', { class: 'secondario', stile: { textAlign: 'center' } }, l.nota) : null);
        bottoni.push(h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'ascolta-pronta', onclick: () => { if (o.ctl) o.ctl.chiudi(true); A.ascolta(l.id, { suona: true, apri: true }); } }, icona('play'), 'Ascolta'));
      } else {
        parti.push(h('div', { class: 'fasi' }, f.righe.map((x) => h('div', { class: 'fase ' + x.stato },
          h('span', { class: 'segno', 'aria-hidden': 'true' }, x.stato === 'fatto' ? icona('spunta') : null), h('span', null, x.nome),
          x.stato === 'fatto' ? h('span', { class: 'sr' }, 'fatto') : x.stato === 'corso' ? h('span', { class: 'sr' }, 'in corso') : null))));
        parti.push(A.barra(f.percentuale, 'spessa'));
        if (f.errore) {
          parti.push(A.messaggio('errore', l.errore || 'La lezione non è riuscita.'));
          bottoni.push(h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'riprova-lezione', onclick: async (e) => {
            e.currentTarget.disabled = true;
            const r = await A.api.post('/server/api/lezioni/' + l.id + '/riprova', {});
            if (!r.ok) { A.avviso(r.errore); disegna(); return; }
            l = Object.assign({}, l, r.dati || {}, { stato: (r.dati && r.dati.stato) || 'in_coda', errore: null });
            disegna();
          } }, 'Riprova'));
        } else if (l.stato === 'annullata') {
          parti.push(A.messaggio('avviso', 'Lezione annullata. Le spese già fatte restano nel conto del mese.'));
        } else {
          parti.push(h('span', { class: 'secondario', stile: { fontSize: '15px', fontWeight: '600' } }, f.inCoda ? 'In coda: parte appena finisce l\'altra lezione. Puoi uscire: ti avviso io.' : 'Puoi uscire: ti avviso io.'));
          if (erroreRete) parti.push(A.messaggio('avviso', erroreRete));
          if (o.ctl) bottoni.push(h('button', { type: 'button', class: 'pulsante tenue alto largo', onclick: () => o.ctl.chiudi() }, 'Chiudi'));
          bottoni.push(h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'annulla-lezione', onclick: async () => {
            if (!(await A.conferma({ titolo: 'Annullare la lezione?', testo: 'Le spese già fatte restano nel conto del mese.', si: 'Annulla la lezione', no: 'Continua', pericolo: true }))) return;
            const r = await A.api.post('/server/api/lezioni/' + l.id + '/annulla', {});
            if (!r.ok) { A.avviso(r.errore); return; }
            l = Object.assign({}, l, { stato: 'annullata' });
            disegna();
          } }, 'Annulla la lezione'));
          timer = setTimeout(() => { if (vivo()) aggiorna(); }, T.preparazione);
        }
      }
      svuota(corpo, h('div', { class: 'vista-prep' }, parti));
      svuota(piede, bottoni.length ? h('div', { class: 'vista-prep' }, bottoni) : null);
      piede.style.display = bottoni.length ? '' : 'none';
    }
    disegna();
    return { aggiorna };
  };

  /** #lezione/12 (clic su un avviso, riga di una lezione non pronta): pronta → lettore, altrimenti In preparazione. */
  A.pagine.lezione = async function (box, rotta, vivo) {
    const pag = h('div', { class: 'pagina' }, A.segnaposto(4));
    svuota(box, pag);
    const r = await A.api.get('/server/api/lezioni/' + rotta.id);
    if (!vivo()) return;
    if (!r.ok) { svuota(pag, A.erroreConRiprova(r, () => A.disegna())); return; }
    if (r.dati.stato === 'pronta') { location.replace('#lettore/' + rotta.id); return; }
    if (!S.materie) await A.caricaMaterie();
    const corpo = h('div', { class: 'su-fondo', stile: { display: 'flex', flexDirection: 'column', gap: '20px', maxWidth: '640px' } });
    const piede = h('div', { stile: { display: 'flex', flexDirection: 'column', gap: '10px', maxWidth: '640px' } });
    svuota(pag, corpo, piede);
    const v = A.vistaPreparazione(corpo, piede, r.dati, {});
    S.aggiornaPagina = v.aggiorna;
  };
})();
