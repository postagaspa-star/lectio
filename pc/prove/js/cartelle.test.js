// I test delle cartelle della 1.3.0 (docs/aspetto.md §42, contratto §27) sulle pagine vere in jsdom, col server finto di
// esempio.js: righe nella materia, pagina della cartella, Nuova cartella e Rinomina, "Metti dei file qui" con i proposti,
// Elimina (solo la cartella o anche i file), la cartella scelta al caricamento e in "Un materiale", le pillole CARTELLE
// nella scelta del materiale, le novità senza avviso. Dati solo finti.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const B = require('./banco');

const opz = { skip: B.SENZA_JSDOM };
test.afterEach(() => B.chiudiTutte());

const premuto = (el) => !!el && el.getAttribute('aria-pressed') === 'true';
const spuntato = (el) => !!el && el.getAttribute('aria-checked') === 'true';
const query = (c) => new URLSearchParams(c.percorso.split('?')[1]);

async function apri(hash, prima) {
  const s = B.server();
  if (prima) prima(s);
  const p = await B.pagina(s, { hash });
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  return p;
}

/** Apre la pagina della materia e poi la cartella con un clic sulla sua riga (così Indietro torna alla materia). */
async function apriCartella(p, materia, cartella) {
  p.A.vai('#materia/' + materia);
  await p.aspetta(() => p.q('[data-cartella="' + cartella + '"]'));
  p.clic(p.q('[data-cartella="' + cartella + '"]'));
  // Pronta quando il server ha risposto: la riga sotto il nome dice quanti file ci sono.
  await p.aspetta(() => p.w.location.hash === '#materia/' + materia + '/cartella/' + cartella && p.q('[data-sotto="cartella"]') && / · \d+ file$/.test(p.q('[data-sotto="cartella"]').textContent));
}

// --- Pagina della materia (§42.1) --------------------------------------------------------------------

test('materia: prima le cartelle (con "messi ora da Lectio"), poi Nuova cartella, poi solo i file fuori dalle cartelle', opz, async () => {
  const p = await apri('#materia/1');
  await p.aspetta(() => p.q('[data-cartella="1"]') && p.q('[data-materiale="14"]'));
  const riga = p.q('[data-cartella="1"]');
  const righe = Array.from(riga.parentElement.children);
  assert.equal(righe[0], riga, 'le cartelle vengono per prime');
  assert.equal(righe[1].getAttribute('data-azione'), 'nuova-cartella');
  assert.match(righe[1].textContent, /Nuova cartella/);
  assert.ok(righe.slice(2).every((r) => r.classList.contains('riga-materiale')), 'poi il materiale');
  assert.match(riga.textContent, /La Repubblica/);
  assert.match(riga.textContent, /2 file · 1 messo ora da Lectio/);
  assert.match(riga.querySelector('.messi-lectio').textContent, /1 messo ora da Lectio/);
  // Il materiale dentro la cartella qui non compare.
  assert.equal(p.q('[data-materiale="10"]'), null);
  assert.equal(p.q('[data-materiale="7"]'), null);
  assert.ok(p.q('[data-materiale="11"]'));
  // Una materia senza materiale: la scheda c'è lo stesso, con Nuova cartella.
  p.A.vai('#materia/5');
  await p.aspetta(() => p.q('[data-azione="nuova-cartella"]') && /Ancora niente/.test(p.q('[data-azione="nuova-cartella"]').parentElement.textContent));
  assert.equal(p.q('[data-cartella]'), null);
});

// --- Pagina della cartella (§42.2) ---------------------------------------------------------------------

