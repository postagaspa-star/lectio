// I test di dove vanno i pezzi delle mappe (pc/ui/disegno-mappe.js): riquadri, raggiera, linea del tempo, pagine del
// PDF, Mettiti alla prova. Si lanciano con: node --test --test-concurrency=2 pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('../../ui/disegno-mappe.js');

const misura = (t) => t.length * 8;
const CONCETTI = [
  { id: 1, testo: 'Crisi del Trecento', genitore: null },
  { id: 2, testo: 'Peste nera', genitore: 1, verbo: 'comprende' },
  { id: 3, testo: 'Carestie', genitore: 1, verbo: 'comprende' },
  { id: 4, testo: 'Calo della popolazione', genitore: 2, verbo: 'provoca' },
  { id: 5, testo: 'Rivolte contadine', genitore: 3, verbo: 'porta a' },
  { id: 6, testo: 'Jacquerie', genitore: 5, verbo: 'come la' },
  { id: 7, testo: 'Ciompi', genitore: 5, verbo: 'e dei' },
];
const dim = {};
for (const c of CONCETTI) dim[c.id] = { w: 40 + misura(c.testo), h: 40 };

const sovrapposti = (a, b) => Math.abs(a.x - b.x) < (a.w + b.w) / 2 && Math.abs(a.y - b.y) < (a.h + b.h) / 2;

function nessunoSiTocca(nodi) {
  const lista = Object.values(nodi);
  for (let i = 0; i < lista.length; i++) for (let j = i + 1; j < lista.length; j++) assert.equal(sovrapposti(lista[i], lista[j]), false, `${i} e ${j} si toccano`);
}

test('a capo: righe dentro la misura, al massimo tre, l\'ultima chiusa con i puntini', () => {
  assert.deepEqual(M.aCapo('Calo della popolazione', 100, misura), ['Calo della', 'popolazione']);
  const r = M.aCapo('uno due tre quattro cinque sei sette otto nove dieci', 70, misura, 2);
  assert.equal(r.length, 2);
  assert.ok(r[1].endsWith('…') && misura(r[1]) <= 70);
  assert.deepEqual(M.aCapo('', 100, misura), ['']);
});

const dentro = (a, b) => a.x >= b.x - 0.5 && a.y >= b.y - 0.5 && a.x + a.w <= b.x + b.w + 0.5 && a.y + a.h <= b.y + b.h + 0.5;
const tocca = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

test('a riquadri: l\'argomento in alto, le parti in colonne dentro il riquadro, le schede nella loro parte', () => {
  const alte = [];
  const alta = (id, w, ruolo) => { alte.push([id, w, ruolo]); return ruolo === 'radice' ? 60 : 40 + (id % 3) * 10; };
  const l = M.layoutRiquadri(CONCETTI, alta);
  const k = l.cornice;
  // Due parti: due colonne, nessuna riga in più.
  assert.equal(l.parti.length, 2);
  assert.equal(k.w, 2 * M.RIQ.colonna + M.RIQ.gap + 2 * M.RIQ.pad);
  assert.equal(l.larghezza, k.w + 2 * M.MARGINE);
  assert.deepEqual([l.nodi[1].ruolo, l.nodi[2].ruolo, l.nodi[4].ruolo], ['radice', 'parte', 'scheda']);
  assert.equal(l.nodi[1].y, k.y);
  assert.equal(l.nodi[1].w, k.w);
  for (const p of l.parti) {
    assert.ok(dentro(p, k), 'la parte sta nel riquadro');
    assert.ok(p.y >= k.y + k.testa, 'sotto la fascia dell\'argomento');
  }
  assert.equal(l.parti[0].y, l.parti[1].y);
  assert.equal(l.parti[0].h, l.parti[1].h, 'le colonne della stessa riga sono alte uguali');
  const parteDi = { 4: 2, 5: 3, 6: 3, 7: 3 };
  for (const [id, pid] of Object.entries(parteDi)) assert.ok(dentro(l.nodi[id], l.parti.find((p) => p.id === pid)), id + ' nella sua parte');
  // Le schede non si toccano; Jacquerie e Ciompi (due figli) rientrano sotto Rivolte contadine.
  const schede = Object.values(l.nodi).filter((n) => n.ruolo === 'scheda');
  for (let i = 0; i < schede.length; i++) for (let j = i + 1; j < schede.length; j++) assert.equal(tocca(schede[i], schede[j]), false);
  assert.ok(l.nodi[6].x > l.nodi[5].x && l.nodi[6].w < l.nodi[5].w);
  assert.ok(l.nodi[6].y > l.nodi[5].y + l.nodi[5].h && l.nodi[7].y > l.nodi[6].y + l.nodi[6].h);
  // Niente frecce dall'argomento alle parti né dalle parti ai loro concetti (anche se il verbo c'era); tra due concetti sì.
  assert.ok(!l.legami.some((x) => x.da === 1 || x.da === 2 || x.da === 3));
  assert.deepEqual(l.legami.map((x) => [x.a, x.tipo, x.freccia]).sort((a, b) => a[0] - b[0]),
    [[6, 'guida', true], [7, 'guida', true]]);
  assert.equal(l.nodi[4].y - (l.nodi[2].y + l.nodi[2].h), M.RIQ.dopoTesta, 'senza verbo: niente spazio in più');
  // L'altezza si chiede alla larghezza vera: le schede rientrate sono più strette.
  assert.ok(alte.some(([id, w]) => id === 6 && w === l.nodi[6].w));
});

