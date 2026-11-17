// I test della pagina delle mappe (docs/aspetto.md §45, contratto §29) in jsdom, col server finto di esempio.js:
// "Che mappa vuoi?", la mappa dei concetti (albero e raggiera, clic = spiegazione), la linea del tempo, Mettiti alla
// prova, Scarica PDF (il foglio che si stampa), Modifica, Correggi a mano, una mappa nuova che si prepara.
// Si lanciano con: node --test --test-concurrency=2 pc/prove/js. Dati solo finti.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };
const TEMPI = { stato: 60, modifica: 40, stimaModifica: 20, avviso: 300 };
test.afterEach(() => B.chiudiTutte());

async function apri(hash, prima, opzioniServer) {
  const s = B.server(Object.assign({ tempoModifica: 80 }, opzioniServer || {}));
  if (prima) prima(s);
  const p = await B.pagina(s, { tempi: TEMPI, hash });
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  return p;
}

async function apriMappa(id, prima) {
  const p = await apri('#mappa/' + id, prima);
  await p.aspetta(() => p.q('.mp-scheda svg.mp-tela .mp-mondo'));
  return p;
}

async function voceMenu(p, azione) {
  if (!p.q('.tendina')) p.clic(p.q('.sch-testa [data-azione="puntini"]'));
  return p.aspetta(() => p.q('.tendina [data-azione="' + azione + '"]'));
}

test('dallo schema: pillola Mappa e scheda in fondo, "Che mappa vuoi?" con i due tipi, un clic apre la mappa', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('.sch-testa [data-azione="mappa"]') && p.q('[data-fine="mappa"]'));
  assert.equal(p.q('[data-fine="mappa"] button').textContent, 'Apri la mappa');
  p.clic(p.q('.sch-testa [data-azione="mappa"]'));
  await p.aspetta(() => p.tutti('[data-tipo-mappa]').length === 2);
  assert.deepEqual(p.tutti('[data-tipo-mappa]').map((b) => b.getAttribute('data-tipo-mappa')), ['date', 'concetti']);
  assert.deepEqual(p.tutti('[data-tipo-mappa] .n').map((x) => x.textContent), ['Le date', 'I concetti']);
  assert.deepEqual(p.tutti('[data-tipo-mappa] .prezzo').map((x) => x.textContent), ['Pronta', 'Pronta']);
  p.clic(p.q('[data-tipo-mappa="date"]'));
  await p.aspetta(() => location(p) === '#mappa/8' && p.q('.mp-scheda[data-mappa="date"] .mp-data'));
  // Una mappa già fatta si apre e basta: al server non si chiede di farne un'altra.
  assert.equal(p.s.chiamate('POST', '/server/api/schemi/3/mappe').length, 0);
});

const location = (p) => p.w.location.hash;

