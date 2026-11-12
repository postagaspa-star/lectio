// I test di "Modifica, Correggi a mano, Torna a prima" (docs/aspetto.md §44, contratto §28): la logica pura di
// logica.js e le pagine vere in jsdom col server finto di esempio.js (schema, riassunto, verifica simulata, lettore).
// Dati solo finti.
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const B = require('./banco');

const L = B.L;
const opz = { skip: B.SENZA_JSDOM };
const TEMPI = { stato: 60, modifica: 40, stimaModifica: 20, avviso: 300 };
test.afterEach(() => B.chiudiTutte());

async function apri(hash, opzioniServer, prima) {
  const s = B.server(Object.assign({ tempoModifica: 80 }, opzioniServer || {}));
  if (prima) prima(s);
  const p = await B.pagina(s, { tempi: TEMPI, hash });
  await p.aspetta(() => p.q('.menu .voce-menu') && p.A.S.giro >= 1);
  return p;
}

/** Apre il menu con i puntini della testata e ne ritorna la voce. */
async function voceMenu(p, azione, dentro) {
  if (!p.q('.tendina')) p.clic(p.q((dentro || '.sch-testa') + ' [data-azione="puntini"]'));
  return p.aspetta(() => p.q('.tendina [data-azione="' + azione + '"]'));
}

/** Scrive la richiesta nella finestra "Cosa vuoi cambiare?" e aspetta che Modifica si accenda. */
async function chiedi(p, richiesta, parti) {
  await p.aspetta(() => p.q('[data-campo="richiesta"]'));
  p.scrivi(p.q('[data-campo="richiesta"]'), richiesta);
  if (parti) {
    p.clic(p.q('[data-dove="io"]'));
    for (const n of parti) p.clic(p.q('.mod-parte[data-parte="' + n + '"]'));
  }
  await p.aspetta(() => { const b = p.q('[data-azione="manda-modifica"]'); return b && !b.disabled; });
}

// --- Logica ----------------------------------------------------------------------------------------

test('correzione a mano dello schema: si cambiano solo i testi, tipi, ordine, foto e fonti restano', () => {
  const originale = {
    forma: 'capitoli',
    sezioni: [{
      n: 1, titolo: 'Augusto', campi: [{ nome: 'Autore', valore: 'Non si conosce' }],
      blocchi: [
        { tipo: 'paragrafo', titolo: null, testo: 'Nel 27 **Augusto**.', fonti: ['B1'] },
        { tipo: 'immagine', n: 1, didascalia: 'Moneta', credito: 'Disegno' },
        { tipo: 'punti', titolo: 'Il governo', voci: ['Uno', 'Due'], fonti: ['A2'] },
        { tipo: 'tabella', titolo: null, intestazione: ['Chi', 'Anni'], righe: [['Caligola', '37-41']], fonti: [] },
        { tipo: 'grafico', titolo: 'Sconosciuto' },
      ],
    }],
    fonti: { B1: { materiale_id: 12 } },
  };
  const corretto = JSON.parse(JSON.stringify(originale));
  const b = corretto.sezioni[0].blocchi;
  corretto.sezioni[0].titolo = '  Augusto e il principato ';
  corretto.sezioni[0].campi[0].valore = 'Sconosciuto';
  b[0].testo = 'Nel 27 avanti Cristo **Augusto**.';
  b[0].titolo = 'Un titolo che prima non c\'era';
  b[0].tipo = 'paragrafo';
  b[0].fonti = ['X9'];
  b[1].didascalia = 'Moneta d\'oro';
  b[1].n = 99;
  b[2].voci = 'Uno\n\n  Due bis  \nTre\n';
  b[3].intestazione = ['Imperatore', 'Anni'];
  b[3].righe = [['Caligola', '37-41 dopo Cristo']];
  b[4].titolo = 'Cambiato';
  b[4].tipo = 'altro';
  const fuori = L.contenutoSchemaCorretto(originale, corretto);
  const f = fuori.sezioni[0].blocchi;
  assert.equal(fuori.sezioni[0].titolo, 'Augusto e il principato');
  assert.equal(fuori.sezioni[0].campi[0].valore, 'Sconosciuto');
  assert.equal(f[0].testo, 'Nel 27 avanti Cristo **Augusto**.');
  assert.equal(f[0].titolo, null, 'un titolo che non c\'era non si aggiunge');
  assert.deepEqual(f[0].fonti, ['B1'], 'le fonti restano quelle del server');
  assert.equal(f[1].n, 1, 'la foto resta la sua');
  assert.equal(f[1].didascalia, 'Moneta d\'oro');
  assert.equal(f[1].credito, 'Disegno');
  assert.deepEqual(f[2].voci, ['Uno', 'Due bis', 'Tre'], 'una voce per riga, le righe vuote si tolgono');
  assert.deepEqual(f[2].fonti, ['A2']);
  assert.deepEqual(f[3].intestazione, ['Imperatore', 'Anni']);
  assert.deepEqual(f[3].righe, [['Caligola', '37-41 dopo Cristo']]);
  assert.deepEqual(f[4], { tipo: 'grafico', titolo: 'Sconosciuto' }, 'un blocco di tipo diverso non si tocca');
  assert.deepEqual(fuori.fonti, originale.fonti);
  assert.deepEqual(f.map((x) => x.tipo), ['paragrafo', 'immagine', 'punti', 'tabella', 'grafico']);
  // L'originale non si tocca.
  assert.equal(originale.sezioni[0].blocchi[0].testo, 'Nel 27 **Augusto**.');
});

