// I test di cartelle e zip (pc/ui/archivi.js) e del foglio che li manda: zip veri costruiti qui (anche ZIP64, nomi
// vecchi di Windows, zip dentro zip, protetti da password), cartelle trascinate finte, avanzi da lasciare fuori, il
// limite dei file, e nel foglio: un file alla volta, Riprova che non rimanda quelli già andati, file non entrati.
// Si lanciano con: node --test pc/prove/js
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const zlib = require('node:zlib');
const Ar = require('../../ui/archivi.js');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };
test.afterEach(() => B.chiudiTutte());

// --- Uno zip costruito a mano ----------------------------------------------------------------------

const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return (b) => {
    let c = 0xFFFFFFFF;
    for (const x of b) c = t[(c ^ x) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
  };
})();

/**
 * voci: { nome (stringa o Buffer), dati (Buffer/stringa), metodo 0|8 (o altro), utf8 (segno), cifrato, intero (finto),
 * cartella }. opzioni.zip64: indice e numeri come li scrive ZIP64.
 */
function creaZip(voci, opzioni) {
  const o = opzioni || {};
  const locali = [];
  const centrali = [];
  let posizione = 0;
  for (const v of voci) {
    const nome = Buffer.isBuffer(v.nome) ? v.nome : Buffer.from(v.nome, 'utf8');
    const dati = v.cartella ? Buffer.alloc(0) : Buffer.isBuffer(v.dati) ? v.dati : Buffer.from(v.dati || '', 'utf8');
    const metodo = v.metodo == null ? 8 : v.metodo;
    const compressi = metodo === 8 ? zlib.deflateRawSync(dati) : dati;
    const intero = v.intero == null ? dati.length : v.intero;
    const segni = (v.utf8 ? 0x800 : 0) | (v.cifrato ? 1 : 0);
    const crc = CRC(dati);
    const loc = Buffer.alloc(30);
    loc.writeUInt32LE(0x04034b50, 0);
    loc.writeUInt16LE(20, 4);
    loc.writeUInt16LE(segni, 6);
    loc.writeUInt16LE(metodo, 8);
    loc.writeUInt32LE(crc, 14);
    loc.writeUInt32LE(compressi.length, 18);
    loc.writeUInt32LE(intero, 22);
    loc.writeUInt16LE(nome.length, 26);
    // Un "extra" nel locale più lungo che nel centrale: chi legge deve usare quello del locale.
    const extraLocale = Buffer.from([0xfe, 0xca, 2, 0, 0, 0]);
    loc.writeUInt16LE(extraLocale.length, 28);
    const pezzo = Buffer.concat([loc, nome, extraLocale, compressi]);
    let extra = Buffer.alloc(0);
    const cen = Buffer.alloc(46);
    cen.writeUInt32LE(0x02014b50, 0);
    cen.writeUInt16LE(20, 4);
    cen.writeUInt16LE(20, 6);
    cen.writeUInt16LE(segni, 8);
    cen.writeUInt16LE(metodo, 10);
    cen.writeUInt32LE(crc, 16);
    if (o.zip64) {
      cen.writeUInt32LE(0xFFFFFFFF, 20);
      cen.writeUInt32LE(0xFFFFFFFF, 24);
      cen.writeUInt32LE(0xFFFFFFFF, 42);
      extra = Buffer.alloc(4 + 24);
      extra.writeUInt16LE(0x0001, 0);
      extra.writeUInt16LE(24, 2);
      extra.writeBigUInt64LE(BigInt(intero), 4);
      extra.writeBigUInt64LE(BigInt(compressi.length), 12);
      extra.writeBigUInt64LE(BigInt(posizione), 20);
    } else {
      cen.writeUInt32LE(compressi.length, 20);
      cen.writeUInt32LE(intero, 24);
      cen.writeUInt32LE(posizione, 42);
    }
    if (v.unicode) {
      const u = Buffer.from(v.unicode, 'utf8');
      const e = Buffer.alloc(9);
      e.writeUInt16LE(0x7075, 0);
      e.writeUInt16LE(5 + u.length, 2);
      e.writeUInt8(1, 4);
      e.writeUInt32LE(CRC(nome), 5);
      extra = Buffer.concat([extra, e, u]);
    }
    cen.writeUInt16LE(nome.length, 28);
    cen.writeUInt16LE(extra.length, 30);
    centrali.push(Buffer.concat([cen, nome, extra]));
    locali.push(pezzo);
    posizione += pezzo.length;
  }
  const cd = Buffer.concat(centrali);
  const fine = Buffer.alloc(22);
  fine.writeUInt32LE(0x06054b50, 0);
  const parti = [...locali, cd];
  if (o.zip64) {
    const z = Buffer.alloc(56);
    z.writeUInt32LE(0x06064b50, 0);
    z.writeBigUInt64LE(44n, 4);
    z.writeUInt16LE(45, 12);
    z.writeUInt16LE(45, 14);
    z.writeBigUInt64LE(BigInt(voci.length), 24);
    z.writeBigUInt64LE(BigInt(voci.length), 32);
    z.writeBigUInt64LE(BigInt(cd.length), 40);
    z.writeBigUInt64LE(BigInt(posizione), 48);
    const loc = Buffer.alloc(20);
    loc.writeUInt32LE(0x07064b50, 0);
    loc.writeBigUInt64LE(BigInt(posizione + cd.length), 8);
    loc.writeUInt32LE(1, 16);
    parti.push(z, loc);
    fine.writeUInt16LE(0xFFFF, 8);
    fine.writeUInt16LE(0xFFFF, 10);
    fine.writeUInt32LE(0xFFFFFFFF, 12);
    fine.writeUInt32LE(0xFFFFFFFF, 16);
  } else {
    fine.writeUInt16LE(voci.length, 8);
    fine.writeUInt16LE(voci.length, 10);
    fine.writeUInt32LE(cd.length, 12);
    fine.writeUInt32LE(posizione, 16);
  }
  const commento = Buffer.from(o.commento || '', 'utf8');
  fine.writeUInt16LE(commento.length, 20);
  parti.push(fine, commento);
  return Buffer.concat(parti);
}

