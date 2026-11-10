/*
 * Lectio per il computer: le pagine (docs/aspetto.md §3-§5, §8-§10) e i loro fogli.
 * Oggi, Materie (+ Nuova/Modifica materia), Materia (+ Aggiungi materiale, Aggiungi data, testo letto),
 * scheda del prof, Lezioni, Per Gemini, Impostazioni, Com'è andata.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  /** Un timer della pagina aperta: si ferma da solo quando si cambia pagina. */
  function ripeti(vivo, fai, ms) {
    clearTimeout(S.timerPagina);
    S.timerPagina = setTimeout(() => { if (vivo()) fai(); }, ms);
  }
  A.ripeti = ripeti;

  function testaPagina(titolo, sotto, destra) {
    return h('div', { class: 'testa-pagina' },
      h('div', null, h('h1', { class: 'titolo-pagina' }, titolo), sotto ? h('span', { class: 'sottotitolo' }, sotto) : null),
      destra || null);
  }

  function titoloEvento(ev, materia) {
    if (ev && ev.argomento) return ev.argomento;
    return (ev && ev.tipo === 'orale' ? 'Interrogazione di ' : 'Verifica di ') + (materia ? materia.nome : '');
  }
  const tipoEvento = (ev) => (ev && ev.tipo === 'orale' ? 'Interrogazione' : 'Verifica');
  A.titoloEvento = titoloEvento;

  /** I dettagli delle lezioni (capitoli e domande), tenuti per non richiederli a ogni giro. */
  const dettagli = new Map();
  A.dettaglioLezione = async function (id, forza) {
    if (!forza && dettagli.has(id)) return { ok: true, dati: dettagli.get(id) };
    const r = await A.api.get('/server/api/lezioni/' + id);
    if (r.ok && r.dati) dettagli.set(id, r.dati);
    return r;
  };
  A.dimenticaDettaglio = (id) => dettagli.delete(id);

  // ================================================================== Oggi (§3)

  A.pagine.oggi = async function (box, rotta, vivo) {
    const pag = h('div', { class: 'pagina' });
    const data = h('span', { class: 'sottotitolo' }, L.dataLunga(L.iso()));
    const corpo = h('div', { class: 'griglia-oggi' }, h('div', { class: 'colonna' }, A.segnaposto(4)), h('div', { class: 'colonna' }, A.segnaposto(2)));
    svuota(box, svuota(pag, h('div', { class: 'testa-pagina' }, h('div', null, h('h1', { class: 'titolo-pagina' }, 'Oggi'), data)), corpo));

    async function riempi() {
      const [r] = await Promise.all([A.api.get('/server/api/oggi'), S.materie ? null : A.caricaMaterie()]);
      if (!vivo()) return;
      if (!r.ok || !r.dati) {
        svuota(corpo, h('div', { class: 'colonna' }, A.erroreConRiprova(r, riempi)));
        return;
      }
      const d = r.dati;
      if (d.oggi) data.textContent = L.dataLunga(d.oggi);
      if (d.spesa) { S.spesa = Object.assign({}, S.spesa || {}, d.spesa); A.disegnaMenu(); }
      if (d.modalita_prova != null && S.salute) S.salute.modalita_prova = d.modalita_prova;
      const senzaMaterie = Array.isArray(S.materie) && S.materie.length === 0;
      if (senzaMaterie && !d.prossima) {
        svuota(corpo, h('div', { class: 'colonna' }, h('div', { class: 'scheda rilievo vuoto' },
          h('p', null, 'Aggiungi la prima materia'),
          h('button', { type: 'button', class: 'pulsante pieno', onclick: () => A.apriMateria(null) }, icona('piu'), 'Nuova materia'),
          h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'visita', onclick: () => A.avviaVisita && A.avviaVisita() }, 'Prima volta? Guarda come funziona'))), h('div'));
        return;
      }
      const sinistra = h('div', { class: 'colonna' }, schedaProssima(d.prossima, riempi));
      const destra = h('div', { class: 'colonna' });
      if (d.riprendi) destra.append(schedaRiprendi(d.riprendi));
      // §31.1 Tra Riprendi e Dopo la verifica.
      if (d.lampo) destra.append(A.schedaLampo(d.lampo.da_fare, d.lampo.minuti, () => { if (A.visitaEvento) A.visitaEvento('lampo-apri'); A.apriLampo(); }));
      for (const x of d.da_raccontare || []) destra.append(schedaRacconta(x));
      // Più avanti sotto la prossima se a destra c'è già qualcosa (le due colonne restano in equilibrio).
      if ((d.piu_avanti || []).length) (destra.childElementCount ? sinistra : destra).append(sezionePiuAvanti(d.piu_avanti));
      if (!destra.childElementCount) destra.append(h('div'));
      svuota(corpo, sinistra, destra);
      // Una lezione in preparazione: si chiede ogni 20 secondi (§11).
      const lez = d.prossima && d.prossima.lezione;
      if (lez && (lez.stato === 'in_coda' || lez.stato === 'in_preparazione')) ripeti(vivo, riempi, T.preparazione);
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }
    S.aggiornaPagina = riempi;
    await riempi();
  };

  function schedaProssima(p, riempi) {
    if (!p) {
      return h('div', { class: 'sezione' }, h('span', { class: 'etichetta' }, 'La prossima'),
        h('div', { class: 'scheda rilievo vuoto' }, h('p', null, 'Nessuna verifica in arrivo'),
          h('button', { type: 'button', class: 'pulsante tenue', onclick: () => A.vai('#materie') }, 'Apri le materie')));
    }
    const m = A.materiaDi(p.materia && p.materia.id) || p.materia;
    const ev = p.evento || {};
    const lez = p.lezione;
    const stato = h('div', { class: 'stato-prossima', stile: { display: 'flex', flexDirection: 'column', gap: '16px' } });
    if (!lez || lez.stato === 'annullata') {
      stato.append(h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'prepara-prossima', onclick: () => A.nuovaLezione(m.id, { eventoId: ev.id }) }, 'Prepara la lezione'));
    } else if (lez.stato === 'pronta') {
      const sotto = h('span', { class: 'secondario' }, L.riassuntoLezione(lez));
      A.dettaglioLezione(lez.id).then((r) => { if (r.ok && r.dati) sotto.textContent = L.riassuntoLezione(r.dati); });
      stato.append(
        h('div', { class: 'riga-lezione' }, A.piastrella(m.colore), h('div', { class: 'testi' }, h('span', { class: 'forte' }, 'Lezione pronta'), sotto)),
        h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'ascolta-prossima', onclick: () => A.ascolta(lez.id, { suona: true }) }, icona('play'), 'Ascolta'));
    } else if (lez.stato === 'errore') {
      stato.append(A.messaggio('errore', lez.errore || 'La lezione non è riuscita.', [
        h('button', { type: 'button', class: 'pulsante bianco', onclick: async () => { const r = await A.api.post('/server/api/lezioni/' + lez.id + '/riprova', {}); if (!r.ok) A.avviso(r.errore); riempi(); } }, 'Riprova')]));
    } else {
      const f = L.fasi(lez);
      const corso = f.righe.find((x) => x.stato === 'corso');
      stato.append(h('button', { type: 'button', class: 'mini-fasi', stile: { border: 'none', background: 'none', padding: '0', textAlign: 'left' }, onclick: () => A.vai('#lezione/' + lez.id) },
        h('span', { class: 'frase' }, f.inCoda ? 'In coda: parte appena finisce l\'altra' : (lez.avanzamento && lez.avanzamento.frase) || (corso ? corso.nome : 'In preparazione')),
        A.barra(f.percentuale, 'spessa')));
    }
    return h('div', { class: 'sezione' },
      h('span', { class: 'etichetta' }, 'La prossima'),
      h('div', { class: 'scheda rilievo prossima ' + A.classeMateria(m.colore), 'data-visita': 'prossima' },
        h('div', { class: 'alto' }, A.pillolaMateria(m, () => { if (A.visitaEvento) A.visitaEvento('apri-materia'); A.vai('#materia/' + m.id); }, { 'data-visita': 'pillola-prossima' }),
          h('span', { class: 'quando' }, L.quando(p.tra_giorni))),
        h('div', { stile: { display: 'flex', flexDirection: 'column', gap: '4px' } },
          h('span', { class: 'titolo-grande' }, titoloEvento(ev, m)),
          h('span', { class: 'secondario' }, L.dataLunga(ev.data) + ' · ' + (ev.tipo === 'orale' ? 'orale' : 'scritta'))),
        stato));
  }

  function schedaRiprendi(l) {
    const m = A.materiaDi(l.materia_id) || { nome: '', colore: 'ambra' };
    const durata = l.durata_s || l.durata_min * 60;
    const pos = (l.ascolto && l.ascolto.posizione_s) || 0;
    return h('div', { class: 'scheda compatta riprendi' },
      h('span', { class: 'etichetta' }, 'Riprendi'),
      h('div', { class: 'corpo' }, A.piastrella(m.colore),
        h('div', { class: 'testi' }, h('span', { class: 'forte' }, l.argomento), A.barra(L.percentuale(pos, durata), 'sottile'),
          h('span', { class: 'secondario' }, m.nome + ' · ' + L.mancano(durata, pos))),
        h('button', { type: 'button', class: 'tondo accento', 'aria-label': 'Riprendi la lezione', onclick: () => A.ascolta(l.id, { suona: true }) }, icona('play'))));
  }

  function schedaRacconta(x) {
    const m = A.materiaDi(x.materia && x.materia.id) || x.materia || { nome: '' };
    const cosa = x.evento.tipo === 'orale' ? 'l\'interrogazione' : 'la verifica';
    return h('div', { class: 'scheda racconta' },
      h('div', { class: 'testi' }, h('span', { class: 'etichetta' }, 'Dopo la verifica'),
        h('span', { class: 'forte' }, 'Com\'è andata ' + cosa + ' di ' + m.nome + '?'),
        h('span', { class: 'secondario' }, L.dataLunga(x.evento.data))),
      h('button', { type: 'button', class: 'pulsante tenue', onclick: () => A.vai('#racconta/' + x.evento.id) }, 'Racconta'));
  }

  function rigaEvento(e, materia, traGiorni) {
    const m = A.materiaDi(materia && materia.id) || materia || { nome: '', colore: 'ambra' };
    return h('div', { class: 'riga' }, A.pallino(m.colore, true),
      h('div', { class: 'testi' }, h('span', { class: 'forte' }, m.nome), h('span', { class: 'secondario' }, tipoEvento(e) + ' · ' + L.dataLunga(e.data))),
      h('span', { class: 'destra' }, traGiorni === 0 ? 'oggi' : traGiorni === 1 ? 'domani' : traGiorni + ' giorni'));
  }

  function sezionePiuAvanti(lista) {
    return h('div', { class: 'sezione' },
      h('div', { class: 'testa-sezione' }, h('span', { class: 'etichetta' }, 'Più avanti'),
        h('button', { type: 'button', class: 'pulsante testo', onclick: apriTutteLeDate }, 'Vedi tutte')),
      h('div', { class: 'elenco' }, lista.slice(0, 4).map((x) => rigaEvento(x.evento, x.materia, x.tra_giorni))));
  }

  function apriTutteLeDate() {
    A.apriFoglio({
      titolo: 'Verifiche e interrogazioni',
      corpo: async (c) => {
        svuota(c, A.segnaposto(3));
        const r = await A.api.get('/server/api/eventi?da=' + L.iso());
        if (!r.ok) { svuota(c, A.erroreConRiprova(r)); return; }
        const futuri = (r.dati || []).filter((e) => L.giorniFra(L.iso(), e.data) >= 0).sort((a, b) => a.data.localeCompare(b.data));
        svuota(c, futuri.length ? h('div', { class: 'elenco', stile: { boxShadow: 'none', padding: '0' } }, futuri.map((e) => rigaEvento(e, A.materiaDi(e.materia_id), L.giorniFra(L.iso(), e.data))))
          : h('p', { class: 'forte' }, 'Nessuna verifica in arrivo'));
      },
    });
  }

  // ================================================================== Materie (§4)

  A.pagine.materie = async function (box, rotta, vivo) {
    const pag = h('div', { class: 'pagina' });
    const elenco = h('div', { class: 'griglia-materie' }, A.segnaposto(2), A.segnaposto(2));
    svuota(box, svuota(pag, testaPagina('Materie', null,
      h('button', { type: 'button', class: 'pulsante pieno medio', 'data-azione': 'nuova-materia', onclick: () => A.apriMateria(null) }, icona('piu'), 'Nuova materia')), elenco));
    const r = await A.caricaMaterie();
    if (!vivo()) return;
    if (!r.ok) { svuota(elenco, A.erroreConRiprova(r, () => A.disegna())); return; }
    if (!S.materie.length) {
      svuota(elenco, h('div', { class: 'vuoto' }, h('p', null, 'Nessuna materia ancora'),
        h('button', { type: 'button', class: 'pulsante pieno', onclick: () => A.apriMateria(null) }, icona('piu'), 'Nuova materia')));
      return;
    }
    svuota(elenco, S.materie.map((m) => h('button', { type: 'button', class: 'scheda-materia ' + A.classeMateria(m.colore), 'data-materia': String(m.id), onclick: () => A.vai('#materia/' + m.id) },
      h('span', { class: 'piastrella iniziale', 'aria-hidden': 'true' }, (m.nome || '?').trim().charAt(0).toUpperCase()),
      h('span', { class: 'testi' }, h('span', { class: 'nome' }, m.nome), h('span', { class: 'secondario' }, L.sottoMateria(m))),
      m.da_ripassare > 0 ? h('span', { class: 'pillola avviso' }, m.da_ripassare + ' da ripassare') : null,
      icona('freccia'))));
  };

  /** Nuova materia o Modifica materia: anteprima dal vivo, nome, prof, 16 colori; in Modifica anche Elimina. */
  A.apriMateria = function (materia) {
    const nuova = !materia;
    const st = { nome: materia ? materia.nome : '', prof: materia ? materia.prof || '' : '', colore: materia ? materia.colore : L.primoColoreLibero(S.materie), errore: null, invio: false };
    A.apriFinestra({
      stretta: true,
      etichetta: nuova ? 'Nuova materia' : 'Modifica materia',
      classe: 'finestra-materia',
      disegna: (f, ctl) => {
        const anteprima = h('div', { class: 'anteprima-materia' });
        const nome = h('input', { class: 'campo', id: 'materia-nome', maxlength: '40', placeholder: 'Per esempio Italiano', value: st.nome, autocapitalize: 'words', 'data-fuoco': true });
        const prof = h('input', { class: 'campo', id: 'materia-prof', maxlength: '40', placeholder: 'Cognome, se vuoi', value: st.prof });
        const errore = h('span', { class: 'errore-campo', role: 'alert' });
        const cerchi = h('div', { class: 'cerchi-colore', role: 'radiogroup', 'aria-label': 'Colore' });
        const nomeColore = h('span', { class: 'nome-colore', 'data-colore-nome': 'si' });
        const usatoDa = h('span', { class: 'colore-usato', 'data-colore-usato': 'si' });
        const crea = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'salva-materia' }, nuova ? 'Crea materia' : 'Salva');
        function aggiorna() {
          anteprima.className = 'anteprima-materia ' + A.classeMateria(st.colore);
          const n = st.nome.trim();
          svuota(anteprima, h('span', { class: 'piastrella iniziale', 'aria-hidden': 'true' }, n ? n.charAt(0).toUpperCase() : '?'),
            h('span', { class: 'testi' }, h('span', { class: 'nome' }, n || 'Nome della materia'), h('span', { class: 'prof' }, st.prof.trim() ? L.prof(st.prof) : 'Prof. da scrivere')));
          svuota(cerchi, L.ORDINE_COLORI.map((c) => {
            const usata = L.coloreUsatoDa(c, S.materie, materia ? materia.id : null);
            return h('button', {
              type: 'button', class: 'cerchio-colore ' + A.classeMateria(c) + (usata ? ' usato' : ''), role: 'radio', 'aria-checked': c === st.colore ? 'true' : 'false',
              'aria-label': L.NOMI_COLORI[c] + (usata ? ', già usato da ' + usata.nome : ''), 'data-colore': c,
              onclick: () => { st.colore = c; aggiorna(); },
            }, icona('spunta'));
          }));
          nomeColore.textContent = ' · ' + L.NOMI_COLORI[st.colore];
          const usata = L.coloreUsatoDa(st.colore, S.materie, materia ? materia.id : null);
          usatoDa.textContent = usata ? 'già usato da ' + usata.nome : '';
          usatoDa.style.display = usata ? '' : 'none';
          crea.disabled = !n || st.invio;
          errore.textContent = st.errore || '';
        }
        nome.addEventListener('input', () => { st.nome = nome.value; st.errore = null; aggiorna(); });
        prof.addEventListener('input', () => { st.prof = prof.value; aggiorna(); });
        nome.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !crea.disabled) crea.click(); });
        crea.addEventListener('click', async () => {
          const corpo = { nome: st.nome.trim(), prof: st.prof.trim(), colore: st.colore };
          st.invio = true;
          aggiorna();
          const r = nuova ? await A.api.post('/server/api/materie', corpo) : await A.api.patch('/server/api/materie/' + materia.id, corpo);
          st.invio = false;
          if (!r.ok) {
            st.errore = r.stato === 409 ? (r.errore || 'Esiste già una materia con questo nome') : r.errore;
            aggiorna();
            nome.focus();
            return;
          }
          ctl.chiudi();
          await A.caricaMaterie();
          if (nuova && r.dati && r.dati.id) A.vai('#materia/' + r.dati.id);
          else A.disegna();
        });
        svuota(f,
          h('div', { class: 'corpo bianco' },
            h('div', { class: 'testa-finestra' }, h('h2', { class: 'titolo-sezione', stile: { fontSize: '21px', flexGrow: '1' } }, nuova ? 'Nuova materia' : 'Modifica materia'),
              h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => ctl.chiudi() }, icona('chiudi'))),
            anteprima,
            h('label', { class: 'campo-gruppo', for: 'materia-nome' }, h('span', { class: 'etichetta' }, 'Nome'), nome, errore),
            h('label', { class: 'campo-gruppo', for: 'materia-prof' }, h('span', { class: 'etichetta' }, 'Prof'), prof),
            h('div', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Colore', nomeColore), cerchi, usatoDa)),
          h('div', { class: 'piede' }, crea,
            nuova ? null : h('button', { type: 'button', class: 'pulsante testo pericolo', 'data-azione': 'elimina-materia', onclick: async () => {
              const ok = await A.conferma({ titolo: 'Eliminare ' + materia.nome + '?', testo: 'Spariscono verifiche, appunti, lezioni e audio di ' + materia.nome + '.', si: 'Elimina materia', pericolo: true });
              if (!ok) return;
              const r = await A.api.elimina('/server/api/materie/' + materia.id, { conferma: true });
              if (!r.ok) { A.avviso(r.errore); return; }
              ctl.chiudi();
              await A.caricaMaterie();
              A.vai('#materie');
            } }, 'Elimina materia')));
        aggiorna();
        setTimeout(() => nome.focus(), 40);
      },
    });
  };

  // ================================================================== Materia (§4)

  const iconaTipo = (tipo) => (['verifica', 'interrogazione', 'appunti', 'libro'].includes(tipo) ? tipo : 'altro');
  A.iconaTipo = iconaTipo;

  A.pagine.materia = async function (box, rotta, vivo) {
    const id = rotta.id;
    let m = A.materiaDi(id);
    if (!m) {
      svuota(box, h('div', { class: 'pagina' }, A.segnaposto(3)));
      await A.caricaMaterie();
      if (!vivo()) return;
      m = A.materiaDi(id);
      if (!m) { svuota(box, h('div', { class: 'pagina' }, A.messaggio('errore', 'Questa materia non c\'è più.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: () => A.vai('#materie') }, 'Apri le materie')]))); return; }
    }
    const cm = A.classeMateria(m.colore);
    const sotto = h('span', { class: 'sotto' }, L.sottoMateria(m, true));
    const testata = h('div', { class: 'testata-materia ' + cm },
      h('div', { class: 'dentro' },
        h('div', { class: 'nomi' }, h('h1', null, m.nome), sotto),
        h('div', { class: 'comandi' },
          h('button', { type: 'button', class: 'tondo', 'aria-label': 'Modifica materia', title: 'Modifica materia', onclick: () => A.apriMateria(A.materiaDi(id) || m) }, icona('appunti')))));
    const scheda = h('section', { class: 'scheda scheda-prof ' + cm, 'data-visita': 'scheda-prof' }, A.segnaposto(3));
    const materiale = h('div', { class: 'elenco ' + cm }, h('div', { class: 'riga' }, h('div', { class: 'segnaposto riga-finta' })));
    const zona = h('div', { class: 'zona-trascina', 'data-zona': 'materia' },
      icona('carica'), h('span', { class: 'titolo-zona' }, 'Trascina qui i file'),
      h('div', { class: 'azioni' },
        h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: () => scegliFile(m, '.pdf,.docx,.odt,.rtf,.txt,.md,.jpg,.jpeg,.png,.webp') }, 'Scegli il file'),
        h('button', { type: 'button', class: 'pulsante bianco', onclick: () => A.vai('#gemini') }, 'Testo per Gemini')));
    const prossime = h('div', { class: 'elenco' });
    const ripasso = h('div', { class: 'elenco' });
    const lampo = h('div', { 'data-lampo': 'materia' });
    const lezioni = h('div', { class: 'elenco', 'data-elenco': 'lezioni' });
    const schemi = h('div', { class: 'elenco nascosto', 'data-elenco': 'schemi' }, h('div', { class: 'riga' }, h('div', { class: 'segnaposto riga-finta' })));
    const simulate = h('div', { class: 'elenco nascosto', 'data-elenco': 'simulate' }, h('div', { class: 'riga' }, h('div', { class: 'segnaposto riga-finta' })));
    const binario = h('div', { class: 'segmenti binario', role: 'group', 'aria-label': 'Lezioni, schemi e verifiche simulate' });
    binario.style.setProperty('--n', '3');
    // §43.2 Il pulsante fisso in basso è sempre Nuovo, qualunque segmento sia aperto: apre "Cosa vuoi fare?".
    let elencoSchemi = null;
    const nuovo = h('button', {
      type: 'button', class: 'pulsante pieno medio', 'data-azione': 'nuovo', 'data-visita': 'nuovo',
      onclick: () => { if (A.visitaEvento) A.visitaEvento('nuovo'); A.apriNuovo(A.materiaDi(id) || m, { schemi: elencoSchemi }); },
    }, icona('piu'), 'Nuovo');
    const fisso = h('div', { class: 'fondo-fisso' }, nuovo);
    function aggiornaNuovo() {
      nuovo.disabled = L.nuovoSpento({ senzaRete: !!S.senzaRete, schedeSalvate: false });
    }
    // Si parte da Lezioni; da un avviso dal segmento giusto; tornando indietro da dov'eri (§31.2). Il binario è solo
    // per guardare quello che c'è (§43.1).
    let segmento = rotta.segmento || (S.verso === 'indietro' && S.segmentoAperto && S.segmentoAperto.id === id ? S.segmentoAperto.segmento : 'lezioni');
    const SEGMENTI = [
      { nome: 'lezioni', testo: 'Lezioni', box: lezioni },
      { nome: 'schemi', testo: 'Schemi', box: schemi },
      { nome: 'simulate', testo: 'Simulate', box: simulate },
    ];
    function disegnaSegmento() {
      S.segmentoAperto = { id, segmento };
      svuota(binario, SEGMENTI.map((x) => h('button', {
        type: 'button', 'aria-pressed': x.nome === segmento ? 'true' : 'false', 'data-segmento': x.nome,
        onclick: () => { if (A.visitaEvento) A.visitaEvento('segmento-' + x.nome); if (segmento !== x.nome) { segmento = x.nome; disegnaSegmento(); } },
      }, x.testo)));
      const s = SEGMENTI.find((x) => x.nome === segmento);
      SEGMENTI.forEach((x) => x.box.classList.toggle('nascosto', x !== s));
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }
    // §43.1 Prima fila: Cosa chiede il prof | Prossime e sotto Da ripassare. Seconda: il binario a tutta larghezza.
    // Terza: Materiale | Trascina qui i file. Sotto i 900 px una colonna sola, nello stesso ordine del telefono.
    const pag = h('div', { class: 'pagina pagina-materia' },
      h('div', { class: 'griglia-materia', 'data-fila': 'prof' }, scheda,
        h('div', { class: 'colonna-destra' },
          h('div', { class: 'sezione', 'data-sezione': 'prossime' }, h('div', { class: 'testa-sezione' }, h('h2', { class: 'titolo-sezione' }, 'Prossime'),
            h('button', { type: 'button', class: 'pulsante testo', onclick: () => apriAggiungiData(m) }, 'Aggiungi data')), prossime),
          h('div', { class: 'sezione', 'data-sezione': 'ripasso' }, h('div', { class: 'testa-sezione' }, h('h2', { class: 'titolo-sezione' }, 'Da ripassare')), h('div', { class: 'colonna-ripasso' }, lampo, ripasso)))),
      h('div', { class: 'sezione', 'data-sezione': 'binario' }, binario, lezioni, schemi, simulate),
      h('div', { class: 'griglia-materia', 'data-fila': 'materiale' },
        h('div', { class: 'sezione', 'data-sezione': 'materiale' }, h('div', { class: 'testa-sezione' }, h('h2', { class: 'titolo-sezione' }, 'Materiale'),
          h('button', { type: 'button', class: 'pulsante tenue piccolo', 'data-azione': 'aggiungi-materiale', 'data-visita': 'aggiungi', onclick: () => { A.apriAggiungi(m); if (A.visitaEvento) A.visitaEvento('foglio-aggiungi'); } }, 'Aggiungi')), materiale),
        h('div', { class: 'colonna-zona' }, zona)),
      fisso);
    svuota(box, testata, pag);
    disegnaSegmento();
    aggiornaNuovo();

    // §43.1 La zona Trascina qui è alta quanto serve, ma non più dell'elenco: con un elenco corto si fa compatta.
    function adattaZona() {
      const alto = materiale.offsetHeight;
      zona.classList.toggle('bassa', alto > 0 && alto < 170);
    }

    function disegnaLampo() {
      const att = A.materiaDi(id) || m;
      const n = Number((att.conteggi || {}).lampo) || 0;
      svuota(lampo, A.schedaLampo(n, L.minutiLampo(n), () => { if (A.visitaEvento) A.visitaEvento('lampo-apri'); A.apriLampo({ materiaId: id }); }, 'Niente per oggi'));
      aggiornaNuovo();
    }
    let timerSchemi = null;
    let timerSimulate = null;
    async function caricaSchemi() {
      clearTimeout(timerSchemi);
      const r = await A.api.get('/server/api/schemi?materia_id=' + id);
      if (!vivo()) return;
      aggiornaNuovo();
      if (!r.ok) { svuota(schemi, A.erroreConRiprova(r, caricaSchemi)); return; }
      const lista = r.dati || [];
      elencoSchemi = lista;
      svuota(schemi, lista.length ? lista.map((x) => A.rigaSchema(x, m, caricaSchemi)) : h('div', { class: 'riga' }, h('span', { class: 'secondario', stile: { fontSize: '15px' } }, 'Nessuno schema ancora')));
      if (lista.some((x) => x.stato === 'in_coda' || x.stato === 'in_preparazione')) timerSchemi = setTimeout(() => { if (vivo()) caricaSchemi(); }, T.stato);
    }
    async function caricaSimulate() {
      clearTimeout(timerSimulate);
      const r = await A.api.get('/server/api/simulate?materia_id=' + id);
      if (!vivo()) return;
      if (!r.ok) { svuota(simulate, A.erroreConRiprova(r, caricaSimulate)); return; }
      const lista = r.dati || [];
      svuota(simulate, lista.length ? lista.map((x) => A.rigaSimulata(x, m, caricaSimulate)) : h('div', { class: 'riga' }, h('span', { class: 'secondario', stile: { fontSize: '15px' } }, 'Nessuna verifica simulata ancora')));
      if (lista.some((x) => ['in_coda', 'in_preparazione', 'in_correzione'].includes(x.stato))) timerSimulate = setTimeout(() => { if (vivo()) caricaSimulate(); }, T.stato);
    }

    async function caricaScheda() {
      const r = await A.api.get('/server/api/materie/' + id + '/scheda');
      if (!vivo()) return;
      if (!r.ok) { svuota(scheda, h('h2', { class: 'titolo-sezione' }, 'Cosa chiede il prof'), A.erroreConRiprova(r, caricaScheda)); return; }
      disegnaScheda(scheda, r.dati, m, caricaScheda);
    }
    async function caricaMateriale() {
      const [r, rc] = await Promise.all([A.api.get('/server/api/materie/' + id + '/materiale'), A.cartelle.carica(id)]);
      if (!vivo()) return;
      if (!r.ok) { svuota(materiale, A.erroreConRiprova(r, caricaMateriale)); return; }
      // Senza le cartelle la pagina non saprebbe quali file stanno fuori: errore e Riprova (un server senza cartelle = nessuna).
      if (!rc.ok && rc.stato !== 404) { svuota(materiale, A.erroreConRiprova(rc, caricaMateriale)); return; }
      const lista = r.dati || [];
      const cartelle = rc.ok ? rc.dati : [];
      S.materialeAperto = { materiaId: id, lista, cartelle };
      // §42.1: prima le cartelle, poi "Nuova cartella" (sempre), poi il materiale fuori dalle cartelle.
      const fuori = L.materialeFuori(lista);
      svuota(materiale,
        cartelle.map((c) => A.rigaCartella(c, m)),
        A.rigaNuovaCartella(m),
        fuori.map((x) => rigaMateriale(x, m, caricaMateriale)),
        lista.length ? null : h('div', { class: 'riga' }, h('span', { class: 'secondario' }, 'Ancora niente: aggiungi una verifica corretta o gli appunti.')));
      adattaZona();
      if (lista.some((x) => x.stato === 'in_lettura')) ripeti(vivo, () => { caricaMateriale(); caricaScheda(); }, T.lettura);
    }
    async function caricaEventi() {
      const [r, rl] = await Promise.all([A.api.get('/server/api/eventi?da=' + L.iso()), A.api.get('/server/api/lezioni?materia_id=' + id)]);
      if (!vivo()) return;
      const lez = rl.ok ? rl.dati || [] : [];
      if (r.ok) {
        const futuri = (r.dati || []).filter((e) => e.materia_id === id && L.giorniFra(L.iso(), e.data) >= 0).sort((a, b) => a.data.localeCompare(b.data));
        svuota(prossime, futuri.length ? futuri.map((e) => {
          const l = lez.find((x) => x.id === e.lezione_id);
          const frase = l ? (l.stato === 'pronta' ? 'lezione pronta' : l.stato === 'errore' ? 'lezione non riuscita' : 'lezione in preparazione') : 'nessuna lezione';
          return h('div', { class: 'riga' }, h('div', { class: 'testi' }, h('span', { class: 'forte' }, titoloEvento(e, m)), h('span', { class: 'secondario' }, L.dataLunga(e.data) + ' · ' + frase)),
            h('span', { class: 'destra', stile: { color: 'var(--testo)', fontWeight: '700' } }, L.quando(L.giorniFra(L.iso(), e.data))));
        }) : h('div', { class: 'riga' }, h('span', { class: 'secondario' }, 'Nessuna data in arrivo')));
      } else svuota(prossime, A.erroreConRiprova(r, caricaEventi));
      svuota(lezioni, lez.length ? lez.map((l) => A.rigaLezione(l, { senzaMateria: true })) : h('div', { class: 'riga' }, h('span', { class: 'secondario' }, 'Nessuna lezione ancora')));
    }
    async function caricaRipasso() {
      const r = await A.api.get('/server/api/materie/' + id + '/da_ripassare');
      if (!vivo()) return;
      if (!r.ok) { svuota(ripasso, r.stato === 404 ? h('div', { class: 'riga' }, h('span', { class: 'secondario' }, 'Niente da ripassare')) : A.erroreConRiprova(r, caricaRipasso)); return; }
      const lista = r.dati || [];
      // §31.2 La sezione c'è sempre: senza domande resta la sola scheda del Ripasso lampo.
      ripasso.classList.toggle('nascosto', !lista.length);
      svuota(ripasso, lista.length ? lista.slice(0, 8).map((x) => {
        const e = L.esitoRisposta(x.esito);
        return h('button', { type: 'button', class: 'riga riga-ripasso', onclick: () => A.apriDomanda(x.lezione_id, x.domanda_id) },
          h('span', { class: 'testi' }, h('span', { class: 'forte', stile: { fontSize: '15px' } }, x.testo), h('span', { class: 'secondario', stile: { fontSize: '13px' } }, (x.lezione || '') + (x.quando ? ' · ' + L.quando(L.giorniFra(L.iso(), x.quando)) : ''))),
          h('span', { class: 'esito ' + e.classe }, e.parola));
      }) : null);
    }
    S.aggiornaPagina = () => { caricaMateriale(); caricaScheda(); caricaEventi(); caricaRipasso(); caricaSchemi(); caricaSimulate(); A.caricaMaterie().then(() => { if (vivo()) disegnaLampo(); }); };
    disegnaLampo();
    await Promise.all([caricaScheda(), caricaMateriale(), caricaEventi(), caricaRipasso(), caricaSchemi(), caricaSimulate(), A.caricaMaterie().then(() => { if (vivo()) disegnaLampo(); })]);
    if (!vivo()) return;
    aggiornaNuovo();
    adattaZona();
    if (A.visitaDopoDisegno) A.visitaDopoDisegno();
  };

  function disegnaScheda(box, sc, m, ricarica) {
    const testa = h('div', { class: 'testa-sezione' }, h('h2', { class: 'titolo-sezione' }, 'Cosa chiede il prof'),
      h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'modifica-scheda', onclick: () => A.vai('#materia/' + m.id + '/scheda') }, 'Modifica'));
    const parti = [testa];
    if (!sc || sc.stato === 'vuota') {
      parti.push(h('p', { class: 'secondario', stile: { fontSize: '15px' } }, 'Ancora vuota: la scrivo io quando aggiungi la prima verifica corretta.'));
    } else {
      if (sc.stato === 'in_aggiornamento') parti.push(h('div', { class: 'messaggio info' }, h('div', null, h('span', null, 'Sto aggiornando la scheda con le verifiche nuove.'))));
      if (sc.stato === 'errore') {
        parti.push(A.messaggio('errore', sc.errore || 'La scheda non si è aggiornata.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: async () => { const r = await A.api.post('/server/api/materie/' + m.id + '/scheda/aggiorna', {}); if (!r.ok) A.avviso(r.errore); ricarica(); } }, 'Riprova')]));
      }
      if ((sc.argomenti || []).length) {
        parti.push(h('div', { class: 'pillole' }, sc.argomenti.map((a) => h('span', { class: 'pillola argomento', title: a.esempio || null }, a.nome + (a.volte ? ' · ' + a.volte : '')))));
      }
      const tipi = (sc.tipi_domanda || []).filter((t) => t.quota > 0).sort((a, b) => b.quota - a.quota);
      if (tipi.length) {
        const divisa = h('div', { class: 'divisa', 'aria-hidden': 'true' }, tipi.map(() => h('span', { stile: { width: '0%' } })));
        requestAnimationFrame(() => tipi.forEach((t, i) => { divisa.children[i].style.width = Math.round(t.quota * 100) + '%'; }));
        parti.push(h('div', { class: 'tipi-domanda' }, divisa,
          h('div', { class: 'legenda' }, tipi.map((t) => h('span', null, (L.NOMI_TIPI_DOMANDA[t.tipo] || t.tipo) + ' ' + Math.round(t.quota * 100) + '%')))));
      }
      const lista = (titolo, voci) => (voci && voci.length ? h('div', { class: 'elenco-breve' }, h('span', { class: 'etichetta' }, titolo), voci.map((v) => h('span', null, v))) : null);
      parti.push(lista('Cosa corregge', sc.cosa_corregge), lista('Cosa premia', sc.cosa_premia));
      if (sc.lunghezza_risposte) parti.push(h('div', { class: 'elenco-breve' }, h('span', { class: 'etichetta' }, 'Quanto scrivere'), h('span', null, sc.lunghezza_risposte)));
      const base = L.basataSu(sc.basata_su);
      if (base) parti.push(h('span', { class: 'moltotenue' }, base));
    }
    if (sc && sc.note_mie && sc.note_mie.trim()) parti.push(h('div', { class: 'elenco-breve' }, h('span', { class: 'etichetta' }, 'Le tue note'), h('div', { class: 'note-mie' }, sc.note_mie)));
    svuota(box, parti);
  }

  /** Una riga del materiale (§4). Nella pagina di una cartella (§42.2) si dice se ce l'ha messo Lectio. */
  function rigaMateriale(x, m, ricarica) {
    const daLectio = x.cartella_id != null && x.cartella_da === 'lectio' ? ' · messo qui da Lectio' : '';
    const sotto = (L.NOMI_TIPO_MATERIALE[x.tipo] || 'Materiale') + (x.data ? ' · ' + L.dataBreve(x.data) : '') + daLectio;
    if (x.stato === 'in_lettura') {
      return h('div', { class: 'riga riga-materiale in-lettura nuovo' }, h('span', { class: 'segnaposto', 'aria-hidden': 'true' }),
        h('div', { class: 'testi' }, h('span', { class: 'forte' }, x.titolo || x.nome_file || 'File'), h('span', { class: 'secondario' }, 'Lo sto leggendo…')));
    }
    if (x.stato === 'errore') {
      return h('div', { class: 'riga riga-materiale' }, icona(iconaTipo(x.tipo), 'icona-tipo'),
        h('div', { class: 'testi' }, h('span', { class: 'forte' }, x.titolo || x.nome_file || 'File'), h('span', { class: 'errore-campo' }, x.errore || 'Non sono riuscito a leggerlo.')),
        h('button', { type: 'button', class: 'pulsante bianco', onclick: async () => { const r = await A.api.post('/server/api/materiale/' + x.id + '/riprova', {}); if (!r.ok) A.avviso(r.errore); ricarica(); } }, 'Riprova'),
        h('button', { type: 'button', class: 'tondo', 'aria-label': 'Elimina', onclick: () => eliminaMateriale(x, ricarica) }, icona('cestino')));
    }
    return h('button', { type: 'button', class: 'riga riga-materiale', 'data-materiale': String(x.id), onclick: () => A.apriMateriale(x, m, ricarica) }, icona(iconaTipo(x.tipo), 'icona-tipo'),
      h('span', { class: 'testi' }, h('span', { class: 'forte' }, x.titolo || x.nome_file || 'Materiale'), h('span', { class: 'secondario' }, sotto),
        // §15.5: il server dice se ha letto solo una parte (per esempio le prime pagine).
        x.avviso ? h('span', { class: 'avviso-materiale', 'data-avviso': 'materiale' }, x.avviso) : null));
  }
  A.rigaMateriale = rigaMateriale;

  async function eliminaMateriale(x, ricarica) {
    const ok = await A.conferma({ titolo: 'Eliminare "' + (x.titolo || 'questo materiale') + '"?', testo: 'Le prossime lezioni non lo useranno più.', si: 'Elimina', pericolo: true });
    if (!ok) return false;
    const r = await A.api.elimina('/server/api/materiale/' + x.id);
    if (!r.ok) { A.avviso(r.errore); return false; }
    A.avviso('Eliminato.');
    if (ricarica) ricarica();
    return true;
  }

  /**
   * "Che cos'è" (§16.4): due famiglie, Da studiare (Appunti, Libro) e Già corretta dal prof (Verifica,
   * Interrogazione). La famiglia scelta ha il bordo accento; si sceglie con le pillole.
   */
  A.cheCosE = function (tipo, alCambio) {
    const box = h('div', { class: 'famiglie', role: 'group', 'aria-label': 'Che cos\'è' });
    function disegna(t) {
      const fam = L.famigliaDi(t);
      svuota(box, L.FAMIGLIE_MATERIALE.map((f) => h('div', { class: 'famiglia' + (f === fam ? ' scelta' : ''), 'data-famiglia': f.chiave },
        h('span', { class: 'n' }, f.nome), h('span', { class: 'f' }, f.frase),
        h('div', { class: 'pp' }, f.tipi.map((x) => h('button', {
          type: 'button', class: 'nl-pillola', 'aria-pressed': x.tipo === t ? 'true' : 'false', 'data-tipo-materiale': x.tipo,
          onclick: () => { disegna(x.tipo); if (alCambio) alCambio(x.tipo); },
        }, x.nome))))));
    }
    disegna(tipo);
    return box;
  };

  /**
   * Un materiale (§5.1, prototipo MaterialePc): titolo che si cambia, da dove viene, Che cos'è, quello che il server
   * ha letto (per controllare cosa ha capito), Salva (PATCH con i soli campi cambiati) ed Elimina con conferma.
   */
  A.apriMateriale = function (x, m, ricarica) {
    let salvato = Object.assign({}, x);
    const st = { tipo: x.tipo, cartella: x.cartella_id == null ? null : x.cartella_id, invio: false, tutto: false, timer: null };
    const materiaId = x.materia_id || (m && m.id);
    // §42.6: CARTELLA (Fuori e una pillola per cartella), solo se la materia ha almeno una cartella.
    const cartellaBox = h('div', { class: 'campo-gruppo', 'data-campo': 'cartella', stile: { display: 'none' } });
    function disegnaCartella() {
      const lista = A.cartelle.lista(materiaId) || [];
      if (!lista.length) { svuota(cartellaBox); cartellaBox.style.display = 'none'; return; }
      cartellaBox.style.display = '';
      const daLectio = salvato.cartella_da === 'lectio' && salvato.cartella_id != null && st.cartella === salvato.cartella_id;
      svuota(cartellaBox, h('span', { class: 'etichetta' }, 'Cartella'),
        A.pilloleCartella(lista, st.cartella, 'Fuori', (id) => { st.cartella = id; torna(); disegnaCartella(); }),
        daLectio ? h('span', { class: 'mat-nota', 'data-nota': 'cartella' }, 'Messo qui da Lectio') : null);
    }
    const titolo = h('input', { class: 'mat-titolo', id: 'mat-titolo', maxlength: '120', value: x.titolo || '', placeholder: 'Scrivi un titolo', autocomplete: 'off' });
    const nota = h('span', { class: 'mat-nota', 'data-nota': 'titolo' });
    const erroreTitolo = h('span', { class: 'errore-campo', role: 'alert' });
    const letto = h('div', { class: 'letto', 'data-letto': 'si', 'aria-busy': 'true' }, h('div', { class: 'segnaposto riga-finta' }), h('div', { class: 'segnaposto riga-finta', stile: { width: '45%' } }));
    const esito = h('div', { class: 'mat-esito' });
    const salva = h('button', { type: 'button', class: 'pulsante pieno mat-salva', 'data-azione': 'salva-materiale' }, 'Salva');
    const elimina = h('button', { type: 'button', class: 'pulsante testo elimina', 'data-azione': 'elimina-materiale' }, 'Elimina');
    const famiglie = A.cheCosE(x.tipo, (t) => { st.tipo = t; torna(); });

    function disegnaNota() {
      const n = L.notaTitolo(salvato);
      nota.textContent = n || '';
      nota.style.display = n ? '' : 'none';
    }
    /** Dopo una modifica il pulsante torna "Salva". */
    function torna() {
      clearTimeout(st.timer);
      salva.classList.remove('fatto');
      svuota(salva, 'Salva');
      erroreTitolo.textContent = '';
    }
    function salvatoPer2s() {
      clearTimeout(st.timer);
      salva.classList.add('fatto');
      svuota(salva, icona('spunta'), 'Salvato');
      st.timer = setTimeout(() => { if (salva.isConnected) torna(); }, T.copiato);
    }
    async function salvaOra() {
      if (st.invio) return;
      if (!L.pulisciTitolo(titolo.value)) { erroreTitolo.textContent = 'Scrivi un titolo.'; titolo.focus(); return; }
      const campi = L.campiCambiati(salvato, { titolo: titolo.value, tipo: st.tipo, cartella_id: st.cartella });
      svuota(esito);
      if (!Object.keys(campi).length) { salvatoPer2s(); return; }
      st.invio = true;
      salva.disabled = true;
      const r = await A.api.patch('/server/api/materiale/' + x.id, campi);
      st.invio = false;
      salva.disabled = false;
      if (!r.ok) {
        if (r.dati && r.dati.campo === 'titolo') erroreTitolo.textContent = r.errore;
        else svuota(esito, A.messaggio('errore', r.errore));
        return;
      }
      salvato = Object.assign({}, salvato, campi, r.dati || {});
      titolo.value = salvato.titolo || '';
      st.cartella = salvato.cartella_id == null ? null : salvato.cartella_id;
      disegnaNota();
      disegnaCartella();
      salvatoPer2s();
      // Un tipo cambiato da o verso verifica rifà la scheda del prof: si ridisegna tutta la materia.
      if (campi.tipo && S.aggiornaPagina) S.aggiornaPagina();
      else if (ricarica) ricarica();
    }
    titolo.addEventListener('input', torna);
    titolo.addEventListener('keydown', (e) => { if (e.key === 'Enter') salvaOra(); });
    salva.addEventListener('click', salvaOra);

    const ctl = A.apriFinestra({
      etichetta: x.titolo || 'Materiale',
      classe: 'finestra-materiale ' + A.classeMateria(m && m.colore),
      suChiusura: () => clearTimeout(st.timer),
      disegna: (f, c) => svuota(f, h('div', { class: 'mat-corpo' },
        h('div', { class: 'mat-testa' }, A.pillolaMateria(m), h('span', { class: 'spazio' }),
          h('button', { type: 'button', class: 'tondo', 'aria-label': 'Chiudi', onclick: () => c.chiudi() }, icona('chiudi'))),
        h('label', { class: 'campo-gruppo', for: 'mat-titolo' }, h('span', { class: 'etichetta' }, 'Titolo'), titolo, nota, erroreTitolo),
        h('div', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Che cos\'è'), famiglie),
        cartellaBox,
        letto,
        esito,
        h('div', { class: 'mat-piede' }, salva, h('span', { class: 'spazio' }), elimina))),
    });
    ctl.tipo = 'materiale';
    disegnaNota();
    disegnaCartella();
    if (materiaId) A.cartelle.carica(materiaId).then(() => { if (!ctl.chiuso) disegnaCartella(); });
    elimina.addEventListener('click', async () => { if (await eliminaMateriale(salvato, ricarica)) ctl.chiudi(); });

    async function caricaTesto() {
      const r = await A.api.get('/server/api/materiale/' + x.id);
      if (ctl.chiuso) return;
      letto.removeAttribute('aria-busy');
      if (!r.ok || !r.dati) { svuota(letto, A.erroreConRiprova(r, caricaTesto)); return; }
      const testo = String(r.dati.testo || '');
      const caratteri = r.dati.caratteri != null ? r.dati.caratteri : testo.length;
      const corto = testo.length <= 360;
      const corpo = h('div', { class: 'testo', 'data-testo': 'letto' });
      const mostra = corto ? null : h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'mostra-tutto' });
      const dis = () => {
        corpo.textContent = corto || st.tutto ? testo : testo.slice(0, 360).trimEnd() + '…';
        corpo.classList.toggle('tutto', st.tutto);
        if (mostra) mostra.textContent = st.tutto ? 'Mostra meno' : 'Mostra tutto';
      };
      if (mostra) mostra.addEventListener('click', () => { st.tutto = !st.tutto; dis(); });
      dis();
      const avviso = r.dati.avviso || x.avviso;
      svuota(letto,
        h('span', { class: 'etichetta' }, 'Cosa ho letto · ' + L.migliaia(caratteri) + (caratteri === 1 ? ' carattere' : ' caratteri')),
        avviso ? A.messaggio('avviso', avviso) : null,
        testo ? corpo : h('span', { class: 'secondario' }, 'Non ho letto niente in questo file.'),
        mostra);
    }
    caricaTesto();
    return ctl;
  };

  function apriAggiungiData(m) {
    A.apriFoglio({
      titolo: 'Aggiungi data',
      corpo: (c, ctl) => {
        let tipo = 'scritta';
        const seg = h('div', { class: 'segmenti', stile: { '--n': '2' } });
        const dis = () => svuota(seg, [['scritta', 'Verifica scritta'], ['orale', 'Interrogazione']].map(([k, n]) => h('button', { type: 'button', 'aria-pressed': tipo === k ? 'true' : 'false', onclick: () => { tipo = k; dis(); } }, n)));
        seg.style.setProperty('--n', '2');
        dis();
        const arg = h('input', { class: 'campo', maxlength: '120', placeholder: 'Per esempio L\'Impero romano', 'data-fuoco': true });
        const data = h('input', { class: 'campo', type: 'date', value: L.iso(new Date(Date.now() + 2 * 86400000)) });
        const esito = h('div');
        const salva = h('button', { type: 'button', class: 'pulsante pieno largo' }, 'Aggiungi');
        salva.addEventListener('click', async () => {
          if (!data.value) { svuota(esito, A.messaggio('errore', 'Scegli il giorno.')); return; }
          salva.disabled = true;
          const r = await A.api.post('/server/api/materie/' + m.id + '/eventi', { tipo, argomento: arg.value.trim(), data: data.value });
          salva.disabled = false;
          if (!r.ok) { svuota(esito, A.messaggio('errore', r.errore)); return; }
          ctl.chiudi();
          if (S.aggiornaPagina) S.aggiornaPagina();
        });
        svuota(c, seg, h('label', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Argomento'), arg),
          h('label', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Quando'), data), esito, salva);
      },
    });
  }

  // ================================================================== Aggiungi materiale (§5) e file

  /** Sceglie i file dal computer. `cartellaId` = la cartella già scelta (Aggiungi aperto da una cartella, §42.6). */
  function scegliFile(m, accetta, tipo, cartellaId) {
    const input = h('input', { type: 'file', accept: accetta, multiple: true, class: 'sr' });
    input.addEventListener('change', () => {
      const file = Array.from(input.files || []);
      input.remove();
      if (file.length) A.flussoFile(file, { materia: m, tipo, cartellaId });
    });
    document.body.append(input);
    input.click();
  }
  A.scegliFile = scegliFile;

  /**
   * Il foglio "Aggiungi a Storia" (§5): Testo per Gemini, Trascina qui, File, Appunti, Interrogazione orale, Foto.
   * `opzioni.cartellaId`: aperto dalla pagina di una cartella, quella cartella è già scelta (§42.6).
   */
  A.apriAggiungi = function (m, opzioni) {
    const o = opzioni || {};
    const ctl = A.apriFoglio({
      titolo: 'Aggiungi a ' + m.nome,
      corpo: (c, ctl2) => {
        const riga = (ic, titolo, sotto, fai, dati) => h('button', Object.assign({ type: 'button', class: 'riga-foglio', onclick: fai }, dati || {}), icona(ic),
          sotto ? h('span', { class: 'testi' }, h('span', null, titolo), h('span', { class: 'secondario' }, sotto)) : titolo);
        svuota(c,
          h('button', { type: 'button', class: 'riga-gemini', 'data-visita': 'gemini-riga', 'data-fuoco': true, onclick: () => { ctl2.chiudi(); A.vai('#gemini'); } }, icona('gemini'), h('span', null, 'Testo per Gemini'), icona('freccia')),
          h('div', { class: 'zona-trascina compatta', 'data-visita': 'trascina-foglio' }, h('span', { class: 'titolo-zona' }, 'Trascina qui il file')),
          h('div', null,
            riga('file', 'File', null, () => scegliFile(m, '.pdf,.docx,.odt,.rtf,.txt,.md', o.tipo, o.cartellaId), { 'data-riga': 'file' }),
            riga('appunti', 'Appunti', null, () => scritto(ctl2, m, 'appunti', o.cartellaId), { 'data-riga': 'appunti' }),
            riga('interrogazione', 'Interrogazione orale', null, () => scritto(ctl2, m, 'interrogazione', o.cartellaId), { 'data-riga': 'orale' }),
            riga('foto', 'Foto', 'circa 0,4 cent a foto', () => scegliFile(m, '.jpg,.jpeg,.png,.webp', o.tipo, o.cartellaId), { 'data-riga': 'foto' })));
      },
    });
    ctl.accettaFile = true;
    ctl.riceviFile = (file) => { ctl.chiudi(true); A.flussoFile(file, { materia: m, tipo: o.tipo, cartellaId: o.cartellaId }); };
    // Le cartelle della materia: servono dopo, per la scelta CARTELLA.
    A.cartelle.carica(m.id);
    return ctl;
  };

  /**
   * CARTELLA nei fogli di caricamento (§42.6): Decide Lectio (null: non si manda cartella_id) e una pillola per cartella.
   * Solo se la materia ha almeno una cartella; le cartelle arrivano anche dopo (si ridisegna).
   */
  function sceltaCartellaCarica(st, materiaDi) {
    const box = h('div', { class: 'campo-gruppo', 'data-campo': 'cartella' });
    let materiaVista = null;
    function disegna() {
      const m = materiaDi();
      const nota = m ? A.cartelle.lista(m.id) : null;
      const lista = nota || [];
      // Una cartella scelta che non c'è (più, o è di un'altra materia) torna a Decide Lectio.
      if (nota && st.cartellaId != null && !nota.some((c) => c.id === st.cartellaId)) st.cartellaId = null;
      if (!lista.length) { svuota(box); box.style.display = 'none'; return; }
      box.style.display = '';
      svuota(box, h('span', { class: 'etichetta' }, 'Cartella'), A.pilloleCartella(lista, st.cartellaId, 'Decide Lectio', (id) => { st.cartellaId = id; disegna(); }));
    }
    function aggiorna() {
      const m = materiaDi();
      disegna();
      if (m && m.id !== materiaVista) {
        materiaVista = m.id;
        A.cartelle.carica(m.id).then(disegna);
      }
    }
    aggiorna();
    return { el: box, aggiorna };
  }

  /** Appunti scritti o interrogazione orale raccontata: testo → POST JSON (§6), con la cartella se scelta (§42.6). */
  function scritto(ctl, m, tipo, cartellaId) {
    ctl.titolo(tipo === 'appunti' ? 'Appunti di ' + m.nome : 'Interrogazione orale');
    const titolo = h('input', { class: 'campo', maxlength: '120', value: '', placeholder: 'Lo scrive Lectio' });
    const data = h('input', { class: 'campo', type: 'date', value: L.iso() });
    const esito = h('div');
    const st = { cartellaId: cartellaId == null ? null : cartellaId };
    const cartella = sceltaCartellaCarica(st, () => m);
    const area = (ph, id) => h('textarea', { class: 'campo', placeholder: ph, id, rows: '4' });
    const campi = tipo === 'appunti'
      ? [area('Scrivi o incolla gli appunti', 'testo-appunti')]
      : [area('Cosa ti ha chiesto', 'orale-chiesto'), area('Cosa hai risposto', 'orale-risposto'), area('Cosa ha detto il prof', 'orale-detto')];
    const aggiungi = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'aggiungi-scritto' }, 'Aggiungi');
    aggiungi.addEventListener('click', async () => {
      const testo = tipo === 'appunti' ? campi[0].value.trim() : L.testoInterrogazione(campi[0].value, campi[1].value, campi[2].value);
      if (!testo) { svuota(esito, A.messaggio('errore', 'Scrivi qualcosa prima di aggiungere.')); return; }
      aggiungi.disabled = true;
      // §16.3: il titolo si manda solo se Andrea l'ha scritto; altrimenti lo sceglie Lectio leggendo.
      const corpo = { tipo, data: data.value || null, testo };
      if (L.pulisciTitolo(titolo.value)) corpo.titolo = L.pulisciTitolo(titolo.value);
      // §27: con la cartella scelta va lì (numero nel JSON); con Decide Lectio non si manda.
      if (st.cartellaId != null) corpo.cartella_id = st.cartellaId;
      const r = await A.api.post('/server/api/materie/' + m.id + '/materiale', corpo);
      aggiungi.disabled = false;
      if (!r.ok) { svuota(esito, A.messaggio('errore', r.errore)); return; }
      ctl.chiudi();
      A.avviso('Aggiunto a ' + m.nome + '.');
      if (S.aggiornaPagina) S.aggiornaPagina();
    });
    svuota(ctl.corpo, h('label', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Titolo'), titolo),
      h('label', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Data'), data), cartella.el, campi, esito, aggiungi);
    setTimeout(() => campi[0].focus(), 30);
  }

  /** I byte di un file scelto o trascinato (ArrayBuffer: arriva intero al programma). */
  function leggiByte(f) {
    if (typeof f.arrayBuffer === 'function') return f.arrayBuffer();
    return new Promise((ok, no) => {
      const r = new FileReader();
      r.onload = () => ok(r.result);
      r.onerror = () => no(r.error);
      r.readAsArrayBuffer(f);
    });
  }

  /** I file trascinati: nel foglio aperto, nella materia aperta, o in Per Gemini (si chiede la materia). */
  A.suFileTrascinati = function (file) {
    const sopra = S.velo[S.velo.length - 1];
    if (sopra && sopra.riceviFile) { sopra.riceviFile(file); return; }
    const v = S.rotta.vista;
    if ((v === 'materia' || v === 'scheda') && A.materiaDi(S.rotta.id)) { A.flussoFile(file, { materia: A.materiaDi(S.rotta.id) }); return; }
    // Nella pagina di una cartella i file vanno in quella cartella (si può cambiare nel foglio).
    if (v === 'cartella' && A.materiaDi(S.rotta.id)) { A.flussoFile(file, { materia: A.materiaDi(S.rotta.id), cartellaId: S.rotta.cartella }); return; }
    A.flussoFile(file, { tipo: 'verifica' });
  };

  /**
   * Dopo la scelta dei file: in quale materia (se non si sa), Che cos'è (§16.4: due famiglie, si parte da Appunti),
   * titolo facoltativo (se resta vuoto lo scrive Lectio), data → Aggiungi. I file vanno al programma come byte e da lì
   * al server.
   */
  A.flussoFile = function (file, opzioni) {
    const o = opzioni || {};
    const problemi = file.map((f) => L.controllaFile(f.name, f.size)).filter(Boolean);
    const buoni = file.filter((f) => !L.controllaFile(f.name, f.size));
    if (!buoni.length) { A.avviso(problemi[0]); return null; }
    const st = { materia: o.materia || null, tipo: o.tipo || 'appunti', cartellaId: o.cartellaId == null ? null : o.cartellaId, invio: false };
    const ctl = A.apriFoglio({
      titolo: st.materia ? 'Aggiungi a ' + st.materia.nome : 'Manda il file a Lectio',
      corpo: (c, ctl2) => {
        const materie = h('div', { class: 'pillole', role: 'radiogroup', 'aria-label': 'In quale materia?' });
        const foto = buoni.filter((f) => L.eImmagine(f.name)).length;
        const tipi = A.cheCosE(st.tipo, (t) => { st.tipo = t; });
        // §42.6: sotto "Che cos'è" la CARTELLA; con più file vale per tutti.
        const cartella = sceltaCartellaCarica(st, () => st.materia);
        const titolo = buoni.length === 1 ? h('input', { class: 'campo', id: 'carica-titolo', maxlength: '120', value: '', placeholder: foto ? 'Lo scrive Lectio leggendo la foto' : 'Lo scrive Lectio leggendo il file' }) : null;
        const data = h('input', { class: 'campo', type: 'date', value: L.iso() });
        const esito = h('div');
        const aggiungi = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'aggiungi-file' });
        function dis() {
          svuota(materie, (S.materie || []).map((m) => h('button', {
            type: 'button', class: 'pillola scelta', role: 'radio', 'aria-pressed': st.materia && st.materia.id === m.id ? 'true' : 'false', 'aria-checked': st.materia && st.materia.id === m.id ? 'true' : 'false',
            onclick: () => { st.materia = m; ctl2.titolo('Aggiungi a ' + m.nome); dis(); },
          }, m.nome)));
          aggiungi.textContent = st.invio ? 'Mando il file…' : buoni.length > 1 ? 'Aggiungi ' + buoni.length + ' file' : 'Aggiungi';
          aggiungi.disabled = !st.materia || st.invio || S.senzaRete;
          cartella.aggiorna();
        }
        aggiungi.addEventListener('click', async () => {
          if (!st.materia) return;
          st.invio = true;
          dis();
          svuota(esito);
          let andati = 0;
          for (const f of buoni) {
            let byte;
            try { byte = await leggiByte(f); } catch (e) { byte = null; }
            if (!byte) { svuota(esito, A.messaggio('errore', 'Non riesco a leggere "' + f.name + '" dal computer.')); break; }
            // §16.3: niente titolo se Andrea non l'ha scritto: lo scrive Lectio leggendo il file.
            const scritto = titolo ? L.pulisciTitolo(titolo.value) : '';
            const r = await A.api.carica(byte, { materia_id: st.materia.id, tipo: st.tipo, titolo: scritto || undefined, data: data.value || '', nome: f.name, cartella_id: st.cartellaId == null ? undefined : st.cartellaId });
            if (!r.ok) {
              svuota(esito, A.messaggio('errore', (buoni.length > 1 ? f.name + ': ' : '') + r.errore, [h('button', { type: 'button', class: 'pulsante bianco', onclick: () => aggiungi.click() }, 'Riprova')]));
              break;
            }
            andati++;
          }
          st.invio = false;
          dis();
          if (andati === buoni.length) {
            ctl2.chiudi();
            A.avviso(andati > 1 ? andati + ' file aggiunti a ' + st.materia.nome + '.' : 'Aggiunto a ' + st.materia.nome + '. Lo sto leggendo.');
            if ((S.rotta.vista === 'materia' || S.rotta.vista === 'cartella') && S.rotta.id === st.materia.id && S.aggiornaPagina) S.aggiornaPagina();
            else if (S.rotta.vista !== 'materia') A.vai('#materia/' + st.materia.id);
          }
        });
        svuota(c,
          h('div', { class: 'secondario' }, buoni.map((f) => f.name).join(', ')),
          problemi.length ? A.messaggio('avviso', problemi[0]) : null,
          st.materia ? null : h('div', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'In quale materia?'), (S.materie || []).length ? materie : h('span', { class: 'secondario' }, 'Prima aggiungi una materia.')),
          h('div', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Che cos\'è'), tipi),
          cartella.el,
          titolo ? h('label', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Titolo'), titolo) : null,
          h('label', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, 'Data'), data),
          foto ? h('span', { class: 'moltotenue' }, 'Le foto si leggono a pagamento: circa 0,4 cent a foto.') : null,
          S.senzaRete ? A.messaggio('avviso', 'Senza rete i file non arrivano al server di casa.') : null,
          esito, aggiungi);
        dis();
      },
    });
    ctl.accettaFile = true;
    ctl.riceviFile = (altri) => { ctl.chiudi(true); A.flussoFile(altri, o); };
    return ctl;
  };

  // ================================================================== La scheda del prof, modificabile (§4)

  A.pagine.scheda = async function (box, rotta, vivo) {
    const m = A.materiaDi(rotta.id) || (await A.caricaMaterie(), A.materiaDi(rotta.id));
    if (!vivo()) return;
    if (!m) { A.vai('#materie'); return; }
    const pag = h('div', { class: 'pagina ' + A.classeMateria(m.colore) }, A.segnaposto(5));
    svuota(box, pag);
    const r = await A.api.get('/server/api/materie/' + m.id + '/scheda');
    if (!vivo()) return;
    if (!r.ok) { svuota(pag, A.erroreConRiprova(r, () => A.disegna())); return; }
    const sc = r.dati || {};
    // Elenchi su più righe; le note sono un testo (anche vuoto: '' non ha join).
    const area = (valore, id, righe) => h('textarea', { class: 'campo', id, rows: String(righe || 4) }, Array.isArray(valore) ? valore.join('\n') : valore || '');
    const argomenti = area((sc.argomenti || []).map((a) => a.nome), 'sc-argomenti');
    const corregge = area(sc.cosa_corregge, 'sc-corregge');
    const premia = area(sc.cosa_premia, 'sc-premia');
    const lunghezza = h('input', { class: 'campo', id: 'sc-lunghezza', value: sc.lunghezza_risposte || '', maxlength: '200' });
    const consigli = area(sc.consigli, 'sc-consigli', 3);
    const note = area(sc.note_mie || '', 'sc-note', 4);
    const esito = h('div');
    const salva = h('button', { type: 'button', class: 'pulsante pieno', 'data-azione': 'salva-scheda' }, 'Salva');
    salva.addEventListener('click', async () => {
      const corpo = L.schedaDaMandare(sc, {
        argomenti: argomenti.value, corregge: corregge.value, premia: premia.value,
        lunghezza: lunghezza.value, consigli: consigli.value, note: note.value,
      });
      const problema = L.controllaScheda(corpo);
      if (problema) { svuota(esito, A.messaggio('errore', problema)); return; }
      svuota(esito);
      salva.disabled = true;
      const r2 = await A.api.put('/server/api/materie/' + m.id + '/scheda', corpo);
      salva.disabled = false;
      if (!r2.ok) { svuota(esito, A.messaggio('errore', r2.errore)); return; }
      A.avviso('Scheda salvata.');
      A.vai('#materia/' + m.id);
    });
    const campo = (etichetta, el) => h('label', { class: 'campo-gruppo' }, h('span', { class: 'etichetta' }, etichetta), el);
    svuota(pag,
      h('div', { class: 'testa-pagina' }, h('div', null,
        h('button', { type: 'button', class: 'pulsante testo', stile: { paddingLeft: '0', alignSelf: 'flex-start' }, onclick: () => A.vai('#materia/' + m.id) }, icona('indietro'), m.nome),
        h('h1', { class: 'titolo-pagina' }, 'Cosa chiede il prof'))),
      h('div', { class: 'griglia-materia su-fondo' },
        h('div', { class: 'colonna' }, campo('Argomenti che tornano', argomenti), campo('Cosa corregge', corregge), campo('Cosa premia', premia)),
        h('div', { class: 'colonna' }, campo('Quanto scrivere', lunghezza), campo('Consigli', consigli), campo('Le tue note', note))),
      esito,
      h('div', { class: 'azioni' }, salva,
        h('button', { type: 'button', class: 'pulsante testo', onclick: async () => {
          const r3 = await A.api.post('/server/api/materie/' + m.id + '/scheda/aggiorna', {});
          A.avviso(r3.ok ? 'Rifaccio la scheda dalle verifiche: ci vuole un minuto.' : r3.errore);
        } }, 'Rifalla dalle verifiche')));
  };

  // ================================================================== Lezioni (§10)

  /** Una riga di lezione: piastrella, titolo, materia, durata, stato, scaricata, menu. */
  A.rigaLezione = function (l, opzioni) {
    const o = opzioni || {};
    const m = A.materiaDi(l.materia_id) || { nome: '', colore: 'ambra' };
    const s = L.statoLezione(l);
    const scaricata = S.motore && (S.motore.scaricate || []).includes(l.id);
    let stato;
    if (s.tipo === 'meta') stato = h('span', { class: 'stato-lezione' }, A.barra(s.percentuale, 'sottile'), s.frase);
    else if (s.tipo === 'finita') stato = h('span', { class: 'stato-lezione finita-s' }, icona('spunta'), 'Finita');
    else if (s.tipo === 'preparazione' || s.tipo === 'coda') stato = h('span', { class: 'stato-lezione prep' }, s.frase);
    else if (s.tipo === 'errore') stato = h('span', { class: 'stato-lezione err' }, s.frase);
    else stato = h('span', { class: 'stato-lezione' }, s.frase);
    const apri = () => (l.stato === 'pronta' ? A.ascolta(l.id) : A.vai('#lezione/' + l.id));
    const menu = h('div', { class: 'menu-piccolo' });
    const tendina = () => {
      const esistente = menu.querySelector('.tendina');
      if (esistente) { esistente.remove(); return; }
      const voci = [];
      if (l.stato === 'pronta' && !scaricata) voci.push(h('button', { type: 'button', onclick: async () => { chiudi(); A.avviso('Scarico la lezione…'); const r = await A.api.post('/locale/lezioni/' + l.id + '/scarica', {}); A.avviso(r.ok ? 'Scaricata sul computer.' : r.errore); await A.aggiornaMotore(); if (S.aggiornaPagina) S.aggiornaPagina(); } }, 'Scarica'));
      if (scaricata) voci.push(h('button', { type: 'button', onclick: async () => { chiudi(); const r = await A.api.elimina('/locale/lezioni/' + l.id); A.avviso(r.ok ? 'Tolta dal computer.' : r.errore); await A.aggiornaMotore(); if (S.aggiornaPagina) S.aggiornaPagina(); } }, 'Togli dal computer'));
      voci.push(h('button', { type: 'button', class: 'pericolo', onclick: async () => {
        chiudi();
        if (!(await A.conferma({ titolo: 'Eliminare la lezione?', testo: '"' + l.argomento + '" sparisce, con il suo audio. I soldi già spesi restano nel conto del mese.', si: 'Elimina la lezione', pericolo: true }))) return;
        const r = await A.api.elimina('/server/api/lezioni/' + l.id);
        if (!r.ok) { A.avviso(r.errore); return; }
        await A.api.elimina('/locale/lezioni/' + l.id);
        if (A.lettoreChiudiSe) A.lettoreChiudiSe(l.id);
        if (S.aggiornaPagina) S.aggiornaPagina();
      } }, 'Elimina'));
      const t = h('div', { class: 'tendina', role: 'menu' }, voci);
      function chiudi() { t.remove(); document.removeEventListener('mousedown', fuori, true); }
      function fuori(e) { if (!menu.contains(e.target)) chiudi(); }
      document.addEventListener('mousedown', fuori, true);
      menu.append(t);
    };
    menu.append(h('button', { type: 'button', class: 'tondo', 'aria-label': 'Altre azioni', onclick: (e) => { e.stopPropagation(); tendina(); } }, icona('puntini')));
    return h('div', { class: 'riga riga-lezione-elenco cliccabile', 'data-lezione': String(l.id) },
      h('button', { type: 'button', stile: { display: 'flex', alignItems: 'center', gap: '12px', flex: '1 1 auto', minWidth: '0', border: 'none', background: 'none', padding: '0', textAlign: 'left', color: 'inherit' }, onclick: apri },
        A.piastrella(m.colore),
        h('span', { class: 'testi' }, h('span', { class: 'forte' }, l.argomento), h('span', { class: 'secondario' }, (o.senzaMateria ? '' : m.nome + ' · ') + l.durata_min + ' min'))),
      stato,
      scaricata ? h('span', { title: 'Scaricata sul computer', 'aria-label': 'Scaricata sul computer' }, icona('scarica', 'scaricata')) : h('span', { stile: { width: '18px' } }),
      menu);
  };

  A.pagine.lezioni = async function (box, rotta, vivo) {
    const filtro = h('div', { class: 'pillole' });
    const elenco = h('div', { class: 'elenco' }, h('div', { class: 'riga' }, h('div', { class: 'segnaposto riga-finta' })));
    svuota(box, h('div', { class: 'pagina' },
      testaPagina('Lezioni', null, h('button', { type: 'button', class: 'pulsante pieno medio', onclick: () => A.nuovaLezioneScegli() }, icona('piu'), 'Nuova lezione')),
      filtro, elenco));
    let scelta = rotta.materia || null;
    async function riempi() {
      const [r] = await Promise.all([A.api.get('/server/api/lezioni'), S.materie ? null : A.caricaMaterie(), A.aggiornaMotore()]);
      if (!vivo()) return;
      if (!r.ok) { svuota(elenco, A.erroreConRiprova(r, riempi)); return; }
      const tutte = r.dati || [];
      const presenti = (S.materie || []).filter((m) => tutte.some((l) => l.materia_id === m.id));
      svuota(filtro, presenti.length > 1 ? [h('button', { type: 'button', class: 'pillola scelta', 'aria-pressed': !scelta ? 'true' : 'false', onclick: () => { scelta = null; riempi(); } }, 'Tutte'),
        presenti.map((m) => h('button', { type: 'button', class: 'pillola scelta', 'aria-pressed': scelta === m.id ? 'true' : 'false', onclick: () => { scelta = m.id; riempi(); } }, m.nome))] : null);
      const lista = tutte.filter((l) => !scelta || l.materia_id === scelta).sort((a, b) => String(b.creata_il || '').localeCompare(String(a.creata_il || '')));
      if (!lista.length) {
        elenco.className = '';
        svuota(elenco, h('div', { class: 'vuoto' }, h('p', null, 'Nessuna lezione ancora'), h('button', { type: 'button', class: 'pulsante pieno', onclick: () => A.nuovaLezioneScegli() }, icona('piu'), 'Nuova lezione')));
      } else {
        elenco.className = 'elenco';
        svuota(elenco, lista.map((l) => A.rigaLezione(l)));
      }
      if (S.senzaRete) {
        const scaricate = (S.motore && S.motore.scaricate) || [];
        elenco.querySelectorAll('[data-lezione]').forEach((el) => { if (!scaricate.includes(Number(el.dataset.lezione))) el.style.opacity = '.5'; });
      }
      if (tutte.some((l) => l.stato === 'in_coda' || l.stato === 'in_preparazione')) ripeti(vivo, riempi, T.preparazione);
    }
    S.aggiornaPagina = riempi;
    await riempi();
  };

  // ================================================================== Per Gemini (§9)

  const CHIAVE_GEMINI = 'lectio.gemini';
  function leggiCopiaGemini() {
    try { return JSON.parse(localStorage.getItem(CHIAVE_GEMINI) || 'null'); } catch (e) { return null; }
  }
  function salvaCopiaGemini(d) {
    try { localStorage.setItem(CHIAVE_GEMINI, JSON.stringify(d)); } catch (e) { /* senza memoria del browser va bene lo stesso */ }
  }

  /** Copia negli appunti: prima la pagina, se non riesce il programma (C#). true se è andata. */
  A.copiaTesto = async function (testo) {
    if (S.prova) return true;
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(testo);
        return true;
      }
    } catch (e) { /* si prova dal programma */ }
    if (S.esempio) return false;
    const r = await A.api.post('/locale/copia', { testo });
    return r.ok;
  };

  A.pagine.gemini = async function (box, rotta, vivo) {
    const copia = leggiCopiaGemini();
    const testo = h('div', { class: 'testo-gemini', id: 'testo-gemini', tabindex: '0' }, copia ? copia.testo : '');
    const errore = h('div');
    const pulsante = h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'copia-gemini' }, 'Copia il testo per Gemini');
    let timer = null;
    pulsante.addEventListener('click', async () => {
      const t = testo.textContent;
      svuota(errore);
      if (!t) { svuota(errore, A.messaggio('errore', 'Il testo non è ancora arrivato dal server di casa. Riprova tra poco.')); return; }
      const ok = await A.copiaTesto(t);
      if (!ok) {
        svuota(errore, h('span', { class: 'errore-campo', role: 'alert' }, 'Non sono riuscito a copiarlo: seleziona il testo qui sotto e copialo con Ctrl+C.'));
        return;
      }
      clearTimeout(timer);
      pulsante.classList.add('fatto');
      svuota(pulsante, icona('spunta'), 'Copiato');
      timer = setTimeout(() => { pulsante.classList.remove('fatto'); svuota(pulsante, 'Copia il testo per Gemini'); }, T.copiato);
    });
    const passo = (n, titolo, ...contenuto) => h('div', { class: 'passo' }, h('div', { class: 'testa' }, h('span', { class: 'numero' + (n === 1 ? ' primo' : '') }, String(n)), h('span', { class: 'titolo' }, titolo)), contenuto);
    svuota(box, h('div', { class: 'pagina' },
      h('h1', { class: 'titolo-pagina' }, 'Per Gemini'),
      h('div', { class: 'griglia-gemini' },
        h('div', { class: 'scheda rilievo passi' },
          passo(1, 'Copia il testo', pulsante, errore),
          passo(2, 'Apri Gemini e manda la foto della verifica', h('button', { type: 'button', class: 'pulsante tenue alto', 'data-azione': 'apri-gemini', onclick: async () => {
            if (S.esempio) { A.avviso('Nell\'esempio Gemini non si apre.'); return; }
            const r = await A.api.post('/locale/apri-gemini', {});
            if (!r.ok) A.avviso(r.errore);
          } }, 'Apri Gemini', icona('esterno'))),
          passo(3, 'Manda il file a Lectio',
            h('div', { class: 'zona-trascina', 'data-zona': 'gemini' }, icona('carica'), h('span', { class: 'titolo-zona' }, 'Trascina qui il file'),
              h('button', { type: 'button', class: 'pulsante tenue piccolo', onclick: () => {
                const input = h('input', { type: 'file', accept: '.txt,.md,.pdf,.docx,.odt,.rtf,.jpg,.jpeg,.png,.webp', multiple: true, class: 'sr' });
                input.addEventListener('change', () => { const f = Array.from(input.files || []); input.remove(); if (f.length) A.flussoFile(f, { tipo: 'verifica' }); });
                document.body.append(input);
                input.click();
              } }, 'Scegli il file')))),
        h('div', { class: 'sezione' }, h('span', { class: 'etichetta' }, 'Il testo'), h('div', { class: 'scheda' }, testo)))));
    if (!S.materie) A.caricaMaterie();
    const r = await A.api.get('/server/api/gemini');
    if (!vivo()) return;
    if (r.ok && r.dati && r.dati.testo) {
      testo.textContent = r.dati.testo;
      if (!r.memoria) salvaCopiaGemini({ testo: r.dati.testo, aggiornato_il: r.dati.aggiornato_il || null });
    } else if (!copia) {
      svuota(errore, A.erroreConRiprova(r, () => A.disegna()));
    }
  };

  // ================================================================== Impostazioni (§10)

  A.pagine.impostazioni = async function (box, rotta, vivo) {
    const pag = h('div', { class: 'pagina' }, h('h1', { class: 'titolo-pagina' }, 'Impostazioni'), A.segnaposto(4));
    svuota(box, pag);
    const [ri] = await Promise.all([A.caricaImpostazioni(), A.aggiornaMotore(), A.caricaSpesa(), S.voci ? null : A.api.get('/server/api/voci').then((r) => { if (r.ok) S.voci = r.dati; })]);
    if (!vivo()) return;
    const imp = S.impostazioni || {};
    const mot = S.motore || {};

    async function cambia(campo, valore, dopo) {
      const prima = imp[campo];
      imp[campo] = valore;
      const r = await A.api.patch('/server/api/impostazioni', { [campo]: valore });
      if (!r.ok) {
        imp[campo] = prima;
        A.avviso(r.errore);
        A.disegna();
        return false;
      }
      if (r.dati) Object.assign(imp, r.dati);
      if (dopo) dopo();
      return true;
    }
    const segmenti = (valori, attuale, fai, etichetta) => {
      const s = h('div', { class: 'segmenti', role: 'group', 'aria-label': etichetta });
      s.style.setProperty('--n', String(valori.length));
      const dis = (att) => svuota(s, valori.map(([v, n]) => h('button', { type: 'button', 'aria-pressed': v === att ? 'true' : 'false', onclick: async () => { dis(v); if (!(await fai(v))) dis(att); } }, n)));
      dis(attuale);
      return s;
    };
    const voce = (nome, sotto, valore) => h('div', { class: 'voce-imp' }, h('div', { class: 'testi' }, h('span', { class: 'nome' }, nome), sotto ? h('span', { class: 'secondario' }, sotto) : null), valore ? h('div', { class: 'valore' }, valore) : null);
    const interruttore = (nome, campo, id) => {
      const input = h('input', { type: 'checkbox', id, checked: imp[campo] !== false, onchange: async () => { if (!(await cambia(campo, input.checked))) input.checked = !input.checked; } });
      return h('label', { class: 'voce-imp interruttore', for: id }, h('span', { class: 'testi' }, h('span', { class: 'nome' }, nome)), input, h('span', { class: 'binario', 'aria-hidden': 'true' }));
    };
    const gruppo = (titolo, ...voci) => h('section', { class: 'gruppo-imp' }, h('span', { class: 'etichetta' }, titolo), h('div', { class: 'imp' }, voci));

    // Collegamento
    const statoCollegamento = S.nonRiconosciuto || mot.non_riconosciuto ? 'Il server non riconosce più questo computer'
      : S.senzaRete || mot.raggiungibile === false ? 'Il server di casa non risponde' : 'Collegato';
    const scollega = h('button', { type: 'button', class: 'pulsante testo pericolo', 'data-azione': 'scollega', onclick: async () => {
      if (S.esempio) return;
      if (!(await A.conferma({ titolo: 'Scollegare questo computer?', testo: 'Per usarlo di nuovo servirà un codice nuovo. Le lezioni scaricate su questo computer si tolgono.', si: 'Scollega', pericolo: true }))) return;
      await A.scollegaComputer();
    } }, 'Scollega');

    // Indirizzo da fuori casa (§15.5): quando quello di casa non risponde o non è il nostro server.
    // Vuoto finché il server non ne manda uno (o Andrea non lo scrive); svuotato a mano, torna quello del server.
    const fuori = h('input', { class: 'campo', id: 'imp-fuori', value: mot.indirizzo_fuori || '', placeholder: 'https://…ts.net', spellcheck: 'false', autocomplete: 'off', stile: { minWidth: '320px', height: '44px' } });
    const esitoFuori = h('span', { class: 'errore-campo', role: 'alert' });
    fuori.addEventListener('change', async () => {
      if (S.esempio) return;
      const r = await A.api.post('/locale/fuori-casa', { indirizzo: fuori.value.trim() });
      if (!r.ok) { esitoFuori.textContent = r.errore; return; }
      esitoFuori.textContent = '';
      fuori.value = r.dati.indirizzo_fuori || '';
      if (S.motore) S.motore.indirizzo_fuori = r.dati.indirizzo_fuori || null;
      A.avviso(r.dati.indirizzo_fuori_a_mano === false ? 'Uso l\'indirizzo da fuori casa del server di casa.' : 'Indirizzo da fuori casa salvato.');
    });

    // Voce predefinita
    const voci = (S.voci || []).filter((v) => v.disponibile !== false);
    const sceltaVoce = h('select', { class: 'campo', 'aria-label': 'Voce predefinita', onchange: () => cambia('voce_predefinita', sceltaVoce.value) },
      voci.map((v) => h('option', { value: v.id, selected: v.id === imp.voce_predefinita ? true : null }, v.nome + (v.tipo === 'espressiva' ? ' · voce espressiva' : v.tipo === 'gratis' ? ' · voce gratis' : ' · voce di prova'))));
    if (imp.voce_predefinita) sceltaVoce.value = imp.voce_predefinita;

    // Tetto
    const tetto = h('input', { class: 'campo', type: 'number', min: '0.5', max: '10', step: '0.5', value: String(imp.tetto_eur != null ? imp.tetto_eur : 2), 'aria-label': 'Tetto del mese in euro' });
    tetto.addEventListener('change', async () => {
      const v = Math.round(Number(tetto.value) * 100) / 100;
      if (!(v >= 0.5 && v <= 10)) { A.avviso('Il tetto va da 0,50 a 10 €.'); tetto.value = String(imp.tetto_eur); return; }
      if (await cambia('tetto_eur', v)) { A.caricaSpesa(); A.avviso('Tetto del mese: ' + L.euroTondo(v) + '.'); }
    });
    const spesa = S.spesa;

    // Tema (del computer: lo tiene il programma)
    const tema = segmenti([['sistema', 'Sistema'], ['chiaro', 'Chiaro'], ['scuro', 'Scuro']], mot.tema || 'sistema', async (v) => {
      A.applicaTema(v);
      if (S.esempio) return true;
      const r = await A.api.post('/locale/tema', { tema: v });
      if (r.ok && S.motore) S.motore.tema = v;
      return r.ok;
    }, 'Tema');

    // Aggiornamenti
    const agg = mot.aggiornamento || {};
    let rigaAgg;
    // Si scarica e si installa da sola (alla chiusura); qui si vede a che punto è, e si può anticipare.
    if (agg.pronta) rigaAgg = voce('È pronta la versione ' + agg.pronta, 'Si installa da sola quando chiudi Lectio.',
      h('button', { type: 'button', class: 'pulsante tenue', onclick: async () => { const r = await A.api.post('/locale/aggiorna-e-riavvia', {}); if (!r.ok) A.avviso(r.errore); } }, 'Aggiorna adesso'));
    else if (agg.in_corso) rigaAgg = voce('Arriva la versione ' + (agg.disponibile || 'nuova'), 'La sto scaricando e controllando.');
    else if (agg.disponibile) rigaAgg = voce('È uscita la versione ' + agg.disponibile, agg.errore || null,
      h('button', { type: 'button', class: 'pulsante tenue', onclick: async () => { const r = await A.api.post('/locale/scarica-aggiornamento', {}); if (r.ok) { A.avviso('La scarico: poi si installa da sola.'); A.disegna(); } else A.avviso(r.errore); } }, agg.errore ? 'Riprova' : 'Scarica adesso'));
    else rigaAgg = voce('Versione ' + (mot.versione || '1.0.0'), agg.attivo ? 'Si aggiorna da sola' : 'È l\'ultima');

    const nScaricate = (mot.scaricate || []).length;
    svuota(pag,
      h('h1', { class: 'titolo-pagina' }, 'Impostazioni'),
      ri && !ri.ok && !S.impostazioni ? A.erroreConRiprova(ri, () => A.disegna()) : null,
      gruppo('Collegamento',
        voce('Server di casa', mot.server || '', null),
        h('div', { class: 'voce-imp' }, h('div', { class: 'testi' }, h('span', { class: 'nome' }, 'Indirizzo da fuori casa'), esitoFuori), h('div', { class: 'valore' }, fuori)),
        voce(statoCollegamento, mot.nome_dispositivo || null, scollega)),
      S.impostazioni ? gruppo('Lezioni',
        voce('Voce predefinita', null, sceltaVoce),
        voce('Durata predefinita', null, segmenti(L.DURATE.map((d) => [d, d + ' min']), L.durataVicina(imp.durata_predefinita), (v) => cambia('durata_predefinita', v), 'Durata predefinita')),
        voce('Domande predefinite', null, segmenti([[false, 'Obbligatorie'], [true, 'Si possono saltare']], !!imp.domande_saltabili, (v) => cambia('domande_saltabili', v), 'Domande predefinite')),
        interruttore('Fermati alle domande', 'fermati_alle_domande', 'imp-fermati'),
        interruttore('Risposte scritte', 'risposte_scritte', 'imp-scritte'),
        voce('Velocità predefinita', null, segmenti(L.VELOCITA.map((v) => [v, L.etichettaVelocita(v)]), L.VELOCITA.find((v) => Math.abs(v - (imp.velocita || 1)) < 0.01) || 1, (v) => cambia('velocita', v, () => { if (A.lettoreVelocita) A.lettoreVelocita(v); }), 'Velocità predefinita'))) : null,
      S.impostazioni ? gruppo('Soldi',
        voce('Tetto del mese', spesa ? 'Questo mese hai speso ' + L.euro(spesa.speso_eur) + '. ' + L.siAzzera(spesa.si_azzera_il) + '.' : null, h('span', { class: 'tetto-campo' }, tetto, h('span', { class: 'forte' }, '€')))) : null,
      gruppo('Aspetto', voce('Tema', null, tema)),
      gruppo('Aiuto', voce('Come funziona Lectio', null, h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'visita', onclick: () => A.avviaVisita && A.avviaVisita(false, 'tutta') }, 'Guarda come funziona'))),
      gruppo('Sul computer', voce('Lezioni scaricate', nScaricate ? L.plurale(nScaricate, 'lezione', 'lezioni') + ' · ' + L.dimensione(mot.byte_scaricate || 0) : 'Nessuna')),
      gruppo('Aggiornamenti', rigaAgg),
      gruppo('Informazioni', voce('Lectio ' + (mot.versione || '1.0.0') + ' per il computer', 'Carattere Figtree, licenza SIL Open Font License.')));
  };

  // ================================================================== Com'è andata (§8)

  A.pagine.racconta = async function (box, rotta, vivo) {
    const pag = h('div', { class: 'pagina' }, A.segnaposto(4));
    svuota(box, pag);
    const re = await A.api.get('/server/api/eventi?da=' + L.iso(new Date(Date.now() - 60 * 86400000)));
    if (!vivo()) return;
    const ev = re.ok ? (re.dati || []).find((e) => e.id === rotta.id) : null;
    if (!ev) { svuota(pag, re.ok ? A.messaggio('errore', 'Questa verifica non c\'è più.') : A.erroreConRiprova(re, () => A.disegna())); return; }
    if (!S.materie) await A.caricaMaterie();
    const m = A.materiaDi(ev.materia_id) || { nome: '', colore: 'ambra', id: ev.materia_id };
    let domande = [];
    if (ev.lezione_id) {
      const rl = await A.dettaglioLezione(ev.lezione_id);
      if (!vivo()) return;
      if (rl.ok && rl.dati) domande = rl.dati.domande || [];
    }
    const cosa = ev.tipo === 'orale' ? 'l\'interrogazione' : 'la verifica';
    const uscite = new Set();
    const altre = h('textarea', { class: 'campo', id: 'altre-domande', rows: '4', placeholder: 'Una domanda per riga' });
    const voto = h('input', { class: 'campo', id: 'voto', maxlength: '10', stile: { width: '140px' } });
    const esito = h('div');
    const salva = h('button', { type: 'button', class: 'pulsante pieno', 'data-azione': 'salva-esito' }, 'Salva');
    salva.addEventListener('click', async () => {
      salva.disabled = true;
      const r = await A.api.post('/server/api/eventi/' + ev.id + '/esito', { domande_uscite: Array.from(uscite), altre_domande: altre.value, voto: voto.value.trim() || null });
      salva.disabled = false;
      if (!r.ok) { svuota(esito, A.messaggio('errore', r.errore)); return; }
      risultato(r.dati || {});
    });
    function risultato(d) {
      const giro = 2 * Math.PI * 58;
      const pieno = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      const fondo = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      for (const c of [fondo, pieno]) { c.setAttribute('cx', '70'); c.setAttribute('cy', '70'); c.setAttribute('r', '58'); }
      fondo.setAttribute('class', 'fondo-anello');
      pieno.setAttribute('class', 'pieno-anello');
      pieno.setAttribute('stroke-dasharray', String(giro));
      pieno.setAttribute('stroke-dashoffset', String(giro));
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 140 140');
      svg.append(fondo, pieno);
      const pct = d.totale_vere ? d.previste_uscite / d.totale_vere : 0;
      requestAnimationFrame(() => requestAnimationFrame(() => pieno.setAttribute('stroke-dashoffset', String(giro * (1 - pct)))));
      svuota(pag,
        h('h1', { class: 'titolo-pagina' }, 'Com\'è andata ' + cosa + ' di ' + m.nome + '?'),
        h('div', { class: 'scheda rilievo', stile: { alignItems: 'center', textAlign: 'center', maxWidth: '520px' } },
          h('div', { class: 'anello' }, svg, h('div', { class: 'numero' }, (d.previste_uscite || 0) + ' di ' + (d.totale_vere || 0), h('span', null, 'domande previste'))),
          h('p', { class: 'forte' }, 'Ho aggiornato la scheda del prof.'),
          h('button', { type: 'button', class: 'pulsante tenue', onclick: () => { A.vai('#materia/' + m.id); setTimeout(() => { const mm = A.materiaDi(m.id); if (mm) A.apriAggiungi(mm, { tipo: 'verifica' }); }, 120); } }, 'Carica la verifica corretta')));
    }
    svuota(pag,
      h('div', { class: 'testa-pagina' }, h('div', null, h('h1', { class: 'titolo-pagina' }, 'Com\'è andata ' + cosa + ' di ' + m.nome + '?'), h('span', { class: 'sottotitolo' }, L.dataLunga(ev.data)))),
      domande.length ? h('div', { class: 'sezione' }, h('span', { class: 'etichetta' }, 'Le domande della lezione'),
        h('div', { class: 'elenco domande-esito' }, domande.map((d) => {
          const id = 'uscita-' + d.id;
          const input = h('input', { type: 'checkbox', id, onchange: () => { if (input.checked) uscite.add(d.id); else uscite.delete(d.id); } });
          return h('label', { class: 'riga interruttore', for: id }, h('span', { class: 'testi' }, h('span', { class: 'forte', stile: { fontSize: '15px' } }, d.testo), h('span', { class: 'secondario' }, 'È uscita')), input, h('span', { class: 'binario', 'aria-hidden': 'true' }));
        }))) : null,
      h('div', { class: 'su-fondo', stile: { display: 'flex', flexDirection: 'column', gap: '16px', maxWidth: '640px' } },
        h('label', { class: 'campo-gruppo', for: 'altre-domande' }, h('span', { class: 'etichetta' }, 'Altre domande uscite'), altre),
        h('label', { class: 'campo-gruppo', for: 'voto' }, h('span', { class: 'etichetta' }, 'Voto'), voto)),
      esito,
      h('div', null, salva));
  };
})();