test('correzione a mano del riassunto: stesse parti, titoli solo dove c\'erano, vuoti tolti', () => {
  const originale = { parti: [
    { titolo: 'Augusto', paragrafi: ['Uno.', 'Due.'], punti: ['a', 'b'] },
    { titolo: null, paragrafi: ['Tre.'], punti: [] },
  ] };
  const corretto = { parti: [
    { titolo: ' Augusto e Tiberio ', paragrafi: ['Uno, meglio.', '   '], punti: 'a\n\nc\n' },
    { titolo: 'Nuovo', paragrafi: ['Tre.'], punti: '' },
    { titolo: 'In più', paragrafi: ['Questa parte non esiste'] },
  ] };
  const fuori = L.contenutoRiassuntoCorretto(originale, corretto);
  assert.equal(fuori.parti.length, 2);
  assert.deepEqual(fuori.parti[0], { titolo: 'Augusto e Tiberio', paragrafi: ['Uno, meglio.'], punti: ['a', 'c'] });
  assert.deepEqual(fuori.parti[1], { titolo: null, paragrafi: ['Tre.'], punti: [] });
});

test('Lasciare le correzioni? solo se è cambiato davvero qualcosa (spazi in più non contano)', () => {
  const o = { sezioni: [{ n: 1, titolo: 'A', blocchi: [{ tipo: 'punti', titolo: null, voci: ['x', 'y'] }] }] };
  const uguale = JSON.parse(JSON.stringify(o));
  uguale.sezioni[0].blocchi[0].voci = 'x \n y\n';
  assert.equal(L.correzioniFatte('schema', o, 'Titolo', uguale, ' Titolo '), false);
  const diverso = JSON.parse(JSON.stringify(o));
  diverso.sezioni[0].blocchi[0].voci = 'x\nz';
  assert.equal(L.correzioniFatte('schema', o, 'Titolo', diverso, 'Titolo'), true);
  assert.equal(L.correzioniFatte('schema', o, 'Titolo', o, 'Titolo nuovo'), true);
  const r = { parti: [{ titolo: 'P', paragrafi: ['uno'], punti: [] }] };
  assert.equal(L.correzioniFatte('riassunto', r, 'T', { parti: [{ titolo: 'P', paragrafi: ['uno '], punti: '' }] }, 'T'), false);
  assert.equal(L.correzioniFatte('riassunto', r, 'T', { parti: [{ titolo: 'P', paragrafi: ['due'], punti: '' }] }, 'T'), true);
});

test('gli spunti entrano nel campo dopo un punto, e mai oltre i 600 caratteri', () => {
  assert.deepEqual(L.aggiungiSpuntoModifica('', 'Più corto'), { testo: 'Più corto', ok: true });
  assert.deepEqual(L.aggiungiSpuntoModifica('Più corto', 'Più semplice'), { testo: 'Più corto. Più semplice', ok: true });
  assert.deepEqual(L.aggiungiSpuntoModifica('Aggiungi le date.  ', 'Più dettagli'), { testo: 'Aggiungi le date. Più dettagli', ok: true });
  const lungo = 'a'.repeat(595);
  assert.deepEqual(L.aggiungiSpuntoModifica(lungo, 'Più corto'), { testo: lungo, ok: false });
  assert.deepEqual(L.contatoreRichiesta(''), { testo: '0 / 600', avviso: false });
  assert.deepEqual(L.contatoreRichiesta('a'.repeat(551)), { testo: '551 / 600', avviso: true });
  assert.equal(L.contatoreRichiesta('a'.repeat(550)).avviso, false);
  for (const tipo of ['schema', 'riassunto', 'lezione', 'simulata']) {
    assert.ok(L.MODIFICA_PER_TIPO[tipo].segnaposto.startsWith('Per esempio: '), tipo);
    assert.ok(L.MODIFICA_PER_TIPO[tipo].spunti.length >= 3, tipo);
  }
  assert.deepEqual(L.MODIFICA_PER_TIPO.schema.spunti, ['Aggiungi le date', 'Più semplice', 'Più dettagli', 'Più corto']);
});

test('"Fatto: ho cambiato le parti 2 e 3", "la parte 2"', () => {
  assert.equal(L.fraseCambiate([2, 3]), 'Fatto: ho cambiato le parti 2 e 3');
  assert.equal(L.fraseCambiate([2]), 'Fatto: ho cambiato la parte 2');
  assert.equal(L.fraseCambiate([4, 1, 2, 2]), 'Fatto: ho cambiato le parti 1, 2 e 4');
  assert.equal(L.fraseCambiate([]), 'Non ho cambiato niente.');
});

test('il pulsante della versione da parte segue il ruolo: prima → Torna a prima, dopo → Rimetti la modifica', () => {
  const prima = L.pulsanteVersione({ ruolo: 'prima', perche: 'modifica', richiesta: 'Aggiungi le date', quando: '2026-10-24T17:30:00+01:00' });
  assert.equal(prima.testo, 'Torna a prima');
  assert.equal(prima.domanda, 'Torno alla versione di prima?');
  assert.equal(prima.frase, 'Quella di adesso resta da parte.');
  const dopo = L.pulsanteVersione({ ruolo: 'dopo', perche: 'a_mano' });
  assert.equal(dopo.testo, 'Rimetti la modifica');
  assert.equal(dopo.domanda, 'Rimetto la versione modificata?');
  assert.equal(L.pulsanteVersione(null), null);
  assert.equal(L.pulsanteVersione({ ruolo: 'altro' }), null);
});

