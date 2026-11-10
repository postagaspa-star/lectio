// I test del pulsante Nuovo e dell'ordine della pagina della materia (1.3.0, docs/aspetto.md §43) sulle pagine vere in
// jsdom, col server finto di esempio.js: l'ordine delle sezioni, "Cosa vuoi fare?" con le sue cinque righe, le righe
// spente, il passo 2 di Riassunto (e il salto con un solo schema), il Ripasso lampo, la finestra che si chiude quando
// si apre la cosa scelta, la rete che cade. Dati solo finti.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };
test.afterEach(() => B.chiudiTutte());

async function apri(hash, prima) {
  const s = B.server();
  if (prima) prima(s);
  const p = await B.pagina(s, { hash });
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  return p;
}

/** La materia con tutto caricato (anche gli schemi e il Ripasso lampo). */
async function apriMateria(id, prima) {
  const p = await apri('#materia/' + id, prima);
  await p.aspetta(() => p.q('[data-visita="nuovo"]') && p.q('[data-lampo="scheda"], [data-lampo="vuoto"]') && p.q('[data-elenco="schemi"] .riga') && !p.q('[data-elenco="schemi"] .segnaposto'));
  return p;
}

async function apriScelta(p) {
  p.clic(p.q('[data-visita="nuovo"]'));
  return p.aspetta(() => p.q('.finestra-nuovo [data-passo="scelta"]') && !p.q('.finestra-nuovo [data-scelta="riassunto"]').disabled && p.q('.finestra-nuovo'));
}

const riga = (p, scelta) => p.q('.finestra-nuovo [data-scelta="' + scelta + '"]');
const frase = (p, scelta) => riga(p, scelta).querySelector('.f').textContent;

// --- Ordine delle sezioni (§43.1) ----------------------------------------------------------------------

test('materia: prima Cosa chiede il prof | Prossime e Da ripassare, poi il binario, poi Materiale | Trascina qui', opz, async () => {
  const p = await apriMateria(1);
  const pag = p.q('.pagina-materia');
  const figli = Array.from(pag.children);
  assert.deepEqual(figli.map((x) => x.getAttribute('data-fila') || x.getAttribute('data-sezione') || x.className), ['prof', 'binario', 'materiale', 'fondo-fisso']);
  // Prima fila: a sinistra la scheda del prof, a destra Prossime e sotto Da ripassare.
  const prof = figli[0];
  assert.ok(prof.classList.contains('griglia-materia'));
  assert.ok(prof.children[0].matches('[data-visita="scheda-prof"]'));
  assert.deepEqual(Array.from(prof.children[1].children).map((x) => x.getAttribute('data-sezione')), ['prossime', 'ripasso']);
  assert.match(prof.children[1].textContent, /^Prossime[\s\S]*Da ripassare/);
  // Seconda: il binario a tutta larghezza, con il suo elenco.
  assert.ok(figli[1].querySelector('.binario [data-segmento="lezioni"]'));
  assert.ok(figli[1].querySelector('[data-elenco="lezioni"]'));
  // Terza: Materiale (titolo, Aggiungi, la scheda con cartelle e file) e la zona per trascinare.
  const mat = figli[2];
  assert.ok(mat.classList.contains('griglia-materia'));
  assert.equal(mat.children[0].getAttribute('data-sezione'), 'materiale');
  assert.equal(mat.children[0].querySelector('.titolo-sezione').textContent, 'Materiale');
  assert.ok(mat.children[0].querySelector('[data-azione="aggiungi-materiale"]'));
  assert.ok(mat.children[0].querySelector('[data-cartella="1"]'));
  assert.ok(mat.children[1].querySelector('.zona-trascina[data-zona="materia"]'));
  assert.match(mat.children[1].textContent, /Trascina qui i file/);
  // Sotto i 900 px una colonna sola, e la zona non ha più lo spazio del titolo sopra.
  const css = fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'stile.css'), 'utf8');
  const stretta = /@media \(max-width: 899px\) \{([^}]*\}[^}]*\})/.exec(css);
  assert.ok(stretta, 'manca la regola sotto i 900 px');
  assert.match(stretta[1], /\.griglia-materia \{ grid-template-columns: minmax\(0, 1fr\); \}/);
  assert.match(stretta[1], /\.colonna-zona \{ padding-top: 0; \}/);
});

// --- Il pulsante e la finestra (§43.2, §43.3) -----------------------------------------------------------

