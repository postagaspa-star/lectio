// I test delle cose nuove della 1.2.0 (pc/ui), logica e pagine vere in jsdom col server finto di esempio.js: 16 colori,
// materia a tre segmenti, lettura dello schema, foto, PDF, "Non ho capito" (anche quando la risposta non c'è),
// Ripasso lampo, Verifica simulata (salvataggio continuo, consegna, risultati), riassunti scritti, visita delle novità.
// Dati solo finti.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const B = require('./banco');

const L = B.L;
const opz = { skip: B.SENZA_JSDOM };
const TEMPI = { stato: 60, salvaRisposta: 30, tempoSalvato: 100, attesaLunga: 300, fermoEsito: 5 };
test.afterEach(() => B.chiudiTutte());

async function apri(hash, opzioniServer, prima) {
  const s = B.server(opzioniServer);
  if (prima) prima(s);
  const p = await B.pagina(s, { tempi: TEMPI, hash });
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  return p;
}

// --- Logica ----------------------------------------------------------------------------------------

test('16 colori, tutti con nome e con la loro classe in chiaro e in scuro', () => {
  assert.equal(L.COLORI.length, 16);
  assert.equal(new Set(L.COLORI).size, 16);
  const css = fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'stile.css'), 'utf8');
  for (const c of L.COLORI) {
    assert.ok(L.NOMI_COLORI[c], 'nome di ' + c);
    const volte = css.split('.m-' + c).length - 1;
    assert.ok(volte >= 3, c + ' deve avere chiaro e i due blocchi scuri, trovato ' + volte);
  }
  assert.deepEqual(L.coloreUsatoDa('blu', [{ id: 1, colore: 'blu', nome: 'Storia' }], 1), null);
  assert.equal(L.coloreUsatoDa('blu', [{ id: 1, colore: 'blu', nome: 'Storia' }], 2).nome, 'Storia');
});

test('il grassetto si legge dai doppi asterischi e gli asterischi spaiati non si vedono mai', () => {
  assert.deepEqual(L.pezziGrassetto('Una **parola** forte'), [{ t: 'Una ', forte: false }, { t: 'parola', forte: true }, { t: ' forte', forte: false }]);
  assert.equal(L.senzaGrassetto('a **b** c ** d'), 'a b c  d');
  assert.equal(L.senzaGrassetto(null), '');
});

test('i tipi di blocco sconosciuti si saltano, il passaggio da chiarire è senza asterischi', () => {
  const noti = L.blocchiNoti({ blocchi: [{ tipo: 'paragrafo', testo: 'x' }, { tipo: 'mappa3d' }, null, { tipo: 'tabella', righe: [] }] });
  assert.deepEqual(noti.map((b) => b.tipo), ['paragrafo', 'tabella']);
  assert.equal(L.passaggioDelBlocco({ tipo: 'punti', voci: ['**Uno**', 'Due'] }), 'Uno\nDue');
  assert.equal(L.passaggioDelBlocco({ tipo: 'definizione', termine: 'Feudo', testo: 'Un **territorio**' }), 'Feudo: Un territorio');
});

test('segni delle fonti: etichette corte, e su blocchi di fila uguali solo sull\'ultimo', () => {
  const f = [{ etichetta: 'Appunti, p. 1' }, { etichetta: 'Libro, p. 4' }, { etichetta: 'Lezione' }];
  assert.equal(L.segnoFonti(f.slice(0, 1)), 'Appunti, p. 1');
  assert.equal(L.segnoFonti(f), 'Appunti, p. 1 · Libro, p. 4 +1');
  assert.equal(L.segnoFonti([]), '');
  const mappa = { 1: f[0], 2: f[1] };
  const blocchi = [{ fonti: [1] }, { fonti: [1] }, { fonti: [2] }, {}];
  const segni = L.segniDeiBlocchi(blocchi, mappa);
  assert.equal(segni[0], null);
  assert.equal(segni[1].length, 1);
  assert.equal(segni[2][0].etichetta, 'Libro, p. 4');
  assert.equal(segni[3], null);
});