test('Modifica spenta con la sua frase: senza rete, modifica in corso, oggetto non pronto', () => {
  assert.equal(L.motivoSpentaModifica({ pronto: true, conParti: true }), null);
  assert.equal(L.motivoSpentaModifica({ senzaRete: true, pronto: true }), 'Serve la rete');
  assert.equal(L.motivoSpentaModifica({ pronto: true, modifica: { stato: 'in_coda' } }), 'C\'è già una modifica in corso');
  assert.equal(L.motivoSpentaModifica({ pronto: true, modifica: { stato: 'pronta' } }), null);
  assert.equal(L.motivoSpentaModifica({ pronto: false }), 'Adesso non si può modificare');
  assert.equal(L.motivoSpentaModifica({ pronto: true, conParti: false }), 'Adesso non si può modificare');
  assert.equal(L.modificaAttiva({ stato: 'in_preparazione' }), true);
  assert.equal(L.modificaAttiva({ stato: 'errore' }), false);
});

test('le parti da scegliere: quelle del server, altrimenti dal contenuto ("Parte 3" senza titolo)', () => {
  assert.deepEqual(L.partiModificabili({ parti_modificabili: [{ n: 2, titolo: 'Tiberio' }, { n: 3, titolo: null }] }, 'schema'), [{ n: 2, titolo: 'Tiberio' }, { n: 3, titolo: 'Parte 3' }]);
  assert.deepEqual(L.partiModificabili({ parti_modificabili: [] }, 'schema'), []);
  assert.deepEqual(L.partiModificabili({ contenuto: { parti: [{ titolo: 'A' }, { titolo: null }] } }, 'riassunto'), [{ n: 1, titolo: 'A' }, { n: 2, titolo: 'Parte 2' }]);
  assert.deepEqual(L.partiModificabili({ capitoli: [{ n: 1, titolo: 'Augusto' }] }, 'lezione'), [{ n: 1, titolo: 'Augusto' }]);
  assert.deepEqual(L.partiModificabili({ domande: [{ n: 1, testo: 'Che cos\'è il principato?' }] }, 'simulata'), [{ n: 1, titolo: 'Che cos\'è il principato?' }]);
});

test('il riepilogo: circa / fino a (di solito meno) / Prova, minuti e le righe per lezione e verifica corretta', () => {
  assert.equal(L.costoModifica({ costo_eur: 0.03, fino_a: false }, false), 'circa 0,03 €');
  assert.equal(L.costoModifica({ costo_eur: 0.15, fino_a: true }, false), 'fino a 0,15 € (di solito meno)');
  assert.equal(L.costoModifica({ costo_eur: 0.15 }, true), 'Prova');
  assert.equal(L.costoModifica(null, false), null);
  assert.deepEqual(L.righeRiepilogoModifica({ tipo: 'schema', stima: { minuti_preparazione: 3 } }), ['Pronta in circa 3 minuti']);
  assert.deepEqual(L.righeRiepilogoModifica({ tipo: 'lezione', stima: { minuti_preparazione: 1 } }), ['Pronta in circa 1 minuto', 'Intanto la lezione si ascolta com\'è adesso.']);
  assert.deepEqual(L.righeRiepilogoModifica({ tipo: 'simulata', stima: null, corretta: true }), ['Diventa una prova nuova: il voto di adesso resta in Torna a prima.']);
  assert.deepEqual(L.righeRiepilogoModifica({ tipo: 'simulata', stima: null, corretta: false }), []);
});

test('dopo una modifica della lezione: stesso punto se il capitolo non è cambiato, altrimenti il suo inizio', () => {
  const vecchi = [{ n: 1, inizio_s: 0, fine_s: 300 }, { n: 2, inizio_s: 300, fine_s: 600 }, { n: 3, inizio_s: 600, fine_s: 900 }];
  const nuovi = [{ n: 1, inizio_s: 0, fine_s: 320 }, { n: 2, inizio_s: 320, fine_s: 500 }, { n: 3, inizio_s: 500, fine_s: 800 }];
  assert.equal(L.puntoDopoModifica(vecchi, nuovi, [1], 450), 470, 'capitolo 2 uguale: 150 s dentro il capitolo, che ora parte da 320');
  assert.equal(L.puntoDopoModifica(vecchi, nuovi, [2], 450), 320, 'capitolo 2 cambiato: il suo inizio');
  assert.equal(L.puntoDopoModifica(vecchi, nuovi, [], 650), 550);
  assert.equal(L.puntoDopoModifica(vecchi, [{ n: 1, inizio_s: 0, fine_s: 100 }, { n: 2, inizio_s: 100, fine_s: 120 }, { n: 3, inizio_s: 120, fine_s: 200 }], [], 590), 119.5, 'mai oltre la fine del capitolo');
  assert.equal(L.puntoDopoModifica(vecchi, nuovi.slice(0, 2), [], 450), null, 'capitoli diversi: vale il punto del server');
});

// --- Schema ----------------------------------------------------------------------------------------