test('Nuovo apre "Cosa vuoi fare?": titolo, pillola della materia, X, cinque righe con piastrella e freccia', opz, async () => {
  const p = await apriMateria(1);
  const f = await apriScelta(p);
  assert.equal(f.querySelector('h2').textContent, 'Cosa vuoi fare?');
  assert.equal(f.querySelector('.pillola-materia').textContent, 'Storia');
  assert.ok(f.querySelector('[data-azione="chiudi-nuovo"]'));
  assert.ok(f.classList.contains('stretta'), 'finestra larga 560');
  assert.deepEqual(p.tutti('.finestra-nuovo [data-scelta]').map((b) => b.getAttribute('data-scelta')), ['lezione', 'schema', 'riassunto', 'simulata', 'lampo']);
  assert.deepEqual(p.tutti('.finestra-nuovo [data-scelta] .n').map((x) => x.textContent), ['Lezione da ascoltare', 'Schema', 'Riassunto', 'Verifica simulata', 'Ripasso lampo']);
  assert.equal(frase(p, 'lezione'), 'Te la spiego a voce, con le domande lungo la strada');
  assert.equal(frase(p, 'riassunto'), 'Da uno schema già pronto: per punti o discorsivo');
  assert.equal(frase(p, 'lampo'), '5 schede · circa 2 min');
  for (const b of p.tutti('.finestra-nuovo [data-scelta]')) {
    assert.equal(b.disabled, false, b.getAttribute('data-scelta'));
    assert.ok(b.querySelector('.nuovo-piastrella svg.icona'), 'piastrella con icona');
    assert.ok(b.querySelector(':scope > svg.icona'), 'freccia a destra');
  }
  assert.doesNotMatch(f.textContent, /!/);
  // Esc chiude.
  p.tasto('Escape', {}, p.d.body);
  await p.aspetta(() => !p.q('.finestra-nuovo'));
  // Anche la X.
  await apriScelta(p);
  p.clic(p.q('[data-azione="chiudi-nuovo"]'));
  await p.aspetta(() => !p.q('.finestra-nuovo'));
  assert.equal(p.s.log.filter((x) => x.metodo !== 'GET' && x.percorso.startsWith('/server/')).length, 0, 'aprire la scelta non crea niente');
});

test('righe spente: senza schede "Niente per oggi", senza schemi pronti "Prima serve uno schema pronto"', opz, async () => {
  // Scienze umane: nessuna scheda di Ripasso lampo e nessuno schema.
  const p = await apriMateria(3);
  p.clic(p.q('[data-visita="nuovo"]'));
  await p.aspetta(() => riga(p, 'riassunto') && riga(p, 'riassunto').disabled && frase(p, 'riassunto') === 'Prima serve uno schema pronto');
  assert.equal(riga(p, 'lampo').disabled, true);
  assert.equal(frase(p, 'lampo'), 'Niente per oggi');
  for (const k of ['lezione', 'schema', 'simulata']) assert.equal(riga(p, k).disabled, false, k);
  // Una riga spenta non fa niente.
  p.clic(riga(p, 'riassunto'));
  p.clic(riga(p, 'lampo'));
  await B.dorme(80);
  assert.ok(p.q('.finestra-nuovo [data-passo="scelta"]'));
  assert.equal(p.q('.finestra-lampo'), null);
});

test('Lezione, Schema e Verifica simulata: la scelta si chiude e si apre la finestra giusta con la prossima data', opz, async () => {
  const p = await apriMateria(1);
  for (const [scelta, tipo, titolo] of [['lezione', 'nuova-lezione', 'Nuova lezione'], ['schema', 'nuovo-schema', 'Nuovo schema'], ['simulata', 'nuovo-simulata', 'Nuova verifica simulata']]) {
    p.A.chiudiTuttiIVeli();
    await apriScelta(p);
    p.clic(riga(p, scelta));
    assert.equal(p.q('.finestra-nuovo'), null, 'la scelta si chiude subito');
    await p.aspetta(() => p.A.S.velo.some((v) => v.tipo === tipo) && p.q('#nl-argomento'));
    assert.equal(p.q('.finestra-lezione h1').textContent, titolo);
    assert.equal(p.q('#nl-argomento').value, 'Impero romano da Augusto a Nerone', scelta + ': la prossima verifica è già scelta');
  }
});

test('Riassunto: passo 2 "Da quale schema?", i più recenti prima; indietro torna alla scelta; un tocco apre la lettura col foglio dei riassunti', opz, async () => {
  const p = await apriMateria(1);
  await apriScelta(p);
  p.clic(riga(p, 'riassunto'));
  await p.aspetta(() => p.q('.finestra-nuovo [data-passo="schema"]'));
  assert.equal(p.q('.finestra-nuovo h2').textContent, 'Da quale schema?');
  assert.ok(p.q('.finestra-nuovo [data-azione="indietro"]'), 'freccia indietro in alto a sinistra');
  assert.ok(p.q('.finestra-nuovo [data-azione="chiudi-nuovo"]'), 'la X resta');
  // Solo gli schemi pronti (non quello in preparazione né quello non riuscito).
  assert.deepEqual(p.tutti('[data-schema-scelto]').map((b) => b.getAttribute('data-schema-scelto')), ['4', '3']);
  assert.equal(p.q('[data-schema-scelto="3"] .n').textContent, 'L\'Impero romano da Augusto a Nerone');
  assert.equal(p.q('[data-schema-scelto="3"] .f').textContent, 'A capitoli · Completo');
  assert.ok(p.q('[data-schema-scelto="3"] .nuovo-piastrella svg.icona'));
  p.clic(p.q('.finestra-nuovo [data-azione="indietro"]'));
  await p.aspetta(() => p.q('.finestra-nuovo [data-passo="scelta"]'));
  p.clic(riga(p, 'riassunto'));
  p.clic(await p.aspetta(() => p.q('[data-schema-scelto="3"]')));
  await p.aspetta(() => p.w.location.hash === '#schema/3' && p.q('[data-forma-riassunto]'));
  assert.equal(p.q('.finestra-nuovo'), null, 'la scelta si è chiusa');
  assert.equal(p.q('.foglio h2').textContent, 'Che riassunto vuoi?');
  assert.ok(p.q('.sch-parte'), 'sotto c\'è la lettura dello schema');
  assert.equal(p.s.chiamate('POST', '/server/api/schemi/3/riassunti').length, 0, 'il riassunto non parte da solo');
  // Il foglio si apre una volta sola: tornando sulla stessa lettura non si riapre.
  p.A.chiudiTuttiIVeli();
  p.A.vai('#oggi');
  await p.aspetta(() => p.q('[data-visita="prossima"]'));
  p.A.vai('#schema/3');
  await p.aspetta(() => p.q('.sch-parte'));
  await B.dorme(60);
  assert.equal(p.q('[data-forma-riassunto]'), null);
});

