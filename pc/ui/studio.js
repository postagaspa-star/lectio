/*
 * Lectio per il computer: i pezzi comuni delle pagine di studio (docs/aspetto.md §30-41, contratto §17-26).
 * Nuovo schema e Nuova verifica simulata (stessa finestra di Nuova lezione), righe della materia, PDF, segni di
 * fonte, vista "ci sto lavorando". Qui si decide solo come si mostra: stime e contenuti li fa il server.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, h, svuota, icona } = A;

  // --- Quello che resta su questo computer (nel modo esempio solo in memoria: chiusa la visita sparisce) ---------

  const inEsempio = new Map();
  A.memoriaStudio = {
    leggi(chiave) {
      if (S.esempio) return inEsempio.has(chiave) ? JSON.parse(inEsempio.get(chiave)) : null;
      try { return JSON.parse(localStorage.getItem(chiave) || 'null'); } catch (e) { return null; }
    },
    scrivi(chiave, valore) {
      if (S.esempio) { inEsempio.set(chiave, JSON.stringify(valore)); return; }
      try { localStorage.setItem(chiave, JSON.stringify(valore)); } catch (e) { /* senza memoria del browser va bene lo stesso */ }
    },
    togli(chiave) {
      if (S.esempio) { inEsempio.delete(chiave); return; }
      try { localStorage.removeItem(chiave); } catch (e) { /* niente da togliere */ }
    },
  };

  // --- Piccoli pezzi --------------------------------------------------------------------------------------------

  /** Il testo del server con il grassetto: mai HTML, solo testo e <strong>. */
  A.conGrassetto = (testo) => L.pezziGrassetto(testo).map((p) => (p.forte ? h('strong', null, p.t) : p.t));

  /** Il menu con i puntini. `voci()` = [{ testo, fai, pericolo, spenta, azione }]; `o.aperto(si)` dice se è aperto. */
  A.menuPuntini = function (voci, o) {
    const opz = o || {};
    const menu = h('div', { class: 'menu-piccolo' });
    const apri = () => {
      const c = menu.querySelector('.tendina');
      if (c) { c.remove(); if (opz.aperto) opz.aperto(false); return; }
      const t = h('div', { class: 'tendina', role: 'menu' }, voci().map((v) => h('button', {
        type: 'button', role: 'menuitem', class: v.pericolo ? 'pericolo' : null, disabled: v.spenta ? true : null, 'data-azione': v.azione || null,
        onclick: () => { chiudi(); v.fai(); },
      }, v.testo)));
      function chiudi() { t.remove(); document.removeEventListener('mousedown', fuori, true); if (opz.aperto) opz.aperto(false); }
      function fuori(e) { if (!menu.contains(e.target)) chiudi(); }
      document.addEventListener('mousedown', fuori, true);
      menu.append(t);
      if (opz.aperto) opz.aperto(true);
    };
    menu.append(h('button', { type: 'button', class: opz.classe || 'tondo', 'aria-label': opz.etichetta || 'Altre azioni', 'data-azione': 'puntini', onclick: (e) => { e.stopPropagation(); apri(); } }, icona('puntini')));
    return menu;
  };

  A.frecciaIndietro = (fai) => h('button', { type: 'button', class: 'tondo grande', 'aria-label': 'Indietro', 'data-azione': 'indietro', onclick: fai }, icona('indietro'));

  /** Torna da dove si era venuti; aperta da un avviso, si torna alla materia. */
  A.tornaIndietroDa = function (materiaId, segmento) {
    if (S.storia.length > 1) { history.back(); return; }
    A.vai(materiaId ? '#materia/' + materiaId + (segmento ? '/' + segmento : '') : '#materie');
  };

  /** Scarica un PDF del server (§33.9): si sceglie dove salvarlo, poi "PDF salvato" con Apri. Errore: frase e Riprova. */
  A.scaricaPdf = async function (percorso, nome, dopo) {
    const r = await A.api.post('/locale/salva-pdf', { percorso, nome });
    if (r.ok && r.dati && r.dati.annullato) return null;
    if (r.ok && r.dati && r.dati.ok) {
      A.avviso('PDF salvato', { dura: 6000, azione: { testo: 'Apri', fai: async () => { const x = await A.api.post('/locale/apri-pdf', { id: r.dati.id }); if (!x.ok) A.avviso(x.errore); } } });
      if (dopo) dopo();
      return r.dati;
    }
    A.avviso(r.errore || 'Non sono riuscito a salvare il PDF.', { dura: 6000, azione: { testo: 'Riprova', fai: () => A.scaricaPdf(percorso, nome, dopo) } });
    return null;
  };

  /** Il pulsante Scarica PDF: l'etichetta diventa "Preparo il PDF…" e si spegne finché lavora. */
  A.pulsantePdf = function (percorso, nome, opzioni) {
    const o = opzioni || {};
    const testo = o.testo || 'Scarica PDF';
    const etichetta = h('span', null, testo);
    const b = h('button', {
      type: 'button', class: o.classe || 'pillola-azione', 'data-azione': o.azione || 'scarica-pdf', disabled: S.senzaRete ? true : null,
      onclick: async () => {
        etichetta.textContent = 'Preparo il PDF…';
        b.disabled = true;
        await A.scaricaPdf(percorso, nome, o.dopo);
        etichetta.textContent = testo;
        b.disabled = !!S.senzaRete;
      },
    }, o.senzaIcona ? null : icona('scarica'), etichetta);
    return b;
  };

  // --- Da dove viene (§33.4) ------------------------------------------------------------------------------------

  /** Apre il materiale di una fonte come pagina sopra quello che c'è: chiusa quella, si è nello stesso punto. */
  A.apriFonte = async function (f) {
    const r = await A.api.get('/server/api/materiale/' + f.materiale_id);
    if (!r.ok || !r.dati) { A.avviso(r.errore || 'Questo materiale non c\'è più.'); return; }
    if (!S.materie) await A.caricaMaterie();
    A.apriMateriale(r.dati, A.materiaDi(r.dati.materia_id) || { nome: '', colore: 'ambra' }, () => {});
  };

  A.rigaFonte = (f, fai) => h('button', { type: 'button', class: 'capito-fonte', 'data-fonte': f.etichetta, onclick: fai },
    icona(A.iconaTipo(f.tipo)), h('span', { class: 'testi' }, h('span', { class: 'e' }, f.etichetta), f.titolo && !f.etichetta.includes(f.titolo) ? h('span', { class: 't' }, f.titolo) : null), icona('freccia'));

  /** Una fonte: apre il materiale. Più fonti: foglio con una riga per fonte. */
  A.apriFonti = function (fonti) {
    if (fonti.length === 1) { A.apriFonte(fonti[0]); return; }
    A.apriFoglio({
      titolo: 'Da dove viene',
      corpo: (c, ctl) => svuota(c, h('div', { class: 'capito-fonti' }, fonti.map((f) => A.rigaFonte(f, () => { ctl.chiudi(true); A.apriFonte(f); })))),
    });
  };

  A.segnoFonte = function (fonti, attributi) {
    if (!fonti || !fonti.length) return null;
    return h('button', Object.assign({ type: 'button', class: 'segno-fonte', 'data-segno': 'fonte', onclick: () => A.apriFonti(fonti) }, attributi || {}), L.segnoFonti(fonti));
  };

  // --- Ci sto lavorando (§33.9) ---------------------------------------------------------------------------------

  /** Titolo, frase del server, barra, Annulla: uguale per schemi e verifiche. */
  A.vistaLavoro = function (o) {
    return h('div', { class: 'vista-lavoro', 'data-vista': 'lavoro' },
      h('div', { class: 'sch-testa' }, A.frecciaIndietro(o.indietro), A.pillolaMateria(o.materia), A.modalitaProva() ? h('span', { class: 'pillola stato prova' }, 'Prova') : null),
      h('h1', { class: 'titolo-pagina' }, o.titolo),
      h('span', { class: 'frase', 'data-frase': 'lavoro' }, o.frase),
      o.percentuale != null ? A.barra(o.percentuale, 'spessa') : h('div', { class: 'segnaposto riga-finta', stile: { width: '100%' } }),
      o.annulla ? h('div', null, h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'annulla-lavoro', onclick: o.annulla }, 'Annulla')) : null);
  };

  // --- Righe della materia (§31.2) ------------------------------------------------------------------------------

  async function eliminaRiga(titolo, testo, percorso, ricarica) {
    if (!(await A.conferma({ titolo, testo, si: 'Elimina', pericolo: true }))) return;
    const r = await A.api.elimina(percorso);
    if (!r.ok) { A.avviso(r.errore); return; }
    ricarica();
  }

  async function riprovaRiga(percorso, ricarica) {
    const r = await A.api.post(percorso, {});
    if (!r.ok) { A.avviso(r.errore); return; }
    if (S.motore) S.motore.in_preparazione = true;
    ricarica();
  }

  function rigaStudio(m, nomeIcona, attributi, titolo, frase, apri, destra, menu, sotto) {
    return h('div', Object.assign({ class: 'riga riga-studio ' + A.classeMateria(m.colore) }, attributi),
      h('button', { type: 'button', class: 'apri', onclick: apri },
        h('span', { class: 'piastrella-icona', 'aria-hidden': 'true' }, icona(nomeIcona)),
        h('span', { class: 'testi' }, h('span', { class: 'titolo-riga' }, titolo), frase, sotto || null)),
      destra || null, menu);
  }

  A.rigaSchema = function (x, m, ricarica) {
    const st = L.statoRigaSchema(x);
    const percorso = '/server/api/schemi/' + x.id;
    const menu = A.menuPuntini(() => {
      const v = [];
      if (x.stato === 'pronto') v.push({ testo: 'Scarica PDF', azione: 'scarica-pdf-riga', spenta: S.senzaRete, fai: () => { A.avviso('Preparo il PDF…'); A.scaricaPdf('api/schemi/' + x.id + '/pdf', L.nomeFilePdf(x.titolo, 'Schema')); } });
      v.push({ testo: 'Elimina', pericolo: true, azione: 'elimina-schema', fai: () => eliminaRiga('Eliminare lo schema?', '"' + x.titolo + '" sparisce. I soldi già spesi restano nel conto del mese.', percorso, ricarica) });
      return v;
    });
    const frase = h('span', { class: 'secondario' + (st.tipo === 'errore' ? ' err' : '') }, st.frase);
    return rigaStudio(m, 'testo', { 'data-schema': String(x.id) }, x.titolo || x.argomento, frase, () => A.vai('#schema/' + x.id),
      st.tipo === 'errore' ? h('button', { type: 'button', class: 'pulsante tenue piccolo', 'data-azione': 'riprova-schema', onclick: () => riprovaRiga(percorso + '/riprova', ricarica) }, 'Riprova') : null,
      menu, st.tipo === 'lavoro' ? A.barra(st.percentuale, 'sottile') : null);
  };

  A.rigaSimulata = function (x, m, ricarica) {
    const st = L.statoRigaSimulata(x);
    const percorso = '/server/api/simulate/' + x.id;
    const menu = A.menuPuntini(() => {
      const v = [];
      if (['pronta', 'in_corso', 'corretta', 'in_correzione'].includes(x.stato)) v.push({ testo: 'Scarica PDF', azione: 'scarica-pdf-riga', spenta: S.senzaRete, fai: () => { A.avviso('Preparo il PDF…'); A.scaricaPdf('api/simulate/' + x.id + '/pdf', L.nomeFilePdf(x.titolo, 'Verifica')); } });
      v.push({ testo: 'Elimina', pericolo: true, azione: 'elimina-simulata', fai: () => eliminaRiga('Eliminare la verifica simulata?', '"' + x.titolo + '" sparisce, con le tue risposte.', percorso, () => { A.memoriaStudio.togli('lectio.simulata.' + x.id); ricarica(); }) });
      return v;
    });
    const frase = h('span', { class: 'secondario' + (st.tipo === 'errore' ? ' err' : '') }, st.frase);
    let destra = null;
    if (st.tipo === 'errore') destra = h('button', { type: 'button', class: 'pulsante tenue piccolo', 'data-azione': 'riprova-simulata', onclick: () => riprovaRiga(percorso + '/riprova', ricarica) }, 'Riprova');
    if (st.tipo === 'meta') destra = h('button', { type: 'button', class: 'pulsante tenue piccolo', 'data-azione': 'riprendi-simulata', onclick: () => A.vai('#simulata/' + x.id) }, 'Riprendi');
    return rigaStudio(m, 'verifica', { 'data-simulata': String(x.id) }, x.titolo || x.argomento, frase, () => A.vai('#simulata/' + x.id), destra, menu, st.tipo === 'lavoro' ? A.barra(st.percentuale, 'sottile') : null);
  };

  // --- Nuovo schema e Nuova verifica simulata (§32, §36.1) ------------------------------------------------------

  const GENERI = {
    schema: {
      titolo: 'Nuovo schema', stima: '/server/api/schemi/stima', crea: '/server/api/schemi', rotta: '#schema/', sezione3: 'Come lo vuoi', etichetta: 'Il tuo schema',
      indicazioni: 'Per esempio: metti una tabella con le date', prepara: 'Prepara lo schema', chiedo: 'Chiedo lo schema…', senzaRete: 'Senza rete non si può preparare uno schema.', parolaPronto: 'PRONTO',
    },
    simulata: {
      titolo: 'Nuova verifica simulata', stima: '/server/api/simulate/stima', crea: '/server/api/simulate', rotta: '#simulata/', sezione3: 'Come la vuoi', etichetta: 'La tua verifica',
      indicazioni: 'Per esempio: più domande sul Seicento', prepara: 'Prepara la verifica', chiedo: 'Chiedo la verifica…', senzaRete: 'Senza rete non si può preparare una verifica.', parolaPronto: 'PRONTA',
    },
  };

  async function finestraNuovo(genere, materiaId, opzioni) {
    const o = opzioni || {};
    const G = GENERI[genere];
    const schema = genere === 'schema';
    if (!S.materie) await A.caricaMaterie();
    const m = A.materiaDi(materiaId);
    if (!m) { A.avviso('Questa materia non c\'è più.'); return null; }
    const st = {
      argomento: o.argomento || '', eventoId: o.eventoId !== undefined ? o.eventoId : undefined, indicazioni: o.indicazioni || '', forma: o.forma || 'auto', livello: o.livello || 'completo', minuti: o.minuti || 50,
      eventi: [], materiale: [], erroreMateriale: null, auto: !Array.isArray(o.materiali), suggeriti: [], suggeritiArrivati: false, manuali: [], cerca: '',
      stima: null, chiaveStima: null, erroreStima: null, invio: false, errore: null, chiusa: false,
    };
    const seqStima = L.creaSequenza();
    const seqSugg = L.creaSequenza();
    const corpo = h('div', { class: 'corpo su-fondo' });
    const ctl = A.apriFinestra({
      etichetta: G.titolo,
      classe: 'finestra-lezione ' + A.classeMateria(m.colore),
      suChiusura: () => { clearTimeout(st.timerSugg); clearTimeout(st.timerStima); st.chiusa = true; if (st.osserva) st.osserva.disconnect(); },
      disegna: (f) => svuota(f, corpo),
    });
    ctl.tipo = 'nuovo-' + genere;
    const testa = () => h('div', { class: 'nl-testa' },
      h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => ctl.chiudi() }, icona('chiudi')),
      h('h1', { class: 'titolo-pagina' }, G.titolo),
      A.pillolaMateria(m),
      A.modalitaProva() ? h('span', { class: 'pillola stato prova' }, 'Prova') : null);
    svuota(corpo, testa(), A.segnaposto(5));

    const oggi = L.iso();
    const [, rm, re] = await Promise.all([
      S.impostazioni ? null : A.caricaImpostazioni(),
      A.api.get('/server/api/materie/' + m.id + '/materiale'),
      A.api.get('/server/api/eventi?da=' + oggi),
      S.salute ? null : A.caricaSalute(),
    ]);
    if (st.chiusa) return ctl;
    st.materiale = rm.ok ? rm.dati || [] : [];
    if (!rm.ok) st.erroreMateriale = rm.errore;
    st.eventi = re.ok ? (re.dati || []).filter((e) => e.materia_id === m.id && L.giorniFra(oggi, e.data) >= 0).sort((a, b) => a.data.localeCompare(b.data)) : [];
    if (st.eventoId === undefined) st.eventoId = st.eventi.length ? st.eventi[0].id : null;
    if (!st.argomento) { const ev = st.eventi.find((e) => e.id === st.eventoId); st.argomento = ev && ev.argomento ? ev.argomento : ''; }
    if (Array.isArray(o.materiali)) st.manuali = o.materiali.filter((id) => st.materiale.some((x) => x.id === id && L.prontoDaUsare(x)));

    const el = {};
    el.argomento = h('input', { class: 'nl-campo', id: 'nl-argomento', maxlength: '200', value: st.argomento, placeholder: 'Per esempio L\'Impero romano da Augusto a Nerone', 'aria-label': 'Argomento', autocomplete: 'off' });
    el.argomento.addEventListener('input', () => {
      st.argomento = el.argomento.value;
      st.errore = null;
      disegnaRiepilogo();
      seqSugg.nuova();
      clearTimeout(st.timerSugg);
      st.timerSugg = setTimeout(chiediSuggeriti, 400);
    });
    el.per = h('div', { class: 'nl-riga-per', role: 'group', 'aria-label': 'Per' });
    el.conta = h('span', { class: 'nl-conta', 'data-conta': 'materiale' });
    el.modi = h('div', { class: 'nl-modi' });
    el.cerca = h('input', { class: 'nl-cerca', id: 'nl-cerca', type: 'search', placeholder: 'Cerca nel materiale', 'aria-label': 'Cerca nel materiale', autocomplete: 'off' });
    el.cerca.addEventListener('input', () => { st.cerca = el.cerca.value; disegnaRighe(); });
    el.studio = h('div', { class: 'nl-righe', 'data-gruppo': 'studio' });
    el.prof = h('div', { class: 'nl-righe', 'data-gruppo': 'prof' });
    el.forme = h('div', { class: 'nl-forme', role: 'group', 'aria-label': 'Forma', 'data-visita': 'forma' });
    el.livello = h('div', { class: 'segmenti livello', role: 'group', 'aria-label': 'Livello' });
    el.durata = h('div', { class: 'segmenti', role: 'group', 'aria-label': 'Durata', stile: { '--n': '4' } });
    el.durata.style.setProperty('--n', '4');
    el.indicazioni = h('textarea', { class: 'nl-area', id: 'nl-indicazioni', rows: '4', maxlength: String(L.MASSIMO_INDICAZIONI), placeholder: G.indicazioni, 'aria-label': 'Le tue indicazioni' });
    el.indicazioni.value = st.indicazioni;
    el.indicazioni.addEventListener('input', () => { st.indicazioni = el.indicazioni.value; disegnaIndicazioni(); disegnaRiepilogo(); chiediStimaTraPoco(); });
    el.contaInd = h('span', { 'data-conta': 'indicazioni' });
    el.spunti = h('div', { class: 'nl-spunti' });
    el.riepilogo = h('aside', { class: 'nl-riepilogo', 'data-visita': 'riepilogo-nuovo', 'aria-label': G.etichetta });
    el.barra = h('div', { class: 'nl-barra via' });
    if (window.IntersectionObserver) {
      const osserva = new IntersectionObserver((voci) => { el.barra.classList.toggle('via', voci[voci.length - 1].isIntersecting); }, { root: corpo });
      osserva.observe(el.riepilogo);
      st.osserva = osserva;
    }
    const etichetta = (t) => h('span', { class: 'etichetta' }, t);
    const sezione = (n, titolo, destra, ...dentro) => h('section', { class: 'nl-sezione', 'data-sezione': String(n) },
      h('div', { class: 'nl-titolo' }, h('span', { class: 'nl-numero', 'aria-hidden': 'true' }, String(n)), h('h2', null, titolo), destra || null), dentro);

    svuota(corpo, testa(),
      h('div', { class: 'nl-griglia' },
        h('div', { class: 'nl-sezioni' },
          sezione(1, 'Su cosa', null, h('label', { class: 'campo-gruppo', for: 'nl-argomento' }, etichetta('Argomento'), el.argomento), el.per),
          sezione(2, 'Da quale materiale', el.conta, el.modi,
            st.erroreMateriale ? A.messaggio('errore', st.erroreMateriale) : null,
            etichetta('Appunti e libro'), el.studio, h('span', { class: 'etichetta nl-gruppo' }, 'Verifiche e interrogazioni del prof'), el.prof),
          sezione(3, G.sezione3, null,
            schema ? [etichetta('Forma'), el.forme, etichetta('Livello'), el.livello] : [etichetta('Durata'), el.durata],
            h('label', { class: 'campo-gruppo', for: 'nl-indicazioni' }, h('span', { class: 'etichetta nl-etichetta-riga' }, h('span', null, 'Le tue indicazioni'), el.contaInd), el.indicazioni),
            schema ? el.spunti : null)),
        el.riepilogo),
      el.barra);

    disegnaPer();
    disegnaMateriale();
    if (schema) { disegnaForme(); disegnaLivello(); } else disegnaDurata();
    disegnaIndicazioni();
    disegnaRiepilogo();
    setTimeout(() => { if (!st.chiusa && !st.argomento && !S.visita) el.argomento.focus(); }, 40);
    if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    if (st.argomento.trim() && st.auto) chiediSuggeriti();
    else dopoCambioMateriale();
    return ctl;

    function pronti() { return st.materiale.filter(L.prontoDaUsare); }
    function spuntati() { return L.materialiSpuntati(st.materiale, st.auto, st.suggeriti, st.manuali); }

    async function chiediSuggeriti() {
      clearTimeout(st.timerSugg);
      const n = seqSugg.nuova();
      const arg = st.argomento.trim();
      if (!arg || !st.auto) {
        if (!arg) st.suggeriti = [];
        dopoCambioMateriale();
        return;
      }
      const r = await A.api.get('/server/api/materie/' + m.id + '/suggeriti?argomento=' + encodeURIComponent(arg));
      if (!seqSugg.valida(n) || st.chiusa) return;
      if (r.ok && r.dati && Array.isArray(r.dati.suggeriti)) {
        st.suggeriti = r.dati.suggeriti.map(Number);
        st.suggeritiArrivati = true;
      } else if (!st.suggeritiArrivati) st.suggeriti = null;
      dopoCambioMateriale();
    }

    function chiaveAttuale() {
      return [st.forma, st.livello, st.minuti, st.indicazioni.trim(), spuntati().join(',')].join('|');
    }

    function dopoCambioMateriale() {
      disegnaMateriale();
      disegnaRiepilogo();
      if (chiaveAttuale() !== st.chiaveStima) chiediStima();
    }

    function chiediStimaTraPoco() {
      clearTimeout(st.timerStima);
      st.timerStima = setTimeout(chiediStima, 400);
    }

    async function chiediStima() {
      clearTimeout(st.timerStima);
      const ids = spuntati();
      st.chiaveStima = chiaveAttuale();
      const n = seqStima.nuova();
      if (!ids.length) {
        st.stima = null;
        st.erroreStima = null;
        disegnaRiepilogo();
        return;
      }
      const dati = schema
        ? { materia_id: m.id, argomento: st.argomento.trim(), materiali: ids, forma: st.forma, livello: st.livello, indicazioni: st.indicazioni.trim() }
        : { materia_id: m.id, argomento: st.argomento.trim(), materiali: ids, minuti: st.minuti, indicazioni: st.indicazioni.trim() };
      const r = await A.api.post(G.stima, dati);
      if (!seqStima.valida(n) || st.chiusa) return;
      st.stima = r.ok ? r.dati : null;
      st.erroreStima = r.ok ? null : r.errore;
      disegnaRiepilogo();
    }

    function tocca(id) {
      const ora = spuntati();
      st.auto = false;
      st.manuali = ora.includes(id) ? ora.filter((x) => x !== id) : ora.concat([id]);
      dopoCambioMateriale();
    }

    function modo(quale) {
      if (quale === 'auto') {
        st.auto = true;
        if (!st.suggeritiArrivati && st.argomento.trim()) { chiediSuggeriti(); return; }
      } else {
        st.auto = false;
        st.manuali = quale === 'tutti' ? pronti().map((x) => x.id) : [];
      }
      dopoCambioMateriale();
    }

    function pillola(testo, premuta, fai, extra) {
      return h('button', Object.assign({ type: 'button', class: 'nl-pillola', 'aria-pressed': premuta ? 'true' : 'false', onclick: fai }, extra || {}), testo);
    }

    function eventoScelto() { return st.eventi.find((e) => e.id === st.eventoId) || null; }

    function disegnaPer() {
      const mostrati = st.eventi.slice(0, 1);
      const scelto = eventoScelto();
      if (scelto && !mostrati.includes(scelto)) mostrati.push(scelto);
      svuota(el.per, etichetta('Per'),
        pillola('Nessuna data', st.eventoId == null, () => { st.eventoId = null; disegnaPer(); disegnaRiepilogo(); }, { 'data-per': 'nessuna' }),
        mostrati.map((e) => pillola(L.etichettaEvento(e), st.eventoId === e.id, () => {
          st.eventoId = e.id;
          if (!st.argomento.trim() && e.argomento) {
            st.argomento = e.argomento;
            el.argomento.value = e.argomento;
            chiediSuggeriti();
          }
          disegnaPer();
          disegnaRiepilogo();
        }, { 'data-per': String(e.id) })));
    }

    function disegnaMateriale() {
      const n = spuntati().length;
      const tot = pronti().length;
      el.conta.textContent = n + ' di ' + tot + (tot === 1 ? ' scelto' : ' scelti');
      svuota(el.modi,
        pillola('Sull\'argomento', st.auto, () => modo('auto'), { 'data-modo': 'argomento' }),
        pillola('Tutti', !st.auto && tot > 0 && n === tot, () => modo('tutti'), { 'data-modo': 'tutti' }),
        pillola('Nessuno', !st.auto && n === 0, () => modo('nessuno'), { 'data-modo': 'nessuno' }),
        h('span', { class: 'spazio' }), el.cerca);
      disegnaRighe();
    }

    function disegnaRighe() {
      const scelti = spuntati();
      const sugg = st.suggeriti || [];
      const g = L.gruppiMateriale(st.materiale);
      const riga = (x) => {
        const pronto = L.prontoDaUsare(x);
        const si = scelti.includes(x.id);
        return h('button', { type: 'button', class: 'nl-mat', role: 'checkbox', 'aria-checked': si ? 'true' : 'false', 'data-scelta': String(x.id), disabled: pronto ? null : true, title: x.titolo || null, onclick: () => tocca(x.id) },
          h('span', { class: 'nl-casella', 'aria-hidden': 'true' }, icona('spunta')),
          h('span', { class: 'testi' }, h('span', { class: 't' }, x.titolo || x.nome_file || 'Materiale'),
            pronto ? h('span', { class: 's' }, L.sottoMateriale(x)) : h('span', { class: 's leggo' }, 'Lo sto leggendo')),
          pronto && sugg.includes(x.id) ? h('span', { class: 'nl-sugg' }, 'argomento') : null);
      };
      const elenco = (box, lista, vuoto) => {
        const visti = L.filtraPerTitolo(lista, st.cerca);
        svuota(box, visti.length ? visti.map(riga) : h('span', { class: 'nl-vuoto' }, lista.length ? 'Nessun titolo con queste parole' : vuoto));
      };
      elenco(el.studio, g.studio, 'Nessuno ancora');
      elenco(el.prof, g.prof, 'Nessuna ancora');
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    function disegnaForme() {
      svuota(el.forme, L.FORME_SCHEMA.map((f) => h('button', {
        type: 'button', class: 'nl-scheda', 'aria-pressed': st.forma === f.forma ? 'true' : 'false', 'data-forma': f.forma,
        onclick: () => { st.forma = f.forma; disegnaForme(); disegnaRiepilogo(); chiediStima(); },
      }, h('span', { class: 'n' }, f.nome), h('span', { class: 'f' }, f.frase))));
    }

    function disegnaLivello() {
      svuota(el.livello, L.LIVELLI_SCHEMA.map((l) => h('button', {
        type: 'button', 'aria-pressed': st.livello === l.livello ? 'true' : 'false', 'data-livello': l.livello,
        onclick: () => { st.livello = l.livello; disegnaLivello(); disegnaRiepilogo(); chiediStima(); },
      }, l.nome)));
    }

    function disegnaDurata() {
      svuota(el.durata, L.DURATE_SIMULATA.map((d) => h('button', {
        type: 'button', 'aria-pressed': st.minuti === d ? 'true' : 'false', 'data-minuti': String(d),
        onclick: () => { st.minuti = d; disegnaDurata(); disegnaRiepilogo(); chiediStima(); },
      }, d + ' min')));
    }

    function disegnaIndicazioni() {
      el.contaInd.textContent = st.indicazioni.length + ' / ' + L.MASSIMO_INDICAZIONI;
      if (!schema) return;
      svuota(el.spunti, L.SPUNTI_SCHEMA.map((s) => {
        const dentro = st.indicazioni.includes(s.frase);
        return h('button', {
          type: 'button', class: 'nl-pillola', 'aria-pressed': dentro ? 'true' : 'false', 'data-spunto': s.etichetta,
          'data-visita': s.etichetta === 'Tante immagini' ? 'spunto-immagini' : null,
          onclick: () => {
            const r = L.alternaSpunto(st.indicazioni, s.frase, L.MASSIMO_INDICAZIONI);
            if (!r.ok) { A.avviso('Le indicazioni sono già lunghe: togli qualcosa prima.'); return; }
            st.indicazioni = r.testo;
            el.indicazioni.value = r.testo;
            disegnaIndicazioni();
            disegnaRiepilogo();
            chiediStima();
          },
        }, icona(dentro ? 'spunta' : 'piu'), s.etichetta);
      }));
    }

    function disegnaRiepilogo() {
      if (st.chiusa) return;
      const scelti = spuntati();
      const titoli = scelti.map((id) => { const x = st.materiale.find((y) => y.id === id); return x ? x.titolo || x.nome_file : ''; });
      const arg = st.argomento.trim();
      const s = st.stima;
      const riga = (k, v, classe) => h('div', { class: 'nl-riga-riep' }, h('span', { class: 'k' }, k), h('span', { class: 'v' + (classe ? ' ' + classe : ''), 'data-riep': k.toLowerCase() }, v));

      let mese = null;
      const spesa = s ? { speso: Number(s.spesa_mese_eur || 0), tetto: Number(s.tetto_eur || 2) } : S.spesa ? { speso: Number(S.spesa.speso_eur || 0), tetto: Number(S.spesa.tetto_eur || 2) } : null;
      if (spesa) {
        const questa = s ? Number(s.costo_eur || 0) : 0;
        const dopo = Math.round((spesa.speso + questa) * 100) / 100;
        const b = L.barraMese(spesa.speso, questa, spesa.tetto);
        const inAvviso = dopo >= 0.8 * spesa.tetto;
        mese = h('div', { class: 'nl-mese' + (inAvviso ? ' avviso' : ''), 'data-mese': 'si' },
          h('div', { class: 'righe' }, h('span', null, 'Questo mese ' + L.euro(spesa.speso)), h('span', null, s ? 'dopo questo ' + L.euro(dopo) + ' di ' + L.euroTondo(spesa.tetto) : 'di ' + L.euroTondo(spesa.tetto))),
          h('div', { class: 'barra spessa due' }, h('span', { stile: { width: b.speso + '%' } }), h('span', { class: 'questa', stile: { width: b.questa + '%' } })),
          s && Number(s.impegnato_eur) > 0 ? h('span', { class: 'secondario' }, 'di cui ' + L.euro(s.impegnato_eur) + ' per lavori già partiti') : null,
          inAvviso && s ? h('span', { class: 'secondario' }, L.siAzzera(s.si_azzera_il)) : null);
      }

      const consentita = s ? s.consentita !== false : false;
      const prezzo = s && scelti.length && arg ? ' · ' + L.euroCosto(s.costo_eur) : '';
      const testoPulsante = st.invio ? G.chiedo : G.prepara + prezzo;
      const fai = { type: 'button', onclick: prepara };
      const spento = st.invio || !arg || !scelti.length || !s || !consentita || S.senzaRete;
      const pulsante = h('button', Object.assign({ class: 'pulsante pieno largo nl-prepara', 'data-azione': 'prepara-studio' }, fai), testoPulsante);
      pulsante.disabled = spento;

      const sotto = [];
      if (st.errore) sotto.push(A.messaggio('errore', st.errore));
      else if (st.erroreStima && scelti.length) sotto.push(A.messaggio('errore', st.erroreStima, [h('button', { type: 'button', class: 'pulsante bianco', onclick: chiediStima }, 'Riprova')]));
      else if (s && !consentita && scelti.length) sotto.push(A.messaggio('avviso', s.motivo || 'Sforerebbe il tetto del mese.'));
      if (S.senzaRete) sotto.push(A.messaggio('avviso', G.senzaRete));

      const forma = L.FORME_SCHEMA.find((f) => f.forma === st.forma) || L.FORME_SCHEMA[0];
      const verifiche = Number((m.conteggi || {}).verifiche) || 0;
      const manca = !arg ? 'Manca l\'argomento' : !scelti.length ? 'Scegli almeno un materiale' : null;
      const righe = schema
        ? [riga('SU', arg || 'Manca l\'argomento', arg ? 'forte' : 'manca'),
          riga('MATERIALE', L.riepilogoMateriale(titoli), scelti.length ? null : 'manca'),
          riga('INDICAZIONI', L.riepilogoIndicazioni(st.indicazioni)),
          riga('PER', L.riepilogoData(eventoScelto())),
          s && Number(s.immagini) > 0 ? riga('IMMAGINI', 'circa ' + s.immagini) : null,
          s && s.minuti_preparazione != null ? riga(G.parolaPronto, 'in circa ' + s.minuti_preparazione + ' min') : null]
        : [riga('SU', arg || 'Manca l\'argomento', arg ? 'forte' : 'manca'),
          riga('MATERIALE', L.riepilogoMateriale(titoli), scelti.length ? null : 'manca'),
          riga('INDICAZIONI', L.riepilogoIndicazioni(st.indicazioni)),
          riga('PER', L.riepilogoData(eventoScelto())),
          verifiche ? riga('FORMATO', verifiche === 1 ? 'Come la tua verifica' : 'Come le tue ' + verifiche + ' verifiche') : riga('FORMATO', 'Nessuna verifica caricata: uso un formato normale', 'manca'),
          s && s.minuti_preparazione != null ? riga(G.parolaPronto, 'in circa ' + s.minuti_preparazione + ' min') : null];

      svuota(el.riepilogo,
        h('span', { class: 'etichetta' }, G.etichetta),
        h('div', { stile: { display: 'flex', flexDirection: 'column', gap: '4px' } },
          h('span', { class: 'titolo', 'data-riep': 'titolo' }, schema ? 'Schema ' + L.nomeLivello(st.livello).toLowerCase() : 'Verifica di ' + st.minuti + ' minuti'),
          schema ? h('span', { class: 'struttura', 'data-riep': 'struttura' }, forma.riepilogo) : null),
        h('div', { class: 'nl-linea' }),
        h('div', { class: 'nl-righe-riep' }, righe),
        h('div', { class: 'nl-linea' }),
        mese, pulsante, h('div', { class: 'nl-sotto' }, sotto));

      const materiali = scelti.length === 1 ? '1 materiale' : scelti.length + ' materiali';
      const primaRiga = schema ? L.nomeLivello(st.livello) + ' · ' + forma.breve + ' · ' + materiali : st.minuti + ' min · ' + materiali;
      const avviso = manca || st.errore || st.erroreStima || (s && !consentita ? s.motivo || 'Sforerebbe il tetto' : null) || (S.senzaRete ? 'Senza rete' : null);
      const meseBreve = spesa ? 'mese: ' + L.euro(spesa.speso + (s ? Number(s.costo_eur || 0) : 0)) + ' di ' + L.euroTondo(spesa.tetto) : '';
      const pulsanteBarra = h('button', Object.assign({ class: 'pulsante pieno nl-prepara', 'data-azione': 'prepara-studio-barra' }, fai), testoPulsante);
      pulsanteBarra.disabled = spento;
      svuota(el.barra, h('div', { class: 'testi' }, h('span', { class: 'uno' }, primaRiga), avviso ? h('span', { class: 'due manca' }, avviso) : h('span', { class: 'due' }, meseBreve)), pulsanteBarra);
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    async function prepara() {
      if (st.invio) return;
      const ids = spuntati();
      const arg = st.argomento.trim();
      if (!ids.length || !arg) return;
      const dati = schema
        ? { materia_id: m.id, evento_id: st.eventoId, argomento: arg, materiali: ids, forma: st.forma, livello: st.livello, indicazioni: st.indicazioni.trim() }
        : { materia_id: m.id, evento_id: st.eventoId, argomento: arg, materiali: ids, minuti: st.minuti, indicazioni: st.indicazioni.trim() };
      st.invio = true;
      st.errore = null;
      disegnaRiepilogo();
      const r = await A.api.post(G.crea, dati);
      st.invio = false;
      if (st.chiusa) return;
      if (!r.ok || !r.dati) {
        st.errore = r.errore;
        disegnaRiepilogo();
        return;
      }
      if (S.motore) S.motore.in_preparazione = true;
      ctl.chiudi(true);
      A.vai(G.rotta + r.dati.id);
    }
  }

  A.nuovoSchema = (materiaId, opzioni) => finestraNuovo('schema', materiaId, opzioni);
  A.nuovaSimulata = (materiaId, opzioni) => finestraNuovo('simulata', materiaId, opzioni);

})();
