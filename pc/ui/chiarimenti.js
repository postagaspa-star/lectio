/*
 * Lectio per il computer: "Non ho capito" (docs/aspetto.md §34, contratto §19). Lo stesso foglio dallo schema e dal
 * lettore. Spiega rileggendo solo i documenti; "non c'è" è una risposta tranquilla, mai un errore.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  /**
   * o.materia; dallo schema o.schemaId, o.sezione, o.passaggio; dal lettore o.lezioneId, o.posizione, o.etichetta
   * ("Al minuto 5:12 · …"). o.suChiusura parte quando il foglio si chiude.
   */
  A.nonHoCapito = function (o) {
    const m = o.materia;
    const st = { domanda: '', invio: false, chiuso: false, errore: null, passaggio: o.passaggio || '', tutto: false, primaFatta: false, comandi: true, lunga: false };
    let timerLungo = null;
    let ctl = null;

    const passaggioBox = h('div', { 'data-capito': 'passaggio' });
    const risposteBox = h('div', { class: 'capito-risposte', 'data-capito': 'risposte' });
    const fondo = h('div', { 'data-capito': 'fondo' });

    const campo = h('textarea', { class: 'capito-campo', rows: '1', maxlength: String(L.MASSIMO_DOMANDA), placeholder: 'Cosa non ti è chiaro', 'aria-label': 'Cosa non ti è chiaro', 'data-campo': 'domanda' });
    const conta = h('span', { class: 'capito-conta', 'data-conta': 'domanda' });
    const adatta = () => {
      campo.style.height = 'auto';
      campo.style.height = Math.min(campo.scrollHeight || 52, 108) + 'px';
      const n = campo.value.length;
      conta.textContent = n >= L.CONTATORE_DOMANDA_DA ? n + ' / ' + L.MASSIMO_DOMANDA : '';
      conta.style.display = n >= L.CONTATORE_DOMANDA_DA ? '' : 'none';
    };
    campo.addEventListener('input', () => { st.domanda = campo.value; adatta(); });
    campo.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        if (!S.senzaRete) invia();
      }
    });

    function disegnaPassaggio() {
      if (!o.etichetta && !st.passaggio) { svuota(passaggioBox); return; }
      svuota(passaggioBox, h('button', {
        type: 'button', class: 'capito-passaggio' + (st.tutto ? ' tutto' : ''), 'aria-expanded': st.tutto ? 'true' : 'false',
        onclick: () => { st.tutto = !st.tutto; disegnaPassaggio(); },
      }, o.etichetta ? h('span', { class: 'etichetta', 'data-capito': 'minuto' }, o.etichetta) : null, st.passaggio ? h('span', { class: 't' }, st.passaggio) : null));
    }

    function disegnaComandi() {
      const manda = h('button', { type: 'button', class: 'pulsante pieno largo capito-manda', 'data-azione': 'spiegamelo', 'data-visita': 'spiegamelo', disabled: S.senzaRete ? true : null, onclick: invia }, 'Spiegamelo');
      svuota(fondo, h('div', { class: 'capito-comandi' },
        st.errore ? A.messaggio('errore', st.errore, [h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-capito', onclick: invia }, 'Riprova')]) : null,
        h('div', { class: 'capito-spunti' }, L.SPUNTI_CHIARIMENTO.map((x) => h('button', {
          type: 'button', 'data-spunto': x.etichetta, onclick: () => { campo.value = x.frase; st.domanda = x.frase; adatta(); campo.focus(); },
        }, x.etichetta))),
        campo, conta,
        S.senzaRete ? h('span', { class: 'capito-frase' }, 'Senza rete non posso rileggere i tuoi documenti.') : null,
        manda));
      adatta();
    }

    function disegnaAttesa() {
      const frase = h('span', { class: 'frase', 'data-capito': 'attesa' }, 'Rileggo i tuoi documenti…');
      svuota(fondo, h('div', { class: 'capito-attesa', 'aria-busy': 'true' },
        h('div', { class: 'segnaposto riga-finta', stile: { width: '100%' } }), h('div', { class: 'segnaposto riga-finta', stile: { width: '90%' } }), h('div', { class: 'segnaposto riga-finta', stile: { width: '60%' } }),
        frase));
      clearTimeout(timerLungo);
      timerLungo = setTimeout(() => { frase.textContent = 'Ci vuole ancora qualche secondo'; }, T.attesaLunga);
    }

    function vaiInFondo() {
      if (ctl && ctl.foglio) ctl.foglio.scrollTop = ctl.foglio.scrollHeight;
    }

    function piedeRisposta() {
      svuota(fondo, h('div', { class: 'capito-piede' },
        h('button', { type: 'button', class: 'pulsante pieno', 'data-azione': 'ho-capito', onclick: () => ctl.chiudi() }, 'Ho capito'),
        h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'non-ancora', onclick: () => { st.domanda = ''; campo.value = ''; disegnaComandi(); vaiInFondo(); } }, 'Non ancora')));
    }

    function piedeNonCe() {
      svuota(fondo, h('div', { class: 'capito-piede' },
        h('button', { type: 'button', class: 'pulsante pieno', 'data-azione': 'aggiungi-materiale', onclick: () => { ctl.chiudi(true); A.apriAggiungi(m); } }, 'Aggiungi materiale'),
        h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'chiudi-capito', onclick: () => ctl.chiudi() }, 'Chiudi')));
    }

    function mostraRisposta(d) {
      if (d.trovato === false) {
        risposteBox.append(h('div', { class: 'capito-una' }, h('div', { class: 'capito-non-ce', 'data-capito': 'non-c-e' },
          h('div', { class: 'testa' }, icona('domanda'), 'Nei tuoi documenti non c\'è'), h('p', null, A.conGrassetto(d.risposta)))));
        piedeNonCe();
      } else {
        const fonti = d.fonti || [];
        risposteBox.append(h('div', { class: 'capito-una', 'data-capito': 'risposta' },
          h('p', { class: 'capito-risposta' }, A.conGrassetto(d.risposta)),
          fonti.length ? h('div', { 'data-visita': 'fonti-capito' }, h('span', { class: 'etichetta' }, 'Da dove l\'ho preso'),
            h('div', { class: 'capito-fonti' }, fonti.map((f) => A.rigaFonte(f, () => A.apriFonte(f))))) : null,
          h('span', { class: 'capito-costo', 'data-capito': 'costo' }, L.costoChiarimento(d.costo_eur))));
        piedeRisposta();
      }
      vaiInFondo();
    }

    async function invia() {
      if (st.invio || S.senzaRete) return;
      st.invio = true;
      st.errore = null;
      disegnaAttesa();
      if (A.visitaEvento) A.visitaEvento('capito-invio');
      const domanda = campo.value.trim();
      const dati = o.lezioneId != null
        ? { materia_id: m.id, lezione_id: o.lezioneId, posizione_s: o.posizione, domanda }
        : { materia_id: m.id, schema_id: o.schemaId, sezione: o.sezione, passaggio: o.passaggio, domanda };
      const r = await A.api.post('/server/api/chiarimenti', dati);
      clearTimeout(timerLungo);
      st.invio = false;
      if (st.chiuso) return;
      if (!r.ok || !r.dati) {
        st.errore = r.errore;
        disegnaComandi();
        return;
      }
      if (r.dati.passaggio && !st.passaggio) { st.passaggio = r.dati.passaggio; disegnaPassaggio(); }
      mostraRisposta(r.dati);
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    ctl = A.apriFoglio({
      titolo: 'Non ho capito',
      classe: 'foglio-capito ' + A.classeMateria(m.colore),
      fuoco: false,
      suChiusura: () => { st.chiuso = true; clearTimeout(timerLungo); if (o.suChiusura) o.suChiusura(); },
      corpo: (c) => {
        svuota(c, passaggioBox, risposteBox, fondo);
        disegnaPassaggio();
        disegnaComandi();
      },
    });
    // Finché il foglio è aperto i tasti non comandano il lettore che sta sotto.
    ctl.tasti = () => {};
    if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    return ctl;
  };
})();