test('a riquadri: un figlio solo col verbo va in catena, subito sotto; senza verbo rientra; tante parti vanno a capo', () => {
  const catena = [
    { id: 1, testo: 'Rivoluzione francese', genitore: null },
    { id: 2, testo: 'Le fasi', genitore: 1 },
    { id: 3, testo: 'Stati generali', genitore: 2, verbo: 'comprende' },
    { id: 4, testo: 'Assemblea nazionale', genitore: 3, verbo: 'diventano' },
    { id: 5, testo: 'Terrore', genitore: 4, verbo: 'porta al' },
    { id: 6, testo: 'Simboli', genitore: 1 },
    { id: 7, testo: 'Tricolore', genitore: 6 },
    { id: 8, testo: 'Tre colori', genitore: 7 },
  ];
  const l = M.layoutRiquadri(catena, (id, w, r) => (r === 'radice' ? 50 : 40));
  assert.equal(l.nodi[4].x, l.nodi[3].x, 'in catena: stessa riga di partenza');
  assert.equal(l.nodi[5].x, l.nodi[3].x);
  assert.ok(l.nodi[4].y - (l.nodi[3].y + l.nodi[3].h) >= M.RIQ.gapVerbo, 'lo spazio per il verbo');
  assert.deepEqual(l.legami.filter((x) => x.tipo === 'catena').map((x) => x.a), [4, 5]);
  assert.ok(l.legami.every((x) => x.tipo !== 'catena' || x.verbo));
  // Un figlio solo ma senza verbo: rientra, con la linea guida senza punta.
  assert.ok(l.nodi[8].x > l.nodi[7].x);
  assert.deepEqual(l.legami.filter((x) => x.a === 8).map((x) => [x.tipo, x.freccia, x.verbo]), [['guida', false, null]]);
  // Le righe di parti: fino a 4 in una riga, poi righe pari.
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(M.colonneRiquadri), [1, 2, 3, 4, 3, 3, 4, 4, 3, 4]);
  const tante = [{ id: 1, testo: 'Tutto', genitore: null }];
  for (let i = 2; i <= 8; i++) tante.push({ id: i, testo: 'Parte ' + i, genitore: 1 });
  const t = M.layoutRiquadri(tante, () => 40);
  assert.deepEqual([...new Set(t.parti.map((p) => p.y))].length, 2, 'sette parti: due righe');
  const seconda = t.parti.filter((p) => p.y > t.parti[0].y);
  assert.equal(seconda.length, 3);
  // L'ultima riga, più corta, sta in mezzo.
  const k = t.cornice;
  assert.ok(Math.abs((seconda[0].x - k.x) - (k.x + k.w - (seconda[2].x + seconda[2].w))) < 1);
  assert.deepEqual(M.layoutRiquadri([], () => 40).parti, []);
});