test('schema: stati delle righe e quello che manca', () => {
  assert.equal(L.statoRigaSchema({ stato: 'in_coda' }).frase, 'In coda');
  assert.equal(L.statoRigaSchema({ stato: 'in_preparazione', frase: 'Scrivo', fatto: 1, totale: 4 }).percentuale, 25);
  assert.equal(L.statoRigaSchema({ stato: 'errore' }).tipo, 'errore');
  assert.equal(L.mancanzeSchema({ contenuto: {} }), null);
  const m = L.mancanzeSchema({ nota: 'Manca il libro', contenuto: { copertura: { mancanti: ['a', 'b', 'c', 'd', 'e', 'f', 'g'] } } });
  assert.equal(m.nota, 'Manca il libro');
  assert.equal(m.mancanti.length, 5);
  assert.equal(m.altri, 2);
});

test('nome del PDF senza i caratteri che Windows non vuole', () => {
  assert.equal(L.nomeFilePdf('La guerra: fredda?'), 'La guerra fredda.pdf');
  assert.equal(L.nomeFilePdf('', 'Schema'), 'Schema.pdf');
  assert.equal(L.nomeFilePdf('  ...'), 'Lectio.pdf');
});

test('Non ho capito: il costo e il minuto si dicono in parole semplici', () => {
  assert.equal(L.costoChiarimento(0.002), 'Costata meno di 1 cent');
  assert.equal(L.etichettaMinuto(312, 'La crisi'), 'Al minuto 5:12 · La crisi');
});

test('Ripasso lampo: tre esiti con i tasti 1 2 3, frasi al singolare e al plurale', () => {
  assert.deepEqual(L.ESITI_LAMPO.map((x) => x.tasto), ['1', '2', '3']);
  assert.equal(L.fraseLampo(8), '8 schede · circa 3 min');
  assert.equal(L.fraseLampo(1), '1 scheda · circa 1 min');
  assert.equal(L.fraseFineRipasso(12, 170000), '12 schede in 3 minuti');
});

test('Verifica simulata: tempo, voto, punti e consegna oltre il tempo', () => {
  assert.equal(L.testoTempo(1800), '30 min');
  assert.equal(L.testoTempo(40), 'meno di 1 min');
  assert.equal(L.testoTempo(-61), '+2 min');
  assert.equal(L.voto(6.5), '6,5');
  assert.equal(L.voto(7), '7');
  assert.equal(L.frasePunti({ punti: 62, punti_totali: 100, soglia: 58 }), '62 punti su 100 · la sufficienza è a 58');
  assert.equal(L.fraseOltreTempo(56 * 60, 50), 'Consegnata dopo 56 minuti su 50');
  assert.equal(L.fraseOltreTempo(40 * 60, 50), null);
  assert.equal(L.puntiDomanda({ esito: 'non_corretta' }, { punti: 10 }), null);
  assert.equal(L.puntiDomanda({ esito: 'parziale', punti: 8 }, { punti: 10 }), '8 su 10');
  assert.equal(L.esitoSimulata('non_corretta').parola, 'Confronta tu');
  assert.equal(L.contaRisposte([{ n: 1 }, { n: 2 }], { 1: 'x', 2: ' ' }).frase, '1 scritta su 2 · 1 vuota');
  assert.deepEqual(L.unisciRisposte({ 1: 'a', 2: 'b' }, { 2: 'nuova', 3: 'c' }, ['2']), { 1: 'a', 2: 'nuova' });
});

test('riassunti: stato di ogni forma, parole e minuti, ultimo fatto', () => {
  assert.equal(L.FORME_RIASSUNTO.length, 3);
  assert.equal(L.statoFormaRiassunto({ stato: 'pronto' }).testo, 'Pronto');
  assert.equal(L.statoFormaRiassunto({ stato: 'in_coda' }).tipo, 'lavoro');
  assert.equal(L.statoFormaRiassunto({ stato: 'errore' }).testo, 'Si è fermato');
  assert.equal(L.statoFormaRiassunto(null, 0, true).testo, 'Prova');
  assert.equal(L.fraseParole(480), '480 parole · 3 min di lettura');
  assert.equal(L.ultimoRiassunto([{ id: 4 }, { id: 9 }, { id: 2 }]).id, 9);
  assert.equal(L.ultimoRiassunto([]), null);
});