test('pagina della cartella: testata col nome, "Storia · 2 file", righe di sempre con "messo qui da Lectio", Indietro torna alla materia', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  assert.equal(p.q('[data-nome="cartella"]').textContent, 'La Repubblica');
  assert.equal(p.q('[data-sotto="cartella"]').textContent, 'Storia · 2 file');
  assert.ok(p.q('.testata-cartella').classList.contains('m-ambra'));
  assert.ok(p.q('.testata-cartella svg.icona-cartella'));
  assert.ok(p.q('.voce-materia.attiva[data-materia="1"]'), 'nel menu resta accesa la materia');
  await p.aspetta(() => p.q('.file-cartella [data-materiale="10"]'));
  assert.match(p.q('[data-materiale="10"]').textContent, /Appunti · 8 ottobre · messo qui da Lectio/);
  assert.doesNotMatch(p.q('[data-materiale="7"]').textContent, /messo qui da Lectio/);
  assert.equal(p.q('[data-azione="metti-qui"]').disabled, false);
  assert.equal(p.q('[data-azione="aggiungi-in-cartella"]').disabled, false);
  p.clic(p.q('[data-azione="indietro"]'));
  await p.aspetta(() => p.w.location.hash === '#materia/1' && p.q('[data-cartella="1"]'));
});

test('pagina della cartella: vuota dice "Ancora nessun file in questa cartella."; con un file in lettura si ricarica da sola', opz, async () => {
  const p = await apri('#oggi', (s) => {
    s.esempio.stato.cartelle[1].push({ id: 50, materia_id: 1, nome: 'Augusto', creata_il: '2026-10-27T10:00:00+01:00' });
  });
  await apriCartella(p, 1, 50);
  assert.equal(p.q('.vuota-cartella').textContent, 'Ancora nessun file in questa cartella.');
  assert.equal(p.q('[data-sotto="cartella"]').textContent, 'Storia · 0 file');
  // Un file in lettura dentro la cartella: si chiede di nuovo finché il server non l'ha letto.
  const x = p.s.esempio.stato.materiale[1].find((y) => y.id === 11);
  Object.assign(x, { cartella_id: 50, cartella_da: 'utente', stato: 'in_lettura' });
  p.A.S.aggiornaPagina();
  await p.aspetta(() => p.q('.file-cartella .riga-materiale.in-lettura'));
  const prima = p.s.chiamate('GET', '/server/api/materie/1/materiale').length;
  x.stato = 'pronto';
  await p.aspetta(() => p.q('.file-cartella [data-materiale="11"]') && !p.q('.riga-materiale.in-lettura'));
  assert.ok(p.s.chiamate('GET', '/server/api/materie/1/materiale').length > prima);
});

test('pagina della cartella senza rete: errore con Riprova; Aggiungi, Metti dei file qui, Rinomina ed Elimina spenti', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  p.s.risposta = (m, percorso) => (percorso.startsWith('/server/') ? 'rete' : undefined);
  p.A.S.aggiornaPagina();
  await p.aspetta(() => p.q('.file-cartella .messaggio.errore'));
  assert.ok(p.pulsante('Riprova'));
  assert.equal(p.q('[data-azione="aggiungi-in-cartella"]').disabled, true);
  assert.equal(p.q('[data-azione="metti-qui"]').disabled, true);
  p.clic(p.q('.testata-cartella [data-azione="puntini"]'));
  assert.equal(p.q('[data-azione="rinomina-cartella"]').disabled, true);
  assert.equal(p.q('[data-azione="elimina-cartella"]').disabled, true);
});

// --- Nuova cartella e Rinomina (§42.3) ---------------------------------------------------------------