test('a raggiera: la radice al centro, il primo giro intorno, nessuno si tocca anche con tanti figli', () => {
  const tanti = [{ id: 1, testo: 'Rivoluzione industriale', genitore: null }];
  for (let i = 2; i <= 13; i++) tanti.push({ id: i, testo: 'Conseguenza numero ' + i, genitore: 1, verbo: 'porta a' });
  for (let i = 14; i <= 20; i++) tanti.push({ id: i, testo: 'Dettaglio ' + i, genitore: i - 12, verbo: 'cioè' });
  const d = {};
  for (const c of tanti) d[c.id] = { w: 40 + misura(c.testo), h: 40 };
  const l = M.layoutRaggiera(tanti, d);
  nessunoSiTocca(l.nodi);
  const r = l.nodi[1];
  const dist = (n) => Math.hypot(n.x - r.x, n.y - r.y);
  for (let i = 2; i <= 13; i++) assert.ok(Math.abs(dist(l.nodi[i]) - dist(l.nodi[2])) < 1);
  for (let i = 14; i <= 20; i++) assert.ok(dist(l.nodi[i]) > dist(l.nodi[2]));
  // Il primo ramo è in alto.
  assert.ok(l.nodi[2].y < r.y);
  // La forma la sceglie il modello; le mappe di prima ("albero") vanno a riquadri.
  assert.equal(M.formaConsigliata({ forma: 'raggiera', concetti: tanti }), 'raggiera');
  assert.equal(M.formaConsigliata({ forma: 'albero', concetti: CONCETTI }), 'riquadri');
  assert.equal(M.formaConsigliata({ concetti: tanti }), 'riquadri');
});

test('le frecce partono e arrivano sul bordo dei concetti', () => {
  const a = { x: 100, y: 100, w: 120, h: 40 };
  const b = { x: 100, y: 300, w: 80, h: 40 };
  const l = M.legame(a, b);
  assert.equal(l.x1, 100);
  assert.equal(l.y1, 124);
  assert.equal(l.y2, 273);
  assert.ok(l.my > 190 && l.my < 210);
});

test('anni avanti Cristo e tacche tonde', () => {
  assert.equal(M.annoTesto(1492), '1492');
  assert.equal(M.annoTesto(-752), '753 a.C.');
  assert.equal(M.annoTesto(0), '1 a.C.');
  assert.equal(M.passoTacche(1000, 1000, 100), 100);
  assert.equal(M.passoTacche(30, 1000, 100), 5);
  assert.deepEqual(M.tacche(-752, 476, 200).map(M.annoTesto), ['600 a.C.', '400 a.C.', '200 a.C.', '200', '400']);
  assert.deepEqual(M.tacche(1400, 1520, 50), [1400, 1450, 1500]);
});

const EVENTI = [
  { id: 1, x: -752, data: '753 a.C.' }, { id: 2, x: -508, data: '509 a.C.' }, { id: 3, x: -26, data: '27 a.C.' },
  { id: 4, x: 14, data: '14 d.C.' }, { id: 5, x: 476, data: '476 d.C.' }, { id: 6, x: 476, data: '476' },
  { id: 7, x: 1401, x_fine: 1500, data: 'XV secolo' }, { id: 8, x: 1492.75, data: '1492' },
];
const dimE = {};
for (const e of EVENTI) dimE[e.id] = { w: 170, h: 58 };

test('linea del tempo in scala: in ordine, le schede sulla stessa corsia non si toccano, i periodi sono fasce', () => {
  const l = M.layoutLinea(EVENTI, dimE, { modo: 'scala', larghezza: 900 });
  const p = l.eventi;
  for (let i = 1; i < EVENTI.length; i++) assert.ok(p[EVENTI[i].id].px >= p[EVENTI[i - 1].id].px);
  const schede = EVENTI.map((e) => ({ x: p[e.id].cx, y: p[e.id].y, w: 170, h: 58 }));
  nessunoSiTocca(schede);
  assert.equal(l.fasce.length, 1);
  assert.ok(l.fasce[0].a > l.fasce[0].da);
  for (const e of EVENTI) assert.ok(p[e.id].sopra ? p[e.id].y < l.asse : p[e.id].y > l.asse);
  assert.ok(l.tacche.length >= 3 && l.tacche.every((t) => t.px >= 0 && t.px <= l.larghezza));
  assert.equal(l.pigiato, false);
});