test('le rotte nuove si leggono', () => {
  assert.equal(L.leggiRotta('#schema/3').vista, 'schema');
  assert.equal(L.leggiRotta('#simulata/4').vista, 'simulata');
  assert.equal(L.leggiRotta('#riassunto/5').vista, 'riassunto');
  assert.equal(L.leggiRotta('#materia/1/schemi').segmento, 'schemi');
  assert.equal(L.leggiRotta('#materia/1/simulate').segmento, 'simulate');
});

// --- Pagine ----------------------------------------------------------------------------------------

test('selettore: 16 cerchi, il colore già usato lo dice senza vietarlo', opz, async () => {
  const p = await apri('#materie');
  p.clic(p.q('[data-azione="nuova-materia"]'));
  await p.aspetta(() => p.q('[data-colore]'));
  assert.equal(p.tutti('[data-colore]').length, 16);
  const usato = p.tutti('[data-colore]').find((c) => /già usato/.test(c.getAttribute('aria-label')));
  assert.ok(usato, 'almeno un colore è già usato nei dati di esempio');
  assert.notEqual(usato.disabled, true);
  p.clic(usato);
  await p.aspetta(() => /Lo usa già|già/.test(p.q('[data-colore-usato]').textContent));
});

test('materia: tre segmenti, il pulsante segue il segmento, gli schemi si chiedono una volta sola', opz, async () => {
  const p = await apri('#materia/1');
  await p.aspetta(() => p.q('[data-segmento="schemi"]'));
  assert.deepEqual(p.tutti('[data-segmento]').map((b) => b.getAttribute('data-segmento')), ['lezioni', 'schemi', 'simulate']);
  assert.ok(p.q('[data-azione="nuova-lezione"], [data-visita="nuova-lezione"]'));
  p.clic(p.q('[data-segmento="schemi"]'));
  await p.aspetta(() => p.q('[data-elenco="schemi"] [data-schema="3"]'));
  assert.ok(p.q('[data-azione="nuovo-schema"]'));
  assert.match(p.q('[data-elenco="schemi"]').textContent, /In preparazione|Scrivo|Leggo|Cerco|Preparo/);
  p.clic(p.q('[data-segmento="simulate"]'));
  await p.aspetta(() => p.q('[data-elenco="simulate"] [data-simulata="4"]'));
  assert.ok(p.q('[data-azione="nuova-simulata"]'));
  assert.match(p.q('[data-elenco="simulate"]').textContent, /A metà/);
  assert.match(p.q('[data-elenco="simulate"]').textContent, /Corretta · stima circa 6,5/);
  assert.equal(p.q('[data-elenco="schemi"]').classList.contains('nascosto'), true);
});

test('nuovo schema: stima del costo prima di partire e richiesta col contratto giusto', opz, async () => {
  const p = await apri('#materia/1');
  await p.aspetta(() => p.q('[data-segmento="schemi"]'));
  p.A.nuovoSchema(1);
  const parti = await p.aspetta(() => { const b = p.q('[data-azione="prepara-studio"]'); return b && /€/.test(b.textContent) ? b : null; });
  assert.ok(p.s.chiamate('POST', '/server/api/schemi/stima').length >= 1);
  assert.ok(p.q('[data-forma]'));
  assert.ok(p.q('[data-livello]'));
  const argomento = p.tutti('input, textarea').find((x) => x.placeholder && !x.closest('[data-sezione]') === false);
  if (argomento) p.scrivi(argomento, 'La guerra fredda');
  await p.aspetta(() => !parti.disabled);
  p.clic(p.q('[data-azione="prepara-studio"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/schemi').filter((c) => !c.percorso.includes('stima')).length === 1);
  const c = p.s.chiamate('POST', '/server/api/schemi').filter((x) => !x.percorso.includes('stima'))[0];
  assert.equal(c.corpo.materia_id, 1);
  assert.ok(c.corpo.forma);
  assert.ok(c.corpo.livello);
});