test('Nuova cartella: spento col nome vuoto, l\'errore del server sotto il campo, Invio crea e apre la cartella nuova', opz, async () => {
  const p = await apri('#materia/1');
  await p.aspetta(() => p.q('[data-azione="nuova-cartella"]'));
  p.clic(p.q('[data-azione="nuova-cartella"]'));
  await p.aspetta(() => p.q('#cartella-nome'));
  const campo = p.q('#cartella-nome');
  const crea = p.q('[data-azione="salva-cartella"]');
  assert.match(p.q('.finestra-cartella').textContent, /Nuova cartella/);
  assert.equal(campo.getAttribute('placeholder'), 'Per esempio Arte greca');
  assert.equal(campo.getAttribute('maxlength'), '40');
  assert.equal(crea.textContent, 'Crea cartella');
  assert.equal(crea.disabled, true);
  p.scrivi(campo, '   ');
  assert.equal(crea.disabled, true, 'solo spazi: spento');
  p.scrivi(campo, 'la repubblica');
  assert.equal(crea.disabled, false);
  p.clic(crea);
  await p.aspetta(() => p.q('[data-errore="nome"]').textContent);
  assert.equal(p.q('[data-errore="nome"]').textContent, 'C\'è già una cartella con questo nome in questa materia.');
  p.scrivi(campo, '  Arte   greca ');
  assert.equal(p.q('[data-errore="nome"]').textContent, '', 'scrivendo l\'errore sparisce');
  p.tasto('Enter', null, campo);
  await p.aspetta(() => /^#materia\/1\/cartella\/\d+$/.test(p.w.location.hash) && p.q('.vuota-cartella'));
  const post = p.s.chiamate('POST', '/server/api/materie/1/cartelle');
  assert.deepEqual(post[post.length - 1].corpo, { nome: 'Arte greca' });
  assert.equal(p.q('[data-nome="cartella"]').textContent, 'Arte greca');
  assert.equal(p.q('.finestra-cartella'), null);
});

test('Rinomina: il nome di prima già scritto, Salva spento finché è uguale, poi PATCH e la testata cambia', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  p.clic(p.q('.testata-cartella [data-azione="puntini"]'));
  p.clic(p.q('[data-azione="rinomina-cartella"]'));
  await p.aspetta(() => p.q('#cartella-nome'));
  assert.match(p.q('.finestra-cartella').textContent, /Rinomina cartella/);
  assert.equal(p.q('#cartella-nome').value, 'La Repubblica');
  const salva = p.q('[data-azione="salva-cartella"]');
  assert.equal(salva.textContent, 'Salva');
  assert.equal(salva.disabled, true);
  p.scrivi(p.q('#cartella-nome'), 'La Repubblica romana');
  assert.equal(salva.disabled, false);
  p.clic(salva);
  await p.aspetta(() => p.q('[data-nome="cartella"]').textContent === 'La Repubblica romana');
  assert.deepEqual(p.s.chiamate('PATCH', '/server/api/cartelle/1')[0].corpo, { nome: 'La Repubblica romana' });
});

// --- Metti dei file qui (§42.4) ----------------------------------------------------------------------

