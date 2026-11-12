/*
 * Lectio per il computer: i riassunti scritti (docs/aspetto.md §41, contratto §26). Si fanno da uno schema pronto, in
 * tre forme: il foglio per sceglierla e la pagina con la barra a tre segmenti.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  const inLavoro = (r) => r.stato === 'in_coda' || r.stato === 'in_preparazione';

  /** §41.2 La miniatura che mostra la forma: linee grige, i pallini nel colore della materia. */
  function miniatura(forma) {
    const riga = (classe) => h('i', { class: classe || null });
    const righe = forma === 'punti' ? [riga('pallino-rs'), riga('pallino-rs'), riga('pallino-rs'), riga('pallino-rs')]
      : forma === 'discorsivo' ? [riga(), riga(), riga(), riga(), riga('corta')]
        : [riga(), riga(), riga('pallino-rs'), riga('pallino-rs')];
    return h('span', { class: 'rs-mini', 'aria-hidden': 'true' }, righe);
  }

  /** §41.2 "Che riassunto vuoi?": un clic crea il riassunto e apre la sua pagina; se c'è già, la apre e basta. */
  A.apriSceltaRiassunto = function (schema, m) {
    const fatti = (schema.riassunti && schema.riassunti.fatti) || [];
    const stima = schema.riassunti ? schema.riassunti.stima_eur : 0;
    let invio = false;
    A.apriFoglio({
      titolo: 'Che riassunto vuoi?',
      classe: A.classeMateria(m.colore),
      corpo: (c, ctl) => {
        const sotto = h('div', { 'data-sotto': 'scelta' });
        async function scegli(f, fatto, righe) {
          if (invio) return;
          if (fatto && fatto.stato !== 'errore') { ctl.chiudi(true); A.vai('#riassunto/' + fatto.id); return; }
          invio = true;
          righe.forEach((b) => { b.disabled = true; });
          svuota(sotto);
          const r = await A.api.post('/server/api/schemi/' + schema.id + '/riassunti', { forma: f.forma });
          invio = false;
          if (ctl.chiuso) return;
          if (!r.ok || !r.dati) {
            righe.forEach((b) => { b.disabled = !!S.senzaRete; });
            svuota(sotto, A.messaggio(r.stato === 402 ? 'avviso' : 'errore', r.errore));
            return;
          }
          if (S.motore) S.motore.in_preparazione = true;
          ctl.chiudi(true);
          A.vai('#riassunto/' + r.dati.id);
        }
        const righe = [];
        L.FORME_RIASSUNTO.forEach((f) => {
          const fatto = L.riassuntoDellaForma(fatti, f.forma);
          const stato = L.statoFormaRiassunto(fatto, stima, A.modalitaProva());
          const b = h('button', {
            type: 'button', class: 'rs-riga', 'data-forma-riassunto': f.forma, disabled: S.senzaRete && !(fatto && fatto.stato === 'pronto') ? true : null,
            onclick: () => scegli(f, fatto, righe),
          }, miniatura(f.forma), h('span', { class: 'testi' }, h('span', { class: 'n' }, f.nome), h('span', { class: 'f' }, f.frase)), h('span', { class: 'prezzo', 'data-prezzo': f.forma }, stato.testo));
          righe.push(b);
        });
        svuota(c, h('div', { class: 'rs-righe' }, righe), sotto);
      },
    });
  };

  /** Il testo di un riassunto: titolo, paragrafi e punti, senza segni di fonte. `cambiate` = parti appena modificate (§44.3). */
  function testoRiassunto(r, cambiate) {
    return ((r.contenuto && r.contenuto.parti) || []).map((p, i) => h('section', { class: 'rs-parte' + (cambiate && cambiate.has(i + 1) ? ' mod-cambiata' : ''), 'data-parte': String(i + 1) },
      cambiate && cambiate.has(i + 1) ? (p.titolo ? h('div', { class: 'mod-titolo-cambiata' }, h('h2', null, p.titolo), A.etichettaCambiata()) : A.etichettaCambiata()) : p.titolo ? h('h2', null, p.titolo) : null,
      (p.paragrafi || []).length ? h('div', { class: 'rs-paragrafi' }, p.paragrafi.map((t) => h('p', { class: 'rs-testo' }, A.conGrassetto(t)))) : null,
      (p.punti || []).length ? h('ul', { class: 'sch-punti' }, p.punti.map((t) => h('li', null, A.conGrassetto(t)))) : null));
  }

  A.pagine.riassunto = async function (box, rotta, vivo) {
    const pag = h('div', { class: 'pagina' }, A.segnaposto(3), A.segnaposto(3), A.segnaposto(3));
    svuota(box, pag);
    let attuale = null;
    let forma = null;
    let schemaId = null;
    let stima = 0;
    let elenco = [];
    let m = null;
    let invio = false;
    const ricorda = (r) => { attuale = r; forma = r.forma; schemaId = r.schema_id; };
    const dellaForma = (f) => L.riassuntoDellaForma(elenco, f);

    // §44.3 La modifica del riassunto aperto (cambia forma = cambia riassunto: si segue quello nuovo).
    let seg = null;
    const aggiornaElenco = (r) => { elenco = elenco.filter((x) => x.id !== r.id).concat([r]); };
    function seguito() {
      if (seg && seg.id === attuale.id) return seg;
      const idQui = attuale.id;
      seg = A.seguiModifica({
        tipo: 'riassunto', id: idQui,
        vivo: () => vivo() && !!attuale && attuale.id === idQui,
        ricarica: () => A.api.get('/server/api/riassunti/' + idQui),
        pronta: (nuovo) => { aggiornaElenco(nuovo); ricorda(nuovo); disegna(false); },
        cambia: (nuovo) => { if (attuale && attuale.id === idQui) attuale.modifica = nuovo.modifica; },
      });
      return seg;
    }

    S.aggiornaPagina = () => {
      if (attuale && inLavoro(attuale)) { richiedi(); return; }
      if (seg && !document.querySelector('[data-correggi]')) { seg.subito(); seg.aggiorna(); }
    };

    async function carica() {
      const r = await A.api.get('/server/api/riassunti/' + rotta.id);
      if (!vivo()) return;
      if (!r.ok || !r.dati) {
        if (r.rete) svuota(pag, A.messaggio('avviso', 'Si apre quando torna la rete.', [h('button', { type: 'button', class: 'pulsante bianco', onclick: carica }, 'Riprova')]));
        else svuota(pag, A.erroreConRiprova(r, carica));
        return;
      }
      ricorda(r.dati);
      await Promise.all([S.materie ? null : A.caricaMaterie(), S.salute ? null : A.caricaSalute()]);
      m = A.materiaDi(attuale.materia_id) || { id: attuale.materia_id, nome: '', colore: 'ambra' };
      S.materiaAperta = attuale.materia_id;
      A.disegnaMenu();
      A.disegnaStato();
      await caricaIntorno();
      if (!vivo()) return;
      disegna(false);
    }

    /** Le altre forme dello stesso schema e la stima (chi non c'è ancora si può chiedere da qui). */
    async function caricaIntorno() {
      const [ri, rs] = await Promise.all([A.api.get('/server/api/schemi/' + schemaId + '/riassunti'), A.api.get('/server/api/schemi/' + schemaId)]);
      elenco = ri.ok && Array.isArray(ri.dati) ? ri.dati : [attuale];
      if (rs.ok && rs.dati && rs.dati.riassunti) stima = Number(rs.dati.riassunti.stima_eur) || 0;
      else stima = Number(attuale.stima_eur) || 0;
    }

    async function richiedi() {
      if (!attuale) return;
      const r = await A.api.get('/server/api/riassunti/' + attuale.id);
      if (!vivo() || !r.ok || !r.dati) return;
      if (r.dati.forma !== forma) return;
      ricorda(r.dati);
      elenco = elenco.filter((x) => x.id !== r.dati.id).concat([r.dati]);
      disegna(false);
    }

    /** Il cambio di forma resta nella stessa pagina: l'indirizzo si aggiorna senza ridisegnare. */
    function mostra(r) {
      ricorda(r);
      const nuovo = '#riassunto/' + r.id;
      history.replaceState(null, '', nuovo);
      if (S.storia.length) S.storia[S.storia.length - 1] = nuovo;
      disegna(true);
    }

    async function chiedi(f) {
      if (invio) return;
      invio = true;
      disegna(false, f);
      const r = await A.api.post('/server/api/schemi/' + schemaId + '/riassunti', { forma: f });
      invio = false;
      if (!vivo()) return;
      if (!r.ok || !r.dati) {
        disegna(false, f, r);
        return;
      }
      if (S.motore) S.motore.in_preparazione = true;
      elenco = elenco.filter((x) => x.forma !== f).concat([r.dati]);
      mostra(r.dati);
    }

    /** `sg` = la modifica seguita, solo se si sta guardando un riassunto pronto (§44.1: Modifica, Correggi, Torna a prima). */
    function testata(sg) {
      const pronto = attuale && attuale.stato === 'pronto';
      const nome = L.FORME_RIASSUNTO.find((x) => x.forma === attuale.forma);
      return h('div', { class: 'sch-testa' },
        A.frecciaIndietro(() => A.vai('#schema/' + schemaId)), A.pillolaMateria(m), h('span', { class: 'spazio' }),
        A.modalitaProva() ? h('span', { class: 'pillola stato prova' }, 'Prova') : null,
        sg ? sg.pillola(apriModifica) : null,
        pronto ? A.pulsantePdf('api/riassunti/' + attuale.id + '/pdf', L.nomeFilePdf((attuale.titolo || 'Riassunto') + ' - ' + (nome ? nome.nome : ''), 'Riassunto')) : null,
        A.menuPuntini(() => {
          const perche = sg ? sg.motivo() : null;
          return [
            { testo: 'Rifai', azione: 'rifai-riassunto', spenta: S.senzaRete || inLavoro(attuale), fai: rifai },
            sg ? { testo: 'Correggi a mano', azione: 'correggi-a-mano', spenta: !!perche, frase: perche, fai: correggi } : null,
            sg ? A.voceVersione(attuale, scambia) : null,
            { testo: 'Elimina', pericolo: true, azione: 'elimina-riassunto', spenta: S.senzaRete, fai: elimina },
          ];
        }));
    }

    function apriModifica() {
      const sg = seguito();
      const nome = L.FORME_RIASSUNTO.find((x) => x.forma === attuale.forma);
      A.apriModifica({ tipo: 'riassunto', oggetto: attuale, titolo: (attuale.titolo || 'Riassunto') + (nome ? ' · ' + nome.nome : ''), materia: m, inviata: (md) => sg.avvia(md) });
    }

    function correggi() {
      A.correggiAMano(pag, {
        tipo: 'riassunto', oggetto: attuale, materia: m, indietro: () => A.vai('#schema/' + schemaId),
        salvato: (nuovo) => { aggiornaElenco(nuovo); ricorda(nuovo); disegna(false); },
        annullato: () => disegna(false),
      });
    }

    async function scambia() {
      const id = attuale.id;
      const nuovo = await A.scambiaVersione('riassunto', id, attuale.versione_da_parte);
      if (!nuovo || !vivo() || !attuale || attuale.id !== id) return;
      aggiornaElenco(nuovo);
      ricorda(nuovo);
      if (seg) seg.evidenzia = new Set();
      disegna(false);
    }

    async function rifai() {
      if (!(await A.conferma({ titolo: 'Lo riscrivo da capo?', testo: 'Quello che c\'è adesso sparisce.', si: 'Riscrivilo', pericolo: true }))) return;
      const f = attuale.forma;
      const d = await A.api.elimina('/server/api/riassunti/' + attuale.id);
      if (!d.ok) { A.avviso(d.errore); return; }
      elenco = elenco.filter((x) => x.id !== attuale.id);
      chiedi(f);
    }

    async function elimina() {
      if (!(await A.conferma({ titolo: 'Eliminare il riassunto?', testo: 'I soldi già spesi restano nel conto del mese.', si: 'Elimina', pericolo: true }))) return;
      const r = await A.api.elimina('/server/api/riassunti/' + attuale.id);
      if (!r.ok) { A.avviso(r.errore); return; }
      A.vai('#schema/' + schemaId);
    }

    function barraForme(formaVista) {
      const barra = h('div', { class: 'segmenti binario rs-segmenti', role: 'group', 'aria-label': 'Forma del riassunto' });
      barra.style.setProperty('--n', '3');
      L.FORME_RIASSUNTO.forEach((f) => barra.append(h('button', {
        type: 'button', 'aria-pressed': formaVista === f.forma ? 'true' : 'false', 'data-forma': f.forma,
        onclick: () => {
          if (formaVista === f.forma) return;
          const r = dellaForma(f.forma);
          if (r) { mostra(r); return; }
          forma = f.forma;
          disegna(true, f.forma);
        },
      }, f.nome)));
      return barra;
    }

    /** `formaVuota` = si sta guardando una forma che non esiste ancora. */
    function disegna(cambio, formaVuota, erroreChiedi) {
      const vista = formaVuota || attuale.forma;
      const prontoQui = !formaVuota && attuale.stato === 'pronto';
      const sg = prontoQui ? seguito() : null;
      if (sg) sg.imposta(attuale);
      const parti = [testata(sg),
        h('div', { class: 'sch-titoli' }, h('h1', null, attuale.titolo || 'Riassunto'),
          prontoQui ? h('span', { class: 'sottotitolo', 'data-parole': 'si' }, L.fraseParole(attuale.parole)) : null),
        // §44.6 Il riassunto è più vecchio dello schema: si dice, con Rifallo (la stessa conferma di Rifai).
        prontoQui && attuale.schema_cambiato ? h('div', { class: 'mod-vecchio', 'data-vecchio': 'si' },
          h('span', null, 'Lo schema è cambiato dopo questo riassunto.'),
          h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'rifallo', disabled: S.senzaRete ? true : null, onclick: rifai }, 'Rifallo')) : null,
        sg ? sg.el : null,
        barraForme(vista)];
      if (formaVuota) {
        const spesa = invio ? 'Chiedo il riassunto…' : A.modalitaProva() ? 'Scrivilo' : 'Scrivilo · ' + L.euro(stima);
        parti.push(h('div', { class: 'rs-manca' + (cambio ? ' dissolve' : ''), 'data-forma-manca': formaVuota },
          h('button', { type: 'button', class: 'pulsante pieno medio', 'data-azione': 'scrivi-riassunto', disabled: invio || S.senzaRete ? true : null, onclick: () => chiedi(formaVuota) }, spesa),
          erroreChiedi ? A.messaggio(erroreChiedi.stato === 402 ? 'avviso' : 'errore', erroreChiedi.errore) : null));
      } else if (inLavoro(attuale)) {
        parti.push(h('div', { class: 'rs-attesa', 'data-vista': 'lavoro' }, h('span', { class: 'frase' }, attuale.frase || 'Scrivo il riassunto'), A.segnaposto(3)));
        A.ripeti(vivo, richiedi, T.stato);
      } else if (attuale.stato === 'errore') {
        parti.push(A.messaggio('errore', attuale.errore || 'Il riassunto non è riuscito.', [h('button', {
          type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-riassunto',
          onclick: async () => {
            const r = await A.api.post('/server/api/riassunti/' + attuale.id + '/riprova', {});
            if (!r.ok || !r.dati) { A.avviso(r.errore); return; }
            if (S.motore) S.motore.in_preparazione = true;
            ricorda(r.dati);
            elenco = elenco.filter((x) => x.id !== r.dati.id).concat([r.dati]);
            disegna(false);
          },
        }, 'Riprova')]), h('div', null, h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'elimina-riassunto', onclick: elimina }, 'Elimina')));
      } else {
        if (attuale.nota) parti.push(h('div', { class: 'sch-nota', 'data-nota': 'riassunto' }, h('div', { class: 'testa' }, icona('domanda'), 'Non ho trovato tutto'), h('p', null, attuale.nota)));
        parti.push(h('article', { class: 'rs-scheda' + (cambio ? ' cambia' : ''), 'data-riassunto': attuale.forma }, testoRiassunto(attuale, sg ? sg.evidenzia : null)));
      }
      svuota(pag, h('div', { class: 'rs-pagina ' + A.classeMateria(m.colore) }, parti));
    }

    await carica();
  };
})();
