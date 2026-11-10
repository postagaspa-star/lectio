/*
 * Lectio per il computer: Nuova lezione (docs/aspetto.md §6, prototipo NuovaLezionePc.dc.html) come finestra
 * grande con il riepilogo vivo, e "In preparazione" (nella stessa finestra e nella pagina #lezione/12 aperta da un
 * avviso). Il costo si vede prima (stime dal server), il tetto non è un muro: la voce gratis è la strada.
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

  /**
   * Apre "Nuova lezione" per una materia (§6, prototipo NuovaLezionePc): quattro decisioni numerate (Su cosa, Da
   * quale materiale, Come la vuoi, Voce) e a fianco il riepilogo vivo con il costo e il pulsante. `opzioni.eventoId`
   * = la verifica già scelta. I pezzi si ridisegnano uno per uno, così i campi non perdono mai il cursore.
   */
  A.nuovaLezione = async function (materiaId, opzioni) {
    const o = opzioni || {};
    if (!S.materie) await A.caricaMaterie();
    const m = A.materiaDi(materiaId);
    if (!m) { A.avviso('Questa materia non c\'è più.'); return null; }
    const st = {
      argomento: '', eventoId: o.eventoId != null ? o.eventoId : undefined, durata: 20, saltabili: false, tipo: 'spiegazione', indicazioni: '',
      voceVoluta: null, eventi: [], materiale: [], cartelle: [], erroreMateriale: null, voci: [],
      // §16.2: finché Andrea non tocca una casella valgono i suggeriti del server (auto); null = il server non sa suggerire.
      auto: true, suggeriti: [], suggeritiArrivati: false, manuali: [], cerca: '',
      stime: {}, chiaveStima: null, erroreStima: null, invio: false, errore: null, chiusa: false,
    };
    const seqStima = L.creaSequenza();
    const seqSugg = L.creaSequenza();
    let ctl;
    const corpo = h('div', { class: 'corpo su-fondo' });
    const piede = h('div', { class: 'piede', stile: { display: 'none' } });
    ctl = A.apriFinestra({
      etichetta: 'Nuova lezione',
      classe: 'finestra-lezione ' + A.classeMateria(m.colore),
      suChiusura: () => { clearTimeout(st.timerSugg); st.chiusa = true; if (st.osserva) st.osserva.disconnect(); },
      disegna: (f) => svuota(f, corpo, piede),
    });
    ctl.tipo = 'nuova-lezione';
    svuota(corpo, testa(), A.segnaposto(5));

    function testa() {
      return h('div', { class: 'nl-testa' },
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => ctl.chiudi() }, icona('chiudi')),
        h('h1', { class: 'titolo-pagina' }, 'Nuova lezione'),
        A.pillolaMateria(m),
        A.modalitaProva() ? h('span', { class: 'pillola stato prova' }, 'Prova') : null);
    }

    const oggi = L.iso();
    const [, rm, re, rv, , rc] = await Promise.all([
      S.impostazioni ? null : A.caricaImpostazioni(),
      A.api.get('/server/api/materie/' + m.id + '/materiale'),
      A.api.get('/server/api/eventi?da=' + oggi),
      A.api.get('/server/api/voci'),
      S.salute ? null : A.caricaSalute(),
      A.cartelle.carica(m.id),
    ]);
    if (st.chiusa) return ctl;
    const imp = S.impostazioni || {};
    st.durata = L.durataVicina(imp.durata_predefinita || 20);
    st.saltabili = !!imp.domande_saltabili;
    st.materiale = rm.ok ? rm.dati || [] : [];
    // §42.7: le cartelle servono solo per le pillole e per il nome nelle righe; senza, si sceglie come prima.
    st.cartelle = rc && rc.ok ? rc.dati || [] : [];
    if (!rm.ok) st.erroreMateriale = rm.errore;
    st.eventi = re.ok ? (re.dati || []).filter((e) => e.materia_id === m.id && L.giorniFra(oggi, e.data) >= 0).sort((a, b) => a.data.localeCompare(b.data)) : [];
    st.voci = rv.ok ? rv.dati || [] : [];
    if (rv.ok) S.voci = st.voci;
    else st.erroreVoci = rv.errore;
    if (st.eventoId === undefined) st.eventoId = st.eventi.length ? st.eventi[0].id : null;
    const ev = st.eventi.find((e) => e.id === st.eventoId);
    st.argomento = ev && ev.argomento ? ev.argomento : '';
    // §33.2 "Ascolta questa parte": argomento e materiali arrivano già decisi dallo schema.
    if (o.argomento) st.argomento = o.argomento;
    if (Array.isArray(o.materiali)) {
      st.auto = false;
      st.manuali = o.materiali.filter((id) => st.materiale.some((x) => x.id === id && L.prontoDaUsare(x)));
    }
    const predefinita = st.voci.find((v) => v.id === imp.voce_predefinita && v.disponibile !== false);
    st.voceVoluta = predefinita ? predefinita.id : null;

    // --- i pezzi fissi (si creano una volta) ------------------------------------------------------
    const el = {};
    el.argomento = h('input', { class: 'nl-campo', id: 'nl-argomento', maxlength: '200', value: st.argomento, placeholder: 'Per esempio L\'Impero romano da Augusto a Nerone', 'aria-label': 'Argomento', autocomplete: 'off' });
    el.argomento.addEventListener('input', () => {
      st.argomento = el.argomento.value;
      st.errore = null;
      disegnaRiepilogo();
      // §16.2: i suggeriti si chiedono 400 ms dopo l'ultima lettera; una risposta per un testo vecchio si butta.
      seqSugg.nuova();
      clearTimeout(st.timerSugg);
      st.timerSugg = setTimeout(chiediSuggeriti, 400);
    });
    el.argomento.addEventListener('keydown', (e) => { if (e.key === 'Enter') { clearTimeout(st.timerSugg); chiediSuggeriti(); } });
    el.per = h('div', { class: 'nl-riga-per', role: 'group', 'aria-label': 'Per' });
    el.conta = h('span', { class: 'nl-conta', 'data-conta': 'materiale' });
    el.modi = h('div', { class: 'nl-modi' });
    el.cartelle = h('div', { class: 'nl-cartelle-box' });
    el.cerca = h('input', { class: 'nl-cerca', id: 'nl-cerca', type: 'search', placeholder: 'Cerca nel materiale', 'aria-label': 'Cerca nel materiale', autocomplete: 'off' });
    el.cerca.addEventListener('input', () => { st.cerca = el.cerca.value; disegnaRighe(); });
    el.studio = h('div', { class: 'nl-righe', 'data-gruppo': 'studio' });
    el.prof = h('div', { class: 'nl-righe', 'data-gruppo': 'prof' });
    el.tipi = h('div', { class: 'nl-tipi', role: 'group', 'aria-label': 'Tipo di lezione' });
    el.indicazioni = h('textarea', { class: 'nl-area', id: 'nl-indicazioni', rows: '4', maxlength: String(L.MASSIMO_INDICAZIONI), placeholder: 'Per esempio: insisti su quello che il prof chiede sempre', 'aria-label': 'Le tue indicazioni' });
    el.indicazioni.addEventListener('input', () => { st.indicazioni = el.indicazioni.value; disegnaIndicazioni(); disegnaRiepilogo(); });
    el.contaInd = h('span', { 'data-conta': 'indicazioni' });
    el.spunti = h('div', { class: 'nl-spunti' });
    el.durata = h('div', { class: 'segmenti', role: 'group', 'aria-label': 'Durata', 'data-visita': 'durata' });
    el.domande = h('div', { class: 'segmenti', role: 'group', 'aria-label': 'Domande' });
    el.domande.style.setProperty('--n', '2');
    el.voci = h('div', { class: 'nl-voci', role: 'group', 'aria-label': 'Voce' });
    el.riepilogo = h('aside', { class: 'nl-riepilogo', 'data-visita': 'riepilogo', 'aria-label': 'La tua lezione' });
    // Finestra stretta (riepilogo in fondo): una barra ferma in basso con l'essenziale e il pulsante, come sul
    // telefono (§6). Sparisce quando il riepilogo intero è sullo schermo; con la finestra larga non si vede.
    el.barra = h('div', { class: 'nl-barra via' });
    if (window.IntersectionObserver) {
      const osserva = new IntersectionObserver((voci) => { el.barra.classList.toggle('via', voci[voci.length - 1].isIntersecting); }, { root: corpo });
      osserva.observe(el.riepilogo);
      st.osserva = osserva;
    }

    const sezione = (n, titolo, destra, ...dentro) => h('section', { class: 'nl-sezione', 'data-sezione': String(n) },
      h('div', { class: 'nl-titolo' }, h('span', { class: 'nl-numero', 'aria-hidden': 'true' }, String(n)), h('h2', null, titolo), destra || null), dentro);
    const etichetta = (t) => h('span', { class: 'etichetta' }, t);

    svuota(corpo,
      testa(),
      h('div', { class: 'nl-griglia' },
        h('div', { class: 'nl-sezioni' },
          sezione(1, 'Su cosa', null,
            h('label', { class: 'campo-gruppo', for: 'nl-argomento' }, etichetta('Argomento'), el.argomento),
            el.per),
          sezione(2, 'Da quale materiale', el.conta,
            el.modi,
            el.cartelle,
            st.erroreMateriale ? A.messaggio('errore', st.erroreMateriale) : null,
            etichetta('Appunti e libro'), el.studio,
            h('span', { class: 'etichetta nl-gruppo' }, 'Verifiche e interrogazioni del prof'), el.prof),
          sezione(3, 'Come la vuoi', null,
            el.tipi,
            h('label', { class: 'campo-gruppo', for: 'nl-indicazioni' }, h('span', { class: 'etichetta nl-etichetta-riga' }, h('span', null, 'Le tue indicazioni'), el.contaInd), el.indicazioni),
            el.spunti,
            h('div', { class: 'nl-due' },
              h('div', null, etichetta('Durata'), el.durata),
              h('div', null, etichetta('Domande'), el.domande))),
          sezione(4, 'Voce', null, el.voci)),
        el.riepilogo),
      el.barra);

    disegnaPer();
    disegnaMateriale();
    disegnaTipi();
    disegnaIndicazioni();
    disegnaDurata();
    disegnaDomande();
    disegnaVoci();
    disegnaRiepilogo();
    setTimeout(() => { if (!st.chiusa && !st.argomento && !S.visita) el.argomento.focus(); }, 40);
    if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    // Si parte coi suggeriti dell'argomento (se c'è); la stima arriva dopo, col materiale spuntato.
    if (st.argomento.trim()) chiediSuggeriti();
    else dopoCambioMateriale();
    return ctl;

    // --- materiale: suggeriti, caselle, stima ------------------------------------------------------

    function pronti() {
      return st.materiale.filter(L.prontoDaUsare);
    }

    function spuntati() {
      return L.materialiSpuntati(st.materiale, st.auto, st.suggeriti, st.manuali);
    }

    async function chiediSuggeriti() {
      clearTimeout(st.timerSugg);
      const n = seqSugg.nuova();
      const arg = st.argomento.trim();
      if (!arg || o.materiali) {
        st.suggeriti = [];
        dopoCambioMateriale();
        return;
      }
      const r = await A.api.get('/server/api/materie/' + m.id + '/suggeriti?argomento=' + encodeURIComponent(arg));
      if (!seqSugg.valida(n) || st.chiusa) return;
      if (r.ok && r.dati && Array.isArray(r.dati.suggeriti)) {
        st.suggeriti = r.dati.suggeriti.map(Number);
        st.suggeritiArrivati = true;
      } else if (!st.suggeritiArrivati) {
        // Un server che non sa ancora suggerire: si parte da tutto il materiale (come prima).
        st.suggeriti = null;
      }
      dopoCambioMateriale();
    }

    function dopoCambioMateriale() {
      disegnaMateriale();
      disegnaRiepilogo();
      const chiave = st.durata + '|' + spuntati().join(',');
      if (chiave !== st.chiaveStima) stima();
    }

    function tocca(id) {
      const ora = spuntati();
      st.auto = false;
      st.manuali = ora.includes(id) ? ora.filter((x) => x !== id) : ora.concat([id]);
      dopoCambioMateriale();
    }

    /** §42.7: una pillola cartella spunta o toglie tutto il suo materiale pronto; conta come un tocco di Andrea. */
    function toccaCartella(ids) {
      const ora = spuntati();
      st.auto = false;
      st.manuali = L.alternaCartella(ora, ids);
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

    /** Le voci che si stimano: una per ogni voce espressiva e per ogni voce del NAS (§16.5: tempi diversi),
     *  una sola per le altre gratis e per quella di prova. */
    function chiaveVoce(v) {
      return v.tipo === 'espressiva' || v.minuti_per_minuto != null ? v.id : v.tipo;
    }

    function vociDisponibili() {
      const tutte = st.voci.filter((v) => v.disponibile !== false);
      const prova = tutte.filter((v) => v.tipo === 'prova');
      return prova.length ? prova : tutte;
    }

    async function stima() {
      const ids = spuntati();
      st.chiaveStima = st.durata + '|' + ids.join(',');
      const n = seqStima.nuova();
      // Con niente spuntato non si chiede: il server direbbe di no (422) e il pulsante è già spento.
      if (!ids.length) {
        st.stime = {};
        st.erroreStima = null;
        disegnaVoci();
        disegnaRiepilogo();
        return;
      }
      const gruppi = [];
      for (const v of vociDisponibili()) if (!gruppi.some((g) => g.chiave === chiaveVoce(v))) gruppi.push({ chiave: chiaveVoce(v), voce: v });
      const risposte = await Promise.all(gruppi.map((g) => A.api.post('/server/api/lezioni/stima', {
        materia_id: m.id, durata_min: st.durata, voce: g.voce.id, materiali: ids, tipo: st.tipo, indicazioni: st.indicazioni.trim(),
      })));
      if (!seqStima.valida(n) || st.chiusa) return; // una stima partita prima e arrivata dopo: si butta
      const nuove = {};
      gruppi.forEach((g, i) => { if (risposte[i].ok && risposte[i].dati) nuove[g.chiave] = risposte[i].dati; });
      st.stime = nuove;
      const r0 = risposte.find((r) => !r.ok);
      st.erroreStima = r0 && !Object.keys(nuove).length ? r0.errore : null;
      disegnaVoci();
      disegnaRiepilogo();
    }

    function stimaDi(v) {
      return v ? st.stime[chiaveVoce(v)] || null : null;
    }

    function tetto() {
      const s = Object.values(st.stime)[0];
      return s && s.tetto_eur != null ? Number(s.tetto_eur) : imp.tetto_eur != null ? Number(imp.tetto_eur) : 2;
    }

    /** Si può scegliere: disponibile e, se la stima c'è, consentita (il tetto non è un muro: c'è la voce gratis). */
    function sceglibile(v) {
      const s = stimaDi(v);
      return !s || s.consentita !== false;
    }

    /** La voce scelta davvero: quella voluta se si può, poi la predefinita, poi una gratis, poi una qualsiasi. */
    function voceScelta() {
      const disp = vociDisponibili();
      const ok = disp.filter(sceglibile);
      const voluta = ok.find((v) => v.id === st.voceVoluta);
      if (voluta) return voluta;
      if (disp.some((v) => v.id === st.voceVoluta)) return ok.find((v) => v.tipo === 'gratis') || ok[0] || disp.find((v) => v.id === st.voceVoluta);
      return ok.find((v) => v.tipo === 'espressiva') || ok[0] || disp[0] || null;
    }

    // --- disegno, pezzo per pezzo -------------------------------------------------------------------

    function pillola(testo, premuta, fai, extra) {
      return h('button', Object.assign({ type: 'button', class: 'nl-pillola', 'aria-pressed': premuta ? 'true' : 'false', onclick: fai }, extra || {}), testo);
    }

    function eventoScelto() {
      return st.eventi.find((e) => e.id === st.eventoId) || null;
    }

    function disegnaPer() {
      // §6: "Nessuna data" e la prossima verifica o interrogazione della materia (più quella già scelta, se è un'altra).
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
      svuota(el.cartelle, A.pilloleCartelleScelta(st.materiale, st.cartelle, spuntati(), toccaCartella));
      disegnaRighe();
    }

    function disegnaRighe() {
      const scelti = spuntati();
      const sugg = st.suggeriti || [];
      const g = L.gruppiMateriale(st.materiale);
      const riga = (x) => {
        const pronto = L.prontoDaUsare(x);
        const si = scelti.includes(x.id);
        return h('button', {
          type: 'button', class: 'nl-mat', role: 'checkbox', 'aria-checked': si ? 'true' : 'false', 'data-scelta': String(x.id), disabled: pronto ? null : true,
          title: x.titolo || null, onclick: () => tocca(x.id),
        },
        h('span', { class: 'nl-casella', 'aria-hidden': 'true' }, icona('spunta')),
        h('span', { class: 'testi' }, h('span', { class: 't' }, x.titolo || x.nome_file || 'Materiale'),
          pronto ? h('span', { class: 's' }, L.sottoMateriale(x, st.cartelle)) : h('span', { class: 's leggo' }, 'Lo sto leggendo')),
        pronto && sugg.includes(x.id) ? h('span', { class: 'nl-sugg' }, 'argomento') : null);
      };
      const elenco = (box, lista, vuoto) => {
        const visti = L.filtraPerTitolo(lista, st.cerca, st.cartelle);
        svuota(box, visti.length ? visti.map(riga) : h('span', { class: 'nl-vuoto' }, lista.length ? 'Nessun titolo con queste parole' : vuoto));
      };
      elenco(el.studio, g.studio, 'Nessuno ancora');
      elenco(el.prof, g.prof, 'Nessuna ancora');
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    function disegnaTipi() {
      svuota(el.tipi, L.TIPI_LEZIONE.map((t) => h('button', {
        type: 'button', class: 'nl-scheda', 'aria-pressed': st.tipo === t.tipo ? 'true' : 'false', 'data-tipo': t.tipo,
        onclick: () => { st.tipo = t.tipo; disegnaTipi(); disegnaRiepilogo(); },
      }, h('span', { class: 'n' }, t.nome), h('span', { class: 'f' }, t.frase))));
    }

    function disegnaIndicazioni() {
      el.contaInd.textContent = st.indicazioni.length + ' / ' + L.MASSIMO_INDICAZIONI;
      svuota(el.spunti, L.SPUNTI.map((s) => {
        const dentro = st.indicazioni.includes(s.frase);
        return h('button', {
          type: 'button', class: 'nl-pillola', 'aria-pressed': dentro ? 'true' : 'false', 'data-spunto': s.etichetta,
          onclick: () => {
            const r = L.alternaSpunto(st.indicazioni, s.frase, L.MASSIMO_INDICAZIONI);
            if (!r.ok) { A.avviso('Le indicazioni sono già lunghe: togli qualcosa prima.'); return; }
            st.indicazioni = r.testo;
            el.indicazioni.value = r.testo;
            disegnaIndicazioni();
            disegnaRiepilogo();
          },
        }, icona(dentro ? 'spunta' : 'piu'), s.etichetta);
      }));
    }

    function disegnaDurata() {
      svuota(el.durata, L.DURATE.map((d) => h('button', {
        type: 'button', 'aria-pressed': st.durata === d ? 'true' : 'false', 'data-durata': String(d),
        onclick: () => { if (st.durata === d) return; st.durata = d; disegnaDurata(); disegnaRiepilogo(); stima(); },
      }, d + ' min')));
    }

    function disegnaDomande() {
      svuota(el.domande, [[false, 'Obbligatorie'], [true, 'Si possono saltare']].map(([v, n]) => h('button', {
        type: 'button', 'aria-pressed': st.saltabili === v ? 'true' : 'false', 'data-saltabili': String(v),
        onclick: () => { st.saltabili = v; disegnaDomande(); disegnaRiepilogo(); },
      }, n)));
    }

    function disegnaVoci() {
      const scelta = voceScelta();
      const prova = st.voci.some((v) => v.tipo === 'prova' && v.disponibile !== false);
      const lista = prova ? vociDisponibili() : st.voci;
      if (!lista.length) {
        svuota(el.voci, st.erroreVoci ? A.messaggio('errore', st.erroreVoci) : h('span', { class: 'nl-vuoto' }, 'Nessuna voce ancora'));
        return;
      }
      svuota(el.voci, lista.map((v) => {
        if (v.disponibile === false) {
          return h('button', { type: 'button', class: 'nl-scheda spenta', disabled: true, 'data-voce': v.tipo, 'data-voce-id': v.id },
            h('span', { class: 'n' }, v.tipo === 'gratis' && v.fornitore !== 'nas' ? 'Voce gratis' : v.nome), h('span', { class: 'f' }, 'Non ancora attiva'));
        }
        const s = stimaDi(v);
        const spenta = !sceglibile(v);
        let sotto;
        if (v.tipo === 'prova') sotto = 'Voce di prova, robotica: serve la chiave Mistral per quella vera · 0 €';
        else {
          const nome = v.tipo === 'gratis' ? 'voce gratis' : 'voce espressiva';
          const costo = s ? L.euroCosto(s.costo_eur) : null;
          // §16.5: le voci del NAS non costano niente in più ma sono più lente: si dice quanto.
          const minuti = v.minuti_per_minuto != null ? L.prontaIn(v, st.durata, s) : null;
          sotto = nome + (costo ? ' · ' + costo : '') + (minuti != null ? ' · pronta in circa ' + minuti + ' min' : '');
        }
        return h('button', {
          type: 'button', class: 'nl-scheda', 'aria-pressed': scelta && scelta.id === v.id ? 'true' : 'false', disabled: spenta ? true : null,
          'data-voce': v.tipo, 'data-voce-id': v.id, onclick: () => { st.voceVoluta = v.id; disegnaVoci(); disegnaRiepilogo(); },
        },
        h('span', { class: 'n' }, v.nome), h('span', { class: 'f', 'data-costo': v.tipo }, sotto),
        spenta ? h('span', { class: 'motivo' }, 'Sforerebbe il tetto di ' + L.euroTondo(tetto())) : null);
      }));
    }

    function disegnaRiepilogo() {
      if (st.chiusa) return;
      const scelti = spuntati();
      const titoli = scelti.map((id) => { const x = st.materiale.find((y) => y.id === id); return x ? x.titolo || x.nome_file : ''; });
      const arg = st.argomento.trim();
      const voce = voceScelta();
      const s = stimaDi(voce);
      const qualunque = s || Object.values(st.stime)[0] || null;
      const riga = (k, v, classe) => h('div', { class: 'nl-riga-riep' }, h('span', { class: 'k' }, k), h('span', { class: 'v' + (classe ? ' ' + classe : ''), 'data-riep': k.toLowerCase() }, v));

      // La barra del mese a due toni: speso (con i lavori già partiti) e questa lezione.
      let mese = null;
      const spesa = qualunque ? { speso: Number(qualunque.spesa_mese_eur || 0), tetto: Number(qualunque.tetto_eur || 2) } : S.spesa ? { speso: Number(S.spesa.speso_eur || 0), tetto: Number(S.spesa.tetto_eur || 2) } : null;
      if (spesa) {
        const questa = s ? Number(s.costo_eur || 0) : 0;
        const dopo = Math.round((spesa.speso + questa) * 100) / 100;
        const b = L.barraMese(spesa.speso, questa, spesa.tetto);
        const avviso = dopo >= 0.8 * spesa.tetto;
        mese = h('div', { class: 'nl-mese' + (avviso ? ' avviso' : ''), 'data-mese': 'si' },
          h('div', { class: 'righe' }, h('span', null, 'Questo mese ' + L.euro(spesa.speso)), h('span', null, s ? 'dopo questa ' + L.euro(dopo) + ' di ' + L.euroTondo(spesa.tetto) : 'di ' + L.euroTondo(spesa.tetto))),
          h('div', { class: 'barra spessa due' }, h('span', { stile: { width: b.speso + '%' } }), h('span', { class: 'questa', stile: { width: b.questa + '%' } })),
          // §15.5: la spesa del mese comprende i lavori già partiti (lezioni in coda, letture): si dice quanto.
          qualunque && Number(qualunque.impegnato_eur) > 0 ? h('span', { class: 'secondario', 'data-impegnato': 'si' }, 'di cui ' + L.euro(qualunque.impegnato_eur) + ' per lavori già partiti') : null,
          avviso && qualunque ? h('span', { class: 'secondario' }, L.siAzzera(qualunque.si_azzera_il)) : null);
      }

      const costo = s ? Number(s.costo_eur || 0) : null;
      const consentita = s ? s.consentita !== false : false;
      const pulsante = h('button', { type: 'button', class: 'pulsante pieno largo nl-prepara', 'data-azione': 'prepara', 'data-visita': 'prepara', onclick: prepara },
        st.invio ? 'Chiedo la lezione…' : L.etichettaPrepara(scelti.length && arg ? costo : null));
      pulsante.disabled = st.invio || !arg || !scelti.length || !voce || !s || !consentita || S.senzaRete;

      // Sotto il pulsante: la frase del server (errore, tetto) o perché non si può.
      const sotto = [];
      if (st.errore) sotto.push(A.messaggio('errore', st.errore));
      else if (st.erroreStima && scelti.length) sotto.push(A.messaggio('errore', st.erroreStima, [h('button', { type: 'button', class: 'pulsante bianco', onclick: stima }, 'Riprova')]));
      else if (s && !consentita && scelti.length) sotto.push(A.messaggio('avviso', s.motivo || 'Sforerebbe il tetto di ' + L.euroTondo(spesa ? spesa.tetto : 2) + '.'));
      else if (!voce && st.voci.length && !S.senzaRete) sotto.push(A.messaggio('avviso', 'Nessuna voce è attiva sul server di casa: per ora la lezione non si può preparare.'));
      if (S.senzaRete) sotto.push(A.messaggio('avviso', 'Senza rete non si può preparare una lezione.'));

      const per = L.riepilogoPer(eventoScelto(), st.saltabili);
      svuota(el.riepilogo,
        h('span', { class: 'etichetta' }, 'La tua lezione'),
        h('div', { stile: { display: 'flex', flexDirection: 'column', gap: '4px' } },
          h('span', { class: 'titolo', 'data-riep': 'titolo' }, L.titoloLezione(st.tipo, st.durata)),
          h('span', { class: 'struttura', 'data-riep': 'struttura' }, L.struttura(st.tipo, st.durata))),
        h('div', { class: 'nl-linea' }),
        h('div', { class: 'nl-righe-riep' },
          riga('SU', arg || 'Manca l\'argomento', arg ? 'forte' : 'manca'),
          riga('MATERIALE', L.riepilogoMateriale(titoli), scelti.length ? null : 'manca'),
          riga('INDICAZIONI', L.riepilogoIndicazioni(st.indicazioni)),
          riga('PER', per),
          s && s.minuti_preparazione != null ? riga('PRONTA', 'in circa ' + L.prontaIn(voce, st.durata, s) + ' min') : null),
        h('div', { class: 'nl-linea' }),
        mese,
        pulsante,
        h('div', { class: 'nl-sotto' }, sotto));
      st.pulsante = pulsante;

      // La barra della finestra stretta: "Ripasso · 20 min · 3 materiali", sotto cosa manca (o l'avviso) oppure il
      // mese, e lo stesso pulsante.
      const tipo = L.TIPI_LEZIONE.find((t) => t.tipo === st.tipo) || L.TIPI_LEZIONE[0];
      const manca = !arg ? 'Manca l\'argomento' : !scelti.length ? 'Scegli almeno un materiale'
        : st.errore || (st.erroreStima ? st.erroreStima : null) || (s && !consentita ? s.motivo || 'Sforerebbe il tetto' : null)
          || (!voce && st.voci.length ? 'Nessuna voce è attiva' : null) || (S.senzaRete ? 'Senza rete' : null);
      const meseBreve = spesa ? (s ? 'dopo questa ' + L.euro(Math.round((spesa.speso + Number(s.costo_eur || 0)) * 100) / 100) : 'questo mese ' + L.euro(spesa.speso)) + ' di ' + L.euroTondo(spesa.tetto) : '';
      const pulsanteBarra = h('button', { type: 'button', class: 'pulsante pieno nl-prepara', 'data-azione': 'prepara-barra', onclick: prepara }, pulsante.textContent);
      pulsanteBarra.disabled = pulsante.disabled;
      svuota(el.barra,
        h('div', { class: 'testi' },
          h('span', { class: 'uno' }, tipo.nome + ' · ' + st.durata + ' min · ' + (scelti.length === 1 ? '1 materiale' : scelti.length + ' materiali')),
          manca ? h('span', { class: 'due manca' }, manca) : h('span', { class: 'due' }, meseBreve)),
        pulsanteBarra);
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    async function prepara() {
      if (st.invio) return;
      const voce = voceScelta();
      const ids = spuntati();
      const arg = st.argomento.trim();
      if (!voce || !ids.length || !arg) return;
      // §16: materiali sempre (solo quelli pronti e spuntati), tipo e indicazioni insieme ai campi di prima.
      const corpoRichiesta = {
        materia_id: m.id, evento_id: st.eventoId, argomento: arg, durata_min: st.durata, voce: voce.id, domande_saltabili: st.saltabili,
        tipo: st.tipo, indicazioni: st.indicazioni.trim(), materiali: ids,
      };
      st.invio = true;
      st.errore = null;
      disegnaRiepilogo();
      const r = await A.api.post('/server/api/lezioni', corpoRichiesta);
      st.invio = false;
      if (st.chiusa) return;
      if (!r.ok) {
        st.errore = r.errore;
        if (r.stato === 402) {
          const gratis = vociDisponibili().find((v) => v.tipo === 'gratis');
          if (gratis) st.voceVoluta = gratis.id;
          stima();
        }
        disegnaVoci();
        disegnaRiepilogo();
        return;
      }
      if (S.motore) S.motore.in_preparazione = true;
      piede.style.display = '';
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