test('schema: Modifica a sinistra di Scarica PDF; la finestra chiede la stima, manda la richiesta e segue la modifica fino a pronta', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('.sch-parte'));
  const testa = Array.from(p.q('.sch-colonna .sch-testa').children).map((x) => x.getAttribute('data-azione') || x.className);
  assert.ok(testa.indexOf('modifica') >= 0 && testa.indexOf('modifica') === testa.indexOf('scarica-pdf') - 1, 'Modifica subito a sinistra di Scarica PDF: ' + testa.join(', '));
  const pillola = p.q('.sch-testa [data-azione="modifica"]');
  assert.equal(pillola.disabled, false);
  assert.ok(pillola.querySelector('svg.icona'));
  p.clic(pillola);
  await p.aspetta(() => p.q('.finestra-modifica'));
  assert.match(p.q('.finestra-modifica').textContent, /Cosa vuoi cambiare\?/);
  assert.match(p.q('[data-oggetto="titolo"]').textContent, /L'Impero romano/);
  assert.equal(p.q('[data-campo="richiesta"]').getAttribute('placeholder'), 'Per esempio: aggiungi le date delle crociate');
  assert.equal(p.q('[data-conta="richiesta"]').textContent, '0 / 600');
  assert.equal(p.q('[data-dove="tutto"]').getAttribute('aria-pressed'), 'true');
  assert.match(p.q('.mod-tutto').textContent, /Lectio sceglie da solo le parti che servono\./);
  assert.equal(p.q('[data-azione="manda-modifica"]').disabled, true, 'campo vuoto: spento');
  // Spunti: il secondo va dopo un punto.
  p.clic(p.q('[data-spunto="Aggiungi le date"]'));
  p.clic(p.q('[data-spunto="Più corto"]'));
  assert.equal(p.q('[data-campo="richiesta"]').value, 'Aggiungi le date. Più corto');
  // Scelgo io senza parti: spento.
  p.clic(p.q('[data-dove="io"]'));
  assert.equal(p.tutti('.mod-parte').length, 5);
  await B.dorme(60);
  assert.equal(p.q('[data-azione="manda-modifica"]').disabled, true, 'Scelgo io senza parti: spento');
  p.clic(p.q('.mod-parte[data-parte="2"]'));
  assert.equal(p.q('.mod-parte[data-parte="2"]').getAttribute('aria-checked'), 'true');
  await p.aspetta(() => !p.q('[data-azione="manda-modifica"]').disabled);
  const stime = p.s.chiamate('POST', '/server/api/modifiche/stima');
  assert.deepEqual(stime[stime.length - 1].corpo, { tipo: 'schema', id: 3, richiesta: 'Aggiungi le date. Più corto', parti: [2] });
  assert.equal(p.q('[data-riep="costo"]').textContent, 'circa 0,02 €');
  assert.match(p.q('[data-riepilogo="modifica"]').textContent, /Pronta in circa 2 minuti/);
  p.clic(p.q('[data-azione="manda-modifica"]'));
  const mandate = () => p.s.chiamate('POST', '/server/api/modifiche').filter((x) => x.percorso === '/server/api/modifiche');
  await p.aspetta(() => mandate().length === 1);
  assert.deepEqual(mandate()[0].corpo, { tipo: 'schema', id: 3, richiesta: 'Aggiungi le date. Più corto', parti: [2] });
  // La finestra si chiude, la scheda "Sto modificando" compare sotto la testata e la pillola si spegne.
  await p.aspetta(() => !p.q('.finestra-modifica') && p.q('[data-modifica="lavoro"]'));
  assert.match(p.q('[data-modifica="lavoro"]').textContent, /Sto modificando/);
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').disabled, true);
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').getAttribute('title'), 'C\'è già una modifica in corso');
  const correggi = await voceMenu(p, 'correggi-a-mano');
  assert.equal(correggi.disabled, true);
  assert.match(correggi.textContent, /C'è già una modifica in corso/);
  p.clic(p.q('.sch-testa [data-azione="puntini"]'));
  // Pronta: la pagina si ricarica da sola, avviso e parte 2 segnata "Cambiata".
  await p.aspetta(() => p.q('.sch-parte.mod-cambiata'), 4000);
  assert.equal(p.q('.sch-parte.mod-cambiata').getAttribute('data-parte'), '2');
  assert.match(p.q('.sch-parte.mod-cambiata').textContent, /Cambiata/);
  assert.match(p.q('.sch-parte.mod-cambiata').textContent, /Le date/);
  assert.equal(p.q('[data-modifica="lavoro"]'), null);
  assert.match(p.q('#avvisi').textContent, /Fatto: ho cambiato la parte 2/);
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').disabled, false);
  const torna = await voceMenu(p, 'scambia-versione');
  assert.match(torna.textContent, /Torna a prima/);
  assert.ok(torna.querySelector('svg.icona'), 'icona torna');
});

test('schema: Annulla la modifica con la conferma in linea (Sì / No)', opz, async () => {
  const p = await apri('#schema/3', { tempoModifica: 100000 });
  await p.aspetta(() => p.q('.sch-parte'));
  p.clic(p.q('.sch-testa [data-azione="modifica"]'));
  await chiedi(p, 'Più semplice');
  p.clic(p.q('[data-azione="manda-modifica"]'));
  await p.aspetta(() => p.q('[data-azione="annulla-modifica"]'));
  p.clic(p.q('[data-azione="annulla-modifica"]'));
  assert.match(p.q('[data-conferma="annulla-modifica"]').textContent, /Annullare la modifica\?/);
  p.clic(p.q('[data-azione="annulla-modifica-no"]'));
  assert.ok(p.q('[data-azione="annulla-modifica"]'));
  p.clic(p.q('[data-azione="annulla-modifica"]'));
  p.clic(p.q('[data-azione="annulla-modifica-si"]'));
  await p.aspetta(() => !p.q('[data-modifica]'));
  assert.equal(p.s.chiamate('POST', '/server/api/modifiche/').filter((x) => x.percorso.endsWith('/annulla')).length, 1);
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').disabled, false);
  // Nessun giro dopo l'annullamento.
  const giri = p.s.chiamate('GET', '/server/api/schemi/3').length;
  await B.dorme(150);
  assert.equal(p.s.chiamate('GET', '/server/api/schemi/3').length, giri);
});