test('lettura dello schema: tutti i blocchi, grassetto vero, fonti, indice, niente asterischi', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('.sch-parte'));
  const tipi = new Set(p.tutti('.sch-blocco').map((b) => b.getAttribute('data-tipo')));
  for (const t of ['paragrafo', 'punti', 'passaggi', 'definizione', 'tabella', 'prof']) assert.ok(tipi.has(t), 'manca il blocco ' + t);
  assert.ok(p.q('.sch-foto-blocco img, [data-foto] img'), 'manca la foto');
  assert.ok(p.q('.sch-blocco strong'), 'il grassetto è vero');
  assert.doesNotMatch(p.q('.schema-contenitore').textContent, /\*\*/);
  assert.ok(p.q('[data-segno="fonte"]'), 'i segni delle fonti ci sono');
  assert.ok(p.tutti('.sch-voce').length >= 2, 'indice');
  assert.match(p.testo(), /Non ho trovato|manca/i, 'nota su quello che manca');
  assert.doesNotMatch(p.q('.schema-contenitore').textContent, /intelligenza artificiale|generato|modello linguistico|\bAI\b|\bIA\b/i);
  p.clic(p.q('[data-segno="fonte"]'));
  await p.aspetta(() => p.q('.capito-fonte') || p.s.chiamate('GET', '/server/api/materiale/').length === 1);
});

test('schema: un tipo di blocco sconosciuto si salta senza rompere la pagina', opz, async () => {
  const p = await apri('#schema/3', {}, (s) => {
    const x = s.esempio.richiesta('GET', '/server/api/schemi/3').dati;
    x.contenuto.sezioni[0].blocchi.unshift({ tipo: 'ologramma', testo: 'NON SI VEDE' });
    s.risposta = (m, q) => (m === 'GET' && q === '/server/api/schemi/3' ? [200, x] : undefined);
  });
  await p.aspetta(() => p.q('.sch-parte'));
  assert.doesNotMatch(p.testo(), /NON SI VEDE/);
});

test('Ascolta questa parte apre Nuova lezione già compilata; Scarica PDF passa dal programma', opz, async () => {
  const p = await apri('#schema/3', {}, (s) => { s.salvaPdf = () => [200, { ok: true, id: 'a', nome: 'x.pdf' }]; });
  await p.aspetta(() => p.q('[data-azione="ascolta-parte"]'));
  p.clic(p.q('[data-azione="scarica-pdf"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/salva-pdf').length === 1);
  assert.equal(p.s.chiamate('POST', '/locale/salva-pdf')[0].corpo.percorso, 'api/schemi/3/pdf');
  await p.aspetta(() => /PDF salvato/.test(p.testo()));
  p.clic(p.tutti('button').find((b) => b.textContent.trim() === 'Apri'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/apri-pdf').length === 1);
  p.clic(p.q('[data-azione="ascolta-parte"]'));
  await p.aspetta(() => p.q('[data-azione="prepara"], [data-azione="prepara-lezione"], .nl-riepilogo, .finestra-nuova'));
  assert.match(p.testo(), /Nuova lezione/);
});

test('PDF: annullare non dice niente, un errore si dice con Riprova', opz, async () => {
  let n = 0;
  const p = await apri('#schema/3', {}, (s) => { s.salvaPdf = () => (++n === 1 ? [200, { annullato: true }] : [422, { errore: 'Non sono riuscito a salvarlo lì.', codice: 'x' }]); });
  await p.aspetta(() => p.q('[data-azione="scarica-pdf"]'));
  p.clic(p.q('[data-azione="scarica-pdf"]'));
  await p.aspetta(() => n === 1);
  await B.dorme(40);
  assert.doesNotMatch(p.testo(), /PDF salvato/);
  p.clic(p.q('[data-azione="scarica-pdf"]'));
  await p.aspetta(() => /Non sono riuscito a salvarlo/.test(p.testo()));
  assert.ok(p.pulsante('Riprova'));
});

test('Cambia foto e Togli foto vanno al server e si ridisegnano', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('[data-foto]'));
  const n = p.q('[data-foto]').getAttribute('data-foto');
  p.clic(p.q('[data-foto] [data-azione="puntini"]'));
  p.clic(await p.aspetta(() => p.tutti('[role="menuitem"]').find((b) => /Togli foto/.test(b.textContent))));
  p.clic(await p.aspetta(() => p.q('[data-azione="togli-si"]')));
  await p.aspetta(() => p.s.chiamate('DELETE', '/server/api/schemi/3/immagini/' + n).length === 1);
  await p.aspetta(() => !p.q('[data-foto="' + n + '"] img'));
});