const testoBuono = (n) => 'Appunti di storia numero ' + n + '. ' + 'La lotta per le investiture. '.repeat(40);

function materialeZip() {
  const dentro = creaZip([{ nome: 'foto lavagna.jpg', dati: Buffer.from([0xff, 0xd8, 0xff, 1, 2, 3, 4]), metodo: 0 }]);
  return creaZip([
    { nome: 'Storia/', cartella: true },
    { nome: 'Storia/verifica 1.pdf', dati: '%PDF-1.4 ' + testoBuono(1) },
    { nome: 'Storia/appunti.txt', dati: testoBuono(2), metodo: 0 },
    { nome: 'Storia/video.mp4', dati: 'video' },
    { nome: '__MACOSX/Storia/._appunti.txt', dati: 'mac' },
    { nome: 'Storia/.DS_Store', dati: 'mac' },
    { nome: 'Storia/~$bozza.docx', dati: 'word aperto' },
    { nome: 'Storia/Thumbs.db', dati: 'win' },
    { nome: 'Storia/segreto.pdf', dati: 'x', cifrato: true },
    { nome: 'Storia/strano.pdf', dati: 'x', metodo: 12 },
    { nome: 'Storia/libro intero.pdf', dati: 'x', metodo: 0, intero: 25 * 1024 * 1024 },
    { nome: Buffer.from([0x70, 0x65, 0x72, 0x63, 0x68, 0x8a, 0x2e, 0x74, 0x78, 0x74]), dati: testoBuono(3) }, // "perchè.txt" in CP850
    { nome: 'Storia\\Medioevo\\crociate.md', dati: testoBuono(4), utf8: true },
    { nome: 'dentro.zip', dati: dentro },
  ]);
}

const nomi = (st) => st.buoni.map((b) => b.name);
const testo = async (b) => Buffer.from(await b.leggi()).toString('utf8');

// --- archivi.js da solo ------------------------------------------------------------------------------