test('Metti dei file qui: due gruppi, i proposti già spuntati, cerca anche nel nome della cartella, "Metti qui 1 file" e la conferma', opz, async () => {
  const p = await apri('#oggi', (s) => {
    s.esempio.stato.cartelle[1][0].nome = 'Anni difficili';
    s.esempio.stato.cartelle[1].push({ id: 50, materia_id: 1, nome: 'Augusto', creata_il: '2026-10-27T10:00:00+01:00' });
  });
  await apriCartella(p, 1, 50);
  p.clic(p.q('[data-azione="metti-qui"]'));
  await p.aspetta(() => p.q('.finestra-metti'));
  assert.match(p.q('.finestra-metti h2').textContent, /^Metti in Augusto$/);
  // Finché arrivano i proposti, le caselle sono spente.
  await p.aspetta(() => p.q('[data-metti="11"]') && !p.q('[data-metti="11"]').disabled);
  assert.equal(p.s.chiamate('GET', '/server/api/cartelle/50/proposti').length, 1);
  assert.ok(spuntato(p.q('[data-metti="11"]')), 'Augusto e il principato è proposto');
  assert.ok(spuntato(p.q('[data-metti="8"]')), 'Interrogazione su Augusto è proposta');
  assert.equal(spuntato(p.q('[data-metti="14"]')), false);
  assert.equal(p.q('[data-conta="scelti"]').textContent, '2 scelti');
  const metti = p.q('[data-azione="metti-qui-conferma"]');
  assert.equal(metti.textContent, 'Metti qui 2 file');
  // I gruppi: fuori dalle cartelle e in altre cartelle, con la loro riga sotto il titolo.
  assert.match(p.q('[data-gruppo="fuori"]').textContent, /Fuori dalle cartelle/i);
  assert.match(p.q('[data-gruppo="altre"]').textContent, /In altre cartelle/i);
  assert.ok(p.q('[data-gruppo="altre"] [data-metti="10"]'));
  assert.equal(p.q('[data-metti="10"] .s').textContent, 'In Anni difficili · Appunti');
  assert.equal(p.q('[data-metti="14"] .s').textContent, 'Appunti · 25 ottobre');
  // Più di 6: c'è Cerca, che trova anche col nome della cartella.
  const cerca = p.q('.cerca-metti');
  assert.ok(cerca);
  p.scrivi(cerca, 'difficili');
  assert.deepEqual(p.tutti('.finestra-metti [data-metti]').map((x) => x.dataset.metti), ['10', '7']);
  p.scrivi(cerca, '');
  // Andrea toglie quello che non vuole.
  p.clic(p.q('[data-metti="8"]'));
  assert.equal(p.q('[data-conta="scelti"]').textContent, '1 scelto');
  assert.equal(p.q('[data-azione="metti-qui-conferma"]').textContent, 'Metti qui 1 file');
  p.clic(p.q('[data-azione="metti-qui-conferma"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/materie/1/sposta').length === 1);
  assert.deepEqual(p.s.chiamate('POST', '/server/api/materie/1/sposta')[0].corpo, { materiali: [11], cartella_id: 50 });
  await p.aspetta(() => !p.q('.finestra-metti') && p.q('.file-cartella [data-materiale="11"]'));
  await p.aspetta(() => /Fatto: 1 file in Augusto/.test(p.q('#avvisi').textContent));
  assert.equal(p.q('[data-sotto="cartella"]').textContent, 'Storia · 1 file');
});

test('Metti dei file qui: niente spuntato = spento; se tutto è già qui il pulsante della pagina è spento e la finestra lo dice', opz, async () => {
  const p = await apri('#oggi', (s) => {
    s.esempio.stato.cartelle[2] = [{ id: 60, materia_id: 2, nome: 'Fonti', creata_il: '2026-10-27T10:00:00+01:00' }];
    Object.assign(s.esempio.stato.materiale[2][0], { cartella_id: 60, cartella_da: 'utente' });
  });
  await apriCartella(p, 2, 60);
  assert.equal(p.q('[data-azione="metti-qui"]').disabled, true);
  p.A.apriMettiQui(p.A.materiaDi(2), { id: 60, nome: 'Fonti', file: 1 }, p.s.esempio.stato.materiale[2], []);
  await p.aspetta(() => p.q('.finestra-metti'));
  assert.match(p.q('.finestra-metti').textContent, /Tutto il materiale della materia è già qui\./);
  assert.equal(p.q('[data-azione="metti-qui-conferma"]'), null);
  p.A.chiudiTuttiIVeli();
  // Nella cartella 1 di Storia, togliendo tutte le spunte il pulsante si spegne.
  await apriCartella(p, 1, 1);
  p.clic(p.q('[data-azione="metti-qui"]'));
  await p.aspetta(() => p.q('[data-metti="11"]') && !p.q('[data-metti="11"]').disabled);
  for (const x of p.tutti('.finestra-metti [data-metti][aria-checked="true"]')) p.clic(x);
  assert.equal(p.q('[data-azione="metti-qui-conferma"]').textContent, 'Metti qui 0 file');
  assert.equal(p.q('[data-azione="metti-qui-conferma"]').disabled, true);
});

// --- Eliminare una cartella (§42.5) -------------------------------------------------------------------

