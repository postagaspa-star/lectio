/*
 * Lectio per il computer: il Ripasso lampo (docs/aspetto.md §35, contratto §20). Una domanda, la risposta solo a un gesto
 * voluto, tre parole sempre allo stesso posto. Niente festa, niente conteggi di giorni.
 */
(function () {
  'use strict';

  const A = window.LectioApp;
  const { L, S, T, h, svuota, icona } = A;

  const FRASE_SENZA_RETE = 'Senza rete non ho le schede di oggi.';
  const senzaMovimento = () => !A.conMovimenti() || document.documentElement.classList.contains('foto');

  /** La scheda "Ripasso lampo" di Oggi e della materia (§31). */
  A.schedaLampo = function (daFare, minuti, apri, senzaSchede) {
    if (!(Number(daFare) > 0)) {
      return h('div', { class: 'riga-lampo-vuota', 'data-lampo': 'vuoto' }, icona('spunta'), senzaSchede || 'Ripasso lampo · niente per oggi');
    }
    return h('div', { class: 'scheda-lampo', 'data-lampo': 'scheda', onclick: apri },
      h('span', { class: 'tessera', 'aria-hidden': 'true' }, icona('lampo')),
      h('span', { class: 'testi' }, h('span', { class: 'nome' }, 'Ripasso lampo'), h('span', { class: 'sotto', 'data-lampo': 'frase' }, L.fraseLampo(daFare, minuti))),
      h('button', { type: 'button', class: 'pulsante tenue', 'data-azione': 'inizia-lampo', 'data-visita': 'lampo-inizia' }, 'Inizia'));
  };

  /** o.materiaId = solo quella materia (allora in fondo si torna alla materia). */
  A.apriLampo = function (opzioni) {
    const o = opzioni || {};
    const st = {
      carte: [], i: 0, aperta: false, fermoFino: 0, pendente: null, ritenta: [], conti: { sapevo: 0, quasi: 0, non_sapevo: 0 }, t0: Date.now(), fatte: 0, domani: 0, daFare: 0,
      fase: 'carico', chiusa: false, conferma: false, inUscita: false, errore: null,
    };
    const alto = h('div', { class: 'lampo-alto' });
    const traccia = h('span');
    const spazio = h('div', { class: 'lampo-spazio' });
    const fondo = h('div', { class: 'lampo-fondo' });
    let ctl = null;

    ctl = A.apriFinestra({
      classe: 'finestra-lampo', etichetta: 'Ripasso lampo',
      suChiusura: async () => {
        st.chiusa = true;
        await mandaPendente();
        if (S.aggiornaPagina) S.aggiornaPagina();
      },
      disegna: (f) => svuota(f, alto, h('div', { class: 'lampo-traccia', 'aria-hidden': 'true' }, traccia), spazio, fondo),
    });
    ctl.tasti = tasti;
    carica();
    return ctl;

    async function carica() {
      st.fase = 'carico';
      disegna();
      const r = await A.api.get('/server/api/lampo?limite=12' + (o.materiaId ? '&materia_id=' + o.materiaId : ''));
      if (st.chiusa) return;
      if (r.rete || (r.ok && r.memoria)) { st.errore = FRASE_SENZA_RETE; st.fase = 'errore'; disegna(); return; }
      if (!r.ok || !r.dati) { st.errore = r.errore; st.fase = 'errore'; disegna(); return; }
      st.carte = r.dati.carte || [];
      st.domani = Number(r.dati.domani) || 0;
      st.daFare = Number(r.dati.da_fare) || 0;
      Object.assign(st, { i: 0, aperta: false, pendente: null, conti: { sapevo: 0, quasi: 0, non_sapevo: 0 }, t0: Date.now(), fatte: 0, conferma: false });
      st.fase = st.carte.length ? 'scheda' : 'vuoto';
      disegna(true);
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    function carta() { return st.carte[st.i]; }

    function rigaAlta() {
      const finita = st.fase === 'fine' || st.fase === 'vuoto';
      const inScheda = st.fase === 'scheda';
      svuota(alto,
        h('div', null, h('button', { type: 'button', class: 'tondo grande', 'aria-label': 'Chiudi', 'data-azione': 'chiudi-lampo', onclick: () => ctl.chiudi() }, icona('chiudi'))),
        h('span', { class: 'lampo-conto', 'data-lampo': 'conto' }, inScheda ? (st.i + 1) + ' di ' + st.carte.length : ''),
        h('div', { class: 'a-destra' },
          inScheda && st.pendente ? h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'annulla-esito', onclick: annulla }, 'Annulla') : null,
          inScheda && !finita ? A.menuPuntini(() => [{ testo: 'Togli questa scheda', azione: 'togli-scheda', fai: () => { st.conferma = true; disegnaScheda(false); } }]) : null));
      const pct = inScheda ? Math.round(st.i / st.carte.length * 100) : finita ? 100 : 0;
      traccia.style.width = pct + '%';
    }

    function disegna(entra) {
      if (st.chiusa) return;
      rigaAlta();
      if (st.fase === 'carico') {
        svuota(spazio, h('div', { class: 'lampo-scheda' }, h('div', { class: 'segnaposto riga-finta', stile: { width: '40%' } }), h('div', { class: 'segnaposto riga-finta', stile: { width: '90%', height: '24px' } }), h('div', { class: 'segnaposto riga-finta', stile: { width: '70%', height: '24px' } })));
        svuota(fondo);
      } else if (st.fase === 'errore') {
        svuota(spazio, A.messaggio(st.errore === FRASE_SENZA_RETE ? 'avviso' : 'errore', st.errore, [h('button', { type: 'button', class: 'pulsante bianco', 'data-azione': 'riprova-lampo', onclick: carica }, 'Riprova')]));
        svuota(fondo);
      } else if (st.fase === 'vuoto') disegnaVuoto();
      else if (st.fase === 'fine') disegnaFine();
      else disegnaScheda(entra);
    }

    function disegnaScheda(entra) {
      const c = carta();
      if (!c) return;
      rigaAlta();
      const scheda = h('div', { class: 'lampo-scheda' + (entra && !senzaMovimento() ? ' entra-destra' : ''), 'data-lampo': 'carta', 'data-carta': String(c.id) },
        A.pillolaMateria({ nome: c.materia, colore: c.colore }),
        h('p', { class: 'lampo-domanda', 'data-lampo': 'domanda' }, c.domanda));
      if (st.aperta) {
        scheda.append(h('div', { class: 'lampo-risposta', tabindex: '-1', 'data-lampo': 'risposta' },
          h('span', { class: 'etichetta' }, 'Risposta'),
          h('p', { class: 'testo-r' }, A.conGrassetto(c.risposta)),
          A.segnoFonte(c.fonti)));
      }
      if (st.conferma) {
        scheda.append(h('div', { class: 'lampo-togli', 'data-lampo': 'togli' }, h('span', null, 'Toglierla per sempre?'),
          h('button', { type: 'button', class: 'pulsante tenue piccolo', 'data-azione': 'togli-si', onclick: togli }, 'Togli'),
          h('button', { type: 'button', class: 'pulsante testo tenue-testo', 'data-azione': 'togli-no', onclick: () => { st.conferma = false; disegnaScheda(false); } }, 'Lascia')));
      }
      svuota(spazio, scheda);
      if (st.aperta) {
        svuota(fondo, h('div', { class: 'lampo-esiti', role: 'group', 'aria-label': 'Come è andata', 'data-visita': 'lampo-esiti' }, L.ESITI_LAMPO.map((x) => h('button', {
          type: 'button', class: 'lampo-esito ' + x.classe, 'data-esito': x.esito, 'data-visita': x.esito === 'quasi' ? 'lampo-quasi' : null, onclick: () => daiEsito(x.esito),
        }, icona(x.icona), x.parola, h('span', { class: 'tasto-angolo', 'aria-hidden': 'true' }, x.tasto)))));
      } else {
        svuota(fondo, h('button', { type: 'button', class: 'pulsante pieno lampo-mostra', 'data-azione': 'mostra-risposta', 'data-visita': 'lampo-mostra', onclick: mostra }, 'Mostra la risposta'));
      }
    }

    function mostra() {
      if (st.fase !== 'scheda' || st.aperta) return;
      st.aperta = true;
      st.fermoFino = performance.now() + T.fermoEsito;
      disegnaScheda(false);
      const r = spazio.querySelector('.lampo-risposta');
      if (r) r.focus();
      if (A.visitaEvento) A.visitaEvento('lampo-mostra');
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    function daiEsito(esito) {
      if (st.fase !== 'scheda' || !st.aperta || st.inUscita || performance.now() < st.fermoFino) return;
      const c = carta();
      const prima = st.pendente;
      if (prima) inviaEsito(prima);
      st.pendente = { carta: c, esito, i: st.i };
      st.conti[esito] += 1;
      st.fatte += 1;
      st.conferma = false;
      if (A.visitaEvento) A.visitaEvento('lampo-esito');
      vaiAvanti();
    }

    function vaiAvanti() {
      const prosegui = async () => {
        st.inUscita = false;
        if (st.chiusa) return;
        if (st.i + 1 >= st.carte.length) { await finisci(); return; }
        st.i += 1;
        st.aperta = false;
        disegna(true);
      };
      const vecchia = spazio.querySelector('.lampo-scheda');
      if (senzaMovimento() || !vecchia) { prosegui(); return; }
      st.inUscita = true;
      vecchia.classList.add('esce');
      setTimeout(prosegui, 180);
    }

    function annulla() {
      const p = st.pendente;
      if (!p || st.inUscita) return;
      st.pendente = null;
      st.conti[p.esito] -= 1;
      st.fatte -= 1;
      st.i = p.i;
      st.aperta = true;
      st.fermoFino = performance.now() + T.fermoEsito;
      disegna(false);
    }

    async function inviaEsito(p) {
      const inCoda = st.ritenta.splice(0);
      for (const x of inCoda) await spedisci(x, false);
      await spedisci(p, true);
    }

    async function spedisci(p, avvisa) {
      const r = await A.api.post('/server/api/lampo/' + p.carta.id, { esito: p.esito });
      if (r.ok || r.stato === 404) return true;
      st.ritenta.push(p);
      if (avvisa) A.avviso('Non ho salvato l\'ultimo esito: riprovo');
      return false;
    }

    async function mandaPendente() {
      const p = st.pendente;
      st.pendente = null;
      if (p) await inviaEsito(p);
      else if (st.ritenta.length) { const inCoda = st.ritenta.splice(0); for (const x of inCoda) await spedisci(x, false); }
    }

    async function finisci() {
      st.durata = Date.now() - st.t0;
      await mandaPendente();
      if (st.chiusa) return;
      // POST /lampo/{id} conta solo la materia di quella scheda: per "Domani" e "Continua" si richiede tutto.
      const r = await A.api.get('/server/api/lampo?limite=12' + (o.materiaId ? '&materia_id=' + o.materiaId : ''));
      if (st.chiusa) return;
      if (r.ok && r.dati && !r.memoria) {
        st.domani = Number(r.dati.domani) || 0;
        st.daFare = Number(r.dati.da_fare) || 0;
      } else st.daFare = 0;
      st.fase = 'fine';
      disegna();
      if (A.visitaDopoDisegno) A.visitaDopoDisegno();
    }

    async function togli() {
      const c = carta();
      const r = await A.api.elimina('/server/api/lampo/' + c.id);
      if (st.chiusa) return;
      if (!r.ok && r.stato !== 404) { A.avviso(r.errore); return; }
      st.conferma = false;
      st.carte.splice(st.i, 1);
      st.aperta = false;
      if (!st.carte.length) { if (st.fatte) { await finisci(); } else { st.fase = 'vuoto'; disegna(); } return; }
      if (st.i >= st.carte.length) { await finisci(); return; }
      disegna(true);
    }

    function disegnaFine() {
      const riquadri = L.ESITI_LAMPO.slice().reverse().map((x) => {
        const num = h('span', { class: 'num', 'data-conteggio': x.esito }, '0');
        A.contaNumero(num, st.conti[x.esito], null, 350);
        return h('div', { class: 'lampo-riquadro ' + x.classe }, num, h('span', { class: 'par' }, x.parola));
      });
      svuota(spazio, h('div', { class: 'lampo-fine', 'data-lampo': 'fine' },
        h('h2', null, 'Ripasso finito'),
        h('span', { class: 'sotto', 'data-lampo': 'durata' }, L.fraseFineRipasso(st.fatte, st.durata)),
        h('div', { class: 'lampo-riquadri' }, riquadri),
        st.domani > 0 ? h('span', { class: 'domani', 'data-lampo': 'domani' }, 'Domani ' + L.plurale(st.domani, 'scheda', 'schede')) : null,
        h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'fine-lampo', onclick: () => ctl.chiudi() }, 'Fine'),
        st.daFare > 0 ? h('button', { type: 'button', class: 'pulsante testo', 'data-azione': 'continua-lampo', onclick: carica }, 'Continua con altre') : null));
      svuota(fondo);
    }

    function disegnaVuoto() {
      svuota(spazio, h('div', { class: 'lampo-fine', 'data-lampo': 'vuoto' },
        h('span', { class: 'spunta-tonda', 'aria-hidden': 'true' }, icona('spunta')),
        h('p', { class: 'niente' }, 'Per oggi niente da ripassare'),
        st.domani > 0 ? h('span', { class: 'domani' }, 'Domani ' + L.plurale(st.domani, 'scheda', 'schede')) : null,
        h('button', { type: 'button', class: 'pulsante pieno largo', 'data-azione': 'torna-lampo', onclick: () => ctl.chiudi() }, o.materiaId ? 'Torna alla materia' : 'Torna a Oggi')));
      svuota(fondo);
    }

    function tasti(e) {
      if (st.fase !== 'scheda' || A.staScrivendo()) return;
      if (e.key === ' ' || e.code === 'Space') {
        e.preventDefault();
        mostra();
        return;
      }
      const x = L.ESITI_LAMPO.find((y) => y.tasto === e.key);
      if (x && st.aperta) {
        e.preventDefault();
        daiEsito(x.esito);
      }
    }
  };
})();