test('schema: una modifica fermata mostra la frase, Riprova e Lascia stare; con la nota la scheda si chiude con la X', opz, async () => {
  const p = await apri('#schema/3', {}, (s) => {
    s.esempio.stato.modifiche.push({ id: 60, tipo: 'schema', oggetto_id: 3, materia_id: 1, richiesta: 'Più dettagli', parti: [], stato: 'errore', fase: null, fatto: 0, totale: 1, frase: null, errore: 'Il servizio che scrive i testi non ha risposto. Riprova tra poco.', nota: null, cambiate: [], stima_eur: 0.1, creata_il: '2026-10-27T09:00:00+01:00', pronta_il: null });
  });
  await p.aspetta(() => p.q('[data-modifica="errore"]'));
  assert.match(p.q('[data-modifica="errore"]').textContent, /non ha risposto/);
  assert.ok(p.q('[data-azione="lascia-modifica"]'));
  p.clic(p.q('[data-azione="riprova-modifica"]'));
  await p.aspetta(() => p.q('[data-modifica="lavoro"]'));
  assert.equal(p.s.chiamate('POST', '/server/api/modifiche/60/riprova').length, 1);
  // Finita con una nota: scheda avviso con la X, che si ricorda chiusa.
  p.s.esempio.stato.modifiche.find((x) => x.id === 60).richiesta = 'Aggiungi le date delle crociate';
  await p.aspetta(() => p.q('[data-modifica="nota"]'), 4000);
  assert.match(p.q('[data-modifica="nota"]').textContent, /crociate/);
  p.clic(p.q('[data-azione="chiudi-nota-modifica"]'));
  assert.equal(p.q('[data-modifica="nota"]'), null);
  p.A.disegna();
  await p.aspetta(() => p.q('.sch-parte'));
  await B.dorme(30);
  assert.equal(p.q('[data-modifica="nota"]'), null, 'chiusa resta chiusa');
});

test('schema: un errore del server lascia aperta la finestra con la frase sotto il pulsante', opz, async () => {
  const p = await apri('#schema/3');
  p.s.risposta = (metodo, percorso) => (metodo === 'POST' && percorso === '/server/api/modifiche' ? [402, { errore: 'Questo lavoro sforerebbe il tetto di 2 €.', codice: 'tetto_raggiunto' }] : undefined);
  await p.aspetta(() => p.q('.sch-parte'));
  p.clic(p.q('.sch-testa [data-azione="modifica"]'));
  await chiedi(p, 'Più dettagli');
  p.clic(p.q('[data-azione="manda-modifica"]'));
  await p.aspetta(() => p.q('[data-sotto="modifica"] .messaggio'));
  assert.ok(p.q('.finestra-modifica'));
  assert.match(p.q('[data-sotto="modifica"]').textContent, /sforerebbe il tetto/);
  assert.equal(p.q('[data-modifica]'), null);
});

test('stima non consentita: Modifica spento e sotto la frase del motivo', opz, async () => {
  const p = await apri('#schema/3', { speso: 1.99 });
  await p.aspetta(() => p.q('.sch-parte'));
  p.clic(p.q('.sch-testa [data-azione="modifica"]'));
  p.scrivi(await p.aspetta(() => p.q('[data-campo="richiesta"]')), 'Più dettagli');
  await p.aspetta(() => p.q('[data-motivo="modifica"]'));
  assert.match(p.q('[data-motivo="modifica"]').textContent, /tetto/);
  assert.equal(p.q('[data-azione="manda-modifica"]').disabled, true);
});

test('senza rete Modifica, Correggi a mano e Torna a prima sono spenti, con la frase', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('.sch-parte'));
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').disabled, false);
  // La rete va giù mentre la pagina è aperta: la pillola si spegne subito, senza ridisegnare.
  p.s.risposta = (m, percorso) => (percorso.startsWith('/server/') ? 'rete' : undefined);
  await p.A.api.get('/server/api/salute');
  assert.equal(p.A.S.senzaRete, true);
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').disabled, true);
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').getAttribute('title'), 'Serve la rete');
  const correggi = await voceMenu(p, 'correggi-a-mano');
  assert.equal(correggi.disabled, true);
  assert.equal(p.q('.tendina [data-azione="scambia-versione"]').disabled, true);
  assert.match(correggi.textContent, /Serve la rete/);
  p.clic(p.q('.sch-testa [data-azione="puntini"]'));
  // Torna la rete: si riaccende.
  p.s.risposta = null;
  await p.A.api.get('/server/api/salute');
  await p.aspetta(() => !p.q('.sch-testa [data-azione="modifica"]').disabled);
  assert.equal(p.q('.sch-testa [data-azione="modifica"]').getAttribute('title'), null);
});