test('Elimina una cartella vuota: conferma semplice "È vuota.", poi si torna alla materia con "Cartella eliminata"', opz, async () => {
  const p = await apri('#oggi', (s) => {
    s.esempio.stato.cartelle[1].push({ id: 50, materia_id: 1, nome: 'Augusto', creata_il: '2026-10-27T10:00:00+01:00' });
  });
  await apriCartella(p, 1, 50);
  p.clic(p.q('.testata-cartella [data-azione="puntini"]'));
  p.clic(p.q('[data-azione="elimina-cartella"]'));
  await p.aspetta(() => p.q('[data-azione="conferma"]'));
  assert.match(p.testo(), /Eliminare la cartella «Augusto»\?/);
  assert.match(p.testo(), /È vuota\./);
  assert.equal(p.q('[data-azione="conferma"]').textContent, 'Elimina');
  p.clic(p.q('[data-azione="conferma"]'));
  await p.aspetta(() => p.s.chiamate('DELETE', '/server/api/cartelle/50').length === 1);
  assert.deepEqual(p.s.chiamate('DELETE', '/server/api/cartelle/50')[0].corpo, { conferma: true, con_i_file: false });
  await p.aspetta(() => p.w.location.hash === '#materia/1' && p.q('[data-cartella="1"]') && !p.q('[data-cartella="50"]'));
  await p.aspetta(() => /Cartella eliminata/.test(p.q('#avvisi').textContent));
});

test('Elimina una cartella con file: tre scelte, solo "Elimina anche i 2 file" è rossa; "Elimina solo la cartella" lascia i file nella materia', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  p.clic(p.q('.testata-cartella [data-azione="puntini"]'));
  p.clic(p.q('[data-azione="elimina-cartella"]'));
  await p.aspetta(() => p.q('[data-azione="elimina-solo-cartella"]'));
  const f = p.q('.finestra-elimina-cartella');
  assert.match(f.textContent, /Eliminare la cartella «La Repubblica»\?/);
  assert.match(f.textContent, /Dentro ci sono 2 file\./);
  assert.match(f.textContent, /I file restano nella materia/);
  const conFile = p.q('[data-azione="elimina-con-file"]');
  assert.equal(conFile.textContent, 'Elimina anche i 2 file');
  assert.ok(conFile.classList.contains('rosso-tenue'));
  assert.deepEqual(p.tutti('.rosso-tenue', f).length, 1, 'un solo pulsante rosso');
  assert.equal(p.q('[data-azione="elimina-solo-cartella"]').classList.contains('rosso-tenue'), false);
  assert.ok(p.q('[data-azione="annulla"]', f));
  p.clic(p.q('[data-azione="elimina-solo-cartella"]'));
  await p.aspetta(() => p.s.chiamate('DELETE', '/server/api/cartelle/1').length === 1);
  assert.deepEqual(p.s.chiamate('DELETE', '/server/api/cartelle/1')[0].corpo, { conferma: true, con_i_file: false });
  await p.aspetta(() => p.w.location.hash === '#materia/1' && p.q('[data-materiale="10"]') && p.q('[data-materiale="7"]'));
  assert.equal(p.q('[data-cartella="1"]'), null);
  await p.aspetta(() => /Cartella eliminata/.test(p.q('#avvisi').textContent));
});

test('Elimina una cartella con file: "Elimina anche i file" li cancella e dice quanti; Annulla non manda niente', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  p.clic(p.q('.testata-cartella [data-azione="puntini"]'));
  p.clic(p.q('[data-azione="elimina-cartella"]'));
  await p.aspetta(() => p.q('[data-azione="annulla"]'));
  p.clic(p.q('.finestra-elimina-cartella [data-azione="annulla"]'));
  await p.aspetta(() => !p.q('.finestra-elimina-cartella'));
  assert.equal(p.s.chiamate('DELETE', '/server/api/cartelle/1').length, 0);
  p.clic(p.q('.testata-cartella [data-azione="puntini"]'));
  p.clic(p.q('[data-azione="elimina-cartella"]'));
  await p.aspetta(() => p.q('[data-azione="elimina-con-file"]'));
  p.clic(p.q('[data-azione="elimina-con-file"]'));
  await p.aspetta(() => p.s.chiamate('DELETE', '/server/api/cartelle/1').length === 1);
  assert.deepEqual(p.s.chiamate('DELETE', '/server/api/cartelle/1')[0].corpo, { conferma: true, con_i_file: true });
  await p.aspetta(() => p.w.location.hash === '#materia/1' && p.q('[data-materiale="11"]') && !p.q('[data-cartella="1"]'));
  assert.equal(p.q('[data-materiale="10"]'), null);
  assert.equal(p.q('[data-materiale="7"]'), null);
  await p.aspetta(() => /Cartella e 2 file eliminati/.test(p.q('#avvisi').textContent));
});

