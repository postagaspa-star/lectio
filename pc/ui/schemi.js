/*
 * Lectio per il computer: la lettura di uno schema (docs/aspetto.md §33, contratto §18).
 * Si legge come un libro: colonna da 580 px, indice sempre visibile, da dove viene ogni cosa. Il testo del server entra
 * come testo; i tipi di blocco che non si conoscono si saltano.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  const chiaveParte = (id) => 'lectio.schema.parte.' + id;
  const inLavoro = (s) => s.stato === 'in_coda' || s.stato === 'in_preparazione';
  const senzaMovimento = () => !A.conMovimenti() || document.documentElement.classList.contains('foto');

  function indirizzoFoto(schemaId, n, versione) {
    if (S.esempio && window.LectioEsempio) return window.LectioEsempio.immagine(n, versione);
    return '/server/api/schemi/' + schemaId + '/immagini/' + n + (versione ? '?v=' + versione : '');
  }

  /** La foto a tutto schermo: la rotella ingrandisce, X o Esc chiudono. */
  A.apriFoto = function (indirizzo, descrizione) {
    let zoom = 1;
    const img = h('img', { src: indirizzo, alt: descrizione || '' });
    A.apriFinestra({
      classe: 'finestra-foto', etichetta: descrizione || 'Foto',
      disegna: (f, ctl) => {
        const zona = h('div', { class: 'zona' }, img);
        zona.addEventListener('wheel', (e) => {
          e.preventDefault();
          zoom = L.limita(zoom * (e.deltaY < 0 ? 1.15 : 0.87), 1, 5);
          img.style.transform = 'scale(' + zoom + ')';
        }, { passive: false });
        svuota(f, h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', 'data-fuoco': true, onclick: () => ctl.chiudi() }, icona('chiudi')), zona);
      },
    });
  };

  /** Scorre il contenuto fino a `dove` in 300 ms (§33.8); senza animazioni, subito. */
  function scorriA(contenitore, dove) {
    if (senzaMovimento()) {
      const prima = contenitore.style.scrollBehavior;
      contenitore.style.scrollBehavior = 'auto';
      contenitore.scrollTop = dove;
      contenitore.style.scrollBehavior = prima;
      return;
    }
    const da = contenitore.scrollTop;
    const inizio = performance.now();
    contenitore.style.scrollBehavior = 'auto';
    const passo = (t) => {
      const p = Math.min(1, (t - inizio) / 300);
      contenitore.scrollTop = da + (dove - da) * (1 - Math.pow(1 - p, 3));
      if (p < 1) requestAnimationFrame(passo);
      else contenitore.style.scrollBehavior = '';
    };
    requestAnimationFrame(passo);
  }

  /** "Torna dov'eri" per 5 secondi dopo un salto. */
  let tornaVia = null;
  function pillolaTorna(contenitore, posizione) {
    if (tornaVia) tornaVia();
    const box = document.getElementById('avvisi');
    if (!box) return;
    let timer = null;
    const b = h('button', { type: 'button', class: 'pulsante tenue torna-dove', 'data-azione': 'torna-dove', onclick: () => { via(); scorriA(contenitore, posizione); } }, 'Torna dov\'eri');
    function via() { clearTimeout(timer); b.remove(); tornaVia = null; }
    tornaVia = via;
    box.append(b);
    timer = setTimeout(via, T.torna);
  }

  A.pagine.schema = async function (box, rotta, vivo) {
    const id = rotta.id;
    const pag = h('div', { class: 'pagina' }, A.segnaposto(3), A.segnaposto(3), A.segnaposto(3));
    svuota(box, pag);
    let s = null;
    let m = null;

    const indietro = () => A.tornaIndietroDa(s && s.materia_id, 'schemi');
    S.aggiornaPagina = () => { if (!s || s.stato !== 'pronto') carica(); };

    async function carica() {
      const [r] = await Promise.all([A.api.get('/server/api/schemi/' + id), S.materie ? null : A.caricaMaterie(), S.salute ? null : A.caricaSalute()]);
      if (!vivo()) return;
      if (!r.ok || !r.dati) {
        if (r.rete) svuota(pag, A.messaggio('avviso', 'Si apre quando torna la rete.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: carica }, 'Riprova')]));
        else svuota(pag, A.erroreConRiprova(r, carica));
        return;
      }
      s = r.dati;
      m = A.materiaDi(s.materia_id) || { id: s.materia_id, nome: '', colore: 'ambra' };
      S.materiaAperta = s.materia_id;
      A.disegnaMenu();
      A.disegnaStato();
      disegna();
    }

    function disegna() {
      if (inLavoro(s)) {
        svuota(pag, A.vistaLavoro({
          materia: m, titolo: s.titolo || s.argomento, frase: s.stato === 'in_coda' ? 'In coda' : s.frase || 'In preparazione', percentuale: s.stato === 'in_coda' ? 0 : L.percentualeLavoro(s), indietro,
          annulla: async () => {
            if (!(await A.conferma({ titolo: 'Annullare lo schema?', testo: 'Le spese già fatte restano nel conto del mese.', si: 'Annulla lo schema', no: 'Continua', pericolo: true }))) return;
            const r = await A.api.post('/server/api/schemi/' + id + '/annulla', {});
            if (!r.ok) { A.avviso(r.errore); return; }
            s = Object.assign({}, s, r.dati || {}, { stato: 'annullato' });
            disegna();
          },
        }));
        A.ripeti(vivo, carica, T.stato);
        return;
      }
      if (s.stato === 'errore' || s.stato === 'annullato') { disegnaFermo(); return; }
      const sezioni = (s.contenuto && s.contenuto.sezioni) || [];
      if (!sezioni.length) { disegnaVuoto(); return; }
      disegnaLettura(sezioni);
    }

    function testaSemplice() {
      return h('div', { class: 'sch-testa' }, A.frecciaIndietro(indietro), A.pillolaMateria(m));
    }

    async function elimina() {
      if (!(await A.conferma({ titolo: 'Eliminare lo schema?', testo: '"' + (s.titolo || s.argomento) + '" sparisce. I soldi già spesi restano nel conto del mese.', si: 'Elimina', pericolo: true }))) return;
      const r = await A.api.elimina('/server/api/schemi/' + id);
      if (!r.ok) { A.avviso(r.errore); return; }
      A.memoriaStudio.togli(chiaveParte(id));
      await A.caricaMaterie();
      A.vai('#materia/' + m.id + '/schemi');
    }

    function disegnaFermo() {
      const riprova = async () => {
        const r = await A.api.post('/server/api/schemi/' + id + '/riprova', {});
        if (!r.ok) { A.avviso(r.errore); return; }
        if (S.motore) S.motore.in_preparazione = true;
        carica();
      };
      svuota(pag, h('div', { class: 'vista-lavoro' }, testaSemplice(),
        h('h1', { class: 'titolo-pagina' }, s.titolo || s.argomento),
        s.stato === 'errore'
          ? A.messaggio('errore', s.errore || 'Lo schema non è riuscito.', [h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-schema', onclick: riprova }, 'Riprova')])
          : A.messaggio('avviso', 'Schema annullato.'),
        h('div', null, h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'elimina-schema', onclick: elimina }, 'Elimina'))));
    }

    function disegnaVuoto() {
      svuota(pag, h('div', { class: 'vista-lavoro' }, testaSemplice(),
        h('h1', { class: 'titolo-pagina' }, s.titolo || s.argomento),
        A.messaggio('avviso', 'Nei documenti scelti non ho trovato abbastanza per uno schema.'),
        h('div', null, h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'altro-materiale', onclick: () => A.nuovoSchema(m.id, { argomento: s.argomento, materiali: s.materiali, forma: s.forma, livello: s.livello, indicazioni: s.indicazioni, eventoId: s.evento_id }) }, 'Scegli altro materiale'))));
    }

    function disegnaLettura(sezioni) {
      const contenitore = S.dom.scorre;
      const mappa = (s.contenuto && s.contenuto.fonti) || {};
      const versioni = new Map();
      const parti = new Map();
      const vociIndice = new Map();
      let corrente = sezioni[0].n;
      let primoParagrafo = true;
      let primaFonte = true;

      const pillolaT = h('span', { class: 't' });
      const pillolaQ = h('span', { class: 'q' });
      const pillolaIndice = h('button', { type: 'button', class: 'sch-pillola-indice', 'data-azione': 'indice-foglio', 'data-visita': 'indice-stretto', onclick: apriFoglioIndice }, icona('indice'), h('span', null, 'Indice'), pillolaQ, pillolaT);
      const indice = h('nav', { class: 'sch-indice', 'aria-label': 'Indice', 'data-visita': 'indice' }, h('span', { class: 'etichetta' }, 'Indice'),
        sezioni.map((x) => {
          const b = h('button', { type: 'button', class: 'sch-voce', 'data-parte': String(x.n), onclick: () => salta(x.n) }, h('span', null, x.titolo));
          vociIndice.set(x.n, b);
          return b;
        }));

      function accendi(n) {
        corrente = n;
        const sez = sezioni.find((x) => x.n === n) || sezioni[0];
        vociIndice.forEach((b, k) => {
          b.classList.toggle('corrente', k === n);
          if (k === n) b.setAttribute('aria-current', 'true'); else b.removeAttribute('aria-current');
        });
        pillolaQ.textContent = (sezioni.indexOf(sez) + 1) + ' di ' + sezioni.length;
        pillolaT.textContent = sez.titolo;
        const voce = vociIndice.get(n);
        if (voce && indice.scrollHeight > indice.clientHeight) {
          if (voce.offsetTop < indice.scrollTop) indice.scrollTop = voce.offsetTop;
          else if (voce.offsetTop + voce.offsetHeight > indice.scrollTop + indice.clientHeight) indice.scrollTop = voce.offsetTop + voce.offsetHeight - indice.clientHeight;
        }
        A.memoriaStudio.scrivi(chiaveParte(id), n);
      }

      function posizioneDi(n) {
        const el = parti.get(n);
        if (!el || !contenitore) return null;
        return contenitore.scrollTop + el.getBoundingClientRect().top - contenitore.getBoundingClientRect().top - 16;
      }

      function salta(n) {
        if (A.visitaEvento) A.visitaEvento('indice');
        const dove = posizioneDi(n);
        if (dove == null) return;
        const prima = contenitore.scrollTop;
        scorriA(contenitore, dove);
        pillolaTorna(contenitore, prima);
        accendi(n);
      }

      function apriFoglioIndice() {
        if (A.visitaEvento) A.visitaEvento('indice');
        A.apriFoglio({
          titolo: 'Indice',
          corpo: (c, ctl) => {
            svuota(c, h('div', null, sezioni.map((x) => h('button', {
              type: 'button', class: 'sch-voce-foglio' + (x.n === corrente ? ' corrente' : ''), 'data-parte': String(x.n), onclick: () => { ctl.chiudi(true); salta(x.n); },
            }, h('span', { class: 'sch-num ' + A.classeMateria(m.colore) }, String(x.n)), x.titolo))));
            const sua = c.querySelector('.corrente');
            if (sua) ctl.foglio.scrollTop = Math.max(0, sua.offsetTop - 120);
          },
        });
      }

      function spento() { return !!S.senzaRete; }

      function foto(b) {
        const blocco = h('div', { class: 'sch-foto-blocco', 'data-foto': String(b.n) });
        const didascalia = () => [b.didascalia ? h('span', { class: 'sch-didascalia' }, b.didascalia) : null, b.credito ? h('span', { class: 'sch-credito' }, b.credito) : null];
        let conferma = false;

        function disegnaFoto(nuova) {
          const v = versioni.get(b.n) || 0;
          const src = indirizzoFoto(id, b.n, v);
          const img = h('img', { src, alt: b.didascalia || 'Foto' });
          const riquadro = h('div', { class: 'sch-foto' + (nuova ? ' nuova' : '') });
          img.addEventListener('error', () => {
            svuota(blocco, h('div', { class: 'sch-foto-rotta', 'data-foto-rotta': 'si' }, 'Foto non disponibile',
              h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: () => { versioni.set(b.n, v + 1); disegnaFoto(false); } }, 'Riprova')), didascalia());
          });
          const sopra = h('div', { class: 'sopra-foto' });
          const menu = A.menuPuntini(() => [
            { testo: 'Cambia foto', azione: 'cambia-foto', spenta: spento(), fai: cambia },
            { testo: 'Togli foto', azione: 'togli-foto', spenta: spento(), fai: () => { conferma = true; disegnaFoto(false); } },
          ], { classe: 'puntini-foto', etichetta: 'Altre azioni sulla foto', aperto: (si) => sopra.classList.toggle('aperto', si) });
          sopra.append(menu);
          riquadro.append(h('button', { type: 'button', class: 'apri-foto', 'aria-label': 'Apri la foto a tutto schermo', onclick: () => A.apriFoto(src, b.didascalia) }, img), sopra);
          svuota(blocco, riquadro, didascalia(), conferma ? h('div', { class: 'sch-togli' }, h('span', null, 'Toglierla?'),
            h('button', { type: 'button', class: 'pulsante tenue piccolo', 'data-azione': 'togli-si', onclick: togli }, 'Togli'),
            h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'togli-no', onclick: () => { conferma = false; disegnaFoto(false); } }, 'Lascia')) : null);
          return riquadro;
        }

        async function cambia() {
          const riquadro = blocco.querySelector('.sch-foto');
          if (!riquadro) return;
          riquadro.classList.add('cerca');
          const scritta = h('span', { class: 'cerco' }, 'Cerco un\'altra foto');
          riquadro.append(scritta);
          const r = await A.api.post('/server/api/schemi/' + id + '/immagini/' + b.n + '/cambia', {});
          if (!blocco.isConnected) return;
          if (r.ok && r.dati) {
            s = r.dati;
            for (const x of (s.contenuto && s.contenuto.sezioni) || []) for (const y of x.blocchi || []) if (y.tipo === 'immagine' && y.n === b.n) Object.assign(b, y);
            versioni.set(b.n, (versioni.get(b.n) || 0) + 1);
            disegnaFoto(true);
            return;
          }
          riquadro.classList.remove('cerca');
          scritta.remove();
          A.avviso(r.stato === 404 ? 'Non ne ho trovate altre' : r.errore);
        }

        async function togli() {
          const r = await A.api.elimina('/server/api/schemi/' + id + '/immagini/' + b.n);
          if (!r.ok) { A.avviso(r.errore); return; }
          s = r.dati || s;
          const via = () => { const c = blocco.closest('.sch-blocco'); (c || blocco).remove(); };
          if (senzaMovimento()) { via(); return; }
          blocco.classList.add('togliendo');
          setTimeout(via, 220);
        }

        disegnaFoto(false);
        return blocco;
      }

      function corpoBlocco(b) {
        const tit = (x, classe) => (x ? h('h3', { class: classe }, x) : null);
        switch (b.tipo) {
          case 'paragrafo': return [tit(b.titolo, 'sch-titolo-testo'), h('p', { class: 'sch-testo' }, A.conGrassetto(b.testo))];
          case 'punti': return [tit(b.titolo, 'sch-titolo-piccolo'), h('ul', { class: 'sch-punti' }, (b.voci || []).map((v) => h('li', null, A.conGrassetto(v))))];
          case 'passaggi': return [tit(b.titolo, 'sch-titolo-piccolo'), h('div', { class: 'sch-passaggi' }, (b.voci || []).map((v, i) => [i ? h('div', { class: 'sch-freccia', 'aria-hidden': 'true' }, icona('giu')) : null, h('div', { class: 'sch-passo' }, A.conGrassetto(v))]))];
          case 'definizione': return h('div', { class: 'sch-definizione' }, h('span', { class: 'termine' }, A.conGrassetto(b.termine)), h('span', { class: 'spiega' }, A.conGrassetto(b.testo)));
          case 'prof': return h('div', { class: 'sch-prof' }, h('span', { class: 'dal' }, icona('verifica'), 'DAL PROF'), h('span', { class: 'detto' }, A.conGrassetto(b.testo)));
          case 'tabella': {
            const titoli = b.intestazione || [];
            return [tit(b.titolo, 'sch-titolo-piccolo'), h('table', { class: 'sch-tabella' + (titoli.length >= 4 ? ' quattro' : '') },
              titoli.length ? h('thead', null, h('tr', null, titoli.map((t) => h('th', { scope: 'col' }, t)))) : null,
              h('tbody', null, (b.righe || []).map((r) => h('tr', null, r.map((c, i) => h('td', { 'data-etichetta': titoli[i] || '' }, A.conGrassetto(c)))))))];
          }
          case 'immagine': return foto(b);
          default: return null;
        }
      }

      function disegnaBlocco(b, x, segno) {
        const chiaribile = L.BLOCCHI_CHIARIBILI.includes(b.tipo);
        const attr = { class: 'sch-blocco' + (chiaribile ? ' chiaribile' : ''), 'data-tipo': b.tipo };
        const quiPrimo = chiaribile && b.tipo === 'paragrafo' && primoParagrafo;
        if (quiPrimo) { attr['data-visita'] = 'paragrafo'; primoParagrafo = false; }
        const segnoAttr = segno && segno.length && primaFonte ? { 'data-visita': 'fonti' } : null;
        if (segnoAttr) primaFonte = false;
        return h('div', attr,
          corpoBlocco(b),
          A.segnoFonte(segno, segnoAttr),
          chiaribile ? h('button', {
            type: 'button', class: 'sch-mano', 'aria-label': 'Non ho capito', 'data-azione': 'non-ho-capito', 'data-visita': quiPrimo ? 'mano' : null, disabled: spento() ? true : null,
            onclick: () => { if (A.visitaEvento) A.visitaEvento('apri-capito'); A.nonHoCapito({ materia: m, schemaId: id, sezione: x.n, passaggio: L.passaggioDelBlocco(b) }); },
          }, icona('domanda'), h('span', { class: 'sopra', 'aria-hidden': 'true' }, 'Non ho capito')) : null);
      }

      function disegnaParte(x) {
        const testaOp = L.testaOpera(x);
        const noti = testaOp.blocchi;
        const segni = L.segniDeiBlocchi(noti, mappa);
        const figli = noti.map((b, i) => disegnaBlocco(b, x, segni[i]));
        const opera = testaOp.campi.length ? h('div', { class: testaOp.foto ? 'sch-opera' : null },
          testaOp.foto ? foto(testaOp.foto) : null,
          h('div', { class: 'sch-campi' }, testaOp.campi.map((c) => h('div', { class: 'sch-campo' }, h('span', { class: 'k' }, c.nome), h('span', { class: 'v' }, c.valore))))) : null;
        const sezEl = h('section', { class: 'sch-parte', 'data-parte': String(x.n) },
          h('div', { class: 'sch-testa-parte' },
            h('span', { class: 'sch-num', 'aria-hidden': 'true' }, String(x.n)),
            h('h2', { class: 'sch-titolo-parte' }, x.titolo),
            h('button', {
              type: 'button', class: 'pillola-azione', 'data-azione': 'ascolta-parte', disabled: spento() ? true : null,
              onclick: () => A.nuovaLezione(m.id, { argomento: x.titolo, materiali: s.materiali || [] }),
            }, icona('lezioni'), 'Ascolta questa parte')),
          opera,
          h('div', { class: 'sch-blocchi' }, figli));
        parti.set(x.n, sezEl);
        return sezEl;
      }

      const mancanze = L.mancanzeSchema(s);
      const nota = mancanze ? h('div', { class: 'sch-nota', 'data-nota': 'mancanze' },
        h('div', { class: 'testa' }, icona('domanda'), 'Non ho trovato tutto'),
        mancanze.nota ? h('p', null, mancanze.nota) : null,
        mancanze.mancanti.length ? h('div', null, h('span', { class: 'forte-nota' }, 'Nei tuoi documenti non c\'è:'),
          h('ul', null, mancanze.mancanti.map((t) => h('li', null, t)), mancanze.altri ? h('li', null, 'e altri ' + mancanze.altri) : null)) : null,
        mancanze.avvisi.map((t) => h('p', null, t)),
        h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'aggiungi-materiale', onclick: () => A.apriAggiungi(m) }, 'Aggiungi materiale')) : null;

      const fatti = (s.riassunti && s.riassunti.fatti) || [];
      const ultimo = L.ultimoRiassunto(fatti);
      const finale = [
        Number(s.carte) > 0 ? h('div', { class: 'sch-fine', 'data-fine': 'lampo' }, h('span', { class: 'testo-fine' }, L.plurale(Number(s.carte), 'scheda è già', 'schede sono già') + ' in Ripasso lampo.'),
          h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'ripassa-adesso', onclick: () => A.apriLampo({ materiaId: m.id }) }, 'Ripassa adesso')) : null,
        s.riassunti ? h('div', { class: 'sch-fine', 'data-fine': 'riassunto' }, h('h2', null, 'Riassunto'),
          h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': ultimo ? 'apri-riassunto' : 'fai-riassunto', onclick: () => (ultimo ? A.vai('#riassunto/' + ultimo.id) : A.apriSceltaRiassunto(s, m)) }, ultimo ? 'Apri il riassunto' : 'Fai il riassunto')) : null,
      ];

      const colonna = h('div', { class: 'sch-colonna' },
        h('div', { class: 'sch-testa' }, A.frecciaIndietro(indietro), A.pillolaMateria(m), h('span', { class: 'spazio' }),
          s.riassunti ? h('button', { type: 'button', class: 'pillola-azione', 'data-azione': 'riassunto', onclick: () => A.apriSceltaRiassunto(s, m) }, icona('riassunto'), 'Riassunto') : null,
          A.pulsantePdf('api/schemi/' + id + '/pdf', L.nomeFilePdf(s.titolo, 'Schema')),
          A.menuPuntini(() => [{ testo: 'Elimina', pericolo: true, azione: 'elimina-schema', fai: elimina }])),
        h('div', { class: 'sch-titoli' }, h('h1', null, s.titolo || s.argomento), h('span', { class: 'sottotitolo', 'data-riassunto': 'schema' }, L.riassuntoSchema(s))),
        nota,
        pillolaIndice,
        sezioni.map(disegnaParte),
        finale);

      svuota(pag, h('div', { class: 'schema-contenitore ' + A.classeMateria(m.colore) }, h('div', { class: 'sch-griglia' }, indice, colonna)));

      accendi(corrente);
      const salvata = A.memoriaStudio.leggi(chiaveParte(id));
      if (contenitore && salvata != null && parti.has(Number(salvata)) && Number(salvata) !== sezioni[0].n) {
        const dove = posizioneDi(Number(salvata));
        if (dove != null) {
          contenitore.style.scrollBehavior = 'auto';
          contenitore.scrollTop = dove;
          contenitore.style.scrollBehavior = '';
          accendi(Number(salvata));
        }
      }

      if (contenitore) {
        let inCoda = false;
        const suScorri = () => {
          if (!vivo()) { contenitore.removeEventListener('scroll', suScorri); return; }
          if (inCoda) return;
          inCoda = true;
          requestAnimationFrame(() => {
            inCoda = false;
            if (!vivo()) return;
            const sopra = contenitore.getBoundingClientRect().top;
            let attuale = sezioni[0].n;
            for (const x of sezioni) {
              const el = parti.get(x.n);
              if (el && el.getBoundingClientRect().top - sopra <= 120) attuale = x.n;
            }
            if (attuale !== corrente) accendi(attuale);
          });
        };
        contenitore.addEventListener('scroll', suScorri, { passive: true });
      }
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    await carica();
  };
})();