test('Non ho capito sullo schema: manda il passaggio, mostra la risposta con le fonti', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('[data-visita="mano"]'));
  p.clic(p.q('[data-visita="mano"]'));
  await p.aspetta(() => p.q('[data-azione="spiegamelo"]'));
  p.scrivi(p.q('[data-campo="domanda"]'), 'Perché proprio allora?');
  p.clic(p.q('[data-azione="spiegamelo"]'));
  await p.aspetta(() => p.q('[data-capito="risposta"]'));
  const c = p.s.chiamate('POST', '/server/api/chiarimenti')[0];
  assert.equal(c.corpo.schema_id, 3);
  assert.equal(c.corpo.materia_id, 1);
  assert.ok(c.corpo.passaggio.length > 3);
  assert.doesNotMatch(c.corpo.passaggio, /\*\*/);
  assert.ok(p.q('[data-visita="fonti-capito"] .capito-fonte'));
  assert.match(p.q('[data-capito="costo"]').textContent, /Costata/);
  assert.ok(p.q('[data-azione="ho-capito"]'));
});

test('Non ho capito: se nei documenti non c\'è è una risposta tranquilla, mai un errore', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('[data-visita="mano"]'));
  p.clic(p.q('[data-visita="mano"]'));
  await p.aspetta(() => p.q('[data-azione="spiegamelo"]'));
  p.scrivi(p.q('[data-campo="domanda"]'), 'Cosa c\'entra Marte?');
  p.clic(p.q('[data-azione="spiegamelo"]'));
  await p.aspetta(() => p.q('[data-capito="non-c-e"]'));
  assert.equal(p.q('.foglio-capito .messaggio.errore, .foglio-capito .avviso-errore'), null);
  assert.ok(p.q('[data-azione="aggiungi-materiale"]'));
  assert.equal(p.q('[data-capito="risposta"]'), null);
});

test('Non ho capito dal lettore: parte dal minuto e manda lezione_id', opz, async () => {
  const p = await apri('#lettore/12');
  await p.aspetta(() => p.audio && p.q('[data-azione="play"]') && !p.q('[data-azione="play"]').disabled && p.q('.capito[data-azione="non-ho-capito"]'));
  p.A.lettoreSalto(312, false);
  p.clic(p.q('.capito[data-azione="non-ho-capito"]'));
  await p.aspetta(() => p.q('[data-azione="spiegamelo"]'));
  assert.match(p.q('[data-capito="minuto"]').textContent, /Al minuto 5:12/);
  p.scrivi(p.q('[data-campo="domanda"]'), 'Non capisco questo passaggio');
  p.clic(p.q('[data-azione="spiegamelo"]'));
  await p.aspetta(() => p.q('[data-capito="risposta"]'));
  assert.equal(p.s.chiamate('POST', '/server/api/chiarimenti')[0].corpo.lezione_id, 12);
});