// --- La cartella di un file (§42.6) --------------------------------------------------------------------

test('Aggiungi dalla cartella: la cartella è già scelta e il caricamento manda cartella_id; il file compare nella cartella', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  p.clic(p.q('[data-azione="aggiungi-in-cartella"]'));
  await p.aspetta(() => p.q('[data-riga="file"]'));
  // Un file trascinato sul foglio Aggiungi (come scelto dal computer).
  p.A.suFileTrascinati([new p.w.File(['Il primo triumvirato'], 'triumvirato.txt', { type: 'text/plain' })]);
  await p.aspetta(() => p.q('[data-azione="aggiungi-file"]') && p.q('[data-cartella-scelta="1"]'));
  assert.match(p.q('[data-campo="cartella"]').textContent, /Cartella/i);
  assert.ok(premuto(p.q('[data-cartella-scelta="1"]')));
  assert.equal(premuto(p.q('[data-cartella-scelta="lectio"]')), false);
  assert.equal(p.q('[data-cartella-scelta="lectio"]').textContent, 'Decide Lectio');
  p.clic(p.q('[data-azione="aggiungi-file"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/carica').length === 1);
  assert.equal(query(p.s.chiamate('POST', '/locale/carica')[0]).get('cartella_id'), '1');
  await p.aspetta(() => p.q('.file-cartella .riga-materiale.in-lettura'));
});

test('Aggiungi dalla materia: parte da "Decide Lectio" (niente cartella_id); scegliendo una cartella si manda; senza cartelle niente CARTELLA', opz, async () => {
  const p = await apri('#materia/1');
  await p.aspetta(() => p.q('[data-cartella="1"]'));
  p.A.suFileTrascinati([new p.w.File(['testo'], 'gracchi.txt', { type: 'text/plain' })]);
  await p.aspetta(() => p.q('[data-azione="aggiungi-file"]') && p.q('[data-cartella-scelta="lectio"]'));
  assert.ok(premuto(p.q('[data-cartella-scelta="lectio"]')));
  p.clic(p.q('[data-azione="aggiungi-file"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/carica').length === 1);
  assert.equal(query(p.s.chiamate('POST', '/locale/carica')[0]).get('cartella_id'), null, 'Decide Lectio: la cartella la sceglie il server');
  await p.aspetta(() => !p.q('.foglio'));
  // Due foto insieme in una cartella scelta: vale per tutte.
  p.A.suFileTrascinati([new p.w.File(['a'], 'IMG_1.jpg', { type: 'image/jpeg' }), new p.w.File(['b'], 'IMG_2.jpg', { type: 'image/jpeg' })]);
  await p.aspetta(() => p.q('[data-azione="aggiungi-file"]') && p.q('[data-cartella-scelta="1"]'));
  p.clic(p.q('[data-cartella-scelta="1"]'));
  assert.ok(premuto(p.q('[data-cartella-scelta="1"]')));
  assert.equal(premuto(p.q('[data-cartella-scelta="lectio"]')), false);
  p.clic(p.q('[data-azione="aggiungi-file"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/carica').length === 3);
  assert.deepEqual(p.s.chiamate('POST', '/locale/carica').slice(1).map((c) => query(c).get('cartella_id')), ['1', '1']);
  await p.aspetta(() => !p.q('.foglio'));
  // Diritto non ha cartelle: niente CARTELLA.
  p.A.vai('#materia/2');
  await p.aspetta(() => p.A.S.rotta.id === 2 && p.q('[data-materiale="20"]'));
  p.A.suFileTrascinati([new p.w.File(['x'], 'fonti.txt', { type: 'text/plain' })]);
  await p.aspetta(() => p.q('[data-azione="aggiungi-file"]'));
  await B.dorme(60);
  assert.equal(p.q('[data-cartella-scelta]'), null);
  assert.equal(p.q('[data-campo="cartella"]').style.display, 'none');
});