test('la mappa dei concetti: titolo, domanda, cerchi e frecce; albero o raggiera; il clic apre la spiegazione', opz, async () => {
  const p = await apriMappa(7);
  assert.equal(p.q('.sch-titoli h1').textContent, 'L\'Impero romano da Augusto a Nerone');
  assert.equal(p.q('[data-sotto-mappa]').textContent, '16 concetti · Come passa il potere da Augusto a Nerone?');
  assert.equal(p.tutti('.mp-nodo').length, 16);
  assert.equal(p.tutti('.mp-legame').length, 15);
  assert.equal(p.tutti('.mp-trasversale').length, 1);
  assert.equal(p.tutti('.mp-nodo.l0').length, 1);
  assert.equal(p.q('.mp-nodo.l0').getAttribute('aria-label'), 'L\'Impero da Augusto a Nerone');
  assert.equal(p.q('[data-forma-mappa="albero"]').getAttribute('aria-pressed'), 'true');
  // Ogni freccia ha la punta e il suo verbo.
  assert.ok(p.tutti('.mp-legame').every((l) => /^url\(#mp-punta-\d+\)$/.test(l.getAttribute('marker-end'))));
  assert.ok(p.tutti('.mp-verbo text').some((t) => t.textContent === 'lascia Roma a'));

  // Clic su Tiberio: a destra chi è, i vicini e "Apri nello schema".
  p.clic(p.q('.mp-nodo[data-id="3"]'));
  await p.aspetta(() => p.q('[data-mappa-pannello].aperto'));
  assert.equal(p.q('.mp-p-titolo').textContent, 'Tiberio');
  assert.match(p.q('.mp-p-testo').textContent, /figlio adottivo/);
  assert.deepEqual(p.tutti('.mp-p-vicino .t').map((x) => x.textContent), ['L\'Impero da Augusto a Nerone', 'Seiano', 'Processi per lesa maestà']);
  assert.ok(p.q('.mp-tela').classList.contains('con-scelta'));
  assert.ok(p.q('.mp-nodo[data-id="8"]').classList.contains('acceso'), 'Seiano acceso');
  assert.ok(!p.q('.mp-nodo[data-id="4"]').classList.contains('acceso'), 'Caligola da parte');
  // Un vicino porta a lui; Esc chiude.
  p.clic(p.q('.mp-p-vicino[data-vicino="8"]'));
  await p.aspetta(() => p.q('.mp-p-titolo').textContent === 'Seiano');
  p.tasto('Escape', {}, p.d.body);
  await p.aspetta(() => !p.q('[data-mappa-pannello].aperto'));
  assert.ok(!p.q('.mp-tela').classList.contains('con-scelta'));

  // A raggiera: stessa mappa, altra forma, e la scelta resta.
  p.clic(p.q('[data-forma-mappa="raggiera"]'));
  await p.aspetta(() => p.q('.mp-scheda[data-forma="raggiera"]'));
  assert.equal(p.tutti('.mp-nodo').length, 16);
  assert.equal(p.A.memoriaStudio.leggi('lectio.mappa.forma.7'), 'raggiera');

  // "Apri nello schema · parte 2" porta lo schema a quella parte.
  p.clic(p.q('.mp-nodo[data-id="3"]'));
  await p.aspetta(() => p.q('[data-azione="apri-nello-schema"]'));
  assert.equal(p.q('[data-azione="apri-nello-schema"]').textContent, 'Apri nello schema · parte 2');
  p.clic(p.q('[data-azione="apri-nello-schema"]'));
  await p.aspetta(() => location(p) === '#schema/3' && p.q('.sch-parte'));
  assert.equal(Number(p.A.memoriaStudio.leggi('lectio.schema.parte.3')), 2);
});

test('la linea del tempo: le date in ordine, i periodi come fasce, in scala o in fila, la nota', opz, async () => {
  const p = await apriMappa(8);
  assert.equal(p.q('[data-sotto-mappa]').textContent, '9 date · dal 27 a.C. al 68');
  assert.equal(p.tutti('.mp-data').length, 9);
  assert.equal(p.tutti('.mp-fascia').length, 2, '37-41 e 41-54');
  assert.deepEqual(p.tutti('.mp-data text.data').map((t) => t.textContent), ['27 a.C.', '26', '31', '37-41', '41-54', '43', '54', '64', '68']);
  assert.match(p.q('[data-nota="mappa"]').textContent, /Di Tiberio/);
  const modo = p.q('.mp-scheda').getAttribute('data-forma');
  const altro = modo === 'scala' ? 'fila' : 'scala';
  p.clic(p.q('[data-forma-mappa="' + altro + '"]'));
  await p.aspetta(() => p.q('.mp-scheda[data-forma="' + altro + '"]'));
  assert.equal(p.tutti('.mp-tacca').length > 0, altro === 'scala');
  // Clic su una data: la spiegazione e la parte dello schema.
  p.clic(p.q('.mp-data[data-id="5"]'));
  await p.aspetta(() => p.q('[data-mappa-pannello].aperto'));
  assert.equal(p.q('.mp-p-data').textContent, '41-54');
  assert.equal(p.q('.mp-p-titolo').textContent, 'Claudio imperatore');
  assert.equal(p.q('.mp-p-durata').textContent, 'Dal 41 al 54');
  assert.equal(p.q('[data-azione="apri-nello-schema"]').textContent, 'Apri nello schema · parte 3');
  // Dalla barra dei tipi si passa ai concetti dello stesso schema, senza chiedere niente.
  p.clic(p.q('.mp-tipi [data-tipo="concetti"]'));
  await p.aspetta(() => location(p) === '#mappa/7' && p.q('.mp-scheda[data-mappa="concetti"]'));
});

test('Mettiti alla prova: pezzi nascosti, un clic li scopre, Rimescola e Basta così', opz, async () => {
  const p = await apriMappa(7);
  p.clic(p.q('[data-azione="mettiti-alla-prova"]'));
  await p.aspetta(() => p.q('[data-prova="si"]'));
  const nascosti = p.tutti('.mp-nodo.coperto');
  assert.ok(nascosti.length >= 4, 'circa un terzo dei concetti');
  assert.ok(!p.q('.mp-nodo.l0').classList.contains('coperto'), 'mai la radice');
  assert.ok(nascosti.every((g) => g.textContent === '?'));
  const totale = Number(/su (\d+)/.exec(p.q('.mp-conta').textContent)[1]);
  assert.equal(p.q('.mp-conta').textContent, 'Scoperti 0 su ' + totale);
  const id = nascosti[0].getAttribute('data-id');
  p.clic(nascosti[0]);
  await p.aspetta(() => p.q('.mp-conta').textContent === 'Scoperti 1 su ' + totale);
  const scoperto = p.q('.mp-nodo[data-id="' + id + '"]');
  assert.ok(!scoperto.classList.contains('coperto') && scoperto.classList.contains('scoperto'));
  assert.ok(!p.q('[data-mappa-pannello].aperto'), 'scoprire non apre la spiegazione');
  // Un secondo clic sullo stesso, ormai scoperto, apre la spiegazione.
  p.clic(scoperto);
  await p.aspetta(() => p.q('[data-mappa-pannello].aperto'));
  p.clic(p.q('[data-azione="rimescola"]'));
  await p.aspetta(() => p.q('.mp-conta').textContent === 'Scoperti 0 su ' + totale);
  p.clic(p.q('[data-azione="fine-prova"]'));
  await p.aspetta(() => !p.q('[data-prova="si"]') && !p.q('.mp-nodo.coperto'));
  // Sulla linea del tempo si nascondono date o fatti.
  p.clic(p.q('.mp-tipi [data-tipo="date"]'));
  await p.aspetta(() => p.q('.mp-scheda[data-mappa="date"]'));
  p.clic(p.q('[data-azione="mettiti-alla-prova"]'));
  await p.aspetta(() => p.q('.mp-buco'));
  assert.ok(p.tutti('.mp-buco').length >= 3);
});

test('Scarica PDF: il PC stampa un foglio in chiaro con la mappa e la firma, poi lo toglie', opz, async () => {
  let visto = null;
  const p = await apriMappa(7, (s) => {
    s.stampaPdf = (corpo) => {
      const d = visto.d;
      visto.dati = {
        nome: corpo.nome, classe: d.documentElement.classList.contains('stampa-mappa'), pagine: d.querySelectorAll('.mappa-stampa .ms-pagina').length,
        nodi: d.querySelectorAll('.mappa-stampa .mp-nodo').length, titolo: d.querySelector('.mappa-stampa h1').textContent,
        firma: d.querySelector('.mappa-stampa .ms-firma').textContent, pieno: d.querySelector('.mappa-stampa').style.getPropertyValue('--m-pieno'),
        viewBox: d.querySelector('.mappa-stampa svg').getAttribute('viewBox'),
      };
      return [200, { ok: true, id: 'a1', nome: corpo.nome }];
    };
  });
  visto = { d: p.d };
  p.clic(p.q('.sch-testa [data-azione="scarica-pdf"]'));
  await p.aspetta(() => visto.dati && !p.q('.mappa-stampa'));
  assert.equal(visto.dati.nome, 'L\'Impero romano da Augusto a Nerone.pdf');
  assert.equal(visto.dati.classe, true);
  assert.equal(visto.dati.pagine, 1);
  assert.equal(visto.dati.nodi, 16);
  assert.equal(visto.dati.titolo, 'L\'Impero romano da Augusto a Nerone');
  assert.equal(visto.dati.firma, 'Lectio');
  assert.equal(visto.dati.pieno, '#C27C0E', 'i colori chiari della materia anche col tema scuro');
  assert.match(visto.dati.viewBox, /^0 0 \d/);
  assert.ok(!p.d.documentElement.classList.contains('stampa-mappa'));
  await p.aspetta(() => /PDF salvato/.test(p.testo()));
  // Annullato: niente avviso.
  p.s.stampaPdf = () => [200, { annullato: true }];
});

test('Scarica PDF in prova: il nome lo dice e i pezzi nascosti restano nascosti', opz, async () => {
  let dati = null;
  const p = await apriMappa(8, (s) => {
    s.stampaPdf = (corpo) => {
      dati = { nome: corpo.nome, buchi: p.d.querySelectorAll('.mappa-stampa .mp-buco').length, pagine: p.d.querySelectorAll('.mappa-stampa .ms-pagina').length, testa: p.d.querySelector('.mappa-stampa .ms-testa').textContent };
      return [200, { ok: true, id: 'a2', nome: corpo.nome }];
    };
  });
  p.clic(p.q('[data-azione="mettiti-alla-prova"]'));
  await p.aspetta(() => p.q('.mp-buco'));
  const buchi = p.tutti('.mp-scheda .mp-buco').length;
  p.clic(p.q('.sch-testa [data-azione="scarica-pdf"]'));
  await p.aspetta(() => dati);
  assert.equal(dati.nome, 'L\'Impero romano da Augusto a Nerone - da completare.pdf');
  // Ogni pagina porta tutta la linea (tagliata dal viewBox): i buchi sono gli stessi, una volta per pagina.
  assert.equal(dati.pagine, 1, 'nove date stanno in una pagina');
  assert.equal(dati.buchi, buchi * dati.pagine);
  assert.match(dati.testa, /Da completare/);
});

test('Modifica: niente "Dove?" (la mappa è una parte sola), gli spunti del tipo, la mappa cambiata torna da sola', opz, async () => {
  const p = await apriMappa(7);
  p.clic(p.q('.sch-testa [data-azione="modifica"]'));
  await p.aspetta(() => p.q('[data-campo="richiesta"]'));
  assert.ok(!p.q('[data-dove]'), 'niente Dove?');
  assert.deepEqual(p.tutti('.mod-spunto').map((b) => b.textContent), ['Meno concetti', 'Più concetti', 'Più semplice']);
  p.scrivi(p.q('[data-campo="richiesta"]'), 'Aggiungi Capri');
  await p.aspetta(() => { const b = p.q('[data-azione="manda-modifica"]'); return b && !b.disabled; });
  p.clic(p.q('[data-azione="manda-modifica"]'));
  await p.aspetta(() => p.tutti('.mp-nodo').length === 17, 4000);
  const md = p.s.chiamate('POST', '/server/api/modifiche').find((x) => x.percorso === '/server/api/modifiche');
  assert.equal(md.corpo.tipo, 'mappa');
  assert.deepEqual(md.corpo.parti, []);
  assert.ok(p.tutti('.mp-nodo').some((g) => g.getAttribute('aria-label') === 'Capri'));
  // Torna a prima è nel menu.
  await voceMenu(p, 'scambia-versione');
});

test('Correggi a mano: un campo per concetto e per verbo, Salva manda solo i testi', opz, async () => {
  const p = await apriMappa(7);
  p.clic(await voceMenu(p, 'correggi-a-mano'));
  await p.aspetta(() => p.q('[data-correggi="mappa"]'));
  const campi = p.tutti('[data-correggi="mappa"] .mp-c-voce [aria-label^="Concetto "]');
  assert.equal(campi.length, 16);
  assert.equal(campi[2].value, 'Tiberio');
  p.scrivi(campi[2], 'Tiberio Claudio Nerone');
  p.clic(p.q('[data-azione="salva-correzioni"]'));
  await p.aspetta(() => p.q('.mp-scheda') && p.tutti('.mp-nodo').some((g) => g.getAttribute('aria-label') === 'Tiberio Claudio Nerone'));
  const put = p.s.chiamate('PUT', '/server/api/mappe/7/testo')[0];
  assert.equal(put.corpo.contenuto.concetti[2].testo, 'Tiberio Claudio Nerone');
  assert.equal(put.corpo.contenuto.concetti[2].genitore, 1, 'chi sta sotto a chi non cambia');
});

test('una mappa che non c\'è: Falla col prezzo, poi si prepara e si disegna da sola', opz, async () => {
  const p = await apriMappa(7, (s) => { s.esempio.stato.mappe = s.esempio.stato.mappe.filter((x) => x.id !== 8); });
  p.clic(p.q('.mp-tipi [data-tipo="date"]'));
  await p.aspetta(() => p.q('[data-tipo-manca="date"]'));
  assert.equal(p.q('[data-azione="fai-mappa"]').textContent, 'Falla · 0,01 €');
  p.clic(p.q('[data-azione="fai-mappa"]'));
  await p.aspetta(() => p.q('[data-vista="lavoro"]'));
  assert.match(location(p), /^#mappa\/\d+$/);
  assert.notEqual(location(p), '#mappa/7');
  await p.aspetta(() => p.tutti('.mp-data').length === 9, 6000);
});

test('Cosa vuoi fare? → Mappa → Da quale schema? → lo schema si apre con "Che mappa vuoi?"', opz, async () => {
  const p = await apri('#materia/1');
  await p.aspetta(() => p.q('[data-visita="nuovo"]') && p.q('[data-elenco="schemi"] .riga') && !p.q('[data-elenco="schemi"] .segnaposto'));
  p.clic(p.q('[data-visita="nuovo"]'));
  await p.aspetta(() => p.q('.finestra-nuovo [data-scelta="mappa"]') && !p.q('.finestra-nuovo [data-scelta="mappa"]').disabled);
  p.clic(p.q('.finestra-nuovo [data-scelta="mappa"]'));
  await p.aspetta(() => p.q('[data-schema-scelto="3"]'));
  p.clic(p.q('[data-schema-scelto="3"]'));
  await p.aspetta(() => location(p) === '#schema/3' && p.tutti('[data-tipo-mappa]').length === 2);
});
