/*
 * Lectio per il computer: le cartelle dentro la materia (docs/aspetto.md §42, contratto §27).
 * Un livello solo (materia → cartelle → file), solo per il materiale. Righe nella pagina della materia, la pagina della
 * cartella, Nuova cartella e Rinomina, "Metti dei file qui", Elimina (si sceglie ogni volta se tenere i file), le
 * pillole per scegliere la cartella di un file e quelle della scelta del materiale.
 * Qui non si decide niente sul server: dove va un file nuovo senza cartella lo decide Lectio sul server (§27.1).
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  // --- Le cartelle di ogni materia, tenute per i fogli (Aggiungi, Un materiale) ----------------------------------

  S.cartelle = S.cartelle || {};
  A.cartelle = {
    /** Le cartelle già note di una materia (null se non sono ancora arrivate). */
    lista(materiaId) {
      const x = S.cartelle[materiaId];
      return Array.isArray(x) ? x : null;
    },
    /** GET /api/materie/{id}/cartelle: tiene la lista e la consegna come { ok, stato, dati, errore }. */
    async carica(materiaId) {
      const r = await A.api.get('/server/api/materie/' + materiaId + '/cartelle');
      if (r.ok && Array.isArray(r.dati)) {
        S.cartelle[materiaId] = r.dati;
        return r;
      }
      // Un server senza cartelle (404): nessuna cartella.
      if (r.stato === 404) S.cartelle[materiaId] = [];
      return Object.assign({}, r, { dati: r.ok ? [] : r.dati });
    },
  };

  // --- Pezzi ------------------------------------------------------------------------------------------------------

  /**
   * Le pillole per scegliere la cartella di un file (§42.6): prima `primo` ("Decide Lectio" o "Fuori", valore null),
   * poi una per cartella. Vanno a capo; quella scelta ha bordo accento e fondo accento tenue.
   */
  A.pilloleCartella = function (cartelle, scelta, primo, alCambio) {
    const box = h('div', { class: 'pillole-cartella', role: 'radiogroup', 'aria-label': 'Cartella' });
    const pillola = (testo, valore, dato) => h('button', {
      type: 'button', class: 'nl-pillola', role: 'radio', 'aria-checked': valore === scelta ? 'true' : 'false', 'aria-pressed': valore === scelta ? 'true' : 'false',
      'data-cartella-scelta': dato, onclick: () => { if (alCambio) alCambio(valore); },
    }, testo);
    svuota(box, pillola(primo, null, primo === 'Fuori' ? 'fuori' : 'lectio'), (cartelle || []).map((c) => pillola(c.nome, c.id, String(c.id))));
    return box;
  };

  /**
   * CARTELLE nella scelta del materiale (§42.7): una pillola "Arte greca · 8" per cartella con materiale pronto.
   * Scelta quando tutto il suo materiale pronto è spuntato; `tocca(ids)` decide cosa fare. null se non ce ne sono.
   */
  A.pilloleCartelleScelta = function (materiale, cartelle, spuntati, tocca) {
    const gruppi = L.cartelleConPronti(materiale, cartelle);
    if (!gruppi.length) return null;
    return h('div', { class: 'nl-cartelle', 'data-cartelle': 'scelta' },
      h('span', { class: 'etichetta' }, 'Cartelle'),
      h('div', { class: 'nl-spunti pillole-cartella' }, gruppi.map((g) => h('button', {
        type: 'button', class: 'nl-pillola', 'aria-pressed': L.cartellaTuttaScelta(spuntati, g.ids) ? 'true' : 'false', 'data-cartella-pillola': String(g.id),
        onclick: () => tocca(g.ids),
      }, g.nome + ' · ' + g.ids.length))));
  };

  /** Una riga cartella nella pagina della materia (§42.1). */
  A.rigaCartella = function (c, m) {
    const messi = L.messiDaLectio(c.messi_da_lectio);
    return h('button', { type: 'button', class: 'riga riga-cartella', 'data-cartella': String(c.id), onclick: () => A.vai(rottaCartella(m.id, c.id)) },
      icona('cartella', 'icona-tipo'),
      h('span', { class: 'testi' },
        h('span', { class: 'forte nome-cartella', title: c.nome }, c.nome),
        h('span', { class: 'secondario' }, L.contaFile(c.file), messi ? h('span', { class: 'messi-lectio', 'data-messi': String(c.messi_da_lectio) }, ' · ' + messi) : null)),
      icona('freccia', 'freccia-riga'));
  };

  /** La riga "Nuova cartella" (§42.1): c'è sempre, anche senza materiale. */
  A.rigaNuovaCartella = function (m) {
    return h('button', { type: 'button', class: 'riga riga-nuova-cartella', 'data-azione': 'nuova-cartella', disabled: S.senzaRete ? true : null, onclick: () => A.apriNomeCartella(m, null) },
      icona('piu'), h('span', null, 'Nuova cartella'));
  };

  const rottaCartella = (materiaId, cartellaId) => '#materia/' + materiaId + '/cartella/' + cartellaId;

  /** Torna alla materia: indietro nella storia se si veniva da lì, altrimenti la si apre. */
  function tornaAllaMateria(materiaId) {
    const chiave = '#materia/' + materiaId;
    if (S.storia.length > 1 && S.storia[S.storia.length - 2] === chiave) history.back();
    else A.vai(chiave);
  }

  // --- Pagina della cartella (§42.2) -------------------------------------------------------------------------------

  A.pagine.cartella = async function (box, rotta, vivo) {
    const id = rotta.id;
    const cartellaId = rotta.cartella;
    let m = A.materiaDi(id);
    if (!m) {
      svuota(box, h('div', { class: 'pagina' }, A.segnaposto(3)));
      await A.caricaMaterie();
      if (!vivo()) return;
      m = A.materiaDi(id);
      if (!m) { svuota(box, h('div', { class: 'pagina' }, A.messaggio('errore', 'Questa materia non c\'è più.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: () => A.vai('#materie') }, 'Apri le materie')]))); return; }
    }
    const cm = A.classeMateria(m.colore);
    const nome = h('h1', { 'data-nome': 'cartella' }, (A.cartelle.lista(id) || []).filter((c) => c.id === cartellaId).map((c) => c.nome)[0] || '');
    const sotto = h('span', { class: 'sotto', 'data-sotto': 'cartella' }, m.nome);
    const st = { cartella: null, lista: [], dentro: [], cartelle: [] };
    const menu = A.menuPuntini(() => [
      { testo: 'Rinomina', azione: 'rinomina-cartella', spenta: S.senzaRete || !st.cartella, fai: () => A.apriNomeCartella(m, st.cartella) },
      { testo: 'Elimina cartella', azione: 'elimina-cartella', pericolo: true, spenta: S.senzaRete || !st.cartella, fai: () => eliminaCartella(m, st.cartella) },
    ], { etichetta: 'Altre azioni della cartella' });
    const testata = h('div', { class: 'testata-materia testata-cartella ' + cm },
      h('div', { class: 'dentro' },
        h('div', { class: 'comandi-cartella' }, A.frecciaIndietro(() => tornaAllaMateria(id)), h('span', { class: 'spazio' }), menu),
        h('div', { class: 'nomi' }, h('div', { class: 'nome-con-icona' }, icona('cartella', 'icona-cartella'), nome), sotto)));
    const aggiungi = h('button', { type: 'button', class: 'pulsante tenue piccolo', 'data-azione': 'aggiungi-in-cartella', onclick: () => A.apriAggiungi(A.materiaDi(id) || m, { cartellaId }) }, 'Aggiungi');
    const elenco = h('div', { class: 'file-cartella' }, h('div', { class: 'elenco ' + cm }, h('div', { class: 'riga' }, h('div', { class: 'segnaposto riga-finta' }))));
    const metti = h('button', { type: 'button', class: 'pulsante pieno medio', 'data-azione': 'metti-qui', disabled: true, onclick: () => apriMettiQui(m, st.cartella, st.lista, st.cartelle) }, icona('piu'), 'Metti dei file qui');
    const pag = h('div', { class: 'pagina' },
      h('div', { class: 'sezione' }, h('div', { class: 'testa-sezione' }, h('h2', { class: 'titolo-sezione' }, 'File'), aggiungi), elenco),
      h('div', { class: 'fondo-fisso' }, metti));
    svuota(box, testata, pag);

    async function riempi() {
      const [rc, r] = await Promise.all([A.cartelle.carica(id), A.api.get('/server/api/materie/' + id + '/materiale')]);
      if (!vivo()) return;
      const errore = !rc.ok ? rc : !r.ok ? r : null;
      if (errore) {
        svuota(elenco, A.erroreConRiprova(errore, riempi));
        aggiungi.disabled = true;
        metti.disabled = true;
        return;
      }
      const c = (rc.dati || []).find((x) => x.id === cartellaId);
      if (!c) {
        svuota(box, h('div', { class: 'pagina' }, A.messaggio('errore', 'Questa cartella non c\'è più.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: () => A.vai('#materia/' + id) }, 'Apri ' + m.nome)])));
        return;
      }
      st.cartella = c;
      st.cartelle = rc.dati || [];
      st.lista = r.dati || [];
      st.dentro = L.materialeDentro(st.lista, cartellaId);
      nome.textContent = c.nome;
      sotto.textContent = m.nome + ' · ' + L.plurale(st.dentro.length, 'file', 'file');
      // Vuota: al posto della scheda una frase; il materiale di sempre, tocco = un materiale (§5.1).
      svuota(elenco, st.dentro.length
        ? h('div', { class: 'elenco ' + cm }, st.dentro.map((x) => A.rigaMateriale(x, m, riempi)))
        : h('p', { class: 'vuota-cartella' }, 'Ancora nessun file in questa cartella.'));
      aggiungi.disabled = !!S.senzaRete;
      // Spento se la materia non ha altro materiale fuori da questa cartella.
      metti.disabled = !!S.senzaRete || st.lista.length === st.dentro.length;
      if (st.dentro.some((x) => x.stato === 'in_lettura')) A.ripeti(vivo, riempi, T.lettura);
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }
    S.aggiornaPagina = riempi;
    await riempi();
  };

  // --- Nuova cartella e Rinomina (§42.3) ---------------------------------------------------------------------------

  /** `cartella` null = Nuova cartella; altrimenti Rinomina. Dopo Crea cartella si apre la pagina della nuova cartella. */
  A.apriNomeCartella = function (m, cartella) {
    const nuova = !cartella;
    const prima = nuova ? '' : cartella.nome;
    const st = { invio: false, errore: null };
    return A.apriFinestra({
      stretta: true,
      etichetta: nuova ? 'Nuova cartella' : 'Rinomina cartella',
      classe: 'finestra-cartella',
      disegna: (f, ctl) => {
        const campo = h('input', { class: 'campo campo-cartella', id: 'cartella-nome', maxlength: '40', placeholder: 'Per esempio Arte greca', value: prima, autocapitalize: 'sentences', autocomplete: 'off', spellcheck: 'false', 'data-fuoco': true });
        const errore = h('span', { class: 'errore-campo', role: 'alert', 'data-errore': 'nome' });
        const salva = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'salva-cartella' }, nuova ? 'Crea cartella' : 'Salva');
        function aggiorna() {
          const n = L.pulisciNomeCartella(campo.value);
          salva.disabled = !n || n === prima || st.invio;
          errore.textContent = st.errore || '';
        }
        async function manda() {
          const n = L.pulisciNomeCartella(campo.value);
          if (!n || n === prima || st.invio) return;
          st.invio = true;
          st.errore = null;
          aggiorna();
          const r = nuova ? await A.api.post('/server/api/materie/' + m.id + '/cartelle', { nome: n }) : await A.api.patch('/server/api/cartelle/' + cartella.id, { nome: n });
          st.invio = false;
          if (!r.ok) {
            st.errore = r.errore;
            aggiorna();
            campo.focus();
            return;
          }
          // Si apre subito la pagina della cartella nuova (così si riempie): la finestra sparisce senza animazione.
          if (nuova && r.dati && r.dati.id) { ctl.chiudi(true); A.cartelle.carica(m.id); A.vai(rottaCartella(m.id, r.dati.id)); return; }
          ctl.chiudi();
          if (S.aggiornaPagina) S.aggiornaPagina();
        }
        campo.addEventListener('input', () => { st.errore = null; aggiorna(); });
        // Invio = il pulsante.
        campo.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); manda(); } });
        salva.addEventListener('click', manda);
        svuota(f,
          h('div', { class: 'corpo bianco' },
            h('div', { class: 'testa-finestra' }, h('h2', { class: 'titolo-sezione titolo-finestra' }, nuova ? 'Nuova cartella' : 'Rinomina cartella'),
              h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => ctl.chiudi() }, icona('chiudi'))),
            h('label', { class: 'campo-gruppo', for: 'cartella-nome' }, h('span', { class: 'etichetta' }, 'Nome'), campo, errore)),
          h('div', { class: 'piede' }, salva));
        aggiorna();
        setTimeout(() => { campo.focus(); if (!nuova) campo.select(); }, 40);
      },
    });
  };

  // --- Metti dei file qui (§42.4) ----------------------------------------------------------------------------------

  /**
   * Finestra 640: tutto il materiale che non è in questa cartella, fuori dalle cartelle e in altre cartelle, con già
   * spuntati i proposti del server. "Metti qui 3 file" → POST /api/materie/{id}/sposta.
   */
  function apriMettiQui(m, c, lista, cartelle) {
    const g = L.gruppiMetti(lista, c.id);
    const tutti = g.fuori.concat(g.altre);
    const st = { scelti: [], attesa: true, cerca: '', invio: false, errore: null, chiusa: false };
    const ctl = A.apriFinestra({
      etichetta: 'Metti in ' + c.nome,
      classe: 'finestra-metti ' + A.classeMateria(m.colore),
      suChiusura: () => { st.chiusa = true; },
      disegna: (f, ctl2) => {
        const conta = h('span', { class: 'pillola tenue conta-scelti', 'data-conta': 'scelti' });
        const cerca = tutti.length > 6 ? h('input', { class: 'nl-cerca cerca-metti', type: 'search', placeholder: 'Cerca nel materiale', 'aria-label': 'Cerca nel materiale', autocomplete: 'off' }) : null;
        const righe = h('div', { class: 'gruppi-metti' });
        const esito = h('div', { class: 'esito-metti' });
        const pulsante = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'metti-qui-conferma' });
        st.disegna = () => {
          conta.textContent = st.scelti.length + (st.scelti.length === 1 ? ' scelto' : ' scelti');
          const riga = (x) => {
            const si = st.scelti.includes(x.id);
            const leggo = x.stato === 'in_lettura';
            return h('button', {
              type: 'button', class: 'nl-mat', role: 'checkbox', 'aria-checked': si ? 'true' : 'false', 'data-metti': String(x.id), disabled: st.attesa || st.invio ? true : null,
              title: x.titolo || null, onclick: () => { st.scelti = si ? st.scelti.filter((y) => y !== x.id) : st.scelti.concat([x.id]); st.disegna(); },
            },
            h('span', { class: 'nl-casella', 'aria-hidden': 'true' }, icona('spunta')),
            h('span', { class: 'testi' }, h('span', { class: 't' }, x.titolo || x.nome_file || 'Materiale'),
              leggo ? h('span', { class: 's leggo' }, 'Lo sto leggendo') : h('span', { class: 's' }, L.sottoMetti(x, cartelle))));
          };
          const gruppo = (etichetta, dati, chiave) => {
            const visti = L.filtraPerTitolo(dati, st.cerca, cartelle);
            if (!dati.length) return null;
            return h('div', { class: 'gruppo-metti', 'data-gruppo': chiave }, h('span', { class: 'etichetta' }, etichetta),
              visti.length ? h('div', { class: 'nl-righe' }, visti.map(riga)) : h('span', { class: 'nl-vuoto' }, 'Nessun titolo con queste parole'));
          };
          svuota(righe, gruppo('Fuori dalle cartelle', g.fuori, 'fuori'), gruppo('In altre cartelle', g.altre, 'altre'));
          svuota(esito, st.errore ? A.messaggio('errore', st.errore) : null);
          pulsante.textContent = st.invio ? 'Sposto i file…' : L.fraseMettiQui(st.scelti.length);
          pulsante.disabled = !st.scelti.length || st.invio || st.attesa || !!S.senzaRete;
        };
        if (cerca) cerca.addEventListener('input', () => { st.cerca = cerca.value; st.disegna(); });
        pulsante.addEventListener('click', async () => {
          if (!st.scelti.length || st.invio) return;
          st.invio = true;
          st.errore = null;
          st.disegna();
          // Nell'ordine dell'elenco, come si vedono.
          const ids = tutti.filter((x) => st.scelti.includes(x.id)).map((x) => x.id);
          const r = await A.api.post('/server/api/materie/' + m.id + '/sposta', { materiali: ids, cartella_id: c.id });
          st.invio = false;
          if (st.chiusa) return;
          if (!r.ok) { st.errore = r.errore; st.disegna(); return; }
          ctl2.chiudi();
          A.avviso(L.fraseFattoMetti(ids.length, c.nome), { dura: 4000 });
          if (S.aggiornaPagina) S.aggiornaPagina();
        });
        const vuoto = !tutti.length;
        svuota(f,
          h('div', { class: 'corpo bianco' },
            h('div', { class: 'testa-finestra' }, h('h2', { class: 'titolo-sezione titolo-finestra' }, 'Metti in ' + c.nome), vuoto ? null : conta,
              h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => ctl2.chiudi() }, icona('chiudi'))),
            vuoto ? h('p', { class: 'vuota-cartella' }, 'Tutto il materiale della materia è già qui.') : [cerca, righe, esito]),
          vuoto ? null : h('div', { class: 'piede' }, pulsante));
        if (!vuoto) st.disegna();
      },
    });
    if (!tutti.length) return ctl;
    // All'apertura si spuntano i proposti del server; finché arrivano, le caselle sono spente.
    A.api.get('/server/api/cartelle/' + c.id + '/proposti').then((r) => {
      if (st.chiusa) return;
      const proposti = r.ok && r.dati && Array.isArray(r.dati.proposti) ? r.dati.proposti.map(Number) : [];
      st.scelti = tutti.filter((x) => proposti.includes(x.id)).map((x) => x.id);
      st.attesa = false;
      st.disegna();
    });
    return ctl;
  }
  A.apriMettiQui = apriMettiQui;

  // --- Eliminare una cartella (§42.5) ------------------------------------------------------------------------------

  async function elimina(m, c, conIFile) {
    const r = await A.api.elimina('/server/api/cartelle/' + c.id, { conferma: true, con_i_file: !!conIFile });
    if (!r.ok) return r;
    A.cartelle.carica(m.id);
    tornaAllaMateria(m.id);
    A.avviso(L.fraseCartellaEliminata(conIFile ? (r.dati && r.dati.file_eliminati != null ? r.dati.file_eliminati : c.file) : 0), { dura: 4000 });
    return r;
  }

  /** Vuota: conferma semplice. Con file: tre pulsanti, e solo "Elimina anche gli N file" è rosso. */
  async function eliminaCartella(m, c) {
    if (!c) return;
    const titolo = 'Eliminare la cartella «' + c.nome + '»?';
    const n = Number(c.file) || 0;
    if (!n) {
      if (!(await A.conferma({ titolo, testo: 'È vuota.', si: 'Elimina', pericolo: true }))) return;
      const r = await elimina(m, c, false);
      if (!r.ok) A.avviso(r.errore);
      return;
    }
    A.apriFinestra({
      stretta: true,
      etichetta: titolo,
      classe: 'finestra-elimina-cartella',
      disegna: (f, ctl) => {
        const esito = h('div');
        let invio = false;
        const scegli = async (conIFile) => {
          if (invio) return;
          invio = true;
          f.querySelectorAll('button').forEach((b) => { b.disabled = true; });
          const r = await elimina(m, c, conIFile);
          if (r.ok) { ctl.chiudi(); return; }
          invio = false;
          f.querySelectorAll('button').forEach((b) => { b.disabled = false; });
          svuota(esito, A.messaggio('errore', r.errore));
        };
        svuota(f, h('div', { class: 'corpo bianco elimina-cartella' },
          h('h2', { class: 'titolo-sezione titolo-finestra' }, titolo),
          h('p', { class: 'secondario testo-elimina' }, L.fraseDentro(n)),
          h('div', { class: 'scelte-elimina' },
            h('div', { class: 'scelta-elimina' },
              h('button', { type: 'button', class: 'pulsante tenue alto largo', 'data-azione': 'elimina-solo-cartella', 'data-fuoco': true, onclick: () => scegli(false) }, 'Elimina solo la cartella'),
              h('span', { class: 'nota-elimina' }, 'I file restano nella materia')),
            h('button', { type: 'button', class: 'pulsante rosso-tenue largo', 'data-azione': 'elimina-con-file', onclick: () => scegli(true) }, L.fraseEliminaConFile(n)),
            h('button', { type: 'button', class: 'pulsante testo largo', 'data-azione': 'annulla', onclick: () => ctl.chiudi() }, 'Annulla')),
          esito));
        setTimeout(() => { const b = f.querySelector('[data-fuoco]'); if (b) b.focus(); }, 30);
      },
    });
  }
  A.eliminaCartella = eliminaCartella;
})();