test('Appunti scritti dentro una cartella: CARTELLA già scelta e cartella_id come numero nel JSON', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  p.clic(p.q('[data-azione="aggiungi-in-cartella"]'));
  await p.aspetta(() => p.q('[data-riga="appunti"]'));
  p.clic(p.q('[data-riga="appunti"]'));
  await p.aspetta(() => p.q('#testo-appunti') && p.q('[data-cartella-scelta="1"]'));
  assert.ok(premuto(p.q('[data-cartella-scelta="1"]')));
  p.scrivi(p.q('#testo-appunti'), 'Silla marcia su Roma nell\'88 avanti Cristo.');
  p.clic(p.q('[data-azione="aggiungi-scritto"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/materie/1/materiale').length === 1);
  const corpo = p.s.chiamate('POST', '/server/api/materie/1/materiale')[0].corpo;
  assert.equal(corpo.cartella_id, 1);
  assert.equal(corpo.tipo, 'appunti');
  await p.aspetta(() => !p.q('.foglio'));
  // Con Decide Lectio il campo non si manda.
  p.clic(p.q('[data-azione="aggiungi-in-cartella"]'));
  await p.aspetta(() => p.q('[data-riga="orale"]'));
  p.clic(p.q('[data-riga="orale"]'));
  await p.aspetta(() => p.q('#orale-chiesto') && p.q('[data-cartella-scelta="lectio"]'));
  p.clic(p.q('[data-cartella-scelta="lectio"]'));
  p.scrivi(p.q('#orale-chiesto'), 'Chi erano i Gracchi?');
  p.clic(p.q('[data-azione="aggiungi-scritto"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/materie/1/materiale').length === 2);
  assert.equal('cartella_id' in p.s.chiamate('POST', '/server/api/materie/1/materiale')[1].corpo, false);
});

test('Un materiale: CARTELLA con Fuori e la cartella attuale scelta, "Messo qui da Lectio"; Salva manda cartella_id null', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  await p.aspetta(() => p.q('.file-cartella [data-materiale="10"]'));
  p.clic(p.q('[data-materiale="10"]'));
  await p.aspetta(() => p.q('.finestra-materiale [data-cartella-scelta="1"]'));
  const box = p.q('[data-campo="cartella"]');
  // Tra CHE COS'È e COSA HO LETTO.
  assert.equal(box.previousElementSibling.querySelector('.famiglie') !== null, true);
  assert.ok(box.nextElementSibling.hasAttribute('data-letto'));
  assert.ok(premuto(p.q('[data-cartella-scelta="1"]')));
  assert.equal(p.q('[data-cartella-scelta="fuori"]').textContent, 'Fuori');
  assert.equal(p.q('[data-nota="cartella"]').textContent, 'Messo qui da Lectio');
  p.clic(p.q('[data-cartella-scelta="fuori"]'));
  assert.ok(premuto(p.q('[data-cartella-scelta="fuori"]')));
  assert.equal(p.q('[data-nota="cartella"]'), null);
  p.clic(p.q('[data-azione="salva-materiale"]'));
  await p.aspetta(() => p.s.chiamate('PATCH', '/server/api/materiale/10').length === 1);
  assert.deepEqual(p.s.chiamate('PATCH', '/server/api/materiale/10')[0].corpo, { cartella_id: null });
  // La pagina sotto si aggiorna: il file non è più nella cartella.
  await p.aspetta(() => !p.q('.file-cartella [data-materiale="10"]'));
  // Un materiale di una materia senza cartelle: niente CARTELLA.
  p.A.chiudiTuttiIVeli();
  p.A.vai('#materia/2');
  await p.aspetta(() => p.q('[data-materiale="20"]'));
  p.clic(p.q('[data-materiale="20"]'));
  await p.aspetta(() => p.q('[data-testo="letto"]'));
  await B.dorme(40);
  assert.equal(p.q('[data-cartella-scelta]'), null);
});