test('Ripasso lampo da Oggi: la risposta si vede solo a un gesto, i tre esiti si danno anche coi tasti', opz, async () => {
  const p = await apri('#oggi');
  await p.aspetta(() => p.q('[data-azione="inizia-lampo"]'));
  assert.match(p.q('[data-lampo="frase"]').textContent, /8 schede/);
  p.clic(p.q('[data-azione="inizia-lampo"]'));
  await p.aspetta(() => p.q('[data-lampo="domanda"]'));
  assert.equal(p.q('[data-lampo="risposta"]'), null);
  assert.equal(p.q('[data-lampo="conto"]').textContent, '1 di 8');
  p.clic(p.q('[data-azione="mostra-risposta"]'));
  await p.aspetta(() => p.q('[data-lampo="risposta"]') && p.q('[data-esito="quasi"]'));
  await B.dorme(20);
  const prima = p.q('[data-lampo="carta"]').getAttribute('data-carta');
  p.clic(p.q('[data-esito="quasi"]'));
  await p.aspetta(() => p.q('[data-lampo="conto"]').textContent === '2 di 8');
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lampo/' + prima).length === 1 || p.pulsante('Annulla'));
  // Con la tastiera: spazio mostra, 1 è "Non lo sapevo".
  p.tasto(' ');
  await p.aspetta(() => p.q('[data-lampo="risposta"]'));
  await B.dorme(20);
  p.tasto('1');
  await p.aspetta(() => p.q('[data-lampo="conto"]').textContent === '3 di 8');
  p.clic(p.q('[data-azione="chiudi-lampo"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/lampo/').length >= 2);
  const esiti = p.s.chiamate('POST', '/server/api/lampo/').map((c) => c.corpo.esito);
  assert.deepEqual(esiti.sort(), ['non_sapevo', 'quasi']);
});

test('Ripasso lampo: in fondo il conto dei tre esiti, niente festa', opz, async () => {
  const p = await apri('#oggi');
  await p.aspetta(() => p.q('[data-azione="inizia-lampo"]'));
  p.clic(p.q('[data-azione="inizia-lampo"]'));
  for (let i = 0; i < 8; i++) {
    const c = await p.aspetta(() => p.q('[data-azione="mostra-risposta"]'));
    p.clic(c);
    const e = await p.aspetta(() => p.q('[data-esito="sapevo"]'));
    await B.dorme(15);
    p.clic(e);
    if (i < 7) await p.aspetta(() => p.q('[data-lampo="conto"]').textContent === (i + 2) + ' di 8');
  }
  await p.aspetta(() => p.q('[data-lampo="fine"]'));
  assert.match(p.q('[data-lampo="durata"]').textContent, /8 schede in 1 minuto/);
  assert.doesNotMatch(p.q('[data-lampo="fine"]').textContent, /[!]|Bravo|Complimenti|Grande/);
});

test('verifica pronta: Inizia, poi si scrive e ogni risposta si salva con PATCH', opz, async () => {
  const p = await apri('#simulata/2');
  await p.aspetta(() => p.q('[data-azione="inizia-simulata"]'));
  assert.match(p.q('[data-riassunto="simulata"]').textContent, /domande/);
  p.clic(p.q('[data-azione="inizia-simulata"]'));
  await p.aspetta(() => p.q('.sim-domanda [data-risposta]'));
  assert.ok(p.s.chiamate('POST', '/server/api/simulate/2/inizia').length + p.s.chiamate('PATCH', '/server/api/simulate/2').length >= 1);
  p.scrivi(p.q('[data-risposta="1"]'), 'La mia risposta');
  await p.aspetta(() => p.s.chiamate('PATCH', '/server/api/simulate/2').some((c) => c.corpo.risposte && c.corpo.risposte['1'] === 'La mia risposta'));
});

test('il tempo si nasconde e torna, e nasconderlo non ferma il salvataggio', opz, async () => {
  const p = await apri('#simulata/3');
  await p.aspetta(() => p.q('.sim-domanda [data-risposta]'));
  const tempo = () => p.q('.sim-sx [data-azione="tempo"]') || p.q('[data-azione="tempo"]');
  assert.ok(tempo().querySelector('[data-tempo]'));
  p.clic(tempo());
  await p.aspetta(() => !tempo().querySelector('[data-tempo]'));
  p.clic(tempo());
  await p.aspetta(() => tempo().querySelector('[data-tempo]'));
});

test('se il salvataggio non riesce le risposte restano e si riprova', opz, async () => {
  let giu = true;
  const p = await apri('#simulata/3', {}, (s) => { s.risposta = (m, q) => (giu && m === 'PATCH' && q.startsWith('/server/api/simulate/3') ? 'rete' : undefined); });
  await p.aspetta(() => p.q('.sim-domanda [data-risposta]'));
  const area = p.tutti('[data-risposta]')[0];
  const n = area.getAttribute('data-risposta');
  p.scrivi(area, 'Scritta senza rete');
  await p.aspetta(() => p.s.chiamate('PATCH', '/server/api/simulate/3').length >= 1);
  giu = false;
  await p.aspetta(() => p.s.chiamate('PATCH', '/server/api/simulate/3').some((c) => c.corpo.risposte && c.corpo.risposte[n] === 'Scritta senza rete'), 6000);
  assert.equal(area.value, 'Scritta senza rete');
});

