// I test di dove vanno i pezzi delle mappe (pc/ui/disegno-mappe.js): albero, raggiera, linea del tempo, pagine del
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

test('albero dall\'alto: la radice in cima, i figli sotto, nessuno si tocca', () => {
  const l = M.layoutAlbero(CONCETTI, dim);
  nessunoSiTocca(l.nodi);
  assert.ok(l.nodi[1].y < l.nodi[2].y && l.nodi[2].y < l.nodi[4].y);
  assert.equal(l.nodi[2].y, l.nodi[3].y);
  // Il genitore sta in mezzo ai suoi figli.
  assert.ok(Math.abs(l.nodi[5].x - (l.nodi[6].x + l.nodi[7].x) / 2) < 1);
  // Tra un livello e l'altro c'è lo spazio per il verbo.
  assert.ok(l.nodi[2].y - l.nodi[2].h / 2 - (l.nodi[1].y + l.nodi[1].h / 2) >= M.GAP_Y - 1);
  for (const n of Object.values(l.nodi)) assert.ok(n.x - n.w / 2 >= 0 && n.x + n.w / 2 <= l.larghezza && n.y + n.h / 2 <= l.altezza);
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
  assert.equal(M.formaConsigliata({ concetti: tanti }), 'raggiera');
  assert.equal(M.formaConsigliata({ concetti: CONCETTI }), 'albero');
  assert.equal(M.formaConsigliata({ forma: 'raggiera', concetti: CONCETTI }), 'raggiera');
});

test('le frecce partono e arrivano sul bordo dei concetti', () => {
  const a = { x: 100, y: 100, w: 120, h: 40 };
  const b = { x: 100, y: 300, w: 80, h: 40 };
  const l = M.legame(a, b);
  assert.equal(l.x1, 100);
  assert.equal(l.y1, 124);
  assert.equal(l.y2, 273);
  assert.ok(l.my > 190 && l.my < 210);
  assert.match(M.curva(a, b).d, /^M100 124 Q/);
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
  assert.deepEqual(v.map((x) => [x.id, x.verbo, x.verso]), [[3, 'porta a', 'da'], [6, 'come la', 'a'], [7, 'e dei', 'a'], [4, 'causa', 'da']]);
});