test('schema: Correggi a mano manda solo i testi (voci una per riga) e torna in lettura con l\'avviso', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('.sch-parte'));
  const prima = JSON.parse(JSON.stringify(p.s.esempio.stato.schemi.find((x) => x.id === 3).contenuto));
  p.clic(await voceMenu(p, 'correggi-a-mano'));
  await p.aspetta(() => p.q('[data-barra="correggi"]'));
  assert.match(p.q('[data-barra="correggi"]').textContent, /Stai correggendo a mano/);
  // Niente indice, Non ho capito, fonti, menu delle foto, Ascolta questa parte, Modifica.
  for (const sel of ['.sch-indice', '[data-azione="non-ho-capito"]', '[data-segno="fonte"]', '.puntini-foto', '[data-azione="ascolta-parte"]', '[data-azione="modifica"]', '.sch-pillola-indice']) assert.equal(p.q(sel), null, sel);
  assert.ok(p.q('[data-correggi="schema"] [data-foto], [data-correggi="schema"] .sch-foto img'), 'le foto restano, solo da guardare');
  const campi = p.tutti('.mod-correggi-campo');
  assert.ok(campi.length > 20);
  // Il grassetto si vede come **parola**.
  const primo = p.tutti('[data-parte="1"] [data-tipo="paragrafo"] textarea')[0];
  assert.match(primo.value, /\*\*Augusto\*\*/);
  p.scrivi(primo, primo.value.replace('Nel 27 avanti Cristo', 'Nel 27 a.C.'));
  const voci = p.q('[data-parte="2"] [data-tipo="passaggi"] textarea');
  assert.equal(voci.value.split('\n').length, 4);
  p.scrivi(voci, voci.value + '\n\nNel 37 muore Tiberio\n');
  p.clic(p.q('[data-azione="salva-correzioni"]'));
  await p.aspetta(() => p.s.chiamate('PUT', '/server/api/schemi/3/testo').length === 1);
  const corpo = p.s.chiamate('PUT', '/server/api/schemi/3/testo')[0].corpo;
  assert.equal(corpo.titolo, 'L\'Impero romano da Augusto a Nerone');
  assert.match(corpo.contenuto.sezioni[0].blocchi[0].testo, /^Nel 27 a\.C\. Ottaviano riceve/);
  const passaggi = corpo.contenuto.sezioni[1].blocchi.find((b) => b.tipo === 'passaggi');
  assert.equal(passaggi.voci.length, 5);
  assert.equal(passaggi.voci[4], 'Nel 37 muore Tiberio');
  assert.deepEqual(corpo.contenuto.sezioni.map((z) => z.blocchi.map((b) => b.tipo)), prima.sezioni.map((z) => z.blocchi.map((b) => b.tipo)));
  assert.deepEqual(corpo.contenuto.fonti, prima.fonti);
  assert.deepEqual(corpo.contenuto.sezioni[0].blocchi[2], prima.sezioni[0].blocchi[2], 'la foto com\'era');
  assert.deepEqual(corpo.contenuto.sezioni[1].blocchi[2], prima.sezioni[1].blocchi[2], 'il blocco sconosciuto com\'era');
  await p.aspetta(() => !p.q('[data-barra="correggi"]') && p.q('.sch-parte'));
  assert.match(p.q('#avvisi').textContent, /Salvato\. Se serve, Torna a prima è nel menu\./);
  assert.match(p.q('.sch-parte[data-parte="1"]').textContent, /Nel 27 a\.C\./);
  assert.equal(p.A.S.guardia, null);
});

test('schema: Annulla, Esc e uscire dalla pagina con correzioni chiedono "Lasciare le correzioni?"', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('.sch-parte'));
  p.clic(await voceMenu(p, 'correggi-a-mano'));
  await p.aspetta(() => p.q('[data-barra="correggi"]'));
  // Senza cambiamenti Annulla esce subito.
  p.clic(p.q('[data-azione="annulla-correzioni"]'));
  await p.aspetta(() => !p.q('[data-barra="correggi"]'));
  assert.equal(p.q('.finestra'), null);
  p.clic(await voceMenu(p, 'correggi-a-mano'));
  await p.aspetta(() => p.q('[data-barra="correggi"]'));
  const t = p.q('.mod-correggi-campo.mod-titolo');
  p.scrivi(t, 'Un titolo nuovo');
  // Esc = Annulla: conferma; Continua resta.
  p.tasto('Escape', {}, p.d.body);
  await p.aspetta(() => p.q('.finestra'));
  assert.match(p.q('.finestra').textContent, /Lasciare le correzioni\?/);
  assert.ok(p.pulsante('Lascia') && p.pulsante('Continua'));
  p.clic(p.pulsante('Continua'));
  await p.aspetta(() => !p.q('.finestra'));
  assert.ok(p.q('[data-barra="correggi"]'));
  assert.equal(p.q('.mod-correggi-campo.mod-titolo').value, 'Un titolo nuovo');
  // Uscire dalla pagina: stessa domanda; Continua resta, Lascia va.
  p.A.vai('#oggi');
  await p.aspetta(() => p.q('.finestra'));
  p.clic(p.pulsante('Continua'));
  await p.aspetta(() => !p.q('.finestra'));
  assert.equal(p.w.location.hash, '#schema/3');
  assert.ok(p.q('[data-barra="correggi"]'));
  p.A.vai('#oggi');
  await p.aspetta(() => p.q('.finestra'));
  p.clic(p.q('[data-azione="conferma"]'));
  await p.aspetta(() => p.w.location.hash === '#oggi' && !p.q('[data-barra="correggi"]'));
  assert.equal(p.s.chiamate('PUT', '/server/api/schemi/3/testo').length, 0);
  assert.equal(p.A.S.guardia, null);
});