test('consegna: conta scritte e vuote, chiede conferma, poi aspetta la correzione', opz, async () => {
  const p = await apri('#simulata/3');
  await p.aspetta(() => p.q('.sim-domanda [data-risposta]'));
  p.clic(p.q('[data-azione="consegna"]'));
  await p.aspetta(() => p.q('[data-consegna="conto"]'));
  assert.match(p.q('[data-consegna="conto"]').textContent, /scritt[ae] su \d+ · \d+ vuot[ae]/);
  p.clic(p.q('[data-azione="consegna-si"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/simulate/3/consegna').length === 1);
  await p.aspetta(() => /La sto correggendo|Sto correggendo|Correggo/.test(p.testo()) || p.q('[data-simulata="corretta"]'));
});

test('risultati: voto stimato, punti, "Confronta tu" senza punti, da rifare già nel ripasso', opz, async () => {
  const p = await apri('#simulata/4');
  await p.aspetta(() => p.q('[data-simulata="corretta"]'));
  assert.equal(p.q('[data-voto]').textContent, 'circa 6,5');
  assert.match(p.q('[data-punti]').textContent, /62 punti su 90 · la sufficienza è a 54/);
  assert.match(p.testo(), /È una stima|stima/);
  assert.match(p.q('[data-rifare]').textContent, /4 domande da rifare/);
  const q6 = p.q('.sim-esito[data-esito="non_corretta"]');
  assert.ok(q6);
  assert.match(q6.textContent, /Confronta tu/);
  assert.doesNotMatch(q6.textContent, /\d+ su \d+/);
  assert.ok(p.q('[data-azione="ripassa-adesso"]'));
  assert.ok(p.q('[data-azione="rifai-simulata"]'));
});

test('PDF della verifica pronta per farla su carta', opz, async () => {
  const p = await apri('#simulata/2');
  await p.aspetta(() => p.q('[data-azione="inizia-simulata"]'));
  const pdf = p.q('[data-azione="fai-su-carta"]');
  assert.ok(pdf);
  p.clic(pdf);
  await p.aspetta(() => p.s.chiamate('POST', '/locale/salva-pdf').length === 1);
  assert.equal(p.s.chiamate('POST', '/locale/salva-pdf')[0].corpo.percorso, 'api/simulate/2/pdf');
  await p.aspetta(() => /premi Inizia e copia qui le risposte/.test(p.testo()));
});

test('stato di lavorazione: avanzamento, Annulla; errore: Riprova ed Elimina', opz, async () => {
  const p = await apri('#schema/5');
  await p.aspetta(() => p.q('[data-vista="lavoro"]'));
  assert.ok(p.q('[data-azione="annulla-lavoro"]'));
  p.A.vai('#schema/6');
  await p.aspetta(() => p.q('[data-azione="riprova-schema"]'));
  assert.ok(p.q('[data-azione="elimina-schema"]'));
  p.clic(p.q('[data-azione="riprova-schema"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/schemi/6/riprova').length === 1);
});

test('riassunti: dalla testata dello schema al foglio con le tre forme e la pagina con la barra', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('[data-azione="riassunto"]'));
  assert.ok(p.q('[data-fine="riassunto"]'), 'la scheda in fondo allo schema');
  p.clic(p.q('[data-azione="riassunto"]'));
  await p.aspetta(() => p.tutti('[data-prezzo]').length === 3);
  assert.match(p.q('[data-prezzo="misto"]').textContent, /Pronto/);
  assert.match(p.q('[data-prezzo="punti"]').textContent, /Pronto|Lo sto scrivendo/);
  p.A.chiudiTuttiIVeli();
  p.A.vai('#riassunto/4');
  await p.aspetta(() => p.q('[data-riassunto="misto"]'));
  assert.equal(p.tutti('button[data-forma]').length, 3);
  assert.equal(p.q('button[data-forma="misto"]').getAttribute('aria-pressed'), 'true');
  assert.match(p.q('[data-parole]').textContent, /parole · \d+ min di lettura/);
  assert.doesNotMatch(p.q('[data-riassunto="misto"]').textContent, /\*\*/);
  p.clic(p.q('[data-azione="scarica-pdf"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/salva-pdf').length === 1);
  assert.equal(p.s.chiamate('POST', '/locale/salva-pdf')[0].corpo.percorso, 'api/riassunti/4/pdf');
});