test('la tabella CP850 ha 128 lettere e "è" è al posto giusto', () => {
  assert.equal(Ar.CP850.length, 128);
  assert.equal(Ar.decodificaNome(new Uint8Array([0x8a, 0x85, 0x97]), false), 'èàù');
  assert.equal(Ar.decodificaNome(new Uint8Array(Buffer.from('perché.pdf')), false), 'perché.pdf', 'UTF-8 senza segno: si riconosce');
});

test('avanzi da lasciare fuori: Mac, nascosti, Thumbs.db, desktop.ini, ~$ di Word', () => {
  for (const p of ['__MACOSX/a.pdf', 'Storia/.DS_Store', '.git/x.txt', 'a/._b.pdf', 'Thumbs.db', 'x/desktop.ini', 'x/~$verifica.docx', '']) assert.ok(Ar.daIgnorare(p), p);
  for (const p of ['Storia/verifica.pdf', 'a.b.c.txt', 'note~.txt']) assert.equal(Ar.daIgnorare(p), false, p);
});

test('zip: escono i file che Lectio legge, anche dalle sottocartelle e dallo zip dentro lo zip; gli altri fuori col motivo', async () => {
  const zip = new File([materialeZip()], 'Materiale storia.zip', { type: 'application/zip' });
  const st = await Ar.espandi([zip]);
  assert.deepEqual(nomi(st), ['foto lavagna.jpg', 'perchè.txt', 'appunti.txt', 'crociate.md', 'verifica 1.pdf']);
  assert.equal(st.buoni.find((b) => b.name === 'crociate.md').percorso, 'Materiale storia.zip/Storia/Medioevo/crociate.md');
  assert.equal(st.buoni[0].percorso, 'Materiale storia.zip/dentro.zip/foto lavagna.jpg');
  const motivi = Object.fromEntries(st.saltati.map((s) => [s.nome, s.motivo]));
  assert.deepEqual(motivi, { 'libro intero.pdf': 'grande', 'segreto.pdf': 'password', 'strano.pdf': 'compressione', 'video.mp4': 'formato' });
  assert.equal(Ar.descrizione(st), '5 file da Materiale storia.zip');
  // I byte giusti: compresso, non compresso, e lo zip dentro lo zip.
  assert.equal(await testo(st.buoni.find((b) => b.name === 'appunti.txt')), testoBuono(2));
  assert.equal(await testo(st.buoni.find((b) => b.name === 'verifica 1.pdf')), '%PDF-1.4 ' + testoBuono(1));
  assert.equal(await testo(st.buoni.find((b) => b.name === 'perchè.txt')), testoBuono(3));
  assert.deepEqual([...new Uint8Array(await st.buoni[0].leggi())], [0xff, 0xd8, 0xff, 1, 2, 3, 4]);
  assert.equal(st.buoni[1].size, Buffer.byteLength(testoBuono(3)));
  assert.deepEqual(Ar.frasiSaltati(st.saltati), [
    'Lascio fuori 1 file più grande di 20 MB: libro intero.pdf.',
    'Lascio fuori 1 file protetto da password: segreto.pdf.',
    'Lascio fuori 1 file compresso in un modo che non so aprire: strano.pdf.',
    'Lascio fuori 1 file che Lectio non sa leggere: video.mp4.',
  ]);
});

test('zip ZIP64, col nome Unicode a parte e con un commento in fondo', async () => {
  const buf = creaZip([
    { nome: 'Verifica 1o quadrimestre.pdf', unicode: 'Verifica 1° quadrimestre.pdf', dati: '%PDF ' + testoBuono(5) },
    { nome: 'b.txt', dati: testoBuono(6), metodo: 0 },
  ], { zip64: true, commento: 'scaricato da Drive' });
  const st = await Ar.espandi([new File([buf], 'drive.zip')]);
  assert.deepEqual(nomi(st), ['b.txt', 'Verifica 1° quadrimestre.pdf']);
  assert.equal(await testo(st.buoni[1]), '%PDF ' + testoBuono(5));
  assert.equal(await testo(st.buoni[0]), testoBuono(6));
});