test('schema: Torna a prima chiede conferma, scambia e poi offre Rimetti la modifica', opz, async () => {
  const p = await apri('#schema/3');
  await p.aspetta(() => p.q('.sch-parte'));
  assert.match(p.q('.sch-parte[data-parte="2"]').textContent, /Nel 26 si ritira a Capri/);
  p.clic(await voceMenu(p, 'scambia-versione'));
  await p.aspetta(() => p.q('.finestra'));
  assert.match(p.q('.finestra').textContent, /Torno alla versione di prima\?/);
  assert.match(p.q('.finestra').textContent, /Quella di adesso resta da parte\./);
  p.clic(p.pulsante('Sì'));
  await p.aspetta(() => p.s.chiamate('POST', '/server/api/schemi/3/scambia-versione').length === 1);
  await p.aspetta(() => /Si ritira a Capri/.test(p.q('.sch-parte[data-parte="2"]').textContent));
  assert.match(p.q('#avvisi').textContent, /Fatto/);
  const rimetti = await voceMenu(p, 'scambia-versione');
  assert.match(rimetti.textContent, /Rimetti la modifica/);
});

// --- Riassunto -------------------------------------------------------------------------------------

test('riassunto più vecchio dello schema: la scheda lo dice e Rifallo usa la conferma di Rifai', opz, async () => {
  const p = await apri('#riassunto/5');
  await p.aspetta(() => p.q('[data-riassunto]'));
  assert.match(p.q('[data-vecchio]').textContent, /Lo schema è cambiato dopo questo riassunto\./);
  p.clic(p.q('[data-azione="rifallo"]'));
  await p.aspetta(() => p.q('.finestra'));
  assert.match(p.q('.finestra').textContent, /Lo riscrivo da capo\?/);
  p.A.disegna();
  const q = await apri('#riassunto/4');
  await q.aspetta(() => q.q('[data-riassunto]'));
  assert.equal(q.q('[data-vecchio]'), null);
  assert.ok(q.q('.sch-testa [data-azione="modifica"]'));
});

test('riassunto: Modifica e Correggi a mano (stesse parti, una voce per riga)', opz, async () => {
  const p = await apri('#riassunto/4');
  await p.aspetta(() => p.q('[data-riassunto]'));
  const testa = Array.from(p.q('.sch-testa').children).map((x) => x.getAttribute('data-azione') || x.className);
  assert.equal(testa.indexOf('modifica'), testa.indexOf('scarica-pdf') - 1);
  p.clic(p.q('.sch-testa [data-azione="modifica"]'));
  await p.aspetta(() => p.q('[data-campo="richiesta"]'));
  assert.equal(p.q('[data-campo="richiesta"]').getAttribute('placeholder'), 'Per esempio: più corto, solo le cose da sapere');
  assert.deepEqual(p.tutti('.mod-spunto').map((b) => b.textContent), ['Più corto', 'Più semplice', 'Più dettagli']);
  p.clic(p.q('[data-azione="chiudi-modifica"]'));
  await p.aspetta(() => !p.q('.finestra-modifica'));
  const voci = (await voceMenu(p, 'correggi-a-mano')).closest('.tendina');
  assert.deepEqual(Array.from(voci.children).map((b) => b.getAttribute('data-azione')), ['rifai-riassunto', 'correggi-a-mano', 'elimina-riassunto']);
  p.clic(p.q('.tendina [data-azione="correggi-a-mano"]'));
  await p.aspetta(() => p.q('[data-correggi="riassunto"]'));
  assert.equal(p.q('.rs-segmenti'), null, 'la barra delle forme si nasconde');
  const punti = p.q('[data-correggi="riassunto"] [data-parte="2"] [data-correggi-campo="voci"]');
  assert.equal(punti.value, 'Claudio crea uffici con i liberti.\nNel 43 conquista la **Britannia**.');
  p.scrivi(punti, punti.value + '\nMuore nel 54.');
  p.clic(p.q('[data-azione="salva-correzioni"]'));
  await p.aspetta(() => p.s.chiamate('PUT', '/server/api/riassunti/4/testo').length === 1);
  const corpo = p.s.chiamate('PUT', '/server/api/riassunti/4/testo')[0].corpo;
  assert.equal(corpo.contenuto.parti.length, 3);
  assert.deepEqual(corpo.contenuto.parti[1].punti, ['Claudio crea uffici con i liberti.', 'Nel 43 conquista la **Britannia**.', 'Muore nel 54.']);
  await p.aspetta(() => p.q('[data-riassunto]'));
  assert.match(p.q('[data-riassunto]').textContent, /Muore nel 54\./);
  const torna = await voceMenu(p, 'scambia-versione');
  assert.match(torna.textContent, /Torna a prima/);
});

// --- Verifica simulata -------------------------------------------------------------------------------

test('verifica corretta: Modifica e rifai, il riepilogo lo dice, e a modifica pronta torna una prova nuova con Torna a prima', opz, async () => {
  const p = await apri('#simulata/4');
  await p.aspetta(() => p.q('.sim-testata'));
  const rifai = p.q('[data-azione="rifai-simulata"]');
  const modifica = p.q('.sim-fondo [data-azione="modifica"]');
  assert.equal(modifica.textContent, 'Modifica e rifai');
  assert.equal(rifai.nextElementSibling, modifica, 'accanto a Rifai la verifica');
  p.clic(modifica);
  await p.aspetta(() => p.q('[data-campo="richiesta"]'));
  assert.match(p.q('[data-riepilogo="modifica"]').textContent, /Diventa una prova nuova: il voto di adesso resta in Torna a prima\./);
  p.clic(p.q('[data-spunto="Più domande aperte"]'));
  await p.aspetta(() => !p.q('[data-azione="manda-modifica"]').disabled);
  p.clic(p.q('[data-azione="manda-modifica"]'));
  await p.aspetta(() => p.q('[data-azione="inizia-simulata"]'), 4000);
  assert.ok(p.pulsante('Modifica le domande'));
  assert.match(p.q('[data-simulata="pronta"] [data-azione="scambia-versione"]').textContent, /Torna a prima/);
  assert.match(p.q('#avvisi').textContent, /Fatto: ho cambiato la parte 2/);
});