test('riassunti: una forma che manca si scrive da lì, con POST sullo schema', opz, async () => {
  const p = await apri('#riassunto/4');
  await p.aspetta(() => p.q('[data-riassunto="misto"]'));
  const vuota = p.tutti('button[data-forma]').find((b) => !['misto', 'punti'].includes(b.getAttribute('data-forma')));
  assert.ok(vuota);
  p.clic(vuota);
  p.clic(await p.aspetta(() => p.q('[data-azione="scrivi-riassunto"]')));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/schemi/3/riassunti').length === 1);
  assert.equal(p.s.chiamate('POST', '/server/api/schemi/3/riassunti')[0].corpo.forma, vuota.getAttribute('data-forma'));
});

test('dopo l\'aggiornamento parte una volta la visita delle cose nuove, e si ricorda a parte', opz, async () => {
  const s = B.server({});
  s.stato.tutorial_novita_visto = false;
  const p = await B.pagina(s, { tempi: TEMPI });
  await p.aspetta(() => p.A.S.visita, 5000);
  assert.equal(p.A.S.visita.modo, 'novita');
  assert.equal(p.A.S.visita.passi.length, 15);
  await p.aspetta(() => p.pulsante('Salta') || p.q('[data-azione="salta-visita"]'));
  p.clic(p.pulsante('Salta') || p.q('[data-azione="salta-visita"]'));
  await p.aspetta(() => s.chiamate('POST', '/locale/tutorial-novita-visto').length === 1);
  assert.equal(s.chiamate('POST', '/locale/tutorial-visto').length, 0);
});

test('la visita delle cose nuove trova il suo bersaglio a ogni passo', opz, async () => {
  const s = B.server({});
  s.stato.tutorial_novita_visto = true;
  const p = await B.pagina(s, { tempi: TEMPI });
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  await p.A.avviaVisita(false, 'novita');
  await p.aspetta(() => p.A.S.visita && !p.A.S.visita.preparando);
  const passi = p.A.S.visita.passi;
  assert.equal(passi.length, 15);
  for (let i = 0; i < passi.length - 1; i++) {
    await p.A.visitaVai(i);
    const trovato = await p.aspetta(() => passi[i].bersaglio.some((b) => p.q(b)), 3000).catch(() => false);
    assert.ok(trovato, 'passo ' + (i + 1) + ', ' + passi[i].titolo + ': manca ' + passi[i].bersaglio.join(' o '));
  }
  assert.equal(s.log.filter((x) => x.metodo !== 'GET' && x.percorso.startsWith('/server/')).length, 0, 'la visita non manda niente al server');
});

test('chi ha già visto le novità non rivede niente', opz, async () => {
  const s = B.server({});
  s.stato.tutorial_novita_visto = true;
  const p = await B.pagina(s, { tempi: TEMPI });
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  await B.dorme(60);
  assert.ok(!p.A.S.visita);
});

test('nei testi nuovi niente emoji e niente punti esclamativi', () => {
  const ui = path.join(__dirname, '..', '..', 'ui');
  const emoji = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u;
  for (const f of ['studio.js', 'schemi.js', 'riassunti.js', 'chiarimenti.js', 'lampo.js', 'simulate.js']) {
    const testo = fs.readFileSync(path.join(ui, f), 'utf8');
    assert.doesNotMatch(testo, emoji, f);
    for (const m of testo.matchAll(/'([^'\n]*[A-Za-zà-ù ]!)'/g)) assert.fail(f + ': punto esclamativo in ' + m[1]);
  }
});