test('linea del tempo in fila: tutte alla stessa distanza, niente tacche', () => {
  const l = M.layoutLinea(EVENTI, dimE, { modo: 'fila', larghezza: 900 });
  const xs = EVENTI.map((e) => l.eventi[e.id].px);
  const passo = xs[1] - xs[0];
  for (let i = 2; i < xs.length; i++) assert.ok(Math.abs(xs[i] - xs[i - 1] - passo) < 0.01);
  assert.deepEqual(l.tacche, []);
  assert.deepEqual(l.fasce, []);
  // Dalla preistoria a oggi in scala si allungherebbe troppo: si consiglia in fila.
  const lunga = [{ id: 1, x: -2500000 }, { id: 2, x: -9999 }, { id: 3, x: -3000 }, { id: 4, x: 1492 }, { id: 5, x: 1789 }, { id: 6, x: 1945 }];
  const d = {};
  for (const e of lunga) d[e.id] = { w: 170, h: 58 };
  assert.equal(M.modoConsigliato(lunga, d, 1000), 'fila');
  assert.equal(M.modoConsigliato(EVENTI.slice(0, 3), dimE, 1000), 'scala');
});

test('pagine del PDF: si taglia dove non c\'è una scheda', () => {
  assert.deepEqual(M.pagine([[0, 100]], 1000, 1000), [[0, 1000]]);
  const pezzi = [[0, 180], [150, 330], [700, 880], [990, 1170], [1500, 1700], [2100, 2300]];
  const t = M.pagine(pezzi, 2400, 1000);
  assert.equal(t[0][0], 0);
  assert.equal(t[t.length - 1][1], 2400);
  for (let i = 1; i < t.length; i++) assert.equal(t[i][0], t[i - 1][1]);
  for (const [, fine] of t.slice(0, -1)) assert.ok(!pezzi.some(([a, b]) => a < fine && b > fine), 'taglio dentro una scheda a ' + fine);
});

test('Mettiti alla prova: un terzo dei concetti, mai la radice, sempre gli stessi con lo stesso seme', () => {
  const contenuto = { concetti: CONCETTI };
  const a = M.sceltaProva('concetti', contenuto, 42);
  const b = M.sceltaProva('concetti', contenuto, 42);
  assert.deepEqual([...a.concetti], [...b.concetti]);
  assert.equal(a.concetti.size, 2);
  assert.ok(!a.concetti.has(1));
  for (const v of a.verbi) assert.ok(!a.concetti.has(v));
  const d = M.sceltaProva('date', { eventi: EVENTI }, 7);
  assert.equal(d.date.size, 3);
  for (const f of d.fatti) assert.ok(!d.date.has(f));
  assert.equal(M.daNascondere([1], 0.5, 1).size, 0);
});

test('frasi della pagina e concetti vicini', () => {
  assert.equal(M.fraseMappa('concetti', { concetti: CONCETTI }), '7 concetti');
  assert.equal(M.fraseMappa('date', { eventi: EVENTI }), '8 date · dal 753 a.C. al 1500');
  assert.equal(M.fraseMappa('date', { eventi: [EVENTI[0]] }), '1 data');
  const v = M.vicini({ concetti: CONCETTI, collegamenti: [{ da: 4, a: 5, verbo: 'causa' }] }, 5);
  // Le frecce tra rami delle mappe di prima non contano più; sotto l'argomento e sotto le parti il verbo non c'è, anche
  // se il modello l'aveva messo.
  assert.deepEqual(v.map((x) => [x.id, x.verbo, x.verso]), [[3, null, 'da'], [6, 'come la', 'a'], [7, 'e dei', 'a']]);
  assert.deepEqual(M.vicini({ concetti: CONCETTI }, 2).map((x) => [x.id, x.verbo, x.verso]), [[1, null, 'da'], [4, null, 'a']]);
  const per = new Map(CONCETTI.map((c) => [c.id, c]));
  assert.deepEqual(CONCETTI.map((c) => M.verboDi(per, c)), [null, null, null, null, null, 'come la', 'e dei']);
});