// --- Lettore -----------------------------------------------------------------------------------------

async function lettore(p) {
  p.A.vai('#lettore/12');
  await p.aspetta(() => p.audio && p.q('[data-azione="play"]') && !p.q('[data-azione="play"]').disabled);
  p.A.lettoreSalto(462, false);
}

async function modificaLezione(p, parti) {
  p.clic(p.q('[data-azione="menu-lettore"]'));
  p.clic(await p.aspetta(() => p.q('[data-azione="modifica-lezione"]')));
  await chiedi(p, 'Spiega meglio', parti);
  assert.match(p.q('[data-riepilogo="modifica"]').textContent, /Intanto la lezione si ascolta com'è adesso\./);
  p.clic(p.q('[data-azione="manda-modifica"]'));
  await p.aspetta(() => p.q('[data-modifica="lavoro"]'));
}

test('lettore: a modifica pronta e audio fermo la lezione si ricarica da sola, al punto giusto, coi capitoli cambiati segnati', opz, async () => {
  const p = await apri('#oggi');
  await lettore(p);
  await modificaLezione(p, [2]);
  assert.equal(p.s.chiamate('POST', '/locale/lezioni/12/scarica').length, 1);
  // Il capitolo 2 (dove si era, al secondo 462) è cambiato: si riparte dal suo inizio.
  await p.aspetta(() => p.s.chiamate('POST', '/locale/lezioni/12/scarica').length === 2, 4000);
  await p.aspetta(() => p.A.lettoreStato().id === 12 && p.audio && !p.A.lettore.caricando);
  assert.equal(p.A.lettoreStato().tempo, 301);
  await p.aspetta(() => p.q('.capitolo.mod-cambiata'));
  assert.equal(p.q('.capitolo.mod-cambiata').getAttribute('data-capitolo'), '2');
  assert.match(p.q('#avvisi').textContent, /Fatto: ho cambiato la parte 2/);
  const punti = p.s.chiamate('PUT', '/server/api/lezioni/12/ascolto');
  assert.equal(punti[punti.length - 1].corpo.posizione_s, 301, 'il punto nuovo va subito sul server');
  p.clic(p.q('[data-azione="menu-lettore"]'));
  assert.match((await p.aspetta(() => p.q('.tendina [data-azione="scambia-versione"]'))).textContent, /Torna a prima/);
});

test('lettore: se la lezione suona compare "Versione nuova pronta · Ricarica" e si ricarica solo col clic', opz, async () => {
  const p = await apri('#oggi');
  await lettore(p);
  p.clic(p.q('[data-azione="play"]'));
  assert.equal(p.audio.paused, false);
  await modificaLezione(p, [3]);
  await p.aspetta(() => p.q('[data-azione="ricarica-versione"]'), 4000);
  assert.match(p.q('[data-azione="ricarica-versione"]').textContent, /Versione nuova pronta · Ricarica/);
  assert.equal(p.s.chiamate('POST', '/locale/lezioni/12/scarica').length, 1, 'mentre suona non si ricarica');
  assert.equal(p.q('[data-modifica="lavoro"]'), null, 'la scheda "Sto modificando" se ne va subito');
  assert.equal(p.q('.capitolo.mod-cambiata'), null, 'CAMBIATA solo nella versione nuova');
  const t = p.A.lettoreStato().tempo;
  p.clic(p.q('[data-azione="ricarica-versione"]'));
  await p.aspetta(() => p.s.chiamate('POST', '/locale/lezioni/12/scarica').length === 2);
  await p.aspetta(() => p.A.lettoreStato().id === 12 && !p.A.lettore.caricando && p.A.lettoreStato().suona);
  // Il capitolo 2 non è cambiato (solo il 3): stesso punto.
  assert.ok(Math.abs(p.A.lettoreStato().tempo - t) < 0.2);
  assert.equal(p.q('[data-azione="ricarica-versione"]'), null);
  assert.equal((await p.aspetta(() => p.q('.capitolo.mod-cambiata'))).getAttribute('data-capitolo'), '3');
});

test('avviso di Windows modifica_pronta: la pagina che segue la modifica richiede subito e non ripete l\'avviso', opz, async () => {
  const p = await apri('#schema/3', { tempoModifica: 100000 }, null);
  p.A.T.modifica = 100000;
  await p.aspetta(() => p.q('.sch-parte'));
  p.clic(p.q('.sch-testa [data-azione="modifica"]'));
  await chiedi(p, 'Più semplice');
  p.clic(p.q('[data-azione="manda-modifica"]'));
  await p.aspetta(() => p.q('[data-modifica="lavoro"]'));
  p.s.esempio.stato.modifiche.find((x) => x.stato === 'in_coda' || x.stato === 'in_preparazione')._t0 = Date.now() - 200000;
  const prima = p.s.chiamate('GET', '/server/api/schemi/3').length;
  p.w.lectioNovita([{ tipo: 'modifica_pronta', testo: 'Ho modificato lo schema', materia_id: 1 }]);
  await p.aspetta(() => p.q('.sch-parte.mod-cambiata'));
  assert.ok(p.s.chiamate('GET', '/server/api/schemi/3').length > prima);
  assert.doesNotMatch(p.q('#avvisi').textContent, /Ho modificato lo schema/);
  assert.match(p.q('#avvisi').textContent, /Fatto: ho cambiato la parte 2/);
});