// --- Scelta del materiale (§42.7) ----------------------------------------------------------------------

test('Nuova lezione: la pillola cartella spunta e toglie tutto il suo materiale, conta come un tocco; nome nelle righe e nella ricerca', opz, async () => {
  const p = await apri('#oggi', (s) => { s.esempio.stato.cartelle[1][0].nome = 'Anni difficili'; });
  p.A.nuovaLezione(1);
  await p.aspetta(() => p.q('[data-cartella-pillola="1"]') && p.q('[data-scelta="10"]'));
  const pillola = () => p.q('[data-cartella-pillola="1"]');
  assert.equal(pillola().textContent, 'Anni difficili · 2');
  assert.match(p.q('[data-cartelle="scelta"]').textContent, /Cartelle/i);
  assert.equal(p.q('[data-scelta="10"] .s').textContent, 'Appunti · Anni difficili · foto · 8 ottobre');
  assert.equal(p.q('[data-scelta="11"] .s').textContent, 'Appunti · file · 22 ottobre', 'fuori dalle cartelle: come prima');
  await p.aspetta(() => premuto(p.q('[data-modo="argomento"]')));
  const prima = p.tutti('.nl-mat[aria-checked="true"]').map((x) => x.dataset.scelta);
  assert.equal(premuto(pillola()), false);
  p.clic(pillola());
  assert.ok(premuto(pillola()));
  assert.ok(spuntato(p.q('[data-scelta="10"]')));
  assert.ok(spuntato(p.q('[data-scelta="7"]')));
  assert.equal(premuto(p.q('[data-modo="argomento"]')), false, 'le caselle smettono di seguire l\'argomento');
  for (const id of prima) assert.ok(spuntato(p.q('[data-scelta="' + id + '"]')), 'quello che c\'era resta');
  p.clic(pillola());
  assert.equal(premuto(pillola()), false);
  assert.equal(spuntato(p.q('[data-scelta="10"]')), false);
  assert.equal(spuntato(p.q('[data-scelta="7"]')), false);
  // Toccandone una sola la pillola non è più scelta, e un tocco la rispunta tutta.
  p.clic(p.q('[data-scelta="10"]'));
  assert.equal(premuto(pillola()), false);
  p.clic(pillola());
  assert.ok(spuntato(p.q('[data-scelta="7"]')));
  // Cerca nel materiale trova anche il nome della cartella.
  p.scrivi(p.q('#nl-cerca'), 'difficili');
  assert.deepEqual(p.tutti('.nl-mat').map((x) => x.dataset.scelta).sort(), ['10', '7']);
});

test('Nuova lezione: senza cartelle con materiale pronto niente CARTELLE', opz, async () => {
  const p = await apri('#oggi');
  p.A.nuovaLezione(2);
  await p.aspetta(() => p.q('[data-scelta="20"]'));
  assert.equal(p.q('[data-cartelle="scelta"]'), null);
});

// --- Novità (§42.8) --------------------------------------------------------------------------------------

test('novità "materiale_in_cartella": nessun avviso, la pagina aperta si aggiorna', opz, async () => {
  const p = await apri('#oggi');
  await apriCartella(p, 1, 1);
  Object.assign(p.s.esempio.stato.materiale[1].find((x) => x.id === 13), { cartella_id: 1, cartella_da: 'lectio' });
  p.w.lectioNovita([{ id: 61, tipo: 'materiale_in_cartella', testo: 'Ho messo «Tiberio, Seiano e i processi» nella cartella La Repubblica', quando: '2026-10-27T10:00:00+01:00' }]);
  await p.aspetta(() => p.q('.file-cartella [data-materiale="13"]'));
  assert.match(p.q('[data-materiale="13"]').textContent, /messo qui da Lectio/);
  assert.doesNotMatch(p.q('#avvisi').textContent, /Ho messo/);
});