test('uno zip che mente sulla dimensione si ferma; uno rovinato lo dice; uno vuoto anche', async () => {
  const bugia = creaZip([{ nome: 'a.txt', dati: 'x'.repeat(5000), intero: 10 }]);
  const st = await Ar.espandi([new File([bugia], 'bugia.zip')]);
  assert.equal(st.buoni.length, 1);
  await assert.rejects(() => st.buoni[0].leggi());
  const rotto = await Ar.espandi([new File([Buffer.from('non sono uno zip, solo testo')], 'rotto.zip')]);
  assert.equal(rotto.buoni.length, 0);
  assert.equal(Ar.fraseNiente(rotto), 'Non riesco ad aprire lo zip: forse è rovinato o non è finito di scaricare.');
  const soloVideo = await Ar.espandi([new File([creaZip([{ nome: 'film.mp4', dati: 'v' }])], 'video.zip')]);
  assert.match(Ar.fraseNiente(soloVideo), /^Nello zip non c'è niente che Lectio sa leggere/);
  const heic = await Ar.espandi([new File(['x'], 'foto.heic')]);
  assert.match(Ar.fraseNiente(heic), /HEIC/, 'un file solo: la frase di sempre');
});

test('cartella trascinata: sottocartelle, zip dentro, avanzi fuori, letta a gruppi come fa il browser', async () => {
  const file = (nome, dati) => ({ isFile: true, isDirectory: false, name: nome, file: (ok) => ok(new File([dati], nome)) });
  const cartella = (nome, figli) => ({
    isFile: false, isDirectory: true, name: nome,
    createReader() {
      // Il browser le dà a gruppi (100 alla volta): qui due alla volta, poi un gruppo vuoto.
      let i = 0;
      return { readEntries: (ok) => { const g = figli.slice(i, i + 2); i += 2; setTimeout(() => ok(g), 1); } };
    },
  });
  const albero = cartella('Filosofia', [
    file('Platone.pdf', '%PDF ' + testoBuono(7)),
    file('desktop.ini', 'x'),
    cartella('Verifiche', [file('verifica 2.docx', 'PK docx finto'), file('voti.xlsx', 'x')]),
    cartella('.nascosta', [file('a.txt', 'x')]),
    file('foto.zip', creaZip([{ nome: 'IMG_1.jpg', dati: 'jpg' }, { nome: 'IMG_2.heic', dati: 'heic' }])),
  ]);
  const st = await Ar.espandi([{ cartella: albero }]);
  assert.deepEqual(nomi(st), ['IMG_1.jpg', 'Platone.pdf', 'verifica 2.docx']);
  assert.deepEqual(st.saltati.map((s) => s.percorso), ['Filosofia/foto.zip/IMG_2.heic', 'Filosofia/Verifiche/voti.xlsx']);
  assert.equal(Ar.descrizione(st), '3 file dalla cartella Filosofia');
  assert.equal(await testo(st.buoni[0]), 'jpg');
});

test('cartella scelta col pulsante (webkitRelativePath) e file misti; il limite dei 300 file', async () => {
  const conPercorso = (nome, rel, dati) => { const f = new File([dati], nome); Object.defineProperty(f, 'webkitRelativePath', { value: rel }); return f; };
  const st = await Ar.espandi([conPercorso('a.pdf', 'Arte/a.pdf', 'a'), conPercorso('b.txt', 'Arte/greca/b.txt', 'b'), conPercorso('._c.pdf', 'Arte/._c.pdf', 'c')]);
  assert.deepEqual(nomi(st), ['a.pdf', 'b.txt']);
  assert.equal(Ar.descrizione(st), '2 file dalla cartella Arte');
  const misti = await Ar.espandi([new File(['x'], 'uno.txt'), new File([creaZip([{ nome: 'due.txt', dati: 'y' }])], 'z.zip')]);
  assert.equal(Ar.descrizione(misti), '2 file');
  const tanti = Array.from({ length: Ar.MASSIMO_FILE + 5 }, (_, i) => new File(['x'], 'f' + i + '.txt'));
  const st2 = await Ar.espandi(tanti);
  assert.equal(st2.buoni.length, Ar.MASSIMO_FILE);
  assert.equal(st2.troppi, true);
});

test('frasi: nomi in elenco, uno e più file per motivo', () => {
  assert.equal(Ar.elencoNomi(['a']), 'a');
  assert.equal(Ar.elencoNomi(['a', 'b']), 'a e b');
  assert.equal(Ar.elencoNomi(['a', 'b', 'c', 'd']), 'a, b, c e un altro');
  assert.equal(Ar.elencoNomi(['a', 'b', 'c', 'd', 'e']), 'a, b, c e altri 2');
  assert.deepEqual(Ar.frasiSaltati([{ nome: 'x.mp4', motivo: 'formato' }, { nome: 'y.xlsx', motivo: 'formato' }, { nome: 'v.txt', motivo: 'vuoto' }]),
    ['Lascio fuori 2 file che Lectio non sa leggere: x.mp4 e y.xlsx.', 'Lascio fuori 1 file vuoto: v.txt.']);
});

test('file trascinati: le cartelle si prendono durante l\'evento; senza webkitGetAsEntry restano i file', () => {
  const dir = { isDirectory: true, name: 'Storia' };
  const f = new File(['x'], 'a.pdf');
  const dt = { items: [{ kind: 'file', webkitGetAsEntry: () => dir, getAsFile: () => null }, { kind: 'file', webkitGetAsEntry: () => ({ isDirectory: false }), getAsFile: () => f }, { kind: 'string' }], files: [f] };
  const presi = Ar.daTrascinati(dt);
  assert.equal(presi.length, 2);
  assert.equal(presi[0].cartella, dir);
  assert.equal(presi[1], f);
  assert.deepEqual(Ar.daTrascinati({ files: [f] }), [f]);
  assert.deepEqual(Ar.daTrascinati(null), []);
});

// --- Il foglio che manda -------------------------------------------------------------------------------

async function apri(prima) {
  const s = B.server({});
  if (prima) prima(s);
  const p = await B.pagina(s, {});
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  p.A.vai('#materia/1');
  await p.aspetta(() => p.q('.zona-trascina'));
  return p;
}
const query = (c) => new URLSearchParams(c.percorso.split('?')[1]);

test('uno zip trascinato nella materia: il foglio dice quanti file e quali restano fuori, poi li manda uno per uno', opz, async () => {
  const p = await apri();
  assert.match(p.q('[data-zona="materia"]').textContent, /Trascina qui file, cartelle o zip/);
  assert.ok(p.q('[data-azione="scegli-cartella"]'));
  p.A.suFileTrascinati([new p.w.File([materialeZip()], 'Materiale storia.zip', { type: 'application/zip' })]);
  await p.aspetta(() => p.q('[data-azione="aggiungi-file"]'));
  assert.equal(p.q('[data-campo="quanti-file"]').textContent, '5 file da Materiale storia.zip');
  assert.match(p.q('[data-campo="nomi-file"]').textContent, /foto lavagna\.jpg, perchè\.txt/);
  assert.match(p.q('.foglio .messaggio.avviso').textContent, /Lascio fuori 1 file che Lectio non sa leggere: video\.mp4\./);
  assert.equal(p.q('#carica-titolo'), null, 'con più file niente titolo');
  assert.match(p.q('.foglio').textContent, /0,02 € per 1 foto|circa 0,4 cent a foto/);
  assert.equal(p.q('[data-azione="aggiungi-file"]').textContent, 'Aggiungi 5 file');
  p.clic(p.q('[data-azione="aggiungi-file"]'));
  await p.aspetta(() => p.q('.avviso-breve'));
  assert.match(p.q('.avviso-breve').textContent, /5 file aggiunti a Storia/);
  await p.aspetta(() => !p.q('.foglio'));
  const chiamate = p.s.chiamate('POST', '/locale/carica');
  assert.deepEqual(chiamate.map((c) => query(c).get('nome')), ['foto lavagna.jpg', 'perchè.txt', 'appunti.txt', 'crociate.md', 'verifica 1.pdf']);
  assert.equal(chiamate[2].grezzo.toString('utf8'), testoBuono(2));
  assert.equal(chiamate[4].grezzo.toString('utf8'), '%PDF-1.4 ' + testoBuono(1));
  assert.ok(chiamate.every((c) => query(c).get('tipo') === 'appunti' && query(c).get('titolo') === null));
});

test('rete giù a metà: Riprova riparte dal file fermo, senza rimandare quelli già andati; un file illeggibile resta fuori', opz, async () => {
  const p = await apri();
  const zip = creaZip([{ nome: 'a.txt', dati: 'aaa' }, { nome: 'b.txt', dati: 'bbb' }, { nome: 'c.txt', dati: 'ccc' }, { nome: 'd.txt', dati: 'ddd' }]);
  let giu = false;
  p.s.risposta = (m, percorso) => {
    if (!percorso.startsWith('/locale/carica')) return undefined;
    const nome = new URLSearchParams(percorso.split('?')[1]).get('nome');
    if (nome === 'b.txt') return [422, { errore: 'In questo file non c\'è testo da leggere.', codice: 'non_valido' }];
    if (nome === 'c.txt' && !giu) { giu = true; return 'rete'; }
    return undefined;
  };
  p.A.suFileTrascinati([new p.w.File([zip], 'lettere.zip')]);
  await p.aspetta(() => p.q('[data-azione="aggiungi-file"]'));
  p.clic(p.q('[data-azione="aggiungi-file"]'));
  await p.aspetta(() => p.q('.foglio .messaggio.errore'));
  assert.match(p.q('.foglio .messaggio.errore').textContent, /^1 file è già andato\. c\.txt: /);
  assert.equal(p.q('[data-azione="aggiungi-file"]').textContent, 'Manda gli altri 2 file');
  const prima = p.s.chiamate('POST', '/locale/carica').map((c) => query(c).get('nome'));
  assert.deepEqual(prima, ['a.txt', 'b.txt', 'c.txt']);
  p.clic(p.q('.foglio .messaggio.errore button'));
  await p.aspetta(() => p.q('[data-esito="fuori"]'));
  const tutte = p.s.chiamate('POST', '/locale/carica').map((c) => query(c).get('nome'));
  assert.deepEqual(tutte, ['a.txt', 'b.txt', 'c.txt', 'c.txt', 'd.txt'], 'a.txt non è partito due volte');
  assert.match(p.q('[data-esito="fuori"]').textContent, /3 file aggiunti\. Questo file non è entrato:b\.txt: In questo file non c'è testo da leggere\./);
  assert.equal(p.q('[data-azione="aggiungi-file"]').textContent, 'Chiudi');
  p.clic(p.q('[data-azione="aggiungi-file"]'));
  await p.aspetta(() => !p.q('.foglio'));
});

test('zip senza niente da leggere: un avviso, niente foglio; il foglio Aggiungi offre file o zip e la cartella del computer', opz, async () => {
  const p = await apri();
  p.A.suFileTrascinati([new p.w.File([creaZip([{ nome: 'film.mp4', dati: 'v' }])], 'video.zip')]);
  await p.aspetta(() => p.q('.avviso-breve'));
  assert.match(p.q('.avviso-breve').textContent, /Nello zip non c'è niente che Lectio sa leggere/);
  assert.equal(p.q('[data-azione="aggiungi-file"]'), null);
  assert.equal(p.s.chiamate('POST', '/locale/carica').length, 0);
  p.A.apriAggiungi(p.A.materiaDi(1));
  await p.aspetta(() => p.q('[data-riga="file"]'));
  assert.match(p.q('[data-riga="file"]').textContent, /File o zip/);
  assert.match(p.q('[data-riga="cartella-pc"]').textContent, /Cartella del computer/);
  assert.match(p.q('[data-visita="trascina-foglio"]').textContent, /Trascina qui file, cartelle o zip/);
});