test('Riassunto con un solo schema pronto: si salta il passo 2', opz, async () => {
  const p = await apriMateria(1, (s) => {
    const uno = s.esempio.richiesta('GET', '/server/api/schemi?materia_id=1').dati.filter((x) => x.id !== 4);
    s.risposta = (m, q) => (m === 'GET' && q === '/server/api/schemi?materia_id=1' ? [200, uno] : undefined);
  });
  await apriScelta(p);
  p.clic(riga(p, 'riassunto'));
  await p.aspetta(() => p.w.location.hash === '#schema/3' && p.q('[data-forma-riassunto]'));
  assert.equal(p.q('[data-passo="schema"]'), null);
  assert.equal(p.q('.finestra-nuovo'), null);
});

test('la scelta chiede gli schemi da sé se la pagina non li ha ancora', opz, async () => {
  // Da Oggi (nessuna pagina della materia che chiede gli schemi per conto suo).
  const p = await apri('#oggi');
  await p.aspetta(() => p.q('[data-visita="prossima"]') && p.A.materiaDi(1));
  const prima = p.s.chiamate('GET', '/server/api/schemi?materia_id=1').length;
  p.A.apriNuovo(p.A.materiaDi(1));
  assert.equal(riga(p, 'riassunto').disabled, true, 'finché non si sa, Riassunto aspetta');
  await p.aspetta(() => !riga(p, 'riassunto').disabled);
  assert.equal(p.s.chiamate('GET', '/server/api/schemi?materia_id=1').length, prima + 1);
});

test('Ripasso lampo: apre il ripasso della materia e la scelta si chiude', opz, async () => {
  const p = await apriMateria(1);
  await apriScelta(p);
  p.clic(riga(p, 'lampo'));
  assert.equal(p.q('.finestra-nuovo'), null);
  await p.aspetta(() => p.q('.finestra-lampo') && p.q('[data-azione="mostra-risposta"]'));
  assert.ok(p.s.chiamate('GET', '/server/api/lampo?limite=12&materia_id=1').length >= 1);
});

test('senza rete: Nuovo spento; con la scelta aperta tutte le righe dicono "Serve la rete"; torna la rete e Nuovo si riaccende', opz, async () => {
  const p = await apriMateria(1);
  assert.equal(p.q('[data-visita="nuovo"]').disabled, false);
  p.s.risposta = (m, percorso) => (percorso.startsWith('/server/') ? 'rete' : undefined);
  p.A.S.aggiornaPagina();
  await p.aspetta(() => p.A.S.senzaRete && p.q('[data-visita="nuovo"]').disabled);
  // Sul computer non ci sono schede salvate (§35.5): anche il Ripasso lampo è spento.
  p.A.apriNuovo(p.A.materiaDi(1), { schemi: [] });
  await p.aspetta(() => p.q('.finestra-nuovo'));
  for (const b of p.tutti('.finestra-nuovo [data-scelta]')) {
    assert.equal(b.disabled, true, b.getAttribute('data-scelta'));
    assert.equal(b.querySelector('.f').textContent, 'Serve la rete');
  }
  p.A.chiudiTuttiIVeli();
  p.s.risposta = null;
  await p.A.api.get('/server/api/salute');
  await p.aspetta(() => !p.A.S.senzaRete && !p.q('[data-visita="nuovo"]').disabled);
});

test('nei testi del pulsante Nuovo niente emoji e niente punti esclamativi', () => {
  const testo = fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'logica.js'), 'utf8');
  const blocco = testo.slice(testo.indexOf('const SCELTE_NUOVO'), testo.indexOf('function nuovoSpento'));
  assert.ok(blocco.length > 100);
  assert.doesNotMatch(blocco, /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u);
  for (const m of blocco.matchAll(/'([^'\n]*)'/g)) assert.doesNotMatch(m[1], /!/);
});
